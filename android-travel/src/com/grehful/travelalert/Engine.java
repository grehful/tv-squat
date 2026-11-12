package com.grehful.travelalert;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/** 모든 여행을 검색하고, 어제와 비교한 결과(JSON)와 알림 문구를 만든다. 안드로이드 API에 의존하지 않음. */
final class Engine {
    static final int KEEP_DAYS = 60;

    private Engine() {}

    static final class TripResult {
        Trip trip;
        List<Prices.Flight> flights = new ArrayList<>(); // 날짜 조합별 최저가, 싼 순
        List<Prices.Hotel> hotels = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        int searched, checkedCombos, totalCombos;

        Prices.Flight best() { return flights.isEmpty() ? null : flights.get(0); }

        Prices.Hotel bestHotel() { return hotels.isEmpty() ? null : hotels.get(0); }

        Integer total() {
            Prices.Flight f = best();
            if (f == null) return null;
            Prices.Hotel h = bestHotel();
            return f.price + (h == null ? 0 : h.total);
        }
    }

    /**
     * 여행 하나 검색. cache 는 이 여행의 조합별 최근 확인 결과({조합키: 항공 JSON 또는 {none, checked}})이며
     * 오늘 검색한 조합이 갱신된다. 결과는 기간 안 모든 조합 중 지금까지 확인한 최저가 순.
     */
    static TripResult search(Prices.Client client, Trip trip, int today, JSONObject cache) throws JSONException {
        TripResult r = new TripResult();
        r.trip = trip;
        List<int[]> combos = trip.allCombos(today);
        for (int[] d : trip.pickToday(today, cache)) {
            String key = Trip.comboKey(d);
            try {
                List<Prices.Flight> fl = Prices.parseFlights(client.flights(trip, d[0], d[1]), d[0], d[1]);
                r.searched++;
                if (fl.isEmpty()) {
                    cache.put(key, new JSONObject().put("none", true).put("checked", today));
                } else {
                    fl.get(0).checked = today;
                    cache.put(key, fl.get(0).toJson());
                }
            } catch (Exception e) {
                r.errors.add("항공 " + Day.label(d[0]) + " " + (d[1] - d[0]) + "박: " + message(e));
            }
        }
        // 기간에서 빠진(지난) 조합은 지우고, 확인된 가격을 모은다
        JSONObject keep = new JSONObject();
        for (int[] d : combos) {
            String key = Trip.comboKey(d);
            JSONObject e = cache.optJSONObject(key);
            if (e == null) continue;
            keep.put(key, e);
            r.checkedCombos++;
            if (e.has("price")) r.flights.add(Prices.Flight.fromJson(e, d[0], d[1]));
        }
        Iterator<String> it = cache.keys();
        List<String> drop = new ArrayList<>();
        while (it.hasNext()) {
            String k = it.next();
            if (!keep.has(k)) drop.add(k);
        }
        for (String k : drop) cache.remove(k);
        r.totalCombos = combos.size();
        Collections.sort(r.flights, Prices.BY_PRICE);

        Prices.Flight best = r.best();
        if (trip.hotel && best != null) {
            try {
                r.hotels = Prices.parseHotels(client.hotels(trip, best.outbound, best.inbound),
                        best.inbound - best.outbound, trip.rooms);
            } catch (Exception e) {
                r.errors.add("호텔: " + message(e));
            }
        }
        if (combos.isEmpty()) r.errors.add("출발 기간이 지났어요");
        return r;
    }

    private static String message(Exception e) {
        String m = e.getMessage();
        return m == null || m.isEmpty() ? e.getClass().getSimpleName() : m;
    }

    /** 결과 JSON. history 는 {날짜: {여행키: {flight, hotel, total}}} 이며 오늘 값이 추가된다. */
    static JSONObject run(Prices.Client client, List<Trip> trips, int today, JSONObject history, JSONObject combos)
            throws JSONException {
        List<TripResult> results = new ArrayList<>();
        JSONObject keepCombos = new JSONObject();
        for (Trip t : trips) {
            JSONObject cache = combos.optJSONObject(t.key());
            if (cache == null) cache = new JSONObject();
            results.add(search(client, t, today, cache));
            keepCombos.put(t.key(), cache);
        }
        // 지운/바꾼 여행의 기록은 정리
        List<String> old = new ArrayList<>();
        Iterator<String> ki = combos.keys();
        while (ki.hasNext()) old.add(ki.next());
        for (String k : old) combos.remove(k);
        Iterator<String> ni = keepCombos.keys();
        while (ni.hasNext()) {
            String k = ni.next();
            combos.put(k, keepCombos.get(k));
        }
        Collections.sort(results, new java.util.Comparator<TripResult>() {
            @Override
            public int compare(TripResult a, TripResult b) {
                Integer x = a.total(), y = b.total();
                if (x == null || y == null) return x == null ? (y == null ? 0 : 1) : -1;
                return Integer.compare(x, y);
            }
        });

        JSONObject prev = previous(history, today);
        JSONObject snap = new JSONObject();
        JSONArray arr = new JSONArray();
        for (TripResult r : results) {
            JSONObject p = prev.optJSONObject(r.trip.key());
            JSONObject o = toJson(r, p, today);
            arr.put(o);
            JSONObject s = new JSONObject();
            s.put("flight", o.optJSONObject("flight") == null ? JSONObject.NULL : o.getJSONObject("flight").getInt("price"));
            s.put("hotel", r.bestHotel() == null ? JSONObject.NULL : r.bestHotel().total);
            s.put("total", r.total() == null ? JSONObject.NULL : r.total());
            snap.put(r.trip.key(), s);
        }
        history.put(Day.iso(today), snap);
        trim(history);

        JSONObject out = new JSONObject();
        out.put("date", Day.iso(today));
        out.put("dateLabel", Day.label(today));
        out.put("calls", client.calls());
        out.put("trips", arr);
        return out;
    }

    static JSONObject previous(JSONObject history, int today) {
        String todayIso = Day.iso(today), best = null;
        Iterator<String> it = history.keys();
        while (it.hasNext()) {
            String k = it.next();
            if (k.compareTo(todayIso) < 0 && (best == null || k.compareTo(best) > 0)) best = k;
        }
        JSONObject p = best == null ? null : history.optJSONObject(best);
        return p == null ? new JSONObject() : p;
    }

    static void trim(JSONObject history) {
        TreeSet<String> days = new TreeSet<>();
        Iterator<String> it = history.keys();
        while (it.hasNext()) days.add(it.next());
        while (days.size() > KEEP_DAYS) history.remove(days.pollFirst());
    }

    private static Object delta(int now, JSONObject prev, String key) {
        if (prev == null || !(prev.opt(key) instanceof Number)) return JSONObject.NULL;
        return now - prev.optInt(key);
    }

    private static JSONObject toJson(TripResult r, JSONObject prev, int today) throws JSONException {
        Trip t = r.trip;
        JSONObject o = new JSONObject();
        o.put("name", t.name);
        o.put("route", t.origin + " → " + t.destination.replace(",", "/"));
        o.put("travelers", t.travelersLabel());
        o.put("cabin", t.cabin);
        o.put("hotelWanted", t.hotel);
        o.put("combosChecked", r.checkedCombos);
        o.put("combosTotal", r.totalCombos);
        o.put("searchedToday", r.searched);
        Prices.Flight f = r.best();
        if (f != null) {
            JSONObject fj = new JSONObject();
            fj.put("price", f.price);
            fj.put("delta", delta(f.price, prev, "flight"));
            fj.put("airlines", Prices.join(f.airlines.toArray(new String[0])).replace(",", ", "));
            fj.put("stops", f.stops);
            fj.put("numbers", Prices.join(f.numbers.toArray(new String[0])).replace(",", " / "));
            fj.put("depart", f.departTime.length() >= 5 ? f.departTime.substring(f.departTime.length() - 5) : "");
            fj.put("durationMin", f.durationMin);
            fj.put("level", f.priceLevel == null ? JSONObject.NULL : f.priceLevel);
            fj.put("outbound", Day.label(f.outbound));
            fj.put("inbound", Day.label(f.inbound));
            fj.put("nights", f.inbound - f.outbound);
            fj.put("checkedAgo", today - f.checked);
            o.put("flight", fj);
            o.put("flightsUrl", googleFlightsUrl(t, f));
            JSONArray others = new JSONArray();
            for (int i = 1; i < Math.min(6, r.flights.size()); i++) {
                Prices.Flight x = r.flights.get(i);
                others.put(new JSONObject().put("label", Day.label(x.outbound) + " " + (x.inbound - x.outbound) + "박")
                        .put("price", x.price).put("checkedAgo", today - x.checked));
            }
            o.put("others", others);
        }
        JSONArray hs = new JSONArray();
        for (int i = 0; i < Math.min(3, r.hotels.size()); i++) {
            Prices.Hotel h = r.hotels.get(i);
            hs.put(new JSONObject().put("name", h.name).put("total", h.total).put("perNight", h.perNight)
                    .put("rating", h.rating).put("link", h.link == null ? JSONObject.NULL : h.link));
        }
        o.put("hotels", hs);
        if (r.bestHotel() != null) o.put("hotelDelta", delta(r.bestHotel().total, prev, "hotel"));
        Integer total = r.total();
        if (total != null && r.bestHotel() != null) {
            o.put("total", total);
            o.put("totalDelta", delta(total, prev, "total"));
        }
        o.put("errors", new JSONArray(r.errors));
        return o;
    }

    static String googleFlightsUrl(Trip t, Prices.Flight f) {
        String dest = t.destination.split(",")[0];
        String q = "Flights to " + dest + " from " + t.origin + " on " + Day.iso(f.outbound)
                + " through " + Day.iso(f.inbound);
        try {
            return "https://www.google.com/travel/flights?hl=ko&q=" + URLEncoder.encode(q, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }

    // ---- 문구 ----

    static String won(long n) {
        if (Math.abs(n) >= 10_000) {
            String s = String.format(Locale.US, "%,.1f", n / 10_000.0);
            if (s.endsWith(".0")) s = s.substring(0, s.length() - 2);
            return s + "만원";
        }
        return String.format(Locale.US, "%,d원", n);
    }

    static String deltaText(Object d) {
        if (!(d instanceof Number) || ((Number) d).intValue() == 0) return "";
        int v = ((Number) d).intValue();
        return (v < 0 ? " 🔻" : " 🔺") + won(Math.abs(v));
    }

    /** 알림에 쓸 {제목, 본문}. */
    static String[] summary(JSONObject result) {
        JSONArray trips = result.optJSONArray("trips");
        StringBuilder body = new StringBuilder();
        String title = "✈️ " + result.optString("dateLabel") + " 여행 가격";
        boolean first = true;
        for (int i = 0; trips != null && i < trips.length(); i++) {
            JSONObject t = trips.optJSONObject(i);
            JSONObject f = t.optJSONObject("flight");
            if (body.length() > 0) body.append('\n');
            if (f == null) {
                body.append(t.optString("name")).append(": 결과 없음");
                continue;
            }
            boolean hasTotal = t.has("total");
            long price = hasTotal ? t.optLong("total") : f.optLong("price");
            Object delta = hasTotal ? t.opt("totalDelta") : f.opt("delta");
            String line = t.optString("name") + " " + won(price) + deltaText(delta)
                    + " · " + f.optString("outbound") + "~" + f.optString("inbound");
            body.append(line);
            if (first) {
                title += " · " + t.optString("name") + " " + won(price) + (hasTotal ? "(항공+호텔)" : "(항공)");
                first = false;
            }
        }
        if (body.length() == 0) body.append("설정된 여행이 없어요. 앱에서 여행을 추가해 주세요.");
        return new String[]{title, body.toString()};
    }
}
