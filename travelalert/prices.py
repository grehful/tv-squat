"""SerpApi(Google Flights / Google Hotels)로 항공권·힐튼 계열 호텔 가격 조회."""

from __future__ import annotations

import json
import urllib.parse
import time
import urllib.error
import urllib.request
from dataclasses import dataclass, field
from datetime import date

from .config import CABINS, STOPS, Trip, combo_key

API_URL = "https://serpapi.com/search.json"

# 힐튼 계열 브랜드 (호텔 이름에 들어가는 단어로 판별, 소문자)
HILTON_KEYWORDS = [
    "hilton", "waldorf astoria", "conrad", "lxr hotels", "canopy by hilton",
    "signia by hilton", "curio collection", "doubletree", "tapestry collection",
    "embassy suites", "tempo by hilton", "motto by hilton", "hampton by hilton",
    "hampton inn", "tru by hilton", "homewood suites", "home2 suites",
    "spark by hilton", "livsmart studios", "graduate by hilton",
    # 한국어 결과 대비
    "힐튼", "월도프", "콘래드", "더블트리", "햄튼", "캐노피", "큐리오",
]


def is_hilton(name: str) -> bool:
    n = name.lower()
    return any(k in n for k in HILTON_KEYWORDS)


@dataclass
class Flight:
    price: int  # 왕복 총액 (전체 인원)
    airlines: list[str]
    flight_numbers: list[str]
    stops: int
    duration_min: int
    depart_time: str
    arrive_time: str
    outbound: date
    inbound: date
    price_level: str | None = None  # low / typical / high (구글 판단)
    typical_range: list[int] = field(default_factory=list)
    checked: date | None = None  # 이 가격을 확인한 날


@dataclass
class Hotel:
    name: str
    total: int  # 전체 숙박 총액
    per_night: int
    rating: float | None
    hotel_class: str | None
    link: str | None


BRAND_FILTERED = "_hilton_brand_filtered"


class ApiError(RuntimeError):
    """SerpApi 가 돌려준 오류 (인터넷 문제가 아니므로 다시 시도하지 않음)."""


def hilton_brand_ids(data: dict) -> str | None:
    """Google Hotels 응답의 brands 목록에서 힐튼 계열 브랜드 id 들 ("1,2,3")."""
    ids: list[str] = []

    def walk(brands, parent_hilton):
        for b in brands or []:
            if not isinstance(b, dict):
                continue
            hilton = parent_hilton or is_hilton(b.get("name", ""))
            if hilton and "id" in b and str(b["id"]) not in ids:
                ids.append(str(b["id"]))
            walk(b.get("children"), hilton)

    walk(data.get("brands"), False)
    return ",".join(ids) or None


class SerpApiClient:
    def __init__(self, api_key: str, currency: str = "KRW", timeout: float = 60,
                 hilton_brands: str | None = None, base_url: str = API_URL, retry_delay: float = 4):
        self.api_key = api_key
        self.currency = currency
        self.timeout = timeout
        self.calls = 0
        self.hilton_brands = hilton_brands  # 한 번 찾으면 기록에 저장해 두고 계속 씀
        self.base_url = base_url
        self.retry_delay = retry_delay
        self.offline = False  # 인터넷이 끊긴 게 확인되면 남은 검색은 기다리지 않고 건너뜀

    def _get(self, params: dict) -> dict:
        """인터넷이 잠깐 끊긴 경우는 조금 기다렸다가 두 번 더 시도한다."""
        last = None
        for attempt in range(1 if self.offline else 3):
            if attempt:
                time.sleep(self.retry_delay * attempt)
            try:
                data = self._get_once(params)
                self.offline = False
                return data
            except ApiError:
                self.offline = False
                raise
            except (OSError, ValueError) as e:  # URLError, 시간 초과, 깨진 응답
                last = e
        self.offline = True
        raise RuntimeError(f"인터넷 연결 문제로 SerpApi 에 접속하지 못했어요 (다음 검색 때 다시 확인): {last}")

    def _get_once(self, params: dict) -> dict:
        params = {**params, "api_key": self.api_key, "currency": self.currency}
        url = self.base_url + "?" + urllib.parse.urlencode(params)
        try:
            with urllib.request.urlopen(url, timeout=self.timeout) as r:
                self.calls += 1
                data = json.load(r)
        except urllib.error.HTTPError as e:
            self.calls += 1
            try:
                data = json.load(e)
            except ValueError:
                data = {}
            if e.code >= 500 and not data.get("error"):
                raise OSError(f"SerpApi 서버 오류 (HTTP {e.code})") from e
            if not data.get("error"):
                raise ApiError(f"HTTP {e.code}") from e
        error = data.get("error") or ""
        # 조건에 맞는 항공편/호텔이 없을 때도 error 로 온다 → 오류가 아니라 '결과 없음'
        if "hasn't returned any results" in error:
            return {"no_results": True}
        if error:
            raise ApiError(error)
        return data

    def flights(self, trip: Trip, outbound: date, inbound: date) -> dict:
        p = {
            "engine": "google_flights",
            "departure_id": trip.origin,
            "arrival_id": trip.destination,
            "outbound_date": outbound.isoformat(),
            "return_date": inbound.isoformat(),
            "type": 1,  # 왕복
            "adults": trip.travelers.adults,
            "children": trip.travelers.children,
            "infants_on_lap": trip.travelers.infants,
            "travel_class": CABINS[trip.cabin],
            "stops": STOPS[trip.max_stops],
            "hl": "ko",
            "gl": "kr",
        }
        if trip.airlines:
            p["include_airlines"] = ",".join(trip.airlines)
        return self._get(p)

    def hotels(self, trip: Trip, check_in: date, check_out: date) -> dict:
        t = trip.travelers
        p = {
            "engine": "google_hotels",
            "q": f"Hilton hotels in {trip.hotel_city}",
            "check_in_date": check_in.isoformat(),
            "check_out_date": check_out.isoformat(),
            # 방 1개 기준 인원 (방 여러 개면 나눠서 검색 후 곱함)
            "adults": max(1, -(-t.adults // trip.rooms)),
            "hl": "en",  # 브랜드 이름 판별이 쉬운 영어로
            "gl": "kr",
        }
        if t.children and trip.rooms == 1:
            p["children"] = t.children
            p["children_ages"] = ",".join(str(a) for a in t.children_ages)
        if self.hilton_brands:
            return self._brand_search(p, trip)
        # 브랜드 id 를 아직 모르면 관련도순으로 검색 (최저가순이면 싼 일반 숙소가 첫 페이지를 채워 힐튼이 빠짐)
        # → 응답의 brands 목록에서 힐튼 id 를 찾아 힐튼 계열만 최저가순으로 다시 검색
        first = self._get(p)
        ids = hilton_brand_ids(first)
        if not ids:
            return first
        self.hilton_brands = ids
        try:
            by_brand = self._brand_search(p, trip)
        except (RuntimeError, OSError):
            return first
        return by_brand if parse_hotels(by_brand, 1) else first

    def _brand_search(self, p: dict, trip: Trip) -> dict:
        p = {**p, "q": f"hotels in {trip.hotel_city}", "brands": self.hilton_brands, "sort_by": 3}
        data = self._get(p)
        data[BRAND_FILTERED] = True
        return data


def parse_flights(data: dict, outbound: date, inbound: date) -> list[Flight]:
    insights = data.get("price_insights") or {}
    out = []
    for item in (data.get("best_flights") or []) + (data.get("other_flights") or []):
        price = item.get("price")
        legs = item.get("flights") or []
        if not isinstance(price, (int, float)) or not legs:
            continue
        airlines = []
        for leg in legs:
            a = leg.get("airline")
            if a and a not in airlines:
                airlines.append(a)
        out.append(Flight(
            price=int(price),
            airlines=airlines,
            flight_numbers=[leg.get("flight_number", "") for leg in legs],
            stops=len(legs) - 1,
            duration_min=int(item.get("total_duration") or 0),
            depart_time=(legs[0].get("departure_airport") or {}).get("time", ""),
            arrive_time=(legs[-1].get("arrival_airport") or {}).get("time", ""),
            outbound=outbound,
            inbound=inbound,
            price_level=insights.get("price_level"),
            typical_range=list(insights.get("typical_price_range") or []),
        ))
    out.sort(key=lambda f: f.price)
    return out


def _num(v) -> int | None:
    if isinstance(v, dict):
        v = v.get("extracted_lowest")
    return int(v) if isinstance(v, (int, float)) else None


def parse_hotels(data: dict, nights: int, rooms: int = 1) -> list[Hotel]:
    out = []
    props = data.get("properties")
    if props is None and data.get("name"):
        props = [data]  # 호텔 1곳 상세만 나온 경우
    brand_filtered = data.get(BRAND_FILTERED, False)
    for p in props or []:
        name = p.get("name", "")
        if p.get("type") == "vacation rental" or not (brand_filtered or is_hilton(name)):
            continue
        per_night = _num(p.get("rate_per_night"))
        total = _num(p.get("total_rate"))
        if per_night is None and total is None:
            continue
        if total is None:
            total = per_night * nights
        if per_night is None:
            per_night = round(total / max(nights, 1))
        out.append(Hotel(
            name=name,
            total=total * rooms,
            per_night=per_night * rooms,
            rating=p.get("overall_rating"),
            hotel_class=p.get("hotel_class"),
            link=p.get("link"),
        ))
    out.sort(key=lambda h: h.total)
    return out


@dataclass
class TripResult:
    trip: Trip
    flights: list[Flight] = field(default_factory=list)  # 날짜 조합별 최저가, 싼 순
    hotels: list[Hotel] = field(default_factory=list)  # 최저가 항공 날짜 기준
    errors: list[str] = field(default_factory=list)
    searched: int = 0
    checked_combos: int = 0
    total_combos: int = 0
    empty_combos: int = 0

    @property
    def best_flight(self) -> Flight | None:
        return self.flights[0] if self.flights else None

    @property
    def best_hotel(self) -> Hotel | None:
        return self.hotels[0] if self.hotels else None

    @property
    def total(self) -> int | None:
        if not self.best_flight:
            return None
        return self.best_flight.price + (self.best_hotel.total if self.best_hotel else 0)


def flight_to_cache(f: Flight, today: date) -> dict:
    return {"price": f.price, "airlines": f.airlines, "numbers": f.flight_numbers, "stops": f.stops,
            "duration": f.duration_min, "depart": f.depart_time, "arrive": f.arrive_time,
            "level": f.price_level, "range": f.typical_range, "checked": today.isoformat()}


def flight_from_cache(e: dict, outbound: date, inbound: date) -> Flight:
    return Flight(price=e["price"], airlines=e.get("airlines", []), flight_numbers=e.get("numbers", []),
                  stops=e.get("stops", 0), duration_min=e.get("duration", 0), depart_time=e.get("depart", ""),
                  arrive_time=e.get("arrive", ""), outbound=outbound, inbound=inbound,
                  price_level=e.get("level"), typical_range=e.get("range", []),
                  checked=date.fromisoformat(e["checked"]) if e.get("checked") else None)


def search_trip(client, trip: Trip, today: date, cache: dict | None = None) -> TripResult:
    """기간 안 모든 (출발일, 박수) 조합 중 최저가를 찾는다.

    cache 는 이 여행의 조합별 최근 확인 결과이며 오늘 검색한 조합이 갱신된다(지난 조합은 지워짐).
    """
    cache = {} if cache is None else cache
    res = TripResult(trip)
    combos = trip.all_combos(today)
    for outbound, inbound in trip.pick_today(today, cache):
        key = combo_key((outbound, inbound))
        try:
            flights = parse_flights(client.flights(trip, outbound, inbound), outbound, inbound)
        except Exception as e:  # noqa: BLE001 - 한 날짜 실패해도 나머지는 계속
            res.errors.append(f"항공 {outbound.month}/{outbound.day} {(inbound - outbound).days}박: {e}")
            continue
        res.searched += 1
        cache[key] = flight_to_cache(flights[0], today) if flights else {"none": True, "checked": today.isoformat()}
    valid = {combo_key(c): c for c in combos}
    for k in list(cache):
        if k not in valid:
            del cache[k]
    res.checked_combos = len(cache)
    res.empty_combos = sum(1 for e in cache.values() if e.get("none"))
    res.total_combos = len(combos)
    res.flights = sorted((flight_from_cache(e, *valid[k]) for k, e in cache.items() if "price" in e),
                         key=lambda f: f.price)
    best = res.best_flight
    if trip.hotel and best:
        nights = (best.inbound - best.outbound).days
        try:
            res.hotels = parse_hotels(client.hotels(trip, best.outbound, best.inbound), nights, trip.rooms)
        except Exception as e:  # noqa: BLE001
            res.errors.append(f"호텔: {e}")
    if not combos:
        res.errors.append("출발 기간이 지났어요")
    return res
