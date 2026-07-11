# fixtures/

손계산 가능한 골든 픽스처 모음. 각 `mini-case-*/` 디렉터리는 `observation.json`
(ObservationSnapshot)과, 정책별 기대 출력 `expected-<policy-id>.json`
(PlacementDecision)으로 구성된다.

## mini-case-01

파일 3개, `observation_mode: "fsimage"` (access_stats는 전부 null, events는 빈 배열).
`observed_at = 2026-07-01T00:00:00Z`, `params.access_horizon_days = 30`.

`expected-B1.json`은 `RuleWeightedPolicy`(B1 어댑터, `edu.dsc.tiering.spi.policy`)의
기대 출력이다. B1은 기존 `PriorityRule`의 점수식·티어 결정 로직을 그대로 재구현하되,
`ACCESS_HORIZON_DAYS`만 `params.access_horizon_days`로 덮어쓸 수 있다(구현 순서 3번
지시사항). 이 픽스처는 그 덮어쓰기 경로(30일)를 실제로 행사하도록 설계했다 — 기본값
90일을 썼다면 override 배선이 잘못돼도 우연히 같은 결과가 나올 수 있어 검증력이 약하다.

### 2번 세션에서의 두 가지 가정을 3번 구현 시 다음과 같이 확정했다 (당시 README의 "확인 필요" 메모에 대한 답)

1. **daysSinceAccess의 기준 시각은 `observed_at`이지 `decided_at`이 아니다.**
   fsimage 관측이 실제로 파일을 본 시점이 `observed_at`이므로, "그 시점 기준으로 며칠
   지났는가"가 스코어링의 의미와 맞는다. `decided_at`은 정책 호출 시각일 뿐 파일 상태의
   근거가 아니다. 이 결정에 맞춰 `atime`을 전부 `observed_at`과 동일한 `T00:00:00Z`
   시각으로 맞춰, `Duration.toDays()`의 절삭(truncation)으로 인한 반나절짜리 오차 없이
   정수 일수가 나오도록 했다.
2. **`current_placement: "HOT"`은 논리 `Tier.HOT`이 아니다.** CLAUDE.md의 "⚠ 용어
   충돌 주의" 그대로다: SPI의 `Placement.HOT`은 HDFS 네이티브 "Hot" 정책(정책 코드 7,
   DISK만)이고, 기존 `ScoringEngine.POLICY_TO_TIER`는 이 코드를 **`Tier.WARM`**으로
   보수적으로 매핑한다(`ScoringEngine.java` 54~61행 참고 — 코드 7 주석: "HDFS Hot(default
   DISK) -> service WARM"). 논리 `Tier.HOT`에 대응하는 Placement는 `ALL_SSD`뿐이다
   (정책 코드 12). 원래 픽스처는 세 파일 모두 `current_placement: "HOT"`로 고정했는데,
   이 매핑을 그대로 적용하면 세 파일 모두 시작 티어가 `Tier.WARM`이 되어
   `moves-to-warm.parquet`가 목표를 만들지 못한다(HOT→WARM 전이는 `currentTier==HOT`일
   때만 발동하는데 시작이 이미 WARM이라 무이동). 파일명이 의도한 "HOT에서 출발"을
   실제로 만들려면 `current_placement: "ALL_SSD"`여야 한다. 세 파일 모두 이렇게 수정했다.

### B1의 Placement ↔ 논리 Tier 매핑 (RuleWeightedPolicy 내부 전용, SPI 밖으로 노출 안 함)

입력(`current_placement` → 내부 Tier)은 기존 `ScoringEngine.POLICY_TO_TIER`를 Placement
기준으로 그대로 옮긴 것이다:

| Placement | 내부 Tier | 근거 (ScoringEngine.POLICY_TO_TIER 정책 코드) |
|---|---|---|
| ALL_SSD | HOT | 12 → HOT |
| ONE_SSD | WARM | 10 → WARM |
| HOT (네이티브) | WARM | 7 → WARM (용어 충돌 지점) |
| WARM (네이티브) | WARM | 5 → WARM |
| COLD | COLD | 2 → COLD |

출력(목표 Tier → `target_placement`)은 CLAUDE.md "정책 라인업" 표 그대로:
HOT→ALL_SSD, WARM→ONE_SSD, COLD→COLD.

### 손계산

```
daysSinceAccess = floor((observed_at - atime) in days)     — Duration.toDays()와 동일 절삭
accessRecency   = clamp(daysSinceAccess / access_horizon_days, 0, 1)
sizeScore       = clamp(size_bytes / 10GiB, 0, 1)           — 10GiB = 10737418240 bytes
score           = 0.5 × accessRecency + 0.5 × sizeScore      — 가중치는 PriorityRule 기본값 0.5/0.5 고정(override 없음)
```

| 파일 | size_bytes | current_placement (→내부 Tier) | atime | daysSinceAccess | accessRecency (÷30) | sizeScore | score | targetTier | target_placement |
|---|---|---|---|---|---|---|---|---|---|
| `stays-hot.parquet` | 1073741824 (1 GiB) | ALL_SSD (HOT) | 2026-06-22T00:00Z | 9 | 9/30 = 0.3 | 0.1 | **0.2** | null (9 < 30) | — (decisions에서 제외) |
| `moves-to-warm.parquet` | 5368709120 (5 GiB) | ALL_SSD (HOT) | 2026-05-17T00:00Z | 45 | clamp(45/30) = 1.0 | 0.5 | **0.75** | WARM (30 ≤ 45 < 90, currentTier=HOT) | ONE_SSD |
| `moves-to-cold.parquet` | 10737418240 (10 GiB) | ALL_SSD (HOT) | 2026-03-03T00:00Z | 120 | clamp(120/30) = 1.0 | 1.0 (클램프 상한) | **1.0** | COLD (120 ≥ 90, currentTier≠COLD) | COLD |

`reason` 필드는 `String.format(Locale.ROOT, "score=%.2f", score)`이므로 각각
"score=0.20"(제외됨, 참고용), "score=0.75", "score=1.00"이다.

- `mtime`은 세 파일 모두 `2026-01-01T00:00:00Z`로 동일 — `PriorityRule`이 mtime을
  쓰지 않으므로 임의값이며, 스키마상 필수 필드라 채워 넣었을 뿐이다.
- `HOT_TO_WARM_DAYS`(30)·`WARM_TO_COLD_DAYS`(90) 임계값과 가중치(0.5/0.5)는
  override 대상이 아니다 — CLAUDE.md가 override 예시로 든 것은 `access_horizon_days`뿐이고,
  나머지를 바꾸면 "B1 스코어링 로직 변경"에 해당해 금지 사항을 건드리게 된다.
