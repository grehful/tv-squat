"""인식 모델/라이브러리를 web/vendor 에 받아둔다 (인터넷 없이 쓰거나 CDN이 막혔을 때).

    python scripts/download_vendor.py
"""

import os
import urllib.request

VERSION = "1.0.1"
CDN = f"https://cdn.jsdelivr.net/npm/@mediapipe/tasks-vision@{VERSION}"
FILES = {
    "tasks-vision/vision_bundle.mjs": f"{CDN}/vision_bundle.mjs",
    "tasks-vision/wasm/vision_wasm_internal.js": f"{CDN}/wasm/vision_wasm_internal.js",
    "tasks-vision/wasm/vision_wasm_internal.wasm": f"{CDN}/wasm/vision_wasm_internal.wasm",
    "tasks-vision/wasm/vision_wasm_nosimd_internal.js": f"{CDN}/wasm/vision_wasm_nosimd_internal.js",
    "tasks-vision/wasm/vision_wasm_nosimd_internal.wasm": f"{CDN}/wasm/vision_wasm_nosimd_internal.wasm",
    "pose_landmarker_lite.task": "https://storage.googleapis.com/mediapipe-models/pose_landmarker/"
                                 "pose_landmarker_lite/float16/latest/pose_landmarker_lite.task",
}

root = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "web", "vendor")
for rel, url in FILES.items():
    dest = os.path.join(root, rel)
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    print(f"받는 중: {rel}")
    urllib.request.urlretrieve(url, dest)
print(f"완료: {root}")
