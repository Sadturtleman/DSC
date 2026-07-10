#!/bin/sh
# ----------------------------------------------------------------------------
# DSC 개발 환경 진단 도구 / DSC development environment doctor
#
# 이 레포를 빌드/실행하기 전에 필요한 프로그램이 있는지 확인합니다.
# Checks whether the tools required to build/run this repo are present.
#
# 자동 설치는 하지 않습니다 — 안내만 합니다 (sudo 금지 원칙).
# This script never installs anything automatically — guidance only.
#
# 사용법 / Usage:
#   ./scripts/doctor.sh              기본 검사 (java, docker)
#   ./scripts/doctor.sh --cluster    실측 배포 환경 검사 추가 (hdfs, yarn, psql)
# ----------------------------------------------------------------------------

REQUIRED_JAVA_MAJOR=11
CLUSTER_MODE=false
FAIL=0

for arg in "$@"; do
  case "$arg" in
    --cluster) CLUSTER_MODE=true ;;
  esac
done

OS_NAME="$(uname -s 2>/dev/null || echo unknown)"

ok() {
  printf '  \033[32m\xe2\x9c\x93\033[0m %s\n' "$1"
}

warn() {
  printf '  \033[33m\xe2\x9c\x97\033[0m %s (optional / 선택)\n' "$1"
}

fail() {
  printf '  \033[31m\xe2\x9c\x97\033[0m %s (required / 필수)\n' "$1"
}

hint() {
  # indented guidance line
  printf '      %s\n' "$1"
}

install_hint_java() {
  case "$OS_NAME" in
    Linux)
      hint "Ubuntu: sudo apt update && sudo apt install openjdk-11-jdk"
      ;;
    Darwin)
      hint "Mac: brew install openjdk@11"
      ;;
    *)
      hint "Ubuntu: sudo apt install openjdk-11-jdk"
      hint "Mac:    brew install openjdk@11"
      ;;
  esac
  hint "Windows: scripts\\doctor.cmd 를 사용하세요 / use scripts\\doctor.cmd"
}

install_hint_docker() {
  case "$OS_NAME" in
    Linux)
      hint "Ubuntu: sudo apt install docker.io (또는 공식 가이드 참고 / or see official guide)"
      ;;
    Darwin)
      hint "Mac: brew install --cask docker"
      ;;
    *)
      hint "Ubuntu: sudo apt install docker.io"
      hint "Mac:    brew install --cask docker"
      ;;
  esac
  hint "가이드 / Guide: https://docs.docker.com/engine/install/"
}

install_hint_psql() {
  case "$OS_NAME" in
    Linux)
      hint "Ubuntu: sudo apt install postgresql-client"
      ;;
    Darwin)
      hint "Mac: brew install libpq"
      ;;
    *)
      hint "Ubuntu: sudo apt install postgresql-client"
      hint "Mac:    brew install libpq"
      ;;
  esac
}

echo "DSC 환경 진단 / DSC environment doctor"
echo "========================================"

# ---- [필수/required] java ---------------------------------------------------
echo ""
echo "[필수 / required]"

if ! command -v java >/dev/null 2>&1; then
  fail "Java: 찾을 수 없음 / not found (11 이상 필요 / 11+ required)"
  install_hint_java
  FAIL=1
else
  version_line="$(java -version 2>&1 | head -n 1)"
  version="$(echo "$version_line" | sed -n 's/.*"\([0-9][0-9.]*\).*/\1/p')"
  if [ -z "$version" ]; then
    fail "Java: 버전을 확인할 수 없음 / could not parse version ($version_line)"
    install_hint_java
    FAIL=1
  else
    major="$(echo "$version" | cut -d. -f1)"
    if [ "$major" = "1" ]; then
      # old-style versioning, e.g. 1.8.0_301 -> major = 8
      major="$(echo "$version" | cut -d. -f2)"
    fi
    if [ "$major" -ge "$REQUIRED_JAVA_MAJOR" ] 2>/dev/null; then
      ok "Java: $version (>= $REQUIRED_JAVA_MAJOR)"
    else
      fail "Java: $version 발견, $REQUIRED_JAVA_MAJOR 이상 필요 / found, but $REQUIRED_JAVA_MAJOR+ required"
      install_hint_java
      FAIL=1
    fi
  fi
fi

# ---- [선택/optional] docker -------------------------------------------------
echo ""
echo "[선택 / optional]"

if ! command -v docker >/dev/null 2>&1; then
  warn "Docker: 찾을 수 없음 / not found"
  hint "통합 테스트(PendingJobRepositoryTest)만 실행 불가, CI에서는 검증됨."
  hint "Integration test (PendingJobRepositoryTest) will be skipped locally; verified in CI."
  install_hint_docker
elif ! docker info >/dev/null 2>&1; then
  warn "Docker: 설치되어 있으나 데몬이 실행 중이 아님 / installed but daemon is not running"
  hint "통합 테스트(PendingJobRepositoryTest)만 실행 불가, CI에서는 검증됨."
  hint "Integration test (PendingJobRepositoryTest) will be skipped locally; verified in CI."
  hint "Docker Desktop을 실행하세요 / Please start Docker Desktop."
  hint "로컬에서 돌리려면 / To run locally: https://docs.docker.com/engine/install/"
else
  docker_version="$(docker version --format '{{.Server.Version}}' 2>/dev/null)"
  ok "Docker: ${docker_version:-installed} (daemon running)"
fi

# ---- [실측 배포 환경 한정 / cluster only] hdfs, yarn, psql -----------------
if [ "$CLUSTER_MODE" = "true" ]; then
  echo ""
  echo "[실측 배포 환경 한정 / cluster deployment only, --cluster]"

  if command -v hdfs >/dev/null 2>&1; then
    ok "hdfs: found"
  else
    warn "hdfs: 찾을 수 없음 / not found"
    hint "패키지 매니저로 설치되지 않습니다. INFRA.md의 Hadoop 설치 절차를 따르세요."
    hint "Not installable via apt/brew. Follow the Hadoop setup steps in INFRA.md."
  fi

  if command -v yarn >/dev/null 2>&1; then
    ok "yarn: found"
  else
    warn "yarn: 찾을 수 없음 / not found"
    hint "패키지 매니저로 설치되지 않습니다. INFRA.md의 Hadoop 설치 절차를 따르세요."
    hint "Not installable via apt/brew. Follow the Hadoop setup steps in INFRA.md."
  fi

  if command -v psql >/dev/null 2>&1; then
    ok "psql: found"
  else
    warn "psql: 찾을 수 없음 / not found"
    install_hint_psql
  fi
fi

echo ""
echo "========================================"
if [ "$FAIL" -eq 0 ]; then
  echo "필수 항목 통과 / all required checks passed."
  exit 0
else
  echo "필수 항목 실패 — 위 안내를 따라 설치 후 다시 실행하세요."
  echo "Required checks failed — install per the guidance above and re-run."
  exit 1
fi
