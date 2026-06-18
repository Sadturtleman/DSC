# DSC Auto-Tiering 빌드 및 배포 가이드

본 문서는 `INFRA.md`에 따라 구축된 서버(WSL2 Ubuntu) 환경과 개발 PC(Windows) 환경을 기준으로, `hdfs-auto-tiering` 서비스를 빌드하고 배포하는 절차를 안내합니다.

---

## 1. 사전 조건

| 환경 | 항목 | 확인 방법 |
|---|---|---|
| **서버 (Ubuntu)** | Hadoop 3.4.1 데몬 정상 기동 | `jps` (NameNode, DataNode, SPS 확인) |
| **서버 (Ubuntu)** | YARN 환경 정상 기동 | `yarn node -list` |
| **서버 (Ubuntu)** | PostgreSQL 기동 + DDL | `psql -h localhost -U dsc -d dsc_tiering -c "\dt"` |

---

## 2. 운영 환경 자동 배포 파이프라인 (권장)

**GitHub Actions**와 **GitHub Releases**를 연동하여 자동화된 배포를 수행합니다.

### 2-1. GitHub Actions를 통한 자동 빌드 및 릴리즈
개발 PC(Windows)에서는 소스 코드를 수정한 뒤, 버전을 명시하는 태그(Tag)를 달아 GitHub에 푸시하기만 하면 됩니다.

```powershell
# 개발 PC(Windows)에서 실행
git tag v1.0.0
git push origin v1.0.0
```

태그가 푸시되면 `.github/workflows/release.yml`에 의해 **GitHub Actions**가 자동으로 코드를 빌드(`mvn clean package`)하고, `hdfs-auto-tiering.jar` 파일을 해당 릴리즈의 Asset으로 업로드합니다.

### 2-2. 서버(Ubuntu)에서 자동 배포 스크립트 실행
GitHub에 릴리즈가 완료되면, 서버(Ubuntu) 터미널에서 `INFRA.md`에 정의된 배포 스크립트를 실행합니다.

서버는 `INFRA.md` 대로 미리 설정되어 있어야 합니다.

```bash
# 서버(Ubuntu)에서 실행
~/deploy-auto-tiering.sh
```

해당 스크립트는 다음 작업을 자동으로 수행합니다:
1. GitHub 최신 릴리즈에서 `jar` 파일을 서버 임시 경로로 다운로드
2. HDFS 경로(`/apps/hdfs-auto-tiering/lib/`)에 다운로드한 `jar` 파일 업로드
3. 기존 YARN Service 중지 및 완전 삭제(`yarn app -destroy`)
4. 새로운 `jar` 파일로 YARN Service 재기동(`yarn app -launch`)

---

## 3. 로컬 빌드 및 직접 실행 (개발/테스트용)

자동 배포를 거치지 않고, 윈도우 로컬 환경에서 직접 빌드하거나 테스트해야 할 때 사용하는 방법입니다.

### 3-1. Windows에 메이븐(Maven) 설치
윈도우에 Maven이 없다면 PowerShell(관리자 권한 불필요)에서 아래 명령어로 설치합니다.

```powershell
winget install Apache.Maven
```
> **주의:** 설치 완료 후 반드시 PowerShell 창을 닫고 새로 열어야 `mvn` 명령어가 인식됩니다.

### 3-2. 로컬 빌드 (Fat JAR 생성)
소스코드가 있는 경로로 이동하여 패키징을 수행합니다.

```powershell
cd C:\Users\0w0i0\Desktop\DSC\services\hdfs-auto-tiering
mvn clean package -DskipTests
```
빌드 성공 시 `target\hdfs-auto-tiering.jar` 파일이 생성됩니다.

### 3-3. 로컬 직접 실행
YARN에 올리지 않고 윈도우 호스트에서 데몬을 직접 띄워 테스트할 수 있습니다.
(`src/main/resources/application.yaml` 내부의 접속 정보가 로컬 환경과 일치하는지 확인 후 실행하세요.)

```powershell
# 기본 설정 사용
java -jar target\hdfs-auto-tiering.jar

# 또는 커스텀 설정 파일 지정
java -jar target\hdfs-auto-tiering.jar C:\path\to\custom-config.yaml
```

---

## 4. YARN Service 배포 내부 동작 (참고사항)

자동 배포 스크립트(`deploy-auto-tiering.sh`)가 뒷단에서 수행하는 구체적인 YARN 배포 동작은 다음과 같습니다. 수동 조작이 필요할 때 참고하세요.

### 4-1. HDFS 디렉터리 구조
JAR 파일 및 설정 파일은 HDFS의 다음 경로에 위치해야 합니다.
- JAR 파일: `hdfs:///apps/hdfs-auto-tiering/lib/hdfs-auto-tiering.jar`
- 설정 파일: `hdfs:///apps/hdfs-auto-tiering/config/hdfs-auto-tiering-config.yaml`

설정 파일의 `scoring.target-directories`는 데몬이 처리할 HDFS 경로 화이트리스트입니다. 검증 스크립트를 실행할 때는 `/test/metric`을 포함해야 하며, 각 테스트 데이터는 그 하위 경로에 생성됩니다.

### 4-2. YARN Service 정의 파일 (`hdfs-auto-tiering-service.json`)
서버의 `~` 경로에 위치하며, YARN Service 프레임워크가 앱을 띄우는 명세서 역할을 합니다.
내부적으로 위 HDFS 경로에서 파일을 내려받고 `java -jar` 명령어로 컨테이너를 실행합니다.

### 4-3. YARN 서비스 관리 명령어
서비스 상태에 문제가 생겼을 경우, 서버 터미널에서 아래 명령어로 제어합니다.

```bash
# 상태 확인
yarn app -status hdfs-auto-tiering
yarn app -list -appTypes YARN-SERVICE

# 강제 중지 및 삭제
yarn app -stop hdfs-auto-tiering
yarn app -destroy hdfs-auto-tiering

# 컨테이너 로그 확인 (에러 추적 시 유용)
yarn logs -applicationId <application_id>
```