# HDFS Auto-Tiering 테스트 수행 가이드

이 문서는 프로젝트의 실제 코드와 문서 계약을 기준으로, HDFS Auto-Tiering을 재현 가능하게 검증하는 방법을 정의한다.

테스트 목표는 다음 네 가지다.

- 테스트 수행 방법을 한 곳에서 설명한다.
- 각 출력값이 무엇을 뜻하는지 명확히 한다.
- 테스트 스크립트가 필요한 HDFS 테스트 파일과 DB 상태를 직접 만든다.
- 테스트가 끝나면 HDFS 데이터, DB 행, 로컬 임시 파일을 직접 정리한다.

## 1. 코드 기준 테스트 전제

현재 코드 기준으로 테스트가 기대하는 핵심 동작은 다음과 같다.

| 항목 | 코드 기준 |
|---|---|
| 대상 경로 제한 | `scoring.target-directories` 하위 파일만 스코어링한다. 검증 경로는 `/test/metric`이다. |
| HOT 판정 | HDFS storage policy `ALL_SSD`는 서비스 티어 `HOT`이다. |
| WARM 판정 | 30일 이상 90일 미만 접근하지 않은 HOT 파일은 `WARM` 대상이다. |
| COLD 판정 | 90일 이상 접근하지 않은 HOT/WARM 파일은 `COLD` 대상이다. |
| 우선순위 | `priority_score`는 낮을수록 먼저 처리된다. 접근 시각 순위와 파일 크기 순위의 가중 합이다. |
| 상태 전이 | `PENDING -> DISPATCHED -> IN_PROGRESS -> COMPLETED`가 정상 경로다. |
| 물리 이동 검증 | COLD/HOT은 목표 storage type 비율이 `completion-ratio` 이상이어야 한다. 기본값은 0.95다. WARM은 SSD 블록이 1개 이상이면 만족한다. |

따라서 테스트 문서의 스크립트는 다음 논리 오류를 피하도록 작성했다.

- `priority_score`를 내림차순으로 해석하지 않는다. A2는 `ORDER BY priority_score ASC`를 사용한다.
- zero-byte 파일로 물리 이동 완료율을 검증하지 않는다. A3/P1/E1은 실제 블록이 생기도록 기본 128 MiB 이상 파일을 만든다.
- NameNode RPC JMX 포트를 `8020`으로 하드코딩하지 않는다. `RpcActivityForPort*` bean을 찾아 측정한다.
- 모든 테스트 파일은 `/test/metric/<test>/<RUN_ID>` 아래에 만들고, 종료 시 해당 범위의 HDFS/DB/로컬 임시 데이터를 삭제한다.
- YARN 프로세스를 강제로 죽이는 S2는 실수 방지를 위해 기본 `SKIP`이며, 명시적으로 `RUN_DESTRUCTIVE=1`을 줬을 때만 실행한다.

## 2. 사전 조건

서버(Ubuntu/WSL2)에서 아래 조건이 충족되어 있어야 한다.

```bash
export HADOOP_CONF_DIR="$HOME/hadoop-conf/namenode"
export HADOOP_HOME="$HOME/hadoop"
export PSQL_CMD="psql -h localhost -U dsc -d dsc_tiering -qtA"
export TEST_ROOT="/test/metric"
```

필수 서비스 확인:

```bash
jps
hdfs dfsadmin -report
hdfs dfs -ls /
psql -h localhost -U dsc -d dsc_tiering -c "\d pending_jobs"
curl -s http://localhost:9870/jmx >/dev/null
```

검증용 설정에는 반드시 다음 값이 있어야 한다.

```yaml
scoring:
  target-directories:
    - /test/metric
```

JAR 파일은 다음 순서로 찾는다.

1. `AUTO_TIERING_JAR` 환경 변수
2. `~/DSC/services/hdfs-auto-tiering/target/hdfs-auto-tiering.jar`
3. `./services/hdfs-auto-tiering/target/hdfs-auto-tiering.jar`
4. HDFS의 `/apps/hdfs-auto-tiering/lib/hdfs-auto-tiering.jar`

## 3. 테스트 스위트 생성

서버에서 아래 블록을 한 번 실행하면 `~/dsc-metric-tests` 아래에 모든 테스트 스크립트가 생성된다.

```bash
cat > ~/install-dsc-metric-tests.sh <<'INSTALL'
#!/usr/bin/env bash
set -euo pipefail

SUITE_DIR="${SUITE_DIR:-$HOME/dsc-metric-tests}"
mkdir -p "$SUITE_DIR/results"

cat > "$SUITE_DIR/lib.sh" <<'LIB'
#!/usr/bin/env bash
set -euo pipefail

SUITE_DIR="${SUITE_DIR:-$HOME/dsc-metric-tests}"
TEST_ROOT="${TEST_ROOT:-/test/metric}"
RUN_ID="${RUN_ID:-$(date +%Y%m%d%H%M%S)}"
PSQL_CMD="${PSQL_CMD:-psql -h localhost -U dsc -d dsc_tiering -qtA}"
HADOOP_CONF_DIR="${HADOOP_CONF_DIR:-$HOME/hadoop-conf/namenode}"
HADOOP_HOME="${HADOOP_HOME:-$HOME/hadoop}"
LOCAL_TMP_BASE="${LOCAL_TMP_BASE:-/tmp/dsc-metric-${RUN_ID}}"
export HADOOP_CONF_DIR HADOOP_HOME

mkdir -p "$LOCAL_TMP_BASE"

hdfs_cmd() {
  HADOOP_CONF_DIR="$HADOOP_CONF_DIR" hdfs "$@"
}

yarn_cmd() {
  HADOOP_CONF_DIR="$HADOOP_CONF_DIR" yarn "$@"
}

sqlq() {
  $PSQL_CMD -c "$1"
}

sql_pretty() {
  psql -h localhost -U dsc -d dsc_tiering -c "$1"
}

require_cmds() {
  local missing=0
  for cmd in hdfs java psql awk date curl python3; do
    if ! command -v "$cmd" >/dev/null 2>&1; then
      echo "[FAIL] required command not found: $cmd"
      missing=1
    fi
  done
  if [ "$missing" -ne 0 ]; then
    exit 1
  fi
}

require_cluster() {
  require_cmds
  hdfs_cmd dfs -ls / >/dev/null
  sqlq "SELECT 1;" >/dev/null
  curl -fsS http://localhost:9870/jmx >/dev/null
}

cleanup_path() {
  local hdfs_path=$1
  sqlq "DELETE FROM pending_jobs WHERE file_path LIKE '${hdfs_path}%';" >/dev/null 2>&1 || true
  hdfs_cmd dfs -rm -r -skipTrash "$hdfs_path" >/dev/null 2>&1 || true
}

cleanup_local() {
  rm -rf "$LOCAL_TMP_BASE" >/dev/null 2>&1 || true
}

save_namespace() {
  hdfs_cmd dfsadmin -safemode enter >/dev/null 2>&1 || true
  hdfs_cmd dfsadmin -saveNamespace >/dev/null
  hdfs_cmd dfsadmin -safemode leave >/dev/null 2>&1 || true
}

find_jar() {
  if [ -n "${AUTO_TIERING_JAR:-}" ] && [ -f "$AUTO_TIERING_JAR" ]; then
    echo "$AUTO_TIERING_JAR"
    return 0
  fi

  local candidates=(
    "$HOME/DSC/services/hdfs-auto-tiering/target/hdfs-auto-tiering.jar"
    "$PWD/services/hdfs-auto-tiering/target/hdfs-auto-tiering.jar"
    "$PWD/target/hdfs-auto-tiering.jar"
  )

  for jar in "${candidates[@]}"; do
    if [ -f "$jar" ]; then
      echo "$jar"
      return 0
    fi
  done

  local copied="$LOCAL_TMP_BASE/hdfs-auto-tiering.jar"
  if hdfs_cmd dfs -test -f /apps/hdfs-auto-tiering/lib/hdfs-auto-tiering.jar; then
    hdfs_cmd dfs -copyToLocal -f /apps/hdfs-auto-tiering/lib/hdfs-auto-tiering.jar "$copied"
    echo "$copied"
    return 0
  fi

  echo "[FAIL] hdfs-auto-tiering.jar not found. Set AUTO_TIERING_JAR or deploy it to /apps/hdfs-auto-tiering/lib/." >&2
  return 1
}

write_test_config() {
  local config_file=$1
  cat > "$config_file" <<CFG
database:
  url: jdbc:postgresql://localhost:5432/dsc_tiering
  username: dsc
  password: dsc
  pool:
    maximumPoolSize: 8
    minimumIdle: 2
hdfs:
  fsDefaultName: hdfs://localhost:9000
  user: ""
  policyMapping:
    HOT: ALL_SSD
    WARM: ONE_SSD
    COLD: COLD
scoring:
  enabled: true
  intervalSeconds: 86400
  weightAccessTime: 0.5
  weightFileSize: 0.5
  localFsimageDir: $LOCAL_TMP_BASE/fsimage
  targetDirectories:
    - $TEST_ROOT
scheduler:
  pollIntervalSeconds: 1
  windows:
    - name: allday
      start: "00:00"
      end: "00:00"
      batchSize: 100
      interBatchWaitMs: 500
  concurrency: 4
  maxRetries: 3
tracker:
  pollIntervalSeconds: 2
  timeoutMinutes: 60
  batchSize: 50
  completionRatio: 0.95
  maxWorkers: 5
  nodenameSemaphore: 3
  maxRetryCount: 5
CFG
}

start_daemon() {
  local log_file=$1
  local config_file=$2
  local jar
  jar=$(find_jar)
  java -jar "$jar" "$config_file" > "$log_file" 2>&1 &
  echo "$!"
}

wait_for_job_count() {
  local scope=$1
  local expected=$2
  local seconds=${3:-120}
  local count
  for _ in $(seq 1 "$seconds"); do
    count=$(sqlq "SELECT count(*) FROM pending_jobs WHERE file_path LIKE '${scope}%' AND status IN ('PENDING','DISPATCHED','IN_PROGRESS','COMPLETED','FAILED');")
    if [ "${count:-0}" -ge "$expected" ]; then
      return 0
    fi
    sleep 1
  done
  return 1
}

storage_summary() {
  local file=$1
  local fsck archive ssd disk total ratio
  fsck=$(hdfs_cmd fsck "$file" -files -blocks -locations 2>/dev/null || true)
  archive=$(printf "%s" "$fsck" | grep -o "ARCHIVE" | wc -l | tr -d ' ')
  ssd=$(printf "%s" "$fsck" | grep -o "SSD" | wc -l | tr -d ' ')
  disk=$(printf "%s" "$fsck" | grep -o "DISK" | wc -l | tr -d ' ')
  total=$((archive + ssd + disk))
  ratio=$(awk "BEGIN {if ($total == 0) printf \"0.0000\"; else printf \"%.4f\", $archive / $total}")
  echo "archive=$archive ssd=$ssd disk=$disk total=$total archive_ratio=$ratio"
}

jmx_rpc_queue_avg() {
  python3 - <<'PY'
import json
import urllib.request

try:
    with urllib.request.urlopen("http://localhost:9870/jmx", timeout=3) as response:
        data = json.load(response)
    values = []
    for bean in data.get("beans", []):
        name = bean.get("name", "")
        if name.startswith("Hadoop:service=NameNode,name=RpcActivity"):
            for key in ("RpcQueueTimeAvgTime", "RpcQueueTimeAvg"):
                if key in bean:
                    values.append(float(bean.get(key) or 0.0))
                    break
    print(sum(values) / len(values) if values else 0.0)
except Exception:
    print(0.0)
PY
}

jmx_heap_used() {
  python3 - <<'PY'
import json
import urllib.request

try:
    with urllib.request.urlopen("http://localhost:9870/jmx?qry=java.lang:type=Memory", timeout=3) as response:
        data = json.load(response)
    beans = data.get("beans", [])
    used = beans[0].get("HeapMemoryUsage", {}).get("used", 0) if beans else 0
    print(int(used or 0))
except Exception:
    print(0)
PY
}

jmx_rpc_num_ops() {
  python3 - <<'PY'
import json
import urllib.request

try:
    with urllib.request.urlopen("http://localhost:9870/jmx", timeout=5) as response:
        data = json.load(response)
    total = 0
    for bean in data.get("beans", []):
        name = bean.get("name", "")
        # NameNodeActivity 빈에서 디렉토리 탐색(ls -R) 관련 메트릭만 추출 (백그라운드 통신 및 스케줄링 노이즈 배제)
        if "name=NameNodeActivity" in name:
            for key in ("GetListingOps",):
                if key in bean:
                    total += int(bean[key])
    print(total)
except Exception:
    print(0)
PY
}

sample_rpc_queue_avg() {
  local count=${1:-10}
  local interval=${2:-1}
  local sum=0
  local value
  for _ in $(seq 1 "$count"); do
    value=$(jmx_rpc_queue_avg)
    sum=$(awk "BEGIN {print $sum + $value}")
    sleep "$interval"
  done
  awk "BEGIN {printf \"%.6f\", $sum / $count}"
}
LIB

cat > "$SUITE_DIR/a1_accuracy.sh" <<'SCRIPT'
#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib.sh"

TEST_DIR="$TEST_ROOT/accuracy/$RUN_ID"
DAEMON_PID=""
cleanup() {
  [ -n "$DAEMON_PID" ] && kill "$DAEMON_PID" >/dev/null 2>&1 || true
  cleanup_path "$TEST_DIR"
  cleanup_local
}
trap cleanup EXIT INT TERM

require_cluster
cleanup_path "$TEST_DIR"
mkdir -p "$LOCAL_TMP_BASE/a1"
hdfs_cmd dfs -mkdir -p "$TEST_DIR"
hdfs_cmd storagepolicies -setStoragePolicy -path "$TEST_DIR" -policy ALL_SSD >/dev/null

for i in $(seq -w 1 20); do
  printf "cold-%s\n" "$i" > "$LOCAL_TMP_BASE/a1/cold_$i.dat"
  printf "warm-%s\n" "$i" > "$LOCAL_TMP_BASE/a1/warm_$i.dat"
  printf "hot-%s\n" "$i" > "$LOCAL_TMP_BASE/a1/hot_$i.dat"
  hdfs_cmd dfs -put -f "$LOCAL_TMP_BASE/a1/cold_$i.dat" "$TEST_DIR/cold_$i.dat"
  hdfs_cmd dfs -put -f "$LOCAL_TMP_BASE/a1/warm_$i.dat" "$TEST_DIR/warm_$i.dat"
  hdfs_cmd dfs -put -f "$LOCAL_TMP_BASE/a1/hot_$i.dat" "$TEST_DIR/hot_$i.dat"
  hdfs_cmd storagepolicies -setStoragePolicy -path "$TEST_DIR/cold_$i.dat" -policy ALL_SSD >/dev/null
  hdfs_cmd storagepolicies -setStoragePolicy -path "$TEST_DIR/warm_$i.dat" -policy ALL_SSD >/dev/null
  hdfs_cmd storagepolicies -setStoragePolicy -path "$TEST_DIR/hot_$i.dat" -policy ALL_SSD >/dev/null
  hdfs_cmd dfs -touch -a -t "$(date -d '100 days ago' +%Y%m%d:%H%M%S)" "$TEST_DIR/cold_$i.dat"
  hdfs_cmd dfs -touch -a -t "$(date -d '60 days ago' +%Y%m%d:%H%M%S)" "$TEST_DIR/warm_$i.dat"
done

save_namespace
CONFIG_FILE="$LOCAL_TMP_BASE/a1-config.yaml"
LOG_FILE="$LOCAL_TMP_BASE/a1-daemon.log"
write_test_config "$CONFIG_FILE"
DAEMON_PID=$(start_daemon "$LOG_FILE" "$CONFIG_FILE")

if ! wait_for_job_count "$TEST_DIR" 40 120; then
  echo "[FAIL] A1 scoring jobs were not created within timeout"
  tail -100 "$LOG_FILE" || true
  sql_pretty "SELECT file_path, current_tier, target_tier, priority_score, status FROM pending_jobs WHERE file_path LIKE '${TEST_DIR}%' ORDER BY file_path;"
  exit 1
fi

EXPECTED=40
DETECTED=$(sqlq "SELECT count(*) FROM pending_jobs WHERE file_path LIKE '${TEST_DIR}%' AND status IN ('PENDING','DISPATCHED','IN_PROGRESS','COMPLETED','FAILED');")
FALSE_POSITIVE=$(sqlq "SELECT count(*) FROM pending_jobs WHERE file_path LIKE '${TEST_DIR}/hot_%';")
COLD_DETECTED=$(sqlq "SELECT count(*) FROM pending_jobs WHERE file_path LIKE '${TEST_DIR}/cold_%' AND target_tier='COLD';")
WARM_DETECTED=$(sqlq "SELECT count(*) FROM pending_jobs WHERE file_path LIKE '${TEST_DIR}/warm_%' AND target_tier='WARM';")

echo "expected=$EXPECTED detected=$DETECTED false_positive=$FALSE_POSITIVE cold=$COLD_DETECTED warm=$WARM_DETECTED"

if [ "$DETECTED" -eq "$EXPECTED" ] && [ "$FALSE_POSITIVE" -eq 0 ] && [ "$COLD_DETECTED" -eq 20 ] && [ "$WARM_DETECTED" -eq 20 ]; then
  echo "[PASS] A1 Precision=1.00 Recall=1.00"
else
  echo "[FAIL] A1 file list mismatch"
  sql_pretty "SELECT file_path, current_tier, target_tier, priority_score, status FROM pending_jobs WHERE file_path LIKE '${TEST_DIR}%' ORDER BY file_path;"
  exit 1
fi
SCRIPT

cat > "$SUITE_DIR/a2_spearman.sh" <<'SCRIPT'
#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib.sh"

TEST_DIR="$TEST_ROOT/ranking/$RUN_ID"
DAEMON_PID=""
cleanup() {
  [ -n "$DAEMON_PID" ] && kill "$DAEMON_PID" >/dev/null 2>&1 || true
  cleanup_path "$TEST_DIR"
  cleanup_local
}
trap cleanup EXIT INT TERM

require_cluster
cleanup_path "$TEST_DIR"
mkdir -p "$LOCAL_TMP_BASE/a2"
hdfs_cmd dfs -mkdir -p "$TEST_DIR"
hdfs_cmd storagepolicies -setStoragePolicy -path "$TEST_DIR" -policy ALL_SSD >/dev/null

for rank in $(seq -w 1 20); do
  days=$((140 - 10#$rank))
  mb=$((21 - 10#$rank))
  [ "$mb" -lt 1 ] && mb=1
  local_file="$LOCAL_TMP_BASE/a2/rank_$rank.dat"
  dd if=/dev/zero of="$local_file" bs=1M count="$mb" 2>/dev/null
  hdfs_cmd dfs -put -f "$local_file" "$TEST_DIR/rank_$rank.dat"
  hdfs_cmd storagepolicies -setStoragePolicy -path "$TEST_DIR/rank_$rank.dat" -policy ALL_SSD >/dev/null
  hdfs_cmd dfs -touch -a -t "$(date -d "${days} days ago" +%Y%m%d:%H%M%S)" "$TEST_DIR/rank_$rank.dat"
done

save_namespace
CONFIG_FILE="$LOCAL_TMP_BASE/a2-config.yaml"
LOG_FILE="$LOCAL_TMP_BASE/a2-daemon.log"
write_test_config "$CONFIG_FILE"
DAEMON_PID=$(start_daemon "$LOG_FILE" "$CONFIG_FILE")

if ! wait_for_job_count "$TEST_DIR" 20 120; then
  echo "[FAIL] A2 scoring jobs were not created within timeout"
  tail -100 "$LOG_FILE" || true
  sql_pretty "SELECT file_path, priority_score, status FROM pending_jobs WHERE file_path LIKE '${TEST_DIR}%' ORDER BY priority_score ASC, file_path ASC;"
  exit 1
fi

SPEARMAN_TEST_DIR="$TEST_DIR" PSQL_CMD="$PSQL_CMD" python3 - <<'PY'
import os
import re
import shlex
import subprocess
import sys

test_dir = os.environ["SPEARMAN_TEST_DIR"]
psql_cmd = shlex.split(os.environ["PSQL_CMD"])
query = (
    "SELECT file_path, priority_score "
    "FROM pending_jobs "
    f"WHERE file_path LIKE '{test_dir}/rank_%' "
    "ORDER BY priority_score ASC, file_path ASC;"
)
rows = subprocess.check_output(psql_cmd + ["-c", query], text=True).strip().splitlines()
actual = []
for row in rows:
    if not row.strip():
        continue
    path, _score = row.split("|", 1)
    match = re.search(r"rank_(\d+)\.dat$", path)
    if match:
        actual.append((path, int(match.group(1))))

n = len(actual)
if n != 20:
    print(f"[FAIL] A2 expected 20 ranked jobs, got {n}")
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
SCRIPT

cat > "$SUITE_DIR/a3_policy_completion.sh" <<'SCRIPT'
#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib.sh"

TEST_DIR="$TEST_ROOT/policy/$RUN_ID"
FILE_MB="${A3_FILE_MB:-128}"
TEST_FILE="$TEST_DIR/cold_${FILE_MB}mb.dat"
DAEMON_PID=""
cleanup() {
  [ -n "$DAEMON_PID" ] && kill "$DAEMON_PID" >/dev/null 2>&1 || true
  cleanup_path "$TEST_DIR"
  cleanup_local
}
trap cleanup EXIT INT TERM

require_cluster
cleanup_path "$TEST_DIR"
mkdir -p "$LOCAL_TMP_BASE/a3"
hdfs_cmd dfs -mkdir -p "$TEST_DIR"
hdfs_cmd storagepolicies -setStoragePolicy -path "$TEST_DIR" -policy ALL_SSD >/dev/null
LOCAL_FILE="$LOCAL_TMP_BASE/a3/cold.dat"
dd if=/dev/zero of="$LOCAL_FILE" bs=1M count="$FILE_MB" 2>/dev/null
hdfs_cmd dfs -put -f "$LOCAL_FILE" "$TEST_FILE"
hdfs_cmd storagepolicies -setStoragePolicy -path "$TEST_FILE" -policy ALL_SSD >/dev/null
hdfs_cmd dfs -touch -a -t "$(date -d '120 days ago' +%Y%m%d:%H%M%S)" "$TEST_FILE"
save_namespace

CONFIG_FILE="$LOCAL_TMP_BASE/a3-config.yaml"
LOG_FILE="$LOCAL_TMP_BASE/a3-daemon.log"
write_test_config "$CONFIG_FILE"
DAEMON_PID=$(start_daemon "$LOG_FILE" "$CONFIG_FILE")

for _ in $(seq 1 240); do
  STATUS=$(sqlq "SELECT status FROM pending_jobs WHERE file_path='${TEST_FILE}' ORDER BY job_id DESC LIMIT 1;" || true)
  SUMMARY=$(storage_summary "$TEST_FILE")
  ARCHIVE_RATIO=$(printf "%s" "$SUMMARY" | awk -F 'archive_ratio=' '{print $2}')
  if [ "$STATUS" = "COMPLETED" ] && awk "BEGIN {exit !($ARCHIVE_RATIO >= 0.95)}"; then
    POLICY=$(hdfs_cmd storagepolicies -getStoragePolicy -path "$TEST_FILE" 2>/dev/null | tr -d '\n')
    echo "status=$STATUS policy=$POLICY $SUMMARY"
    echo "[PASS] A3 target policy completion ratio = 100% of test target"
    exit 0
  fi
  sleep 2
done

echo "[FAIL] A3 target policy completion timeout"
sql_pretty "SELECT job_id, file_path, status, current_tier, target_tier, retry_count, last_error FROM pending_jobs WHERE file_path='${TEST_FILE}';"
hdfs_cmd storagepolicies -getStoragePolicy -path "$TEST_FILE" || true
hdfs_cmd fsck "$TEST_FILE" -files -blocks -locations || true
tail -100 "$LOG_FILE" || true
exit 1
SCRIPT

cat > "$SUITE_DIR/s1_rpc_queue.sh" <<'SCRIPT'
#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib.sh"

TEST_DIR="$TEST_ROOT/stability-rpc/$RUN_ID"
DAEMON_PID=""
cleanup() {
  [ -n "$DAEMON_PID" ] && kill "$DAEMON_PID" >/dev/null 2>&1 || true
  cleanup_path "$TEST_DIR"
  cleanup_local
}
trap cleanup EXIT INT TERM

require_cluster
cleanup_path "$TEST_DIR"
mkdir -p "$LOCAL_TMP_BASE/s1"
hdfs_cmd dfs -mkdir -p "$TEST_DIR"
hdfs_cmd storagepolicies -setStoragePolicy -path "$TEST_DIR" -policy ALL_SSD >/dev/null

for i in $(seq -w 1 30); do
  local_file="$LOCAL_TMP_BASE/s1/file_$i.dat"
  dd if=/dev/zero of="$local_file" bs=1M count=1 2>/dev/null
  hdfs_cmd dfs -put -f "$local_file" "$TEST_DIR/file_$i.dat"
  hdfs_cmd storagepolicies -setStoragePolicy -path "$TEST_DIR/file_$i.dat" -policy ALL_SSD >/dev/null
  hdfs_cmd dfs -touch -a -t "$(date -d '120 days ago' +%Y%m%d:%H%M%S)" "$TEST_DIR/file_$i.dat"
done
save_namespace

BASE=$(sample_rpc_queue_avg 10 1)
echo "baseline_avg_ms=$BASE"

CONFIG_FILE="$LOCAL_TMP_BASE/s1-config.yaml"
LOG_FILE="$LOCAL_TMP_BASE/s1-daemon.log"
write_test_config "$CONFIG_FILE"
DAEMON_PID=$(start_daemon "$LOG_FILE" "$CONFIG_FILE")

DURING=$(sample_rpc_queue_avg 20 1)
echo "during_avg_ms=$DURING"
PERCENT=$(awk "BEGIN {base=$BASE; if (base < 0.1) base=0.1; printf \"%.4f\", (($DURING - $BASE) / base) * 100}")
echo "rpc_queue_increase_percent=$PERCENT"

if awk "BEGIN {exit !($PERCENT <= 5.0)}"; then
  echo "[PASS] S1 RPC Queue Time increase <= 5%"
else
  echo "[FAIL] S1 RPC Queue Time increase > 5%"
  tail -100 "$LOG_FILE" || true
  exit 1
fi
SCRIPT

cat > "$SUITE_DIR/s2_yarn_recovery.sh" <<'SCRIPT'
#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib.sh"

if [ "${S2_ALLOW_KILL:-0}" != "1" ]; then
  echo "[SKIP] S2 kills the running service process. Re-run with S2_ALLOW_KILL=1 or RUN_DESTRUCTIVE=1."
  exit 0
fi

require_cmds
SERVICE="${YARN_SERVICE_NAME:-hdfs-auto-tiering}"

if ! yarn_cmd app -status "$SERVICE" >/dev/null 2>&1; then
  echo "[FAIL] YARN service not found: $SERVICE"
  exit 1
fi

OLD_PID=$(pgrep -f 'hdfs-auto-tiering.*\.jar' | head -1 || true)
if [ -z "$OLD_PID" ]; then
  echo "[FAIL] hdfs-auto-tiering java process not found"
  exit 1
fi

echo "killing_pid=$OLD_PID"
kill -9 "$OLD_PID"
START=$(date +%s)

for _ in $(seq 1 30); do
  STATE=$(yarn_cmd app -status "$SERVICE" 2>/dev/null | awk -F ':' '/State/ {gsub(/^[ \t]+|[ \t]+$/, "", $2); print $2; exit}' || true)
  NEW_PID=$(pgrep -f 'hdfs-auto-tiering.*\.jar' | grep -v "^${OLD_PID}$" | head -1 || true)
  NOW=$(date +%s)
  ELAPSED=$((NOW - START))
  echo "elapsed=${ELAPSED}s state=${STATE:-UNKNOWN} pid=${NEW_PID:-NONE}"
  if [ -n "$NEW_PID" ] && { [ "$STATE" = "RUNNING" ] || [ "$STATE" = "STABLE" ]; }; then
    echo "[PASS] S2 YARN recovery completed in ${ELAPSED}s"
    exit 0
  fi
  sleep 1
done

echo "[FAIL] S2 recovery did not complete within 30s"
yarn_cmd app -status "$SERVICE" || true
exit 1
SCRIPT

cat > "$SUITE_DIR/p1_transfer_time.sh" <<'SCRIPT'
#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib.sh"

ROOT="$TEST_ROOT/performance/$RUN_ID"
MANUAL_DIR="$ROOT/manual"
AUTO_DIR="$ROOT/auto"
FILE_MB="${TRANSFER_FILE_MB:-128}"
GB=$(awk "BEGIN {printf \"%.6f\", $FILE_MB / 1024}")
DAEMON_PID=""
cleanup() {
  [ -n "$DAEMON_PID" ] && kill "$DAEMON_PID" >/dev/null 2>&1 || true
  cleanup_path "$ROOT"
  cleanup_local
}
trap cleanup EXIT INT TERM

require_cluster
cleanup_path "$ROOT"
mkdir -p "$LOCAL_TMP_BASE/p1"

prepare_file() {
  local dir=$1
  local file=$dir/test_${FILE_MB}mb.dat
  local local_file="$LOCAL_TMP_BASE/p1/test_${FILE_MB}mb.dat"
  hdfs_cmd dfs -rm -r -skipTrash "$dir" >/dev/null 2>&1 || true
  hdfs_cmd dfs -mkdir -p "$dir"
  hdfs_cmd storagepolicies -setStoragePolicy -path "$dir" -policy ALL_SSD >/dev/null
  dd if=/dev/zero of="$local_file" bs=1M count="$FILE_MB" 2>/dev/null
  hdfs_cmd dfs -put -f "$local_file" "$file"
  hdfs_cmd storagepolicies -setStoragePolicy -path "$file" -policy ALL_SSD >/dev/null
  hdfs_cmd dfs -touch -a -t "$(date -d '120 days ago' +%Y%m%d:%H%M%S)" "$file"
  echo "$file"
}

wait_archive() {
  local file=$1
  local summary ratio
  for _ in $(seq 1 240); do
    summary=$(storage_summary "$file")
    ratio=$(printf "%s" "$summary" | awk -F 'archive_ratio=' '{print $2}')
    if awk "BEGIN {exit !($ratio >= 0.95)}"; then
      return 0
    fi
    sleep 2
  done
  return 1
}

MANUAL_FILE=$(prepare_file "$MANUAL_DIR")
START=$(date +%s)
hdfs_cmd dfs -ls -R "$MANUAL_DIR" >/dev/null
hdfs_cmd storagepolicies -setStoragePolicy -path "$MANUAL_FILE" -policy COLD >/dev/null
hdfs_cmd storagepolicies -satisfyStoragePolicy -path "$MANUAL_FILE" >/dev/null 2>&1 || true
if ! wait_archive "$MANUAL_FILE"; then
  echo "[FAIL] P1 manual baseline archive movement timeout"
  hdfs_cmd fsck "$MANUAL_FILE" -files -blocks -locations || true
  exit 1
fi
END=$(date +%s)
MANUAL=$((END - START))

AUTO_FILE=$(prepare_file "$AUTO_DIR")
sqlq "DELETE FROM pending_jobs WHERE file_path LIKE '${AUTO_DIR}%';" >/dev/null
save_namespace

CONFIG_FILE="$LOCAL_TMP_BASE/p1-config.yaml"
LOG_FILE="$LOCAL_TMP_BASE/p1-daemon.log"
write_test_config "$CONFIG_FILE"
DAEMON_PID=$(start_daemon "$LOG_FILE" "$CONFIG_FILE")

START=$(date +%s)
AUTO_DONE=0
for _ in $(seq 1 240); do
  STATUS=$(sqlq "SELECT status FROM pending_jobs WHERE file_path='${AUTO_FILE}' ORDER BY job_id DESC LIMIT 1;" || true)
  summary=$(storage_summary "$AUTO_FILE")
  ratio=$(printf "%s" "$summary" | awk -F 'archive_ratio=' '{print $2}')
  if [ "$STATUS" = "COMPLETED" ] && awk "BEGIN {exit !($ratio >= 0.95)}"; then
    AUTO_DONE=1
    break
  fi
  sleep 2
done
END=$(date +%s)
AUTO=$((END - START))

if [ "$AUTO_DONE" -ne 1 ]; then
  echo "[FAIL] P1 auto-tiering archive movement timeout"
  sql_pretty "SELECT job_id, file_path, status, current_tier, target_tier, retry_count, last_error FROM pending_jobs WHERE file_path LIKE '${AUTO_DIR}%';"
  hdfs_cmd fsck "$AUTO_FILE" -files -blocks -locations || true
  tail -100 "$LOG_FILE" || true
  exit 1
fi

MANUAL_PER_GB=$(awk "BEGIN {printf \"%.2f\", $MANUAL / $GB}")
AUTO_PER_GB=$(awk "BEGIN {printf \"%.2f\", $AUTO / $GB}")
DELTA=$(awk "BEGIN {printf \"%.2f\", $AUTO_PER_GB - $MANUAL_PER_GB}")
OVERHEAD=$(awk "BEGIN {if ($MANUAL_PER_GB <= 0) print 0; else printf \"%.2f\", (($AUTO_PER_GB - $MANUAL_PER_GB) / $MANUAL_PER_GB) * 100}")

echo "file_mb=$FILE_MB moved_gb=$GB"
echo "manual_sec_per_gb=$MANUAL_PER_GB"
echo "auto_sec_per_gb=$AUTO_PER_GB"
echo "delta_sec_per_gb=$DELTA"
echo "overhead_percent=$OVERHEAD"

if awk "BEGIN {exit !($OVERHEAD <= 10.0)}"; then
  echo "[PASS] P1 automation overhead <= 10%"
else
  echo "[WARN] P1 automation overhead > 10%. Use the printed metrics with SPS and daemon logs."
fi
SCRIPT

cat > "$SUITE_DIR/e1_cost_saving.sh" <<'SCRIPT'
#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib.sh"

TEST_DIR="$TEST_ROOT/cost/$RUN_ID"
# [Reference & Data Temperature Distribution]
# 1. "GreenHDFS: Towards An Energy-Conserving, Storage-Efficient, Hybrid Hadoop Compute Cluster" (USENIX HotPower '10) - Yahoo! 실측 데이터 기준 Cold 용량 약 60% 증명.
# 2. Hot/Warm 비율은 실증 데이터 부재로 파레토 법칙(Zipf 분포) 및 운영 SLA에 기반한 가변적 추정치(Hot 15%, Warm 25%) 적용.
# 본 비용 절감(E1) 시나리오에서는 위 학술 논문 및 추정치를 종합하여 HOT 15%, WARM 25%, COLD 60% 분포를 채택하여 시뮬레이션합니다.
TOTAL_MB="${COST_FILE_MB:-1000}"
WARM_MB=$((TOTAL_MB * 25 / 100))
COLD_MB=$((TOTAL_MB * 60 / 100))
# HOT (15%) 데이터는 스토리지 계층 이동 대상이 아니므로, 이동에 따른 절감액을 산출하기 위해 이동 대상인 WARM, COLD만 해당 비율에 맞게 생성합니다.

DAEMON_PID=""
cleanup() {
  [ -n "$DAEMON_PID" ] && kill "$DAEMON_PID" >/dev/null 2>&1 || true
  cleanup_path "$TEST_DIR"
  cleanup_local
}
trap cleanup EXIT INT TERM

require_cluster
cleanup_path "$TEST_DIR"
mkdir -p "$LOCAL_TMP_BASE/e1"
hdfs_cmd dfs -mkdir -p "$TEST_DIR"
hdfs_cmd storagepolicies -setStoragePolicy -path "$TEST_DIR" -policy ALL_SSD >/dev/null

local_file_warm="$LOCAL_TMP_BASE/e1/warm.dat"
dd if=/dev/zero of="$local_file_warm" bs=1M count="$WARM_MB" 2>/dev/null
hdfs_cmd dfs -put -f "$local_file_warm" "$TEST_DIR/warm.dat"
hdfs_cmd storagepolicies -setStoragePolicy -path "$TEST_DIR/warm.dat" -policy ALL_SSD >/dev/null

local_file_cold="$LOCAL_TMP_BASE/e1/cold.dat"
dd if=/dev/zero of="$local_file_cold" bs=1M count="$COLD_MB" 2>/dev/null
hdfs_cmd dfs -put -f "$local_file_cold" "$TEST_DIR/cold.dat"
hdfs_cmd storagepolicies -setStoragePolicy -path "$TEST_DIR/cold.dat" -policy ALL_SSD >/dev/null

hdfs_cmd dfs -touch -a -t "$(date -d '60 days ago' +%Y%m%d:%H%M%S)" "$TEST_DIR/warm.dat"
hdfs_cmd dfs -touch -a -t "$(date -d '120 days ago' +%Y%m%d:%H%M%S)" "$TEST_DIR/cold.dat"
save_namespace

CONFIG_FILE="$LOCAL_TMP_BASE/e1-config.yaml"
LOG_FILE="$LOCAL_TMP_BASE/e1-daemon.log"
write_test_config "$CONFIG_FILE"
DAEMON_PID=$(start_daemon "$LOG_FILE" "$CONFIG_FILE")

for _ in $(seq 1 240); do
  COMPLETED=$(sqlq "SELECT count(*) FROM pending_jobs WHERE file_path LIKE '${TEST_DIR}%' AND status='COMPLETED';")
  [ "$COMPLETED" -ge 2 ] && break
  sleep 2
done

if [ "${COMPLETED:-0}" -lt 2 ]; then
  echo "[FAIL] E1 expected 2 completed jobs, got ${COMPLETED:-0}"
  sql_pretty "SELECT job_id, file_path, status, current_tier, target_tier, retry_count, last_error FROM pending_jobs WHERE file_path LIKE '${TEST_DIR}%';"
  tail -100 "$LOG_FILE" || true
  exit 1
fi

HOT_COST="${HOT_COST_PER_GB_MONTH:-0.080}"
WARM_COST="${WARM_COST_PER_GB_MONTH:-0.045}"
COLD_COST="${COLD_COST_PER_GB_MONTH:-0.015}"
HOT_TO_WARM_BYTES=$(sqlq "SELECT COALESCE(SUM(file_size_bytes),0) FROM pending_jobs WHERE status='COMPLETED' AND current_tier='HOT' AND target_tier='WARM' AND file_path LIKE '${TEST_DIR}%';")
HOT_TO_COLD_BYTES=$(sqlq "SELECT COALESCE(SUM(file_size_bytes),0) FROM pending_jobs WHERE status='COMPLETED' AND current_tier='HOT' AND target_tier='COLD' AND file_path LIKE '${TEST_DIR}%';")

awk -v warm_b="$HOT_TO_WARM_BYTES" -v cold_b="$HOT_TO_COLD_BYTES" -v hot="$HOT_COST" -v warm="$WARM_COST" -v cold="$COLD_COST" '
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
    print "[PASS] E1 cost saving metrics produced"
  } else {
    print "[FAIL] E1 no completed moved data found"
    exit 1
  }
}'
SCRIPT

cat > "$SUITE_DIR/e2_namenode_offload.sh" <<'SCRIPT'
#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib.sh"

TEST_DIR="$TEST_ROOT/offload/$RUN_ID"
N="${OFFLOAD_FILE_COUNT:-500}"
DAEMON_PID=""
cleanup() {
  [ -n "$DAEMON_PID" ] && kill "$DAEMON_PID" >/dev/null 2>&1 || true
  cleanup_path "$TEST_DIR"
  cleanup_local
}
trap cleanup EXIT INT TERM

require_cluster
cleanup_path "$TEST_DIR"
hdfs_cmd dfs -mkdir -p "$TEST_DIR"

# Phase 1: 파일 배치 생성 (단일 JVM 호출로 최적화)
# 플랫 디렉토리 구조에서는 ls -R이 1회의 RPC만 발생시키므로, 실제 환경의 복잡한 네임스페이스 탐색 부하를 재현하기 위해 하위 디렉토리 구조로 분산 생성합니다.
echo "[INFO] E2 creating $N test directories and files (batch mode)..."
DIR_ARGS=""
FILE_ARGS=""
for i in $(seq 1 "$N"); do
  DIR_ARGS="$DIR_ARGS $TEST_DIR/dir_$i"
  FILE_ARGS="$FILE_ARGS $TEST_DIR/dir_$i/file.txt"
done
hdfs_cmd dfs -mkdir -p $DIR_ARGS
hdfs_cmd dfs -touchz $FILE_ARGS

# accessTime 일괄 변경 (120일 전 → COLD 대상)
PAST=$(date -d '120 days ago' +%Y%m%d:%H%M%S)
hdfs_cmd dfs -touch -a -t "$PAST" $FILE_ARGS

# Phase 2: ls -R 측 RPC 횟수 측정
LS_BEFORE_OPS=$(jmx_rpc_num_ops)
hdfs_cmd dfs -ls -R "$TEST_DIR" >/dev/null

# Hadoop JMX 메트릭(Metrics2)은 기본 10초 주기로 비동기 갱신되므로 반영 대기
sleep 15
LS_AFTER_OPS=$(jmx_rpc_num_ops)

# Phase 3: Cooldown
sleep 5

# Phase 4: Auto(FSImage) 측 RPC 횟수 측정
save_namespace
AUTO_BEFORE_OPS=$(jmx_rpc_num_ops)
CONFIG_FILE="$LOCAL_TMP_BASE/e2-config.yaml"
LOG_FILE="$LOCAL_TMP_BASE/e2-daemon.log"
write_test_config "$CONFIG_FILE"
DAEMON_PID=$(start_daemon "$LOG_FILE" "$CONFIG_FILE")

# 스코어링 완료 대기 (DB 폴링)
SCORING_DONE=0
for _ in $(seq 1 180); do
  SCORED=$(sqlq "SELECT count(*) FROM pending_jobs WHERE file_path LIKE '${TEST_DIR}%';" || echo 0)
  if [ "${SCORED:-0}" -ge "$N" ]; then
    SCORING_DONE=1
    break
  fi
  sleep 1
done
kill "$DAEMON_PID" >/dev/null 2>&1 || true
DAEMON_PID=""

# 데몬 동작 중 발생한 RPC가 JMX에 반영되도록 대기
sleep 15
AUTO_AFTER_OPS=$(jmx_rpc_num_ops)

if [ "$SCORING_DONE" -ne 1 ]; then
  echo "[FAIL] E2 scoring did not complete within timeout (scored=${SCORED:-0}/${N})"
  tail -100 "$LOG_FILE" || true
  exit 1
fi

# Phase 5: 산출
awk -v ls_before="$LS_BEFORE_OPS" -v ls_after="$LS_AFTER_OPS" \
    -v auto_before="$AUTO_BEFORE_OPS" -v auto_after="$AUTO_AFTER_OPS" -v n="$N" '
BEGIN {
  ls_ops = ls_after - ls_before
  auto_ops = auto_after - auto_before
  reduction = ls_ops > 0 ? (1 - (auto_ops / ls_ops)) * 100 : 0
  printf "file_count=%d\n", n
  printf "ls_rpc_ops=%d\n", ls_ops
  printf "auto_rpc_ops=%d\n", auto_ops
  printf "rpc_reduction_percent=%.2f\n", reduction
  if (ls_ops > 0 && reduction >= 50) {
    print "[PASS] E2 FSImage approach uses " int(reduction) "% fewer NN RPCs than ls -R"
  } else if (ls_ops > 0) {
    printf "[WARN] E2 RPC reduction %.2f%% < 50%%\n", reduction
  } else {
    print "[FAIL] E2 ls -R generated 0 RPCs; increase OFFLOAD_FILE_COUNT"
    exit 1
  }
}'
SCRIPT

cat > "$SUITE_DIR/cleanup-all.sh" <<'SCRIPT'
#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib.sh"

echo "[INFO] cleaning HDFS test root: $TEST_ROOT"
hdfs_cmd dfs -rm -r -skipTrash "$TEST_ROOT/accuracy" "$TEST_ROOT/ranking" "$TEST_ROOT/policy" "$TEST_ROOT/stability-rpc" "$TEST_ROOT/performance" "$TEST_ROOT/cost" "$TEST_ROOT/offload" >/dev/null 2>&1 || true
sqlq "DELETE FROM pending_jobs WHERE file_path LIKE '${TEST_ROOT}/accuracy/%' OR file_path LIKE '${TEST_ROOT}/ranking/%' OR file_path LIKE '${TEST_ROOT}/policy/%' OR file_path LIKE '${TEST_ROOT}/stability-rpc/%' OR file_path LIKE '${TEST_ROOT}/performance/%' OR file_path LIKE '${TEST_ROOT}/cost/%' OR file_path LIKE '${TEST_ROOT}/offload/%';" >/dev/null 2>&1 || true
rm -rf /tmp/dsc-metric-* >/dev/null 2>&1 || true
echo "[PASS] cleanup complete"
SCRIPT

cat > "$SUITE_DIR/run_all.sh" <<'SCRIPT'
#!/usr/bin/env bash
set -euo pipefail

SUITE_DIR="$(cd "$(dirname "$0")" && pwd)"
export SUITE_DIR
export RUN_ID="${RUN_ID:-$(date +%Y%m%d%H%M%S)}"

tests=(
  a1_accuracy.sh
  a2_spearman.sh
  a3_policy_completion.sh
  s1_rpc_queue.sh
  p1_transfer_time.sh
  e1_cost_saving.sh
  e2_namenode_offload.sh
)

"$SUITE_DIR/cleanup-all.sh" || true

failed=0
for test_script in "${tests[@]}"; do
  echo ""
  echo "============================================================"
  echo "[RUN] $test_script RUN_ID=$RUN_ID"
  echo "============================================================"
  if "$SUITE_DIR/$test_script"; then
    echo "[OK] $test_script"
  else
    echo "[NG] $test_script"
    failed=$((failed + 1))
  fi
done

if [ "${RUN_DESTRUCTIVE:-0}" = "1" ]; then
  echo ""
  echo "============================================================"
  echo "[RUN] s2_yarn_recovery.sh"
  echo "============================================================"
  S2_ALLOW_KILL=1 "$SUITE_DIR/s2_yarn_recovery.sh" || failed=$((failed + 1))
else
  echo "[SKIP] S2 is destructive. Use RUN_DESTRUCTIVE=1 $SUITE_DIR/run_all.sh to include it."
fi

"$SUITE_DIR/cleanup-all.sh" || true

if [ "$failed" -eq 0 ]; then
  echo "[PASS] all selected tests passed"
else
  echo "[FAIL] $failed selected test(s) failed"
  exit 1
fi
SCRIPT

chmod +x "$SUITE_DIR"/*.sh
echo "[PASS] test suite installed at $SUITE_DIR"
echo "Run all non-destructive tests: $SUITE_DIR/run_all.sh"
echo "Run one test: $SUITE_DIR/a1_accuracy.sh"
echo "Cleanup only: $SUITE_DIR/cleanup-all.sh"
INSTALL

chmod +x ~/install-dsc-metric-tests.sh
~/install-dsc-metric-tests.sh
```

## 4. 실행 방법

전체 비파괴 테스트:

```bash
~/dsc-metric-tests/run_all.sh
```

S2(YARN 복구)까지 포함:

```bash
RUN_DESTRUCTIVE=1 ~/dsc-metric-tests/run_all.sh
```

개별 실행:

```bash
~/dsc-metric-tests/a1_accuracy.sh
~/dsc-metric-tests/a2_spearman.sh
~/dsc-metric-tests/a3_policy_completion.sh
~/dsc-metric-tests/s1_rpc_queue.sh
~/dsc-metric-tests/s2_yarn_recovery.sh
~/dsc-metric-tests/p1_transfer_time.sh
~/dsc-metric-tests/e1_cost_saving.sh
~/dsc-metric-tests/e2_namenode_offload.sh
```

대용량 테스트로 실행하려면 파일 크기를 키운다.

```bash
A3_FILE_MB=1024 ~/dsc-metric-tests/a3_policy_completion.sh
TRANSFER_FILE_MB=1024 ~/dsc-metric-tests/p1_transfer_time.sh
COST_FILE_MB=1024 ~/dsc-metric-tests/e1_cost_saving.sh
OFFLOAD_FILE_COUNT=3000 ~/dsc-metric-tests/e2_namenode_offload.sh
```

수동 정리:

```bash
~/dsc-metric-tests/cleanup-all.sh
```

## 5. 출력의 의미

공통 판정:

| 출력 | 의미 |
|---|---|
| `[PASS]` | 해당 지표의 성공 기준을 만족했거나, 산출형 지표 계산이 정상 완료되었다. |
| `[FAIL]` | 성공 기준을 만족하지 못했거나 필수 전제, job 생성, 물리 이동, 복구가 실패했다. 스크립트 exit code는 1이다. |
| `[WARN]` | 정량값은 산출했지만 권장 기준을 넘었다. P1처럼 권장 기준인 경우 결과 해석이 필요하다. |
| `[SKIP]` | 안전상 기본 실행하지 않는 테스트다. 현재는 S2가 해당한다. |

DB 상태:

| 상태 | 의미 |
|---|---|
| `PENDING` | ScoringEngine이 이동 대상을 DB에 넣었고 Scheduler가 아직 잡지 않았다. |
| `DISPATCHED` | Scheduler가 job을 잡아 `setStoragePolicy + satisfyStoragePolicy` 호출을 시도했다. |
| `IN_PROGRESS` | CompletionTracker가 검증 대상으로 잡았다. |
| `COMPLETED` | HdfsPolicyChecker가 목표 storage type 충족을 확인했다. |
| `FAILED` | HDFS 호출 실패 또는 tracker timeout으로 실패 처리됐다. |

지표별 출력:

| 지표 | 핵심 출력 | 해석 |
|---|---|---|
| A1 | `expected`, `detected`, `false_positive`, `cold`, `warm` | `detected=40`, `false_positive=0`, `cold=20`, `warm=20`이면 Precision/Recall이 모두 1.00이다. |
| A2 | `spearman_rho` | 1.0에 가까울수록 oracle 순위와 실제 우선순위가 일치한다. 기준은 `>= 0.95`다. |
| A3 | `status`, `policy`, `archive_ratio` | `COMPLETED`이고 `archive_ratio >= 0.95`이면 COLD 물리 이동까지 완료된 것이다. |
| S1 | `baseline_avg_ms`, `during_avg_ms`, `rpc_queue_increase_percent` | 오토티어링 중 NameNode RPC queue time 증가율이다. 기준은 `<= 5%`다. |
| S2 | `elapsed`, `state`, `pid` | 강제 종료 후 새 Java PID가 생기고 YARN 상태가 `RUNNING` 또는 `STABLE`이면 복구 완료다. |
| P1 | `manual_sec_per_gb`, `auto_sec_per_gb`, `overhead_percent` | 수동 기준선 대비 자동화 파이프라인의 시간 오버헤드다. 권장 기준은 `<= 10%`다. |
| E1 | `moved_total_gb`, `monthly_saving`, `saving_rate_percent` | HOT 유지 대비 WARM/COLD 이관으로 절감되는 월간 비용 추정치다. |
| E2 | `ls_rpc_ops`, `auto_rpc_ops`, `rpc_reduction_percent` | `ls -R` 대비 FSImage 기반 접근의 NameNode RPC 호출 횟수 절감률이다. 산출형 지표다. |

## 6. 테스트별 생성/정리 범위

| 테스트 | 생성 HDFS 경로 | 생성 로컬 경로 | DB 정리 조건 |
|---|---|---|---|
| A1 | `/test/metric/accuracy/<RUN_ID>` | `/tmp/dsc-metric-<RUN_ID>` | `file_path LIKE '<TEST_DIR>%'` |
| A2 | `/test/metric/ranking/<RUN_ID>` | `/tmp/dsc-metric-<RUN_ID>` | `file_path LIKE '<TEST_DIR>%'` |
| A3 | `/test/metric/policy/<RUN_ID>` | `/tmp/dsc-metric-<RUN_ID>` | `file_path LIKE '<TEST_DIR>%'` |
| S1 | `/test/metric/stability-rpc/<RUN_ID>` | `/tmp/dsc-metric-<RUN_ID>` | `file_path LIKE '<TEST_DIR>%'` |
| S2 | 없음 | 없음 | 없음 |
| P1 | `/test/metric/performance/<RUN_ID>` | `/tmp/dsc-metric-<RUN_ID>` | `file_path LIKE '<ROOT>%'` |
| E1 | `/test/metric/cost/<RUN_ID>` | `/tmp/dsc-metric-<RUN_ID>` | `file_path LIKE '<TEST_DIR>%'` |
| E2 | `/test/metric/offload/<RUN_ID>` | `/tmp/dsc-metric-<RUN_ID>` | `file_path LIKE '<TEST_DIR>%'` |

각 개별 스크립트는 `trap cleanup EXIT INT TERM`을 사용한다. 성공, 실패, Ctrl+C 종료 모두에서 정리를 시도한다.

## 7. 단위 테스트

서비스 코드 자체의 단위 테스트는 Maven으로 실행한다.

```bash
cd ~/DSC/services/hdfs-auto-tiering
mvn test
```

Docker가 없어서 Testcontainers 기반 PostgreSQL 테스트가 불가능하면 다음처럼 Repository 테스트만 제외한다.

```bash
cd ~/DSC/services/hdfs-auto-tiering
mvn -Dtest='!PendingJobRepositoryTest' test
```

테스트 클래스별 의미:

| 클래스 | 검증 내용 |
|---|---|
| `ConfigLoaderTest` | YAML kebab-case/camelCase 매핑, 기본값, `/test/metric` 화이트리스트 |
| `PriorityRuleTest` | 30일/90일 기준 HOT/WARM/COLD 목표 티어 산정 |
| `ScoringEngineTest` | 낮은 `priority_score` 우선, target directory 필터 |
| `WindowSelectorTest` | 일반/자정 경유/24시간 window 선택 |
| `BatchSchedulerTest` | HDFS 호출 성공/실패와 `recordHdfsFailure` 호출 |
| `PendingJobRepositoryTest` | PostgreSQL DDL, `SKIP LOCKED`, 중복 활성 job 방지 |
| `HdfsPolicyCheckerTest` | HOT/WARM/COLD storage type 만족도 판정 |
| `CompletionTrackerTest` | tracker batch 처리, timeout, 완료/미완료 상태 처리 |

## 8. 최종 제출용 결과표

발표 또는 보고서에는 실제 측정값으로 아래 표를 채운다.

| 지표 | 측정값 | 성공 기준 | 판정 | 증거 |
|---|---:|---:|---|---|
| A1 Precision |  | 1.00 |  | `a1_accuracy.sh` 출력 |
| A1 Recall |  | 1.00 |  | `a1_accuracy.sh` 출력 |
| A2 Spearman rho |  | >= 0.95 |  | `a2_spearman.sh` 출력 |
| A3 목표 정책 전환율 |  | 100% |  | DB `COMPLETED`, `archive_ratio >= 0.95` |
| S1 RPC Queue Time 상승폭 |  | <= 5% |  | `s1_rpc_queue.sh` 출력 |
| S2 YARN 복구 시간 |  | <= 30s |  | `s2_yarn_recovery.sh` 출력 |
| P1 manual sec/GB |  | 산출 |  | `p1_transfer_time.sh` 출력 |
| P1 auto sec/GB |  | 산출 |  | `p1_transfer_time.sh` 출력 |
| P1 overhead |  | 권장 <= 10% |  | `p1_transfer_time.sh` 출력 |
| E1 monthly saving |  | 산출 |  | `e1_cost_saving.sh` 출력 |
| E1 saving rate |  | 산출 |  | `e1_cost_saving.sh` 출력 |
| E2 RPC reduction |  | 산출 |  | `e2_namenode_offload.sh` 출력 |
