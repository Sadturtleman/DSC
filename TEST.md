# HDFS Auto-Tiering 성공 기준 및 정량 평가 계획

이 문서는 HDFS Auto-Tiering 프로젝트가 “동작한다”는 수준을 넘어, 교수자 관점에서 재현 가능하고 반박 가능한 정량 근거로 성능을 설명하기 위한 평가 기준을 정의한다.

평가는 성공 기준 3축(Accuracy, Stability, Performance)과 효용성 설명 1축(Effectiveness)으로 구성한다.

| 영역 | 지표 ID | 검증 내용 | 성공 기준 |
|---|---:|---|---|
| Accuracy | A1 | FSImage 분석 결과와 실제 HDFS 파일 시스템 간 티어링 필요 파일 리스트 일치율 | Precision = 1.00, Recall = 1.00 |
| Accuracy | A2 | 스코어링 우선순위와 독립 oracle 순위의 Spearman 순위 상관계수 | rho >= 0.95 |
| Accuracy | A3 | 임계 시간을 초과한 파일의 목표 정책 전환 완료율 | 100% |
| Stability | S1 | 서비스 가동 중 Active NameNode RPC Queue Time 상승폭 | 5% 이하 |
| Stability | S2 | 프로세스 강제 종료 후 YARN 재실행 및 서비스 복구 시간 | 30초 이내 |
| Performance | P1 | GB당 평균 이관 소요 시간: 오토티어링 vs 수동 기준선 | 차이와 오버헤드율 산출, 권장 10% 이내 |
| Effectiveness | E1 | HOT에서 WARM/COLD로 이관된 데이터의 월간 저장 비용 절감액 | 절감액과 절감률 산출 |
| Effectiveness | E2 | 기존 recursive scan 대비 NameNode 부하 회피 효과 | RPC/Heap 증가량 비교 |
| Effectiveness | E3 | 워크로드별 적합성과 한계 도출 | 적용 권장/보완 필요 조건 제시 |

## 0. 공통 평가 조건

### 0.1 테스트 범위

본 문서는 서버(Ubuntu/Hadoop/YARN/PostgreSQL)에서 실행하는 검증 절차를 정의한다. 개발 PC에서는 코드 정적 분석만 수행하며, 서버 E2E 실행 결과는 아래 산출물로 제출한다.

### 0.2 화이트리스트 설정

현재 `application.yaml`의 검증용 화이트리스트는 `/test/metric`을 대상으로 한다. 이 문서의 정량 평가 스크립트와 INFRA.md의 E2E 검증 스크립트는 모두 `/test/metric` 하위 경로를 사용하므로, 검증용 설정 파일에는 다음 값이 유지되어야 한다.

```yaml
scoring:
  target-directories:
    - /test/metric
```

화이트리스트에 `/test/metric`이 없으면 ScoringEngine이 안전하게 해당 파일을 무시하므로, A1/A2/A3와 INFRA E2E 검증이 `NOT_CREATED` 또는 0건으로 실패한다.

### 0.3 공통 환경 변수

아래 변수는 모든 테스트 스크립트에서 공통으로 사용한다.

```bash
export HADOOP_CONF_DIR="$HOME/hadoop-conf/namenode"
export HADOOP_HOME="$HOME/hadoop"
export PSQL_CMD="psql -h localhost -U dsc -d dsc_tiering -qtA"
export TEST_ROOT="/test/metric"
export RUN_ID="$(date +%Y%m%d%H%M%S)"
```

### 0.4 제출 산출물

각 테스트 실행 후 다음 파일 또는 화면 출력을 보관한다.

| 산출물 | 목적 |
|---|---|
| `pending_jobs` 조회 결과 | Scoring, scheduling, tracking 상태 전이 증거 |
| HDFS `getStoragePolicy` 또는 `fsck -blocks -locations` 출력 | 실제 정책/블록 위치 검증 |
| JMX RPC Queue Time 샘플 로그 | NameNode 안정성 검증 |
| YARN `app -status`, `yarn logs` 출력 | 장애 복구 검증 |
| 각 스크립트의 `[PASS]`, `[FAIL]` 출력 | 최종 판정 근거 |

## 1. Accuracy

Accuracy는 “올바른 파일을 골랐는가”, “올바른 순서로 처리했는가”, “목표 정책으로 끝까지 전환했는가”를 분리해서 검증한다.

### A1. 티어링 필요 파일 리스트 일치율

**정의**

테스트 데이터 집합에서 사람이 의도적으로 만든 ground truth 집합 `G`와 ScoringEngine이 DB에 생성한 job 집합 `D`를 비교한다.

```text
Precision = |G ∩ D| / |D|
Recall    = |G ∩ D| / |G|
```

성공 기준은 `Precision = 1.00`, `Recall = 1.00`이다. 즉, 불필요한 파일을 잡아도 실패이고, 필요한 파일을 놓쳐도 실패다.

**테스트 데이터 설계**

- `/test/metric/accuracy/<RUN_ID>/cold_*`: 100일 전 접근, HOT에서 COLD로 이동 필요
- `/test/metric/accuracy/<RUN_ID>/warm_*`: 60일 전 접근, HOT에서 WARM으로 이동 필요
- `/test/metric/accuracy/<RUN_ID>/hot_*`: 최근 접근, 이동 불필요

```bash
cat > ~/test-metric-a1-list-match.sh <<'SCRIPT'
#!/usr/bin/env bash
set -euo pipefail

TEST_DIR="${TEST_ROOT:-/test/metric}/accuracy/${RUN_ID:-manual}"
PSQL="${PSQL_CMD:-psql -h localhost -U dsc -d dsc_tiering -qtA}"

hdfs dfs -rm -r -skipTrash "$TEST_DIR" 2>/dev/null || true
hdfs dfs -mkdir -p "$TEST_DIR"
hdfs storagepolicies -setStoragePolicy -path "$TEST_DIR" -policy ALL_SSD >/dev/null
$PSQL -c "DELETE FROM pending_jobs WHERE file_path LIKE '${TEST_DIR}%';" >/dev/null

for i in $(seq -w 1 20); do
  hdfs dfs -touchz "$TEST_DIR/cold_${i}.dat"
  hdfs dfs -touchz "$TEST_DIR/warm_${i}.dat"
  hdfs dfs -touchz "$TEST_DIR/hot_${i}.dat"
  hdfs storagepolicies -setStoragePolicy -path "$TEST_DIR/cold_${i}.dat" -policy ALL_SSD >/dev/null
  hdfs storagepolicies -setStoragePolicy -path "$TEST_DIR/warm_${i}.dat" -policy ALL_SSD >/dev/null
  hdfs storagepolicies -setStoragePolicy -path "$TEST_DIR/hot_${i}.dat" -policy ALL_SSD >/dev/null
  hdfs dfs -touch -a -t "$(date -d '100 days ago' +%Y%m%d:%H%M%S)" "$TEST_DIR/cold_${i}.dat"
  hdfs dfs -touch -a -t "$(date -d '60 days ago' +%Y%m%d:%H%M%S)" "$TEST_DIR/warm_${i}.dat"
done

hdfs dfsadmin -safemode enter >/dev/null
hdfs dfsadmin -saveNamespace >/dev/null
hdfs dfsadmin -safemode leave >/dev/null

echo "[INFO] ScoringEngine 1회 사이클이 실행되도록 데몬을 재시작하거나 로컬 jar를 실행하십시오."
echo "[INFO] 대기 후 아래 검증을 수행합니다."
sleep 60

EXPECTED=40
DETECTED=$($PSQL -c "SELECT count(*) FROM pending_jobs WHERE file_path LIKE '${TEST_DIR}%' AND status IN ('PENDING','DISPATCHED','IN_PROGRESS','COMPLETED');")
FALSE_POSITIVE=$($PSQL -c "SELECT count(*) FROM pending_jobs WHERE file_path LIKE '${TEST_DIR}/hot_%';")
COLD_DETECTED=$($PSQL -c "SELECT count(*) FROM pending_jobs WHERE file_path LIKE '${TEST_DIR}/cold_%' AND target_tier='COLD';")
WARM_DETECTED=$($PSQL -c "SELECT count(*) FROM pending_jobs WHERE file_path LIKE '${TEST_DIR}/warm_%' AND target_tier='WARM';")

echo "expected=$EXPECTED detected=$DETECTED false_positive=$FALSE_POSITIVE cold=$COLD_DETECTED warm=$WARM_DETECTED"

if [ "$DETECTED" -eq "$EXPECTED" ] && [ "$FALSE_POSITIVE" -eq 0 ] && [ "$COLD_DETECTED" -eq 20 ] && [ "$WARM_DETECTED" -eq 20 ]; then
  echo "[PASS] A1 Precision=1.00 Recall=1.00"
else
  echo "[FAIL] A1 파일 리스트 일치율 실패"
  $PSQL -c "SELECT file_path, current_tier, target_tier, priority_score, status FROM pending_jobs WHERE file_path LIKE '${TEST_DIR}%' ORDER BY file_path;"
  exit 1
fi
SCRIPT
chmod +x ~/test-metric-a1-list-match.sh
```

### A2. Scoring 순위 정확도

**정의**

ScoringEngine의 `priority_score` 정렬 순위와 별도로 정의한 oracle 순위를 비교한다. Oracle은 테스트 파일명에 내장된 기대 순위를 사용한다.

```text
rank_01.dat: 가장 먼저 처리되어야 하는 파일
rank_20.dat: 가장 나중에 처리되어야 하는 파일
```

Spearman 순위 상관계수는 다음 수식으로 계산한다.

```text
rho = 1 - (6 * Σ d_i^2) / (n * (n^2 - 1))
```

여기서 `d_i`는 파일 `i`의 oracle rank와 실제 DB rank의 차이다. 성공 기준은 `rho >= 0.95`이다.

```bash
cat > ~/test-metric-a2-spearman.py <<'PY'
#!/usr/bin/env python3
import os
import re
import subprocess
import sys

test_dir = os.environ.get("SPEARMAN_TEST_DIR")
if not test_dir:
    print("[FAIL] SPEARMAN_TEST_DIR is required")
    sys.exit(1)

psql = os.environ.get("PSQL_CMD", "psql -h localhost -U dsc -d dsc_tiering -qtA")
query = (
    "SELECT file_path, priority_score "
    "FROM pending_jobs "
    f"WHERE file_path LIKE '{test_dir}/rank_%' "
    "ORDER BY priority_score ASC, file_path ASC;"
)

rows = subprocess.check_output(psql.split() + ["-c", query], text=True).strip().splitlines()
actual = []
for row in rows:
    if not row.strip():
        continue
    path, _score = row.split("|")
    m = re.search(r"rank_(\d+)\.dat$", path)
    if not m:
        continue
    oracle_rank = int(m.group(1))
    actual.append((path, oracle_rank))

n = len(actual)
if n < 10:
    print(f"[FAIL] Spearman sample too small: n={n}")
    sys.exit(1)

sum_d2 = 0
for actual_rank, (_path, oracle_rank) in enumerate(actual, start=1):
    sum_d2 += (actual_rank - oracle_rank) ** 2

rho = 1 - (6 * sum_d2) / (n * (n * n - 1))
print(f"n={n} spearman_rho={rho:.4f}")
if rho >= 0.95:
    print("[PASS] A2 Spearman rho >= 0.95")
else:
    print("[FAIL] A2 Spearman rho < 0.95")
    for actual_rank, (path, oracle_rank) in enumerate(actual, start=1):
        print(f"actual={actual_rank:02d} oracle={oracle_rank:02d} path={path}")
    sys.exit(1)
PY
chmod +x ~/test-metric-a2-spearman.py
```

Rank 데이터 생성 예시는 다음과 같다. `rank_01`이 가장 오래되고 가장 큰 파일이므로 oracle 기준 최우선이다.

```bash
cat > ~/prepare-metric-a2-ranking-data.sh <<'SCRIPT'
#!/usr/bin/env bash
set -euo pipefail

TEST_DIR="${TEST_ROOT:-/test/metric}/ranking/${RUN_ID:-manual}"
PSQL="${PSQL_CMD:-psql -h localhost -U dsc -d dsc_tiering -qtA}"

hdfs dfs -rm -r -skipTrash "$TEST_DIR" 2>/dev/null || true
hdfs dfs -mkdir -p "$TEST_DIR"
hdfs storagepolicies -setStoragePolicy -path "$TEST_DIR" -policy ALL_SSD >/dev/null
$PSQL -c "DELETE FROM pending_jobs WHERE file_path LIKE '${TEST_DIR}%';" >/dev/null

for rank in $(seq -w 1 20); do
  days=$((140 - 2 * 10#$rank))
  mb=$((64 - 10#$rank))
  if [ "$mb" -lt 8 ]; then mb=8; fi
  local_file="/tmp/rank_${rank}.dat"
  dd if=/dev/zero of="$local_file" bs=1M count="$mb" 2>/dev/null
  hdfs dfs -put -f "$local_file" "$TEST_DIR/rank_${rank}.dat"
  hdfs storagepolicies -setStoragePolicy -path "$TEST_DIR/rank_${rank}.dat" -policy ALL_SSD >/dev/null
  hdfs dfs -touch -a -t "$(date -d "${days} days ago" +%Y%m%d:%H%M%S)" "$TEST_DIR/rank_${rank}.dat"
  rm -f "$local_file"
done

hdfs dfsadmin -safemode enter >/dev/null
hdfs dfsadmin -saveNamespace >/dev/null
hdfs dfsadmin -safemode leave >/dev/null

echo "export SPEARMAN_TEST_DIR=$TEST_DIR"
echo "[INFO] ScoringEngine 실행 후: SPEARMAN_TEST_DIR=$TEST_DIR ~/test-metric-a2-spearman.py"
SCRIPT
chmod +x ~/prepare-metric-a2-ranking-data.sh
```

### A3. 목표 정책 전환율

**정의**

임계 시간을 초과한 파일이 ScoringEngine에서 목표 티어를 부여받고, Scheduler와 Tracker를 거쳐 실제 HDFS 정책까지 목표 정책으로 전환되었는지 확인한다.

```text
전환율 = 목표 정책 전환 완료 파일 수 / 전환 대상 파일 수
성공 기준 = 100%
```

```bash
cat > ~/test-metric-a3-policy-completion.sh <<'SCRIPT'
#!/usr/bin/env bash
set -euo pipefail

TEST_DIR="${TEST_ROOT:-/test/metric}/policy/${RUN_ID:-manual}"
TEST_FILE="$TEST_DIR/cold_1gb.dat"
LOCAL_FILE="/tmp/cold_1gb.dat"
PSQL="${PSQL_CMD:-psql -h localhost -U dsc -d dsc_tiering -qtA}"

hdfs dfs -rm -r -skipTrash "$TEST_DIR" 2>/dev/null || true
hdfs dfs -mkdir -p "$TEST_DIR"
hdfs storagepolicies -setStoragePolicy -path "$TEST_DIR" -policy ALL_SSD >/dev/null
$PSQL -c "DELETE FROM pending_jobs WHERE file_path LIKE '${TEST_DIR}%';" >/dev/null

dd if=/dev/zero of="$LOCAL_FILE" bs=1M count=1024 2>/dev/null
hdfs dfs -put -f "$LOCAL_FILE" "$TEST_FILE"
hdfs storagepolicies -setStoragePolicy -path "$TEST_FILE" -policy ALL_SSD >/dev/null
hdfs dfs -touch -a -t "$(date -d '120 days ago' +%Y%m%d:%H%M%S)" "$TEST_FILE"
rm -f "$LOCAL_FILE"

hdfs dfsadmin -safemode enter >/dev/null
hdfs dfsadmin -saveNamespace >/dev/null
hdfs dfsadmin -safemode leave >/dev/null

echo "[INFO] ScoringEngine/Scheduler/Tracker 실행 대기"
for _ in $(seq 1 120); do
  STATUS=$($PSQL -c "SELECT status FROM pending_jobs WHERE file_path='${TEST_FILE}' ORDER BY job_id DESC LIMIT 1;")
  if [ "$STATUS" = "COMPLETED" ]; then
    POLICY=$(hdfs storagepolicies -getStoragePolicy -path "$TEST_FILE" 2>/dev/null | tr -d '\n')
    FSCK=$(hdfs fsck "$TEST_FILE" -files -blocks -locations 2>/dev/null || true)
    ARCHIVE_COUNT=$(echo "$FSCK" | grep -o "ARCHIVE" | wc -l)
    NON_ARCHIVE_COUNT=$(echo "$FSCK" | grep -E "\[SSD\]|\[DISK\]" | wc -l)
    echo "status=$STATUS policy=$POLICY archive_blocks=$ARCHIVE_COUNT non_archive_blocks=$NON_ARCHIVE_COUNT"
    if echo "$POLICY" | grep -q "COLD" && [ "$ARCHIVE_COUNT" -gt 0 ] && [ "$NON_ARCHIVE_COUNT" -eq 0 ]; then
      echo "[PASS] A3 목표 정책 전환율 100%"
      exit 0
    fi
  fi
  sleep 5
done

echo "[FAIL] A3 제한 시간 내 목표 정책 전환 실패"
$PSQL -c "SELECT job_id, file_path, status, current_tier, target_tier, retry_count, last_error FROM pending_jobs WHERE file_path='${TEST_FILE}';"
hdfs storagepolicies -getStoragePolicy -path "$TEST_FILE" || true
hdfs fsck "$TEST_FILE" -files -blocks -locations || true
exit 1
SCRIPT
chmod +x ~/test-metric-a3-policy-completion.sh
```

## 2. Stability

Stability는 “오토티어링이 NameNode를 흔들지 않는가”와 “서비스 프로세스가 죽어도 YARN이 회복시키는가”를 검증한다.

### S1. Active NameNode RPC Queue Time 상승폭

**정의**

NameNode JMX의 `RpcQueueTimeAvg`를 서비스 실행 전과 실행 중에 동일한 방식으로 표본 수집한다.

```text
상승폭(%) = (during_avg - baseline_avg) / max(baseline_avg, 0.1ms) * 100
성공 기준 = 5% 이하
```

단일 샘플은 우연성이 크므로 최소 10개 이상 샘플의 평균을 사용한다.

```bash
cat > ~/test-metric-s1-rpc-queue.sh <<'SCRIPT'
#!/usr/bin/env bash
set -euo pipefail

sample_rpc_queue() {
  local count=${1:-10}
  local interval=${2:-1}
  local sum=0
  for _ in $(seq 1 "$count"); do
    v=$(curl -s "http://localhost:9870/jmx?qry=Hadoop:service=NameNode,name=RpcActivityForPort8020" \
      | grep -o '"RpcQueueTimeAvg" *: *[0-9.]*' \
      | head -1 \
      | awk -F ':' '{gsub(/[ ,]/, "", $2); print $2}')
    if [ -z "$v" ]; then v=0; fi
    sum=$(awk "BEGIN {print $sum + $v}")
    sleep "$interval"
  done
  awk "BEGIN {printf \"%.6f\", $sum / $count}"
}

BASE=$(sample_rpc_queue 10 1)
echo "baseline_avg_ms=$BASE"

echo "[INFO] 지금 오토티어링 데몬을 실행하거나 E2E 테스트를 시작하십시오."
sleep 5
DURING=$(sample_rpc_queue 20 1)
echo "during_avg_ms=$DURING"

PERCENT=$(awk "BEGIN {base=$BASE; if (base < 0.1) base=0.1; printf \"%.4f\", (($DURING - $BASE) / base) * 100}")
echo "rpc_queue_increase_percent=$PERCENT"

if awk "BEGIN {exit !($PERCENT <= 5.0)}"; then
  echo "[PASS] S1 RPC Queue Time 상승폭 5% 이하"
else
  echo "[FAIL] S1 RPC Queue Time 상승폭 초과"
  exit 1
fi
SCRIPT
chmod +x ~/test-metric-s1-rpc-queue.sh
```

### S2. YARN 기반 30초 내 서비스 복구

**정의**

실행 중인 `hdfs-auto-tiering` 컨테이너 또는 Java 프로세스를 강제로 종료한 뒤, YARN Service가 다시 `RUNNING` 또는 `STABLE` 상태로 복구되는 시간을 측정한다.

성공 기준은 30초 이내다.

```bash
cat > ~/test-metric-s2-yarn-recovery.sh <<'SCRIPT'
#!/usr/bin/env bash
set -euo pipefail

SERVICE="hdfs-auto-tiering"
APP_STATUS=$(yarn app -status "$SERVICE" 2>/dev/null || true)
if [ -z "$APP_STATUS" ]; then
  echo "[FAIL] YARN service not found: $SERVICE"
  exit 1
fi

PID=$(ps -ef | grep hdfs-auto-tiering.jar | grep -v grep | awk '{print $2}' | head -1)
if [ -z "$PID" ]; then
  echo "[FAIL] hdfs-auto-tiering java process not found"
  exit 1
fi

echo "killing_pid=$PID"
kill -9 "$PID"
START=$(date +%s)

for _ in $(seq 1 30); do
  STATE=$(yarn app -status "$SERVICE" 2>/dev/null | grep -E "State *:" | awk '{print $3}' | head -1)
  NEW_PID=$(ps -ef | grep hdfs-auto-tiering.jar | grep -v grep | awk '{print $2}' | head -1)
  NOW=$(date +%s)
  ELAPSED=$((NOW - START))
  echo "elapsed=${ELAPSED}s state=${STATE:-UNKNOWN} pid=${NEW_PID:-NONE}"
  if [ -n "$NEW_PID" ] && { [ "$STATE" = "RUNNING" ] || [ "$STATE" = "STABLE" ]; }; then
    echo "[PASS] S2 YARN recovery completed in ${ELAPSED}s"
    exit 0
  fi
  sleep 1
done

echo "[FAIL] S2 30초 내 복구 실패"
yarn app -status "$SERVICE" || true
exit 1
SCRIPT
chmod +x ~/test-metric-s2-yarn-recovery.sh
```

## 3. Performance

Performance는 물리적 블록 이동 자체의 한계와 오토티어링 파이프라인 오버헤드를 분리해서 설명한다. HDFS의 실제 이관은 두 경우 모두 SPS가 수행하므로, 비교 대상은 다음 두 방식이다.

- 수동 기준선: 관리자가 `hdfs dfs -ls -R`로 후보를 찾고 `setStoragePolicy + satisfyStoragePolicy`를 직접 호출
- 오토티어링: FSImage 기반 scoring, DB queue, scheduler, tracker를 거쳐 동일한 SPS 이동 수행

### P1. GB당 평균 이관 소요 시간 차이

**정의**

```text
manual_sec_per_gb = 수동 기준선 총 소요 시간 / 이관 GB
auto_sec_per_gb   = 오토티어링 총 소요 시간 / 이관 GB
delta_sec_per_gb  = auto_sec_per_gb - manual_sec_per_gb
overhead_percent  = delta_sec_per_gb / manual_sec_per_gb * 100
```

성공 판정은 두 단계로 제시한다.

1. 필수: `manual_sec_per_gb`, `auto_sec_per_gb`, `delta_sec_per_gb`, `overhead_percent`를 모두 산출한다.
2. 권장: 자동화 오버헤드가 수동 기준선 대비 10% 이내이면 성능상 수용 가능으로 본다.

```bash
cat > ~/test-metric-p1-transfer-time.sh <<'SCRIPT'
#!/usr/bin/env bash
set -euo pipefail

ROOT="${TEST_ROOT:-/test/metric}/performance/${RUN_ID:-manual}"
MANUAL_DIR="$ROOT/manual"
AUTO_DIR="$ROOT/auto"
PSQL="${PSQL_CMD:-psql -h localhost -U dsc -d dsc_tiering -qtA}"
GB=1

prepare_file() {
  local dir=$1
  local file=$dir/1gb.dat
  local local_file=/tmp/metric_1gb.dat
  hdfs dfs -rm -r -skipTrash "$dir" 2>/dev/null || true
  hdfs dfs -mkdir -p "$dir"
  hdfs storagepolicies -setStoragePolicy -path "$dir" -policy ALL_SSD >/dev/null
  dd if=/dev/zero of="$local_file" bs=1M count=1024 2>/dev/null
  hdfs dfs -put -f "$local_file" "$file"
  hdfs storagepolicies -setStoragePolicy -path "$file" -policy ALL_SSD >/dev/null
  hdfs dfs -touch -a -t "$(date -d '120 days ago' +%Y%m%d:%H%M%S)" "$file"
  rm -f "$local_file"
}

wait_archive() {
  local file=$1
  for _ in $(seq 1 240); do
    fsck=$(hdfs fsck "$file" -files -blocks -locations 2>/dev/null || true)
    archive_count=$(echo "$fsck" | grep -o "ARCHIVE" | wc -l)
    non_archive_count=$(echo "$fsck" | grep -E "\[SSD\]|\[DISK\]" | wc -l)
    if [ "$archive_count" -gt 0 ] && [ "$non_archive_count" -eq 0 ]; then
      return 0
    fi
    sleep 2
  done
  return 1
}

prepare_file "$MANUAL_DIR"
MANUAL_FILE="$MANUAL_DIR/1gb.dat"
START=$(date +%s)
hdfs dfs -ls -R "$MANUAL_DIR" >/dev/null
hdfs storagepolicies -setStoragePolicy -path "$MANUAL_FILE" -policy COLD >/dev/null
hdfs storagepolicies -satisfyStoragePolicy -path "$MANUAL_FILE" >/dev/null 2>&1 || true
wait_archive "$MANUAL_FILE"
END=$(date +%s)
MANUAL=$((END - START))

prepare_file "$AUTO_DIR"
AUTO_FILE="$AUTO_DIR/1gb.dat"
$PSQL -c "DELETE FROM pending_jobs WHERE file_path LIKE '${AUTO_DIR}%';" >/dev/null
hdfs dfsadmin -safemode enter >/dev/null
hdfs dfsadmin -saveNamespace >/dev/null
hdfs dfsadmin -safemode leave >/dev/null

echo "[INFO] 오토티어링 데몬 실행 대기"
START=$(date +%s)
for _ in $(seq 1 240); do
  STATUS=$($PSQL -c "SELECT status FROM pending_jobs WHERE file_path='${AUTO_FILE}' ORDER BY job_id DESC LIMIT 1;")
  fsck=$(hdfs fsck "$AUTO_FILE" -files -blocks -locations 2>/dev/null || true)
  archive_count=$(echo "$fsck" | grep -o "ARCHIVE" | wc -l)
  non_archive_count=$(echo "$fsck" | grep -E "\[SSD\]|\[DISK\]" | wc -l)
  if [ "$STATUS" = "COMPLETED" ] && [ "$archive_count" -gt 0 ] && [ "$non_archive_count" -eq 0 ]; then
    break
  fi
  sleep 2
done
END=$(date +%s)
AUTO=$((END - START))

MANUAL_PER_GB=$(awk "BEGIN {printf \"%.2f\", $MANUAL / $GB}")
AUTO_PER_GB=$(awk "BEGIN {printf \"%.2f\", $AUTO / $GB}")
DELTA=$(awk "BEGIN {printf \"%.2f\", $AUTO_PER_GB - $MANUAL_PER_GB}")
OVERHEAD=$(awk "BEGIN {if ($MANUAL_PER_GB == 0) print 0; else printf \"%.2f\", (($AUTO_PER_GB - $MANUAL_PER_GB) / $MANUAL_PER_GB) * 100}")

echo "manual_sec_per_gb=$MANUAL_PER_GB"
echo "auto_sec_per_gb=$AUTO_PER_GB"
echo "delta_sec_per_gb=$DELTA"
echo "overhead_percent=$OVERHEAD"

if awk "BEGIN {exit !($OVERHEAD <= 10.0)}"; then
  echo "[PASS] P1 자동화 오버헤드 10% 이내"
else
  echo "[WARN] P1 자동화 오버헤드 10% 초과. 물리 이동 시간, queue 대기, tracker timeout 로그를 함께 해석해야 함."
fi
SCRIPT
chmod +x ~/test-metric-p1-transfer-time.sh
```

## 4. Effectiveness

Effectiveness는 “기능이 맞다”를 넘어 “왜 이 시스템을 운영할 가치가 있는가”를 보여준다. 단, 이 영역은 클러스터 규모와 스토리지 단가에 따라 값이 달라지므로 절대 기준보다 산출식과 해석을 명확히 제시한다.

### E1. 월간 저장 비용 절감액

**정의**

HDFS 물리 매체를 클라우드 스토리지 티어와 대응시키거나, 자체 장비의 GB당 월 환산 비용을 넣어 절감액을 계산한다. 발표에서는 단가를 하드코딩된 사실처럼 주장하지 말고 “평가에 사용한 단가표”로 명시한다.

```text
HOT_GB_TO_WARM = SUM(file_size_bytes where current_tier='HOT' and target_tier='WARM') / GiB
HOT_GB_TO_COLD = SUM(file_size_bytes where current_tier='HOT' and target_tier='COLD') / GiB

monthly_saving =
    HOT_GB_TO_WARM * (HOT_COST - WARM_COST)
  + HOT_GB_TO_COLD * (HOT_COST - COLD_COST)

saving_rate =
    monthly_saving / (moved_total_gb * HOT_COST) * 100
```

이 지표의 장점은 프로젝트 효과를 교수자가 바로 이해할 수 있는 단위, 즉 “GB당/월 비용 절감”으로 환산한다는 점이다.

```bash
cat > ~/test-metric-e1-cost-saving.sh <<'SCRIPT'
#!/usr/bin/env bash
set -euo pipefail

PSQL="${PSQL_CMD:-psql -h localhost -U dsc -d dsc_tiering -qtA}"
SCOPE="${COST_SCOPE_SQL:-AND file_path LIKE '/test/metric/%'}"

# 평가용 단가 변수. 발표 자료에는 사용한 단가표의 출처와 산정일을 별도 명시한다.
HOT_COST="${HOT_COST_PER_GB_MONTH:-0.080}"
WARM_COST="${WARM_COST_PER_GB_MONTH:-0.045}"
COLD_COST="${COLD_COST_PER_GB_MONTH:-0.015}"

HOT_TO_WARM_BYTES=$($PSQL -c "SELECT COALESCE(SUM(file_size_bytes),0) FROM pending_jobs WHERE status='COMPLETED' AND current_tier='HOT' AND target_tier='WARM' ${SCOPE};")
HOT_TO_COLD_BYTES=$($PSQL -c "SELECT COALESCE(SUM(file_size_bytes),0) FROM pending_jobs WHERE status='COMPLETED' AND current_tier='HOT' AND target_tier='COLD' ${SCOPE};")

awk -v warm_b="$HOT_TO_WARM_BYTES" -v cold_b="$HOT_TO_COLD_BYTES" \
    -v hot="$HOT_COST" -v warm="$WARM_COST" -v cold="$COLD_COST" '
BEGIN {
  gib = 1024 * 1024 * 1024
  warm_gb = warm_b / gib
  cold_gb = cold_b / gib
  moved_gb = warm_gb + cold_gb
  saving = warm_gb * (hot - warm) + cold_gb * (hot - cold)
  baseline = moved_gb * hot
  rate = baseline > 0 ? (saving / baseline) * 100 : 0
  printf "hot_to_warm_gb=%.3f\n", warm_gb
  printf "hot_to_cold_gb=%.3f\n", cold_gb
  printf "moved_total_gb=%.3f\n", moved_gb
  printf "monthly_saving=%.4f\n", saving
  printf "saving_rate_percent=%.2f\n", rate
  if (moved_gb > 0 && saving > 0) {
    print "[PASS] E1 비용 절감액 산출 완료"
  } else {
    print "[WARN] E1 완료된 이관량이 없어 절감액이 0입니다. A3/P1 실행 후 다시 측정하십시오."
  }
}'
SCRIPT
chmod +x ~/test-metric-e1-cost-saving.sh
```

보고서에는 다음처럼 해석한다.

| 항목 | 해석 |
|---|---|
| `moved_total_gb` | 오토티어링이 실제로 저비용 티어로 내린 데이터 규모 |
| `monthly_saving` | 같은 데이터를 HOT에 계속 둘 때 대비 월간 절감액 |
| `saving_rate_percent` | 이관 대상 데이터 기준 비용 절감률 |

### E2. NameNode 메타데이터 스캔 부하 회피

**정의**

이 프로젝트의 핵심 주장은 “HDFS 파일 목록을 매번 RPC로 전수 탐색하지 않고 FSImage를 이용해 오프라인 분석한다”는 점이다. 따라서 기존 방식(`hdfs dfs -ls -R`)과 오토티어링 방식 실행 중의 NameNode 지표를 비교한다.

```text
heap_delta = peak_heap_used - baseline_heap_used
rpc_queue_delta = during_rpc_queue_avg - baseline_rpc_queue_avg

offload_ratio = 1 - (auto_delta / recursive_scan_delta)
```

`offload_ratio`가 1에 가까울수록 기존 recursive scan 대비 NameNode 부하를 더 많이 회피한 것이다.

```bash
cat > ~/test-metric-e2-namenode-offload.sh <<'SCRIPT'
#!/usr/bin/env bash
set -euo pipefail

TEST_DIR="${TEST_ROOT:-/test/metric}/offload/${RUN_ID:-manual}"
N="${OFFLOAD_FILE_COUNT:-3000}"

heap_used() {
  curl -s "http://localhost:9870/jmx?qry=java.lang:type=Memory" \
    | grep -A 8 '"HeapMemoryUsage"' \
    | grep '"used"' \
    | head -1 \
    | grep -o '[0-9]*' \
    | head -1
}

rpc_queue() {
  curl -s "http://localhost:9870/jmx?qry=Hadoop:service=NameNode,name=RpcActivityForPort8020" \
    | grep -o '"RpcQueueTimeAvg" *: *[0-9.]*' \
    | head -1 \
    | awk -F ':' '{gsub(/[ ,]/, "", $2); print $2}'
}

hdfs dfs -rm -r -skipTrash "$TEST_DIR" 2>/dev/null || true
hdfs dfs -mkdir -p "$TEST_DIR"
mkdir -p /tmp/metric_offload
for i in $(seq 1 "$N"); do
  : > "/tmp/metric_offload/file_${i}.txt"
done
hdfs dfs -put -f /tmp/metric_offload/* "$TEST_DIR/"
rm -rf /tmp/metric_offload

BASE_HEAP=$(heap_used)
BASE_RPC=$(rpc_queue)

START=$(date +%s)
hdfs dfs -ls -R "$TEST_DIR" >/dev/null
END=$(date +%s)
LS_HEAP=$(heap_used)
LS_RPC=$(rpc_queue)
LS_TIME=$((END - START))

hdfs dfsadmin -safemode enter >/dev/null
hdfs dfsadmin -saveNamespace >/dev/null
hdfs dfsadmin -safemode leave >/dev/null

echo "[INFO] 지금 오토티어링 ScoringEngine 1회 사이클이 실행되도록 하십시오."
sleep 20
AUTO_HEAP=$(heap_used)
AUTO_RPC=$(rpc_queue)

awk -v base_heap="$BASE_HEAP" -v base_rpc="$BASE_RPC" \
    -v ls_heap="$LS_HEAP" -v ls_rpc="$LS_RPC" -v ls_time="$LS_TIME" \
    -v auto_heap="$AUTO_HEAP" -v auto_rpc="$AUTO_RPC" '
BEGIN {
  ls_heap_delta = ls_heap - base_heap
  auto_heap_delta = auto_heap - base_heap
  ls_rpc_delta = ls_rpc - base_rpc
  auto_rpc_delta = auto_rpc - base_rpc
  heap_offload = ls_heap_delta > 0 ? (1 - (auto_heap_delta / ls_heap_delta)) * 100 : 0
  rpc_offload = ls_rpc_delta > 0 ? (1 - (auto_rpc_delta / ls_rpc_delta)) * 100 : 0
  printf "recursive_ls_time_sec=%d\n", ls_time
  printf "recursive_heap_delta_bytes=%.0f\n", ls_heap_delta
  printf "auto_heap_delta_bytes=%.0f\n", auto_heap_delta
  printf "heap_offload_percent=%.2f\n", heap_offload
  printf "recursive_rpc_queue_delta_ms=%.6f\n", ls_rpc_delta
  printf "auto_rpc_queue_delta_ms=%.6f\n", auto_rpc_delta
  printf "rpc_offload_percent=%.2f\n", rpc_offload
  print "[INFO] E2는 효과 크기 지표입니다. recursive scan 대비 auto delta가 작을수록 설득력이 큽니다."
}'
SCRIPT
chmod +x ~/test-metric-e2-namenode-offload.sh
```

이 지표는 단순 성능 수치보다 프로젝트의 구조적 가치를 잘 보여준다. 파일 수가 늘수록 recursive scan은 NameNode RPC를 계속 증가시키지만, FSImage 기반 접근은 분석 부하를 서비스 컨테이너로 이동시킨다는 점을 숫자로 제시할 수 있다.

### E3. 워크로드별 적합성과 한계 도출

한계는 “실패 요인”으로만 제시하지 말고, 어떤 워크로드에서 프로젝트가 가장 효과적인지 판단하는 기준으로 정리한다.

| 워크로드 | 기대 효과 | 관찰 지표 | 해석 |
|---|---|---|---|
| 대용량 로그/백업 아카이브 | 매우 높음 | E1 절감액, A3 전환율, P1 sec/GB | 오래 읽지 않는 대용량 파일은 비용 절감 효과가 직접적이다. |
| ML/AI 데이터 레이크 | 높음 | WARM/COLD 분리 비율, Spearman rho | 학습 종료 후 식는 데이터에 적합하다. |
| 수백만 개 초소형 파일 | 제한적 | E2 NameNode 부하, E1 절감액 | 스토리지 비용보다 NameNode 메타데이터 부담이 본질적이라, 티어링만으로는 비용 효과가 작을 수 있다. |
| 단기 실시간 분석 데이터 | 보완 필요 | A1 false negative/positive, 임계값 민감도 | 30/90일 고정 임계값보다 디렉터리별 TTL 설정이 있으면 개선된다. |
| 계절성 데이터 | 중간 이상 | COLD 전환 후 재접근 패턴 | 현재는 주로 downgrade에 강하다. 향후 promotion 로직을 넣으면 보완 가능하다. |

교수자에게는 다음 결론으로 제시한다.

- 본 시스템은 “오래 방치된 대용량 데이터”에서 비용 절감과 자동화 효과가 가장 크다.
- “초소형 파일”이나 “매우 짧은 생명주기 데이터”는 티어링보다 별도 정책, 예를 들어 소형 파일 병합이나 디렉터리별 TTL이 더 중요할 수 있다.
- 따라서 이 프로젝트의 가치는 모든 워크로드를 한 번에 해결하는 데 있지 않고, HDFS 안에서 비용이 큰 콜드 데이터를 자동 식별하고 안전하게 저비용 티어로 이동시키는 운영 루프를 제공하는 데 있다.

## 5. 최종 제출용 결과표

발표 또는 보고서에는 아래 표를 실제 측정값으로 채운다.

| 지표 | 측정값 | 성공 기준 | 판정 | 증거 |
|---|---:|---:|---|---|
| A1 Precision |  | 1.00 |  | `test-metric-a1-list-match.sh` 출력 |
| A1 Recall |  | 1.00 |  | `pending_jobs` 조회 |
| A2 Spearman rho |  | >= 0.95 |  | `test-metric-a2-spearman.py` 출력 |
| A3 목표 정책 전환율 |  | 100% |  | DB `COMPLETED`, HDFS policy |
| S1 RPC Queue Time 상승폭 |  | <= 5% |  | JMX 샘플 로그 |
| S2 YARN 복구 시간 |  | <= 30s |  | `yarn app -status`, PID 로그 |
| P1 manual sec/GB |  | 산출 |  | 성능 스크립트 출력 |
| P1 auto sec/GB |  | 산출 |  | 성능 스크립트 출력 |
| P1 overhead |  | 권장 <= 10% |  | 성능 스크립트 출력 |
| E1 monthly saving |  | 산출 |  | 비용 절감 스크립트 출력 |
| E1 saving rate |  | 산출 |  | 비용 절감 스크립트 출력 |
| E2 heap offload |  | 산출 |  | JMX 비교 출력 |
| E2 RPC offload |  | 산출 |  | JMX 비교 출력 |
| E3 적합 워크로드 |  | 정성+정량 해석 |  | 워크로드별 결과표 |

## 6. Cleanup

테스트 후 서버 상태를 원복한다.

```bash
yarn application -kill $(yarn app -list | grep hdfs-auto-tiering | awk '{print $1}') 2>/dev/null || true
rm -f ~/test-metric-a*.sh ~/test-metric-s*.sh ~/test-metric-p*.sh ~/test-metric-e*.sh ~/prepare-metric-a2-ranking-data.sh ~/test-metric-a2-spearman.py
hdfs dfs -rm -r -skipTrash /test/metric 2>/dev/null || true
psql -h localhost -U dsc -d dsc_tiering -c "DELETE FROM pending_jobs WHERE file_path LIKE '/test/metric/%';"
```
