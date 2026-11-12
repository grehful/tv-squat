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
    int maxSearches = 0; // 하루 항공 검색 횟수, 0 = 매일 모든 조합
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

    /** 기간 안의 모든 {출발일, 귀국일} 조합 (오늘 이후 출발만). */
    List<int[]> allCombos(int today) {
        List<int[]> combos = new ArrayList<>();
        for (int d = Math.max(departFrom, today + 1); d <= departTo; d++) {
            for (int n : nights) combos.add(new int[]{d, d + n});
        }
        return combos;
    }

    static String comboKey(int[] c) {
        return Day.iso(c[0]) + "+" + (c[1] - c[0]);
    }

    /**
     * 오늘 검색할 조합. 하루 검색 횟수가 조합 수보다 적으면 돌아가며 검색한다:
     * 지금까지 가장 싼 조합 2개를 다시 확인 → 아직 안 본 조합(기간 전체에 고르게) → 오래전에 본 조합 순.
     * cache: {조합키: {price.., checked}} (확인 결과 기록)
     */
    List<int[]> pickToday(int today, org.json.JSONObject cache) {
        final List<int[]> combos = allCombos(today);
        if (maxSearches <= 0 || maxSearches >= combos.size()) return combos;
        List<int[]> order = new ArrayList<>();
        // 1) 지금까지 최저가 2개
        List<int[]> known = new ArrayList<>();
        for (int[] c : combos) {
            org.json.JSONObject e = cache.optJSONObject(comboKey(c));
            if (e != null && e.has("price")) known.add(c);
        }
        final org.json.JSONObject cc = cache;
        java.util.Collections.sort(known, new java.util.Comparator<int[]>() {
            @Override
            public int compare(int[] a, int[] b) {
                return Integer.compare(cc.optJSONObject(comboKey(a)).optInt("price"), cc.optJSONObject(comboKey(b)).optInt("price"));
            }
        });
        // 아직 안 본 조합이 있으면 한 바퀴 도는 것을 우선하고, 다 본 뒤에는 최저가 조합을 매일 다시 확인
        boolean unseen = false;
        for (int[] c : combos) unseen |= cache.optJSONObject(comboKey(c)) == null;
        int refresh = unseen ? (maxSearches >= 4 ? 1 : 0) : Math.min(2, Math.max(1, maxSearches / 2));
        refresh = Math.min(refresh, known.size());
        for (int i = 0; i < refresh; i++) order.add(known.get(i));
        // 2) 나머지: 확인한 지 오래된 순 (안 본 조합이 먼저), 같으면 기간 전체에 고르게 퍼지는 순서
        List<int[]> rest = new ArrayList<>();
        for (int idx : spreadOrder(combos.size())) {
            if (!order.contains(combos.get(idx))) rest.add(combos.get(idx));
        }
        final List<int[]> spread = new ArrayList<>(rest);
        java.util.Collections.sort(rest, new java.util.Comparator<int[]>() {
            @Override
            public int compare(int[] a, int[] b) {
                int ca = checked(cc, a), cb = checked(cc, b);
                if (ca != cb) return Integer.compare(ca, cb);
                return Integer.compare(spread.indexOf(a), spread.indexOf(b));
            }
        });
        for (int[] c : rest) {
            if (order.size() >= maxSearches) break;
            order.add(c);
        }
        return order;
    }

    private static int checked(org.json.JSONObject cache, int[] c) {
        org.json.JSONObject e = cache.optJSONObject(comboKey(c));
        return e == null ? Integer.MIN_VALUE : e.optInt("checked", Integer.MIN_VALUE);
    }

    /** 0..n-1 을 기간 전체에 고르게 퍼지게 나열 (0, 끝, 가운데, 1/4, 3/4 ...). */
    static List<Integer> spreadOrder(int n) {
        List<Integer> out = new ArrayList<>();
        if (n == 0) return out;
        boolean[] used = new boolean[n];
        out.add(0);
        used[0] = true;
        if (n > 1) {
            out.add(n - 1);
            used[n - 1] = true;
        }
        for (int parts = 2; out.size() < n && parts < 4 * n; parts *= 2) {
            for (int k = 1; k < parts; k += 2) {
                int idx = (int) Math.round(k * (n - 1) / (double) parts);
                if (!used[idx]) {
                    used[idx] = true;
                    out.add(idx);
                }
            }
        }
        for (int i = 0; i < n; i++) if (!used[i]) out.add(i);
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
        trip.maxSearches = Math.max(0, t.optInt("max_searches", 0));
        trip.hotel = t.optBoolean("hotel", true);
        trip.rooms = Math.max(1, t.optInt("rooms", 1));
        return trip;
    }

    private static String codes(String s) {
        return s.replace(" ", "").toUpperCase();
    }
}
