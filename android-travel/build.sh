#!/usr/bin/env bash
# 여행 가격 알림 안드로이드 APK 빌드 (Android Studio/Gradle 없이 Ubuntu 패키지 도구만 사용)
#
#   sudo apt-get install -y aapt apksigner zipalign dalvik-exchange android-sdk-platform-23 openjdk-17-jdk-headless
#   ./android-travel/build.sh      → android-travel/build/travel-alert.apk
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(dirname "$HERE")"
OUT="$HERE/build"
SDK="${ANDROID_JAR:-/usr/lib/android-sdk/platforms/android-23/android.jar}"
KEYSTORE="$ROOT/android/debug.keystore"   # TV 스쿼트 앱과 같은 시험용 키
DX="$(command -v dx || echo /usr/lib/android-sdk/build-tools/debian/dx)"

for tool in aapt zipalign apksigner javac "$DX"; do
  command -v "$tool" >/dev/null || { echo "필요한 도구가 없습니다: $tool (파일 맨 위의 apt-get 명령을 실행하세요)" >&2; exit 1; }
done
[ -f "$SDK" ] || { echo "android.jar 가 없습니다: $SDK" >&2; exit 1; }

rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/assets"
cp -r "$HERE/assets/." "$OUT/assets/"
cp "$ROOT/travel.json" "$OUT/assets/default-travel.json"

echo "== 리소스 (R.java)"
aapt package -f -m -J "$OUT/gen" -M "$HERE/AndroidManifest.xml" -S "$HERE/res" -I "$SDK"

echo "== 자바 컴파일"
find "$HERE/src" "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
javac -nowarn -encoding UTF-8 --release 8 -classpath "$SDK" -d "$OUT/classes" @"$OUT/sources.txt" 2>&1 \
  | grep -v "^warning: \[options\]" || true
[ -f "$OUT/classes/com/grehful/travelalert/MainActivity.class" ] || { echo "컴파일 실패" >&2; exit 1; }

echo "== dex 변환"
"$DX" --dex --min-sdk-version=26 --output="$OUT/classes.dex" "$OUT/classes"
# dx 는 람다(->)를 안드로이드에 없는 LambdaMetafactory 호출로 바꿔서, 실행하는 순간 앱이 멈춘다
if grep -qa "LambdaMetafactory" "$OUT/classes.dex"; then
  echo "람다(->)나 메서드 참조(::)는 쓸 수 없습니다. 익명 클래스로 바꾸세요." >&2
  exit 1
fi

echo "== APK 묶기"
aapt package -f -M "$HERE/AndroidManifest.xml" -S "$HERE/res" -A "$OUT/assets" -I "$SDK" -F "$OUT/unsigned.apk"
(cd "$OUT" && aapt add unsigned.apk classes.dex >/dev/null)
zipalign -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

echo "== 서명"
apksigner sign --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android \
  --min-sdk-version 26 --out "$OUT/travel-alert.apk" "$OUT/aligned.apk"
apksigner verify "$OUT/travel-alert.apk"

echo "완료: $OUT/travel-alert.apk ($(du -h "$OUT/travel-alert.apk" | cut -f1))"
