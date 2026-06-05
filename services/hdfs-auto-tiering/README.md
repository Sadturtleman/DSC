# hdfs-auto-tiering

HDFS Auto-Tiering 의 **scoring engine + batch scheduler + completion tracker** 컴포넌트.

PostgreSQL `pending_jobs` 테이블에서 우선순위 상위 PENDING job 들을 원자적으로
점유한 뒤, HdfsAdmin SDK 로 `setStoragePolicy + satisfyStoragePolicy` 를 호출해
External SPS 데몬에 블록 이동을 위임한다.

## 빌드 / 실행

```bash
# 빌드 (fat jar)
mvn -q -DskipTests package

# 실행 (classpath application.yaml 사용)
java -jar target/hdfs-auto-tiering.jar

# 외부 yaml 경로 지정
java -jar target/hdfs-auto-tiering.jar /etc/dsc/scheduler.yaml
```

## 의존 환경

| 의존 | 비고 |
|---|---|
| PostgreSQL 14+ | `db/migrations/V001__pending_jobs.sql` 가 적용된 상태 |
| Hadoop 3.3.x | External SPS 활성화: `dfs.storage.policy.satisfier.mode=external` |
| Java 11 | 빌드 & 런타임 |

## 구조

```
edu.dsc.tiering
├── Main                       엔트리포인트 (Hikari + HdfsAdmin + BatchScheduler 와이어링)
├── config/
│   ├── AppConfig              YAML → record
│   └── ConfigLoader           Jackson YAML 로더 (kebab-case)
├── model/
│   ├── Tier                   HOT / WARM / COLD
│   ├── JobStatus              PENDING / DISPATCHED / IN_PROGRESS / COMPLETED / FAILED / CANCELLED
│   └── PendingJob             테이블 한 행
├── repository/
│   └── PendingJobRepository   claimBatch / claimTrackableBatch (SKIP LOCKED)
├── scoring/
│   ├── ScoringEngine          target-directories 하위 FSImage 분석 → pending_jobs(PENDING)
│   └── PriorityRule           접근 시간·파일 크기 기반 우선순위
├── scheduler/
│   ├── WindowSelector         시간대 → batch-size/wait 매핑
│   └── BatchScheduler         메인 루프 + 워커 풀
├── tracking/
│   └── CompletionTracker      IN_PROGRESS / COMPLETED / FAILED 검증 루프
└── hdfs/
    └── HdfsApiCaller          HdfsAdmin.setStoragePolicy / satisfyStoragePolicy
```

## 인터페이스 계약

본 컴포넌트가 읽고 쓰는 DB 테이블의 계약은
[`docs/interfaces/pending-jobs-schema.md`](../../docs/interfaces/pending-jobs-schema.md)
참고. **scoring engine 과 completion tracker 담당자는 본 문서를 보고 자기 컴포넌트를
이 계약에 맞춰 구현하면 된다.**

`scoring.target-directories`는 스코어링 권한 범위를 제한하는 화이트리스트다. 값이 없으면 `ScoringEngine`은 FSImage 수집 자체를 건너뛰고 아무 job도 만들지 않는다. INFRA.md의 검증 설정에는 테스트 데이터 경로(`/test/auto-tiering-e2e`, `/test/scenario_e2e`)를 반드시 포함한다.

## 테스트

```bash
mvn test                       # 전체 테스트 (PostgreSQLContainer 부팅 포함, Docker 필요)
mvn -Dtest=WindowSelectorTest test
mvn -Dtest='!PendingJobRepositoryTest' test    # Docker 없을 때 — 단위 테스트만
```

| 테스트 클래스 | 의존성 | 검증 내용 |
|---|---|---|
| `ConfigLoaderTest` | 없음 | YAML → record 매핑 (kebab-case, enum-key map) |
| `WindowSelectorTest` | 없음 | 일반/자정 가로지르기/24h 윈도우 경계 처리 |
| `BatchSchedulerTest` | Mockito | 빈 배치 no-op, 전 성공/부분 실패 시 `recordHdfsFailure` 호출 여부 |
| `PendingJobRepositoryTest` | **Docker** (Testcontainers + postgres:16-alpine) | `claimBatch` / `claimTrackableBatch` 우선순위 정렬·상태 전이, **SKIP LOCKED 동시성**, 재시도 한도 분기 |

`PendingJobRepositoryTest` 는 실제 PG 컨테이너를 띄워 `db/migrations/V001__pending_jobs.sql`
및 `V002__add_retry_columns.sql`을 적용하므로, **DDL 이 깨지면 즉시 fail** 한다.
스코어링 / tracker 담당자가 컬럼/enum을 추가하려면 마이그레이션 스크립트를 추가 → 이 테스트가 통과해야 정상.

## 인수인계

본 섹션은 본 컴포넌트를 처음 받는 사람이 30분 안에 빌드·테스트·실행까지
도달할 수 있도록 한다.

### 설계 결정 (왜 그렇게 짰는가)

| 결정 | 이유 |
|---|---|
| **Java 11 + HdfsAdmin SDK** | `satisfyStoragePolicy` 가 Java native API. CLI/WebHDFS subprocess 호출보다 에러 핸들링이 깔끔하고, Hadoop 클라이언트 jar 가 Kerberos/HA 까지 한 번에 지원. |
| **PostgreSQL `FOR UPDATE SKIP LOCKED`** | 단일 큐 테이블에서 다중 scheduler 인스턴스 / YARN container 가 안전하게 병렬 픽업하는 표준 패턴. 별도 Redis/Kafka 도입 회피. |
| **PENDING → DISPATCHED 를 HDFS 호출 *직전* 에 update** | 호출 성공 시점이 아니라 *시도 시점* 에 마킹 → scheduler 가 죽어도 tracker 가 타임아웃으로 FAILED 처리해 행이 멈춰 보이지 않게 한다. 호출 후 마킹하면 "호출은 됐지만 DB 미반영" 윈도우에서 row 가 영영 안 보임. |
| **시간대별 가변 windows (YAML)** | 제안서 §3.3 가변 정책 요구. List 구조라 daytime/nighttime 2개 외에도 점심시간/주말 등 자유 추가 가능. |
| **`concurrency` = 단일 batch 내 병렬 HDFS 호출 수** | NN RPC burst 와 dispatch 속도의 trade-off 를 단일 knob 으로 제어. 배치 사이에는 `inter-batch-wait-ms` 로 폭주 방지. |
| **HikariCP** | PG 커넥션 풀 사실상 표준. 별 이유 없음. |
| **Jackson YAML + record** | record 의 final field 가 config immutability 강제. `ParameterNamesModule` + `-parameters` 플래그로 생성자 파라미터명 보존 → kebab-case 매핑 자연스러움. |

### 다음 사람을 위한 dev loop

```bash
# 1) 인프라 (Hadoop + External SPS + Postgres) 한 번에 띄우기
cd ../../docker/hadoop-cluster
docker compose up -d
bash scripts/smoke-test.sh         # SPS 가 정말로 ARCHIVE 로 옮기는지 확인

# 2) 본 컴포넌트 빌드 + 테스트
cd ../../services/hdfs-auto-tiering
mvn -q -DskipTests package
mvn test

# 3) 실제 HDFS 파일을 만들고 FSImage 저장
dd if=/dev/zero of=/tmp/tiering-sample.bin bs=1M count=128
hdfs dfs -mkdir -p /test/auto-tiering-e2e
hdfs storagepolicies -setStoragePolicy -path /test/auto-tiering-e2e -policy ALL_SSD
hdfs dfs -put -f /tmp/tiering-sample.bin /test/auto-tiering-e2e/sample.bin
hdfs storagepolicies -setStoragePolicy -path /test/auto-tiering-e2e/sample.bin -policy ALL_SSD
hdfs storagepolicies -satisfyStoragePolicy -path /test/auto-tiering-e2e/sample.bin
hdfs dfs -touch -a -t "$(date -d '120 days ago' +%Y%m%d:%H%M%S)" /test/auto-tiering-e2e/sample.bin
hdfs dfsadmin -safemode enter
hdfs dfsadmin -saveNamespace
hdfs dfsadmin -safemode leave

# 4) 통합 데몬 기동: 전체 YAML에 scoring.target-directories 포함
java -jar target/hdfs-auto-tiering.jar /etc/dsc/hdfs-auto-tiering-dev.yaml &
DAEMON_PID=$!

# 5) 상태 확인
psql -h localhost -U dsc -d dsc_tiering -c \
    "SELECT job_id, file_path, status, current_tier, target_tier
       FROM pending_jobs
      WHERE file_path = '/test/auto-tiering-e2e/sample.bin'
      ORDER BY job_id DESC LIMIT 1;"
kill "$DAEMON_PID"
```

호스트에서 jar 를 직접 실행할 경우 `application.yaml` 의
`fs-default-name` 을 `hdfs://localhost:8020` 으로 변경 (compose 가 포트
포워딩 함).

### 알려진 함정

- **Windows 경로**: Maven shaded jar 는 OK, 다만 docker compose 의 bind mount
  (`./conf/...`) 가 Windows 줄바꿈으로 깨질 수 있음 — `.gitattributes` 로 LF 강제
  를 검토.
- **Testcontainers 가 Docker 필수**: CI 에서 Docker-in-Docker 가 안 되면
  `PendingJobRepositoryTest` 만 별도 분리 (`-Dtest='!*RepositoryTest'`).
- **`HdfsApiCaller` 생성자 시점에 NN 접속**: `FileSystem.get` 이 즉시 RPC 를
  쏘므로, NN 이 죽어 있으면 Main 부팅 자체가 실패한다. 운영에서는 lazy init /
  retry-on-startup 으로 바꿔야 함.
- **`recordHdfsFailure` 의 race**: HDFS 호출이 *부분* 성공 (setStoragePolicy 는
  됐지만 satisfyStoragePolicy 가 실패) 한 경우, 정책은 바뀐 채로 PENDING 으로
  되돌아간다. 다음 시도가 idempotent 하므로 기능적 문제는 없지만 감사
  관점에서는 의도 외 상태. tracker 가 이 케이스를 식별하면 좋음.
- **`dispatched_at` 을 SKIP LOCKED 와 같은 UPDATE 에서 채움**: 락 점유 시간이
  HDFS 호출 시간과 분리됨 (claim 후 락 해제 → HDFS 호출). DB 락이 길게 잡혀서
  다른 워커가 굶주리는 일은 없음.

### 미해결 / 후속 작업 (우선순위 순)

- [ ] **External SPS 가 정말 ARCHIVE 로 옮기는지 e2e 검증** — 1MB 보다 작은
      smoke-test 파일은 디폴트 블록 단위라 못 옮길 수 있음, 128MB+ 로 재현 필요
- [ ] **JMX/Prometheus 메트릭 노출**: 윈도우별 dispatch 수, 평균 latency,
      재시도/실패 카운터 (제안서 §3.4 "운영 모니터링")
- [ ] **dead-letter 처리**: `FAILED` 상태로 끝난 job 의 후처리 (운영자 알림,
      재시도 리셋 API)
- [ ] **운영 중 YAML 재로딩**: SIGHUP 또는 파일 watch (제안서 §3.3 가중치 무중단
      변경 요구)
- [ ] **YARN Service Framework Yarnfile**: HA 배포 (제안서 §4.4)
- [ ] **Kerberos 인증 옵션**: production HDFS 는 보통 secured
- [ ] **DB / NN 부팅 race**: Main 이 시작될 때 둘 다 준비 안 됐을 수 있음 →
      retry-with-backoff
- [ ] **lazy HdfsAdmin 초기화**: NN 죽었을 때도 scheduler 자체는 살아있어야 함

### 팀 협의 필요 사항

DDL 계약 (`docs/interfaces/pending-jobs-schema.md` §5) 의 미해결 항목을
**다음 standup 까지 확정** 해야 함:

1. `priority_score` 는 접근시간 순위와 용량 순위의 동등 가중 합이며 낮을수록 우선
2. `fsimage_snapshot_id` 를 별도 `fsimage_snapshots` 테이블의 FK 로 둘지 결정
3. `CANCELLED` 후 같은 path 재-INSERT 시점의 race (scoring 담당자와)
4. tier 별 통계 집계 view — 누가 만들지 (운영 모니터링 §3.4)
5. completion tracker 의 `timeout-minutes` 기본값 — 실제 클러스터 이동 속도에 맞게 조정
