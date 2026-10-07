package com.grehful.restockalert;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Iterator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 상품 페이지를 받아서 판매 상태를 읽는다. 네이버 스마트스토어와 SSG.COM 을 지원한다. */
public final class StockChecker {
    private static final Pattern TITLE = Pattern.compile("<title[^>]*>([^<]*)</title>", Pattern.CASE_INSENSITIVE);
    private static final Pattern PRODUCT_NO = Pattern.compile("/products/(\\d+)");
    private static final Pattern STATE = Pattern.compile(
            "window\\.__PRELOADED_STATE__\\s*=\\s*(\\{.*?\\})\\s*</script>", Pattern.DOTALL);
    private static final Pattern STATUS = Pattern.compile("\"productStatusType\"\\s*:\\s*\"(\\w+)\"");

    private static final Pattern SSG_ITEM_ID = Pattern.compile("[?&]itemId=(\\d+)");
    // SSG 페이지 스크립트의 품절 여부/재고 값 (예: soldOutYn : "Y", "usablInvQty":"0")
    private static final Pattern SSG_SOLD_OUT = Pattern.compile(
            "[\"']?(?:soldOutYn|soldoutYn|sldOutYn)[\"']?\\s*[:=]\\s*[\"']([YN])[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern SSG_STOCK = Pattern.compile(
            "[\"']?usablInvQty[\"']?\\s*[:=]\\s*[\"']?(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern OG_TITLE = Pattern.compile(
            "<meta[^>]+property=[\"']og:title[\"'][^>]+content=[\"']([^\"']*)[\"']", Pattern.CASE_INSENSITIVE);
    private static final String[] SOLD_OUT_WORDS = {"일시품절", "품절된 상품", "판매종료", "판매 종료", "판매가 종료"};
    private static final String[] BUY_WORDS = {"바로구매", "구매하기"};

    /** 사이트가 접속을 막았을 때 (HTTP 403/429). */
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
        /** 네이버가 로그인 페이지로 보냈을 때 */
        public final boolean loginRequired;

        Result(Boolean inStock, String description, String name) {
            this(inStock, description, name, false);
        }

        Result(Boolean inStock, String description, String name, boolean loginRequired) {
            this.inStock = inStock;
            this.description = description;
            this.name = name;
            this.loginRequired = loginRequired;
        }
    }

    private StockChecker() {}

    static boolean isSsg(String url) {
        return url.contains("ssg.com/");
    }

    /** 지원하는 상품 주소면 상품 번호, 아니면 null. */
    public static String productNo(String url) {
        if (!isSsg(url) && !url.contains("naver.com/")) return null;
        Matcher m = (isSsg(url) ? SSG_ITEM_ID : PRODUCT_NO).matcher(url);
        return m.find() ? m.group(1) : null;
    }

    /** PageLoader 가 가져온 페이지에서 판매 상태를 읽는다. */
    public static Result parsePage(String url, String html) {
        Result r = isSsg(url) ? parseSsg(html) : parse(html, productNo(url));
        if (r.inStock == null) {
            // 무슨 페이지가 열렸는지(보안 확인 화면 등) 알 수 있게 제목을 붙인다
            Matcher t = TITLE.matcher(html);
            String title = t.find() ? t.group(1).trim() : "제목 없음";
            if (!isSsg(url) && (title.contains("로그인") || html.contains("nid.naver.com/nidlogin"))) {
                return new Result(null, "네이버 로그인 필요 → 앱의 [네이버 로그인] 버튼을 눌러주세요", r.name, true);
            }
            return new Result(null, r.description + " · 페이지 제목: " + title, r.name);
        }
        return r;
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

    /** SSG.COM: 스크립트 값이 있으면 그걸로, 없으면 화면 문구(일시품절/바로구매)로 판단한다. */
    public static Result parseSsg(String html) {
        String name = null;
        Matcher t = OG_TITLE.matcher(html);
        if (t.find()) name = t.group(1).trim();

        Matcher so = SSG_SOLD_OUT.matcher(html);
        if (so.find()) {
            boolean soldOut = "Y".equalsIgnoreCase(so.group(1));
            return new Result(!soldOut, (soldOut ? "품절" : "판매중") + " (soldOutYn=" + so.group(1) + ")", name);
        }
        Matcher q = SSG_STOCK.matcher(html);
        if (q.find()) {
            int qty = Integer.parseInt(q.group(1));
            return new Result(qty > 0, (qty > 0 ? "판매중" : "품절") + " (재고 " + qty + "개)", name);
        }

        String text = html.replaceAll("(?s)<script.*?</script>|<style.*?</style>", " ")
                .replaceAll("<[^>]+>", " ");
        for (String w : SOLD_OUT_WORDS) {
            if (text.contains(w)) return new Result(false, "품절 ('" + w + "' 문구)", name);
        }
        for (String w : BUY_WORDS) {
            if (text.contains(w)) return new Result(true, "판매중 ('" + w + "' 버튼)", name);
        }
        return new Result(null, "페이지에서 판매 상태를 찾지 못함 (" + html.length() + "자)", name);
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
