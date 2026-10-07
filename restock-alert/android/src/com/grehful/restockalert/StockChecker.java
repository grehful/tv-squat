package com.grehful.restockalert;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Iterator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 스마트스토어 상품 페이지를 받아서 판매 상태를 읽는다. (PC용 restock_alert.py 와 같은 방식) */
public final class StockChecker {
    static final String USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; SM-S921N) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/129.0.0.0 Mobile Safari/537.36";

    private static final Pattern PRODUCT_NO = Pattern.compile("/products/(\\d+)");
    private static final Pattern STATE = Pattern.compile(
            "window\\.__PRELOADED_STATE__\\s*=\\s*(\\{.*?\\})\\s*</script>", Pattern.DOTALL);
    private static final Pattern STATUS = Pattern.compile("\"productStatusType\"\\s*:\\s*\"(\\w+)\"");

    /** 네이버가 접속을 막았을 때 (HTTP 403/429). */
    public static final class RateLimitedException extends IOException {
        public RateLimitedException(int code) {
            super("HTTP " + code);
        }
    }

    public static final class Result {
        /** true: 구매 가능, false: 구매 불가, null: 판단 불가 */
        public final Boolean inStock;
        public final String description;
        public final String name;

        Result(Boolean inStock, String description, String name) {
            this.inStock = inStock;
            this.description = description;
            this.name = name;
        }
    }

    private StockChecker() {}

    public static String productNo(String url) {
        Matcher m = PRODUCT_NO.matcher(url);
        return m.find() ? m.group(1) : null;
    }

    public static Result check(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(20000);
        conn.setRequestProperty("User-Agent", USER_AGENT);
        conn.setRequestProperty("Accept-Language", "ko-KR,ko;q=0.9");
        conn.setRequestProperty("Accept", "text/html,application/xhtml+xml");
        try {
            int code = conn.getResponseCode();
            if (code == 403 || code == 429) throw new RateLimitedException(code);
            if (code != 200) throw new IOException("HTTP " + code);
            try (InputStream in = conn.getInputStream()) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] chunk = new byte[16384];
                int n;
                while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
                return parse(buf.toString("UTF-8"), productNo(url));
            }
        } finally {
            conn.disconnect();
        }
    }

    public static Result parse(String html, String productNo) {
        JSONObject product = null;
        Matcher m = STATE.matcher(html);
        if (m.find()) {
            try {
                product = findProduct(new JSONObject(m.group(1)), productNo);
            } catch (JSONException ignored) {
                // 아래 단순 검색으로 넘어간다
            }
        }
        if (product != null) {
            String status = product.optString("productStatusType");
            String name = product.optString("name", null);
            String label = label(status);
            if (product.has("stockQuantity")) {
                int qty = product.optInt("stockQuantity");
                return new Result("SALE".equals(status) && qty > 0, label + " (재고 " + qty + "개)", name);
            }
            return new Result("SALE".equals(status), label, name);
        }

        // 페이지 구조가 바뀌었을 때를 대비한 단순 검색
        Matcher s = STATUS.matcher(html);
        if (s.find()) {
            String status = s.group(1);
            return new Result("SALE".equals(status), label(status) + " (단순검색)", null);
        }
        return new Result(null, "페이지에서 판매 상태를 찾지 못함", null);
    }

    static String label(String status) {
        switch (status) {
            case "SALE": return "판매중";
            case "OUTOFSTOCK": return "품절";
            case "SUSPENSION": return "판매중지";
            case "WAIT": return "판매대기";
            case "CLOSE": return "판매종료";
            case "PROHIBITION": return "판매금지";
            default: return status;
        }
    }

    /** 상태 JSON 안에서 이 상품 번호를 가진 상품 객체를 찾는다. */
    private static JSONObject findProduct(Object node, String productNo) {
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            if (o.has("productStatusType")) {
                if (productNo == null) return o;
                for (String k : new String[]{"productNo", "id", "productId"}) {
                    if (productNo.equals(o.optString(k))) return o;
                }
            }
            Iterator<String> keys = o.keys();
            while (keys.hasNext()) {
                JSONObject found = findProduct(o.opt(keys.next()), productNo);
                if (found != null) return found;
            }
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) {
                JSONObject found = findProduct(a.opt(i), productNo);
                if (found != null) return found;
            }
        }
        return null;
    }
}
