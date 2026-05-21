# completion-tracker

HDFS Auto-Tiering 의 **Completion Tracker** 컴포넌트.

batch-scheduler 가 `pending_jobs` 테이블에 `DISPATCHED` 로 마킹한 job 을
주기적으로 샘플링해 실제 블록 이동 완료 여부를 검증하고, DB 상태를
`COMPLETED` 또는 `FAILED` 로 전이시킨다.

## 빌드 / 실행

```bash
# 빌드 (fat jar)
mvn -q -DskipTests package

# 실행 (classpath application.yaml 사용)
java -jar target/completion-tracker-0.1.0-SNAPSHOT.jar

# 외부 yaml 경로 지정
java -jar target/completion-tracker-0.1.0-SNAPSHOT.jar /etc/dsc/tracker.yaml
```

## 의존 환경

| 의존 | 비고 |
|---|---|
| PostgreSQL 14+ | `db/migrations/V001__pending_jobs.sql` 이 적용된 상태 |
| Hadoop 3.3.x | `dfs.storage.policy.satisfier.mode=external` |
| Java 17 | 빌드 & 런타임 |

## 구조

```
markdown## 파일 구조
edu.dsc.tiering
├── Main.java                        엔트리포인트 — DB·HDFS 공유 자원 초기화 및 3대 Worker 스레드 기동
│                                    (ScoringEngine / BatchScheduler / CompletionTracker)
│
├── config/
│   ├── AppConfig.java               YAML → record (TrackerSettings 포함)
│   └── ConfigLoader.java            Jackson YAML 로더 (kebab-case)
│
├── model/
│   ├── Tier.java                    HOT / WARM / COLD + expectedStorageTypes() / isMixedPolicy()
│   ├── JobStatus.java               PENDING / DISPATCHED / IN_PROGRESS / COMPLETED / FAILED / CANCELLED
│   ├── PendingJob.java              pending_jobs 테이블 한 행 — scoring·scheduler 용 (record)
│   └── DispatchedJob.java           pending_jobs 테이블 한 행 — completion tracker 용 (record)
│
├── repository/
│   └── PendingJobRepository.java    HikariCP 기반 JDBC CRUD
│                                    ├── claimBatch()       FOR UPDATE SKIP LOCKED
│                                    ├── markCompleted()    DISPATCHED → COMPLETED
│                                    ├── markFailed()       DISPATCHED → FAILED (타임아웃)
│                                    └── touchCheckedAt()   last_checked_at 갱신
│
├── hdfs/
│   ├── HdfsApiCaller.java           스토리지 정책 변경 및 SPS 호출 (batch-scheduler 소유)
│   │                                ├── setStoragePolicy()
│   │                                └── satisfyStoragePolicy()
│   ├── FsImageFetcher.java          DFSAdmin.fetchImage 및 OIV 파싱 래퍼 (scoring 소유)
│   └── HdfsPolicyChecker.java       블록 이동 완료 여부 검증 (completion-tracker 소유)
│                                    └── isSatisfied()      DFSClient.getBlockLocations 호출
│
├── scoring/
│   ├── ScoringEngine.java           FSImage 메타데이터 분석 → pending_jobs INSERT
│   └── PriorityRule.java            접근 시간·파일 크기 기반 우선순위 점수 산출
│
├── scheduler/
│   └── BatchScheduler.java          PENDING → DISPATCHED 전이 + HdfsApiCaller 호출
│
└── tracking/
    └── CompletionTracker.java       DISPATCHED → COMPLETED | FAILED 검증 루프
        ├── runCycle()         한 폴링 사이클 실행
        ├── checkOne()         Semaphore 획득 후 단일 파일 검사
        └── Semaphore          NN RPC 동시 호출 수 제한
```

## 컴포넌트 소유 경계

| 파일 | 소유 파트 | 비고 |
|---|---|---|
| `Main.java` | 공통 | 3개 Worker 스레드풀 기동 |
| `config/AppConfig.java` | 공통 | `TrackerSettings` 포함 |
| `model/*.java` | 공통 | 전 컴포넌트 공유 |
| `repository/PendingJobRepository.java` | 공통 | `markCompleted` / `markFailed` / `touchCheckedAt` 는 tracker 계약 |
| `hdfs/HdfsApiCaller.java` | batch-scheduler | 정책 변경·SPS 호출 |
| `hdfs/FsImageFetcher.java` | scoring | FSImage 수집·파싱 |
| `hdfs/HdfsPolicyChecker.java` | **completion-tracker** | 블록 타입 검증 |
| `scoring/ScoringEngine.java` | scoring | — |
| `scoring/PriorityRule.java` | scoring | — |
| `scheduler/BatchScheduler.java` | batch-scheduler | — |
| `tracking/CompletionTracker.java` | **completion-tracker** | — |

## 상태 전이 흐름
FSImage 수집

│

▼

ScoringEngine ──────────────────────────► pending_jobs (PENDING)

│

▼

BatchScheduler + HdfsApiCaller

│

setStoragePolicy + satisfyStoragePolicy

│

▼

pending_jobs (DISPATCHED)

│

▼

CompletionTracker + HdfsPolicyChecker

│

├────────────────────────────────────┐

▼                                    ▼
COMPLETED                          FAILED
(블록 이동 95% 완료)              (타임아웃 초과)

## 인터페이스 계약

본 컴포넌트가 읽고 쓰는 DB 테이블의 계약은
[`docs/interfaces/pending-jobs-schema.md`](../../docs/interfaces/pending-jobs-schema.md) 참고.

| 컬럼 | 읽기 | 쓰기 |
|---|---|---|
| `status` | `DISPATCHED` 만 조회 | `COMPLETED` / `FAILED` 로 전이 |
| `dispatched_at` | 타임아웃 기준 | — |
| `last_checked_at` | 폴링 우선순위 정렬 | 매 사이클 갱신 |
| `completed_at` | — | `COMPLETED` 전이 시 기록 |

## 테스트

```bash
mvn test                                          # 전체 (Docker 필요)
mvn -Dtest=HdfsPolicyCheckerTest test             # 단위 테스트만
mvn -Dtest=CompletionTrackerTest test             # 단위 테스트만
```

| 테스트 클래스 | 의존성 | 검증 내용 |
|---|---|---|
| `HdfsPolicyCheckerTest` | Mockito | COLD/HOT/WARM 정책별 블록 타입 판정, 95% 경계값, IOException 처리 |
| `CompletionTrackerTest` | Mockito | 빈 배치 no-op, 전이 로직, 타임아웃, 배치 내 독립성, HDFS 오류 복원 |

## 인수인계

### 설계 결정

| 결정 | 이유 |
|---|---|
| **Java HdfsAdmin SDK** | batch-scheduler 와 동일한 Hadoop 클라이언트 jar 재사용. subprocess `hdfs fsck` 대비 NameNode read lock 점유 시간이 짧고 예외 처리가 명확. |
| **`getBlockLocations`** | `hdfs fsck` 는 파일 트리 전체를 순회하며 read lock 을 오래 잡음. `DFSClient.getBlockLocations` 는 해당 파일만 조회 → NN 부하 최소화. |
| **claimBatch 후 즉시 커밋** | SELECT FOR UPDATE SKIP LOCKED 로 락을 잡은 뒤 즉시 커밋해 HDFS 호출(느림) 동안 PG 행 락이 유지되지 않도록 함. 다른 Tracker 인스턴스가 굶주리는 문제 방지. |
| **Semaphore(3)** | NN RPC 동시 호출 수를 단일 knob 으로 제한. `namenode-semaphore` 설정값으로 조정 가능. |
| **타임아웃 기준: `dispatched_at`** | batch-scheduler 가 DISPATCHED 로 마킹하는 시점이 "시도 시점". 이 이후 60분이 지나도 완료되지 않으면 FAILED. |
| **WARM 정책: SSD 1개 이상** | HDFS One_SSD 정책은 복제본 중 1개만 SSD 에 저장. 전체 비율 대신 존재 여부로 판정. |

### 다음 사람을 위한 dev loop

```bash
# 1) 인프라 기동 (batch-scheduler 와 동일한 docker-compose 사용)
cd ../../docker/hadoop-cluster
docker compose up -d
bash scripts/smoke-test.sh

# 2) 빌드 + 테스트
cd ../../services/completion-tracker
mvn -q -DskipTests package
mvn test

# 3) batch-scheduler 와 함께 기동
#    터미널 1: batch-scheduler
java -jar ../batch-scheduler/target/batch-scheduler-0.1.0-SNAPSHOT.jar

#    터미널 2: completion-tracker
java -jar target/completion-tracker-0.1.0-SNAPSHOT.jar

# 4) PENDING job 수동 주입 → DISPATCHED(scheduler) → COMPLETED(tracker) 확인
docker compose exec postgres psql -U dsc -d dsc_tiering -c \
  "INSERT INTO pending_jobs
       (file_path, file_size_bytes, current_tier, target_tier, priority_score, scored_at)
   VALUES ('/tiering-test/sample.bin', 134217728, 'HOT', 'COLD', 99.0, NOW());"

# 5) 상태 변화 모니터링
watch -n 10 "docker compose exec postgres psql -U dsc -d dsc_tiering -c \
  \"SELECT id, file_path, status, dispatched_at, completed_at
      FROM pending_jobs ORDER BY id DESC LIMIT 10;\""
```

### 알려진 함정

- **NN 부팅 race**: `HdfsPolicyChecker` 생성자가 즉시 NN RPC 를 시도함. NN 이 죽어있으면 Main 부팅 자체가 실패 → batch-scheduler 와 동일하게 lazy init / retry-with-backoff 개선 필요.
- **1 MiB 이하 파일**: External SPS 가 블록을 옮기지 못할 수 있음. `isSatisfied` 가 계속 false 를 반환 → `timeout-minutes` 후 FAILED 처리됨. 운영에서는 scoring engine 단에서 최소 파일 크기 필터 권장.
- **Standby NameNode**: 현재 `fs-default-name` 이 가리키는 NN (Active) 에 직접 RPC. 운영에서는 Standby 로 보내 Active NN 부하를 줄여야 함.
- **Testcontainers Docker 필수**: `PendingJobRepositoryTest` 는 Docker-in-Docker 환경 없으면 실패 → `-Dtest='!PendingJobRepositoryTest'` 로 분리.

### 미해결 / 후속 작업

- [ ] **lazy HdfsPolicyChecker 초기화**: NN 죽었을 때도 Tracker 자체는 살아있어야 함
- [ ] **JMX/Prometheus 메트릭**: 사이클별 완료 수, 타임아웃 수, 평균 검사 시간
- [ ] **YAML Hot-reload**: SIGHUP 또는 파일 watch (제안서 §3.3 가중치 무중단 변경)
- [ ] **YARN Service Framework Yarnfile**: HA 배포 (제안서 §4.4)
- [ ] **Kerberos 인증**: production HDFS secured 환경 대응
- [ ] **`recordHdfsFailure` 부분 성공 케이스 식별**: batch-scheduler 알려진 함정 — `setStoragePolicy` 는 됐지만 `satisfyStoragePolicy` 가 실패한 경우 tracker 가 식별해 감사 로그 보강

### 팀 협의 필요 사항

batch-scheduler README §팀 협의 필요 사항 의 미해결 항목 중 tracker 에 직접 영향:

1. `timeout-minutes` 기본값 (T_dispatch) — 현재 60분, 실제 클러스터 이동 속도에 맞게 조정 필요
2. tier 별 통계 집계 view — `COMPLETED` 건수·소요시간 집계를 tracker 가 기록할지 별도 view 로 뺄지
3. LF / CRLF 결정 문제
