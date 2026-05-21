# sand honey where

6자리 코드로 파일을 주고받는 P2P 파일 전송 시스템입니다. Raspberry Pi FastAPI 서버는 방 생성, signaling, metadata/status 기록만 담당하고 파일 본문은 저장하거나 중계하지 않습니다.

## 구조

- `server/`: FastAPI, WebSocket signaling, SQLite metadata/status/security log, Web MVP 제공
- `web/`: Web 클라이언트 설명 문서
- `android/`: Kotlin + Jetpack Compose + Native WebRTC Android MVP
- `windows/`: Tauri 2 + Vite + TypeScript WebView Windows MVP
- `docs/`: 공통 protocol, 보안, coturn, native app 빌드/테스트 문서

파일 전송은 WebRTC `RTCDataChannel`로만 수행합니다. 서버에는 파일 업로드 API가 없고, WebSocket으로 파일 chunk를 보내는 것도 금지되어 있습니다.

## 구현 범위

- 서버 방 생성/join/config/admin/health API
- WebSocket JSON signaling/status relay
- WebSocket binary message reject + `security_events` 기록
- Web sender/receiver MVP
- Android sender/receiver MVP
- Windows Tauri WebView sender/receiver MVP
- 기본 chunk size 1MiB
- `sender-finished` / `receiver-complete` / `receivedBytes == file_size` 완료 조건 유지
- SQLite에는 metadata/status/security event만 저장

## 서버 로컬 실행

```bash
cd server
python -m venv .venv
. .venv/bin/activate
pip install -r requirements.txt
cp ../.env.example .env
uvicorn app.main:app --host 127.0.0.1 --port 8010
```

Windows PowerShell:

```powershell
cd server
python -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install -r requirements.txt
Copy-Item ..\.env.example .env
uvicorn app.main:app --host 127.0.0.1 --port 8010
```

브라우저에서 `http://127.0.0.1:8010`을 열고 `파일 보내기`와 `파일 받기`를 각각 다른 탭에서 테스트합니다.

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

자세한 내용은 `docs/android_build.md`와 `android/README.md`를 참고합니다.

## Windows 실행/빌드

```powershell
cd windows
npm install
npm run dev
```

Vite WebView MVP URL:

```text
http://127.0.0.1:1420
```

정적 빌드:

```powershell
npm run build
```

Tauri 패키징:

```powershell
npm run tauri:build
```

Tauri 패키징에는 Rust/Cargo가 필요합니다. 현재 검증 환경에서는 Cargo가 없어 패키징은 실패했고, Vite/TypeScript 빌드는 성공했습니다. 자세한 내용은 `docs/windows_build.md`와 `windows/README.md`를 참고합니다.

## 테스트

서버 회귀 테스트:

```bash
cd server
pytest
```

확인 항목:

- room 생성, 만료 join 차단, IP/code hash
- upload endpoint 부재
- WebSocket binary/금지 메시지 reject
- admin auth, filename sanitize, status transition, rate limit

수동 호환성 테스트:

- Android -> Android
- Android -> Windows
- Android -> Web
- Windows -> Windows
- Windows -> Web
- Web -> Web
- Web -> Android
- Web -> Windows
- Windows -> Android

같은 Wi-Fi, LTE/5G 외부망, TURN fallback, 큰 영상 파일에서 수신 파일 크기가 원본과 같은지 확인합니다.

## Direct P2P와 TURN fallback

기본값은 STUN을 사용한 direct P2P입니다. NAT 환경에 따라 direct 연결이 실패할 수 있으며, 이 경우 coturn을 선택적으로 추가할 수 있습니다. TURN을 사용하면 트래픽은 TURN 서버를 경유할 수 있지만 파일은 저장되지 않습니다.

속도가 느린 일반 원인:

- TURN TCP fallback
- Raspberry Pi Wi-Fi 병목
- 공유기 포트포워딩/NAT 제약
- 통신사 업로드 속도 제한

## 대용량 파일과 화질

파일은 원본 binary bytes 그대로 chunk 전송됩니다. 동영상/이미지/오디오는 decode, encode, 압축, 변환, 재인코딩하지 않습니다. 기본 chunk size는 고속 전송을 위해 1MiB입니다.

## 미완성/제한사항

- Android Foreground Service 전송은 Phase 2 TODO입니다.
- Windows Rust native streaming bridge는 Phase 2 TODO입니다.
- Windows MVP는 WebView File API 기반이며 `showSaveFilePicker()` 미지원 환경에서는 수신 저장을 진행하지 않습니다.
- hash 검증은 TODO입니다. MVP는 `receivedBytes == file_size`를 필수 검증으로 사용합니다.
- chunk 재전송/request-missing은 protocol 확장 구조만 유지했습니다.
- in-memory rate limit은 단일 프로세스 기준입니다.
