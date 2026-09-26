#!/usr/bin/env bash
# 안드로이드 APK 빌드 (Android Studio/Gradle 없이 Ubuntu 패키지 도구만 사용)
#
#   sudo apt-get install -y aapt apksigner zipalign dalvik-exchange android-sdk-platform-23 openjdk-17-jdk-headless
#   ./android/build.sh            → android/build/tv-squat.apk
#
# 웹 화면(web/)을 그대로 assets/www 에 넣고, 네이티브 코드는 TV 제어와 카메라 권한만 담당한다.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(dirname "$HERE")"
OUT="$HERE/build"
SDK="${ANDROID_JAR:-/usr/lib/android-sdk/platforms/android-23/android.jar}"
KEYSTORE="$HERE/debug.keystore"

for tool in aapt dx zipalign apksigner javac; do
  if ! command -v "$tool" >/dev/null && ! [ "$tool" = dx -a -x /usr/lib/android-sdk/build-tools/debian/dx ]; then
    echo "필요한 도구가 없습니다: $tool (파일 맨 위의 apt-get 명령을 실행하세요)" >&2
    exit 1
  fi
done
DX="$(command -v dx || echo /usr/lib/android-sdk/build-tools/debian/dx)"
[ -f "$SDK" ] || { echo "android.jar 가 없습니다: $SDK" >&2; exit 1; }

if [ ! -f "$ROOT/web/vendor/pose_landmarker_lite.task" ]; then
  echo "== 인식 모델 받기"
  python3 "$ROOT/scripts/download_vendor.py"
fi

rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/assets/www/vendor/tasks-vision/wasm"

echo "== 웹 화면 복사"
cp "$ROOT"/web/{index.html,app.js,backend.js,session.js,squat.js,style.css} "$OUT/assets/www/"
cp "$ROOT/web/vendor/pose_landmarker_lite.task" "$OUT/assets/www/vendor/"
cp "$ROOT/web/vendor/tasks-vision/vision_bundle.mjs" "$OUT/assets/www/vendor/tasks-vision/"
cp "$ROOT"/web/vendor/tasks-vision/wasm/vision_wasm{,_nosimd}_internal.{js,wasm} "$OUT/assets/www/vendor/tasks-vision/wasm/"

echo "== 리소스 (R.java)"
aapt package -f -m -J "$OUT/gen" -M "$HERE/AndroidManifest.xml" -S "$HERE/res" -I "$SDK"

echo "== 자바 컴파일"
find "$HERE/src" "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
javac -nowarn -encoding UTF-8 --release 8 -classpath "$SDK" -d "$OUT/classes" @"$OUT/sources.txt" 2>&1 \
  | grep -v "^warning: \[options\]" || true
[ -f "$OUT/classes/com/grehful/tvsquat/MainActivity.class" ] || { echo "컴파일 실패" >&2; exit 1; }

echo "== dex 변환"
"$DX" --dex --min-sdk-version=26 --output="$OUT/classes.dex" "$OUT/classes"

echo "== APK 묶기"
aapt package -f -M "$HERE/AndroidManifest.xml" -S "$HERE/res" -A "$OUT/assets" -I "$SDK" \
  -F "$OUT/unsigned.apk"
(cd "$OUT" && aapt add unsigned.apk classes.dex >/dev/null)
zipalign -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

echo "== 서명"
if [ ! -f "$KEYSTORE" ]; then
  keytool -genkeypair -keystore "$KEYSTORE" -storepass android -keypass android -alias androiddebugkey \
    -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
fi
apksigner sign --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android \
  --min-sdk-version 26 --out "$OUT/tv-squat.apk" "$OUT/aligned.apk"
apksigner verify "$OUT/tv-squat.apk"

echo "완료: $OUT/tv-squat.apk ($(du -h "$OUT/tv-squat.apk" | cut -f1))"
