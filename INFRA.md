# HDFS Auto-Tiering YARN 서비스
## 인프라 구성 완전 가이드

**환경**: Windows 11 + WSL2 Ubuntu 22.04 + Hadoop 3.4.1
**대상**: 클린 설치 Windows 11 데스크탑에서 처음부터 구성하는 경우

---

## 목차

0. [사용법 (명령어 및 스크립트 요약)](#0-사용법-명령어-및-스크립트-요약)
1. [아키텍처 개요](#1-아키텍처-개요)
2. [시스템 요구사항](#2-시스템-요구사항)
3. [WSL2 설치](#3-wsl2-설치)
4. [WSL2 리소스 설정](#4-wsl2-리소스-설정)
5. [Ubuntu 초기 설정](#5-ubuntu-초기-설정)
6. [Java 11 설치](#6-java-11-설치)
7. [PostgreSQL 설치 및 설정](#7-postgresql-설치-및-설정)
8. [SSH 무암호 로컬 접속 설정](#8-ssh-무암호-로컬-접속-설정)
9. [Hadoop 설치](#9-hadoop-설치)
10. [환경변수 등록](#10-환경변수-등록)
11. [디렉터리 구조 생성](#11-디렉터리-구조-생성)
12. [설정 파일 작성](#12-설정-파일-작성)
13. [NameNode 포맷](#13-namenode-포맷)
14. [시작·종료 스크립트 작성](#14-시작종료-스크립트-작성)
15. [클러스터 기동 및 확인](#15-클러스터-기동-및-확인)
16. [External SPS 구성](#16-external-sps-구성)
17. [스토리지 정책 동작 검증](#17-스토리지-정책-동작-검증)
18. [Auto-Tiering YARN 서비스 배포](#18-auto-tiering-yarn-서비스-배포)
19. [포트 참조표](#19-포트-참조표)
20. [트러블슈팅](#20-트러블슈팅)
21. [오토티어링 효용 검증 (비용 절감 리포트)](#21-오토티어링-효용-검증-비용-절감-리포트)

---

## 0. 사용법 (명령어 및 스크립트 요약)

본 인프라와 서비스를 관리하기 위해 사용하는 스크립트 및 명령어의 용도와 사용 상황을 요약합니다.
모든 쉘 스크립트는 서버(Ubuntu)의 `~/` 경로에 생성되어 있다고 가정합니다.

### 0.1 인프라 (Hadoop/YARN) 전체 제어
* **클러스터 기동 스크립트**: `~/hadoop-start.sh`
  * **상황**: WSL2 인스턴스를 재시작했거나 클러스터 데몬이 내려간 경우, NameNode, DataNode(3대), ResourceManager, NodeManager, External SPS를 순서대로 모두 기동할 때 사용합니다.
* **클러스터 종료 스크립트**: `~/hadoop-stop.sh`
  * **상황**: YARN 서비스와 모든 Hadoop 관련 데몬을 안전하게 종료할 때 사용합니다.
* **클러스터 완전 초기화**: (명령어 직접 입력, [20. 트러블슈팅](#처음부터-완전히-초기화해야-할-때) 참조)
  * **상황**: HDFS 데이터가 손상되어 NameNode를 포맷하고 클러스터를 처음부터 다시 구성하고 싶을 때 사용합니다.

### 0.2 YARN 서비스 (Auto-Tiering 데몬) 제어
* **자동 배포 스크립트**: `~/deploy-auto-tiering.sh`
  * **상황**: 개발 PC(Windows)에서 GitHub Repository에 새 버전을 릴리즈한 후, 서버(Ubuntu)에 새로운 버전의 데몬(JAR)을 다운로드하고 YARN에 자동으로 배포 및 재기동할 때 사용합니다.
* **E2E 테스트 스크립트**: `~/test-auto-tiering.sh`
  * **상황**: 자동 배포 스크립트를 통해 HDFS에 업로드된 JAR 파일을 기반으로, 전체 파이프라인(파일 업로드 -> FSImage Scoring -> 스케줄러 기동 -> HDFS 물리 블록 이동)이 정상 동작하는지 검증할 때 사용합니다.
* **효용 검증 및 비용 절감 리포트**: `~/test-auto-tiering-savings.sh [A|B|C]`
  * **상황**: 프로젝트 시연 시 E2E 이관 실적을 바탕으로 "스토리지 비용을 얼마나 절감했는지"를 계산합니다. 뒤에 `A`, `B`, `C` 인자를 붙이면 해당 시나리오에 맞춰 기가바이트(GB) 단위의 물리적 더미 파일을 생성하고 이관 테스트를 전체 수행한 뒤 절감액을 산출합니다.
* **서비스 상태 확인**: `yarn app -status hdfs-auto-tiering`
  * **상황**: YARN에 올라간 데몬 컨테이너가 정상적으로 실행(`STABLE`, `RUNNING`)되고 있는지 확인할 때 사용합니다.
* **서비스 컨테이너 로그 확인**: `yarn logs -applicationId <application_id>`
  * **상황**: 데몬 프로세스 내에서 에러가 발생했거나, YARN 서비스 기동이 실패했을 때 디버깅 목적으로 상세 로그를 조회할 때 사용합니다.
* **External SPS 데몬 로그 실시간 확인**: `tail -f ~/hadoop-logs/sps/hadoop-$(whoami)-sps-$(hostname).log`
  * **상황**: HDFS 데몬인 SPS가 HDFS 내부에서 블록을 스토리지 정책에 맞게 물리적으로 이동시키는 과정 중 발생하는 로그나 에러(Drop, Timeout 등)를 실시간으로 추적할 때 사용합니다.

### 0.3 데이터베이스 (PostgreSQL) 제어
* **PostgreSQL 서비스 기동**: `sudo service postgresql start`
  * **상황**: WSL2 재시작 후 서비스 구동 전 DB를 수동으로 띄워야 할 때 사용합니다.
* **DB 접속 및 상태 조회**: `psql -h localhost -U dsc -d dsc_tiering`
  * **상황**: `pending_jobs` 테이블에 작업 목록이 정상적으로 적재되었는지 수동으로 쿼리하여 확인할 때 사용합니다.
* **DB 작업 스케줄링 실시간 모니터링**: 
  ```bash
  watch -n 2 "psql -h localhost -U dsc -d dsc_tiering -c \"SELECT job_id, file_path, current_tier, target_tier, status, retry_count, last_failed_at FROM pending_jobs ORDER BY job_id DESC LIMIT 20;\""
  ```
  * **상황**: 오토 티어링 데몬이 대상 파일들을 스케줄링하고 HDFS로 명령을 내리는(Dispatch) 과정을 실시간(2초마다 갱신)으로 추적하며, `status`(PENDING -> DISPATCHED -> IN_PROGRESS -> COMPLETED)가 전이되는 과정을 모니터링할 때 사용합니다.

---

## 1. 아키텍처 개요

### 1.1 실제 운영 환경과의 대응 관계

운영 환경에서 Hadoop 클러스터는 물리 서버 단위로 역할이 분리된다. 본 가이드에서는 단일 WSL2 인스턴스 내에서 복수의 Java 프로세스로 이를 시뮬레이션한다. 각 DataNode는 별도의 `HADOOP_CONF_DIR`·`HADOOP_PID_DIR`·`HADOOP_LOG_DIR`을 가져 운영 환경의 노드 분리를 재현한다.

| 운영 환경 | 본 구성 |
|---|---|
| NameNode 전용 서버 | WSL2 내 NameNode 프로세스 |
| Worker Node (DataNode + NodeManager) × N | WSL2 내 DataNode 프로세스 × 3 + NodeManager 프로세스 × 1 |
| External SPS 데몬 서버 | WSL2 내 StoragePolicySatisfier 프로세스 |
| YARN ResourceManager 서버 | WSL2 내 ResourceManager 프로세스 |

### 1.2 이기종 스토리지 구성

HDFS의 스토리지 정책은 `dfs.datanode.data.dir`의 디렉터리 레이블(`[SSD]`, `[DISK]`, `[ARCHIVE]`)로 결정된다. 물리 미디어가 달라야 하는 것이 아니라 소프트웨어 레벨의 타입 태깅으로 동작하므로, 단일 물리 디스크 위에 디렉터리를 분리하는 것만으로 이기종 스토리지 환경을 완전히 재현할 수 있다.

| DataNode | 스토리지 레이블 | HDFS 정책 | 역할 |
|---|---|---|---|
| DataNode-0 | `[SSD]` | ALL_SSD | HOT 데이터 |
| DataNode-1 | `[DISK]` | ONE_SSD | WARM 데이터 |
| DataNode-2 | `[ARCHIVE]` | COLD | COLD 데이터 |

### 1.3 전체 구성도

```
┌──────────────────────────────────────────────────────────────────┐
│                        Windows 11 Host                           │
│                                                                  │
│  ┌────────────────────────────────────────────────────────────┐  │
│  │                   WSL2 (Ubuntu 22.04)                      │  │
│  │                                                            │  │
│  │  ┌─────────────────┐   ┌──────────────────────────────┐   │  │
│  │  │   NameNode      │   │           YARN               │   │  │
│  │  │  RPC  :9000     │   │  ResourceManager      :8088  │   │  │
│  │  │  WebUI:9870     │   │  NodeManager          :8042  │   │  │
│  │  └────────┬────────┘   └──────────────┬───────────────┘   │  │
│  │           │ fetchImage (HTTP)          │ YARN Service       │  │
│  │           │                            │ Framework          │  │
│  │           │           ┌────────────────▼──────────────┐   │  │
│  │           │           │   hdfs-auto-tiering           │   │  │
│  │           └───────────▶   (YARN Container)            │   │  │
│  │                       │ 1. [Scoring] FSImage Fetch    │   │  │
│  │                       │    & Parse (OIV), DB Insert   │   │  │
│  │                       │ 2. [Scheduler] 정책 변경        │   │  │
│  │                       │    (satisfyStoragePolicy)     │   │  │
│  │                       │ 3. [Tracker] 블록 위치 검증     │   │  │
│  │                       └───────────────────────────────┘   │  │
│  │                                                            │  │
│  │  ┌──────────────┐ ┌──────────────┐ ┌──────────────────┐  │  │
│  │  │ DataNode-0   │ │ DataNode-1   │ │ DataNode-2       │  │  │
│  │  │ [SSD]  :9864 │ │ [DISK] :9874 │ │ [ARCHIVE]  :9884 │  │  │
│  │  └──────────────┘ └──────────────┘ └──────────────────┘  │  │
│  │                                                            │  │
│  │  ┌──────────────────────────────────────────────────────┐  │  │
│  │  │  External SPS Daemon (StoragePolicySatisfier)        │  │  │
│  │  │  hdfs --daemon start sps                             │  │  │
│  │  │  블록 물리 이동 실행 (NameNode 부하 분리)              │  │  │
│  │  └──────────────────────────────────────────────────────┘  │  │
│  └────────────────────────────────────────────────────────────┘  │
│                                                                  │
│   브라우저 → http://localhost:9870  (HDFS NameNode UI)           │
│   브라우저 → http://localhost:8088  (YARN ResourceManager UI)    │
└──────────────────────────────────────────────────────────────────┘
```

### 1.4 디렉터리 레이아웃

```
~/
├── hadoop/                        ← Hadoop 바이너리 (공유)
├── hadoop-conf/
│   ├── namenode/                  ← NameNode · RM · NM 설정
│   ├── dn0/                       ← DataNode-0 [SSD] 설정
│   ├── dn1/                       ← DataNode-1 [DISK] 설정
│   ├── dn2/                       ← DataNode-2 [ARCHIVE] 설정
│   └── sps/                       ← External SPS 설정
├── hadoop-data/
│   ├── namenode/                  ← NameNode 메타데이터
│   ├── dn0-ssd/                   ← DataNode-0 데이터
│   ├── dn1-onessd/                ← DataNode-1 데이터
│   └── dn2-archive/               ← DataNode-2 데이터
├── hadoop-pids/                   ← 각 데몬 PID 파일
├── hadoop-logs/                   ← 각 데몬 로그
└── hadoop-yarn/
    ├── local/                     ← YARN 로컬 임시 디렉터리
    └── log/                       ← YARN 컨테이너 로그
```

---

## 2. 시스템 요구사항

### 최소 / 권장 사양

| 항목 | 최소 | 권장 |
|---|---|---|
| OS | Windows 11 21H2 (빌드 22000) | Windows 11 23H2 이상 |
| RAM | 16 GB | 32 GB |
| 저장 공간 | 20 GB 여유 | 50 GB 이상 |
| CPU | 4코어 | 8코어 이상 |

### Windows 버전 확인

`Win + R` → `winver` → 빌드 번호가 22000 이상인지 확인한다.

---

## 3. WSL2 설치

### 3-1. PowerShell 관리자 권한으로 열기

`Win + S` → `PowerShell` → **관리자 권한으로 실행**

### 3-2. WSL2 및 Ubuntu 22.04 설치

```powershell
wsl --install -d Ubuntu-22.04
```

이 명령 하나로 WSL2 기능 활성화, 가상 머신 플랫폼, Linux 커널 업데이트, Ubuntu 22.04 설치가 모두 진행된다.

### 3-3. 재부팅

설치 완료 후 반드시 재부팅한다.

### 3-4. Ubuntu 초기 계정 설정

재부팅 후 Ubuntu 터미널이 자동으로 열린다.

- **UNIX username** 입력 (영문 소문자, 예: `dsc`)
- **password** 입력 두 번 (화면에 표시되지 않는 것이 정상)

> 이후 이 가이드에서 `<username>`은 위에서 입력한 계정명을 의미한다. 명령어에는 `$(whoami)`를 사용하므로 직접 치환할 필요가 없다.

### 3-5. WSL2 기본 버전 확인 (PowerShell에서)

```powershell
wsl --set-default-version 2
wsl -l -v
```

Ubuntu-22.04의 `VERSION` 컬럼이 `2`이어야 한다.

---

## 4. WSL2 리소스 설정

### 4-1. .wslconfig 파일 생성 (PowerShell에서)

```powershell
notepad "$env:USERPROFILE\.wslconfig"
```

### 4-2. 내용 입력 후 저장

```ini
[wsl2]
memory=12GB
processors=6
swap=4GB
```

> RAM 32 GB 이상이면 `memory=20GB`, RAM 16 GB이면 `memory=10GB`로 설정한다.

### 4-3. WSL2 재시작 (PowerShell에서)

```powershell
wsl --shutdown
```

Ubuntu 터미널을 다시 연다.

### 4-4. 적용 확인 (Ubuntu 터미널에서)

```bash
free -h
```

`Mem:` 행의 `total`이 설정값에 근접하면 정상이다.

---

## 5. Ubuntu 초기 설정

이후 모든 작업은 **Ubuntu 터미널** 안에서 수행한다.

### 5-1. 패키지 업데이트

```bash
sudo apt update && sudo apt upgrade -y
```

### 5-2. 필수 패키지 설치

```bash
sudo apt install -y \
  curl wget unzip tar \
  net-tools iputils-ping \
  openssh-server openssh-client \
  vim nano
```

---

## 6. Java 11 설치

Hadoop 3.4.x는 Java 8 또는 Java 11을 지원한다. 본 가이드는 Java 11을 사용한다.

### 6-1. OpenJDK 11 설치

```bash
sudo apt install -y openjdk-11-jdk
```

### 6-2. 설치 확인

```bash
java -version
```

```
openjdk version "11.0.xx" ...
```

### 6-3. JAVA_HOME 경로 확인

```bash
readlink -f $(which java) | sed "s|/bin/java||"
```

출력 예시: `/usr/lib/jvm/java-11-openjdk-amd64`

이 경로를 기억해 둔다. 이후 설정 파일에 사용한다.

---

## 7. PostgreSQL 설치 및 설정

데이터베이스 스키마와 상태 관리 기능을 사용하기 위해 네이티브 PostgreSQL 환경을 구성합니다.

### 7-1. 패키지 설치 및 서비스 시작

```bash
sudo apt update
sudo apt install -y postgresql postgresql-contrib
sudo service postgresql start
```

### 7-2. 유저 및 데이터베이스 생성

```bash
sudo -u postgres psql -c "CREATE USER dsc WITH PASSWORD 'dsc';"
sudo -u postgres psql -c "CREATE DATABASE dsc_tiering OWNER dsc;"
```

### 7-3. 스키마(DDL) 파일 적용

프로젝트 저장소가 클론되어 있다고 가정하고 마이그레이션 파일들을 순서대로 실행합니다.

```bash
# 본인의 프로젝트 루트(~/DSC 또는 지정한 경로)에서 실행
psql -h localhost -U dsc -d dsc_tiering -f ~/DSC/db/migrations/V001__pending_jobs.sql
psql -h localhost -U dsc -d dsc_tiering -f ~/DSC/db/migrations/V002__add_retry_columns.sql
```

테이블이 정상적으로 생성되었는지 확인합니다.

```bash
psql -h localhost -U dsc -d dsc_tiering -c "\dt"
```

---

## 8. SSH 무암호 로컬 접속 설정

Hadoop 데몬 제어 스크립트는 SSH를 통해 localhost에 접속한다. 암호 없이 접속되어야 한다.

### 8-1. SSH 서버 시작

```bash
sudo service ssh start
```

### 8-2. sudo 패스워드 없이 SSH 시작되도록 설정

```bash
sudo visudo
```

편집기가 열리면 파일 맨 끝에 아래 한 줄을 추가한다. `dsc` 부분을 본인 username으로 교체한다.

```
dsc ALL=(ALL) NOPASSWD: /usr/sbin/service ssh start
```

저장: `Ctrl+O` → Enter → `Ctrl+X`

### 8-3. SSH 키 생성

```bash
ssh-keygen -t rsa -P '' -f ~/.ssh/id_rsa
```

### 8-4. 공개키 등록

```bash
cat ~/.ssh/id_rsa.pub >> ~/.ssh/authorized_keys
chmod 600 ~/.ssh/authorized_keys
```

### 8-5. localhost 접속 테스트

```bash
ssh localhost
```

`Are you sure you want to continue connecting (yes/no/[fingerprint])?` → `yes` 입력

접속 성공 후 `exit`로 빠져나온다. 이후에는 암호 없이 즉시 접속되어야 한다.

---

## 9. Hadoop 설치

### 9-1. Hadoop 3.4.1 다운로드

```bash
cd ~
wget https://downloads.apache.org/hadoop/common/hadoop-3.4.1/hadoop-3.4.1.tar.gz
```

### 9-2. 압축 해제 및 이동

```bash
tar -xzf hadoop-3.4.1.tar.gz
mv hadoop-3.4.1 ~/hadoop
```

### 9-3. 설치 확인

```bash
~/hadoop/bin/hadoop version
```

`Hadoop 3.4.1`이 출력되면 정상이다.

---

## 10. 환경변수 등록

### 10-1. `.bashrc` 편집

```bash
nano ~/.bashrc
```

파일 맨 끝에 아래 내용을 추가한다.

```bash
# ── Hadoop 환경변수 ──────────────────────────────────────────
export JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64
export HADOOP_HOME=$HOME/hadoop
export HADOOP_CONF_DIR=$HADOOP_HOME/etc/hadoop
export PATH=$PATH:$HADOOP_HOME/bin:$HADOOP_HOME/sbin
export HADOOP_OPTS="$HADOOP_OPTS -Djava.net.preferIPv4Stack=true"
```

저장: `Ctrl+O` → Enter → `Ctrl+X`

### 10-2. 즉시 적용

```bash
source ~/.bashrc
```

### 10-3. 확인

```bash
hadoop version
echo $JAVA_HOME
```

---

## 11. 디렉터리 구조 생성

```bash
# 설정 디렉터리
mkdir -p ~/hadoop-conf/{namenode,dn0,dn1,dn2,sps}

# 데이터 디렉터리
mkdir -p ~/hadoop-data/{namenode,dn0-ssd,dn1-onessd,dn2-archive}

# NameNode 체크포인트 디렉터리
mkdir -p ~/hadoop-data/namenode/checkpoint

# PID 파일 디렉터리
mkdir -p ~/hadoop-pids/{namenode,yarn,sps,dn0,dn1,dn2}

# 로그 디렉터리
mkdir -p ~/hadoop-logs/{namenode,yarn,sps,dn0,dn1,dn2}

# YARN 로컬·로그 디렉터리
mkdir -p ~/hadoop-yarn/{local,log}
```

---

## 12. 설정 파일 작성

각 섹션의 명령을 순서대로 실행한다. 모든 파일을 빠짐없이 작성해야 한다.

---

### 12-A. Hadoop 기본 설정 파일 복사

Hadoop 기본 conf에서 모든 커스텀 conf 디렉터리로 공통 파일을 복사한다.

```bash
for CONF_DIR in namenode dn0 dn1 dn2 sps; do
  cp ~/hadoop/etc/hadoop/log4j.properties  ~/hadoop-conf/$CONF_DIR/
  cp ~/hadoop/etc/hadoop/log4j2.properties ~/hadoop-conf/$CONF_DIR/ 2>/dev/null || true
done
```

---

### 12-B. NameNode 설정 (`~/hadoop-conf/namenode/`)

#### core-site.xml

```bash
cat > ~/hadoop-conf/namenode/core-site.xml << 'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<?xml-stylesheet type="text/xsl" href="configuration.xsl"?>
<configuration>

  <property>
    <name>fs.defaultFS</name>
    <value>hdfs://localhost:9000</value>
  </property>

  <property>
    <name>hadoop.tmp.dir</name>
    <value>/tmp/hadoop-PLACEHOLDER</value>
  </property>

</configuration>
EOF
sed -i "s|PLACEHOLDER|$(whoami)|g" ~/hadoop-conf/namenode/core-site.xml
```

#### hdfs-site.xml

```bash
cat > ~/hadoop-conf/namenode/hdfs-site.xml << 'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<?xml-stylesheet type="text/xsl" href="configuration.xsl"?>
<configuration>

  <!-- NameNode 메타데이터 저장 경로 -->
  <property>
    <name>dfs.namenode.name.dir</name>
    <value>HOMEDIR/hadoop-data/namenode</value>
  </property>

  <!-- FSImage 체크포인트 디렉터리 -->
  <property>
    <name>dfs.namenode.checkpoint.dir</name>
    <value>HOMEDIR/hadoop-data/namenode/checkpoint</value>
  </property>

  <!-- 단일 머신에서 복수 DataNode 등록 허용 -->
  <property>
    <name>dfs.namenode.datanode.registration.ip-hostname-check</name>
    <value>false</value>
  </property>

  <!-- 복제본 수 (단일 머신) -->
  <property>
    <name>dfs.replication</name>
    <value>1</value>
  </property>

  <!-- 이기종 스토리지 정책 활성화 -->
  <property>
    <name>dfs.storage.policy.enabled</name>
    <value>true</value>
  </property>

  <!-- External SPS 모드 -->
  <property>
    <name>dfs.storage.policy.satisfier.mode</name>
    <value>external</value>
  </property>

  <!-- SPS 재시도 횟수 제한 해제 (기본 3 -> 100) : 3번 실패 시 포기 방지 -->
  <property>
    <name>dfs.storage.policy.satisfier.retry.max.attempts</name>
    <value>100</value>
  </property>

  <!-- SPS 대기 시간 단축 (기본 60000ms -> 3000ms) : 1분 대기 방지 -->
  <property>
    <name>dfs.storage.policy.satisfier.recheck.timeout.millis</name>
    <value>3000</value>
  </property>

  <!-- NameNode 동시 블록 이동 허용량 대폭 증가 (기본 2 -> 20) -->
  <property>
    <name>dfs.namenode.replication.max-streams</name>
    <value>20</value>
  </property>

  <!-- NameNode 동시 블록 이동 하드 리미트 증가 (기본 4 -> 40) -->
  <property>
    <name>dfs.namenode.replication.max-streams-hard-limit</name>
    <value>40</value>
  </property>

  <!-- NameNode Web UI -->
  <property>
    <name>dfs.namenode.http-address</name>
    <value>0.0.0.0:9870</value>
  </property>

</configuration>
EOF
sed -i "s|HOMEDIR|$HOME|g" ~/hadoop-conf/namenode/hdfs-site.xml
```

#### yarn-site.xml

```bash
cat > ~/hadoop-conf/namenode/yarn-site.xml << 'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<?xml-stylesheet type="text/xsl" href="configuration.xsl"?>
<configuration>

  <property>
    <name>yarn.resourcemanager.hostname</name>
    <value>localhost</value>
  </property>

  <property>
    <name>yarn.nodemanager.aux-services</name>
    <value>mapreduce_shuffle</value>
  </property>

  <!-- YARN Service Framework REST API 활성화 -->
  <property>
    <name>yarn.webapp.api-service.enable</name>
    <value>true</value>
  </property>

  <!-- NodeManager 가용 메모리 (MB) -->
  <property>
    <name>yarn.nodemanager.resource.memory-mb</name>
    <value>8192</value>
  </property>

  <!-- NodeManager 가용 CPU 코어 -->
  <property>
    <name>yarn.nodemanager.resource.cpu-vcores</name>
    <value>4</value>
  </property>

  <!-- 컨테이너 최소 메모리 -->
  <property>
    <name>yarn.scheduler.minimum-allocation-mb</name>
    <value>256</value>
  </property>

  <!-- YARN 로컬 디렉터리 -->
  <property>
    <name>yarn.nodemanager.local-dirs</name>
    <value>HOMEDIR/hadoop-yarn/local</value>
  </property>

  <!-- YARN 컨테이너 로그 디렉터리 -->
  <property>
    <name>yarn.nodemanager.log-dirs</name>
    <value>HOMEDIR/hadoop-yarn/log</value>
  </property>

  <!-- 로그 집계 활성화 -->
  <property>
    <name>yarn.log-aggregation-enable</name>
    <value>true</value>
  </property>

  <!-- 환경변수 전달 화이트리스트 -->
  <property>
    <name>yarn.nodemanager.env-whitelist</name>
    <value>JAVA_HOME,HADOOP_COMMON_HOME,HADOOP_HDFS_HOME,HADOOP_CONF_DIR,CLASSPATH_PREPEND_DISTCACHE,HADOOP_YARN_HOME,HADOOP_HOME,PATH,LANG,TZ</value>
  </property>

  <!-- cgroup 비활성화 (WSL2 환경) -->
  <property>
    <name>yarn.nodemanager.container-executor.class</name>
    <value>org.apache.hadoop.yarn.server.nodemanager.DefaultContainerExecutor</value>
  </property>

  <!-- YARN Service 프레임워크 의존성 경로 강제 지정 (버그 우회) -->
  <property>
    <name>yarn.service.framework.path</name>
    <value>hdfs:///user/dsc/.yarn/system/services/service-dep.tar.gz</value>
  </property>

</configuration>
EOF
sed -i "s|HOMEDIR|$HOME|g" ~/hadoop-conf/namenode/yarn-site.xml
```

#### capacity-scheduler.xml

CapacityScheduler는 반드시 root 큐와 자식 큐 정의가 있어야 ResourceManager가 기동된다.

```bash
cat > ~/hadoop-conf/namenode/capacity-scheduler.xml << 'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<?xml-stylesheet type="text/xsl" href="configuration.xsl"?>
<configuration>

  <!-- root 큐의 자식 큐 정의 -->
  <property>
    <name>yarn.scheduler.capacity.root.queues</name>
    <value>default</value>
  </property>

  <!-- default 큐: 전체 용량 100% -->
  <property>
    <name>yarn.scheduler.capacity.root.default.capacity</name>
    <value>100</value>
  </property>

  <property>
    <name>yarn.scheduler.capacity.root.default.maximum-capacity</name>
    <value>100</value>
  </property>

  <property>
    <name>yarn.scheduler.capacity.root.default.state</name>
    <value>RUNNING</value>
  </property>

  <property>
    <name>yarn.scheduler.capacity.root.default.acl_submit_applications</name>
    <value>*</value>
  </property>

  <property>
    <name>yarn.scheduler.capacity.root.default.acl_administer_queue</name>
    <value>*</value>
  </property>

  <property>
    <name>yarn.scheduler.capacity.root.default.user-limit-factor</name>
    <value>1</value>
  </property>

</configuration>
EOF
```

#### mapred-site.xml

```bash
cat > ~/hadoop-conf/namenode/mapred-site.xml << 'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<?xml-stylesheet type="text/xsl" href="configuration.xsl"?>
<configuration>

  <property>
    <name>mapreduce.framework.name</name>
    <value>yarn</value>
  </property>

  <property>
    <name>mapreduce.application.classpath</name>
    <value>$HADOOP_MAPRED_HOME/share/hadoop/mapreduce/*:$HADOOP_MAPRED_HOME/share/hadoop/mapreduce/lib/*</value>
  </property>

</configuration>
EOF
```

#### hadoop-env.sh (NameNode)

```bash
cat > ~/hadoop-conf/namenode/hadoop-env.sh << EOF
export JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64
export HADOOP_HOME=$HOME/hadoop
export HADOOP_PID_DIR=$HOME/hadoop-pids/namenode
export HADOOP_LOG_DIR=$HOME/hadoop-logs/namenode
EOF
chmod 755 ~/hadoop-conf/namenode/hadoop-env.sh
```

#### workers

```bash
echo "localhost" > ~/hadoop-conf/namenode/workers
```

---

### 12-C. DataNode-0 설정 — `[SSD]` (`~/hadoop-conf/dn0/`)

HOT 데이터를 저장하는 ALL_SSD 정책 대상 DataNode다.

```bash
# core-site.xml
cat > ~/hadoop-conf/dn0/core-site.xml << 'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
  <property>
    <name>fs.defaultFS</name>
    <value>hdfs://localhost:9000</value>
  </property>
</configuration>
EOF

# hdfs-site.xml
cat > ~/hadoop-conf/dn0/hdfs-site.xml << 'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<configuration>

  <property>
    <name>dfs.datanode.data.dir</name>
    <value>[SSD]HOMEDIR/hadoop-data/dn0-ssd</value>
  </property>

  <property>
    <name>dfs.datanode.address</name>
    <value>0.0.0.0:9866</value>
  </property>

  <property>
    <name>dfs.datanode.http.address</name>
    <value>0.0.0.0:9864</value>
  </property>

  <property>
    <name>dfs.datanode.ipc.address</name>
    <value>0.0.0.0:9867</value>
  </property>

  <property>
    <name>dfs.namenode.datanode.registration.ip-hostname-check</name>
    <value>false</value>
  </property>

  <property>
    <name>dfs.replication</name>
    <value>1</value>
  </property>

  <property>
    <name>dfs.storage.policy.enabled</name>
    <value>true</value>
  </property>

</configuration>
EOF
sed -i "s|HOMEDIR|$HOME|g" ~/hadoop-conf/dn0/hdfs-site.xml

# hadoop-env.sh
cat > ~/hadoop-conf/dn0/hadoop-env.sh << EOF
export JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64
export HADOOP_HOME=$HOME/hadoop
export HADOOP_PID_DIR=$HOME/hadoop-pids/dn0
export HADOOP_LOG_DIR=$HOME/hadoop-logs/dn0
EOF
chmod 755 ~/hadoop-conf/dn0/hadoop-env.sh
```

---

### 12-D. DataNode-1 설정 — `[DISK]` (`~/hadoop-conf/dn1/`)

WARM 데이터를 저장하는 DISK 타입 DataNode다. ONE_SSD 정책은 SSD 1개 + DISK 1개를 요구하므로, dn0의 SSD와 dn1의 DISK가 조합되어 ONE_SSD 정책을 구현한다.

```bash
# core-site.xml
cat > ~/hadoop-conf/dn1/core-site.xml << 'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
  <property>
    <name>fs.defaultFS</name>
    <value>hdfs://localhost:9000</value>
  </property>
</configuration>
EOF

# hdfs-site.xml
cat > ~/hadoop-conf/dn1/hdfs-site.xml << 'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<configuration>

  <property>
    <name>dfs.datanode.data.dir</name>
    <value>[DISK]HOMEDIR/hadoop-data/dn1-onessd</value>
  </property>

  <property>
    <name>dfs.datanode.address</name>
    <value>0.0.0.0:9876</value>
  </property>

  <property>
    <name>dfs.datanode.http.address</name>
    <value>0.0.0.0:9874</value>
  </property>

  <property>
    <name>dfs.datanode.ipc.address</name>
    <value>0.0.0.0:9877</value>
  </property>

  <property>
    <name>dfs.namenode.datanode.registration.ip-hostname-check</name>
    <value>false</value>
  </property>

  <property>
    <name>dfs.replication</name>
    <value>1</value>
  </property>

  <property>
    <name>dfs.storage.policy.enabled</name>
    <value>true</value>
  </property>

</configuration>
EOF
sed -i "s|HOMEDIR|$HOME|g" ~/hadoop-conf/dn1/hdfs-site.xml

# hadoop-env.sh
cat > ~/hadoop-conf/dn1/hadoop-env.sh << EOF
export JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64
export HADOOP_HOME=$HOME/hadoop
export HADOOP_PID_DIR=$HOME/hadoop-pids/dn1
export HADOOP_LOG_DIR=$HOME/hadoop-logs/dn1
EOF
chmod 755 ~/hadoop-conf/dn1/hadoop-env.sh
```

---

### 12-E. DataNode-2 설정 — `[ARCHIVE]` (`~/hadoop-conf/dn2/`)

COLD 데이터를 저장하는 ARCHIVE 타입 DataNode다.

```bash
# core-site.xml
cat > ~/hadoop-conf/dn2/core-site.xml << 'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
  <property>
    <name>fs.defaultFS</name>
    <value>hdfs://localhost:9000</value>
  </property>
</configuration>
EOF

# hdfs-site.xml
cat > ~/hadoop-conf/dn2/hdfs-site.xml << 'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<configuration>

  <property>
    <name>dfs.datanode.data.dir</name>
    <value>[ARCHIVE]HOMEDIR/hadoop-data/dn2-archive</value>
  </property>

  <property>
    <name>dfs.datanode.address</name>
    <value>0.0.0.0:9886</value>
  </property>

  <property>
    <name>dfs.datanode.http.address</name>
    <value>0.0.0.0:9884</value>
  </property>

  <property>
    <name>dfs.datanode.ipc.address</name>
    <value>0.0.0.0:9887</value>
  </property>

  <property>
    <name>dfs.namenode.datanode.registration.ip-hostname-check</name>
    <value>false</value>
  </property>

  <property>
    <name>dfs.replication</name>
    <value>1</value>
  </property>

  <property>
    <name>dfs.storage.policy.enabled</name>
    <value>true</value>
  </property>

</configuration>
EOF
sed -i "s|HOMEDIR|$HOME|g" ~/hadoop-conf/dn2/hdfs-site.xml

# hadoop-env.sh
cat > ~/hadoop-conf/dn2/hadoop-env.sh << EOF
export JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64
export HADOOP_HOME=$HOME/hadoop
export HADOOP_PID_DIR=$HOME/hadoop-pids/dn2
export HADOOP_LOG_DIR=$HOME/hadoop-logs/dn2
EOF
chmod 755 ~/hadoop-conf/dn2/hadoop-env.sh
```

---

### 12-F. External SPS 설정 (`~/hadoop-conf/sps/`)

SPS는 NameNode와 동일한 core-site.xml·hdfs-site.xml을 사용하되, PID·로그 디렉터리만 분리한다.

```bash
cp ~/hadoop-conf/namenode/core-site.xml   ~/hadoop-conf/sps/
cp ~/hadoop-conf/namenode/hdfs-site.xml   ~/hadoop-conf/sps/
cp ~/hadoop-conf/namenode/log4j.properties ~/hadoop-conf/sps/

cat > ~/hadoop-conf/sps/hadoop-env.sh << EOF
export JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64
export HADOOP_HOME=$HOME/hadoop
export HADOOP_PID_DIR=$HOME/hadoop-pids/sps
export HADOOP_LOG_DIR=$HOME/hadoop-logs/sps
EOF
chmod 755 ~/hadoop-conf/sps/hadoop-env.sh
```

---

### 12-G. 설정 파일 구성 최종 확인

```bash
echo "=== namenode ===" && ls ~/hadoop-conf/namenode/
echo "=== dn0 ===" && ls ~/hadoop-conf/dn0/
echo "=== dn1 ===" && ls ~/hadoop-conf/dn1/
echo "=== dn2 ===" && ls ~/hadoop-conf/dn2/
echo "=== sps ===" && ls ~/hadoop-conf/sps/
```

예상 출력:

```
=== namenode ===
capacity-scheduler.xml  core-site.xml  hadoop-env.sh  hdfs-site.xml
log4j.properties  mapred-site.xml  workers  yarn-site.xml

=== dn0 ===
core-site.xml  hadoop-env.sh  hdfs-site.xml  log4j.properties

=== dn1 ===
core-site.xml  hadoop-env.sh  hdfs-site.xml  log4j.properties

=== dn2 ===
core-site.xml  hadoop-env.sh  hdfs-site.xml  log4j.properties

=== sps ===
core-site.xml  hadoop-env.sh  hdfs-site.xml  log4j.properties
```

경로 치환이 올바른지 검증한다.

```bash
grep -r "HOMEDIR\|PLACEHOLDER\|home/hadoop" ~/hadoop-conf/
# 아무 출력도 없어야 한다
```

---

## 13. NameNode 포맷

> **경고**: 최초 1회만 수행한다. 이미 포맷한 상태에서 다시 실행하면 기존 HDFS 데이터가 모두 삭제된다.

```bash
HADOOP_CONF_DIR=~/hadoop-conf/namenode hdfs namenode -format
```

중간에 다음 질문이 나오면 `Y`를 입력한다.

```
Re-format filesystem in Storage Directory ... ? (Y or N) Y
```

마지막 출력에 `Exiting with status 0`이 있으면 성공이다.

---

## 14. 시작·종료 스크립트 작성

### 14-1. 시작 스크립트

```bash
cat > ~/hadoop-start.sh << 'SCRIPT'
#!/usr/bin/env bash

echo "=== SSH 서비스 시작 ==="
sudo service ssh start 2>/dev/null || true

echo "=== NameNode 기동 ==="
HADOOP_CONF_DIR=$HOME/hadoop-conf/namenode hdfs --daemon start namenode
echo "  -> NameNode 안정화 대기 (15초)..."
sleep 15

if ! jps | grep -q NameNode; then
  echo "[ERROR] NameNode 기동 실패. 로그를 확인하세요:"
  ls -t $HOME/hadoop-logs/namenode/*.log 2>/dev/null | head -1 | xargs tail -30
  exit 1
fi
echo "  -> NameNode 확인 OK"

echo "=== DataNode 3개 기동 ==="
HADOOP_CONF_DIR=$HOME/hadoop-conf/dn0 hdfs --daemon start datanode
sleep 3
HADOOP_CONF_DIR=$HOME/hadoop-conf/dn1 hdfs --daemon start datanode
sleep 3
HADOOP_CONF_DIR=$HOME/hadoop-conf/dn2 hdfs --daemon start datanode
sleep 5

DN_COUNT=$(jps | grep -c DataNode || true)
echo "  -> DataNode $DN_COUNT / 3 기동 확인"
if [ "$DN_COUNT" -lt 3 ]; then
  echo "[WARN] DataNode가 3개 미만입니다."
  echo "  tail -30 $HOME/hadoop-logs/dn0/*.log"
  echo "  tail -30 $HOME/hadoop-logs/dn1/*.log"
  echo "  tail -30 $HOME/hadoop-logs/dn2/*.log"
fi

echo "=== YARN ResourceManager 기동 ==="
HADOOP_CONF_DIR=$HOME/hadoop-conf/namenode yarn --daemon start resourcemanager
echo "  -> ResourceManager 안정화 대기 (10초)..."
sleep 10

if ! jps | grep -q ResourceManager; then
  echo "[ERROR] ResourceManager 기동 실패. 로그를 확인하세요:"
  ls -t $HOME/hadoop-logs/namenode/yarn-*-resourcemanager-*.log 2>/dev/null | \
    head -1 | xargs tail -30
  exit 1
fi
echo "  -> ResourceManager 확인 OK"

echo "=== YARN NodeManager 기동 ==="
HADOOP_CONF_DIR=$HOME/hadoop-conf/namenode yarn --daemon start nodemanager
sleep 8

if ! jps | grep -q NodeManager; then
  echo "[WARN] NodeManager 기동 실패. 로그를 확인하세요:"
  ls -t $HOME/hadoop-logs/namenode/yarn-*-nodemanager-*.log 2>/dev/null | \
    head -1 | xargs tail -30
fi

echo "=== External SPS 기동 ==="
echo "  -> NameNode Safe Mode 해제 대기 중..."
HADOOP_CONF_DIR=$HOME/hadoop-conf/namenode hdfs dfsadmin -safemode wait
jps | grep -i 'StoragePolicySatisfier' | awk '{print $1}' | xargs kill -9 2>/dev/null || true
# 이전에 비정상 종료로 인해 HDFS에 남아있는 모든 잠금 파일(lock) 강제 삭제 (SPS, Balancer 등)
HADOOP_CONF_DIR=$HOME/hadoop-conf/namenode hdfs dfs -rm -f /system/*.id 2>/dev/null || true
HADOOP_CONF_DIR=$HOME/hadoop-conf/sps hdfs --daemon start sps 2>/dev/null || \
  echo "[WARN] SPS 기동 실패 (hdfs-site.xml external 모드 설정 확인 필요)"
sleep 3

echo ""
echo "=== 기동 완료 ==="
jps | sort

echo ""
echo "  HDFS NameNode   : http://localhost:9870"
echo "  YARN ResourceMgr: http://localhost:8088"
SCRIPT

chmod +x ~/hadoop-start.sh
```

### 14-2. 종료 스크립트

```bash
cat > ~/hadoop-stop.sh << 'SCRIPT'
#!/usr/bin/env bash

echo "=== Auto-Tiering YARN 서비스 중지 ==="
HADOOP_CONF_DIR=$HOME/hadoop-conf/namenode \
  yarn app -stop hdfs-auto-tiering 2>/dev/null || true

echo "=== External SPS 종료 ==="
HADOOP_CONF_DIR=$HOME/hadoop-conf/sps \
  hdfs --daemon stop sps 2>/dev/null || true

echo "=== YARN NodeManager 종료 ==="
HADOOP_CONF_DIR=$HOME/hadoop-conf/namenode \
  yarn --daemon stop nodemanager 2>/dev/null || true

echo "=== YARN ResourceManager 종료 ==="
HADOOP_CONF_DIR=$HOME/hadoop-conf/namenode \
  yarn --daemon stop resourcemanager 2>/dev/null || true

echo "=== DataNode 3개 종료 ==="
HADOOP_CONF_DIR=$HOME/hadoop-conf/dn2 hdfs --daemon stop datanode 2>/dev/null || true
HADOOP_CONF_DIR=$HOME/hadoop-conf/dn1 hdfs --daemon stop datanode 2>/dev/null || true
HADOOP_CONF_DIR=$HOME/hadoop-conf/dn0 hdfs --daemon stop datanode 2>/dev/null || true

echo "=== NameNode 종료 ==="
HADOOP_CONF_DIR=$HOME/hadoop-conf/namenode \
  hdfs --daemon stop namenode 2>/dev/null || true

sleep 3
echo ""
echo "=== 종료 완료 ==="
jps
SCRIPT

chmod +x ~/hadoop-stop.sh
```

---

## 15. 클러스터 기동 및 확인

### 15-1. 시작

```bash
~/hadoop-start.sh
```

### 15-2. 프로세스 확인

```bash
jps | sort
```

다음 7개(+Jps)가 모두 있어야 한다.

```
XXXXX DataNode
XXXXX DataNode
XXXXX DataNode
XXXXX NameNode
XXXXX NodeManager
XXXXX ResourceManager
XXXXX StoragePolicySatisfier
XXXXX Jps
```

### 15-3. HDFS DataNode 스토리지 타입 확인

```bash
HADOOP_CONF_DIR=~/hadoop-conf/namenode hdfs dfsadmin -report
```

`Live datanodes (3)` 이후 각 노드의 `Storage type`이 `SSD`, `DISK`, `ARCHIVE`로 각각 표시되어야 한다.

### 15-4. YARN 노드 확인

```bash
HADOOP_CONF_DIR=~/hadoop-conf/namenode yarn node -list
```

`RUNNING` 상태 노드 1개가 표시되면 정상이다.

### 15-5. YARN Service 프레임워크 의존성 수동 업로드 (버그 우회)

Hadoop 3.x YARN의 기본 의존성 파일 자동 업로드 로직에 버그(압축 실패, 경로 누락 등)가 존재하므로, 정상적인 `service-dep.tar.gz`를 수동으로 생성하여 HDFS에 업로드해야 한다.
**주의**: 압축 시 디렉터리 구조 없이 모든 `jar` 파일이 최상단(root)에 위치하도록 평탄화(Flatten)해야 `Application Master`가 정상 기동된다.

```bash
# 1. 모든 jar를 평탄화하여 모을 임시 폴더 생성
mkdir -p ~/tmp_yarn_jars
rm -rf ~/tmp_yarn_jars/*

# 2. YARN AM 구동에 필요한 모든 핵심/의존성 jar 파일을 한 곳으로 복사 (오류 무시)
cp ~/hadoop/share/hadoop/common/*.jar ~/tmp_yarn_jars/ 2>/dev/null
cp ~/hadoop/share/hadoop/common/lib/*.jar ~/tmp_yarn_jars/ 2>/dev/null
cp ~/hadoop/share/hadoop/hdfs/*.jar ~/tmp_yarn_jars/ 2>/dev/null
cp ~/hadoop/share/hadoop/hdfs/lib/*.jar ~/tmp_yarn_jars/ 2>/dev/null
cp ~/hadoop/share/hadoop/yarn/*.jar ~/tmp_yarn_jars/ 2>/dev/null
cp ~/hadoop/share/hadoop/yarn/lib/*.jar ~/tmp_yarn_jars/ 2>/dev/null

# 3. 디렉터리 구조 없이 최상단에 압축
cd ~/tmp_yarn_jars
tar -czf ~/service-dep.tar.gz *.jar

# 4. HDFS 업로드 경로 생성 및 업로드
HADOOP_CONF_DIR=~/hadoop-conf/namenode hdfs dfs -mkdir -p /user/dsc/.yarn/system/services/
HADOOP_CONF_DIR=~/hadoop-conf/namenode hdfs storagepolicies -setStoragePolicy -path /user/dsc/.yarn -policy ALL_SSD 2>/dev/null || true
HADOOP_CONF_DIR=~/hadoop-conf/namenode hdfs dfs -rm -f /user/dsc/.yarn/system/services/service-dep.tar.gz
HADOOP_CONF_DIR=~/hadoop-conf/namenode hdfs dfs -put ~/service-dep.tar.gz /user/dsc/.yarn/system/services/
HADOOP_CONF_DIR=~/hadoop-conf/namenode hdfs storagepolicies -setStoragePolicy -path /user/dsc/.yarn/system/services/service-dep.tar.gz -policy ALL_SSD 2>/dev/null || true
HADOOP_CONF_DIR=~/hadoop-conf/namenode hdfs storagepolicies -satisfyStoragePolicy -path /user/dsc/.yarn/system/services/service-dep.tar.gz 2>/dev/null || true

# 5. 임시 찌꺼기 정리
rm -rf ~/tmp_yarn_jars ~/service-dep.tar.gz
cd ~
```

업로드 후, 본 문서의 `yarn-site.xml` 설정에 명시된 `yarn.service.framework.path` 속성을 통해 YARN이 이 파일을 참조하게 된다.

### 15-6. Web UI 확인

Windows 브라우저에서 접속한다.

| URL | 확인 항목 |
|---|---|
| http://localhost:9870 | Datanodes 탭 → 3개 노드 표시 |
| http://localhost:8088 | Nodes 탭 → 1개 노드 표시, Services 탭 존재 |
| http://localhost:9864 | DataNode-0 (SSD) 정보 |
| http://localhost:9874 | DataNode-1 (DISK) 정보 |
| http://localhost:9884 | DataNode-2 (ARCHIVE) 정보 |

---

## 16. External SPS 구성

External SPS(Storage Policy Satisfier)는 `satisfyStoragePolicy` API 호출 후 실제 블록을 목표 스토리지 타입으로 이동시키는 데몬이다. NameNode 내부에서 동작하는 Internal SPS와 달리, External SPS는 별도 프로세스로 실행되어 NameNode 부하를 분리한다.

`~/hadoop-start.sh`에 이미 SPS 기동이 포함되어 있다. 별도로 기동해야 할 경우:

```bash
# NameNode에 external 모드 통지 (동적 reconfig)
HADOOP_CONF_DIR=~/hadoop-conf/namenode \
  hdfs dfsadmin -reconfig namenode localhost:9000 start

# 완료 확인
HADOOP_CONF_DIR=~/hadoop-conf/namenode \
  hdfs dfsadmin -reconfig namenode localhost:9000 status

# SPS 데몬 기동
HADOOP_CONF_DIR=~/hadoop-conf/sps hdfs --daemon start sps

# 기동 확인
jps | grep StoragePolicySatisfier
```

---

## 17. 스토리지 정책 동작 검증

YARN 서비스 배포 전에 HDFS 스토리지 정책과 SPS가 정상 동작하는지 확인한다.

### 17-1. HDFS 기본 디렉터리 및 정책 설정

```bash
HADOOP_CONF_DIR=~/hadoop-conf/namenode hdfs dfs -mkdir -p /user/$(whoami)
HADOOP_CONF_DIR=~/hadoop-conf/namenode hdfs dfs -mkdir -p /test/hot
HADOOP_CONF_DIR=~/hadoop-conf/namenode hdfs dfs -mkdir -p /test/cold

# ALL_SSD 정책 설정
HADOOP_CONF_DIR=~/hadoop-conf/namenode \
  hdfs storagepolicies -setStoragePolicy -path /test/hot -policy ALL_SSD

# COLD 정책 설정
HADOOP_CONF_DIR=~/hadoop-conf/namenode \
  hdfs storagepolicies -setStoragePolicy -path /test/cold -policy COLD
```

### 17-2. 테스트 파일 업로드

```bash
dd if=/dev/urandom of=/tmp/testfile.dat bs=1M count=50 2>/dev/null

HADOOP_CONF_DIR=~/hadoop-conf/namenode \
  hdfs dfs -put /tmp/testfile.dat /test/hot/testfile.dat
```

### 17-3. 블록 위치 확인

```bash
HADOOP_CONF_DIR=~/hadoop-conf/namenode \
  hdfs fsck /test/hot/testfile.dat -files -blocks -locations
```

블록이 포트 `9867`(SSD DataNode)에 위치하면 정상이다.

### 17-4. 정책 변경 및 SPS 트리거

```bash
HADOOP_CONF_DIR=~/hadoop-conf/namenode \
  hdfs storagepolicies -setStoragePolicy \
  -path /test/hot/testfile.dat -policy COLD

HADOOP_CONF_DIR=~/hadoop-conf/namenode \
  hdfs storagepolicies -satisfyStoragePolicy \
  -path /test/hot/testfile.dat
```

### 17-5. 블록 이동 완료 확인 (30~60초 후)

```bash
sleep 45

HADOOP_CONF_DIR=~/hadoop-conf/namenode \
  hdfs fsck /test/hot/testfile.dat -files -blocks -locations
```

블록 위치가 포트 `9887`(ARCHIVE DataNode)로 변경되면 SPS 동작이 검증된 것이다.

### 17-6. 테스트 환경 정리

검증이 끝난 후 다음 장의 실 배포를 위해, 생성했던 HDFS 테스트 데이터와 로컬 임시 파일을 삭제하여 클러스터를 원상 복구한다.

```bash
# HDFS 테스트 디렉터리 삭제
HADOOP_CONF_DIR=~/hadoop-conf/namenode \
  hdfs dfs -rm -r -skipTrash /test/hot /test/cold 2>/dev/null || true

# 로컬 임시 파일 삭제
rm -f /tmp/testfile.dat 2>/dev/null || true
```

---

## 18. Auto-Tiering YARN 서비스 배포

본 장에서는 수동 배포 절차(18-1 ~ 18-8)와 GitHub Releases를 활용한 배포 자동화 및 E2E 테스트 절차(18-9 ~ 18-10)를 설명한다. 서비스 이름과 jar 파일명, HDFS 경로 등은 모두 `hdfs-auto-tiering`으로 통일하여 사용한다.

### 18-1. HDFS 애플리케이션 디렉터리 준비

```bash
HADOOP_CONF_DIR=~/hadoop-conf/namenode hdfs dfs -mkdir -p /apps/hdfs-auto-tiering/lib
HADOOP_CONF_DIR=~/hadoop-conf/namenode hdfs dfs -mkdir -p /apps/hdfs-auto-tiering/scripts
HADOOP_CONF_DIR=~/hadoop-conf/namenode hdfs dfs -mkdir -p /apps/hdfs-auto-tiering/config
```

### 18-2. 로컬 실행용 설정 파일 생성

로컬 테스트 및 YARN 서비스 내부에서 공통으로 사용할 애플리케이션 설정 파일(`hdfs-auto-tiering-config.yaml`)을 로컬 디렉터리에 먼저 생성한다.

```bash
cat > ~/hdfs-auto-tiering-config.yaml << 'EOF'
database:
  url: jdbc:postgresql://localhost:5432/dsc_tiering
  username: dsc
  password: dsc
  pool:
    maximumPoolSize: 8
    minimumIdle: 2

hdfs:
  fsDefaultName: hdfs://localhost:9000
  user: ""
  policyMapping:
    HOT: ALL_SSD
    WARM: ONE_SSD
    COLD: COLD

scoring:
  enabled: true
  intervalSeconds: 86400
  weightAccessTime: 0.5
  weightFileSize: 0.5
  localFsimageDir: /tmp/hdfs-auto-tiering-fsimage
  targetDirectories:
    - /test/auto-tiering-e2e
    - /test/scenario_e2e

scheduler:
  pollIntervalSeconds: 10
  windows:
    - name: daytime
      start: "09:00"
      end:   "18:00"
      batchSize: 50
      interBatchWaitMs: 5000
    - name: nighttime
      start: "18:00"
      end:   "09:00"
      batchSize: 500
      interBatchWaitMs: 1000
  concurrency: 8
  maxRetries: 3

tracker:
  pollIntervalSeconds: 45
  timeoutMinutes: 60
  batchSize: 20
  completionRatio: 0.95
  maxWorkers: 5
  nodenameSemaphore: 3
EOF
```

`targetDirectories`는 ScoringEngine이 처리할 HDFS 경로 화이트리스트다. 위 인프라 문서의 E2E/비용 검증 스크립트가 생성하는 `/test/auto-tiering-e2e`, `/test/scenario_e2e`가 빠지면 job이 생성되지 않아 테스트가 `NOT_CREATED` 상태로 대기한다.

### 18-3. YARN Service Framework 동작 사전 검증

실제 서비스 배포 전에 프레임워크 자체가 정상 동작하는지 확인한다.

```bash
cat > /tmp/sleeper-test.json << 'EOF'
{
  "name": "sleeper-test",
  "version": "1.0",
  "components": [
    {
      "name": "sleeper",
      "number_of_containers": 1,
      "launch_command": "sleep 9000",
      "resource": {
        "cpus": 1,
        "memory": "256"
      }
    }
  ]
}
EOF

# 단일 DISK 노드 환경에서 YARN 서비스 테스트(및 실제 서비스)를 통과하기 위해,
# YARN 프레임워크가 사용하는 HDFS 시스템 디렉터리를 SSD 노드로 우회 설정한다.
HADOOP_CONF_DIR=~/hadoop-conf/namenode hdfs dfs -mkdir -p /user/$(whoami)/.yarn
HADOOP_CONF_DIR=~/hadoop-conf/namenode hdfs storagepolicies -setStoragePolicy -path /user/$(whoami)/.yarn -policy ALL_SSD

HADOOP_CONF_DIR=~/hadoop-conf/namenode \
  yarn app -launch sleeper-test /tmp/sleeper-test.json

sleep 15

HADOOP_CONF_DIR=~/hadoop-conf/namenode \
  yarn app -status sleeper-test
```

`State: STABLE` 또는 `RUNNING`이 출력되면 정상이다.

```bash
# 1. 테스트용 앱 강제 종료
HADOOP_CONF_DIR=~/hadoop-conf/namenode yarn app -destroy sleeper-test

# (주의) 단일 DISK 노드 환경 제약으로 인해 실제 서비스 배포 시에도 .yarn 디렉터리는 ALL_SSD 정책을 유지해야 합니다.
# 절대 정책 해제(unsetStoragePolicy)를 수행하지 마세요.
```

### 18-4. 애플리케이션 배포 (GitHub Actions 자동 빌드)

서버(인프라) 환경에는 소스코드가 존재하지 않으므로, 개발 PC에서 직접 빌드하고 파일을 복사(`scp`)하는 대신 **GitHub Actions와 Releases**를 활용하여 빌드 및 배포를 100% 자동화합니다.

1. **로컬(개발 PC)에서 깃허브로 푸시 (자동 빌드 트리거)**
   - 로컬 PC에서는 메이븐(Maven)을 설치하거나 직접 빌드할 필요가 전혀 없습니다.
   - 코드를 수정한 후 `v1.0.0`과 같이 버전을 나타내는 태그(Tag)를 달아서 GitHub에 Push합니다.
   ```bash
   git tag v1.0.0
   git push origin v1.0.0
   ```
   - GitHub Actions가 자동으로 백그라운드에서 코드를 빌드하고, 완성된 `hdfs-auto-tiering.jar` 파일을 해당 릴리즈(Release)의 첨부파일로 알아서 업로드해 줍니다.

2. **서버 환경에서 배포 스크립트 실행**
   - GitHub에 릴리즈가 완료되면, 본 문서 하단의 **[18-10. GitHub Releases 기반 자동 배포 스크립트]**(`deploy-auto-tiering.sh`)를 서버에서 실행하기만 하면 끝입니다. 서버가 최신 릴리즈의 `jar` 파일을 다운로드하고 HDFS에 올려 배포를 완료합니다.


### 18-5. YARN Service 정의 파일 작성

fat jar 형태이므로 `java -jar` 명령으로 기동하며, 환경에 맞게 Java 11 경로를 지정한다.

```bash
cat > ~/hdfs-auto-tiering-service.json << EOF
{
  "name": "hdfs-auto-tiering",
  "version": "1.0",
  "components": [
    {
      "name": "tiering-daemon",
      "number_of_containers": 1,
      "launch_command": "java -Xmx1024m -jar ./hdfs-auto-tiering.jar ./hdfs-auto-tiering-config.yaml",
      "resource": {
        "cpus": 1,
        "memory": "1536"
      },
      "configuration": {
        "env": {
          "JAVA_HOME": "/usr/lib/jvm/java-11-openjdk-amd64",
          "HADOOP_CONF_DIR": "$HOME/hadoop-conf/namenode",
          "HADOOP_HOME": "$HOME/hadoop"
        },
        "files": [
          {
            "type": "STATIC",
            "dest_file": "hdfs-auto-tiering.jar",
            "src_file": "hdfs:///apps/hdfs-auto-tiering/lib/hdfs-auto-tiering.jar"
          },
          {
            "type": "STATIC",
            "dest_file": "hdfs-auto-tiering-config.yaml",
            "src_file": "hdfs:///apps/hdfs-auto-tiering/config/hdfs-auto-tiering-config.yaml"
          }
        ]
      },
      "restart_policy": "ALWAYS",
      "readiness_check": {
        "type": "DEFAULT"
      }
    }
  ]
}
EOF
```

### 18-6. (수동 배포 시) JAR 업로드 및 서비스 기동

> **중요 참고**: 18-4의 GitHub Actions 자동 배포를 이용하는 경우, 이 단계를 수동으로 실행하지 말고 **바로 18-10의 `deploy-auto-tiering.sh` 스크립트를 실행**하세요. 스크립트 내부에서 다운로드 및 업로드, 런칭을 모두 수행합니다.
> 아래는 스크립트를 사용하지 않고 100% 수동으로 배포할 때의 절차입니다.

```bash
# 1. 빌드한 JAR 파일과 설정 파일을 HDFS에 먼저 업로드
HADOOP_CONF_DIR=~/hadoop-conf/namenode hdfs dfs -put -f ~/hdfs-auto-tiering.jar /apps/hdfs-auto-tiering/lib/
HADOOP_CONF_DIR=~/hadoop-conf/namenode hdfs dfs -put -f ~/hdfs-auto-tiering-config.yaml /apps/hdfs-auto-tiering/config/

# 2. 서비스 런칭 (이미 존재한다는 오류 발생 시, yarn app -destroy hdfs-auto-tiering 후 재시도)
HADOOP_CONF_DIR=~/hadoop-conf/namenode \
  yarn app -launch hdfs-auto-tiering ~/hdfs-auto-tiering-service.json
```

### 18-7. 서비스 상태 확인

```bash
# CLI
HADOOP_CONF_DIR=~/hadoop-conf/namenode \
  yarn app -status hdfs-auto-tiering

# 목록
HADOOP_CONF_DIR=~/hadoop-conf/namenode \
  yarn app -list -appTypes YARN-SERVICE
```

http://localhost:8088 → **Services** 탭에서도 확인할 수 있다.

### 18-8. 내부 Scoring Engine 메커니즘 (FSImage OIV 파이프라인 연계)

새로운 아키텍처에서는 외부 파이썬 스크립트 대신 Java 컨테이너 내부의 `Scoring Worker`가 직접 FSImage를 처리합니다.

로직은 내부적으로 다음과 같이 동작합니다:
1. `DFSAdmin.fetchImage` API를 호출하여 NameNode로부터 최신 `fsimage`를 다운로드.
2. `OfflineImageViewerPB` API를 사용하여 메모리 상에서 이미지를 텍스트/객체 형태로 파싱.
3. 우선순위 가중치를 계산하고 PostgreSQL에 `PENDING` 상태로 일괄(Insert Batch) 삽입.

*(해당 과정은 데몬 내부에서 자동 수행되므로 사용자가 수동으로 개입할 필요가 없습니다.)*

### 18-8. 서비스 관리 명령

```bash
# 중지 (상태 보존)
HADOOP_CONF_DIR=~/hadoop-conf/namenode yarn app -stop hdfs-auto-tiering

# 재시작
HADOOP_CONF_DIR=~/hadoop-conf/namenode yarn app -start hdfs-auto-tiering

# 완전 삭제
HADOOP_CONF_DIR=~/hadoop-conf/namenode yarn app -destroy hdfs-auto-tiering
```

### 18-10. GitHub Releases 기반 자동 배포 스크립트

GitHub Releases에서 최신 jar 파일을 다운로드하여 HDFS에 통일된 이름(`hdfs-auto-tiering.jar`)으로 덮어쓰고, YARN 서비스를 재기동하는 스크립트이다.
원본 코드(`pom.xml` 등) 수정 없이 스크립트 내부에서 파일명을 통일하여 처리한다.

```bash
cat > ~/deploy-auto-tiering.sh << 'SCRIPT'
#!/usr/bin/env bash
set -euo pipefail

GITHUB_REPO="Sadturtleman/DSC"
HDFS_LIB_DIR="/apps/hdfs-auto-tiering/lib"
HDFS_CONFIG_DIR="/apps/hdfs-auto-tiering/config"
YARN_SERVICE_NAME="hdfs-auto-tiering"
SERVICE_JSON="$HOME/hdfs-auto-tiering-service.json"
DOWNLOAD_DIR="/tmp/hdfs-auto-tiering-deploy"
TARGET_JAR_NAME="hdfs-auto-tiering.jar"
LOCAL_CONFIG="$HOME/hdfs-auto-tiering-config.yaml"

export HADOOP_CONF_DIR="$HOME/hadoop-conf/namenode"

echo "GitHub 최신 릴리즈 정보 조회 중..."
# jq를 사용하여 최신 릴리즈의 첫 번째 jar 파일 다운로드 URL 획득
DOWNLOAD_URL=$(curl -s "https://api.github.com/repos/${GITHUB_REPO}/releases/latest" | \
  grep -o '"browser_download_url": *"[^"]*.jar"' | head -n 1 | cut -d '"' -f 4)

if [ -z "$DOWNLOAD_URL" ]; then
    echo "다운로드 URL을 찾을 수 없습니다."
    exit 1
fi

echo "다운로드 URL: ${DOWNLOAD_URL}"

mkdir -p "$DOWNLOAD_DIR"
HTTP_CODE=$(curl -sL -w "%{http_code}" -o "${DOWNLOAD_DIR}/${TARGET_JAR_NAME}" "$DOWNLOAD_URL")

if [ "$HTTP_CODE" != "200" ]; then
    echo "다운로드 실패 (HTTP ${HTTP_CODE}). URL: ${DOWNLOAD_URL}"
    exit 1
fi

echo "HDFS 디렉터리 준비 및 업로드"
hdfs dfs -mkdir -p "$HDFS_LIB_DIR"
hdfs dfs -mkdir -p "$HDFS_CONFIG_DIR"

# [긴급 패치 1] 배포 파일들을 dn0(SSD)에 쓰도록 강제
hdfs storagepolicies -setStoragePolicy -path /apps -policy ALL_SSD 2>/dev/null || true

# [긴급 패치 2] YARN이 백그라운드 서비스를 구동할 때 내부적으로 생성하는 메타데이터(.yarn) 파일들이
# 통신이 막혀있는 dn1(DISK)으로 가는 것을 막기 위해 사용자 홈 디렉터리도 ALL_SSD로 강제 지정
hdfs dfs -mkdir -p /user/dsc
hdfs storagepolicies -setStoragePolicy -path /user/dsc -policy ALL_SSD 2>/dev/null || true

hdfs dfs -put -f "${DOWNLOAD_DIR}/${TARGET_JAR_NAME}" "${HDFS_LIB_DIR}/${TARGET_JAR_NAME}"
hdfs storagepolicies -setStoragePolicy -path "${HDFS_LIB_DIR}/${TARGET_JAR_NAME}" -policy ALL_SSD 2>/dev/null || true
hdfs storagepolicies -satisfyStoragePolicy -path "${HDFS_LIB_DIR}/${TARGET_JAR_NAME}" 2>/dev/null || true

if [ -f "$LOCAL_CONFIG" ]; then
    hdfs dfs -put -f "$LOCAL_CONFIG" "${HDFS_CONFIG_DIR}/hdfs-auto-tiering-config.yaml"
    hdfs storagepolicies -setStoragePolicy -path "${HDFS_CONFIG_DIR}/hdfs-auto-tiering-config.yaml" -policy ALL_SSD 2>/dev/null || true
    hdfs storagepolicies -satisfyStoragePolicy -path "${HDFS_CONFIG_DIR}/hdfs-auto-tiering-config.yaml" 2>/dev/null || true
fi

if yarn app -status "$YARN_SERVICE_NAME" > /dev/null 2>&1; then
    echo "기존 YARN 서비스 중지 및 삭제"
    yarn app -stop "$YARN_SERVICE_NAME" 2>/dev/null || true
    sleep 3
    yarn app -destroy "$YARN_SERVICE_NAME" 2>/dev/null || true
    sleep 2
fi

echo "YARN 서비스 기동: ${YARN_SERVICE_NAME}"
yarn app -launch "$YARN_SERVICE_NAME" "$SERVICE_JSON"

echo "서비스 안정화 대기 (20초)"
sleep 20
yarn app -status "$YARN_SERVICE_NAME" 2>&1 | head -20

rm -rf "$DOWNLOAD_DIR"
echo "배포 완료"
SCRIPT

chmod +x ~/deploy-auto-tiering.sh
```

### 18-11. E2E 검증 테스트 자동화 스크립트

HDFS 파일 업로드, 정책 적용, FSImage 기반 ScoringEngine 적재, 스케줄러 기동 및 블록 이동 완료까지 자동 검증 후 생성된 테스트 데이터를 삭제(원상 복구)하는 스크립트이다.
배포 스크립트를 통해 HDFS에 업로드된 JAR 파일을 가져와 테스트를 진행한다.

```bash
cat > ~/test-auto-tiering.sh << 'SCRIPT'
#!/usr/bin/env bash
set -uo pipefail

export HADOOP_CONF_DIR="$HOME/hadoop-conf/namenode"
PSQL_CMD="psql -h localhost -U dsc -d dsc_tiering -qtA"
TEST_DIR="/test/auto-tiering-e2e"
TEST_FILE="${TEST_DIR}/testfile.dat"
LOCAL_TMP="/tmp/auto-tiering-e2e-testfile.dat"
SCHEDULER_PID=""
CONFIG_FILE="$HOME/hdfs-auto-tiering-config.yaml"
JAR_FILE="/tmp/hdfs-auto-tiering-e2e-test.jar"

cleanup() {
    echo "[단계] 테스트 환경 정리"
    if [ -n "$SCHEDULER_PID" ] && kill -0 "$SCHEDULER_PID" 2>/dev/null; then
        kill "$SCHEDULER_PID" 2>/dev/null || true
    fi
    $PSQL_CMD -c "DELETE FROM pending_jobs WHERE file_path LIKE '${TEST_DIR}%';" 2>/dev/null || true
    hdfs dfs -rm -r -skipTrash "$TEST_DIR" 2>/dev/null || true
    rm -f "$LOCAL_TMP" 2>/dev/null || true
    rm -f "$JAR_FILE" 2>/dev/null || true
}
trap cleanup EXIT

echo "[단계] 사전 점검 및 JAR/설정 파일 준비"
HDFS_CONFIG_PATH="/apps/hdfs-auto-tiering/config/hdfs-auto-tiering-config.yaml"
if [ ! -f "$CONFIG_FILE" ]; then
    echo " > HDFS에서 설정 파일 다운로드 중..."
    hdfs dfs -copyToLocal -f "$HDFS_CONFIG_PATH" "$CONFIG_FILE" 2>/dev/null || true
fi

if [ ! -f "$CONFIG_FILE" ]; then
    echo "[오류] HDFS 및 로컬에 설정 파일이 없습니다."
    exit 1
fi

echo " > HDFS 배포 경로에서 JAR 파일 가져오기..."
HDFS_JAR_PATH="/apps/hdfs-auto-tiering/lib/hdfs-auto-tiering.jar"
hdfs dfs -copyToLocal -f "$HDFS_JAR_PATH" "$JAR_FILE" 2>/dev/null || true

if [ ! -f "$JAR_FILE" ]; then
    echo "[오류] JAR 파일 가져오기 실패. 먼저 deploy-auto-tiering.sh를 통해 배포하십시오."
    exit 1
fi

echo "[1단계] 파일 업로드 및 ALL_SSD 정책 적용"
echo " > 기대 효과: 고성능 SSD 티어에 초기 데이터 배치"
dd if=/dev/urandom of="$LOCAL_TMP" bs=1M count=10 2>/dev/null
hdfs dfs -mkdir -p "$TEST_DIR"
hdfs storagepolicies -setStoragePolicy -path "$TEST_DIR" -policy ALL_SSD > /dev/null
hdfs dfs -put -f "$LOCAL_TMP" "$TEST_FILE"
hdfs storagepolicies -setStoragePolicy -path "$TEST_FILE" -policy ALL_SSD > /dev/null
hdfs storagepolicies -satisfyStoragePolicy -path "$TEST_FILE" > /dev/null 2>&1 || true
sleep 5

echo "[2단계] 초기 SSD 블록 위치 검증"
FSCK_BEFORE=$(hdfs fsck "$TEST_FILE" -files -blocks -locations 2>/dev/null)
if ! echo "$FSCK_BEFORE" | grep -E -q "9867|9866"; then
    echo "[오류] SSD 블록 위치 확인 실패."
    exit 1
fi
echo " > SSD 배치 확인 완료."

echo "[3단계] FSImage 동기화 및 메타데이터 오프로딩"
echo " > 목적: NameNode RPC 부하 없이 오프라인으로 메타데이터 분석"
OLD_ATIME=$(date -d '120 days ago' +%Y%m%d:%H%M%S)
hdfs dfs -touch -a -t "$OLD_ATIME" "$TEST_FILE"
hdfs dfsadmin -safemode enter >/dev/null
hdfs dfsadmin -saveNamespace >/dev/null
hdfs dfsadmin -safemode leave >/dev/null

echo "[4단계] 자동 이관을 위한 배치 스케줄러 기동"
java -jar "$JAR_FILE" "$CONFIG_FILE" > /tmp/tiering-test.log 2>&1 &
SCHEDULER_PID=$!

DISPATCHED="no"
for i in $(seq 1 60); do
    sleep 2
    STATUS=$($PSQL_CMD -c "SELECT status FROM pending_jobs WHERE file_path = '${TEST_FILE}' ORDER BY job_id DESC LIMIT 1;" 2>/dev/null || echo "UNKNOWN")
    if [ -z "$STATUS" ]; then STATUS="NOT_CREATED"; fi
    if [ "$STATUS" = "DISPATCHED" ] || [ "$STATUS" = "IN_PROGRESS" ] || [ "$STATUS" = "COMPLETED" ]; then
        DISPATCHED="yes"
        break
    fi
    echo " > 대기 중... 현재 상태: $STATUS"
done

if [ "$DISPATCHED" = "no" ]; then
    echo "[오류] 작업 처리 실패. Java 데몬 에러 로그:"
    cat /tmp/tiering-test.log
    exit 1
fi
echo " > 상태 전이 성공: $STATUS"

if kill -0 "$SCHEDULER_PID" 2>/dev/null; then
    kill "$SCHEDULER_PID" 2>/dev/null || true
fi
SCHEDULER_PID=""

echo "[5단계] External SPS를 통한 물리적 블록 이동 검증"
echo " > 목적: 비용 효율적인 ARCHIVE 티어로의 물리적 블록 마이그레이션 확인"
sleep 45
FSCK_AFTER=$(hdfs fsck "$TEST_FILE" -files -blocks -locations 2>/dev/null)
if ! echo "$FSCK_AFTER" | grep -E -q "9887|9886"; then
    echo "[오류] ARCHIVE 블록 위치 확인 실패."
    exit 1
fi
echo " > ARCHIVE 마이그레이션 확인 완료."
echo "[결과] 전체 E2E 테스트가 성공적으로 완료되었습니다."
exit 0
SCRIPT

chmod +x ~/test-auto-tiering.sh
```


---

## 19. 포트 참조표

| 서비스 | 역할 | 포트 |
|---|---|---|
| NameNode | RPC | 9000 |
| NameNode | Web UI | 9870 |
| DataNode-0 (SSD) | 데이터 전송 | 9866 |
| DataNode-0 (SSD) | Web UI | 9864 |
| DataNode-0 (SSD) | IPC | 9867 |
| DataNode-1 (DISK) | 데이터 전송 | 9876 |
| DataNode-1 (DISK) | Web UI | 9874 |
| DataNode-1 (DISK) | IPC | 9877 |
| DataNode-2 (ARCHIVE) | 데이터 전송 | 9886 |
| DataNode-2 (ARCHIVE) | Web UI | 9884 |
| DataNode-2 (ARCHIVE) | IPC | 9887 |
| ResourceManager | Web UI | 8088 |
| NodeManager | Web UI | 8042 |

---

## 20. 트러블슈팅

### DataNode가 3개 미만으로 기동됨

각 DataNode 로그에서 원인을 확인한다.

```bash
tail -50 ~/hadoop-logs/dn0/hadoop-$(whoami)-datanode-$(hostname).log
tail -50 ~/hadoop-logs/dn1/hadoop-$(whoami)-datanode-$(hostname).log
tail -50 ~/hadoop-logs/dn2/hadoop-$(whoami)-datanode-$(hostname).log
```

포트 충돌 여부를 확인한다.

```bash
ss -tlnp | grep -E "9864|9866|9867|9874|9876|9877|9884|9886|9887"
```

### HDFS가 Safe Mode에서 벗어나지 않음

```bash
HADOOP_CONF_DIR=~/hadoop-conf/namenode hdfs dfsadmin -safemode get
HADOOP_CONF_DIR=~/hadoop-conf/namenode hdfs dfsadmin -safemode leave
```

### ResourceManager 기동 실패 — Queue configuration 오류

`capacity-scheduler.xml`이 `~/hadoop-conf/namenode/`에 존재하는지, root.queues 속성이 정의되어 있는지 확인한다.

```bash
ls ~/hadoop-conf/namenode/capacity-scheduler.xml
grep "root.queues" ~/hadoop-conf/namenode/capacity-scheduler.xml
```

없거나 내용이 비어 있으면 섹션 12-B의 `capacity-scheduler.xml` 작성 단계를 다시 수행한다.

### NodeManager 기동 실패 — cgroup 오류

`yarn-site.xml`에 `DefaultContainerExecutor` 설정이 있는지 확인한다.

```bash
grep "container-executor.class" ~/hadoop-conf/namenode/yarn-site.xml
```

없으면 아래를 `yarn-site.xml`의 `<configuration>` 안에 추가한다.

```xml
<property>
  <name>yarn.nodemanager.container-executor.class</name>
  <value>org.apache.hadoop.yarn.server.nodemanager.DefaultContainerExecutor</value>
</property>
```

그 후 NodeManager를 재시작한다.

```bash
HADOOP_CONF_DIR=~/hadoop-conf/namenode yarn --daemon stop nodemanager
sleep 3
HADOOP_CONF_DIR=~/hadoop-conf/namenode yarn --daemon start nodemanager
```

### YARN Service Application 실행 시 tar.gz 추출 실패 오류 (파일 없음/압축 오류)

YARN 3.x의 기본 의존성 파일 자동 업로드 로직 버그로 인해 발생한다. (정상 압축 파일 대신 jar 파일 등을 잘못 생성하여 업로드함)
1. **15-5. YARN Service 프레임워크 의존성 수동 업로드 (버그 우회)** 단계를 수행하여 정상적인 평탄화된 `service-dep.tar.gz`를 HDFS에 직접 올린다.
2. `yarn-site.xml`에 `yarn.service.framework.path` 속성이 수동 업로드 경로로 명시되어 있는지 확인한다.

```bash
grep "yarn.service.framework.path" ~/hadoop-conf/namenode/yarn-site.xml
```

없다면 `INFRA.md`의 `yarn-site.xml` 설정 부분을 참고하여 추가하고, YARN 데몬(resourcemanager, nodemanager)을 모두 재시작한다.

### YARN Service Framework API 활성화 오류 (`yarn app -launch` 실패)

ResourceManager에 API 서비스가 활성화되었는지 확인한다.

```bash
grep "api-service" ~/hadoop-conf/namenode/yarn-site.xml
```

`true`로 설정되어 있어야 한다. ResourceManager를 재시작한 후 재시도한다.

```bash
HADOOP_CONF_DIR=~/hadoop-conf/namenode yarn --daemon stop resourcemanager
sleep 5
HADOOP_CONF_DIR=~/hadoop-conf/namenode yarn --daemon start resourcemanager
sleep 10
HADOOP_CONF_DIR=~/hadoop-conf/namenode \
  yarn app -launch hdfs-auto-tiering ~/hdfs-auto-tiering-service.json
```

### SPS 기동 후 블록이 이동하지 않음

DataNode의 실제 스토리지 타입을 확인한다.

```bash
HADOOP_CONF_DIR=~/hadoop-conf/namenode hdfs dfsadmin -report | grep -A3 "Name:"
```

SPS 로그에서 오류를 확인한다.

```bash
tail -50 ~/hadoop-logs/sps/hadoop-$(whoami)-sps-$(hostname).log
```

### WSL2 재시작 후 서비스 모두 꺼짐

WSL2는 Windows 재부팅 또는 `wsl --shutdown` 시 모든 프로세스가 종료된다. Ubuntu 터미널을 열고 아래를 실행한다.

```bash
~/hadoop-start.sh
```

### 처음부터 완전히 초기화해야 할 때

```bash
~/hadoop-stop.sh 2>/dev/null || true
jps | grep -v Jps | awk '{print $1}' | xargs kill -9 2>/dev/null || true

rm -rf ~/hadoop-data ~/hadoop-pids ~/hadoop-logs ~/hadoop-yarn

mkdir -p ~/hadoop-data/{namenode,dn0-ssd,dn1-onessd,dn2-archive}
mkdir -p ~/hadoop-data/namenode/checkpoint
mkdir -p ~/hadoop-pids/{namenode,yarn,sps,dn0,dn1,dn2}
mkdir -p ~/hadoop-logs/{namenode,yarn,sps,dn0,dn1,dn2}
mkdir -p ~/hadoop-yarn/{local,log}

HADOOP_CONF_DIR=~/hadoop-conf/namenode hdfs namenode -format

~/hadoop-start.sh
```

---

## 21. 오토티어링 효용 검증 (비용 절감 리포트)

오토티어링 시스템이 실제로 비즈니스에 얼마나 기여하고 있는지(스토리지 비용 절감 및 공간 최적화 비율)를 직관적으로 보여주는 리포팅 스크립트입니다. DB의 처리 이력을 바탕으로 **실제 AWS EBS 스토리지 단가표**를 적용하여 월간 절감액을 산출합니다.

```bash
cat > ~/test-auto-tiering-savings.sh << 'SCRIPT'
#!/usr/bin/env bash
set -u

# AWS EBS 기반 실제 클라우드 스토리지 단가표 (월간 GB당 비용, us-east-1 기준)
# HOT (gp3 - 범용 SSD): $0.08 / GB
# WARM (st1 - 처리량 최적화 HDD): $0.045 / GB
# COLD (sc1 - 콜드 HDD): $0.015 / GB

export HADOOP_CONF_DIR="$HOME/hadoop-conf/namenode"
export HADOOP_HOME="$HOME/hadoop"
PSQL_CMD="psql -h localhost -U dsc -d dsc_tiering -qtA"
SCENARIO=${1:-"CURRENT"}
LOCAL_TMP_DIR="/tmp/tiering_scenario_data"
HDFS_TEST_DIR="/test/scenario_e2e"

# 스크립트 강제 종료(Ctrl+C) 시에도 반드시 쓰레기 데이터를 삭제하도록 trap 설정
trap 'echo -e "\n[시스템] 종료 신호 감지. 물리적 테스트 데이터를 정리합니다..."; rm -rf "$LOCAL_TMP_DIR" 2>/dev/null; hdfs dfs -rm -r -skipTrash "$HDFS_TEST_DIR" 2>/dev/null || true' INT TERM EXIT

if [ "$SCENARIO" != "CURRENT" ]; then
    echo "=========================================================="
    echo "[시나리오 ${SCENARIO}] HDFS 자동 티어링 데이터 이동 테스트"
    echo "목적: NameNode 메타데이터 스캔 오프로딩 및 블록 배치 최적화"
    mkdir -p "$LOCAL_TMP_DIR"
    hdfs dfs -rm -r -skipTrash "$HDFS_TEST_DIR" 2>/dev/null || true
    hdfs dfs -mkdir -p "$HDFS_TEST_DIR"
    hdfs storagepolicies -setStoragePolicy -path "$HDFS_TEST_DIR" -policy ALL_SSD >/dev/null
    $PSQL_CMD -c "DELETE FROM pending_jobs WHERE file_path LIKE '${HDFS_TEST_DIR}%';" > /dev/null 2>&1
    WARM_ATIME=$(date -d '60 days ago' +%Y%m%d:%H%M%S)
    COLD_ATIME=$(date -d '200 days ago' +%Y%m%d:%H%M%S)

    if [ "$SCENARIO" == "A" ]; then
        echo " - 2GB 로그 데이터 생성 중 (1GB HOT, 1GB COLD) ..."
        dd if=/dev/zero of="${LOCAL_TMP_DIR}/log_hot.dat" bs=1M count=1024 2>/dev/null
        dd if=/dev/zero of="${LOCAL_TMP_DIR}/log_cold.dat" bs=1M count=1024 2>/dev/null
        hdfs dfs -put -f "${LOCAL_TMP_DIR}/log_hot.dat" "$HDFS_TEST_DIR/"
        hdfs dfs -put -f "${LOCAL_TMP_DIR}/log_cold.dat" "$HDFS_TEST_DIR/"
        hdfs storagepolicies -setStoragePolicy -path "${HDFS_TEST_DIR}/log_hot.dat" -policy ALL_SSD >/dev/null
        hdfs storagepolicies -setStoragePolicy -path "${HDFS_TEST_DIR}/log_cold.dat" -policy ALL_SSD >/dev/null
        hdfs storagepolicies -satisfyStoragePolicy -path "${HDFS_TEST_DIR}/log_hot.dat" >/dev/null 2>&1 || true
        hdfs storagepolicies -satisfyStoragePolicy -path "${HDFS_TEST_DIR}/log_cold.dat" >/dev/null 2>&1 || true

        # Access Time을 과거(예: 2023년)로 조작하여 ScoringEngine이 COLD로 판정하게 만듦
        hdfs dfs -touch -a -t "$COLD_ATIME" "${HDFS_TEST_DIR}/log_cold.dat"

    elif [ "$SCENARIO" == "B" ]; then
        echo " - 3GB ML 데이터 레이크 데이터 생성 중 (HOT/WARM/COLD) ..."
        dd if=/dev/zero of="${LOCAL_TMP_DIR}/ml_hot.dat" bs=1M count=1024 2>/dev/null
        dd if=/dev/zero of="${LOCAL_TMP_DIR}/ml_warm.dat" bs=1M count=1024 2>/dev/null
        dd if=/dev/zero of="${LOCAL_TMP_DIR}/ml_cold.dat" bs=1M count=1024 2>/dev/null
        hdfs dfs -put -f "${LOCAL_TMP_DIR}/ml_hot.dat" "$HDFS_TEST_DIR/"
        hdfs dfs -put -f "${LOCAL_TMP_DIR}/ml_warm.dat" "$HDFS_TEST_DIR/"
        hdfs dfs -put -f "${LOCAL_TMP_DIR}/ml_cold.dat" "$HDFS_TEST_DIR/"
        hdfs storagepolicies -setStoragePolicy -path "${HDFS_TEST_DIR}/ml_hot.dat" -policy ALL_SSD >/dev/null
        hdfs storagepolicies -setStoragePolicy -path "${HDFS_TEST_DIR}/ml_warm.dat" -policy ALL_SSD >/dev/null
        hdfs storagepolicies -setStoragePolicy -path "${HDFS_TEST_DIR}/ml_cold.dat" -policy ALL_SSD >/dev/null
        hdfs storagepolicies -satisfyStoragePolicy -path "${HDFS_TEST_DIR}/ml_hot.dat" >/dev/null 2>&1 || true
        hdfs storagepolicies -satisfyStoragePolicy -path "${HDFS_TEST_DIR}/ml_warm.dat" >/dev/null 2>&1 || true
        hdfs storagepolicies -satisfyStoragePolicy -path "${HDFS_TEST_DIR}/ml_cold.dat" >/dev/null 2>&1 || true

        # WARM 조건(30~90일), COLD 조건(90일 이상)에 맞게 시간 조작
        # 테스트 편의상 WARM은 60일 전, COLD는 200일 전으로 강제 지정하기 위해 구체적인 과거 시간 사용
        hdfs dfs -touch -a -t "$WARM_ATIME" "${HDFS_TEST_DIR}/ml_warm.dat"
        hdfs dfs -touch -a -t "$COLD_ATIME" "${HDFS_TEST_DIR}/ml_cold.dat"

    elif [ "$SCENARIO" == "C" ]; then
        echo " - 5GB 컴플라이언스 아카이브 데이터 생성 중 (전체 COLD) ..."
        for i in {1..5}; do
            dd if=/dev/zero of="${LOCAL_TMP_DIR}/comp_${i}.dat" bs=1M count=1024 2>/dev/null
            hdfs dfs -put -f "${LOCAL_TMP_DIR}/comp_${i}.dat" "$HDFS_TEST_DIR/"
            hdfs storagepolicies -setStoragePolicy -path "${HDFS_TEST_DIR}/comp_${i}.dat" -policy ALL_SSD >/dev/null
            hdfs storagepolicies -satisfyStoragePolicy -path "${HDFS_TEST_DIR}/comp_${i}.dat" >/dev/null 2>&1 || true
            hdfs dfs -touch -a -t "$COLD_ATIME" "${HDFS_TEST_DIR}/comp_${i}.dat"
        done
    else
        echo "[오류] 잘못된 시나리오가 선택되었습니다. (A, B, C 중 택 1)"
        exit 1
    fi

    echo " - 오프라인 스코어링 엔진을 위한 NameNode FSImage 동기화 중..."
    hdfs dfsadmin -safemode enter >/dev/null
    hdfs dfsadmin -saveNamespace >/dev/null
    hdfs dfsadmin -safemode leave >/dev/null

    echo " - 파이프라인 실행을 위한 Java 데몬 트리거 중..."
    # 기존에 돌고 있던 Java 데몬 프로세스 종료 (YARN이 아닌 로컬 데몬 기준)
    kill $(pgrep -f hdfs-auto-tiering.jar) 2>/dev/null || true
    sleep 2

    JAR_FILE="/tmp/hdfs-auto-tiering-scenario.jar"
    CONFIG_FILE="$HOME/hdfs-auto-tiering-config-test.yaml"
    hdfs dfs -copyToLocal -f "/apps/hdfs-auto-tiering/lib/hdfs-auto-tiering.jar" "$JAR_FILE" 2>/dev/null
    if [ ! -f "$JAR_FILE" ]; then
        echo "[오류] JAR 파일을 찾을 수 없습니다. 먼저 /apps/hdfs-auto-tiering/lib/hdfs-auto-tiering.jar 배포를 진행해야 합니다."
        exit 1
    fi

    cat > "$CONFIG_FILE" << 'CFG'
database:
  url: jdbc:postgresql://localhost:5432/dsc_tiering
  username: dsc
  password: dsc
  pool:
    maximumPoolSize: 8
    minimumIdle: 2
hdfs:
  fsDefaultName: hdfs://localhost:9000
  user: ""
  policyMapping:
    HOT: ALL_SSD
    WARM: ONE_SSD
    COLD: COLD
scoring:
  enabled: true
  intervalSeconds: 86400
  weightAccessTime: 0.5
  weightFileSize: 0.5
  localFsimageDir: /tmp/hdfs-auto-tiering-fsimage
  targetDirectories:
    - /test/scenario_e2e
scheduler:
  pollIntervalSeconds: 10
  windows:
    - name: allday
      start: "00:00"
      end:   "23:59"
      batchSize: 50
      interBatchWaitMs: 1000
  concurrency: 8
  maxRetries: 3
tracker:
  pollIntervalSeconds: 5
  timeoutMinutes: 60
  batchSize: 20
  completionRatio: 0.95
  maxWorkers: 5
  nodenameSemaphore: 3
CFG

    > /tmp/tiering-scenario.log
    java -jar "$JAR_FILE" "$CONFIG_FILE" > /tmp/tiering-scenario.log 2>&1 &
    DAEMON_PID=$!

    TARGET_COMPLETED=0
    if [ "$SCENARIO" == "A" ]; then TARGET_COMPLETED=1; fi
    if [ "$SCENARIO" == "B" ]; then TARGET_COMPLETED=2; fi
    if [ "$SCENARIO" == "C" ]; then TARGET_COMPLETED=5; fi
    QUEUE_SCOPE_SQL=" AND file_path LIKE '${HDFS_TEST_DIR}%'"

    for i in $(seq 1 120); do
        PENDING_COUNT=$($PSQL_CMD -c "SELECT COUNT(*) FROM pending_jobs WHERE status='PENDING'${QUEUE_SCOPE_SQL};" 2>/dev/null)
        DISPATCHED_COUNT=$($PSQL_CMD -c "SELECT COUNT(*) FROM pending_jobs WHERE status='DISPATCHED'${QUEUE_SCOPE_SQL};" 2>/dev/null)
        IN_PROGRESS_COUNT=$($PSQL_CMD -c "SELECT COUNT(*) FROM pending_jobs WHERE status='IN_PROGRESS'${QUEUE_SCOPE_SQL};" 2>/dev/null)
        COMPLETED_COUNT=$($PSQL_CMD -c "SELECT COUNT(*) FROM pending_jobs WHERE status='COMPLETED'${QUEUE_SCOPE_SQL};" 2>/dev/null)
        FAILED_COUNT=$($PSQL_CMD -c "SELECT COUNT(*) FROM pending_jobs WHERE status='FAILED'${QUEUE_SCOPE_SQL};" 2>/dev/null)

        # 현재 이동 중인 파일(DISPATCHED) 목록 조회
        DISPATCHED_FILES=$($PSQL_CMD -c "SELECT file_path, current_tier, target_tier FROM pending_jobs WHERE status IN ('DISPATCHED', 'IN_PROGRESS')${QUEUE_SCOPE_SQL} LIMIT 2;" 2>/dev/null)

        # 화면 전체를 지우고(clear) 새로 그려 터미널 깨짐 방지
        clear
        echo "=========================================================="
        echo "[라이브 모니터] HDFS 자동 티어링 파이프라인 상태"
        echo "=========================================================="
        printf "[큐 상태] 타임아웃 제한: %ds\n" "$((i*2))"
        printf " - PENDING    : %d\n" "$PENDING_COUNT"
        printf " - DISPATCHED : %d\n" "$DISPATCHED_COUNT"
        printf " - IN_PROGRESS: %d\n" "$IN_PROGRESS_COUNT"
        printf " - COMPLETED  : %d / %d\n" "$COMPLETED_COUNT" "$TARGET_COMPLETED"
        printf " - FAILED     : %d\n" "$FAILED_COUNT"
        echo "----------------------------------------------------------"
        # 백그라운드 프로세스가 죽었는지 확인
        if ! kill -0 "$DAEMON_PID" 2>/dev/null; then
            echo "[오류] 백그라운드 Java 데몬이 예기치 않게 종료되었습니다."
            echo "--- [/tmp/tiering-scenario.log] ---"
            cat /tmp/tiering-scenario.log
            echo "----------------------------------------"
            break
        fi

        if [ -n "$DISPATCHED_FILES" ]; then
            echo "[동작] 물리적 블록 이동 진행 중:"
            echo "$DISPATCHED_FILES" | sed 's/|/ -> /g' | sed 's/^/   - /'
        elif [ "$COMPLETED_COUNT" -lt "$TARGET_COMPLETED" ]; then
            echo "[상태] 스케줄러/트래커 상태 전이 대기 중..."
        else
            echo "[상태] 모든 티어링 파이프라인이 성공적으로 완료되었습니다."
        fi

        if [ "$COMPLETED_COUNT" -eq "$TARGET_COMPLETED" ]; then
            SUCCESS_FLAG=1
            break
        fi
        sleep 2
    done

    kill "$DAEMON_PID" 2>/dev/null || true
    rm -f "$JAR_FILE" 2>/dev/null || true
    rm -rf "$LOCAL_TMP_DIR"

    if [ "${SUCCESS_FLAG:-0}" -eq 1 ]; then
        echo "[시스템] 파이프라인 실행 및 데이터 티어링 완료."
    else
        echo "[경고] 모든 티어링 작업을 완료하기 전에 스크립트가 종료되었습니다 (타임아웃 또는 에러)."
        echo "로그를 확인하십시오: cat /tmp/tiering-scenario.log"
        cat /tmp/tiering-scenario.log | grep -E 'Exception|Error|Fail' || true
    fi
fi

echo "=========================================================="
echo "HDFS 자동 티어링 스토리지 최적화 및 비용 리포트"
echo "자동화된 블록 배치를 통한 스토리지 비용 절감 지표 검증"
echo "=========================================================="

# 1. 누적 처리량 (HOT -> COLD) 및 (HOT -> WARM) 바이트
REPORT_SCOPE_SQL=""
if [ "$SCENARIO" != "CURRENT" ]; then
    REPORT_SCOPE_SQL=" AND file_path LIKE '${HDFS_TEST_DIR}%'"
fi
HOT_TO_COLD_BYTES=$($PSQL_CMD -c "SELECT COALESCE(SUM(file_size_bytes), 0) FROM pending_jobs WHERE status='COMPLETED' AND current_tier='HOT' AND target_tier='COLD'${REPORT_SCOPE_SQL};" 2>/dev/null || echo 0)
HOT_TO_WARM_BYTES=$($PSQL_CMD -c "SELECT COALESCE(SUM(file_size_bytes), 0) FROM pending_jobs WHERE status='COMPLETED' AND current_tier='HOT' AND target_tier='WARM'${REPORT_SCOPE_SQL};" 2>/dev/null || echo 0)

# 바이트를 GB(기가바이트) 단위로 변환 (소수점 유지)
HOT_TO_COLD_GB=$(awk "BEGIN {print $HOT_TO_COLD_BYTES / 1024 / 1024 / 1024}")
HOT_TO_WARM_GB=$(awk "BEGIN {print $HOT_TO_WARM_BYTES / 1024 / 1024 / 1024}")

echo "[데이터 티어링 지표 (누적 GB)]"
printf -- " - HOT(gp3) -> COLD(sc1): %.2f GB\n" "$HOT_TO_COLD_GB"
printf -- " - HOT(gp3) -> WARM(st1): %.2f GB\n" "$HOT_TO_WARM_GB"

# 비용 절감액 계산 (월간 GB당 절감액: COLD 이관 시 $0.065, WARM 이관 시 $0.035 절감)
SAVINGS_COLD=$(awk "BEGIN {printf \"%.2f\", $HOT_TO_COLD_GB * 0.065}")
SAVINGS_WARM=$(awk "BEGIN {printf \"%.2f\", $HOT_TO_WARM_GB * 0.035}")
TOTAL_SAVINGS=$(awk "BEGIN {printf \"%.2f\", $SAVINGS_COLD + $SAVINGS_WARM}")

echo ""
echo "[스토리지 비용 절감액 (예상 월간, AWS EBS 벤치마크)]"
echo " - ARCHIVE(sc1) 도입에 따른 절감액: 월 \$${SAVINGS_COLD}"
echo " - DISK(st1) 도입에 따른 절감액   : 월 \$${SAVINGS_WARM}"
echo "----------------------------------------------------------"
echo " > 총 비용 절감액: 월 \$${TOTAL_SAVINGS}"
echo "=========================================================="

if [ "$SCENARIO" != "CURRENT" ]; then
    echo "[시스템] 물리적 시나리오 테스트 데이터 정리 완료."
    $PSQL_CMD -c "DELETE FROM pending_jobs WHERE file_path LIKE '${HDFS_TEST_DIR}%';" > /dev/null 2>&1
    hdfs dfs -rm -r -skipTrash "$HDFS_TEST_DIR" 2>/dev/null || true
fi
SCRIPT

chmod +x ~/test-auto-tiering-savings.sh
```

- **실행 방법**:
  - 현재 DB 누적치 확인: `~/test-auto-tiering-savings.sh`
  - 시나리오별 실물 테스트: `~/test-auto-tiering-savings.sh A` (또는 B, C)
- **활용**: E2E 테스트 직후에는 이관량이 10MB에 불과하므로, 스크립트 뒤에 `A`, `B`, `C` 인자를 붙여 실제 더미 파일을 HDFS에 생성하고 ScoringEngine이 발견한 작업만으로 절감 효과를 시연합니다. (테스트가 끝나면 생성된 HDFS 데이터와 해당 경로의 DB 이력은 자동으로 정리됩니다.)

### 21.1. 실제 운영 환경 적용 시나리오 (실물 GB 스케일 테스트)

테스트 서버(WSL2)의 물리적 한계를 고려하여, 수 기가바이트(GB) 단위의 **실제 더미 파일을 HDFS에 생성하고 YARN 기반 데몬이 이를 직접 이관 처리(Physical Data Movement)하도록 시뮬레이션**합니다. 테스트 후 산출되는 절감액 역시 실제 GB 이관량에 맞춰 계산됩니다.

#### 시나리오 A: 로그성 데이터 누적 (Log Data Accumulation)
- **상황**: 매일 지속적으로 로그 데이터가 쌓이며, 오래된 로그는 콜드로 이관됩니다.
- **테스트 환경**: 총 2GB의 물리 파일(1GB 단위 2개) 생성. 1GB는 HOT, 1GB는 COLD로 이관.
- **Auto-Tiering 미도입**: 2GB 모두 `gp3(HOT)` 유지 시 $\rightarrow$ 월 $0.16 발생.
- **Auto-Tiering 도입**:
  - HOT 비용: 1GB * $0.08/GB = $0.080
  - COLD 비용: 1GB * $0.015/GB = $0.015
  - **결과**: 월 $0.095 비용 발생 (절감액: $0.065)

#### 시나리오 B: 머신러닝 데이터 레이크 (ML Data Lake)
- **상황**: 학습 데이터(HOT), 검증 데이터(WARM), 과거 데이터(COLD)가 혼재되어 있습니다.
- **테스트 환경**: 총 3GB의 물리 파일(1GB 단위 3개) 생성. HOT 1GB, WARM 1GB, COLD 1GB로 분산.
- **Auto-Tiering 미도입**: 3GB 모두 `gp3(HOT)` 유지 시 $\rightarrow$ 월 $0.24 발생.
- **Auto-Tiering 도입**:
  - HOT 비용: 1GB * $0.08/GB = $0.080
  - WARM 비용: 1GB * $0.045/GB = $0.045
  - COLD 비용: 1GB * $0.015/GB = $0.015
  - **결과**: 월 $0.14 비용 발생 (절감액: $0.10)

#### 시나리오 C: 금융/의료 컴플라이언스 아카이빙 (Long-term Compliance)
- **상황**: 컴플라이언스로 인해 대부분의 과거 데이터를 무조건 보관해야 합니다.
- **테스트 환경**: 총 5GB의 물리 파일(1GB 단위 5개) 생성. 전량 COLD로 압축 이관.
- **Auto-Tiering 미도입**: 5GB 모두 `gp3(HOT)` 유지 시 $\rightarrow$ 월 $0.40 발생.
- **Auto-Tiering 도입**:
  - COLD 비용: 5GB * $0.015/GB = $0.075
  - **결과**: 월 $0.075 비용 발생 (절감액: $0.325)

---

*Hadoop 3.4.1 + WSL2 Ubuntu 22.04 기준*
*분산시스템및컴퓨팅(2026-1) HDFS Auto-Tiering YARN 서비스 프로젝트*
