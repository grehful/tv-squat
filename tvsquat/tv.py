"""삼성 스마트 TV(2016년 이후 Tizen 모델) 제어.

- 끄기: 웹소켓으로 KEY_POWER 전송 (samsungtvws 라이브러리)
- 켜기: Wake-on-LAN 매직 패킷 (TV 설정에서 '모바일로 TV 켜기'가 켜져 있어야 함)
- 상태 확인: http://TV:8001/api/v2/ 의 PowerState 값 (없는 구형 모델은 응답 여부로 판단)
"""

from __future__ import annotations

import json
import logging
import socket
import time
import urllib.request
from typing import Optional

log = logging.getLogger(__name__)


def send_magic_packet(mac: str, broadcast: str = "255.255.255.255", port: int = 9) -> None:
    hex_mac = mac.replace(":", "").replace("-", "").replace(".", "")
    if len(hex_mac) != 12:
        raise ValueError(f"MAC 주소 형식이 잘못되었습니다: {mac}")
    packet = b"\xff" * 6 + bytes.fromhex(hex_mac) * 16
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        sock.sendto(packet, (broadcast, port))


class DummyTV:
    """TV 없이 테스트할 때 사용 (--dry-run). 명령을 로그로만 남긴다."""

    def __init__(self) -> None:
        self.on = True

    def is_on(self) -> Optional[bool]:
        return self.on

    def power_off(self) -> None:
        log.info("[dry-run] TV 끄기")
        self.on = False

    def power_on(self) -> None:
        log.info("[dry-run] TV 켜기")
        self.on = True

    def describe(self) -> str:
        return "dry-run (가상 TV)"


class SamsungTV:
    def __init__(self, host: str, mac: Optional[str] = None, token_file: str = "tv-token.txt",
                 name: str = "TV Squat", timeout: float = 3.0):
        self.host = host
        self.mac = mac
        self.token_file = token_file
        self.name = name
        self.timeout = timeout

    def _remote(self):
        from samsungtvws import SamsungTVWS  # 설치 안 된 환경에서도 dry-run은 되도록 지연 import

        return SamsungTVWS(host=self.host, port=8002, token_file=self.token_file,
                           name=self.name, timeout=self.timeout)

    def device_info(self) -> Optional[dict]:
        try:
            with urllib.request.urlopen(f"http://{self.host}:8001/api/v2/", timeout=self.timeout) as r:
                return json.load(r)
        except Exception:  # noqa: BLE001 - 꺼져 있으면 연결 실패가 정상
            return None

    def is_on(self) -> Optional[bool]:
        info = self.device_info()
        if info is None:
            return False
        state = (info.get("device") or {}).get("PowerState")
        if state is None:
            return True  # PowerState가 없는 모델: 응답하면 켜진 것
        return state.lower() == "on"

    def send_key(self, key: str) -> None:
        remote = self._remote()
        try:
            remote.send_key(key)
        finally:
            try:
                remote.close()
            except Exception:  # noqa: BLE001
                pass

    def power_off(self) -> None:
        if self.is_on() is False:
            log.info("TV가 이미 꺼져 있음")
            return
        log.info("TV 끄기 (KEY_POWER)")
        self.send_key("KEY_POWER")

    def power_on(self) -> None:
        if self.is_on():
            return
        if self.mac:
            log.info("TV 켜기 (Wake-on-LAN %s)", self.mac)
            for _ in range(3):
                send_magic_packet(self.mac)
                time.sleep(0.3)
        # 대기모드(standby)에서도 응답하는 모델은 KEY_POWER로 켜야 함
        deadline = time.time() + 15
        while time.time() < deadline:
            info = self.device_info()
            if info is not None:
                state = ((info.get("device") or {}).get("PowerState") or "on").lower()
                if state == "on":
                    return
                log.info("TV가 대기모드 -> KEY_POWER 전송")
                self.send_key("KEY_POWER")
                return
            time.sleep(1)
        if not self.mac:
            log.warning("TV를 켤 수 없습니다: --tv-mac 을 지정해야 Wake-on-LAN으로 켤 수 있어요")

    def describe(self) -> str:
        return f"Samsung TV {self.host}" + (f" (MAC {self.mac})" if self.mac else "")
