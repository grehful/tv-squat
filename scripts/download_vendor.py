"""인식 모델/라이브러리를 web/vendor 에 받아둔다 (인터넷 없이 쓰거나 안드로이드 앱에 넣을 때).

    python scripts/download_vendor.py
"""

import io
import os
import tarfile
import urllib.request

VERSION = "1.0.1"
# npm 레지스트리의 패키지 묶음에서 필요한 파일만 꺼낸다
PACKAGE = f"https://registry.npmjs.org/@mediapipe/tasks-vision/-/tasks-vision-{VERSION}.tgz"
PACKAGE_FILES = [
    "vision_bundle.mjs",
    "wasm/vision_wasm_internal.js",
    "wasm/vision_wasm_internal.wasm",
    "wasm/vision_wasm_nosimd_internal.js",
    "wasm/vision_wasm_nosimd_internal.wasm",
]
MODEL = ("https://storage.googleapis.com/mediapipe-models/pose_landmarker/"
         "pose_landmarker_lite/float16/latest/pose_landmarker_lite.task")

root = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "web", "vendor")

print(f"받는 중: @mediapipe/tasks-vision {VERSION}")
with urllib.request.urlopen(PACKAGE) as r:
    data = r.read()
with tarfile.open(fileobj=io.BytesIO(data), mode="r:gz") as tar:
    for rel in PACKAGE_FILES:
        dest = os.path.join(root, "tasks-vision", rel)
        os.makedirs(os.path.dirname(dest), exist_ok=True)
        with tar.extractfile(f"package/{rel}") as src, open(dest, "wb") as out:
            out.write(src.read())

print("받는 중: pose_landmarker_lite.task")
os.makedirs(root, exist_ok=True)
urllib.request.urlretrieve(MODEL, os.path.join(root, "pose_landmarker_lite.task"))
print(f"완료: {root}")
