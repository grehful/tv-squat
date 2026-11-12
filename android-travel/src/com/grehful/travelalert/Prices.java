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
    }

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
        Collections.sort(out, (a, b) -> Integer.compare(a.price, b.price));
        return out;
    }

    private static Integer amount(Object v) {
        if (v instanceof JSONObject) v = ((JSONObject) v).opt("extracted_lowest");
        return v instanceof Number ? ((Number) v).intValue() : null;
    }

    static List<Hotel> parseHotels(JSONObject data, int nights, int rooms) {
        List<Hotel> out = new ArrayList<>();
        JSONArray props = data.optJSONArray("properties");
        if (props == null) return out;
        for (int i = 0; i < props.length(); i++) {
            JSONObject p = props.optJSONObject(i);
            if (p == null) continue;
            String name = p.optString("name", "");
            if (!isHilton(name) || "vacation rental".equals(p.optString("type"))) continue;
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
        Collections.sort(out, (a, b) -> Integer.compare(a.total, b.total));
        return out;
    }

    /** 실제 SerpApi 호출. */
    static final class SerpApi implements Client {
        private final String apiKey, currency;
        private int calls;

        SerpApi(String apiKey, String currency) {
            this.apiKey = apiKey;
            this.currency = currency;
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
            p.put("sort_by", 3);
            p.put("hl", "en");
            p.put("gl", "kr");
            return get(p);
        }

        private JSONObject get(Map<String, Object> params) throws IOException, JSONException {
            params.put("currency", currency);
            params.put("api_key", apiKey);
            StringBuilder q = new StringBuilder("https://serpapi.com/search.json?");
            for (Map.Entry<String, Object> e : params.entrySet()) {
                if (q.charAt(q.length() - 1) != '?') q.append('&');
                q.append(e.getKey()).append('=').append(URLEncoder.encode(String.valueOf(e.getValue()), "UTF-8"));
            }
            calls++;
            HttpURLConnection c = (HttpURLConnection) new URL(q.toString()).openConnection();
            c.setConnectTimeout(20_000);
            c.setReadTimeout(90_000);
            try {
                int code = c.getResponseCode();
                InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
                String body = in == null ? "" : read(in);
                JSONObject json;
                try {
                    json = new JSONObject(body);
                } catch (JSONException e) {
                    throw new IOException("HTTP " + code);
                }
                if (json.has("error")) throw new IOException(json.optString("error"));
                if (code >= 400) throw new IOException("HTTP " + code);
                return json;
            } finally {
                c.disconnect();
            }
        }
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
