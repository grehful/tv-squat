import { test } from "node:test";
import assert from "node:assert/strict";
import { angleAt, kneeAngle, RepCounter, MultiPersonCounter } from "../web/squat.js";

// 무릎 각도가 deg 가 되도록 hip-knee-ankle 랜드마크를 만든다 (ox: 사람 x 위치)
function pose(deg, ox = 0.5) {
  const lm = Array.from({ length: 33 }, () => ({ x: ox, y: 0.5, visibility: 1 }));
  const rad = (deg * Math.PI) / 180;
  for (const [h, k, a] of [[23, 25, 27], [24, 26, 28]]) {
    lm[k] = { x: ox, y: 0.6, visibility: 1 };
    lm[a] = { x: ox, y: 0.8, visibility: 1 };
    lm[h] = { x: ox + 0.2 * Math.sin(rad), y: 0.6 + 0.2 * Math.cos(rad), visibility: 1 };
  }
  return lm;
}

test("angle", () => {
  assert.equal(Math.round(angleAt({ x: 0, y: 0 }, { x: 0, y: 1 }, { x: 1, y: 1 })), 90);
  assert.equal(Math.round(kneeAngle(pose(170))), 170);
  assert.equal(Math.round(kneeAngle(pose(90))), 90);
});

test("counts a full squat once", () => {
  const c = new RepCounter({ smoothing: 0 });
  let t = 0, reps = 0;
  for (const a of [175, 150, 120, 90, 85, 120, 150, 172, 175]) reps += c.update(a, (t += 100)) ? 1 : 0;
  assert.equal(reps, 1);
});

test("half squat or starting seated does not count", () => {
  const c = new RepCounter({ smoothing: 0 });
  let t = 0, reps = 0;
  for (const a of [175, 130, 120, 175]) reps += c.update(a, (t += 200)) ? 1 : 0;
  const c2 = new RepCounter({ smoothing: 0 });
  for (const a of [80, 80, 175]) reps += c2.update(a, (t += 200)) ? 1 : 0;
  assert.equal(reps, 0);
});

test("too fast jitter does not count", () => {
  const c = new RepCounter({ smoothing: 0, minRepMs: 600 });
  let reps = 0;
  for (const [a, t] of [[175, 0], [90, 100], [175, 200]]) reps += c.update(a, t) ? 1 : 0;
  assert.equal(reps, 0);
});

test("two kids are tracked separately", () => {
  const m = new MultiPersonCounter({ smoothing: 0 });
  let t = 0, reps = 0;
  const seq = [[175, 175], [130, 175], [90, 130], [130, 90], [175, 130], [175, 175], [130, 175], [90, 175], [130, 175], [175, 175]];
  for (const [a, b] of seq) reps += m.update([pose(a, 0.2), pose(b, 0.7)], (t += 400));
  assert.equal(reps, 3);
  assert.deepEqual(m.people.map((p) => p.counter.count).sort(), [1, 2]);
});
