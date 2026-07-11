# fixtures/mini-trace-01

`ClusterStateModelReplayTest`가 `SimulationDriver` + `ClusterStateModel`을 통해 이 트레이스를
재생한 뒤 최종 상태를 손계산 기대값과 대조하는 데 쓰는 픽스처. 파일 3개, 이벤트 6개.

## 트레이스 (trace.jsonl)

| # | at | 이벤트 |
|---|---|---|
| 1 | 0 | file_create `/data/a.parquet` size=1,000,000,000 |
| 2 | 1000 | file_create `/data/b.parquet` size=2,000,000,000 |
| 3 | 2000 | file_create `/data/c.parquet` size=500,000,000 |
| 4 | 5000 | read `/data/a.parquet` |
| 5 | 6000 | job `j1` input_paths=[`/data/b.parquet`, `/data/c.parquet`] |
| 6 | 9000 | read `/data/c.parquet` |

## 손계산 규칙

- `file_create`로 등록된 파일은 항상 `Placement.HOT`(DISK×3)에서 시작한다
  (`ClusterStateModel`의 `INITIAL_PLACEMENT` — HDFS 기본 정책과 동일 전제. 클래스 javadoc 참고).
  이 트레이스는 `requestPlacementChange`를 한 번도 호출하지 않으므로, 재생이 끝나도 세 파일
  모두 `HOT`에 머문다.
- `lastAccessAtMillis`는 그 파일을 건드린 이벤트(`file_create` 자신, `read`, 자신이
  `input_paths`에 포함된 `job`) 중 가장 늦은 `at`.
- 티어 사용량은 `PlacementFootprint.occupiedBytes(HOT, size) = {DISK: size×3}`의 합.

## 기대값

| 파일 | size_bytes | 최종 Placement | lastAccessAtMillis 근거 | lastAccessAtMillis |
|---|---|---|---|---|
| `/data/a.parquet` | 1,000,000,000 | HOT | max(생성 0, read 5000) | **5000** |
| `/data/b.parquet` | 2,000,000,000 | HOT | max(생성 1000, job(#5) 6000) | **6000** |
| `/data/c.parquet` | 500,000,000 | HOT | max(생성 2000, job(#5) 6000, read 9000) | **9000** |

티어 사용량:

```
DISK = (1,000,000,000 + 2,000,000,000 + 500,000,000) × 3
     = 3,500,000,000 × 3
     = 10,500,000,000

SSD     = 0
ARCHIVE = 0
```

`rejectionCount() == 0` (용량 변경 요청 자체가 없으므로).
