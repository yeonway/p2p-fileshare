이 프로젝트의 기본 모드는 Raspberry Pi에 파일을 임시 저장하는 HTTP 전송 시스템이다.
기존 서버 저장 없는 WebRTC P2P 전송은 숨겨진 고급 모드(`/p2p/send`, `/p2p/receive`)로 유지한다.

- 기본 HTTP 임시 저장 모드는 raw chunk upload/download endpoint를 사용할 수 있다.
- 기본 HTTP 임시 저장 모드 파일은 `server/data/stored_files` 아래에만 저장할 것.
- 기본 HTTP 임시 저장 모드 파일은 첫 정상 다운로드, TTL 만료, idle timeout 때 삭제할 것.
- 기본 HTTP 임시 저장 모드에서 code/token/IP 원문을 DB에 저장하지 말 것.
- 숨겨진 P2P 모드에서는 서버가 signaling, room code, 기록 저장만 담당한다.
- 숨겨진 P2P 모드에서는 파일 업로드 endpoint를 사용하지 말 것.
- 숨겨진 P2P 모드에서는 multipart/form-data 파일 업로드를 구현하지 말 것.
- 숨겨진 P2P 모드에서는 WebSocket으로 파일 본문을 중계하지 말 것.
- 숨겨진 P2P 모드에서는 서버에 파일/chunk/base64/Blob/ArrayBuffer를 저장하지 말 것.
- 숨겨진 P2P 모드 파일은 WebRTC RTCDataChannel로만 전송할 것.
- 파일은 원본 binary bytes 그대로 전송할 것.
- 동영상/이미지/오디오를 압축/변환/재인코딩하지 말 것.
- 기본 HTTP 임시 저장 모드는 전체 전송 합계 30GB 제한을 둔다.
- 대용량 파일을 위해 chunk 전송과 backpressure를 유지할 것.
- Web/Android/Windows는 같은 protocol을 사용해야 한다.
- SQLite에는 기록, 메타데이터, chunk 진행률, hash 처리된 code/token/IP만 저장할 것.
- IP 원문 저장 금지.
- 보안 관련 변경 시 README와 docs에 반영할 것.

안드로이드 앱이라면 내 명령을 실행한 후에 apk 빌드해놔.
