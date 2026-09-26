import json
import threading
import unittest
import urllib.error
import urllib.request
from http.server import ThreadingHTTPServer

from tvsquat.config import Settings
from tvsquat.server import App, make_handler
from tvsquat.session import TV_OFF
from tvsquat.tv import DummyTV


class FakeClock:
    def __init__(self):
        self.t = 0.0

    def __call__(self):
        return self.t


class ServerTest(unittest.TestCase):
    def setUp(self):
        self.clock = FakeClock()
        self.app = App(Settings(pin="1234"), DummyTV(), clock=self.clock)
        self.app.tv_on = True
        self.httpd = ThreadingHTTPServer(("127.0.0.1", 0), make_handler(self.app))
        threading.Thread(target=self.httpd.serve_forever, daemon=True).start()
        self.base = f"http://127.0.0.1:{self.httpd.server_address[1]}"

    def tearDown(self):
        self.httpd.shutdown()
        self.httpd.server_close()

    def req(self, path, body=None):
        data = None if body is None else json.dumps(body).encode()
        r = urllib.request.Request(self.base + path, data=data, headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(r) as resp:
            return json.load(resp)

    def test_index_and_state(self):
        with urllib.request.urlopen(self.base + "/") as resp:
            self.assertIn("TV 스쿼트", resp.read().decode())
        st = self.req("/api/state")
        self.assertEqual(st["quota"], 10)
        self.assertNotIn("pin", st["settings"])
        self.assertTrue(st["settings"]["pin_required"])

    def test_reps_and_window_failure(self):
        self.app.tick()
        self.req("/api/rep", {"count": 3})
        self.clock.t = 300
        self.app.tick()
        self.assertEqual(self.app.actions.get_nowait(), TV_OFF)
        self.assertEqual(self.req("/api/state")["state"], "locked")

    def test_settings_require_pin(self):
        with self.assertRaises(urllib.error.HTTPError) as cm:
            self.req("/api/settings", {"seconds_per_squat": 20, "pin": "0000"})
        self.assertEqual(cm.exception.code, 403)
        st = self.req("/api/settings", {"seconds_per_squat": 20, "pin": "1234"})
        self.assertEqual(st["quota"], 15)

    def test_invalid_settings_rejected_without_change(self):
        with self.assertRaises(urllib.error.HTTPError) as cm:
            self.req("/api/settings", {"seconds_per_squat": 20, "players": 99, "pin": "1234"})
        self.assertEqual(cm.exception.code, 400)
        self.assertEqual(self.app.settings.seconds_per_squat, 30)


if __name__ == "__main__":
    unittest.main()
