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

## Known Limits

- MVP는 Foreground Service를 완성하지 않았습니다.
- 백그라운드 전송은 Android 정책에 따라 끊길 수 있습니다.
- hash 검증과 missing chunk 재요청은 TODO입니다.
