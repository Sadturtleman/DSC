# DSC Auto-Tiering 빌드 및 배포 가이드

본 문서는 `INFRA.md`에 따라 구축된 **WSL2 Ubuntu 22.04 + Hadoop 3.4.1 (Java 11)** 환경에 `hdfs-auto-tiering` 서비스를 빌드하고 배포하는 절차를 안내합니다.

---

## 1. 사전 조건

아래 항목이 모두 완료되어 있어야 합니다.

| 항목 | 확인 방법 |
|---|---|
| WSL2 Ubuntu 22.04 | `wsl -l -v` (VERSION = 2) |
| Java 11 (OpenJDK) | `java -version` (11.0.x) |
| Hadoop 3.4.1 바이너리 | `hadoop version` |
| NameNode + DataNode 3대 + External SPS 기동 | `jps` 로 데몬 확인 |
| YARN ResourceManager + NodeManager 기동 | `yarn node -list` |
| PostgreSQL 기동 + DDL 적용 | `psql -h localhost -U dsc -d dsc_tiering -c "\dt"` |
| Maven 3.6+ (빌드용) | `mvn -version` |

> **Maven 미설치 시:**
> ```bash
> sudo apt install -y maven
> ```

---

## 2. 빌드

### 2-1. Fat JAR 빌드

`maven-shade-plugin`이 `pom.xml`에 설정되어 있으므로 모든 의존성을 포함하는 단일 Fat JAR이 생성됩니다.

```bash
cd ~/DSC/services/hdfs-auto-tiering
mvn clean package -DskipTests
```

빌드 성공 시 생성 파일: `target/hdfs-auto-tiering.jar`

### 2-2. 빌드 검증

```bash
java -jar target/hdfs-auto-tiering.jar --help 2>&1 || true
ls -lh target/hdfs-auto-tiering.jar
```

---

## 3. 로컬 직접 실행 (개발/디버깅용)

YARN을 거치지 않고 호스트에서 직접 JAR를 실행하여 동작을 확인합니다.

### 3-1. application.yaml 설정 확인

`src/main/resources/application.yaml` 내의 연결 정보가 로컬 환경과 일치하는지 확인합니다.

```yaml
database:
  url: jdbc:postgresql://localhost:5432/dsc_tiering
  username: dsc
  password: dsc

hdfs:
  fs-default-name: hdfs://localhost:9000
```

> `INFRA.md`에서 NameNode RPC 포트는 **9000** 입니다 (`fs.defaultFS = hdfs://localhost:9000`).

### 3-2. 실행

```bash
# 기본 classpath application.yaml 사용
java -jar target/hdfs-auto-tiering.jar

# 또는 외부 yaml 경로 지정
java -jar target/hdfs-auto-tiering.jar /path/to/custom-application.yaml
```

정상 동작 시 `window=... claimed=0 files` 로그가 `poll-interval-seconds` 간격으로 출력됩니다.

### 3-3. 수동 테스트 (PENDING row 주입)

별도 터미널에서 DB에 테스트 row를 삽입해 DISPATCHED 전이를 확인합니다.

```bash
psql -h localhost -U dsc -d dsc_tiering -c \
  "INSERT INTO pending_jobs (file_path, file_size_bytes, current_tier, target_tier, priority_score, scored_at)
   VALUES ('/tiering-test/sample.bin', 1048576, 'HOT', 'COLD', 99.0, NOW());"
```

---

## 4. YARN Service 배포 (운영용)

### 4-1. HDFS에 JAR 및 설정 파일 업로드

```bash
hdfs dfs -mkdir -p /apps/dsc-tiering/
hdfs dfs -put -f target/hdfs-auto-tiering.jar /apps/dsc-tiering/
hdfs dfs -put -f src/main/resources/application.yaml /apps/dsc-tiering/

# 업로드 확인
hdfs dfs -ls /apps/dsc-tiering/
```

### 4-2. Yarnfile 작성

프로젝트 루트에 `Yarnfile.json`을 생성합니다.

```json
{
  "name": "hdfs-auto-tiering",
  "version": "0.1.0",
  "components": [
    {
      "name": "tiering-daemon",
      "number_of_containers": 1,
      "artifact": {
        "id": "hdfs:///apps/dsc-tiering/hdfs-auto-tiering.jar",
        "type": "ARCHIVE"
      },
      "resource": {
        "cpus": 2,
        "memory": "1024"
      },
      "launch_command": "$JAVA_HOME/bin/java -jar hdfs-auto-tiering.jar application.yaml",
      "configuration": {
        "env": {
          "JAVA_HOME": "/usr/lib/jvm/java-11-openjdk-amd64",
          "HADOOP_CONF_DIR": "$HOME/hadoop-conf/namenode",
          "HADOOP_HOME": "$HOME/hadoop"
        },
        "files": [
          {
            "type": "STATIC",
            "src_file": "hdfs:///apps/dsc-tiering/application.yaml",
            "dest_file": "application.yaml"
          }
        ]
      }
    }
  ]
}
```

### 4-3. YARN Service 기동

```bash
yarn app -launch hdfs-auto-tiering ./Yarnfile.json
```

### 4-4. 상태 확인

```bash
# 서비스 상태 조회
yarn app -status hdfs-auto-tiering

# 컨테이너 로그 확인
yarn logs -applicationId <application_id>
```

### 4-5. 서비스 중지

```bash
yarn app -stop hdfs-auto-tiering
```

### 4-6. 서비스 삭제 (완전 제거)

```bash
yarn app -destroy hdfs-auto-tiering
```

---

## 5. GitHub Actions 자동 빌드 및 릴리즈

버전 태그(`v*`) Push 시 자동으로 Fat JAR을 빌드하고 GitHub Releases에 첨부하는 CI/CD 파이프라인입니다.

### 5-1. 워크플로우 파일 생성

`.github/workflows/release.yml` 파일을 생성합니다.

```yaml
name: Build and Release JAR

on:
  push:
    tags:
      - 'v*'

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - name: Checkout Code
        uses: actions/checkout@v4

      - name: Set up JDK 11
        uses: actions/setup-java@v4
        with:
          java-version: '11'
          distribution: 'temurin'
          cache: maven

      - name: Build Fat JAR
        run: mvn -f services/hdfs-auto-tiering/pom.xml clean package -DskipTests

      - name: Create GitHub Release
        uses: softprops/action-gh-release@v2
        with:
          files: services/hdfs-auto-tiering/target/hdfs-auto-tiering.jar
        env:
          GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
```

### 5-2. 릴리즈 수행 방법

```bash
git tag v0.1.0
git push origin v0.1.0
```

태그가 Push되면 GitHub Actions가 자동으로 JAR을 빌드하고, 해당 태그의 Release 페이지에 `hdfs-auto-tiering.jar`가 첨부됩니다.

---

## 6. 트러블슈팅

| 증상 | 원인 | 해결 |
|---|---|---|
| `UnsupportedClassVersionError` | JAR이 Java 17로 빌드됨 | `pom.xml`의 `maven.compiler.source/target`이 `11`인지 확인 후 재빌드 |
| `Connection refused (localhost:9000)` | NameNode 미기동 | `HADOOP_CONF_DIR=~/hadoop-conf/namenode hdfs --daemon start namenode` |
| `FATAL: role "dsc" does not exist` | PostgreSQL 미설정 | `INFRA.md` §15 PostgreSQL 설정 참조 |
| YARN 컨테이너 즉시 FAILED | 메모리 부족 또는 JAR 경로 오류 | `yarn logs`로 원인 확인, Yarnfile의 memory 값 조정 |
| `FileNotFoundException: application.yaml` | HDFS에 설정 파일 미업로드 | §4-1 HDFS 업로드 단계 재수행 |
