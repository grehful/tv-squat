package com.grehful.travelalert;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** 여행 하나의 검색 조건. 설정 JSON 형식은 저장소의 travel.json 과 같다. */
final class Trip {
    static final String[] CABINS = {"economy", "premium", "business", "first"};

    String name;
    String destination; // "HND,NRT"
    String hotelCity;
    String origin;
    int departFrom, departTo; // Day
    int[] nights;
    int adults = 1;
    int[] childAges = new int[0];
    int infants;
    String[] airlines = new String[0];
    int maxStops = -1; // -1 = 상관없음, 0 = 직항
    String cabin = "economy";
    int maxSearches = 3;
    boolean hotel = true;
    int rooms = 1;

    int cabinCode() {
        for (int i = 0; i < CABINS.length; i++) if (CABINS[i].equals(cabin)) return i + 1;
        return 1;
    }

    /** SerpApi stops: 0=상관없음, 1=직항, 2=1회 이하, 3=2회 이하 */
    int stopsCode() {
        return maxStops < 0 ? 0 : maxStops + 1;
    }

    String travelersLabel() {
        StringBuilder sb = new StringBuilder("성인 ").append(adults);
        if (childAges.length > 0) sb.append(" · 아동 ").append(childAges.length);
        if (infants > 0) sb.append(" · 유아 ").append(infants);
        return sb.toString();
    }

    String key() {
        return name + "|" + origin + "-" + destination + "|" + Day.iso(departFrom) + "~" + Day.iso(departTo);
    }

    /** 검색할 {출발일, 귀국일} 후보. maxSearches 개를 넘으면 기간 안에서 고르게 뽑는다. */
    List<int[]> dateOptions(int today) {
        List<int[]> combos = new ArrayList<>();
        for (int d = Math.max(departFrom, today + 1); d <= departTo; d++) {
            for (int n : nights) combos.add(new int[]{d, d + n});
        }
        if (combos.size() <= maxSearches) return combos;
        List<int[]> out = new ArrayList<>();
        if (maxSearches == 1) {
            out.add(combos.get(combos.size() / 2));
            return out;
        }
        double step = (combos.size() - 1) / (double) (maxSearches - 1);
        for (int i = 0; i < maxSearches; i++) out.add(combos.get((int) Math.round(i * step)));
        return out;
    }

    // ---- 설정 읽기 ----

    static List<Trip> parseAll(JSONObject root) throws JSONException {
        String origin = root.optString("origin", "ICN");
        JSONObject travelers = root.optJSONObject("travelers");
        JSONObject defaults = root.optJSONObject("defaults");
        JSONArray arr = root.optJSONArray("trips");
        List<Trip> trips = new ArrayList<>();
        if (arr == null) return trips;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject t = new JSONObject();
            if (defaults != null) copy(defaults, t);
            copy(arr.getJSONObject(i), t);
            if (!t.optBoolean("enabled", true)) continue;
            trips.add(parse(t, origin, travelers));
        }
        return trips;
    }

    private static void copy(JSONObject from, JSONObject to) throws JSONException {
        Iterator<String> it = from.keys();
        while (it.hasNext()) {
            String k = it.next();
            to.put(k, from.get(k));
        }
    }

    static Trip parse(JSONObject t, String defaultOrigin, JSONObject defaultTravelers) throws JSONException {
        Trip trip = new Trip();
        trip.name = t.getString("name").trim();
        if (trip.name.isEmpty()) throw new JSONException("여행 이름이 비어 있습니다");
        String err = trip.name + ": ";
        trip.destination = codes(t.optString("destination", ""));
        if (trip.destination.isEmpty()) throw new JSONException(err + "도착 공항을 입력하세요");
        trip.hotelCity = t.optString("hotel_city", "").trim();
        if (trip.hotelCity.isEmpty()) trip.hotelCity = trip.name;
        trip.origin = codes(t.optString("origin", defaultOrigin));
        try {
            trip.departFrom = Day.parse(t.getString("depart_from"));
            trip.departTo = Day.parse(t.optString("depart_to", t.getString("depart_from")));
        } catch (IllegalArgumentException e) {
            throw new JSONException(err + e.getMessage());
        }
        if (trip.departTo < trip.departFrom) throw new JSONException(err + "출발 기간 끝이 시작보다 빠릅니다");

        Object nights = t.opt("nights");
        if (nights instanceof JSONArray) {
            JSONArray a = (JSONArray) nights;
            trip.nights = new int[a.length()];
            for (int i = 0; i < a.length(); i++) trip.nights[i] = a.getInt(i);
        } else {
            trip.nights = new int[]{nights == null ? 4 : t.getInt("nights")};
        }
        if (trip.nights.length == 0) throw new JSONException(err + "몇 박인지 입력하세요");
        for (int n : trip.nights) if (n < 1 || n > 60) throw new JSONException(err + "숙박 일수는 1~60");

        JSONObject tr = t.optJSONObject("travelers");
        if (tr == null) tr = defaultTravelers;
        if (tr != null) {
            trip.adults = tr.optInt("adults", 1);
            JSONArray ages = tr.optJSONArray("children_ages");
            if (ages != null) {
                trip.childAges = new int[ages.length()];
                for (int i = 0; i < ages.length(); i++) trip.childAges[i] = ages.getInt(i);
            }
            trip.infants = tr.optInt("infants", 0);
        }
        if (trip.adults < 1) throw new JSONException(err + "성인은 최소 1명");

        JSONArray al = t.optJSONArray("airlines");
        if (al != null) {
            List<String> list = new ArrayList<>();
            for (int i = 0; i < al.length(); i++) {
                String a = al.getString(i).trim().toUpperCase();
                if (!a.isEmpty()) list.add(a);
            }
            trip.airlines = list.toArray(new String[0]);
        }
        trip.maxStops = t.isNull("max_stops") ? -1 : t.getInt("max_stops");
        if (trip.maxStops < -1 || trip.maxStops > 2) throw new JSONException(err + "경유는 직항/1회/2회/상관없음");
        trip.cabin = t.optString("cabin", "economy");
        boolean ok = false;
        for (String c : CABINS) ok |= c.equals(trip.cabin);
        if (!ok) throw new JSONException(err + "좌석 등급이 잘못됐습니다: " + trip.cabin);
        trip.maxSearches = Math.max(1, t.optInt("max_searches", 3));
        trip.hotel = t.optBoolean("hotel", true);
        trip.rooms = Math.max(1, t.optInt("rooms", 1));
        return trip;
    }

    private static String codes(String s) {
        return s.replace(" ", "").toUpperCase();
    }
}
