"""할당량 판정 로직 (시간은 모두 인자로 받아서 테스트하기 쉽게 함).

상태:
  running  - 구간(기본 5분) 타이머가 돌아가는 중
  paused   - 타이머 멈춤 (부모가 일시정지했거나, TV가 꺼져 있음)
  locked   - 할당량 미달로 TV를 끈 상태. 스쿼트를 채우면 다시 켜짐
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Optional

from .config import Settings

RUNNING = "running"
PAUSED = "paused"
LOCKED = "locked"

TV_OFF = "tv_off"
TV_ON = "tv_on"

# TV 전원 명령을 다시 보내기 전 최소 대기 시간(초).
# 삼성 TV의 KEY_POWER는 토글이라, 켜지거나 꺼지는 중에 또 보내면 반대로 동작할 수 있음.
POWER_COMMAND_COOLDOWN = 20.0


@dataclass
class WindowResult:
    at: float
    reps: int
    quota: int
    passed: bool


class Session:
    def __init__(self, settings: Settings, now: float):
        self.settings = settings
        self.state = PAUSED
        self.pause_reason: Optional[str] = "tv_off"
        self.reps = 0
        self.total_reps = 0
        self.remaining = float(settings.window_seconds)
        self.deadline: Optional[float] = None
        self.unlock_reps = 0
        self.unlock_needed = 0
        self.last_power_command = -POWER_COMMAND_COOLDOWN
        self.history: list[WindowResult] = []
        self.last_tv_on: Optional[bool] = None

    # ---- 외부 이벤트 ----

    def add_reps(self, n: int, now: float) -> list[str]:
        n = max(0, int(n))
        self.total_reps += n
        if self.state == LOCKED:
            self.unlock_reps += n
            if self.settings.auto_turn_on and self.unlock_reps >= self.unlock_needed:
                return self._unlock(now)
            return []
        self.reps += n
        return []

    def pause(self, now: float) -> None:
        if self.state == RUNNING:
            self._freeze(now, "parent")
        elif self.state == PAUSED:
            self.pause_reason = "parent"

    def resume(self, now: float) -> list[str]:
        """부모가 재개. 잠금 상태라면 TV를 다시 켠다."""
        if self.state == LOCKED:
            return self._unlock(now)
        if self.state == PAUSED:
            if self.last_tv_on is False:
                self.pause_reason = "tv_off"
            else:
                self._start(now, self.remaining)
        return []

    def reset(self, now: float) -> None:
        """현재 구간을 처음부터 다시 시작 (개수 0)."""
        self.reps = 0
        self.remaining = float(self.settings.window_seconds)
        if self.state == RUNNING:
            self.deadline = now + self.remaining

    def settings_changed(self, now: float) -> None:
        # 구간 길이가 바뀌었으면 남은 시간이 새 길이를 넘지 않게 맞춘다
        limit = float(self.settings.window_seconds)
        if self.state == RUNNING and self.deadline is not None:
            self.deadline = min(self.deadline, now + limit)
        self.remaining = min(self.remaining, limit)
        if self.state == LOCKED:
            self.unlock_needed = min(self.unlock_needed, self.settings.quota)

    def tick(self, now: float, tv_on: Optional[bool]) -> list[str]:
        """주기적으로 호출. tv_on=None 이면 TV 상태를 모르는 것(항상 켜진 것으로 간주)."""
        self.last_tv_on = tv_on
        effective_on = True if tv_on is None else tv_on

        if self.state == LOCKED:
            # 잠금 중에 누가 리모컨으로 TV를 켜면 다시 끈다
            if tv_on and now - self.last_power_command >= POWER_COMMAND_COOLDOWN:
                self.last_power_command = now
                return [TV_OFF]
            return []

        if self.state == PAUSED:
            if self.pause_reason == "tv_off" and effective_on:
                self._start(now, self.remaining)
            return []

        # RUNNING
        if not effective_on:
            self._freeze(now, "tv_off")
            return []
        if now < self.deadline:
            return []
        return self._end_window(now)

    # ---- 내부 ----

    def _start(self, now: float, remaining: float) -> None:
        self.state = RUNNING
        self.pause_reason = None
        self.remaining = remaining
        self.deadline = now + remaining

    def _freeze(self, now: float, reason: str) -> None:
        self.remaining = max(0.0, self.deadline - now) if self.deadline else self.remaining
        self.deadline = None
        self.state = PAUSED
        self.pause_reason = reason

    def _end_window(self, now: float) -> list[str]:
        quota = self.settings.quota
        passed = self.reps >= quota
        self.history.append(WindowResult(now, self.reps, quota, passed))
        del self.history[:-20]
        if passed:
            carry = min(self.reps - quota, quota) if self.settings.carry_over else 0
            self.reps = carry
            self._start(now, float(self.settings.window_seconds))
            return []
        self.state = LOCKED
        self.deadline = None
        self.reps = 0
        self.unlock_reps = 0
        self.unlock_needed = quota
        self.last_power_command = now
        return [TV_OFF]

    def _unlock(self, now: float) -> list[str]:
        self.unlock_reps = 0
        self.unlock_needed = 0
        self.reps = 0
        self.last_power_command = now
        self._start(now, float(self.settings.window_seconds))
        return [TV_ON]

    def snapshot(self, now: float) -> dict:
        if self.state == RUNNING and self.deadline is not None:
            remaining = max(0.0, self.deadline - now)
        else:
            remaining = self.remaining
        return {
            "state": self.state,
            "pause_reason": self.pause_reason,
            "reps": self.reps,
            "quota": self.settings.quota,
            "remaining_seconds": round(remaining, 1),
            "window_seconds": self.settings.window_seconds,
            "unlock_reps": self.unlock_reps,
            "unlock_needed": self.unlock_needed,
            "total_reps": self.total_reps,
            "tv_on": self.last_tv_on,
            "history": [
                {"at": h.at, "reps": h.reps, "quota": h.quota, "passed": h.passed}
                for h in self.history
            ],
        }
