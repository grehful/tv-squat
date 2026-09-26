"""실행: python -m tvsquat --tv-ip 192.168.0.20 --tv-mac AA:BB:CC:DD:EE:FF"""

from __future__ import annotations

import argparse
import logging
import sys

from .config import load_settings, save_settings
from .server import App, serve
from .tv import DummyTV, SamsungTV


def main(argv=None) -> int:
    p = argparse.ArgumentParser(prog="tvsquat", description="스쿼트 할당량을 못 채우면 삼성 TV를 끕니다.")
    p.add_argument("command", nargs="?", default="run", choices=["run", "pair", "status", "off", "on"],
                   help="run: 서버 실행(기본) / pair: TV 연결 승인 / status: TV 상태 / off, on: TV 전원 테스트")
    p.add_argument("--tv-ip", help="삼성 TV의 IP 주소")
    p.add_argument("--tv-mac", help="TV 켜기(Wake-on-LAN)용 MAC 주소")
    p.add_argument("--token-file", default="tv-token.txt", help="TV 인증 토큰 저장 파일")
    p.add_argument("--dry-run", action="store_true", help="실제 TV 없이 가상 TV로 실행")
    p.add_argument("--settings", default="settings.json", help="설정 파일 경로")
    p.add_argument("--host", default="127.0.0.1",
                   help="서버 주소 (휴대폰에서 설정을 바꾸려면 0.0.0.0)")
    p.add_argument("--port", type=int, default=8765)
    # 자주 바꾸는 설정은 명령줄에서도 지정 가능 (지정하면 설정 파일에 저장됨)
    p.add_argument("--seconds-per-squat", type=int, help="스쿼트 1개당 초 (기본 30)")
    p.add_argument("--window-minutes", type=float, help="검사 주기(분) (기본 5)")
    p.add_argument("--players", type=int, help="함께 하는 아이 수 (기본 1)")
    p.add_argument("--pin", help="설정 변경용 부모 PIN")
    p.add_argument("-v", "--verbose", action="store_true")
    args = p.parse_args(argv)

    logging.basicConfig(level=logging.DEBUG if args.verbose else logging.INFO,
                        format="%(asctime)s %(levelname)s %(message)s", datefmt="%H:%M:%S")

    if args.dry_run:
        tv = DummyTV()
    elif args.tv_ip:
        tv = SamsungTV(args.tv_ip, mac=args.tv_mac, token_file=args.token_file)
    else:
        p.error("--tv-ip 를 지정하거나, 테스트라면 --dry-run 을 사용하세요")

    if args.command == "pair":
        if not isinstance(tv, SamsungTV):
            p.error("pair 에는 --tv-ip 가 필요합니다")
        print("TV 화면에 뜨는 연결 요청에서 '허용'을 눌러주세요... (30초 안에)")
        tv.timeout = 30
        tv.send_key("KEY_VOLUP")
        tv.send_key("KEY_VOLDOWN")
        print(f"완료! 토큰이 {args.token_file} 에 저장되었습니다.")
        return 0
    if args.command == "status":
        print("켜짐" if tv.is_on() else "꺼짐")
        if isinstance(tv, SamsungTV):
            info = tv.device_info()
            if info:
                dev = info.get("device", {})
                print(f"모델: {dev.get('modelName')}  이름: {dev.get('name')}  "
                      f"MAC: {dev.get('wifiMac')}  PowerState: {dev.get('PowerState')}")
        return 0
    if args.command == "off":
        tv.power_off()
        return 0
    if args.command == "on":
        tv.power_on()
        return 0

    try:
        settings = load_settings(args.settings)
    except ValueError as e:
        p.error(f"{args.settings} 설정 오류: {e}")
    overrides = {k: v for k, v in {
        "seconds_per_squat": args.seconds_per_squat,
        "window_minutes": args.window_minutes,
        "players": args.players,
        "pin": args.pin,
    }.items() if v is not None}
    if overrides:
        try:
            settings.update(overrides)
        except ValueError as e:
            p.error(str(e))
        save_settings(settings, args.settings)

    print(f"스쿼트 {settings.seconds_per_squat}초에 1개, {settings.window_minutes:g}분마다 "
          f"{settings.quota}개 검사 (인원 {settings.players}명)")
    serve(App(settings, tv, settings_path=args.settings), args.host, args.port)
    return 0


if __name__ == "__main__":
    sys.exit(main())
