"""알림 메시지 만들기 + 어제 가격과 비교하기."""

from __future__ import annotations

import json
from datetime import date
from pathlib import Path

from .prices import TripResult

WEEKDAYS = "월화수목금토일"
LEVELS = {"low": "🟢 평소보다 쌈", "typical": "🟡 보통", "high": "🔴 평소보다 비쌈"}


def won(n: int, currency: str = "KRW") -> str:
    if currency == "KRW":
        if n >= 10_000:
            man = n / 10_000
            return f"{man:,.1f}만원".replace(".0만", "만")
        return f"{n:,}원"
    return f"{n:,} {currency}"


def _d(d: date) -> str:
    return f"{d.month}/{d.day}({WEEKDAYS[d.weekday()]})"


def _ago(checked: date | None, today: date) -> str:
    if checked is None or checked >= today:
        return ""
    n = (today - checked).days
    return " (어제 확인)" if n == 1 else f" ({n}일 전 확인)"


def _delta(now: int | None, before: int | None, currency: str) -> str:
    if now is None or before is None or now == before:
        return ""
    diff = now - before
    arrow = "🔻" if diff < 0 else "🔺"
    return f" {arrow}{won(abs(diff), currency)}"


def trip_key_of(t) -> str:
    return f"{t.name}|{t.origin}-{t.destination}|{t.depart_from}~{t.depart_to}"


def trip_key(r: TripResult) -> str:
    return trip_key_of(r.trip)


def snapshot(results: list[TripResult]) -> dict:
    return {trip_key(r): {
        "flight": r.best_flight.price if r.best_flight else None,
        "hotel": r.best_hotel.total if r.best_hotel else None,
        "total": r.total,
    } for r in results}


def load_history(path: Path) -> dict:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}


def save_history(path: Path, history: dict, today: date, results: list[TripResult], keep_days: int = 60,
                 combos: dict | None = None) -> dict:
    """history: {날짜: 스냅샷, ..., "_combos": {여행키: 조합별 확인 결과}}"""
    history = dict(history)
    history[today.isoformat()] = snapshot(results)
    if combos is not None:
        history["_combos"] = combos
    days = sorted(k for k in history if not k.startswith("_"))
    for k in days[:-keep_days]:
        del history[k]
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(history, ensure_ascii=False, indent=1), encoding="utf-8")
    return history


def previous(history: dict, today: date) -> dict:
    days = [d for d in sorted(history) if not d.startswith("_") and d < today.isoformat()]
    return history[days[-1]] if days else {}


def build_message(results: list[TripResult], today: date, currency: str = "KRW",
                  prev: dict | None = None) -> str:
    prev = prev or {}
    lines = [f"✈️ {today.month}월 {today.day}일 ({WEEKDAYS[today.weekday()]}) 여행 가격 알림", ""]
    ranked = sorted(results, key=lambda r: (r.total is None, r.total or 0))
    for r in ranked:
        t = r.trip
        p = prev.get(trip_key(r), {})
        head = f"📍 {t.name} ({t.origin}→{t.destination.replace(',', '/')}) · {t.travelers.label()}"
        lines.append(head)
        f = r.best_flight
        if not f:
            lines.append("  항공권: 조회 결과 없음")
        else:
            nights = (f.inbound - f.outbound).days
            stops = "직항" if f.stops == 0 else f"경유 {f.stops}회"
            lines.append(f"  🗓 {_d(f.outbound)} → {_d(f.inbound)} ({nights}박){_ago(f.checked, today)}")
            if r.total_combos:
                lines.append(f"     날짜 조합 {r.total_combos}개 중 {r.checked_combos}개 확인 · 오늘 {r.searched}회 검색")
            lines.append(f"  🛫 {won(f.price, currency)}{_delta(f.price, p.get('flight'), currency)}"
                         f" · {', '.join(f.airlines)} {stops}")
            detail = " / ".join(x for x in f.flight_numbers if x)
            if f.depart_time:
                detail += f" · 출발 {f.depart_time[-5:]}"
            if detail.strip(" ·"):
                lines.append(f"     {detail.strip(' ·')}")
            if f.price_level in LEVELS:
                lines.append(f"     {LEVELS[f.price_level]}")
            others = r.flights[1:4]
            if others:
                lines.append("     다음으로 싼 날짜: " + ", ".join(
                    f"{_d(o.outbound)} {(o.inbound - o.outbound).days}박 {won(o.price, currency)}{_ago(o.checked, today)}"
                    for o in others))
        if t.hotel and f:
            h = r.best_hotel
            if h:
                lines.append(f"  🏨 {h.name}")
                lines.append(f"     {won(h.total, currency)}{_delta(h.total, p.get('hotel'), currency)}"
                             f" (1박 {won(h.per_night, currency)}" + (f", ⭐{h.rating}" if h.rating else "") + ")")
                for o in r.hotels[1:3]:
                    lines.append(f"     · {o.name} {won(o.total, currency)}")
            else:
                lines.append("  🏨 힐튼 계열 호텔: 조회 결과 없음")
        if r.total is not None and r.best_hotel:
            lines.append(f"  💰 합계 {won(r.total, currency)}{_delta(r.total, p.get('total'), currency)}")
        for e in r.errors:
            lines.append(f"  ⚠️ {e}")
        lines.append("")
    if not results:
        lines.append("설정된 여행이 없어요. travel.json 을 확인해 주세요.")
    lines.append("※ 항공권은 왕복·전체 인원 총액, 호텔은 전체 숙박 총액(세금 포함 여부는 구글 표시 기준)")
    return "\n".join(lines).strip()
