"""매일 아침 여행 가격 알림.

    python -m travelalert                 # travel.json 으로 검색 후 알림 전송
    python -m travelalert --demo --dry-run  # API 키 없이 가짜 데이터로 메시지만 출력
"""

from __future__ import annotations

import argparse
import os
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

from . import config as cfg
from .notify import send_all
from .prices import SerpApiClient, search_trip
from .report import build_message, load_history, previous, save_history

KST = timezone(timedelta(hours=9))


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(prog="travelalert", description="여행지 항공권 + 힐튼 호텔 가격 알림")
    ap.add_argument("--config", default=os.environ.get("TRAVEL_CONFIG", "travel.json"))
    ap.add_argument("--history", default=os.environ.get("TRAVEL_HISTORY", "state/travel-history.json"))
    ap.add_argument("--demo", action="store_true", help="API 키 없이 가짜 가격으로 시험")
    ap.add_argument("--dry-run", action="store_true", help="알림을 보내지 않고 화면에만 출력")
    args = ap.parse_args(argv)

    conf = cfg.load(args.config)
    today = datetime.now(KST).date()

    if args.demo:
        from .demo import DemoClient
        client = DemoClient(today)
    else:
        key = os.environ.get("SERPAPI_API_KEY")
        if not key:
            print("SERPAPI_API_KEY 환경변수가 필요합니다 (시험만 하려면 --demo).", file=sys.stderr)
            return 2
        client = SerpApiClient(key, conf.currency)

    results = [search_trip(client, t, today) for t in conf.trips]

    history_path = Path(args.history)
    history = load_history(history_path)
    text = build_message(results, today, conf.currency, previous(history, today))
    print(text)
    print(f"\n(API 호출 {client.calls}회)", file=sys.stderr)
    if not args.demo:
        save_history(history_path, history, today, results)

    if args.dry_run:
        return 0
    sent = send_all(text, f"여행 가격 알림 {today:%m/%d}")
    if not sent:
        print("알림 채널이 설정되지 않아 출력만 했습니다 (TELEGRAM_BOT_TOKEN/TELEGRAM_CHAT_ID 등).", file=sys.stderr)
    else:
        print(f"전송 완료: {', '.join(sent)}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
