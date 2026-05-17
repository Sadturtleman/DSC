#!/usr/bin/env bash
# External SPS 동작 smoke test.
# 컨테이너 띄운 후 호스트에서: bash scripts/smoke-test.sh
#
# 시나리오:
#   1) /tiering-test 디렉토리 생성 후 1MB 파일 PUT
#   2) 초기 storage policy 확인 (기본: HOT)
#   3) COLD 정책 설정 + satisfyStoragePolicy 호출
#   4) 잠시 대기 후 블록 위치 확인 (ARCHIVE 로 이동 기대)
set -euo pipefail

DC="docker compose"
NN="$DC exec -T namenode"

echo "== 1. 테스트 파일 생성 =="
$NN bash -c 'hdfs dfs -mkdir -p /tiering-test && \
             dd if=/dev/urandom of=/tmp/sample.bin bs=1M count=1 status=none && \
             hdfs dfs -put -f /tmp/sample.bin /tiering-test/sample.bin'

echo "== 2. 초기 정책 =="
$NN hdfs storagepolicies -getStoragePolicy -path /tiering-test/sample.bin

echo "== 3. COLD 정책 적용 + SPS 트리거 =="
$NN hdfs storagepolicies -setStoragePolicy -path /tiering-test/sample.bin -policy COLD
$NN hdfs storagepolicies -satisfyStoragePolicy -path /tiering-test/sample.bin

echo "== 4. 30s 대기 후 블록 위치 확인 =="
sleep 30
$NN hdfs fsck /tiering-test/sample.bin -files -blocks -locations | grep -E 'ARCHIVE|DISK|SSD' || true

echo "== done =="
