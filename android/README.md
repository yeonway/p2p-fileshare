# Android MVP

Kotlin + Jetpack Compose 기반 Android 앱입니다. 기본 보내기/받기는 Raspberry Pi HTTP 임시 저장 모드를 사용합니다.

## 구현 상태

- REST: `/api/stored/transfers`, `/api/stored/transfers/join`, status, download
- 파일 선택: Storage Access Framework `ACTION_OPEN_DOCUMENT` / `ACTION_OPEN_DOCUMENT_MULTIPLE`
- 파일 저장: Storage Access Framework `ACTION_CREATE_DOCUMENT`
- 업로드: `InputStream`에서 chunk를 읽어 raw PUT 업로드
- 다운로드: HTTP stream을 `OutputStream`으로 저장
- 단일 파일 수신: 원본 파일명과 MIME 사용
- 다중 파일 수신: uncompressed `.tar` 저장
- 재시작: 실패 후 status를 조회하고 이미 올라간 chunk는 건너뜀
- Foreground Service: 업로드/다운로드 진행률 알림
- 자동 초기화: DataStore 설정으로 저장
- 딥링크:
  - `sendhoney://receive?code=123456`
  - `https://files.dcout.site/receive?code=123456`

Android 폴더 트리 선택은 SAF 제약 때문에 v1에서 제외했습니다. 여러 파일 선택과 tar 수신을 우선 지원합니다.

## 빌드

Android SDK가 설치되어 있고 `ANDROID_HOME`이 SDK 경로를 가리켜야 합니다.

```powershell
$env:ANDROID_HOME="$env:LOCALAPPDATA\Android\Sdk"
cd android
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

APK 경로:

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

## 서버 URL

현재 앱 기본 서버 URL은 `https://files.dcout.site`입니다. 개발 예외로 `http://127.0.0.1:<port>`와 `http://localhost:<port>`만 허용합니다.

## 제한사항

- hash 검증은 TODO입니다.
- Android 폴더 트리 선택은 후속 작업입니다.
- 앱 프로세스가 강제 종료되면 현재 세션 정보도 사라질 수 있습니다.
- Windows P2P 앱과의 직접 호환은 후속 stored mode 전환 후 지원합니다.
