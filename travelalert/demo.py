"""API 키 없이 시험해 보는 가짜 가격 데이터 (--demo)."""

from __future__ import annotations

import hashlib
from datetime import date

from .config import Trip


def _n(*parts) -> int:
    h = hashlib.sha256("|".join(map(str, parts)).encode()).digest()
    return int.from_bytes(h[:4], "big")


class DemoClient:
    def __init__(self, today: date | None = None):
        self.today = today or date.today()
        self.calls = 0

    def flights(self, trip: Trip, outbound: date, inbound: date) -> dict:
        self.calls += 1
        people = trip.travelers.adults + trip.travelers.children
        base = 250_000 + _n(trip.destination) % 900_000
        items = []
        for i, airline in enumerate(trip.airlines or ["대한항공", "아시아나항공", "제주항공"]):
            price = (base + _n(trip.destination, outbound, inbound, airline, self.today) % 200_000) * people
            items.append({
                "price": price // 100 * 100,
                "total_duration": 120 + _n(trip.destination) % 600,
                "flights": [{
                    "airline": airline,
                    "flight_number": f"DEMO {100 + i * 7}",
                    "departure_airport": {"id": trip.origin, "time": f"{outbound} 0{8 + i}:30"},
                    "arrival_airport": {"id": trip.destination.split(",")[0], "time": f"{outbound} 1{2 + i}:10"},
                }],
            })
        return {"best_flights": items[:1], "other_flights": items[1:],
                "price_insights": {"price_level": "typical", "typical_price_range": [base * people, (base + 200_000) * people]}}

    def hotels(self, trip: Trip, check_in: date, check_out: date) -> dict:
        self.calls += 1
        nights = (check_out - check_in).days
        props = []
        for brand in ["Hilton", "Conrad", "DoubleTree by Hilton", "Hampton by Hilton"]:
            per = 120_000 + _n(trip.hotel_city, brand, check_in, self.today) % 400_000
            props.append({"name": f"{brand} {trip.hotel_city}", "rate_per_night": {"extracted_lowest": per},
                          "total_rate": {"extracted_lowest": per * nights}, "overall_rating": 4.3,
                          "hotel_class": "5-star hotel", "link": "https://www.hilton.com"})
        props.append({"name": f"Some Other Hotel {trip.hotel_city}", "rate_per_night": {"extracted_lowest": 50_000}})
        return {"properties": props}
