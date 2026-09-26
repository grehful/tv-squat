// 할당량 판정 로직 - tvsquat/session.py 와 같은 동작 (서버 없이 휴대폰 앱에서 쓰기 위한 JS 버전)
//
// 상태:
//   running  - 구간(기본 5분) 타이머가 돌아가는 중
//   paused   - 타이머 멈춤 (부모가 일시정지했거나, TV가 꺼져 있음)
//   locked   - 할당량 미달로 TV를 끈 상태. 스쿼트를 채우면 다시 켜짐

export const RUNNING = "running";
export const PAUSED = "paused";
export const LOCKED = "locked";
export const TV_OFF = "tv_off";
export const TV_ON = "tv_on";

// 삼성 TV의 KEY_POWER는 토글이라, 켜지거나 꺼지는 중에 또 보내면 반대로 동작할 수 있음
export const POWER_COMMAND_COOLDOWN = 20;

export const DEFAULT_SETTINGS = Object.freeze({
  seconds_per_squat: 30,
  window_minutes: 5,
  players: 1,
  carry_over: false,
  auto_turn_on: true,
  down_angle: 100,
  up_angle: 160,
  pin: "",
});

export const windowSeconds = (s) => Math.max(10, Math.round(s.window_minutes * 60));
export const quotaOf = (s) =>
  Math.max(1, Math.ceil(windowSeconds(s) / Math.max(1, s.seconds_per_squat))) * Math.max(1, s.players);

// 사용자 입력을 검증해서 새 설정 객체를 돌려준다. 잘못된 값이면 Error.
export function mergeSettings(current, data) {
  const next = { ...current };
  for (const [key, value] of Object.entries(data)) {
    if (!(key in current)) continue;
    const kind = typeof current[key];
    if (kind === "boolean") next[key] = value === true || ["1", "true", "yes", "on"].includes(String(value).toLowerCase());
    else if (kind === "number") {
      const n = Number(value);
      if (!Number.isFinite(n)) throw new Error(`${key} 값이 숫자가 아닙니다`);
      next[key] = Number.isInteger(DEFAULT_SETTINGS[key]) && key !== "window_minutes" ? Math.trunc(n) : n;
    } else next[key] = String(value);
  }
  if (!(next.seconds_per_squat >= 3 && next.seconds_per_squat <= 600)) throw new Error("스쿼트 1개당 시간은 3~600초 사이여야 합니다");
  if (!(next.window_minutes >= 0.5 && next.window_minutes <= 120)) throw new Error("검사 주기는 0.5~120분 사이여야 합니다");
  if (!(next.players >= 1 && next.players <= 4)) throw new Error("인원은 1~4명이어야 합니다");
  if (!(next.down_angle >= 30 && next.down_angle < next.up_angle && next.up_angle <= 180)) {
    throw new Error("각도는 30 <= 앉음 각도 < 일어섬 각도 <= 180 이어야 합니다");
  }
  return next;
}

export function publicSettings(s) {
  const { pin, ...rest } = s;
  return { ...rest, pin_required: Boolean(pin), quota: quotaOf(s), window_seconds: windowSeconds(s) };
}

export class Session {
  constructor(settings) {
    this.settings = settings;
    this.state = PAUSED;
    this.pauseReason = "tv_off";
    this.reps = 0;
    this.totalReps = 0;
    this.remaining = windowSeconds(settings);
    this.deadline = null;
    this.unlockReps = 0;
    this.unlockNeeded = 0;
    this.lastPowerCommand = -POWER_COMMAND_COOLDOWN;
    this.history = [];
    this.lastTvOn = null;
  }

  // ---- 외부 이벤트 ----

  addReps(n, now) {
    n = Math.max(0, Math.trunc(n));
    this.totalReps += n;
    if (this.state === LOCKED) {
      this.unlockReps += n;
      if (this.settings.auto_turn_on && this.unlockReps >= this.unlockNeeded) return this._unlock(now);
      return [];
    }
    this.reps += n;
    return [];
  }

  pause(now) {
    if (this.state === RUNNING) this._freeze(now, "parent");
    else if (this.state === PAUSED) this.pauseReason = "parent";
  }

  // 부모가 재개. 잠금 상태라면 TV를 다시 켠다
  resume(now) {
    if (this.state === LOCKED) return this._unlock(now);
    if (this.state === PAUSED) {
      if (this.lastTvOn === false) this.pauseReason = "tv_off";
      else this._start(now, this.remaining);
    }
    return [];
  }

  // 현재 구간을 처음부터 다시 시작 (개수 0)
  reset(now) {
    this.reps = 0;
    this.remaining = windowSeconds(this.settings);
    if (this.state === RUNNING) this.deadline = now + this.remaining;
  }

  settingsChanged(settings, now) {
    this.settings = settings;
    const limit = windowSeconds(settings);
    if (this.state === RUNNING && this.deadline !== null) this.deadline = Math.min(this.deadline, now + limit);
    this.remaining = Math.min(this.remaining, limit);
    if (this.state === LOCKED) this.unlockNeeded = Math.min(this.unlockNeeded, quotaOf(settings));
  }

  // 주기적으로 호출. tvOn=null 이면 TV 상태를 모르는 것(항상 켜진 것으로 간주)
  tick(now, tvOn) {
    this.lastTvOn = tvOn;
    const effectiveOn = tvOn === null || tvOn === undefined ? true : tvOn;

    if (this.state === LOCKED) {
      // 잠금 중에 누가 리모컨으로 TV를 켜면 다시 끈다
      if (tvOn && now - this.lastPowerCommand >= POWER_COMMAND_COOLDOWN) {
        this.lastPowerCommand = now;
        return [TV_OFF];
      }
      return [];
    }
    if (this.state === PAUSED) {
      if (this.pauseReason === "tv_off" && effectiveOn) this._start(now, this.remaining);
      return [];
    }
    if (!effectiveOn) {
      this._freeze(now, "tv_off");
      return [];
    }
    if (now < this.deadline) return [];
    return this._endWindow(now);
  }

  // ---- 내부 ----

  _start(now, remaining) {
    this.state = RUNNING;
    this.pauseReason = null;
    this.remaining = remaining;
    this.deadline = now + remaining;
  }

  _freeze(now, reason) {
    if (this.deadline !== null) this.remaining = Math.max(0, this.deadline - now);
    this.deadline = null;
    this.state = PAUSED;
    this.pauseReason = reason;
  }

  _endWindow(now) {
    const quota = quotaOf(this.settings);
    const passed = this.reps >= quota;
    this.history.push({ at: now, reps: this.reps, quota, passed });
    this.history.splice(0, Math.max(0, this.history.length - 20));
    if (passed) {
      this.reps = this.settings.carry_over ? Math.min(this.reps - quota, quota) : 0;
      this._start(now, windowSeconds(this.settings));
      return [];
    }
    this.state = LOCKED;
    this.deadline = null;
    this.reps = 0;
    this.unlockReps = 0;
    this.unlockNeeded = quota;
    this.lastPowerCommand = now;
    return [TV_OFF];
  }

  _unlock(now) {
    this.unlockReps = 0;
    this.unlockNeeded = 0;
    this.reps = 0;
    this.lastPowerCommand = now;
    this._start(now, windowSeconds(this.settings));
    return [TV_ON];
  }

  snapshot(now) {
    const remaining = this.state === RUNNING && this.deadline !== null ? Math.max(0, this.deadline - now) : this.remaining;
    return {
      state: this.state,
      pause_reason: this.pauseReason,
      reps: this.reps,
      quota: quotaOf(this.settings),
      remaining_seconds: Math.round(remaining * 10) / 10,
      window_seconds: windowSeconds(this.settings),
      unlock_reps: this.unlockReps,
      unlock_needed: this.unlockNeeded,
      total_reps: this.totalReps,
      tv_on: this.lastTvOn,
      history: this.history.map((h) => ({ ...h })),
    };
  }
}
