# fixtures/

손계산 가능한 골든 픽스처 모음. 각 `mini-case-*/` 디렉터리는 `observation.json`
(ObservationSnapshot)과, 정책별 기대 출력 `expected-<policy-id>.json`
(PlacementDecision)으로 구성된다.

CLAUDE.md 구현 순서 2번(현재) 기준으로는 `observation.json`만 채워져 있다.
`expected-*.json`은 순서 3번(B1 어댑터 구현)에서 채운다 — 지금 시점에는 B1이
아직 없어 기대값을 계산할 기준이 없기 때문이다.

## mini-case-01

파일 3개, `observation_mode: "fsimage"` (access_stats는 전부 null, events는 빈 배열).
`decided_at = 2026-07-01T06:00:00Z`.

기존 `PriorityRule`의 점수식을 손계산하기 쉬운 값으로 골랐다 (가중치는 기본값
0.5/0.5 가정):

```
score = 0.5 × clamp(daysSinceAccess / 90) + 0.5 × clamp(size_bytes / 10GiB)
```

| 파일 | size_bytes | daysSinceAccess (atime 기준) | sizeScore | accessRecency | score | 의도 |
|---|---|---|---|---|---|---|
| `stays-hot.parquet` | 1073741824 (1 GiB) | 9 | 0.1 | 9/90 = 0.1 | **0.1** | 30일 미만 접근 → HOT 유지 구간 (targetTier=null) 검증용, 최소 점수 근처 |
| `moves-to-warm.parquet` | 5368709120 (5 GiB) | 45 | 0.5 | 45/90 = 0.5 | **0.5** | 30~90일 구간 → HOT에 있으면 WARM 대상. 중간 점수 검증용 |
| `moves-to-cold.parquet` | 10737418240 (10 GiB, sizeScore 상한) | 120 | 1.0 (clamp 상한) | clamp(120/90)=1.0 (clamp 상한) | **1.0** | 90일 초과 → COLD 대상. 두 구성요소 모두 클램프 상한에 걸리는 경우 검증용 |

- `current_placement`는 세 파일 모두 `HOT`으로 고정했다 — 갓 생성되어 아직 아무
  이관도 없었던 파일(HDFS 기본 정책)을 가정한 것으로, 결과가 순수하게
  `daysSinceAccess`/`size_bytes`에서만 갈리도록 하기 위함이다.
- `mtime`은 세 파일 모두 `2026-01-01T00:00:00Z`로 동일 — `PriorityRule`이 mtime을
  쓰지 않으므로 임의값이며, 스키마상 필수 필드라 채워 넣었을 뿐이다.
- **가정 (3번 구현 시 확인 필요)**: `PriorityRule.daysSince()`는 생성자에 주입된
  `now` 기준이다. 이 픽스처의 `daysSinceAccess` 계산은 `now = decided_at`이라고
  가정했다. B1 어댑터가 `observed_at`을 기준으로 삼기로 하면 위 표의 일수·점수를
  다시 계산해야 한다.
