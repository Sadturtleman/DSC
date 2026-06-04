# Timeout Retry 구현 명세

---

## 1. 수정 파일 목록

| 파일 | 유형 |
|---|---|
| `db/migrations/V002__add_retry_columns.sql` | 신규 생성 |
| `src/main/resources/application.yaml` | 수정 |
| `src/main/java/edu/dsc/tiering/config/AppConfig.java` | 수정 |
| `src/main/java/edu/dsc/tiering/repository/PendingJobRepository.java` | 수정 |
| `src/main/java/edu/dsc/tiering/tracking/CompletionTracker.java` | 수정 |

---

## 2. 추가 내용

### DB (`V002__add_retry_columns.sql`)
`pending_jobs` 테이블에 컬럼 2개와 인덱스 1개를 추가한다.

- `retry_count INT NOT NULL DEFAULT 0` — 재시도 횟수를 누적 기록한다.
- `last_failed_at TIMESTAMP` — 마지막 타임아웃 발생 시각을 기록한다. 지수 백오프의 기준 값으로 사용한다.
- `idx_pending_jobs_retry` — `status = 'PENDING'` 조건의 재시도 대상 조회를 최적화한다.

### 설정 (`application.yaml`, `AppConfig.java`)
`tracker` 설정 블록에 `max-retry-count` 항목을 추가한다. 기본값은 5이다. 이 값을 초과한 job은 더 이상 재시도하지 않고 `FAILED`로 최종 처리한다.

### Repository (`PendingJobRepository.java`)
메서드 3개를 추가한다.

- `markForRetry(long id)` — status를 `PENDING`으로 되돌리고 `retry_count`를 1 증가시킨다. `dispatched_at`과 `last_checked_at`은 NULL로 초기화하여 BatchScheduler가 다음 사이클에 정상적으로 재픽업할 수 있도록 한다.
- `markFailedPermanently(long id)` — `retry_count`가 한도를 초과한 경우 `FAILED`로 최종 마킹한다.
- `getRetryCount(long id)` — CompletionTracker가 재시도 여부를 결정하기 위해 현재 `retry_count`를 조회한다.

### Tracker (`CompletionTracker.java`)
타임아웃 감지 후 처리 분기를 수정한다.

---

## 3. 로직 변경사항

### 변경 전
타임아웃이 발생하면 무조건 `FAILED`로 마킹하고 종료된다. 이후 해당 job을 재처리하는 주체가 없으므로 파일이 이관되지 않은 채 영구적으로 방치된다.

```
DISPATCHED/IN_PROGRESS → (타임아웃) → FAILED (종료)
```

### 변경 후
타임아웃 발생 시 `retry_count`를 확인하여 분기한다. 한도 미만이면 `PENDING`으로 복귀시키고, 한도 초과 시에만 `FAILED`로 최종 처리한다. `PENDING`으로 복귀된 job은 별도 처리 없이 BatchScheduler의 다음 폴링 사이클에서 자동으로 재픽업된다.

```
DISPATCHED/IN_PROGRESS
    → (타임아웃 + retry_count < max) → PENDING (retry_count + 1) → BatchScheduler 재픽업
    → (타임아웃 + retry_count >= max) → FAILED (최종)
```

### 지수 백오프
동일 파일이 즉시 재시도되어 클러스터 부하가 집중되는 것을 방지하기 위해 `markForRetry` 시 `scored_at`을 `NOW() + (retry_count * 5분)`으로 설정한다. BatchScheduler의 `claimBatch` 쿼리에 `WHERE scored_at <= NOW()` 조건이 있으므로 자동으로 대기 후 재시도된다.

| retry_count | 재시도 대기 시간 |
|---|---|
| 1회 | 5분 후 |
| 2회 | 10분 후 |
| 3회 | 15분 후 |
| ... | ... |
| max 초과 | FAILED (최종) |

---

## 4. 프로젝트 적용 방법

### Step 1 — DB 마이그레이션 적용
서버(Ubuntu)에서 아래 명령어를 실행한다. INFRA.md 7-3절의 V001 적용 방식과 동일하다.

```bash
psql -h localhost -U dsc -d dsc_tiering \
    -f ~/DSC/db/migrations/V002__add_retry_columns.sql
```

적용 결과를 확인한다.

```bash
psql -h localhost -U dsc -d dsc_tiering -c "\d pending_jobs"
# retry_count, last_failed_at 컬럼이 출력되면 정상이다.
```

### Step 2 — 설정 파일 수정
`src/main/resources/application.yaml`의 `tracker` 블록에 아래 항목을 추가한다.

```yaml
tracker:
  poll-interval-seconds: 5
  timeout-minutes: 60
  batch-size: 20
  completion-ratio: 0.95
  max-workers: 5
  namenode-semaphore: 3
  max-retry-count: 5        # 추가
```

### Step 3 — 빌드 및 배포
DEPLOY.md의 절차에 따라 빌드하고 배포한다.

```bash
# Windows 개발 PC에서
mvn clean package -DskipTests
git tag v1.x.x
git push origin v1.x.x

# 서버(Ubuntu)에서
~/deploy-auto-tiering.sh
```

### Step 4 — 동작 확인
모니터링 쿼리로 `retry_count`가 증가하며 `PENDING`으로 복귀되는지 확인한다.

```bash
watch -n 2 "psql -h localhost -U dsc -d dsc_tiering \
  -c \"SELECT job_id, file_path, status, retry_count, last_failed_at \
       FROM pending_jobs ORDER BY job_id DESC LIMIT 20;\""
```