# hadoop-cluster (개발용)

DSC Auto-Tiering 의 **External SPS 가 실제로 동작하는 최소 환경**. 단일 노드
구성이지만 DataNode 에 `[SSD]/[DISK]/[ARCHIVE]` 3종 storage type 을 부여해
HOT→WARM→COLD 전환을 end-to-end 로 검증할 수 있다.

## 컴포넌트

| 컨테이너 | 역할 |
|---|---|
| `namenode` | HDFS NameNode. SPS 내장 모드 OFF (`mode=external`) |
| `datanode` | DataNode 1대. `/data/{ssd,disk,archive}` 세 볼륨 mount |
| `external-sps` | `hdfs sps` 데몬 별도 프로세스. NN 부하 격리 (제안서 §3.2) |
| `postgres` | `pending_jobs` 테이블 호스팅. 부팅 시 `db/migrations/*.sql` 자동 적용 |

## 부팅

```bash
docker compose up -d
docker compose logs -f namenode    # NN 포맷 / 부팅 확인
docker compose exec namenode hdfs dfsadmin -report
```

NameNode UI: <http://localhost:9870>
PostgreSQL: `psql -h localhost -U dsc -d dsc_tiering`

## End-to-End 검증

```bash
bash scripts/smoke-test.sh
```

스크립트가 하는 일:
1. `/tiering-test/sample.bin` 생성 (1 MiB)
2. 초기 정책 조회 (기본 HOT)
3. `setStoragePolicy COLD` + `satisfyStoragePolicy`
4. 30초 후 `hdfs fsck -files -blocks -locations` 로 블록이 ARCHIVE 볼륨에
   재배치되었는지 확인

External SPS 데몬이 정상 동작하면 `fsck` 결과의 block location 이
`/data/archive` 경로로 옮겨가야 한다. 만약 SSD/DISK 에 그대로 있다면:

- `external-sps` 컨테이너 로그 확인 (`docker compose logs external-sps`)
- NN 의 `dfs.storage.policy.satisfier.mode` 가 정말 `external` 인지 확인
  (`docker compose exec namenode hdfs getconf -confKey dfs.storage.policy.satisfier.mode`)

## hdfs-auto-tiering 와 연동

호스트에서 `services/hdfs-auto-tiering` 를 띄울 때는 `application.yaml` 의
연결 정보가 컨테이너 외부에서 접근 가능해야 한다 — 이미 다음 포트가 노출됨:

| 서비스 | 호스트 포트 |
|---|---|
| NameNode RPC | `localhost:8020` |
| PostgreSQL | `localhost:5432` |

호스트에서 실행 시 `application.yaml` 의 `fs-default-name` 을
`hdfs://localhost:8020` 으로 바꿔야 한다 (컨테이너 안에서 실행할 거면
`hdfs://namenode:8020` 그대로).

## 정리

```bash
docker compose down -v     # -v 로 볼륨까지 삭제 (NN 포맷 데이터 초기화)
```

## 한계 / TODO

- 단일 DataNode, 단일 replication — HA / 다중 노드 검증 불가
- Kerberos 비활성
- Standby NameNode 없음 → FSImage Collector 가 Active NN 에서 받게 되는데
  실제 운영에서는 Standby 에서 받아야 함 (제안서 §3.4)
- External SPS HA 미구성
