"""여행 알림 설정 (travel.json) 읽기."""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from datetime import date, timedelta
from pathlib import Path

CABINS = {"economy": 1, "premium": 2, "business": 3, "first": 4}
# SerpApi stops: 0=상관없음, 1=직항만, 2=1회 경유 이하, 3=2회 경유 이하
STOPS = {None: 0, 0: 1, 1: 2, 2: 3}


@dataclass
class Travelers:
    adults: int = 1
    children_ages: list[int] = field(default_factory=list)  # 만 2~11세 (항공 소아)
    infants: int = 0  # 만 2세 미만, 좌석 없음

    @property
    def children(self) -> int:
        return len(self.children_ages)

    def label(self) -> str:
        parts = [f"성인 {self.adults}"]
        if self.children:
            parts.append(f"아동 {self.children}")
        if self.infants:
            parts.append(f"유아 {self.infants}")
        return " · ".join(parts)


@dataclass
class Trip:
    name: str
    destination: str  # 공항 코드, 여러 개면 "HND,NRT"
    hotel_city: str  # 호텔 검색용 도시 이름 (영어 권장)
    depart_from: date
    depart_to: date
    nights: list[int]
    origin: str
    travelers: Travelers
    airlines: list[str] = field(default_factory=list)  # IATA 코드, 비우면 전체
    max_stops: int | None = None
    cabin: str = "economy"
    max_searches: int = 0  # 하루 항공 검색 횟수, 0 = 매일 모든 조합
    hotel: bool = True
    rooms: int = 1

    def all_combos(self, today: date) -> list[tuple[date, date]]:
        """기간 안의 모든 (출발일, 귀국일) 조합 (오늘 이후 출발만)."""
        combos = []
        d = max(self.depart_from, today + timedelta(days=1))
        while d <= self.depart_to:
            for n in self.nights:
                combos.append((d, d + timedelta(days=n)))
            d += timedelta(days=1)
        return combos

    def pick_today(self, today: date, cache: dict) -> list[tuple[date, date]]:
        """오늘 검색할 조합. 하루 횟수가 조합 수보다 적으면 돌아가며 검색 (안드로이드 앱과 같은 규칙).

        아직 안 본 조합이 있으면 한 바퀴 도는 것을 우선(4회 이상이면 최저가 1개는 다시 확인),
        다 본 뒤에는 최저가 조합을 다시 확인하고 나머지는 오래전에 본 순서로.
        """
        combos = self.all_combos(today)
        if self.max_searches <= 0 or self.max_searches >= len(combos):
            return combos
        known = sorted((c for c in combos if "price" in cache.get(combo_key(c), {})),
                       key=lambda c: cache[combo_key(c)]["price"])
        unseen = any(combo_key(c) not in cache for c in combos)
        refresh = (1 if self.max_searches >= 4 else 0) if unseen else min(2, max(1, self.max_searches // 2))
        order = known[:refresh]
        spread = [combos[i] for i in spread_order(len(combos)) if combos[i] not in order]
        pos = {c: i for i, c in enumerate(spread)}

        def age(c):  # 안 본 조합 → 오래전에 본 조합 순, 같으면 고르게 퍼지는 순
            checked = cache.get(combo_key(c), {}).get("checked")
            return (checked is not None, checked or "", pos[c])

        spread.sort(key=age)
        return (order + spread)[: self.max_searches]


def combo_key(c: tuple[date, date]) -> str:
    return f"{c[0].isoformat()}+{(c[1] - c[0]).days}"


def spread_order(n: int) -> list[int]:
    """0..n-1 을 기간 전체에 고르게 퍼지게 나열 (0, 끝, 가운데, 1/4, 3/4 ...)."""
    if n == 0:
        return []
    out, used = [0], {0}
    if n > 1:
        out.append(n - 1)
        used.add(n - 1)
    parts = 2
    while len(out) < n and parts < 4 * n:
        for k in range(1, parts, 2):
            idx = int(k * (n - 1) / parts + 0.5)
            if idx not in used:
                used.add(idx)
                out.append(idx)
        parts *= 2
    out += [i for i in range(n) if i not in used]
    return out


@dataclass
class Config:
    trips: list[Trip]
    currency: str = "KRW"


def _date(v: str) -> date:
    return date.fromisoformat(str(v))


def _travelers(raw: dict | None, default: Travelers | None = None) -> Travelers:
    if raw is None:
        return default or Travelers()
    t = Travelers(
        adults=int(raw.get("adults", 1)),
        children_ages=[int(a) for a in raw.get("children_ages", [])],
        infants=int(raw.get("infants", 0)),
    )
    if t.adults < 1:
        raise ValueError("성인은 최소 1명이어야 합니다")
    return t


def parse(raw: dict) -> Config:
    origin = raw.get("origin", "ICN")
    travelers = _travelers(raw.get("travelers"))
    defaults = raw.get("defaults", {})
    trips = []
    for t in raw.get("trips", []):
        t = {**defaults, **t}
        if t.get("enabled", True) is False:
            continue
        nights = t.get("nights", [4])
        nights = [int(n) for n in (nights if isinstance(nights, list) else [nights])]
        cabin = t.get("cabin", "economy")
        if cabin not in CABINS:
            raise ValueError(f"{t.get('name')}: cabin 은 {', '.join(CABINS)} 중 하나")
        max_stops = t.get("max_stops")
        if max_stops not in STOPS:
            raise ValueError(f"{t.get('name')}: max_stops 는 null, 0, 1, 2 중 하나")
        trip = Trip(
            name=t["name"],
            destination=t["destination"].replace(" ", "").upper(),
            hotel_city=t.get("hotel_city", t["name"]),
            depart_from=_date(t["depart_from"]),
            depart_to=_date(t.get("depart_to", t["depart_from"])),
            nights=nights,
            origin=t.get("origin", origin).replace(" ", "").upper(),
            travelers=_travelers(t.get("travelers"), travelers),
            airlines=[a.strip().upper() for a in t.get("airlines", []) if a.strip()],
            max_stops=max_stops,
            cabin=cabin,
            max_searches=max(0, int(t.get("max_searches", 0))),
            hotel=bool(t.get("hotel", True)),
            rooms=max(1, int(t.get("rooms", 1))),
        )
        if trip.depart_to < trip.depart_from:
            raise ValueError(f"{trip.name}: depart_to 가 depart_from 보다 빠릅니다")
        trips.append(trip)
    return Config(trips=trips, currency=raw.get("currency", "KRW"))


def load(path: str | Path) -> Config:
    with open(path, encoding="utf-8") as f:
        return parse(json.load(f))
