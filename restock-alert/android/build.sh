#!/usr/bin/env bash
# 재입고 알림 안드로이드 APK 빌드 (Android Studio/Gradle 없이)
#
#   sudo apt-get install -y aapt apksigner zipalign dalvik-exchange openjdk-17-jdk-headless
#   ./restock-alert/android/build.sh      → restock-alert/android/build/restock-alert.apk
#
# 포그라운드 서비스/알림 채널 API(26~34)가 필요해서, Ubuntu 패키지의 android-23 대신
# Maven Central 의 Android 14 프레임워크 jar(Robolectric android-all)를 받아 컴파일한다.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="$HERE/build"
CACHE="${XDG_CACHE_HOME:-$HOME/.cache}/restock-alert"
ANDROID_ALL_VERSION="14-robolectric-10818077"
SDK="${ANDROID_JAR:-$CACHE/android-all-$ANDROID_ALL_VERSION.jar}"
KEYSTORE="$HERE/../../android/debug.keystore"   # TV 스쿼트 앱과 같은 시험용 키

for tool in aapt zipalign apksigner javac; do
  command -v "$tool" >/dev/null || { echo "필요한 도구가 없습니다: $tool (파일 맨 위의 apt-get 명령을 실행하세요)" >&2; exit 1; }
done
DX="$(command -v dx || echo /usr/lib/android-sdk/build-tools/debian/dx)"
[ -x "$DX" ] || { echo "dx 가 없습니다 (dalvik-exchange 패키지)" >&2; exit 1; }

if [ ! -f "$SDK" ]; then
  echo "== Android 14 프레임워크 jar 받기 (약 130MB, 처음 한 번만)"
  mkdir -p "$(dirname "$SDK")"
  curl -fL --retry 3 -o "$SDK.part" \
    "https://repo1.maven.org/maven2/org/robolectric/android-all/$ANDROID_ALL_VERSION/android-all-$ANDROID_ALL_VERSION.jar"
  mv "$SDK.part" "$SDK"
fi

rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes"

echo "== 리소스 (R.java)"
aapt package -f -m -J "$OUT/gen" -M "$HERE/AndroidManifest.xml" -S "$HERE/res" -I "$SDK"

echo "== 자바 컴파일"
find "$HERE/src" "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
javac -nowarn -encoding UTF-8 --release 8 -classpath "$SDK" -d "$OUT/classes" @"$OUT/sources.txt" 2>&1 \
  | grep -v "^warning: \[options\]" || true
[ -f "$OUT/classes/com/grehful/restockalert/MainActivity.class" ] || { echo "컴파일 실패" >&2; exit 1; }

echo "== dex 변환"
"$DX" --dex --min-sdk-version=26 --output="$OUT/classes.dex" "$OUT/classes"
# dx 는 람다를 안드로이드에 없는 LambdaMetafactory 호출로 바꿔서 앱이 켜지자마자 죽는다.
if grep -q LambdaMetafactory "$OUT/classes.dex"; then
  echo "람다(->, ::)는 쓸 수 없습니다. 익명 클래스로 바꿔 주세요." >&2
  exit 1
fi

echo "== APK 묶기"
aapt package -f -M "$HERE/AndroidManifest.xml" -S "$HERE/res" -I "$SDK" -F "$OUT/unsigned.apk"
(cd "$OUT" && aapt add unsigned.apk classes.dex >/dev/null)
zipalign -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

echo "== 서명"
apksigner sign --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android \
  --min-sdk-version 26 --out "$OUT/restock-alert.apk" "$OUT/aligned.apk"
apksigner verify "$OUT/restock-alert.apk"

echo "완료: $OUT/restock-alert.apk ($(du -h "$OUT/restock-alert.apk" | cut -f1))"
