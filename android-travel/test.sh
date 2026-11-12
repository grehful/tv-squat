#!/usr/bin/env bash
# 안드로이드 기기 없이 PC 자바로 엔진 테스트 (org.json 만 받아서 사용)
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="$HERE/build/test"
JAR="$HERE/build/json.jar"
mkdir -p "$OUT"
[ -f "$JAR" ] || curl -sSfLo "$JAR" https://repo1.maven.org/maven2/org/json/json/20240303/json-20240303.jar
S="$HERE/src/com/grehful/travelalert"
javac -nowarn -encoding UTF-8 -cp "$JAR" -d "$OUT" "$S/Day.java" "$S/Trip.java" "$S/Prices.java" "$S/Engine.java" "$S/DemoClient.java" "$HERE/test/EngineTest.java"
java -cp "$OUT:$JAR" com.grehful.travelalert.EngineTest "$(dirname "$HERE")/travel.json" "$@"
