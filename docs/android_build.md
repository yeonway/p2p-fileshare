# Android Build

## Requirements

- JDK 17
- Android SDK with platform/build-tools 36
- Gradle wrapper included in `android/`

PowerShell:

```powershell
$env:ANDROID_HOME="$env:LOCALAPPDATA\Android\Sdk"
cd android
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

APK:

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

## Manual Test

1. APK를 Android 기기에 설치합니다.
2. 송신 화면에서 단일 파일 업로드를 테스트합니다.
3. 송신 화면에서 여러 파일 업로드를 테스트합니다.
4. Web `/receive`에서 code로 받아 단일 파일과 tar를 저장합니다.
5. Android receive 화면에서 앱 링크 code 자동 입력을 확인합니다.
6. Android receive 화면에서 단일 파일과 tar 저장을 확인합니다.
7. 업로드/다운로드 중 네트워크를 끊고 재시작 버튼으로 이어받기를 확인합니다.
8. Foreground 알림 진행률을 확인합니다.
9. 자동 초기화 설정 켬/끔 동작을 확인합니다.

## Known Limits

- Android 폴더 트리 선택은 후속 작업입니다.
- hash 검증은 TODO입니다.
- 앱 프로세스가 강제 종료되면 진행 중인 세션 정보가 사라질 수 있습니다.
- HTTPS App Link 자동 검증은 서버의 `ANDROID_APP_SHA256_CERT_FINGERPRINTS` 설정에 실제 서명 인증서 SHA-256 fingerprint가 들어가야 완전하게 동작합니다. 커스텀 스킴 `sendhoney://receive?code=...`는 앱 설치 시 바로 처리합니다.
