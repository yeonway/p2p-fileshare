# Android Build

## Requirements

- JDK 17
- Android SDK with platform/build-tools 36
- Gradle wrapper included in `android/`

PowerShell 예시:

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
2. 설정에서 서버 URL을 확인합니다.
3. Android sender -> Web receiver를 테스트합니다.
4. Web sender -> Android receiver를 테스트합니다.
5. Android sender -> Windows receiver를 테스트합니다.
6. 수신 파일 크기가 원본과 같은지 확인합니다.
7. 큰 영상 파일과 LTE/5G 외부망, TURN fallback을 각각 확인합니다.
8. 송신 화면 QR을 스캔해 Android 앱의 받기 화면에 코드가 자동 입력되는지 확인합니다.
9. 알림 권한과 배터리 최적화 예외 요청을 허용한 뒤 백그라운드 전송을 확인합니다.

## Known Limits

- 전송 중에는 Foreground Service 알림으로 진행률을 갱신해 앱이 홈 화면이나 잠금 화면 뒤에 있어도 전송이 유지되도록 합니다.
- 사용자가 앱을 강제 종료하거나 시스템이 프로세스를 종료하면 진행 중인 WebRTC 전송은 복구되지 않습니다.
- HTTPS App Link 자동 검증은 서버의 `ANDROID_APP_SHA256_CERT_FINGERPRINTS` 설정에 실제 서명 인증서 SHA-256 fingerprint가 들어가야 완전하게 동작합니다. 커스텀 스킴 `sendhoney://receive?code=...`는 앱 설치 시 바로 처리합니다.
- hash 검증과 missing chunk 재요청은 TODO입니다.
