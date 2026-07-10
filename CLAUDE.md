# CLAUDE.md — DSC 확장: HDFS 티어링 비용–성능 트레이드오프 측정 프레임워크

## 프로젝트 개요

이 레포(DSC)는 분산시스템 텀프로젝트로 완성된 **동작하는 시스템**이다:
YARN 위에서 구동되는 HDFS 오토티어링 서비스 (`hdfs-auto-tiering/`, Java 11, Maven, Hadoop 3.4.1, PostgreSQL job queue).

이번 작업은 이 시스템을 **국내 학회(ACK/KSC 2026) 논문용 측정 프레임워크로 확장**하는 것이다.
목표: 무수정 HDFS 위에서 티어링 정책별 **저장 비용 vs Spark job 완료시간 트레이드오프 곡선**을 실측한다.

### 절대 원칙

1. **기존 코드는 자산이다.** 기존 모듈의 동작을 바꾸지 않는다. 확장은 새 모듈 추가 + 최소한의 인터페이스 추출로만 한다.
2. **HDFS 코어는 절대 수정하지 않는다.** 티어 제어는 `setStoragePolicy` + SPS로만.
3. **전부 Java로 개발한다.** 시뮬레이터 포함. 신규 코드도 Java 11 문법 준수 (레코드 등 17+ 문법 금지).
4. **모든 결과 숫자는 재현 가능해야 한다.** 측정기에는 손계산 검증 테스트가 반드시 붙는다.
5. **레포는 자기완결적이어야 한다.** 최종 목표가 오픈소스 공개 + 논문 재현이므로, "낯선 사람이 클론해서 `./mvnw test` 한 줄로 빌드·테스트되는 상태"를 항상 유지한다. 공유 서버의 로컬 상태(설치된 도구, 환경변수, 특정 경로)에 의존하는 코드·문서 금지.

## 협업 및 재현성 규약

- **빌드 진입점은 `./mvnw` 하나다.** Maven Wrapper(3.9.x)를 레포에 커밋한다. 사전 조건은 JDK 11+뿐이며 README에 명시한다. 라이브러리는 전부 pom.xml로만 가져온다 (수동 설치 안내 금지).
- **진실의 기준은 공유 서버가 아니라 CI다.** 모든 push/PR에서 `./mvnw test`가 깨끗한 환경(GitHub Actions ubuntu-latest)에서 실행된다. 공유 서버에서는 되는데 CI에서 깨지면 그것은 암묵적 환경 의존이 생겼다는 뜻이며, CI를 통과시키는 방향으로 고친다.
- **실험은 단일 명령으로 재현된다.** 논문의 각 그림은 `./mvnw -pl tiering-simulator exec:java -Dexp=rq1` 형태의 한 명령으로 재생성 가능해야 하며, seed·상수·트레이스 전처리 스크립트는 전부 버전 관리한다. 실험 결과 CSV는 `paper/data/`에 커밋한다.
- **논문 투고 시점에 태그를 박는다** (예: `v0.9-ack2026`). "논문의 숫자는 이 커밋에서 나왔다"를 고정하기 위함.
- **오픈소스 준비 상태 유지**: 비밀정보(실서버 IP, 실계정)를 코드·문서에 커밋하지 않는다. 로컬 기본값(localhost, dsc/dsc)은 허용.

## 기존 코드 지도 (수정 전 반드시 해당 파일을 읽을 것)

```
hdfs-auto-tiering/src/main/java/edu/dsc/tiering/
  model/       Tier(HOT/WARM/COLD 논리 티어), PendingJob, JobStatus
  config/      AppConfig, ConfigLoader          ← application.yaml 로더
  scoring/     FileMetadata, PriorityRule, ScoringEngine   ← B1 베이스라인의 원본
  scheduler/   WindowSelector, BatchScheduler   ← 시간대별 배치 이관 (실행 계층)
  tracking/    CompletionTracker, HdfsPolicyChecker
  hdfs/        FsImageFetcher, HdfsApiCaller
  repository/  PendingJobRepository (PostgreSQL)
```

기존 동작 흐름: FsImageFetcher(배치 스냅샷) → ScoringEngine(PriorityRule로 targetTier 결정)
→ PendingJobRepository → BatchScheduler(WindowSelector) → HdfsApiCaller → CompletionTracker.

- `PriorityRule`: `score = w_a·clamp(daysSinceAccess/90) + w_s·clamp(size/10GB)`, `targetTier(meta, currentTier) → Tier | null`
- `application.yaml`의 `hdfs.policy-mapping`: HOT→ALL_SSD, WARM→ONE_SSD, COLD→COLD

### ⚠ 용어 충돌 주의

논리 티어 `Tier.WARM`은 HDFS 정책 `ONE_SSD`로 매핑되어 있고, HDFS에는 별도의 내장 정책 `WARM`(DISK+ARCHIVE)도 존재한다.
**혼동 방지를 위해 신규 SPI에서는 논리 티어명을 쓰지 않고 HDFS 정책명 기준의 Placement 5종만 사용한다.**
기존 `Tier` enum은 B1 어댑터 내부와 기존 모듈에서만 사용한다.

## 연구 질문 (우선순위 순)

| ID | 질문 | 상태 |
|----|------|------|
| RQ1 | 파일 단위 이진 티어링의 비용–job시간 곡선 (베이스라인 3종 × 공격성 sweep) | **최우선. 이것만으로 논문 성립** |
| RQ2 | 복제본 단위 부분 티어링(P1)은 같은 비용에서 지연을 낮추는가 | RQ1 이후 |
| RQ3 | 힌트 기반 선제 승급(P2)의 이득 경계(리드타임·재접근)와 관측 신선도(FSImage 주기)의 영향 | RQ1 이후 |

**일정이 밀리면 P2 → 관측 신선도 → P1 순으로 잘라낸다. RQ1 곡선은 무조건 완성한다.**

## 모듈 구성 (Maven 멀티모듈 전환)

루트에 parent pom을 두고 기존 모듈을 하위로 편입한다. 기존 `hdfs-auto-tiering/pom.xml`의
의존성·플러그인 설정은 변경 최소화(parent 상속 선언 추가 정도).

```
DSC/
  pom.xml                  # (신규) parent: 모듈 목록, 공통 버전 관리
  hdfs-auto-tiering/       # (기존) 실측 서비스. 변경은 Policy SPI 어댑터 추가만
  tiering-policy-spi/      # (신규) SPI 인터페이스 + DTO + JSON 직렬화(Jackson) + 정책 구현 + 골든 픽스처
  tiering-simulator/       # (신규) 이산 사건 시뮬레이터 + 측정기 + 실험 러너
  bench/                   # (신규, 코드 아님) 미니 클러스터 마이크로벤치마크 셸 스크립트, HiBench 시나리오
  paper/                   # (신규) 논문 그림 생성용 데이터(CSV)와 스크립트
```

의존 방향: `hdfs-auto-tiering → tiering-policy-spi ← tiering-simulator`. 역방향 의존 금지.
시뮬레이터는 Hadoop/PostgreSQL에 의존하지 않는다 (순수 Java + Jackson만).

## Policy SPI (v1.0) — 모든 작업의 접점 계약

정책은 **상태 없는 순수 함수**다. 접근 통계 등 이력 가공은 관측 어댑터(호출자)의 책임.
출력은 명령이 아닌 **선언(목표 배치)**. 이관 순서·rate limit·차이 계산은 실행 계층 몫.

### Java 인터페이스 (tiering-policy-spi)

```java
package edu.dsc.tiering.spi;

public interface TieringPolicy {
    String policyId();
    PlacementDecision decide(ObservationSnapshot snapshot);
}

/** HDFS storage policy로 표현 가능한 5종 정규형만 허용 */
public enum Placement {
    ALL_SSD,   // [SSD, SSD, SSD]
    ONE_SSD,   // [SSD, DISK, DISK]      ← P1 부분 티어링의 핵심
    HOT,       // [DISK, DISK, DISK]     (HDFS 기본)
    WARM,      // [DISK, ARCHIVE, ARCHIVE]
    COLD       // [ARCHIVE, ARCHIVE, ARCHIVE]
}
```

DTO는 Jackson 직렬화 가능한 불변 클래스(Java 11이므로 record 대신 final 필드 + 생성자).
JSON 표현은 아래와 1:1 대응하며, 골든 픽스처와 언어 간 검증의 기준은 JSON이다.

### 입력: ObservationSnapshot (JSON)

```json
{
  "schema_version": "1.0",
  "observed_at": "2026-07-01T00:00:00Z",
  "decided_at":  "2026-07-01T06:00:00Z",
  "observation_mode": "fsimage",
  "cluster": {
    "tiers": [
      {"name": "SSD",     "capacity_bytes": 0, "used_bytes": 0},
      {"name": "DISK",    "capacity_bytes": 0, "used_bytes": 0},
      {"name": "ARCHIVE", "capacity_bytes": 0, "used_bytes": 0}
    ]
  },
  "files": [
    {
      "path": "/data/.../part-0001.parquet",
      "size_bytes": 0,
      "current_placement": "ONE_SSD",
      "atime": "2026-06-28T14:00:00Z",
      "mtime": "2026-06-01T02:00:00Z",
      "access_stats": {"reads_1h": 0, "reads_24h": 0, "reads_7d": 0}
    }
  ],
  "events": [
    {"type": "read", "path": "...", "at": "..."},
    {"type": "job_submitted", "job_id": "...", "input_paths": ["..."], "at": "..."}
  ],
  "params": {"aggressiveness": 0.7, "access_horizon_days": 30}
}
```

규약:
- `observation_mode`: `"fsimage"`(배치 스냅샷) 또는 `"audit_hybrid"`(준실시간).
- `fsimage` 모드에서는 `access_stats: null`, `events: []`. `atime`은 스냅샷 시점 값(stale 가능).
- `decided_at − observed_at` = 관측 지연(staleness). RQ3의 실험 변수이므로 정확히 기록.
- `events`는 직전 decide 호출 이후 발생분만.
- `params`는 실험 러너가 주입. 정책은 여기 없는 설정값을 하드코딩하지 않는다.

### 출력: PlacementDecision (JSON)

```json
{
  "schema_version": "1.0",
  "policy_id": "P1-replica-partial",
  "decisions": [
    {
      "path": "...",
      "target_placement": "WARM",
      "priority": 0.83,
      "reason": "score=0.83, demote 2 replicas"
    }
  ]
}
```

- 응답에 없는 파일 = 현 상태 유지.
- `priority`(0–1): 실행 계층이 rate limit 하에서 이관 순서 결정에 사용.
- `reason`: 형식 자유, 논문 사례 분석용.
- Placement 5종 외 값은 역직렬화 시 즉시 예외.

## 정책 라인업 (tiering-policy-spi 내 구현)

| ID | 이름 | 설명 |
|----|------|------|
| B0 | call-count | 접근 횟수 임계치 (업계 관행 베이스라인, audit_hybrid 전용) |
| B1 | rule-weighted | **기존 PriorityRule을 감싸는 어댑터. 스코어링 로직 변경 절대 금지.** Tier→Placement 매핑: HOT→ALL_SSD, WARM→ONE_SSD, COLD→COLD (기존 policy-mapping과 동일) |
| B2 | prob-split | 스코어 비례 확률 분산 배치 (seed 고정 필수) |
| P1 | replica-partial | 스코어 구간별 승급 수준 선택: ALL_SSD/ONE_SSD/HOT/WARM/COLD 5단계 + 히스테리시스 (제안) |
| P2 | workload-hint | `events`의 job_submitted/read 힌트로 선제 승급, N분 후 강등 (제안) |

## 시뮬레이터 (tiering-simulator) — 논문 숫자의 원천

이산 사건 시뮬레이션: `PriorityQueue` 기반 이벤트 큐 + 가상 시계(long epochMillis). 실시간 대기 없음.

구성:
- `trace/`   : 트레이스 파서(CMU OpenCloud → 접근 이벤트) + **합성 미니 트레이스 생성기**(개발·테스트용)
- `core/`    : 재생 드라이버, 클러스터 상태 모델(파일→Placement, 티어 용량), 이관 모델
- `observe/` : 관측 어댑터 — 상태 모델로부터 ObservationSnapshot 조립. fsimage 뷰(주기 파라미터) / audit_hybrid 뷰
- `meters/`  : 비용 측정기, 성능 측정기
- `runner/`  : 실험 러너(정책 × params sweep), 결과 CSV 출력

측정기 정의:
- **비용** = Σ_티어 (점유 GB × 경과시간 × 단가[$/GB·h]) + 이관 바이트 × 이관 단가. **시간 적분이며 스냅샷 아님.**
- **job 읽기시간** = Σ_입력파일 (size ÷ throughput[그 파일의 현재 최고 속도 티어]). ONE_SSD처럼 복제본이 여러 티어에 걸치면 가장 빠른 티어에서 읽는 것으로 모델링. 읽기 시점에 해당 파일 이관 진행 중이면 간섭 페널티 적용.
- **이관 모델**: 소요시간 = size ÷ migration_bandwidth, 동시 이관 총량은 rate_limit으로 제한. **이관 완료 전까지 파일은 원래 Placement에 있다** (리드타임 실험의 전제).

상수는 `tiering-simulator/src/main/resources/sim-constants.yaml`에서 로드 (하드코딩 금지):

```yaml
# TODO: bench/ 마이크로벤치마크 실측치로 교체 (INFRA.md 참조). 추정치 사용 시에도 TODO 유지
throughput_bytes_per_sec: {SSD: TODO, DISK: TODO, ARCHIVE: TODO}
migration_bandwidth_bytes_per_sec: TODO
rate_limit_bytes_per_sec: TODO
price_per_gb_hour: {SSD: TODO, DISK: TODO, ARCHIVE: TODO}
migration_price_per_gb: TODO
```

결과 CSV 스키마: `policy_id, param_set, cost_total, cost_saving_pct, avg_job_slowdown_pct, p95_job_slowdown_pct, migrated_bytes` → `paper/data/`에 저장. 곡선 그림은 이 CSV에서 생성한다(그림 생성 도구는 자유, Java 빌드에 포함하지 않음).

## 테스트 규칙 (필수)

1. **골든 픽스처**: `tiering-policy-spi/src/test/resources/fixtures/`에 손계산 가능한 미니 케이스(파일 3개, 접근 5회 수준)의 ObservationSnapshot JSON과 각 정책의 기대 PlacementDecision JSON. 기대값 산출 근거(손계산 과정)를 픽스처 옆 README에 남긴다. 모든 정책은 픽스처 테스트 통과 필수.
2. **측정기 검증**: 파일 2–3개 시나리오의 비용·job시간 손계산 기대값 테스트를 구현보다 먼저 작성.
3. **B1 회귀 보장**: B1 어댑터의 출력이 기존 `PriorityRuleTest`/`ScoringEngineTest`가 검증하는 동작과 일치함을 어댑터 테스트로 증명 (기존 테스트는 수정 금지, 전부 통과 유지).
4. **결정론**: 같은 트레이스 + 같은 정책 + 같은 seed = 같은 CSV. B2 등 랜덤은 seed 주입.
5. JUnit 5 + 기존 테스트 스타일(Mockito, Testcontainers) 준수.

## 구현 순서 (의존성 순 — 이 순서 번호로 작업을 지시한다)

0. 빌드 기반: Maven Wrapper(3.9.x) 커밋, `.github/workflows/`에 push/PR용 `./mvnw test` CI 추가, 기존 `release.yml`의 낡은 경로(`services/hdfs-auto-tiering` → 실제 경로) 수정, DEPLOY.md 경로 안내 갱신, README에 사전 조건(JDK 11+)과 빌드 방법 명시
1. Maven 멀티모듈 전환 (parent pom + 빈 `tiering-policy-spi`, `tiering-simulator` 모듈, 기존 `hdfs-auto-tiering/pom.xml`에 `<parent>` 선언 추가, `./mvnw test`로 기존 테스트 전부 통과 확인)
2. `tiering-policy-spi`: Placement enum, DTO, TieringPolicy 인터페이스, Jackson 직렬화 + 스키마 검증, 골든 픽스처 뼈대
3. `tiering-policy-spi`: B1 어댑터 (기존 PriorityRule 재사용) + 회귀 테스트
4. `tiering-simulator/trace`: 합성 미니 트레이스 생성기 + 이벤트 스트림 모델 (CMU 파서는 실데이터 확보 후)
5. `tiering-simulator/core`: 재생 드라이버 + 클러스터 상태 모델
6. `tiering-simulator/meters`: 비용 측정기 (→ **1차 마일스톤: B1의 비용 축 산출**)
7. `tiering-simulator/meters+core`: 성능 측정기 + 이관 모델
8. `tiering-simulator/observe`: fsimage 뷰(주기 파라미터) → audit_hybrid 뷰
9. `tiering-policy-spi`: B0, B2, P1, P2 구현
10. `tiering-simulator/runner`: sweep + CSV 출력
11. `hdfs-auto-tiering`: ScoringEngine이 TieringPolicy를 통해 결정하도록 어댑터 주입 (기존 경로는 설정으로 유지, 기본값은 기존 동작)
12. `bench/`: 마이크로벤치마크 스크립트 (티어별 처리량, 이관 대역폭) → sim-constants.yaml 채움

## 코딩 컨벤션

- Java 11 (기존 pom의 compiler 설정 준수). 신규 모듈도 동일.
- 기존 코드 스타일(패키지 구조 `edu.dsc.tiering.*`, SLF4J 로깅, 생성자 주입)을 따른다.
- 시뮬레이터 코어는 IO 없는 순수 로직으로, 파일/설정 IO는 경계에만.
- 커밋 메시지·주석 한국어 허용, 식별자는 영어.

## 하지 말 것

- 기존 모듈의 동작·공개 시그니처 변경 (11번 어댑터 주입 제외), 기존 테스트 수정
- HDFS/Hadoop 소스 수정 또는 포크 제안
- Placement 5종 외의 복제본 배치 지원
- ML/RL 정책 구현 (이번 논문 범위 밖 — 후속 연구)
- B1(PriorityRule) 스코어링 로직 변경 (베이스라인 무결성)
- sim-constants.yaml의 TODO를 임의 추정치로 채우면서 TODO 표시를 지우는 것
- Python 등 타 언어 모듈 추가 (그림 생성 스크립트 제외)
- 서버 프로비저닝 자동화(Ansible 등)나 Hadoop 클러스터 Docker화 제안 (범위 밖, INFRA.md 문서로 충분)
- 공유 서버의 로컬 상태(설치 경로, 환경변수)에 의존하는 코드·스크립트 작성