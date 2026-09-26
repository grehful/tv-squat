"""설정값 (JSON 파일로 저장되어 재시작해도 유지됨)."""

from __future__ import annotations

import json
import math
import os
from dataclasses import asdict, dataclass, fields


@dataclass
class Settings:
    # 스쿼트 1개당 몇 초 (30이면 30초에 1개)
    seconds_per_squat: int = 30
    # 몇 분마다 할당량을 검사할지
    window_minutes: float = 5.0
    # 함께 하는 아이 수 (할당량 = 기본 할당량 x 인원)
    players: int = 1
    # 할당량보다 더 한 개수를 다음 구간으로 넘겨줄지
    carry_over: bool = False
    # TV가 꺼진 뒤 할당량만큼 스쿼트를 하면 TV를 다시 켜줄지
    auto_turn_on: bool = True
    # 무릎 각도 기준 (도). 이보다 작으면 "앉음", 크면 "일어섬"
    down_angle: int = 100
    up_angle: int = 160
    # 설정 변경용 부모 PIN (빈 문자열이면 PIN 없음)
    pin: str = ""

    @property
    def window_seconds(self) -> int:
        return max(10, int(round(self.window_minutes * 60)))

    @property
    def quota(self) -> int:
        per_player = max(1, math.ceil(self.window_seconds / max(1, self.seconds_per_squat)))
        return per_player * max(1, self.players)

    def update(self, data: dict) -> None:
        """사용자 입력을 검증하면서 반영한다. 잘못된 값은 ValueError."""
        known = {f.name: f for f in fields(self)}
        for key, value in data.items():
            if key not in known:
                continue
            kind = known[key].type  # "bool", "int", "float", "str" (문자열 어노테이션)
            if kind == "bool":
                value = value if isinstance(value, bool) else str(value).lower() in ("1", "true", "yes", "on")
            elif kind == "int":
                value = int(value)
            elif kind == "float":
                value = float(value)
            else:
                value = str(value)
            setattr(self, key, value)
        self.validate()

    def validate(self) -> None:
        if not 3 <= self.seconds_per_squat <= 600:
            raise ValueError("seconds_per_squat는 3~600초 사이여야 합니다")
        if not 0.5 <= self.window_minutes <= 120:
            raise ValueError("window_minutes는 0.5~120분 사이여야 합니다")
        if not 1 <= self.players <= 4:
            raise ValueError("players는 1~4명이어야 합니다")
        if not 30 <= self.down_angle < self.up_angle <= 180:
            raise ValueError("각도는 30 <= down_angle < up_angle <= 180 이어야 합니다")

    def public_dict(self) -> dict:
        data = asdict(self)
        data.pop("pin")
        data["pin_required"] = bool(self.pin)
        data["quota"] = self.quota
        data["window_seconds"] = self.window_seconds
        return data


def load_settings(path: str) -> Settings:
    settings = Settings()
    if os.path.exists(path):
        with open(path, encoding="utf-8") as f:
            settings.update(json.load(f))
    return settings


def save_settings(settings: Settings, path: str) -> None:
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(asdict(settings), f, ensure_ascii=False, indent=2)
    os.replace(tmp, path)
