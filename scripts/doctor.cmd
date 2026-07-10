@echo off
setlocal EnableDelayedExpansion

set "REQUIRED_JAVA_MAJOR=11"
set "CLUSTER_MODE=0"
set "FAILED=0"

if /I "%~1"=="--cluster" set "CLUSTER_MODE=1"

echo DSC 환경 진단 / DSC environment doctor
echo ========================================
echo.
echo [필수 / required]

where java >nul 2>nul
if errorlevel 1 goto :java_missing

for /f "tokens=3" %%v in ('java -version 2^>^&1 ^| findstr /i "version"') do set "JAVA_VER_RAW=%%v"
set "JAVA_VER=%JAVA_VER_RAW:"=%"

for /f "delims=. tokens=1,2" %%a in ("%JAVA_VER%") do (
    set "MAJOR=%%a"
    set "MINOR=%%b"
)
if "%MAJOR%"=="1" set "MAJOR=%MINOR%"

if not defined MAJOR goto :java_unparsable

if %MAJOR% GEQ %REQUIRED_JAVA_MAJOR% (
    echo   [OK]   Java: %JAVA_VER% ^(^>= %REQUIRED_JAVA_MAJOR%^)
) else (
    echo   [FAIL] Java: %JAVA_VER% 발견, %REQUIRED_JAVA_MAJOR% 이상 필요 / found, but %REQUIRED_JAVA_MAJOR%+ required
    call :hint_java
    set "FAILED=1"
)
goto :after_java

:java_unparsable
echo   [FAIL] Java: 버전을 확인할 수 없음 / could not parse version
call :hint_java
set "FAILED=1"
goto :after_java

:java_missing
echo   [FAIL] Java: 찾을 수 없음 / not found ^(11 이상 필요 / 11+ required^)
call :hint_java
set "FAILED=1"

:after_java
echo.
echo [선택 / optional]

where docker >nul 2>nul
if errorlevel 1 goto :docker_missing

docker info >nul 2>nul
if errorlevel 1 goto :docker_not_running

for /f "tokens=*" %%d in ('docker version --format "{{.Server.Version}}" 2^>nul') do set "DOCKER_VER=%%d"
echo   [OK]   Docker: %DOCKER_VER% ^(daemon running^)
goto :after_docker

:docker_missing
echo   [WARN] Docker: 찾을 수 없음 / not found ^(optional / 선택^)
call :hint_docker
goto :after_docker

:docker_not_running
echo   [WARN] Docker: 설치되어 있으나 데몬이 실행 중이 아님 / installed but daemon is not running ^(optional / 선택^)
call :hint_docker

:after_docker
if not "%CLUSTER_MODE%"=="1" goto :summary

echo.
echo [실측 배포 환경 한정 / cluster deployment only, --cluster]

where hdfs >nul 2>nul
if errorlevel 1 (
    echo   [WARN] hdfs: 찾을 수 없음 / not found
    echo          패키지 매니저로 설치되지 않습니다. INFRA.md의 Hadoop 설치 절차를 따르세요.
    echo          Not installable via winget. Follow the Hadoop setup steps in INFRA.md.
) else (
    echo   [OK]   hdfs: found
)

where yarn >nul 2>nul
if errorlevel 1 (
    echo   [WARN] yarn: 찾을 수 없음 / not found
    echo          패키지 매니저로 설치되지 않습니다. INFRA.md의 Hadoop 설치 절차를 따르세요.
    echo          Not installable via winget. Follow the Hadoop setup steps in INFRA.md.
) else (
    echo   [OK]   yarn: found
)

where psql >nul 2>nul
if errorlevel 1 (
    echo   [WARN] psql: 찾을 수 없음 / not found
    echo          Windows: winget install PostgreSQL.PostgreSQL
) else (
    echo   [OK]   psql: found
)

:summary
echo.
echo ========================================
if "%FAILED%"=="0" (
    echo 필수 항목 통과 / all required checks passed.
    exit /b 0
) else (
    echo 필수 항목 실패 - 위 안내를 따라 설치 후 다시 실행하세요.
    echo Required checks failed - install per the guidance above and re-run.
    exit /b 1
)

:hint_java
echo          Windows: winget install EclipseAdoptium.Temurin.11.JDK
exit /b 0

:hint_docker
echo          통합 테스트^(PendingJobRepositoryTest^)만 실행 불가, CI에서는 검증됨.
echo          Integration test ^(PendingJobRepositoryTest^) will be skipped locally; verified in CI.
echo          Windows: winget install Docker.DockerDesktop
echo          가이드 / Guide: https://docs.docker.com/engine/install/
exit /b 0
