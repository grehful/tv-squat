package com.grehful.travelalert;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

/** 안드로이드 없이 PC 자바로 돌리는 엔진 테스트: android-travel/test.sh */
public class EngineTest {
    /** 출발일·박수마다 가격이 다르고, 한 조합이 제일 싼 가짜 항공 검색. */
    static final class GridClient implements Prices.Client {
        final int cheapDay, cheapNights;
        final java.util.List<String> keys = new java.util.ArrayList<>();
        int calls;

        GridClient(int cheapDay, int cheapNights) {
            this.cheapDay = cheapDay;
            this.cheapNights = cheapNights;
        }

        public int calls() { return calls; }

        public JSONObject flights(Trip t, int out, int in) throws Exception {
            calls++;
            keys.add(Trip.comboKey(new int[]{out, in}));
            int price = (out == cheapDay && in - out == cheapNights) ? 500000 : 700000 + (out % 7) * 10000 + (in - out) * 1000;
            return new JSONObject().put("best_flights", new JSONArray().put(new JSONObject().put("price", price)
                    .put("flights", new JSONArray().put(new JSONObject().put("airline", "KE").put("flight_number", "KE 1")))));
        }

        public JSONObject hotels(Trip t, int a, int b) { return new JSONObject(); }
    }

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

    /** PC 안에 가짜 SerpApi 서버를 띄워서 실제 HTTP 호출 경로를 시험. */
    static void serpApiTests() throws Exception {
        final java.util.List<String> queries = new java.util.ArrayList<>();
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/search.json", new com.sun.net.httpserver.HttpHandler() {
            public void handle(com.sun.net.httpserver.HttpExchange ex) throws java.io.IOException {
                String q = java.net.URLDecoder.decode(ex.getRequestURI().getRawQuery(), "UTF-8");
                queries.add(q);
                String body;
                int code = 200;
                if (q.contains("arrival_id=NONE")) {
                    body = "{\"error\":\"Google Flights hasn't returned any results for this query.\"}";
                } else if (q.contains("arrival_id=BAD")) {
                    body = "{\"error\":\"Invalid API key.\"}";
                    code = 401;
                } else if (q.contains("brands=")) {
                    body = "{\"properties\":[{\"name\":\"The Curio Place\",\"rate_per_night\":{\"extracted_lowest\":150000}},"
                            + "{\"name\":\"Hilton Danang\",\"rate_per_night\":{\"extracted_lowest\":180000}}]}";
                } else if (q.contains("google_hotels")) {
                    body = "{\"brands\":[{\"id\":17,\"name\":\"Marriott\"},{\"id\":28,\"name\":\"Hilton Worldwide\",\"children\":[{\"id\":44,\"name\":\"DoubleTree\"}]}],"
                            + "\"properties\":[{\"name\":\"Cheap Hostel\",\"rate_per_night\":{\"extracted_lowest\":10000}},"
                            + "{\"name\":\"Hilton Danang\",\"rate_per_night\":{\"extracted_lowest\":190000}}]}";
                } else {
                    body = "{\"best_flights\":[{\"price\":300000,\"flights\":[{\"airline\":\"KE\"}]}]}";
                }
                byte[] b = body.getBytes("UTF-8");
                ex.sendResponseHeaders(code, b.length);
                ex.getResponseBody().write(b);
                ex.close();
            }
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/search.json";
        Trip t = Trip.parseAll(new JSONObject("{\"trips\":[{\"name\":\"다낭\",\"destination\":\"DAD\",\"hotel_city\":\"Da Nang\",\"depart_from\":\"2026-12-25\"}]}")).get(0);
        int d = Day.parse("2026-12-25");
        try {
            Prices.SerpApi api = new Prices.SerpApi("k", "KRW", null);
            api.baseUrl = base;
            api.retryDelayMs = 1;
            eq(Prices.parseFlights(api.flights(t, d, d + 5), d, d + 5).size(), 1, "flights via http");
            // 결과 없음은 오류가 아니라 빈 결과
            t.destination = "NONE";
            JSONObject none = api.flights(t, d, d + 5);
            eq(Prices.parseFlights(none, d, d + 5).size(), 0, "no results = empty");
            // API 오류는 다시 시도하지 않음
            t.destination = "BAD";
            int before = queries.size();
            try {
                api.flights(t, d, d + 5);
                check(false, "api error should throw");
            } catch (Prices.ApiError e) {
                eq(e.getMessage(), "Invalid API key.", "api error message");
            }
            eq(queries.size() - before, 1, "api error not retried");
            // 호텔: 처음엔 관련도순 검색 → 힐튼 브랜드 id 찾음 → 브랜드로 다시 최저가순 검색
            before = queries.size();
            java.util.List<Prices.Hotel> hs = Prices.parseHotels(api.hotels(t, d, d + 5), 5, 1);
            eq(queries.size() - before, 2, "hotel discovery = 2 calls");
            check(!queries.get(before).contains("sort_by"), "first hotel query by relevance " + queries.get(before));
            check(queries.get(before + 1).contains("brands=28,44") && queries.get(before + 1).contains("sort_by=3"), "brand query " + queries.get(before + 1));
            eq(api.hiltonBrands, "28,44", "brand ids");
            eq(hs.size(), 2, "brand filtered accepts all");
            eq(hs.get(0).name, "The Curio Place", "cheapest in brand");
            before = queries.size();
            api.hotels(t, d, d + 5);
            eq(queries.size() - before, 1, "known brands = 1 call");
        } finally {
            server.stop(0);
        }
        // 인터넷이 끊김: 3번 시도 후 실패, 그다음부터는 바로 건너뜀
        Prices.SerpApi off = new Prices.SerpApi("k", "KRW", null);
        off.baseUrl = base; // 서버가 꺼졌으니 연결 거부
        off.retryDelayMs = 1;
        t.destination = "DAD";
        try {
            off.flights(t, d, d + 5);
            check(false, "offline should throw");
        } catch (java.io.IOException e) {
            check(e.getMessage().contains("인터넷 연결"), "friendly offline message " + e.getMessage());
        }
        long start = System.currentTimeMillis();
        off.retryDelayMs = 5000;
        try {
            off.flights(t, d, d + 6);
        } catch (java.io.IOException expected) {
        }
        check(System.currentTimeMillis() - start < 3000, "fail fast once offline");
        // 호텔 응답이 1곳 상세로 올 때
        eq(Prices.parseHotels(new JSONObject("{\"name\":\"Conrad Osaka\",\"rate_per_night\":{\"extracted_lowest\":300000}}"), 2, 1).size(), 1, "single property");
        eq(Prices.hiltonBrandIds(new JSONObject("{\"brands\":[{\"id\":1,\"name\":\"Accor\"}]}")), null, "no hilton brand");
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
        // 날짜 조합: 기간 안 모든 출발일 x 박수
        List<int[]> all = tokyo.allCombos(today);
        eq(all.size(), 22, "all combos (11 days x 2)");
        eq(Day.iso(all.get(0)[0]) + "/" + Day.iso(all.get(0)[1]), "2027-01-15/2027-01-18", "first combo");
        eq(Day.iso(all.get(21)[0]) + "/" + Day.iso(all.get(21)[1]), "2027-01-25/2027-01-29", "last combo");
        eq(tokyo.allCombos(Day.parse("2027-01-24")).size(), 2, "past days dropped");
        eq(tokyo.allCombos(Day.parse("2027-02-01")).size(), 0, "past");
        eq(Trip.spreadOrder(5).toString(), "[0, 4, 2, 1, 3]", "spread");
        for (int n = 1; n < 40; n++) eq(new java.util.HashSet<>(Trip.spreadOrder(n)).size(), n, "spread covers " + n);

        // 하루 2회씩 돌아가며 확인 → 11일이면 모든 조합 확인, 진짜 최저가 조합을 찾는다
        tokyo = Trip.parseAll(root).get(0);
        tokyo.maxSearches = 2;
        tokyo.hotel = false;
        GridClient grid = new GridClient(Day.parse("2027-01-21"), 3); // 1/21 출발 3박이 제일 쌈
        JSONObject cache = new JSONObject();
        java.util.Set<String> seen = new java.util.HashSet<>();
        Engine.TripResult tr = null;
        for (int d = 0; d < 11; d++) {
            tr = Engine.search(grid, tokyo, today + d, cache);
            eq(tr.searched, 2, "2 searches per day");
            seen.addAll(grid.keys);
            grid.keys.clear();
        }
        eq(seen.size(), 22, "all combos covered in 11 days");
        eq(tr.checkedCombos + "/" + tr.totalCombos, "22/22", "coverage");
        eq(Day.iso(tr.best().outbound) + "+" + (tr.best().inbound - tr.best().outbound), "2027-01-21+3", "cheapest combo found");
        // 그다음 날은 최저가 조합을 다시 확인한다
        tr = Engine.search(grid, tokyo, today + 11, cache);
        check(grid.keys.contains("2027-01-21+3"), "refresh best " + grid.keys);
        // 0 = 매일 전부
        tokyo.maxSearches = 0;
        grid.keys.clear();
        tr = Engine.search(grid, tokyo, today, new JSONObject());
        eq(grid.keys.size(), 22, "all every day");
        eq(Day.iso(tr.best().outbound), "2027-01-21", "best when all");
        // 기간이 줄면 지난 조합은 지워진다
        Engine.search(grid, tokyo, Day.parse("2027-01-24"), cache);
        eq(cache.length(), 2, "cache pruned");

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
        for (Trip t : trips) t.maxSearches = 2;
        JSONObject combos = new JSONObject();
        JSONObject r1 = Engine.run(new DemoClient(today - 1), trips, today - 1, history, combos);
        JSONObject r2 = Engine.run(new DemoClient(today), trips, today, history, combos);
        eq(combos.length(), 3, "combo cache per trip");
        JSONArray t2 = r2.getJSONArray("trips");
        eq(t2.length(), 3, "results");
        eq(r2.getInt("calls"), 9, "calls");
        check(history.has("2026-11-11") && history.has("2026-11-12"), "history saved");
        for (int i = 0; i < t2.length(); i++) {
            JSONObject t = t2.getJSONObject(i);
            check(t.has("flight") && t.getJSONArray("hotels").length() == 3, "trip has flight+hotels " + t);
            check(t.getJSONObject("flight").opt("delta") instanceof Number, "delta vs yesterday");
            check(t.getInt("combosChecked") == 4 && t.getInt("combosTotal") > 4, "coverage after 2 days " + t.getInt("combosChecked"));
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

        serpApiTests();

        if (failures > 0) {
            System.out.println(failures + " failures");
            System.exit(1);
        }
        System.out.println("OK");
    }
}
