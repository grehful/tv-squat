package com.grehful.travelalert;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** SerpApi(Google Flights / Google Hotels) 호출과 결과 해석. */
final class Prices {
    private Prices() {}

    interface Client {
        JSONObject flights(Trip trip, int outbound, int inbound) throws Exception;

        JSONObject hotels(Trip trip, int checkIn, int checkOut) throws Exception;

        int calls();
    }

    // 힐튼 계열 브랜드 (호텔 이름에 들어가는 단어, 소문자)
    static final String[] HILTON_KEYWORDS = {
        "hilton", "waldorf astoria", "conrad", "lxr hotels", "canopy by hilton",
        "signia by hilton", "curio collection", "doubletree", "tapestry collection",
        "embassy suites", "tempo by hilton", "motto by hilton", "hampton by hilton",
        "hampton inn", "tru by hilton", "homewood suites", "home2 suites",
        "spark by hilton", "livsmart studios", "graduate by hilton",
        "힐튼", "월도프", "콘래드", "더블트리", "햄튼", "캐노피", "큐리오",
    };

    static boolean isHilton(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        for (String k : HILTON_KEYWORDS) if (n.contains(k)) return true;
        return false;
    }

    static final class Flight {
        int price; // 왕복 총액(전체 인원)
        List<String> airlines = new ArrayList<>();
        List<String> numbers = new ArrayList<>();
        int stops, durationMin;
        String departTime = "";
        int outbound, inbound;
        String priceLevel;
        int checked = -1; // 이 가격을 확인한 날 (Day)

        JSONObject toJson() throws JSONException {
            return new JSONObject().put("price", price).put("airlines", new JSONArray(airlines))
                    .put("numbers", new JSONArray(numbers)).put("stops", stops).put("durationMin", durationMin)
                    .put("departTime", departTime).put("level", priceLevel == null ? JSONObject.NULL : priceLevel)
                    .put("checked", checked);
        }

        static Flight fromJson(JSONObject o, int outbound, int inbound) {
            Flight f = new Flight();
            f.price = o.optInt("price");
            JSONArray a = o.optJSONArray("airlines"), n = o.optJSONArray("numbers");
            for (int i = 0; a != null && i < a.length(); i++) f.airlines.add(a.optString(i));
            for (int i = 0; n != null && i < n.length(); i++) f.numbers.add(n.optString(i));
            f.stops = o.optInt("stops");
            f.durationMin = o.optInt("durationMin");
            f.departTime = o.optString("departTime", "");
            f.priceLevel = o.isNull("level") ? null : o.optString("level", null);
            f.checked = o.optInt("checked", -1);
            f.outbound = outbound;
            f.inbound = inbound;
            return f;
        }
    }

    static final Comparator<Flight> BY_PRICE = new Comparator<Flight>() {
        @Override
        public int compare(Flight a, Flight b) { return Integer.compare(a.price, b.price); }
    };

    static final class Hotel {
        String name, link, hotelClass;
        int total, perNight;
        double rating;
    }

    static List<Flight> parseFlights(JSONObject data, int outbound, int inbound) {
        JSONObject insights = data.optJSONObject("price_insights");
        List<Flight> out = new ArrayList<>();
        for (String key : new String[]{"best_flights", "other_flights"}) {
            JSONArray arr = data.optJSONArray(key);
            if (arr == null) continue;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject item = arr.optJSONObject(i);
                if (item == null || !(item.opt("price") instanceof Number)) continue;
                JSONArray legs = item.optJSONArray("flights");
                if (legs == null || legs.length() == 0) continue;
                Flight f = new Flight();
                f.price = item.optInt("price");
                for (int j = 0; j < legs.length(); j++) {
                    JSONObject leg = legs.optJSONObject(j);
                    if (leg == null) continue;
                    String a = leg.optString("airline", "");
                    if (!a.isEmpty() && !f.airlines.contains(a)) f.airlines.add(a);
                    String no = leg.optString("flight_number", "");
                    if (!no.isEmpty()) f.numbers.add(no);
                }
                JSONObject dep = legs.optJSONObject(0) == null ? null : legs.optJSONObject(0).optJSONObject("departure_airport");
                if (dep != null) f.departTime = dep.optString("time", "");
                f.stops = legs.length() - 1;
                f.durationMin = item.optInt("total_duration", 0);
                f.outbound = outbound;
                f.inbound = inbound;
                f.priceLevel = insights == null ? null : insights.optString("price_level", null);
                out.add(f);
            }
        }
        Collections.sort(out, BY_PRICE);
        return out;
    }

    private static Integer amount(Object v) {
        if (v instanceof JSONObject) v = ((JSONObject) v).opt("extracted_lowest");
        return v instanceof Number ? ((Number) v).intValue() : null;
    }

    /** 힐튼 브랜드 필터로 검색한 결과인지 (그렇다면 이름에 힐튼이 없어도 힐튼 계열). */
    static final String BRAND_FILTERED = "_hilton_brand_filtered";

    static List<Hotel> parseHotels(JSONObject data, int nights, int rooms) {
        List<Hotel> out = new ArrayList<>();
        JSONArray props = data.optJSONArray("properties");
        if (props == null && data.has("name")) props = new JSONArray().put(data); // 호텔 1곳만 나온 경우
        if (props == null) return out;
        boolean brandFiltered = data.optBoolean(BRAND_FILTERED);
        for (int i = 0; i < props.length(); i++) {
            JSONObject p = props.optJSONObject(i);
            if (p == null) continue;
            String name = p.optString("name", "");
            if ("vacation rental".equals(p.optString("type"))) continue;
            if (!brandFiltered && !isHilton(name)) continue;
            Integer per = amount(p.opt("rate_per_night"));
            Integer total = amount(p.opt("total_rate"));
            if (per == null && total == null) continue;
            if (total == null) total = per * nights;
            if (per == null) per = Math.round(total / (float) Math.max(nights, 1));
            Hotel h = new Hotel();
            h.name = name;
            h.total = total * rooms;
            h.perNight = per * rooms;
            h.rating = p.optDouble("overall_rating", 0);
            h.hotelClass = p.optString("hotel_class", null);
            h.link = p.optString("link", null);
            out.add(h);
        }
        Collections.sort(out, new Comparator<Hotel>() {
            @Override
            public int compare(Hotel a, Hotel b) { return Integer.compare(a.total, b.total); }
        });
        return out;
    }

    /** Google Hotels 응답의 brands 목록에서 힐튼 계열 브랜드 id 들을 찾는다 ("1,2,3", 없으면 null). */
    static String hiltonBrandIds(JSONObject data) {
        List<String> ids = new ArrayList<>();
        collectBrands(data.optJSONArray("brands"), false, ids);
        return ids.isEmpty() ? null : join(ids.toArray(new String[0]));
    }

    private static void collectBrands(JSONArray brands, boolean parentHilton, List<String> ids) {
        if (brands == null) return;
        for (int i = 0; i < brands.length(); i++) {
            JSONObject b = brands.optJSONObject(i);
            if (b == null) continue;
            boolean hilton = parentHilton || isHilton(b.optString("name", ""));
            String id = b.has("id") ? String.valueOf(b.opt("id")) : null;
            if (hilton && id != null && !ids.contains(id)) ids.add(id);
            collectBrands(b.optJSONArray("children"), hilton, ids);
        }
    }

    /** SerpApi 가 돌려준 오류 (인터넷 문제가 아니므로 다시 시도하지 않음). */
    static final class ApiError extends IOException {
        ApiError(String m) { super(m); }
    }

    /** 실제 SerpApi 호출. */
    static final class SerpApi implements Client {
        private final String apiKey, currency;
        private int calls;
        String hiltonBrands; // 한 번 찾으면 저장해 두고 계속 씀 (Runner 가 보관)
        long retryDelayMs = 4000;
        String baseUrl = "https://serpapi.com/search.json"; // 테스트에서 바꿈
        private boolean offline; // 인터넷이 끊긴 게 확인되면 남은 검색은 기다리지 않고 바로 건너뜀

        SerpApi(String apiKey, String currency, String hiltonBrands) {
            this.apiKey = apiKey;
            this.currency = currency;
            this.hiltonBrands = hiltonBrands;
        }

        @Override
        public int calls() { return calls; }

        @Override
        public JSONObject flights(Trip t, int outbound, int inbound) throws Exception {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("engine", "google_flights");
            p.put("departure_id", t.origin);
            p.put("arrival_id", t.destination);
            p.put("outbound_date", Day.iso(outbound));
            p.put("return_date", Day.iso(inbound));
            p.put("type", 1);
            p.put("adults", t.adults);
            p.put("children", t.childAges.length);
            p.put("infants_on_lap", t.infants);
            p.put("travel_class", t.cabinCode());
            p.put("stops", t.stopsCode());
            p.put("hl", "ko");
            p.put("gl", "kr");
            if (t.airlines.length > 0) p.put("include_airlines", join(t.airlines));
            return get(p);
        }

        @Override
        public JSONObject hotels(Trip t, int checkIn, int checkOut) throws Exception {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("engine", "google_hotels");
            p.put("q", "Hilton hotels in " + t.hotelCity);
            p.put("check_in_date", Day.iso(checkIn));
            p.put("check_out_date", Day.iso(checkOut));
            p.put("adults", Math.max(1, (t.adults + t.rooms - 1) / t.rooms));
            if (t.childAges.length > 0 && t.rooms == 1) {
                p.put("children", t.childAges.length);
                String[] ages = new String[t.childAges.length];
                for (int i = 0; i < ages.length; i++) ages[i] = String.valueOf(t.childAges[i]);
                p.put("children_ages", join(ages));
            }
            p.put("hl", "en");
            p.put("gl", "kr");
            if (hiltonBrands != null) return brandSearch(p, t);
            // 브랜드 id 를 아직 모르면: 관련도순으로 "Hilton hotels in 도시" 검색 (최저가순으로 하면
            // 싼 일반 숙소가 첫 페이지를 채워 힐튼이 빠진다) → 응답의 brands 목록에서 힐튼 id 를 찾아 다시 검색
            p.put("q", "Hilton hotels in " + t.hotelCity);
            JSONObject first = get(p);
            String ids = hiltonBrandIds(first);
            if (ids == null) return first;
            hiltonBrands = ids;
            try {
                JSONObject byBrand = brandSearch(p, t);
                return parseHotels(byBrand, 1, 1).isEmpty() ? first : byBrand;
            } catch (IOException e) {
                return first;
            }
        }

        private JSONObject brandSearch(Map<String, Object> p, Trip t) throws IOException, JSONException {
            p.put("q", "hotels in " + t.hotelCity);
            p.put("brands", hiltonBrands);
            p.put("sort_by", 3); // 힐튼 계열 안에서 최저가순
            return get(p).put(BRAND_FILTERED, true);
        }

        /** 인터넷이 잠깐 끊긴 경우(주소 못 찾음, 시간 초과 등)는 조금 기다렸다가 두 번 더 시도한다. */
        private JSONObject get(Map<String, Object> params) throws IOException, JSONException {
            IOException last = null;
            for (int attempt = 0; attempt < (offline ? 1 : 3); attempt++) {
                if (attempt > 0) {
                    try {
                        Thread.sleep(retryDelayMs * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                try {
                    JSONObject r = getOnce(params);
                    offline = false;
                    return r;
                } catch (ApiError e) {
                    offline = false;
                    throw e;
                } catch (IOException e) {
                    last = e;
                }
            }
            offline = true;
            throw new IOException(networkMessage(last), last);
        }

        private JSONObject getOnce(Map<String, Object> params) throws IOException, JSONException {
            params.put("currency", currency);
            params.put("api_key", apiKey);
            StringBuilder q = new StringBuilder(baseUrl).append('?');
            for (Map.Entry<String, Object> e : params.entrySet()) {
                if (q.charAt(q.length() - 1) != '?') q.append('&');
                q.append(e.getKey()).append('=').append(URLEncoder.encode(String.valueOf(e.getValue()), "UTF-8"));
            }
            HttpURLConnection c = (HttpURLConnection) new URL(q.toString()).openConnection();
            c.setConnectTimeout(20_000);
            c.setReadTimeout(90_000);
            try {
                int code = c.getResponseCode();
                calls++;
                InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
                String body = in == null ? "" : read(in);
                JSONObject json;
                try {
                    json = new JSONObject(body);
                } catch (JSONException e) {
                    if (code >= 500) throw new IOException("SerpApi 서버 오류 (HTTP " + code + ")");
                    throw new ApiError("HTTP " + code);
                }
                String error = json.optString("error", "");
                // 조건에 맞는 항공편/호텔이 없을 때도 error 로 온다 → 오류가 아니라 '결과 없음'
                if (error.contains("hasn't returned any results")) return new JSONObject().put("no_results", true);
                if (!error.isEmpty()) throw new ApiError(error);
                if (code >= 500) throw new IOException("SerpApi 서버 오류 (HTTP " + code + ")");
                if (code >= 400) throw new ApiError("HTTP " + code);
                return json;
            } finally {
                c.disconnect();
            }
        }
    }

    static String networkMessage(IOException e) {
        if (e instanceof java.net.UnknownHostException || e instanceof java.net.ConnectException) {
            return "인터넷 연결이 끊겨 serpapi.com 에 접속하지 못했어요 (다음 검색 때 다시 확인)";
        }
        if (e instanceof java.net.SocketTimeoutException) return "응답이 너무 늦어 건너뛰었어요 (다음 검색 때 다시 확인)";
        return e == null ? "인터넷 오류" : String.valueOf(e.getMessage());
    }

    static String join(String[] parts) {
        StringBuilder sb = new StringBuilder();
        for (String s : parts) {
            if (sb.length() > 0) sb.append(',');
            sb.append(s);
        }
        return sb.toString();
    }

    static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        return out.toString("UTF-8");
    }
}
