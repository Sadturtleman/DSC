# examples/

CLAUDE.md "Policy SPI (v1.0)" 섹션의 ObservationSnapshot/PlacementDecision JSON 예시를
그대로 옮긴 라운드트립 테스트 자원이다.

유일한 변경: `events[]`의 `"..."` placeholder(문서 축약 표기)는 `Instant` 파싱이
불가능하므로 파싱 가능한 구체적인 값(경로, job_id, 타임스탬프)으로 채웠다. 그 외
필드명·구조·값은 CLAUDE.md 원문과 동일하다.

`observation-snapshot-example.json`은 `observation_mode: "fsimage"`이면서 `events`가
비어있지 않다 — CLAUDE.md 문서 예시 자체가 그렇게 되어 있다 (여러 이벤트 형태를 한
예시에서 보여주기 위함으로 보임). 이는 `SpiValidator`의 "fsimage 모드에서는 events가
비어있어야 한다" 규칙과 충돌하므로, 라운드트립 테스트는 `SpiValidator`를 거치지 않고
`SpiObjectMapper`로 직렬화/역직렬화 형태만 검증한다. 규칙 위반 자체는
`SpiValidatorTest`가 별도로 검증한다.
