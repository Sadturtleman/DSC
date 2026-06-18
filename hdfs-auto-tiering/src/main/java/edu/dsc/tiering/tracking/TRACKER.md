# completion-tracker

Completion Tracker는 `DISPATCHED` 상태로 표시된 `pending_jobs`를 주기적으로 확인해 HDFS 블록 이동 완료 여부를 판정하고, DB 상태를 `COMPLETED`, `FAILED` 또는 재시도 처리를 적용하는 컴포넌트입니다.

## 요구 사항

- PostgreSQL 14+ (`db/V001__pending_jobs.sql` 적용)
- Hadoop 3.x (External SPS 활성화)
- Java 11

## 파일 구조 (요약)

- `edu.dsc.tiering.Main` – 자원 초기화 및 Worker 스레드 기동
- `edu.dsc.tiering.config.AppConfig` / `ConfigLoader` – Tracker 설정 로드 (`TrackerSettings`)
- `edu.dsc.tiering.repository.PendingJobRepository` – DB 접근 (`claimTrackableBatch`, `markCompleted`, `markFailed`, `markForRetry`, `recordHdfsFailure` 등)
- `edu.dsc.tiering.hdfs.HdfsPolicyChecker` – 블록 스토리지 타입 조회 및 완료 판정
- `edu.dsc.tiering.tracking.CompletionTracker` – 폴링 루프 및 상태 전이 로직

## 동작 개요

1. `claimTrackableBatch(batchSize)`로 `DISPATCHED` 또는 `IN_PROGRESS` 상태의 job들을 원자적으로 점유하고, 해당 행들을 `IN_PROGRESS`로 전이합니다 (SELECT ... FOR UPDATE SKIP LOCKED).
2. 각 job에 대해 `checkOne(job)`을 worker 풀로 병렬 실행합니다. `checkOne`은 내부적으로 `nnSemaphore`를 획득해 NameNode에 대한 동시 RPC 수를 제한합니다.
3. worker 풀은 `cfg.pollIntervalSeconds()` 동안 작업 종료를 기다리고 미완료 작업은 다음 사이클로 이월하거나 취소합니다.
4. 각 job에 대해 다음을 판정합니다:
     - `dispatched_at`이 없으면 `markFailed`로 처리(데이터 불일치 경고).
     - `dispatched_at` 기준으로 경과시간이 `timeoutMinutes` 이상이면 재시도 한도를 조회해 재시도 또는 영구 실패 처리(코드상 `markFailed`/`markFailedPermanently` 호출).
     - RPC 결과가 `true`이면 `markCompleted` 호출, `false`이면 `touchCheckedAt`로 다음 사이클에 재검증합니다.
5. HDFS 조회 중 `FileNotFoundException`이 감지되면 `recordHdfsFailure(..., maxRetries=0)`로 영구 실패 처리합니다.

## 완료 판정 (HdfsPolicyChecker)

- `HdfsPolicyChecker.isSatisfied(path, targetTier)`는 `dfs.getFileStatus`와 `getLocatedBlocks`로 블록 정보를 조회합니다.
- 판정 로직:
    - HOT/COLD: 블록 타입 중 목표 타입의 비율(ratio) >= `completionRatio`이면 충족 (기본 0.95).
    - WARM(One_SSD): SSD 타입 블록이 1개 이상이면 충족.

## 설정 및 기본값

- `poll-interval-seconds`: 45
- `timeout-minutes`: 60
- `batch-size`: 20
- `completion-ratio`: 0.95
- `max-workers`: 5
- `nodename-semaphore` (동시 NN RPC): 3
- `max-retry-count`: 5

설정은 `src/main/resources/application.yaml`과 `AppConfig.TrackerSettings`를 통해 로드됩니다.