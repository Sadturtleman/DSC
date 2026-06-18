# hdfs-auto-tiering

hdfs-auto-tiering는 HDFS 파일 스토리지 정책(Tiering)을 자동화하는 컴포넌트입니다.
세 부분으로 구성됩니다: 스코어링(ScoringEngine), 배치 스케줄러(BatchScheduler), 완료 트래커(CompletionTracker).

요약 흐름
- ScoringEngine: NameNode의 FSImage를 수집·파싱해 이동 대상 파일을 계산하고 `pending_jobs`에 삽입합니다.
- BatchScheduler: 우선순위 상위 PENDING job을 원자적으로 점유(claim)해 DISPATCHED로 전이하고, HDFS API로 목표 스토리지 정책을 적용합니다.
- CompletionTracker: DISPATCHED/IN_PROGRESS job을 주기적으로 확인해 이동 완료 시 COMPLETED, 실패 시 FAILED로 마킹합니다.

## 빌드 및 실행

빌드:

```bash
mvn -q -DskipTests package
```

실행 (클래스패스의 기본 `application.yaml` 사용):

```bash
java -jar target/hdfs-auto-tiering.jar
```

외부 설정 파일을 지정하려면 YAML 경로를 인수로 전달합니다:

```bash
java -jar target/hdfs-auto-tiering.jar /etc/dsc/hdfs-auto-tiering.yaml
```

## 요구 사항

- PostgreSQL 14+ (마이그레이션 스크립트: `db/V001__pending_jobs.sql`)
- Hadoop 3.x (External SPS 활성화: `dfs.storage.policy.satisfier.mode=external`)
- Java 11

## 구성 파일

기본 설정 파일: `src/main/resources/application.yaml`.
주요 설정 항목:
- `database`: JDBC URL, 사용자, 패풀 설정
- `hdfs`: `fs-default-name`, `user`, `policy-mapping`(티어→HDFS 정책)
- `scoring`: `enabled`, `interval-seconds`, `weight-access-time`, `weight-file-size`, `local-fsimage-dir`, `target-directories`
- `scheduler`: `poll-interval-seconds`, `windows`(시간대별 batch-size/대기시간), `concurrency`, `max-retries`
- `tracker`: `poll-interval-seconds`, `timeout-minutes`, `batch-size`, `completion-ratio`, `max-workers`, `nodename-semaphore`, `max-retry-count`

설정 로딩은 `edu.dsc.tiering.config.ConfigLoader`가 담당하며, kebab-case YAML 키를 Java POJO로 매핑합니다.

## 아키텍처 개요

- `Main` : 설정을 로드하고 `HikariDataSource`, `HdfsApiCaller`, `PendingJobRepository`, `BatchScheduler`, `ScoringEngine`, `CompletionTracker`를 초기화합니다.
- `ScoringEngine` : `FsImageFetcher`로 FSImage를 받아 파싱하고, `PriorityRule`로 우선순위를 계산해 `PendingJobRepository.insertPendingJobs`를 통해 `PENDING` 상태로 저장합니다. 스코어링은 `scoring.target-directories` 화이트리스트에 따라 동작합니다.
- `PriorityRule` : 접근 시간과 파일 크기를 가중합해 이동 우선순위를 산출하고, 목표 티어(`Tier`)를 결정합니다.
- `PendingJobRepository` : DB 연산을 담당합니다. `claimBatch`와 `claimTrackableBatch`는 `FOR UPDATE SKIP LOCKED` 패턴을 사용해 다중 인스턴스 동시 실행을 안전하게 처리합니다. 실패 기록 및 상태 전이 관련 유틸을 제공합니다.
- `BatchScheduler` : 현재 윈도우에 따른 배치 크기만큼 `claimBatch`로 작업을 가져와 병렬로 `HdfsApiCaller.applyTier`를 호출합니다. 실패 시 `recordHdfsFailure`를 호출해 재시도 로직을 적용합니다.
- `HdfsApiCaller` : `FileSystem.setStoragePolicy`와 `HdfsAdmin.satisfyStoragePolicy`를 호출해 외부 SPS로 블록 이동을 요청합니다.
- `HdfsPolicyChecker` : `getLocatedBlocks`로 블록의 스토리지 타입을 조회해 목표 정책 충족 여부를 판정합니다. `CompletionTracker`가 이를 호출해 완료 여부를 확정합니다.

## DB 계약

스케마와 인덱스는 `db/V001__pending_jobs.sql`에 정의되어 있습니다. 핵심 제약:
- 단일 경로당 활성 job은 하나 (`UNIQUE INDEX ... WHERE status IN ('PENDING','DISPATCHED','IN_PROGRESS')`)
- `priority_score`와 `scored_at`를 이용해 SKIP LOCKED 기반 픽업 순서를 결정합니다.

## 테스트

기본 테스트 실행:

```bash
mvn test
```

`PendingJobRepositoryTest`는 Testcontainers 기반 PostgreSQL 컨테이너를 사용하므로 Docker가 필요합니다. Docker 환경이 없으면 특정 단위 테스트만 선별 실행할 수 있습니다.

예:

```bash
mvn -Dtest=WindowSelectorTest test
mvn -Dtest='!PendingJobRepositoryTest' test
```