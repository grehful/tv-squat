"""웹 서버 + 백그라운드 타이머/TV 제어 스레드."""

from __future__ import annotations

import hmac
import json
import logging
import os
import queue
import threading
import time
from http import HTTPStatus
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from typing import Optional

from . import __version__
from .config import Settings, save_settings
from .session import TV_OFF, TV_ON, Session

log = logging.getLogger(__name__)

WEB_DIR = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "web")


class PinError(Exception):
    pass


class App:
    """세션 + TV + 설정을 묶고 스레드 안전하게 접근하게 해준다."""

    def __init__(self, settings: Settings, tv, settings_path: Optional[str] = None,
                 clock=time.monotonic, poll_interval: float = 5.0):
        self.settings = settings
        self.tv = tv
        self.settings_path = settings_path
        self.clock = clock
        self.poll_interval = poll_interval
        self.lock = threading.Lock()
        self.session = Session(settings, clock())
        self.tv_on: Optional[bool] = None
        self.actions: "queue.Queue[str]" = queue.Queue()
        self.stop_event = threading.Event()

    # ---- HTTP에서 호출 ----

    def state(self) -> dict:
        with self.lock:
            data = self.session.snapshot(self.clock())
            data["settings"] = self.settings.public_dict()
        data["tv"] = self.tv.describe()
        data["version"] = __version__
        return data

    def add_reps(self, n: int) -> None:
        with self.lock:
            self._dispatch(self.session.add_reps(n, self.clock()))

    def check_pin(self, pin) -> None:
        if self.settings.pin and not hmac.compare_digest(str(pin or ""), self.settings.pin):
            raise PinError("PIN이 틀렸습니다")

    def update_settings(self, data: dict) -> None:
        self.check_pin(data.pop("pin", ""))
        if "new_pin" in data:
            data["pin"] = data.pop("new_pin")
        with self.lock:
            candidate = Settings(**{**self.settings.__dict__})
            candidate.update(data)  # 검증 실패 시 ValueError, 원본은 그대로
            self.settings.__dict__.update(candidate.__dict__)
            self.session.settings_changed(self.clock())
        if self.settings_path:
            save_settings(self.settings, self.settings_path)
        log.info("설정 변경: %s", self.settings.public_dict())

    def control(self, action: str, pin) -> None:
        self.check_pin(pin)
        with self.lock:
            now = self.clock()
            if action == "pause":
                self.session.pause(now)
            elif action == "resume":
                self._dispatch(self.session.resume(now))
            elif action == "reset":
                self.session.reset(now)
            else:
                raise ValueError(f"알 수 없는 동작: {action}")
        log.info("부모 제어: %s", action)

    # ---- 백그라운드 ----

    def tick(self) -> None:
        with self.lock:
            self._dispatch(self.session.tick(self.clock(), self.tv_on))

    def _dispatch(self, actions: list[str]) -> None:
        for a in actions:
            self.actions.put(a)

    def _poll_tv_loop(self) -> None:
        while not self.stop_event.is_set():
            try:
                self.tv_on = self.tv.is_on()
            except Exception:  # noqa: BLE001
                log.exception("TV 상태 확인 실패")
            self.stop_event.wait(self.poll_interval)

    def _tick_loop(self) -> None:
        while not self.stop_event.is_set():
            self.tick()
            self.stop_event.wait(1.0)

    def _action_loop(self) -> None:
        while not self.stop_event.is_set():
            try:
                action = self.actions.get(timeout=0.5)
            except queue.Empty:
                continue
            try:
                if action == TV_OFF:
                    log.info("할당량 미달 -> TV 끄기")
                    self.tv.power_off()
                elif action == TV_ON:
                    log.info("스쿼트 완료 -> TV 켜기")
                    self.tv.power_on()
                self.tv_on = self.tv.is_on()
            except Exception:  # noqa: BLE001
                log.exception("TV 제어 실패 (%s)", action)

    def start_background(self) -> None:
        for target in (self._poll_tv_loop, self._tick_loop, self._action_loop):
            threading.Thread(target=target, daemon=True).start()

    def stop(self) -> None:
        self.stop_event.set()


def make_handler(app: App):
    class Handler(SimpleHTTPRequestHandler):
        def __init__(self, *args, **kwargs):
            super().__init__(*args, directory=WEB_DIR, **kwargs)

        def log_message(self, fmt, *args):  # 요청마다 로그를 찍지 않음
            log.debug(fmt, *args)

        def end_headers(self):
            self.send_header("Cache-Control", "no-store")
            super().end_headers()

        def _json(self, status: int, data: dict) -> None:
            body = json.dumps(data, ensure_ascii=False).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def _body(self) -> dict:
            length = min(int(self.headers.get("Content-Length") or 0), 64 * 1024)
            raw = self.rfile.read(length) if length else b"{}"
            data = json.loads(raw or b"{}")
            if not isinstance(data, dict):
                raise ValueError("JSON 객체가 필요합니다")
            return data

        def do_GET(self):
            if self.path.split("?")[0] == "/api/state":
                return self._json(HTTPStatus.OK, app.state())
            return super().do_GET()

        def do_POST(self):
            path = self.path.split("?")[0]
            try:
                data = self._body()
                if path == "/api/rep":
                    app.add_reps(int(data.get("count", 1)))
                elif path == "/api/settings":
                    app.update_settings(data)
                elif path == "/api/control":
                    app.control(str(data.get("action")), data.get("pin"))
                else:
                    return self._json(HTTPStatus.NOT_FOUND, {"error": "not found"})
            except PinError as e:
                return self._json(HTTPStatus.FORBIDDEN, {"error": str(e)})
            except (ValueError, TypeError) as e:
                return self._json(HTTPStatus.BAD_REQUEST, {"error": str(e)})
            return self._json(HTTPStatus.OK, app.state())

    return Handler


def serve(app: App, host: str, port: int) -> None:
    httpd = ThreadingHTTPServer((host, port), make_handler(app))
    app.start_background()
    shown = "localhost" if host in ("0.0.0.0", "127.0.0.1", "") else host
    log.info("TV: %s", app.tv.describe())
    log.info("브라우저에서 http://%s:%d 을 여세요 (카메라는 localhost 주소에서만 허용됩니다)", shown, port)
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        app.stop()
        httpd.server_close()
