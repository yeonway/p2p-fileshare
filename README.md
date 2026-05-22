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
- Web PWA 설치 manifest/service worker
- Android Foreground Service 전송 알림
- Android 다중 파일 선택/전송. 여러 파일은 수신 호환성을 위해 하나의 `.tar` 묶음으로 스트리밍됩니다.
- 중단/타임아웃/저장 실패 공통 오류 코드
- 송신 QR과 Android 딥링크/웹 fallback 링크
- 기본 chunk size 64KiB
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

빠른 서버 회귀 테스트:

```powershell
.\scripts\verify.ps1
```

Windows 빌드까지 포함:

```powershell
.\scripts\verify.ps1 -IncludeWindows
```

Android 테스트와 debug APK 빌드까지 포함:

```powershell
.\scripts\verify.ps1 -IncludeAndroid
```

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

단일 파일은 원본 binary bytes 그대로 chunk 전송됩니다. 동영상/이미지/오디오는 decode, encode, 압축, 변환, 재인코딩하지 않습니다. Android에서 여러 파일을 고르면 파일 본문을 압축하지 않는 `.tar` 묶음으로 스트리밍합니다. 기본 chunk size는 WebRTC 호환성을 위해 64KiB입니다.

## 미완성/제한사항

- WebRTC 전송은 Web PWA 창이나 브라우저 탭이 열려 있어야 유지됩니다.
- Windows Tauri 앱은 native save picker와 Rust side chunk write를 우선 사용합니다.
- 일반 브라우저/WebView fallback은 File System Access API 또는 Blob download fallback에 의존하므로 대용량 수신에는 적합하지 않을 수 있습니다.
- hash 검증은 TODO입니다. MVP는 `receivedBytes == file_size`를 필수 검증으로 사용합니다.
- chunk 재전송/request-missing은 protocol 확장 구조만 유지했습니다.
- in-memory rate limit은 단일 프로세스 기준입니다.
