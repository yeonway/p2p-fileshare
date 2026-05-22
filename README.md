# send honey where

6자리 코드로 파일을 주고받는 Raspberry Pi 기반 파일 전송 시스템입니다. 기본 모드는 HTTP chunk upload/download로 Pi SSD에 파일을 잠깐 저장하고, 첫 정상 다운로드 또는 만료 조건에서 삭제합니다.

## 구조

- `server/`: FastAPI, HTTP 임시 저장 API, SQLite metadata/status/security log, Web UI 제공
- `android/`: Kotlin + Jetpack Compose Android 앱. 기본 HTTP 임시 저장 모드 사용
- `windows/`: Tauri 2 + Vite Windows MVP. 현재는 기존 P2P 모드 유지
- `docs/`: protocol, 보안, 배포, native app 문서

기존 WebRTC P2P 전송은 삭제하지 않고 숨겨진 고급 모드로 남겨두었습니다.

- 기본 저장 모드: `/send`, `/receive`
- 숨겨진 P2P 모드: `/p2p/send`, `/p2p/receive`

## 기본 HTTP 임시 저장 모드

- 전체 전송 합계 최대 30GB
- 파일 개수 제한 없음
- Web은 여러 파일과 폴더 선택 지원
- Android는 SAF 기반 단일/다중 파일 선택 지원
- 여러 파일/폴더는 수신 시 압축하지 않는 `.tar` 묶음으로 다운로드
- 업로드 chunk 기본값 4MiB
- 업로드/다운로드 중 끊기면 재시작 버튼으로 재시도
- 업로드/다운로드 idle 10분 초과 시 삭제
- 업로드 완료 후 수신 대기 TTL 30분
- 첫 정상 다운로드 완료 시 즉시 삭제
- DB에는 code/token/IP 원문을 저장하지 않고 hash만 저장

v1은 지인용 private Raspberry Pi 운영과 속도를 우선해 서버에 plaintext 임시 저장합니다. 외부 공개 운영에서는 HTTPS, 강한 관리자 비밀번호, 충분한 디스크 quota를 설정하세요.

## 서버 로컬 실행

```powershell
cd server
python -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install -r requirements.txt
Copy-Item ..\.env.example .env
uvicorn app.main:app --host 127.0.0.1 --port 8010
```

브라우저에서 `http://127.0.0.1:8010`을 열고 `/send`, `/receive`를 테스트합니다.

## 주요 환경변수

```text
STORED_FILES_DIR=./data/stored_files
STORED_CHUNK_SIZE_BYTES=4194304
STORED_MAX_TOTAL_SIZE_BYTES=32212254720
STORED_IDLE_TIMEOUT_SECONDS=600
STORED_READY_TTL_MINUTES=30
IP_HASH_SALT=<random-secret>
ADMIN_PASSWORD=<strong-password>
```

## Android 빌드

```powershell
$env:ANDROID_HOME="$env:LOCALAPPDATA\Android\Sdk"
cd android
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

APK:

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

## 테스트

```powershell
.\scripts\verify.ps1
.\scripts\verify.ps1 -IncludeAndroid
```

서버만:

```powershell
cd server
python -m pytest
```

확인 항목:

- 30GB 초과 거부
- code/token/IP hash 저장
- chunk 업로드/재시작/완료 검증
- 단일 파일 raw download
- 다중/폴더 tar download
- 첫 다운로드 후 삭제
- idle/TTL cleanup
- 숨겨진 P2P WebSocket 보안 테스트 유지
