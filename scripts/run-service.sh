#!/bin/sh
# ----------------------------------------------------------------------------
# hdfs-auto-tiering 실행 래퍼 / hdfs-auto-tiering launch wrapper
#
# Main 클래스 자체는 수정하지 않는다는 원칙에 따라, jar 실행 전에 Java 버전을
# 검사하는 책임은 이 래퍼가 대신 진다.
# Per the "never modify existing module source" rule, this wrapper — not
# Main.java — is responsible for checking the Java version before launch.
#
# 사용법 / Usage:
#   ./scripts/run-service.sh [config.yaml]
# ----------------------------------------------------------------------------

REQUIRED_JAVA_MAJOR=11
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
JAR_PATH="$REPO_ROOT/hdfs-auto-tiering/target/hdfs-auto-tiering.jar"

if ! command -v java >/dev/null 2>&1; then
  echo "[오류/error] Java를 찾을 수 없습니다 / Java not found. $REQUIRED_JAVA_MAJOR 이상 필요 / $REQUIRED_JAVA_MAJOR+ required."
  echo "먼저 실행하세요 / Run this first: ./scripts/doctor.sh"
  exit 1
fi

version_line="$(java -version 2>&1 | head -n 1)"
version="$(echo "$version_line" | sed -n 's/.*"\([0-9][0-9.]*\).*/\1/p')"
major="$(echo "$version" | cut -d. -f1)"
if [ "$major" = "1" ]; then
  major="$(echo "$version" | cut -d. -f2)"
fi

if [ -z "$major" ] || ! [ "$major" -ge "$REQUIRED_JAVA_MAJOR" ] 2>/dev/null; then
  echo "[오류/error] Java $REQUIRED_JAVA_MAJOR 이상이 필요합니다. 발견됨 / found: ${version:-unknown} ($version_line)"
  echo "먼저 실행하세요 / Run this first: ./scripts/doctor.sh"
  exit 1
fi

if [ ! -f "$JAR_PATH" ]; then
  echo "[오류/error] $JAR_PATH 를 찾을 수 없습니다 / not found."
  echo "먼저 빌드하세요 / Build it first: ./mvnw -pl hdfs-auto-tiering -am clean package -DskipTests"
  exit 1
fi

exec java -jar "$JAR_PATH" "$@"
