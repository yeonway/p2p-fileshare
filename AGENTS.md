이 프로젝트는 서버에 파일을 저장하지 않는 P2P 파일 전송 시스템이다.

- 서버는 signaling, room code, 기록 저장만 담당한다.
- 파일 업로드 endpoint를 만들지 말 것.
- multipart/form-data 파일 업로드를 구현하지 말 것.
- WebSocket으로 파일 본문을 중계하지 말 것.
- 서버에 파일/chunk/base64/Blob/ArrayBuffer를 저장하지 말 것.
- 파일은 WebRTC RTCDataChannel로만 전송할 것.
- 파일은 원본 binary bytes 그대로 전송할 것.
- 동영상/이미지/오디오를 압축/변환/재인코딩하지 말 것.
- 앱 레벨 파일 크기 제한을 두지 말 것.
- 대용량 파일을 위해 chunk 전송과 backpressure를 유지할 것.
- Web/Android/Windows는 같은 protocol을 사용해야 한다.
- SQLite에는 기록과 메타데이터만 저장할 것.
- IP 원문 저장 금지.
- 보안 관련 변경 시 README와 docs에 반영할 것.

안드로이드 앱이라면 내 명령을 실행한 후에 apk 빌드해놔.
