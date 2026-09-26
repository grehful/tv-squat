// web/session.js (휴대폰 앱용) 가 tvsquat/session.py 와 같은 규칙으로 동작하는지 확인
import { test } from "node:test";
import assert from "node:assert/strict";
import {
  DEFAULT_SETTINGS, LOCKED, PAUSED, POWER_COMMAND_COOLDOWN, RUNNING, Session, TV_OFF, TV_ON, mergeSettings, quotaOf,
} from "../web/session.js";
import { FakeTv, LocalBackend } from "../web/backend.js";

function make(overrides = {}) {
  const s = mergeSettings({ ...DEFAULT_SETTINGS }, overrides);
  const sess = new Session(s);
  sess.tick(0, true); // TV 켜짐 -> 타이머 시작
  return sess;
}

test("default quota is 10 per 5 minutes", () => {
  assert.equal(quotaOf(DEFAULT_SETTINGS), 10);
  assert.equal(quotaOf({ ...DEFAULT_SETTINGS, seconds_per_squat: 20 }), 15);
  assert.equal(quotaOf({ ...DEFAULT_SETTINGS, players: 2 }), 20);
  assert.equal(quotaOf(mergeSettings(DEFAULT_SETTINGS, { window_minutes: "0.5", seconds_per_squat: "3" })), 10);
});

test("passing window keeps TV on", () => {
  const sess = make();
  assert.equal(sess.state, RUNNING);
  sess.addReps(10, 100);
  assert.deepEqual(sess.tick(300, true), []);
  assert.equal(sess.reps, 0);
  assert.equal(sess.history.at(-1).passed, true);
});

test("failing window turns TV off, squats turn it back on", () => {
  const sess = make();
  sess.addReps(9, 100);
  assert.deepEqual(sess.tick(299, true), []);
  assert.deepEqual(sess.tick(300, true), [TV_OFF]);
  assert.equal(sess.state, LOCKED);
  assert.deepEqual(sess.addReps(9, 310), []);
  assert.deepEqual(sess.addReps(1, 320), [TV_ON]);
  assert.equal(sess.snapshot(320).remaining_seconds, 300);
});

test("locked TV turned on by remote is turned off again after cooldown", () => {
  const sess = make();
  sess.tick(300, true);
  assert.deepEqual(sess.tick(305, true), []);
  assert.deepEqual(sess.tick(300 + POWER_COMMAND_COOLDOWN, true), [TV_OFF]);
});

test("timer pauses while TV is off; parent pause survives TV on", () => {
  const sess = make();
  sess.tick(100, false);
  assert.equal(sess.state, PAUSED);
  sess.tick(1000, true);
  assert.equal(sess.snapshot(1000).remaining_seconds, 200);
  sess.pause(1050);
  sess.tick(1500, true);
  assert.equal(sess.state, PAUSED);
  sess.resume(1500);
  assert.equal(sess.snapshot(1500).remaining_seconds, 150);
});

test("carry over and validation", () => {
  const sess = make({ carry_over: true });
  sess.addReps(13, 100);
  sess.tick(300, true);
  assert.equal(sess.reps, 3);
  assert.throws(() => mergeSettings(DEFAULT_SETTINGS, { seconds_per_squat: 0 }));
  assert.throws(() => mergeSettings(DEFAULT_SETTINGS, { down_angle: 170, up_angle: 160 }));
  assert.throws(() => mergeSettings(DEFAULT_SETTINGS, { players: "abc" }));
});

test("LocalBackend drives the TV and keeps settings", async () => {
  let t = 0;
  const store = new Map();
  const storage = { getItem: (k) => store.get(k) ?? null, setItem: (k, v) => store.set(k, v) };
  const tv = new FakeTv();
  const b = new LocalBackend(tv, { storage, clock: () => t, autoTick: false });
  b.tick();
  assert.equal((await b.state()).state, "running");
  await b.updateSettings({ seconds_per_squat: 60, new_pin: "77", tv_ip: " 10.0.0.5 " });
  assert.equal((await b.state()).quota, 5);
  await assert.rejects(b.control("pause", "00"), /PIN/);
  await b.addReps(2);
  t = 300;
  b.tick();
  assert.equal(tv.on, false);
  assert.equal((await b.state()).state, "locked");
  await b.addReps(5);
  assert.equal(tv.on, true);
  // 새로 만들어도 저장된 설정이 남아 있다
  const b2 = new LocalBackend(new FakeTv(), { storage, clock: () => t, autoTick: false });
  assert.equal(b2.settings.seconds_per_squat, 60);
  assert.equal(b2.settings.tv_ip, "10.0.0.5");
  assert.equal((await b2.state()).settings.pin_required, true);
  assert.equal("pin" in (await b2.state()).settings, false);
});
