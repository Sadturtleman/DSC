# `pending_jobs` 테이블 계약

이 문서는 **스코어링 엔진 ↔ batch scheduler ↔ completion tracker** 가 공유하는
PostgreSQL 테이블 계약을 정의한다. 본 계약은 batch scheduler 담당자가 박아두며,
다른 컴포넌트들은 이 계약에 맞춰 구현한다.

> DDL은 [`db/migrations/V001__pending_jobs.sql`](../../db/migrations/V001__pending_jobs.sql)
> 을 single source of truth로 본다. 본 문서와 DDL이 어긋나면 DDL이 우선이며,
> 본 문서를 업데이트한다.

## 1. 테이블 개요

`pending_jobs` 한 행 = **"파일 한 개의 티어 전환 요청"** 단위.

| 컬럼 | 타입 | 누가 채우나 | 설명 |
|---|---|---|---|
| `job_id` | `BIGSERIAL PK` | DB | 자동 채번 |
| `file_path` | `TEXT NOT NULL` | scoring engine | HDFS 절대 경로 |
| `file_size_bytes` | `BIGINT NOT NULL` | scoring engine | FSImage 기준 파일 크기 |
| `current_tier` | `tier NOT NULL` | scoring engine | 현재 스토리지 정책 매핑 (HOT/WARM/COLD) |
| `target_tier` | `tier NOT NULL` | scoring engine | 이동 목표 티어 |
| `priority_score` | `DOUBLE PRECISION NOT NULL` | scoring engine | 높을수록 먼저 처리 |
| `status` | `job_status NOT NULL` | 컴포넌트별 (§3) | 상태머신 |
| `scored_at` | `TIMESTAMPTZ NOT NULL` | scoring engine | INSERT 시각 |
| `dispatched_at` | `TIMESTAMPTZ` | batch scheduler | setStoragePolicy 호출 성공 시각 |
| `completed_at` | `TIMESTAMPTZ` | completion tracker | 블록 이동 검증 완료 시각 |
| `retry_count` | `INT NOT NULL DEFAULT 0` | batch scheduler / tracker | 재시도 횟수 |
| `last_error` | `TEXT` | 실패 측 | 마지막 실패 사유 (예외 메시지) |
| `fsimage_snapshot_id` | `BIGINT` | scoring engine | (선택) 출처 FSImage 식별자 |

### 1.1. 열거형

- `tier`: `'HOT' | 'WARM' | 'COLD'`
  - HDFS 물리 정책 매핑: `HOT ↔ All_SSD`, `WARM ↔ One_SSD`, `COLD ↔ Cold`
- `job_status`: `'PENDING' | 'DISPATCHED' | 'IN_PROGRESS' | 'COMPLETED' | 'FAILED' | 'CANCELLED'`

### 1.2. 불변 조건

- `current_tier <> target_tier` (CHECK constraint)
- 동일 `file_path` 에 대해 활성(`PENDING|DISPATCHED|IN_PROGRESS`) 상태의 job은
  **최대 1개**. partial unique index 로 강제.
  → scoring engine 이 동일 파일을 재스코어링 하려면 먼저 기존 활성 job 을
  `CANCELLED` 처리해야 함.

## 2. 상태 머신

```
   (scoring engine INSERT)
            │
            ▼
        ┌─────────┐    (newer score / manual)    ┌───────────┐
        │ PENDING │ ──────────────────────────▶ │ CANCELLED │ (terminal)
        └────┬────┘                              └───────────┘
             │
   (scheduler atomic claim:
    PENDING → DISPATCHED + setStoragePolicy + satisfyStoragePolicy)
             │
   ┌─────────┴─────── HDFS call fails ──────────────────────┐
   │                                                        │
   ▼ success                                                ▼
┌────────────┐       (tracker timeout >  T_dispatch)    retry_count++
│ DISPATCHED │ ──────────────────────────────────────▶  PENDING (또는
└──────┬─────┘                                          retry 한도 초과 시 FAILED)
       │
   (tracker: SPS picked up the work)
       ▼
┌───────────────┐    (tracker timeout > T_progress)    ┌────────┐
│ IN_PROGRESS   │ ──────────────────────────────────▶ │ FAILED │ (terminal)
└───────┬───────┘    또는 retry → PENDING               └────────┘
        │
   (tracker: block locations 가 target_tier 와 일치)
        ▼
   ┌───────────┐
   │ COMPLETED │ (terminal)
   └───────────┘
```

### 2.1. 타임아웃 / 재시도 (계약 값)

- `T_dispatch` (DISPATCHED 후 SPS pickup 대기): 기본 5분, YAML 로 오버라이드
- `T_progress` (IN_PROGRESS 후 완료 대기): 파일 크기 × 환산 계수 + 안전 마진
- 최대 재시도 `max_retries`: 기본 3회. 초과 시 `FAILED` (terminal).

타임아웃 감지 및 재시도 상태 전이는 **completion tracker 담당**.
scheduler 는 HDFS 호출 직후 동기적으로 실패한 경우에만 `retry_count++` &
`PENDING` 으로 즉시 되돌린다.

## 3. 상태 전이 권한 매트릭스

| From → To | 누가 | 조건 |
|---|---|---|
| (none) → `PENDING` | scoring engine | INSERT |
| `PENDING` → `DISPATCHED` | batch scheduler | `setStoragePolicy + satisfyStoragePolicy` 성공 |
| `PENDING` → `PENDING` (retry++) | batch scheduler | HDFS 호출 동기 실패 |
| `PENDING` → `FAILED` | batch scheduler | 위 실패이면서 `retry_count >= max_retries` |
| `PENDING` → `CANCELLED` | scoring engine / 운영자 | 재스코어링 또는 수동 취소 |
| `DISPATCHED` → `IN_PROGRESS` | completion tracker | SPS JMX/log 에서 픽업 감지 |
| `DISPATCHED` → `PENDING` (retry++) | completion tracker | `T_dispatch` 초과 |
| `IN_PROGRESS` → `COMPLETED` | completion tracker | block location 검증 통과 |
| `IN_PROGRESS` → `PENDING` (retry++) | completion tracker | `T_progress` 초과 |
| `IN_PROGRESS` → `FAILED` | completion tracker | 재시도 한도 초과 또는 영구 오류 |

> **Rule of thumb**: 한 상태에는 단 하나의 컴포넌트만 write 권한을 가진다.
> 다른 컴포넌트가 같은 상태를 write 하려면 본 문서를 먼저 갱신할 것.

## 4. 표준 쿼리 패턴

### 4.1. scoring engine — INSERT

```sql
INSERT INTO pending_jobs
    (file_path, file_size_bytes, current_tier, target_tier,
     priority_score, status, scored_at, fsimage_snapshot_id)
VALUES ($1, $2, $3, $4, $5, 'PENDING', NOW(), $6)
ON CONFLICT (file_path)
    WHERE status IN ('PENDING','DISPATCHED','IN_PROGRESS')
DO NOTHING;
```

### 4.2. batch scheduler — 원자적 claim (SKIP LOCKED)

```sql
UPDATE pending_jobs
SET status = 'DISPATCHED',
    dispatched_at = NOW()
WHERE job_id IN (
    SELECT job_id FROM pending_jobs
    WHERE status = 'PENDING'
    ORDER BY priority_score DESC, scored_at ASC
    LIMIT $batch_size
    FOR UPDATE SKIP LOCKED
)
RETURNING *;
```

> ⚠️ 이 UPDATE 는 HDFS 호출 *직전* 에 실행. HDFS 호출 실패 시 별도 UPDATE 로
> `PENDING` 되돌리고 `retry_count++`, `last_error` 기록. dispatched_at 은 NULL 로
> 다시 비운다.

### 4.3. completion tracker — 진행/완료/타임아웃 갱신

진행 갱신:
```sql
UPDATE pending_jobs SET status = 'IN_PROGRESS'
WHERE job_id = $1 AND status = 'DISPATCHED';
```

완료 마크:
```sql
UPDATE pending_jobs
SET status = 'COMPLETED', completed_at = NOW()
WHERE job_id = $1 AND status = 'IN_PROGRESS';
```

타임아웃 재시도 (DISPATCHED 5분 초과 예시):
```sql
UPDATE pending_jobs
SET status = CASE WHEN retry_count + 1 >= $max_retries
                  THEN 'FAILED' ELSE 'PENDING' END,
    retry_count = retry_count + 1,
    dispatched_at = NULL,
    last_error = 'dispatch timeout'
WHERE status = 'DISPATCHED'
  AND dispatched_at < NOW() - INTERVAL '5 minutes';
```

## 5. 미해결 항목 (팀 협의 필요)

- [ ] `priority_score` 의 정규화 범위 (0~1 vs 무제한)
- [ ] `fsimage_snapshot_id` 를 별도 테이블 FK 로 둘지 (감사 추적)
- [ ] CANCELLED 후 동일 path 재 INSERT 시점의 race condition
- [ ] tier 별 통계 집계 뷰 (운영 모니터링용) — 누가 만들지
