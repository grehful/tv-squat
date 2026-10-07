"""네이버 스마트스토어 재입고 알림.

상품 페이지를 주기적으로 확인하다가 '품절' -> '판매중'으로 바뀌면
휴대폰(ntfy 앱, 선택적으로 텔레그램)으로 알림을 보낸다.
자동 구매는 하지 않는다. 알림을 받으면 직접 들어가서 결제하면 된다.

사용 예:
    python restock_alert.py --topic my-pokemon-alert-1234
    python restock_alert.py --url https://m.smartstore.naver.com/moncolle_korea/products/13763561200 \
        --topic my-pokemon-alert-1234 --interval 60
"""

import argparse
import json
import os
import random
import re
import sys
import time
from datetime import datetime

import requests

DEFAULT_URL = "https://m.smartstore.naver.com/moncolle_korea/products/13763561200"

USER_AGENT = (
    "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) "
    "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1"
)

STATUS_LABELS = {
    "SALE": "판매중",
    "OUTOFSTOCK": "품절",
    "SUSPENSION": "판매중지",
    "WAIT": "판매대기",
    "CLOSE": "판매종료",
    "PROHIBITION": "판매금지",
}

# 너무 자주 확인하면 네이버가 접속을 막을 수 있으니 최소 간격을 둔다.
MIN_INTERVAL = 20


def log(msg):
    print(f"[{datetime.now():%Y-%m-%d %H:%M:%S}] {msg}", flush=True)


def product_no_from_url(url):
    m = re.search(r"/products/(\d+)", url)
    return m.group(1) if m else None


def _extract_preloaded_state(html):
    m = re.search(r"window\.__PRELOADED_STATE__\s*=\s*(\{.*?\})\s*</script>", html, re.S)
    if not m:
        return None
    try:
        return json.loads(m.group(1))
    except ValueError:
        return None


def _find_product(node, product_no):
    """상태 JSON 안에서 이 상품 번호를 가진 상품 객체를 찾는다."""
    if isinstance(node, dict):
        if "productStatusType" in node:
            ids = {str(node.get(k)) for k in ("productNo", "id", "productId") if node.get(k) is not None}
            if product_no is None or product_no in ids:
                return node
        for v in node.values():
            found = _find_product(v, product_no)
            if found:
                return found
    elif isinstance(node, list):
        for v in node:
            found = _find_product(v, product_no)
            if found:
                return found
    return None


def parse_stock(html, product_no):
    """(재고있음 여부, 상태 설명)을 돌려준다. 판단할 수 없으면 (None, 이유)."""
    state = _extract_preloaded_state(html)
    product = _find_product(state, product_no) if state else None
    if product:
        status = product.get("productStatusType")
        qty = product.get("stockQuantity")
        label = STATUS_LABELS.get(status, status)
        desc = label if qty is None else f"{label} (재고 {qty}개)"
        in_stock = status == "SALE" and (qty is None or qty > 0)
        return in_stock, desc

    # 페이지 구조가 바뀌었을 때를 대비한 단순 검색
    m = re.search(r'"productStatusType"\s*:\s*"(\w+)"', html)
    if m:
        status = m.group(1)
        return status == "SALE", STATUS_LABELS.get(status, status) + " (단순검색)"

    return None, "페이지에서 판매 상태를 찾지 못함"


def check(session, url, product_no):
    resp = session.get(url, timeout=20)
    if resp.status_code in (403, 429):
        raise RateLimited(resp.status_code)
    resp.raise_for_status()
    return parse_stock(resp.text, product_no)


class RateLimited(Exception):
    pass


def notify(args, title, message):
    if args.topic:
        try:
            requests.post(
                f"{args.ntfy_server.rstrip('/')}/{args.topic}",
                data=message.encode("utf-8"),
                headers={
                    "Title": title.encode("utf-8"),
                    "Priority": "urgent",
                    "Tags": "rotating_light",
                    "Click": args.url,
                },
                timeout=10,
            )
        except requests.RequestException as e:
            log(f"ntfy 알림 실패: {e}")

    if args.telegram_token and args.telegram_chat_id:
        try:
            requests.post(
                f"https://api.telegram.org/bot{args.telegram_token}/sendMessage",
                data={"chat_id": args.telegram_chat_id, "text": f"{title}\n{message}\n{args.url}"},
                timeout=10,
            )
        except requests.RequestException as e:
            log(f"텔레그램 알림 실패: {e}")

    if args.beep:
        for _ in range(5):
            sys.stdout.write("\a")
            sys.stdout.flush()
            time.sleep(0.3)


def main():
    p = argparse.ArgumentParser(description="네이버 스마트스토어 재입고 알림")
    p.add_argument("--url", default=DEFAULT_URL, help="확인할 상품 페이지 주소")
    p.add_argument("--interval", type=int, default=60, help="확인 간격(초), 최소 %d" % MIN_INTERVAL)
    p.add_argument("--topic", default=os.environ.get("NTFY_TOPIC"), help="ntfy 토픽 이름 (휴대폰 앱에서 구독한 이름)")
    p.add_argument("--ntfy-server", default="https://ntfy.sh")
    p.add_argument("--telegram-token", default=os.environ.get("TELEGRAM_TOKEN"))
    p.add_argument("--telegram-chat-id", default=os.environ.get("TELEGRAM_CHAT_ID"))
    p.add_argument("--no-beep", dest="beep", action="store_false", help="컴퓨터 경고음 끄기")
    p.add_argument("--test", action="store_true", help="알림이 잘 오는지 테스트 알림만 보내고 종료")
    args = p.parse_args()

    if not args.topic and not (args.telegram_token and args.telegram_chat_id):
        p.error("--topic(ntfy) 또는 텔레그램 설정 중 하나는 필요합니다.")

    if args.test:
        notify(args, "테스트 알림", "재입고 알림 프로그램이 정상적으로 연결되었습니다.")
        log("테스트 알림을 보냈습니다. 휴대폰을 확인하세요.")
        return

    interval = max(args.interval, MIN_INTERVAL)
    product_no = product_no_from_url(args.url)
    session = requests.Session()
    session.headers.update({"User-Agent": USER_AGENT, "Accept-Language": "ko-KR,ko;q=0.9"})

    log(f"감시 시작: {args.url} (약 {interval}초마다 확인, 종료는 Ctrl+C)")
    was_in_stock = None
    backoff = 0
    failures = 0

    while True:
        try:
            in_stock, desc = check(session, args.url, product_no)
            backoff = 0
            failures = 0
            log(f"상태: {desc}")

            if in_stock and was_in_stock is not True:
                log(">>> 입고됨! 알림을 보냅니다.")
                notify(args, "포켓몬 카드 입고!", f"지금 구매 가능: {desc}")
            if in_stock is not None:
                was_in_stock = in_stock
        except RateLimited as e:
            backoff = min(max(backoff * 2, 60), 900)
            log(f"네이버가 접속을 제한함 (HTTP {e}). {backoff}초 쉬었다가 다시 시도합니다.")
        except requests.RequestException as e:
            failures += 1
            log(f"확인 실패: {e}")
            if failures == 10:
                notify(args, "재입고 알림 오류", "10번 연속 확인에 실패했습니다. 프로그램을 확인해주세요.")

        # 일정한 간격으로 두드리면 봇처럼 보이므로 약간의 무작위성을 준다.
        time.sleep(backoff or interval + random.uniform(0, interval * 0.3))


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        log("종료합니다.")
