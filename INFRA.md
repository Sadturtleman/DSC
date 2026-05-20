# HDFS Auto-Tiering YARN 서비스
## 인프라 구성 완전 가이드

**환경**: Windows 11 + WSL2 Ubuntu 22.04 + Hadoop 3.4.1  
**대상**: 클린 설치 Windows 11 데스크탑에서 처음부터 구성하는 경우

---

## 목차

1. [아키텍처 개요](#1-아키텍처-개요)
2. [시스템 요구사항](#2-시스템-요구사항)
3. [WSL2 설치](#3-wsl2-설치)
4. [WSL2 리소스 설정](#4-wsl2-리소스-설정)
5. [Ubuntu 초기 설정](#5-ubuntu-초기-설정)
6. [Java 11 설치](#6-java-11-설치)
7. [Python 3 설치](#7-python-3-설치)
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

## 7. Python 3 설치

*(본 가이드에서는 핵심 엔진이 All-in-Java로 통합되었으므로, 별도의 Python 환경 구축이 필수는 아닙니다. 기타 스크립트 테스트 용도로만 사용됩니다.)*

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

### 15-5. Web UI 확인

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
    maximum-pool-size: 8
    minimum-idle: 2

hdfs:
  fs-default-name: hdfs://localhost:9000
  user: ""
  namenode-http-url: http://localhost:9870
  policy-mapping:
    HOT: ALL_SSD
    WARM: ONE_SSD
    COLD: COLD

workers:
  scoring:
    cron: "0 0 0 * * ?"  # 매일 자정 스코어링
    weight-access-time: 0.7
    weight-file-size: 0.3
  scheduler:
    poll-interval-seconds: 10
    concurrency: 8
  tracker:
    poll-interval-seconds: 30
    timeout-hours: 12
EOF
```

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

# 2. 테스트 완료 후 YARN 시스템 디렉터리 정책을 기본값(HOT)으로 원상 복구
HADOOP_CONF_DIR=~/hadoop-conf/namenode hdfs storagepolicies -unsetStoragePolicy -path /user/$(whoami)/.yarn
```

### 18-4. YARN Service 정의 파일 작성

fat jar 형태이므로 `java -jar` 명령으로 기동하며, 환경에 맞게 Java 11 경로를 지정한다.

```bash
cat > ~/hdfs-auto-tiering-service.json << EOF
{
  "name": "hdfs-auto-tiering",
  "version": "1.0",
  "components": [
    {
      "name": "batch-scheduler",
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

### 18-5. 서비스 등록 및 기동

```bash
HADOOP_CONF_DIR=~/hadoop-conf/namenode \
  yarn app -launch hdfs-auto-tiering ~/hdfs-auto-tiering-service.json
```

### 18-6. 서비스 상태 확인

```bash
# CLI
HADOOP_CONF_DIR=~/hadoop-conf/namenode \
  yarn app -status hdfs-auto-tiering

# 목록
HADOOP_CONF_DIR=~/hadoop-conf/namenode \
  yarn app -list -appTypes YARN-SERVICE
```

http://localhost:8088 → **Services** 탭에서도 확인할 수 있다.

### 18-7. 내부 Scoring Engine 메커니즘 (FSImage OIV 파이프라인 연계)

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

### 18-9. GitHub Releases 기반 자동 배포 스크립트

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
hdfs dfs -put -f "${DOWNLOAD_DIR}/${TARGET_JAR_NAME}" "${HDFS_LIB_DIR}/${TARGET_JAR_NAME}"

if [ -f "$LOCAL_CONFIG" ]; then
    hdfs dfs -put -f "$LOCAL_CONFIG" "${HDFS_CONFIG_DIR}/hdfs-auto-tiering-config.yaml"
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

### 18-10. E2E 검증 테스트 자동화 스크립트

HDFS 파일 업로드, 정책 적용, DB 상태 변경(HOT->COLD), 스케줄러 기동 및 블록 이동 완료까지 자동 검증 후 생성된 테스트 데이터를 삭제(원상 복구)하는 스크립트이다.
코드 변경 없이 로컬 빌드 경로의 원본 jar를 사용한다.

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
JAR_FILE="$HOME/DSC/services/batch-scheduler/target/batch-scheduler-0.1.0-SNAPSHOT.jar"

cleanup() {
    echo "=== 테스트 환경 정리 ==="
    if [ -n "$SCHEDULER_PID" ] && kill -0 "$SCHEDULER_PID" 2>/dev/null; then
        kill "$SCHEDULER_PID" 2>/dev/null || true
    fi
    $PSQL_CMD -c "DELETE FROM pending_jobs WHERE file_path LIKE '${TEST_DIR}%';" 2>/dev/null || true
    hdfs dfs -rm -r -skipTrash "$TEST_DIR" 2>/dev/null || true
    rm -f "$LOCAL_TMP" 2>/dev/null || true
}
trap cleanup EXIT

echo "=== 사전 점검 ==="
if [ ! -f "$JAR_FILE" ]; then
    echo "빌드된 JAR 파일 누락: ${JAR_FILE}"
    exit 1
fi
if [ ! -f "$CONFIG_FILE" ]; then
    echo "설정 파일 누락: ${CONFIG_FILE} (18-2장을 수행하십시오)"
    exit 1
fi

echo "=== 테스트 1: 파일 업로드 및 ALL_SSD 정책 적용 ==="
dd if=/dev/urandom of="$LOCAL_TMP" bs=1M count=10 2>/dev/null
hdfs dfs -mkdir -p "$TEST_DIR"
hdfs storagepolicies -setStoragePolicy -path "$TEST_DIR" -policy ALL_SSD > /dev/null
hdfs dfs -put -f "$LOCAL_TMP" "$TEST_FILE"

echo "=== 테스트 2: 블록 SSD 배치 확인 ==="
FSCK_BEFORE=$(hdfs fsck "$TEST_FILE" -files -blocks -locations 2>/dev/null)
if ! echo "$FSCK_BEFORE" | grep -E -q "9867|9866"; then
    echo "SSD 블록 위치 확인 실패"
    exit 1
fi
echo "확인 성공"

echo "=== 테스트 3: DB 상태 전이 삽입 (HOT->COLD) ==="
# 원래는 Scoring Engine이 자정에 자동으로 FSImage를 파싱하여 아래와 같이 INSERT 하지만,
# 본 테스트에서는 실시간 E2E 동작을 검증하기 위해 강제로 PENDING 작업을 주입합니다.
FILE_SIZE_BYTES=$((10 * 1048576))
$PSQL_CMD -c "INSERT INTO pending_jobs (file_path, file_size_bytes, current_tier, target_tier, priority_score, status, scored_at) VALUES ('${TEST_FILE}', ${FILE_SIZE_BYTES}, 'HOT', 'COLD', 100.0, 'PENDING', NOW());"

echo "=== 테스트 4: Batch Scheduler 기동 ==="
java -jar "$JAR_FILE" "$CONFIG_FILE" > /dev/null 2>&1 &
SCHEDULER_PID=$!

DISPATCHED="no"
for i in $(seq 1 15); do
    sleep 2
    STATUS=$($PSQL_CMD -c "SELECT status FROM pending_jobs WHERE file_path = '${TEST_FILE}' ORDER BY job_id DESC LIMIT 1;" 2>/dev/null || echo "UNKNOWN")
    if [ "$STATUS" = "DISPATCHED" ] || [ "$STATUS" = "COMPLETED" ]; then
        DISPATCHED="yes"
        break
    fi
done

if [ "$DISPATCHED" = "no" ]; then
    echo "작업 처리 실패"
    exit 1
fi
echo "상태 전이 성공: $STATUS"

if kill -0 "$SCHEDULER_PID" 2>/dev/null; then
    kill "$SCHEDULER_PID" 2>/dev/null || true
fi
SCHEDULER_PID=""

echo "=== 테스트 5: External SPS 동작 확인 ==="
sleep 45
FSCK_AFTER=$(hdfs fsck "$TEST_FILE" -files -blocks -locations 2>/dev/null)
if ! echo "$FSCK_AFTER" | grep -E -q "9887|9886"; then
    echo "ARCHIVE 이동 확인 실패. SPS 로그 확인 요망."
    exit 1
fi
echo "ARCHIVE 이동 성공"
echo "=== 모든 테스트 성공 ==="
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

### YARN Service Framework 오류 (`yarn app -launch` 실패)

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

*Hadoop 3.4.1 + WSL2 Ubuntu 22.04 기준*  
*분산시스템및컴퓨팅(2026-1) HDFS Auto-Tiering YARN 서비스 프로젝트*
