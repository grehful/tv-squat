import unittest

from tvsquat.config import Settings
from tvsquat.session import LOCKED, PAUSED, RUNNING, TV_OFF, TV_ON, POWER_COMMAND_COOLDOWN, Session


class SessionTest(unittest.TestCase):
    def make(self, **kw):
        s = Settings(**kw)
        sess = Session(s, 0)
        sess.tick(0, True)  # TV 켜짐 -> 타이머 시작
        return s, sess

    def test_default_quota_is_10_per_5_minutes(self):
        self.assertEqual(Settings().quota, 10)
        self.assertEqual(Settings(seconds_per_squat=20).quota, 15)
        self.assertEqual(Settings(players=2).quota, 20)

    def test_passing_window_keeps_tv_on(self):
        _, sess = self.make()
        self.assertEqual(sess.state, RUNNING)
        sess.add_reps(10, 100)
        self.assertEqual(sess.tick(300, True), [])
        self.assertEqual(sess.state, RUNNING)
        self.assertEqual(sess.reps, 0)
        self.assertTrue(sess.history[-1].passed)

    def test_failing_window_turns_tv_off_and_squats_turn_it_back_on(self):
        _, sess = self.make()
        sess.add_reps(9, 100)
        self.assertEqual(sess.tick(299, True), [])
        self.assertEqual(sess.tick(300, True), [TV_OFF])
        self.assertEqual(sess.state, LOCKED)
        self.assertEqual(sess.add_reps(9, 310), [])
        self.assertEqual(sess.add_reps(1, 320), [TV_ON])
        self.assertEqual(sess.state, RUNNING)
        self.assertEqual(sess.snapshot(320)["remaining_seconds"], 300)

    def test_no_auto_turn_on_requires_parent(self):
        _, sess = self.make(auto_turn_on=False)
        sess.tick(300, True)
        self.assertEqual(sess.add_reps(50, 310), [])
        self.assertEqual(sess.state, LOCKED)
        self.assertEqual(sess.resume(320), [TV_ON])

    def test_locked_tv_turned_on_by_remote_is_turned_off_again(self):
        _, sess = self.make()
        sess.tick(300, True)
        self.assertEqual(sess.tick(305, True), [])  # 쿨다운 중
        self.assertEqual(sess.tick(300 + POWER_COMMAND_COOLDOWN, True), [TV_OFF])
        self.assertEqual(sess.tick(330, False), [])

    def test_timer_pauses_while_tv_is_off(self):
        _, sess = self.make()
        sess.tick(100, False)
        self.assertEqual(sess.state, PAUSED)
        self.assertEqual(sess.tick(1000, False), [])
        sess.tick(1000, True)
        self.assertEqual(sess.state, RUNNING)
        self.assertEqual(sess.snapshot(1000)["remaining_seconds"], 200)
        self.assertEqual(sess.tick(1200, True), [TV_OFF])

    def test_parent_pause_is_not_resumed_by_tv(self):
        _, sess = self.make()
        sess.pause(50)
        sess.tick(500, True)
        self.assertEqual(sess.state, PAUSED)
        sess.resume(500)
        self.assertEqual(sess.snapshot(500)["remaining_seconds"], 250)

    def test_unknown_tv_state_counts_as_on(self):
        _, sess = self.make()
        sess.tick(10, None)
        self.assertEqual(sess.state, RUNNING)

    def test_carry_over(self):
        _, sess = self.make(carry_over=True)
        sess.add_reps(13, 100)
        sess.tick(300, True)
        self.assertEqual(sess.reps, 3)

    def test_shorter_window_setting_shortens_remaining(self):
        s, sess = self.make()
        s.update({"window_minutes": 1})
        sess.settings_changed(10)
        self.assertEqual(sess.snapshot(10)["remaining_seconds"], 60)

    def test_fractional_minutes(self):
        s = Settings()
        s.update({"window_minutes": "0.5", "seconds_per_squat": "3", "carry_over": "true"})
        self.assertEqual(s.window_seconds, 30)
        self.assertEqual(s.quota, 10)
        self.assertIs(s.carry_over, True)

    def test_settings_validation(self):
        s = Settings()
        with self.assertRaises(ValueError):
            s.update({"seconds_per_squat": 0})
        with self.assertRaises(ValueError):
            s.update({"down_angle": 170, "up_angle": 160})


if __name__ == "__main__":
    unittest.main()
