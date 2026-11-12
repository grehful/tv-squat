package com.grehful.travelalert;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** API 키 없이 화면을 시험해 보는 가짜 가격 (체험 모드). */
final class DemoClient implements Prices.Client {
    private final int today;
    private int calls;

    DemoClient(int today) { this.today = today; }

    @Override
    public int calls() { return calls; }

    private static int n(Object... parts) {
        int h = 17;
        for (Object p : parts) h = h * 31 + String.valueOf(p).hashCode();
        h ^= h >>> 16;
        return h & 0x7fffffff;
    }

    @Override
    public JSONObject flights(Trip t, int outbound, int inbound) throws JSONException {
        calls++;
        int people = t.adults + t.childAges.length;
        int base = 250_000 + n(t.destination) % 900_000;
        String[] airlines = t.airlines.length > 0 ? t.airlines : new String[]{"대한항공", "아시아나항공", "제주항공"};
        JSONArray best = new JSONArray(), other = new JSONArray();
        for (int i = 0; i < airlines.length; i++) {
            int price = (base + n(t.destination, outbound, inbound, airlines[i], today) % 200_000) * people / 100 * 100;
            JSONObject leg = new JSONObject()
                    .put("airline", airlines[i])
                    .put("flight_number", "DEMO " + (100 + i * 7))
                    .put("departure_airport", new JSONObject().put("time", Day.iso(outbound) + " 0" + (8 + i) + ":30"));
            JSONObject item = new JSONObject().put("price", price).put("total_duration", 150)
                    .put("flights", new JSONArray().put(leg));
            (i == 0 ? best : other).put(item);
        }
        return new JSONObject().put("best_flights", best).put("other_flights", other)
                .put("price_insights", new JSONObject().put("price_level", "typical"));
    }

    @Override
    public JSONObject hotels(Trip t, int checkIn, int checkOut) throws JSONException {
        calls++;
        int nights = checkOut - checkIn;
        JSONArray props = new JSONArray();
        for (String brand : new String[]{"Hilton", "Conrad", "DoubleTree by Hilton", "Hampton by Hilton"}) {
            int per = 120_000 + n(t.hotelCity, brand, checkIn, today) % 400_000;
            props.put(new JSONObject().put("name", brand + " " + t.hotelCity)
                    .put("rate_per_night", new JSONObject().put("extracted_lowest", per))
                    .put("total_rate", new JSONObject().put("extracted_lowest", per * nights))
                    .put("overall_rating", 4.4)
                    .put("link", "https://www.hilton.com/ko/"));
        }
        return new JSONObject().put("properties", props);
    }
}
