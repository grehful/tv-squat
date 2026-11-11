"""SerpApi(Google Flights / Google Hotels)로 항공권·힐튼 계열 호텔 가격 조회."""

from __future__ import annotations

import json
import urllib.parse
import urllib.request
from dataclasses import dataclass, field
from datetime import date

from .config import CABINS, STOPS, Trip

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


@dataclass
class Hotel:
    name: str
    total: int  # 전체 숙박 총액
    per_night: int
    rating: float | None
    hotel_class: str | None
    link: str | None


class SerpApiClient:
    def __init__(self, api_key: str, currency: str = "KRW", timeout: float = 60):
        self.api_key = api_key
        self.currency = currency
        self.timeout = timeout
        self.calls = 0

    def _get(self, params: dict) -> dict:
        params = {**params, "api_key": self.api_key, "currency": self.currency}
        url = API_URL + "?" + urllib.parse.urlencode(params)
        self.calls += 1
        with urllib.request.urlopen(url, timeout=self.timeout) as r:
            data = json.load(r)
        if data.get("error"):
            raise RuntimeError(data["error"])
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
            "sort_by": 3,  # 최저가순
            "hl": "en",  # 브랜드 이름 판별이 쉬운 영어로
            "gl": "kr",
        }
        if t.children and trip.rooms == 1:
            p["children"] = t.children
            p["children_ages"] = ",".join(str(a) for a in t.children_ages)
        return self._get(p)


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
    for p in data.get("properties") or []:
        name = p.get("name", "")
        if not is_hilton(name) or p.get("type") == "vacation rental":
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


def search_trip(client, trip: Trip, today: date) -> TripResult:
    res = TripResult(trip)
    for outbound, inbound in trip.date_options(today):
        try:
            flights = parse_flights(client.flights(trip, outbound, inbound), outbound, inbound)
        except Exception as e:  # noqa: BLE001 - 한 날짜 실패해도 나머지는 계속
            res.errors.append(f"항공 {outbound:%m/%d}: {e}")
            continue
        if flights:
            res.flights.append(flights[0])
    res.flights.sort(key=lambda f: f.price)
    best = res.best_flight
    if trip.hotel and best:
        nights = (best.inbound - best.outbound).days
        try:
            res.hotels = parse_hotels(client.hotels(trip, best.outbound, best.inbound), nights, trip.rooms)
        except Exception as e:  # noqa: BLE001
            res.errors.append(f"호텔: {e}")
    return res
