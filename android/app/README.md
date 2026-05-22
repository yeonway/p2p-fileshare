# Android App Module

`site.sexyminup.p2pfileshare` Android app module입니다. 패키지명은 기존 이름을 유지하지만 기본 전송 방식은 HTTP stored transfer입니다.

주요 코드 위치:

- `data/`: stored REST API, 설정 저장, response model
- `transfer/`: SAF file metadata, formatting helper
- `ui/`: Compose UI와 transfer ViewModel
- `P2PTransferForegroundService.kt`: 업로드/다운로드 진행률 foreground 알림
- `webrtc/`, `signaling/`: 기존 P2P 호환 코드. 기본 Android UI에서는 사용하지 않습니다.

빌드:

```powershell
cd android
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

APK:

```text
android/app/build/outputs/apk/debug/app-debug.apk
```
