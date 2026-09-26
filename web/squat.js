// 스쿼트 인식 로직 (MediaPipe 포즈 랜드마크 -> 무릎 각도 -> 개수)
// 브라우저와 Node 테스트에서 모두 쓰이도록 DOM에 의존하지 않는다.

// MediaPipe Pose 랜드마크 번호
const L_HIP = 23, R_HIP = 24, L_KNEE = 25, R_KNEE = 26, L_ANKLE = 27, R_ANKLE = 28;

// b 지점의 각도(도). 좌표는 0~1로 정규화돼 있으므로 aspect(가로/세로)로 x를 보정한다.
export function angleAt(a, b, c, aspect = 1) {
  const abx = (a.x - b.x) * aspect, aby = a.y - b.y;
  const cbx = (c.x - b.x) * aspect, cby = c.y - b.y;
  const dot = abx * cbx + aby * cby;
  const norm = Math.hypot(abx, aby) * Math.hypot(cbx, cby);
  if (norm === 0) return null;
  return (Math.acos(Math.max(-1, Math.min(1, dot / norm))) * 180) / Math.PI;
}

const visible = (p, minVis) => p && (p.visibility === undefined || p.visibility >= minVis);

// 보이는 다리들의 무릎 각도 평균. 다리가 안 보이면 null.
export function kneeAngle(lm, aspect = 1, minVis = 0.5) {
  const angles = [];
  for (const [h, k, a] of [[L_HIP, L_KNEE, L_ANKLE], [R_HIP, R_KNEE, R_ANKLE]]) {
    if (visible(lm[h], minVis) && visible(lm[k], minVis) && visible(lm[a], minVis)) {
      const v = angleAt(lm[h], lm[k], lm[a], aspect);
      if (v !== null) angles.push(v);
    }
  }
  if (!angles.length) return null;
  return angles.reduce((s, v) => s + v, 0) / angles.length;
}

export function hipCenter(lm) {
  const l = lm[L_HIP], r = lm[R_HIP];
  return { x: (l.x + r.x) / 2, y: (l.y + r.y) / 2 };
}

// 한 사람의 스쿼트 개수: 일어섬(up) -> 앉음(down) -> 일어섬(up) 이면 1개
export class RepCounter {
  constructor({ downAngle = 100, upAngle = 160, minRepMs = 600, smoothing = 0.5 } = {}) {
    this.downAngle = downAngle;
    this.upAngle = upAngle;
    this.minRepMs = minRepMs;
    this.smoothing = smoothing;
    this.phase = "unknown"; // 처음엔 한 번 일어선 자세를 봐야 시작
    this.angle = null;
    this.count = 0;
    this.upSince = 0;
    this.reachedDown = false;
  }

  // 한 프레임 반영. 이번 프레임에 1개가 완료되면 true
  update(angle, t) {
    if (angle === null || angle === undefined) return false;
    this.angle = this.angle === null ? angle : this.smoothing * this.angle + (1 - this.smoothing) * angle;
    const a = this.angle;
    if (this.phase !== "down" && a <= this.downAngle) {
      if (this.phase === "up") this.reachedDown = true;
      this.phase = "down";
      return false;
    }
    if (a >= this.upAngle && this.phase !== "up") {
      const completed = this.phase === "down" && this.reachedDown && t - this.upSince >= this.minRepMs;
      this.phase = "up";
      this.upSince = t;
      this.reachedDown = false;
      if (completed) {
        this.count += 1;
        return true;
      }
    }
    return false;
  }

  // 0(서 있음) ~ 1(충분히 앉음) : 화면 게이지용
  depth() {
    if (this.angle === null) return 0;
    return Math.max(0, Math.min(1, (this.upAngle - this.angle) / (this.upAngle - this.downAngle)));
  }
}

// 여러 명을 엉덩이 위치로 추적하면서 각자 개수를 센다
export class MultiPersonCounter {
  constructor(opts = {}) {
    this.opts = opts;
    this.people = [];
    this.nextId = 1;
    this.maxJump = opts.maxJump ?? 0.3; // 한 프레임에 이만큼(화면 비율) 이상 움직이면 다른 사람
    this.forgetMs = opts.forgetMs ?? 2000;
  }

  setThresholds(downAngle, upAngle) {
    this.opts = { ...this.opts, downAngle, upAngle };
    for (const p of this.people) {
      p.counter.downAngle = downAngle;
      p.counter.upAngle = upAngle;
    }
  }

  // poses: 사람별 랜드마크 배열. 반환: 이번 프레임에 완료된 개수
  update(poses, t, aspect = 1) {
    let reps = 0;
    const centers = poses.map(hipCenter);
    // 가까운 쌍부터 짝지어서 같은 사람으로 본다
    const pairs = [];
    centers.forEach((c, i) => this.people.forEach((p) => {
      const d = Math.hypot((p.center.x - c.x) * aspect, p.center.y - c.y);
      if (d < this.maxJump) pairs.push([d, i, p]);
    }));
    pairs.sort((a, b) => a[0] - b[0]);
    const match = new Map();
    const taken = new Set();
    for (const [, i, p] of pairs) {
      if (match.has(i) || taken.has(p)) continue;
      match.set(i, p);
      taken.add(p);
    }
    poses.forEach((lm, i) => {
      let person = match.get(i);
      if (!person) {
        person = { id: this.nextId++, counter: new RepCounter(this.opts) };
        this.people.push(person);
      }
      person.center = centers[i];
      person.seen = t;
      person.landmarks = lm;
      if (person.counter.update(kneeAngle(lm, aspect), t)) reps += 1;
    });
    for (const p of this.people) if (p.seen !== t) p.landmarks = null;
    this.people = this.people.filter((p) => t - p.seen <= this.forgetMs);
    return reps;
  }
}
