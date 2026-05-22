# coturn Optional Fallback

coturn은 hidden P2P 모드(`/p2p/send`, `/p2p/receive`)에서만 쓰는 선택 기능입니다. 기본 stored transfer mode는 HTTP upload/download이므로 TURN이 필요하지 않습니다.

## 중요 사항

- TURN은 WebRTC relay 서버입니다.
- FastAPI stored transfer API의 파일 임시 저장과는 별개입니다.
- Windows MVP 또는 Web hidden P2P 테스트에서 direct P2P가 실패하는 네트워크 조합일 때만 사용합니다.

## 포트

- `3478` UDP/TCP: TURN 기본 포트
- relay port range: 운영자가 제한해서 공개 권장

예:

```text
min-port=49160
max-port=49200
```

## 환경변수

```text
WEBRTC_TURN_URLS=turn:files.dcout.site:3478
WEBRTC_TURN_USERNAME=<username>
WEBRTC_TURN_CREDENTIAL=<password>
```

TURN을 사용하지 않는 기본값:

```text
WEBRTC_TURN_URLS=
WEBRTC_TURN_USERNAME=
WEBRTC_TURN_CREDENTIAL=
```

## UI 표시

hidden P2P 클라이언트는 WebRTC `getStats()`로 selected candidate에 relay candidate가 있는지 확인하고 다음 중 하나를 표시합니다.

- `직접 P2P`
- `TURN relay`

서버에는 `connection-info` status message로 `direct_p2p`, `turn_used` metadata만 보고합니다.
