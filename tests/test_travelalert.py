import tempfile
import unittest
from datetime import date
from pathlib import Path

from travelalert import config as cfg
from travelalert.demo import DemoClient
from travelalert.notify import send_all, split_message
from travelalert.prices import is_hilton, parse_flights, parse_hotels, search_trip
from travelalert.report import build_message, load_history, previous, save_history, won

TODAY = date(2026, 11, 12)


def trip(**kw):
    raw = {"name": "도쿄", "destination": "hnd, nrt", "hotel_city": "Tokyo",
           "depart_from": "2027-01-15", "depart_to": "2027-01-20", "nights": [3, 4], **kw}
    return cfg.parse({"travelers": {"adults": 2}, "trips": [raw]}).trips[0]


FLIGHTS = {
    "best_flights": [{
        "price": 820000, "total_duration": 140,
        "flights": [{"airline": "대한항공", "flight_number": "KE 719",
                     "departure_airport": {"id": "ICN", "time": "2027-01-15 08:30"},
                     "arrival_airport": {"id": "NRT", "time": "2027-01-15 10:50"}}],
    }],
    "other_flights": [
        {"price": 610000, "total_duration": 300, "flights": [
            {"airline": "A", "flight_number": "A1", "departure_airport": {"time": "x 07:00"}},
            {"airline": "B", "flight_number": "B2", "arrival_airport": {"time": "x 13:00"}}]},
        {"price": None, "flights": []},
    ],
    "price_insights": {"price_level": "low", "typical_price_range": [700000, 900000]},
}

HOTELS = {"properties": [
    {"name": "Park Hyatt Tokyo", "total_rate": {"extracted_lowest": 100000}},
    {"name": "Conrad Tokyo", "rate_per_night": {"extracted_lowest": 400000}, "overall_rating": 4.6},
    {"name": "Hilton Tokyo", "rate_per_night": {"extracted_lowest": 250000},
     "total_rate": {"extracted_lowest": 1000000}},
    {"name": "Hilton Grand Vacations Apt", "type": "vacation rental", "rate_per_night": {"extracted_lowest": 1}},
]}


class ConfigTest(unittest.TestCase):
    def test_parse_defaults_and_normalize(self):
        t = trip(airlines=["ke", " oz "])
        self.assertEqual(t.destination, "HND,NRT")
        self.assertEqual(t.origin, "ICN")
        self.assertEqual(t.airlines, ["KE", "OZ"])
        self.assertEqual(t.travelers.adults, 2)

    def test_trip_overrides_travelers_and_disabled_trips_skipped(self):
        c = cfg.parse({"defaults": {"nights": 5}, "trips": [
            {"name": "a", "destination": "BKK", "depart_from": "2027-03-01",
             "travelers": {"adults": 1, "children_ages": [5, 9]}},
            {"name": "b", "destination": "DAD", "depart_from": "2027-03-01", "enabled": False}]})
        self.assertEqual(len(c.trips), 1)
        self.assertEqual(c.trips[0].nights, [5])
        self.assertEqual(c.trips[0].travelers.children, 2)

    def test_invalid_values(self):
        with self.assertRaises(ValueError):
            trip(cabin="vip")
        with self.assertRaises(ValueError):
            trip(depart_to="2027-01-01")

    def test_all_combos_cover_range_and_nights(self):
        t = trip()
        combos = t.all_combos(TODAY)
        self.assertEqual(len(combos), 12)  # 6일 x (3박, 4박)
        self.assertEqual(combos[0], (date(2027, 1, 15), date(2027, 1, 18)))
        self.assertEqual(combos[-1], (date(2027, 1, 20), date(2027, 1, 24)))
        self.assertEqual(t.all_combos(date(2027, 1, 19))[0][0], date(2027, 1, 20))
        self.assertEqual(t.all_combos(date(2027, 2, 1)), [])

    def test_spread_order(self):
        self.assertEqual(cfg.spread_order(5), [0, 4, 2, 1, 3])
        for n in range(1, 40):
            self.assertEqual(sorted(cfg.spread_order(n)), list(range(n)))

    def test_zero_means_all_every_day(self):
        self.assertEqual(len(trip(max_searches=0).pick_today(TODAY, {})), 12)
        self.assertEqual(len(trip().pick_today(TODAY, {})), 12)


class GridClient:
    """1/18 출발 4박이 제일 싼 가짜 항공 검색."""

    def __init__(self):
        self.keys = []

    def flights(self, trip, outbound, inbound):
        self.keys.append(cfg.combo_key((outbound, inbound)))
        n = (inbound - outbound).days
        price = 500_000 if (outbound.day, n) == (18, 4) else 700_000 + outbound.day * 1000 + n
        return {"best_flights": [{"price": price, "flights": [{"airline": "KE", "flight_number": "KE1"}]}]}

    def hotels(self, trip, a, b):
        return {"properties": []}


class RotationTest(unittest.TestCase):
    def test_rotation_finds_cheapest_combo(self):
        t = trip(max_searches=2, hotel=False)
        c, cache, seen = GridClient(), {}, set()
        for d in range(6):
            r = search_trip(c, t, date(2026, 11, 12 + d), cache)
            self.assertEqual(r.searched, 2)
        seen.update(c.keys)
        self.assertEqual(len(seen), 12)  # 6일 동안 12개 조합 전부 확인
        self.assertEqual((r.checked_combos, r.total_combos), (12, 12))
        self.assertEqual((r.best_flight.outbound, (r.best_flight.inbound - r.best_flight.outbound).days),
                         (date(2027, 1, 18), 4))
        self.assertEqual(r.best_flight.checked, date(2026, 11, 12 + c.keys.index("2027-01-18+4") // 2))
        c.keys.clear()
        search_trip(c, t, date(2026, 11, 18), cache)
        self.assertIn("2027-01-18+4", c.keys)  # 다 본 뒤에는 최저가 조합을 다시 확인

    def test_past_combos_dropped(self):
        t = trip(max_searches=0, hotel=False)
        cache = {}
        search_trip(GridClient(), t, TODAY, cache)
        self.assertEqual(len(cache), 12)
        search_trip(GridClient(), t, date(2027, 1, 19), cache)
        self.assertEqual(len(cache), 2)


class ParseTest(unittest.TestCase):
    def test_flights_sorted_and_skip_broken(self):
        fl = parse_flights(FLIGHTS, date(2027, 1, 15), date(2027, 1, 18))
        self.assertEqual([f.price for f in fl], [610000, 820000])
        self.assertEqual(fl[0].stops, 1)
        self.assertEqual(fl[0].airlines, ["A", "B"])
        self.assertEqual(fl[1].flight_numbers, ["KE 719"])
        self.assertEqual(fl[1].price_level, "low")

    def test_hotels_only_hilton_family(self):
        hs = parse_hotels(HOTELS, nights=4)
        self.assertEqual([h.name for h in hs], ["Hilton Tokyo", "Conrad Tokyo"])
        self.assertEqual(hs[0].per_night, 250000)
        self.assertEqual(hs[1].total, 1600000)  # 1박 요금 x 4박

    def test_hotels_multiple_rooms(self):
        self.assertEqual(parse_hotels(HOTELS, nights=4, rooms=2)[0].total, 2000000)

    def test_hilton_brands(self):
        for n in ["Waldorf Astoria Osaka", "DoubleTree by Hilton Seoul", "Hampton by Hilton Bangkok",
                  "Canopy by Hilton Seoul", "Embassy Suites Waikiki", "힐튼 나고야"]:
            self.assertTrue(is_hilton(n), n)
        for n in ["Marriott", "Park Hyatt", "Hotel Nikko"]:
            self.assertFalse(is_hilton(n), n)


class FakeClient:
    def __init__(self, fail_hotel=False):
        self.flight_calls = []
        self.fail_hotel = fail_hotel
        self.hotel_dates = None

    def flights(self, trip, outbound, inbound):
        self.flight_calls.append((outbound, inbound))
        if outbound.day == 17:
            raise RuntimeError("timeout")
        price = 1_000_000 - outbound.day * 10_000 - (inbound - outbound).days
        return {"best_flights": [{"price": price, "flights": [{"airline": "KE", "flight_number": "KE1"}]}]}

    def hotels(self, trip, check_in, check_out):
        if self.fail_hotel:
            raise RuntimeError("quota")
        self.hotel_dates = (check_in, check_out)
        return HOTELS


class SearchTest(unittest.TestCase):
    def test_search_picks_cheapest_and_hotel_for_those_dates(self):
        c = FakeClient()
        r = search_trip(c, trip(max_searches=12), TODAY)
        self.assertEqual(len(c.flight_calls), 12)
        self.assertEqual(r.best_flight.outbound, date(2027, 1, 20))
        self.assertEqual(c.hotel_dates, (r.best_flight.outbound, r.best_flight.inbound))
        self.assertEqual(r.total, r.best_flight.price + r.best_hotel.total)
        self.assertEqual(len(r.errors), 2)  # 17일 출발 2건 실패

    def test_hotel_failure_still_reports_flight(self):
        r = search_trip(FakeClient(fail_hotel=True), trip(), TODAY)
        self.assertIsNotNone(r.best_flight)
        self.assertIsNone(r.best_hotel)
        self.assertIn("호텔: quota", r.errors)
        self.assertIn("호텔: quota", build_message([r], TODAY))

    def test_demo_end_to_end(self):
        r = search_trip(DemoClient(TODAY), trip(), TODAY)
        self.assertTrue(r.best_flight and r.best_hotel)
        self.assertNotIn("Other", r.best_hotel.name)


class ReportTest(unittest.TestCase):
    def test_won(self):
        self.assertEqual(won(1_318_000), "131.8만원")
        self.assertEqual(won(500_000), "50만원")
        self.assertEqual(won(9_500), "9,500원")
        self.assertEqual(won(1200, "USD"), "1,200 USD")

    def test_history_and_price_change(self):
        t = trip()
        with tempfile.TemporaryDirectory() as d:
            path = Path(d) / "h.json"
            r1 = search_trip(FakeClient(), t, TODAY)
            h = save_history(path, load_history(path), date(2026, 11, 11), [r1])
            prev = previous(load_history(path), TODAY)
            r1.flights[0].price -= 30_000
            msg = build_message([r1], TODAY, prev=prev)
            self.assertIn("🔻3만원", msg)
            self.assertIn("Hilton Tokyo", msg)
            self.assertEqual(previous(h, date(2026, 11, 11)), {})

    def test_history_trimmed(self):
        with tempfile.TemporaryDirectory() as d:
            path = Path(d) / "h.json"
            h = {}
            for i in range(1, 6):
                h = save_history(path, h, date(2026, 11, i), [], keep_days=3)
            self.assertEqual(sorted(h), ["2026-11-03", "2026-11-04", "2026-11-05"])


class NotifyTest(unittest.TestCase):
    def test_split_message(self):
        text = "\n\n".join(["a" * 30] * 10)
        chunks = split_message(text, limit=100)
        self.assertTrue(all(len(c) <= 100 for c in chunks))
        self.assertEqual("\n\n".join(chunks), text)
        self.assertEqual(split_message("x" * 250, limit=100), ["x" * 100, "x" * 100, "x" * 50])

    def test_no_channels_configured(self):
        self.assertEqual(send_all("hi", "s", env={}), [])


if __name__ == "__main__":
    unittest.main()
