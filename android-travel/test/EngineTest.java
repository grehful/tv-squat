package com.grehful.travelalert;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

/** 안드로이드 없이 PC 자바로 돌리는 엔진 테스트: android-travel/test.sh */
public class EngineTest {
    static int failures;

    static void check(boolean ok, String what) {
        if (!ok) {
            failures++;
            System.out.println("FAIL: " + what);
        }
    }

    static void eq(Object a, Object b, String what) {
        check(String.valueOf(a).equals(String.valueOf(b)), what + " (" + a + " != " + b + ")");
    }

    public static void main(String[] args) throws Exception {
        // 날짜
        eq(Day.iso(Day.parse("2027-02-05")), "2027-02-05", "iso");
        eq(Day.label(Day.parse("2027-02-05")), "2/5(금)", "weekday");
        eq(Day.label(Day.parse("2024-02-29")), "2/29(목)", "leap");
        eq(Day.parse("2027-03-01") - Day.parse("2027-02-28"), 1, "month boundary");
        eq(Day.iso(Day.todayKst(1762956000000L)), "2025-11-12", "kst"); // 2025-11-12 14:00 UTC
        eq(Day.iso(Day.todayKst(1762970400000L)), "2025-11-13", "kst after midnight"); // 18:00 UTC = 03:00 KST

        // 설정 (저장소 travel.json)
        JSONObject root = new JSONObject(new String(Files.readAllBytes(Paths.get(args[0])), "UTF-8"));
        List<Trip> trips = Trip.parseAll(root);
        eq(trips.size(), 3, "enabled trips");
        Trip tokyo = trips.get(0);
        eq(tokyo.destination, "HND,NRT", "dest");
        eq(tokyo.adults, 2, "adults default");
        eq(tokyo.maxStops, 0, "defaults merged");
        eq(trips.get(2).childAges.length, 1, "trip travelers");
        int today = Day.parse("2026-11-12");
        List<int[]> opts = tokyo.dateOptions(today);
        eq(opts.size(), 2, "max searches");
        eq(Day.iso(opts.get(0)[0]) + "/" + Day.iso(opts.get(0)[1]), "2027-01-15/2027-01-18", "first combo");
        eq(Day.iso(opts.get(1)[0]) + "/" + Day.iso(opts.get(1)[1]), "2027-01-25/2027-01-29", "last combo");
        eq(tokyo.dateOptions(Day.parse("2027-02-01")).size(), 0, "past");

        // 잘못된 설정
        String[] bad = {
            "{\"trips\":[{\"name\":\"a\",\"destination\":\"X\",\"depart_from\":\"2027-01-05\",\"depart_to\":\"2027-01-01\"}]}",
            "{\"trips\":[{\"name\":\"a\",\"destination\":\"X\",\"depart_from\":\"2027-01-05\",\"cabin\":\"vip\"}]}",
            "{\"trips\":[{\"name\":\"a\",\"destination\":\"\",\"depart_from\":\"2027-01-05\"}]}",
            "{\"trips\":[{\"name\":\"a\",\"destination\":\"X\",\"depart_from\":\"2027-13-05\"}]}",
        };
        for (String b : bad) {
            try {
                Trip.parseAll(new JSONObject(b));
                check(false, "should reject " + b);
            } catch (org.json.JSONException expected) {
            }
        }
        Trip anyStops = Trip.parseAll(new JSONObject(
                "{\"trips\":[{\"name\":\"a\",\"destination\":\"X\",\"depart_from\":\"2027-01-05\",\"max_stops\":null,\"nights\":5}]}")).get(0);
        eq(anyStops.stopsCode(), 0, "any stops");
        eq(anyStops.nights[0], 5, "single nights");

        // SerpApi 응답 해석
        JSONObject fl = new JSONObject("{\"best_flights\":[{\"price\":820000,\"total_duration\":140,\"flights\":[{\"airline\":\"대한항공\",\"flight_number\":\"KE 719\",\"departure_airport\":{\"time\":\"2027-01-15 08:30\"}}]}],"
                + "\"other_flights\":[{\"price\":610000,\"flights\":[{\"airline\":\"A\",\"flight_number\":\"A1\"},{\"airline\":\"B\",\"flight_number\":\"B2\"}]},{\"price\":null,\"flights\":[]}],"
                + "\"price_insights\":{\"price_level\":\"low\"}}");
        List<Prices.Flight> fs = Prices.parseFlights(fl, 1, 4);
        eq(fs.size(), 2, "flights parsed");
        eq(fs.get(0).price, 610000, "sorted");
        eq(fs.get(0).stops, 1, "stops");
        eq(fs.get(1).departTime, "2027-01-15 08:30", "depart");
        eq(fs.get(1).priceLevel, "low", "level");

        JSONObject ho = new JSONObject("{\"properties\":[{\"name\":\"Park Hyatt Tokyo\",\"total_rate\":{\"extracted_lowest\":1}},"
                + "{\"name\":\"Conrad Tokyo\",\"rate_per_night\":{\"extracted_lowest\":400000}},"
                + "{\"name\":\"Hilton Tokyo\",\"rate_per_night\":{\"extracted_lowest\":250000},\"total_rate\":{\"extracted_lowest\":1000000}},"
                + "{\"name\":\"Hilton Vacation\",\"type\":\"vacation rental\",\"rate_per_night\":{\"extracted_lowest\":1}}]}");
        List<Prices.Hotel> hs = Prices.parseHotels(ho, 4, 1);
        eq(hs.size(), 2, "hilton only");
        eq(hs.get(0).name, "Hilton Tokyo", "cheapest hotel");
        eq(hs.get(1).total, 1600000, "per night x nights");
        eq(Prices.parseHotels(ho, 4, 2).get(0).total, 2000000, "rooms");
        check(Prices.isHilton("Waldorf Astoria Osaka") && Prices.isHilton("힐튼 나고야") && !Prices.isHilton("Marriott"), "brands");

        // 전체 실행 + 어제와 비교
        JSONObject history = new JSONObject();
        JSONObject r1 = Engine.run(new DemoClient(today - 1), trips, today - 1, history);
        JSONObject r2 = Engine.run(new DemoClient(today), trips, today, history);
        JSONArray t2 = r2.getJSONArray("trips");
        eq(t2.length(), 3, "results");
        eq(r2.getInt("calls"), 9, "calls");
        check(history.has("2026-11-11") && history.has("2026-11-12"), "history saved");
        for (int i = 0; i < t2.length(); i++) {
            JSONObject t = t2.getJSONObject(i);
            check(t.has("flight") && t.getJSONArray("hotels").length() == 3, "trip has flight+hotels " + t);
            check(t.getJSONObject("flight").opt("delta") instanceof Number, "delta vs yesterday");
            check(t.getString("flightsUrl").startsWith("https://www.google.com/travel/flights"), "url");
            if (i > 0) check(t2.getJSONObject(i - 1).getInt("total") <= t.getInt("total"), "sorted by total");
        }
        check(r1.getJSONArray("trips").getJSONObject(0).getJSONObject("flight").isNull("delta"), "no delta on first day");
        for (int d = 0; d < 70; d++) history.put(Day.iso(today - 100 + d), new JSONObject());
        Engine.trim(history);
        eq(history.length(), Engine.KEEP_DAYS, "trim");

        // 문구
        eq(Engine.won(1318000), "131.8만원", "won");
        eq(Engine.won(500000), "50만원", "won round");
        eq(Engine.won(9500), "9,500원", "won small");
        eq(Engine.deltaText(-30000), " 🔻3만원", "delta text");
        String[] s = Engine.summary(r2);
        check(s[0].startsWith("✈️ 11/12(목) 여행 가격 · "), "title " + s[0]);
        eq(s[1].split("\n").length, 3, "summary lines");
        if (args.length > 1) Files.write(Paths.get(args[1]), r2.put("at", 1762956000000L).put("demo", true).toString().getBytes("UTF-8"));

        if (failures > 0) {
            System.out.println(failures + " failures");
            System.exit(1);
        }
        System.out.println("OK");
    }
}
