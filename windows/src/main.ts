import "./style.css";
import { fetch as tauriFetch } from "@tauri-apps/plugin-http";
import TauriWebSocket, { type Message as TauriWebSocketMessage } from "@tauri-apps/plugin-websocket";

type Role = "sender" | "receiver";
type MaybePromise<T> = T | Promise<T>;

interface SignalingSocket {
  send(data: string): MaybePromise<void>;
  close(): MaybePromise<void>;
  isOpen(): boolean;
}

interface ConfigResponse {
  iceServers: RTCIceServer[];
  roomTtlMinutes: number;
  activeTransferIdleTimeoutSeconds: number;
  chunkSizeBytes: number;
}

interface RoomCreateResponse {
  room_id: string;
  code: string;
  expires_at: string;
}

interface RoomJoinResponse {
  room_id: string;
  file_name: string;
  file_size: number;
  mime_type: string;
  expires_at: string;
}

interface ManifestMessage {
  type: "manifest";
  transfer_id: string;
  file_name: string;
  file_size: number;
  mime_type: string;
  chunk_size: number;
  chunk_count: number;
  hash_algorithm: null;
}

interface AckMessage {
  type: "ack";
  transfer_id: string | null;
  received_bytes: number;
  last_chunk_index: number;
}

interface SenderFinishedMessage {
  type: "sender-finished";
  transfer_id: string;
  total_bytes: number;
  last_chunk_index: number;
}

interface ReceiverCompleteMessage {
  type: "receiver-complete";
  transfer_id: string;
  total_bytes: number;
  last_chunk_index: number;
}

interface ErrorMessage {
  type: "error";
  transfer_id: string | null;
  message: string;
}

interface WsMessage {
  type: string;
  sdp?: RTCSessionDescriptionInit;
  candidate?: RTCIceCandidateInit;
  bytes_sent?: number;
  bytes_received?: number;
  total_bytes?: number;
  direct_p2p?: boolean;
  turn_used?: boolean;
  reason?: string;
  role?: Role;
}

const DEFAULT_SERVER_URL = "https://files.dcout.site";
const SECONDARY_SERVER_URL = "https://files.sexyminup.site";
const LEGACY_DEFAULT_SERVER_URL = "https://files.sexyminup.site";
const DEFAULT_CHUNK_SIZE = 64 * 1024;
const HIGH_WATER_BYTES = 32 * 1024 * 1024;
const LOW_WATER_BYTES = 8 * 1024 * 1024;
const ACK_CHUNK_INTERVAL = 64;

type IconName = "send" | "download" | "settings" | "upload" | "save" | "refresh";

const state: {
  serverUrl: string;
  config: ConfigResponse | null;
  room: RoomCreateResponse | RoomJoinResponse | null;
  file: File | null;
  ws: SignalingSocket | null;
  pc: RTCPeerConnection | null;
  controlChannel: RTCDataChannel | null;
  fileChannel: RTCDataChannel | null;
  writer: FileSystemWritableFileStream | null;
  writeQueue: Promise<void>;
  manifest: ManifestMessage | null;
  transferId: string | null;
  startedAt: number;
  bytesSent: number;
  ackedBytes: number;
  bytesReceived: number;
  chunksSent: number;
  chunkIndex: number;
  sendingStarted: boolean;
  senderFinished: boolean;
  receiverSenderFinished: boolean;
  completed: boolean;
  failed: boolean;
  lastAckAt: number;
  lastProgressAt: number;
  missingDataTimer: number | null;
} = {
  serverUrl: loadServerUrl(),
  config: null,
  room: null,
  file: null,
  ws: null,
  pc: null,
  controlChannel: null,
  fileChannel: null,
  writer: null,
  writeQueue: Promise.resolve(),
  manifest: null,
  transferId: null,
  startedAt: 0,
  bytesSent: 0,
  ackedBytes: 0,
  bytesReceived: 0,
  chunksSent: 0,
  chunkIndex: -1,
  sendingStarted: false,
  senderFinished: false,
  receiverSenderFinished: false,
  completed: false,
  failed: false,
  lastAckAt: 0,
  lastProgressAt: 0,
  missingDataTimer: null,
};

document.querySelector<HTMLDivElement>("#app")!.innerHTML = `
  <main class="app-shell">
    <header class="app-header">
      <div>
        <p class="eyebrow">Windows desktop</p>
        <h1>sand honey where</h1>
        <p>파일은 서버에 저장되지 않고 WebRTC DataChannel로만 전송됩니다.</p>
      </div>
      <div class="server-chip" id="currentServerChip">${escapeHtml(formatServerLabel(state.serverUrl))}</div>
    </header>

    <nav class="tabs" aria-label="주요 메뉴">
      <button class="tab active" data-tab="send" aria-selected="true">${icon("send")}<span>보내기</span></button>
      <button class="tab" data-tab="receive" aria-selected="false">${icon("download")}<span>받기</span></button>
      <button class="tab" data-tab="settings" aria-selected="false">${icon("settings")}<span>설정</span></button>
    </nav>

    <div class="content-grid">
      <section id="send" class="panel active">
        <div class="section-title">
          <h2>파일 보내기</h2>
          <span>Sender</span>
        </div>
        <button id="pickFileButton" class="action-button secondary" type="button">${icon("upload")}<span>보낼 파일 선택</span></button>
        <input id="fileInput" class="file-input" type="file" aria-hidden="true" tabindex="-1" />
        <dl class="meta" id="sendFileMeta">
          <div><dt>파일</dt><dd id="sendFileName">-</dd></div>
          <div><dt>크기</dt><dd id="sendFileSize">-</dd></div>
          <div><dt>형식</dt><dd id="sendMimeType">-</dd></div>
        </dl>
        <button id="createRoomButton" class="action-button primary" disabled>${icon("send")}<span>6자리 코드 만들기</span></button>
        <div class="code-block" id="roomCodeBlock" hidden>
          <div class="code-label">상대 입력 코드</div>
          <div class="code" id="roomCode">------</div>
        </div>
      </section>

      <section id="receive" class="panel">
        <div class="section-title">
          <h2>파일 받기</h2>
          <span>Receiver</span>
        </div>
        <label class="field-label" for="codeInput">6자리 코드</label>
        <div class="code-entry">
          <input id="codeInput" maxlength="6" inputmode="numeric" autocomplete="one-time-code" placeholder="000000" />
          <button id="joinRoomButton" class="action-button primary">${icon("download")}<span>조회</span></button>
        </div>
        <dl class="meta">
          <div><dt>파일</dt><dd id="receiveFileName">-</dd></div>
          <div><dt>크기</dt><dd id="receiveFileSize">-</dd></div>
          <div><dt>형식</dt><dd id="receiveMime">-</dd></div>
        </dl>
        <button id="saveAndReceiveButton" class="action-button secondary" disabled>${icon("save")}<span>저장 위치 선택 후 받기</span></button>
      </section>

      <section id="settings" class="panel">
        <div class="section-title">
          <h2>설정</h2>
          <span>Server</span>
        </div>
        <label class="field-label" for="serverUrlInput">서버 URL</label>
        <input id="serverUrlInput" value="${escapeHtml(state.serverUrl)}" spellcheck="false" />
        <button id="saveServerButton" class="action-button primary">${icon("save")}<span>서버 URL 저장</span></button>
        <div class="setting-note">
          <div><strong>기본값</strong><span>${DEFAULT_SERVER_URL}</span></div>
          <div><strong>보조 예시</strong><span>${SECONDARY_SERVER_URL}</span></div>
        </div>
      </section>

      <section class="status-card">
        <div class="status-header">
          <h2>전송 상태</h2>
          <span class="status-badge" id="statusBadge" data-status="idle">대기 중</span>
        </div>
        <progress id="progressBar" value="0" max="100"></progress>
        <dl class="meta status-meta">
          <div><dt>상태</dt><dd id="statusText">대기 중</dd></div>
          <div><dt>연결</dt><dd id="connectionText">대기 중</dd></div>
          <div><dt>경로</dt><dd id="pathText">대기 중</dd></div>
          <div><dt>진행률</dt><dd id="progressText">0.0%</dd></div>
          <div><dt>전송량</dt><dd id="bytesText">-</dd></div>
          <div><dt>속도</dt><dd id="speedText">-</dd></div>
          <div><dt>남은 시간</dt><dd id="etaText">-</dd></div>
        </dl>
        <p id="errorText" class="error"></p>
        <button id="resetButton" class="action-button ghost">${icon("refresh")}<span>초기화</span></button>
      </section>
    </div>
  </main>
`;

bindUi();
syncUi();

function bindUi(): void {
  document.querySelectorAll<HTMLButtonElement>(".tab").forEach((button) => {
    button.addEventListener("click", () => {
      const tab = button.dataset.tab || "send";
      document.querySelectorAll<HTMLButtonElement>(".tab").forEach((tabButton) => {
        const active = tabButton.dataset.tab === tab;
        tabButton.classList.toggle("active", active);
        tabButton.setAttribute("aria-selected", String(active));
      });
      document.querySelectorAll(".panel").forEach((el) => el.classList.remove("active"));
      byId(tab).classList.add("active");
    });
  });
  byId<HTMLInputElement>("fileInput").addEventListener("change", (event) => {
    const input = event.target as HTMLInputElement;
    state.file = input.files?.[0] || null;
    if (state.file) {
      setStatus("파일 선택됨");
      updateProgress(0, state.file.size);
      byId("roomCodeBlock").hidden = true;
      setText("roomCode", "------");
    }
    syncSendFileUi();
  });
  byId<HTMLButtonElement>("pickFileButton").addEventListener("click", () => {
    byId<HTMLInputElement>("fileInput").click();
  });
  byId<HTMLInputElement>("codeInput").addEventListener("input", (event) => {
    const input = event.target as HTMLInputElement;
    input.value = input.value.replace(/\D/g, "").slice(0, 6);
  });
  byId<HTMLButtonElement>("createRoomButton").addEventListener("click", createRoom);
  byId<HTMLButtonElement>("joinRoomButton").addEventListener("click", joinRoom);
  byId<HTMLButtonElement>("saveAndReceiveButton").addEventListener("click", prepareWriterAndConnect);
  byId<HTMLButtonElement>("saveServerButton").addEventListener("click", saveServerUrl);
  byId<HTMLButtonElement>("resetButton").addEventListener("click", resetTransfer);
}

async function createRoom(): Promise<void> {
  clearError();
  resetRuntime();
  if (!state.file) {
    setError("보낼 파일을 선택하세요.");
    return;
  }
  try {
    setStatus("서버 연결 중");
    state.config = await apiJson<ConfigResponse>("/api/config");
    setStatus("방 생성 중");
    state.room = await apiJson<RoomCreateResponse>("/api/rooms", {
      method: "POST",
      body: JSON.stringify({
        file_name: state.file.name,
        file_size: state.file.size,
        mime_type: state.file.type || "application/octet-stream",
        client_type: "windows",
      }),
    });
    state.transferId = crypto.randomUUID();
    setText("roomCode", state.room.code);
    byId("roomCodeBlock").hidden = false;
    setStatus("상대 접속 대기 중");
    await openWebSocket(state.room.room_id, "sender");
  } catch (error) {
    failTransfer(errorMessage(error));
  }
}

async function joinRoom(): Promise<void> {
  clearError();
  resetRuntime();
  const code = byId<HTMLInputElement>("codeInput").value.trim();
  if (!/^\d{6}$/.test(code)) {
    setError("6자리 코드를 입력하세요.");
    return;
  }
  try {
    setStatus("서버 연결 중");
    state.config = await apiJson<ConfigResponse>("/api/config");
    state.room = await apiJson<RoomJoinResponse>("/api/rooms/join", {
      method: "POST",
      body: JSON.stringify({ code, client_type: "windows" }),
    });
    setText("receiveFileName", state.room.file_name);
    setText("receiveFileSize", formatBytes(state.room.file_size));
    setText("receiveMime", state.room.mime_type || "application/octet-stream");
    byId<HTMLButtonElement>("saveAndReceiveButton").disabled = false;
    setStatus("저장 위치 선택 대기 중");
    updateProgress(0, state.room.file_size);
  } catch (error) {
    failTransfer(errorMessage(error));
  }
}

async function prepareWriterAndConnect(): Promise<void> {
  clearError();
  if (!state.room || !("file_name" in state.room)) {
    setError("먼저 코드를 조회하세요.");
    return;
  }
  if (!window.showSaveFilePicker) {
    setError("이 WebView는 File System Access API 저장 스트리밍을 지원하지 않습니다. Phase 2 native streaming bridge가 필요합니다.");
    return;
  }
  try {
    const handle = await window.showSaveFilePicker({
      suggestedName: state.room.file_name,
      types: [{ description: "원본 파일", accept: { [state.room.mime_type || "application/octet-stream"]: [extensionOf(state.room.file_name)] } }],
    });
    state.writer = await handle.createWritable();
    setStatus("상대 접속 대기 중");
    await openWebSocket(state.room.room_id, "receiver");
  } catch (error) {
    failTransfer(errorMessage(error));
  }
}

async function openWebSocket(roomId: string, role: Role): Promise<void> {
  const url = webSocketUrl(roomId, role);
  if (isTauriApp()) {
    let socket: TauriWebSocket | null = null;
    const adapter: SignalingSocket = {
      send: (data) => socket?.send(data),
      close: () => socket?.disconnect(),
      isOpen: () => socket !== null,
    };
    state.ws = adapter;
    try {
      socket = await TauriWebSocket.connect(url, {
        headers: { Origin: state.serverUrl.replace(/\/$/, "") },
      });
    } catch (error) {
      if (state.ws === adapter) state.ws = null;
      failTransfer(errorMessage(error));
      return;
    }
    if (state.ws !== adapter) {
      await socket.disconnect();
      return;
    }
    socket.addListener((message) => {
      void handleNativeWebSocketMessage(message, role);
    });
    sendWs({ type: role === "sender" ? "sender-ready" : "receiver-ready" });
    setStatus(role === "sender" ? "코드 대기 중" : "WebRTC 연결 중");
    return;
  }

  const socket = new WebSocket(url);
  state.ws = {
    send: (data) => socket.send(data),
    close: () => socket.close(),
    isOpen: () => socket.readyState === WebSocket.OPEN,
  };
  socket.onopen = () => {
    sendWs({ type: role === "sender" ? "sender-ready" : "receiver-ready" });
    setStatus(role === "sender" ? "코드 대기 중" : "WebRTC 연결 중");
  };
  socket.onmessage = (event) => {
    void handleWebSocketText(String(event.data), role);
  };
  socket.onclose = () => {
    if (!state.completed && !state.failed) setText("connectionText", "signaling 종료");
  };
  socket.onerror = () => failTransfer("WebSocket signaling 오류");
}

async function handleNativeWebSocketMessage(message: TauriWebSocketMessage, role: Role): Promise<void> {
  if (message.type === "Text") {
    await handleWebSocketText(message.data, role);
  } else if (message.type === "Close" && !state.completed && !state.failed) {
    setText("connectionText", "signaling 종료");
  }
}

async function handleWebSocketText(data: string, role: Role): Promise<void> {
  const message = JSON.parse(data) as WsMessage;
  try {
    if (message.type === "receiver-ready" && role === "sender") {
      await createOffer();
    } else if (message.type === "offer" && role === "receiver" && message.sdp) {
      await acceptOffer(message.sdp);
    } else if (message.type === "answer" && role === "sender" && message.sdp) {
      await state.pc?.setRemoteDescription(message.sdp);
    } else if (message.type === "ice-candidate" && message.candidate) {
      await state.pc?.addIceCandidate(new RTCIceCandidate(message.candidate));
    }
  } catch (error) {
    failTransfer(errorMessage(error));
  }
}

async function createOffer(): Promise<void> {
  if (state.pc) return;
  state.pc = createPeerConnection("sender");
  state.controlChannel = state.pc.createDataChannel("control", { ordered: true });
  state.fileChannel = state.pc.createDataChannel("file", { ordered: true });
  configureFileChannel(state.fileChannel);
  setupControlChannel();
  const offer = await state.pc.createOffer();
  await state.pc.setLocalDescription(offer);
  sendWs({ type: "offer", sdp: state.pc.localDescription || offer });
}

async function acceptOffer(sdp: RTCSessionDescriptionInit): Promise<void> {
  state.pc = createPeerConnection("receiver");
  state.pc.ondatachannel = (event) => {
    if (event.channel.label === "control") {
      state.controlChannel = event.channel;
      setupControlChannel();
    } else if (event.channel.label === "file") {
      state.fileChannel = event.channel;
      configureFileChannel(event.channel);
      setupFileChannel(event.channel);
    }
  };
  await state.pc.setRemoteDescription(sdp);
  const answer = await state.pc.createAnswer();
  await state.pc.setLocalDescription(answer);
  sendWs({ type: "answer", sdp: state.pc.localDescription || answer });
}

function createPeerConnection(role: Role): RTCPeerConnection {
  const pc = new RTCPeerConnection({ iceServers: state.config?.iceServers || [] });
  pc.onicecandidate = (event) => {
    if (event.candidate) sendWs({ type: "ice-candidate", candidate: event.candidate.toJSON() });
  };
  pc.onconnectionstatechange = () => {
    setText("connectionText", pc.connectionState);
    if (pc.connectionState === "connected") {
      setStatus("직접 연결됨");
      setTimeout(() => reportConnectionInfo(pc, role), 1200);
    }
  };
  return pc;
}

function setupControlChannel(): void {
  if (!state.controlChannel) return;
  state.controlChannel.onopen = () => {
    if (state.file) {
      const transferId = state.transferId;
      if (!transferId) {
        failTransfer("transfer_id가 준비되지 않았습니다.");
        return;
      }
      const chunkSize = resolveChunkSize();
      state.controlChannel?.send(JSON.stringify({
        type: "manifest",
        transfer_id: transferId,
        file_name: state.file.name,
        file_size: state.file.size,
        mime_type: state.file.type || "application/octet-stream",
        chunk_size: chunkSize,
        chunk_count: Math.ceil(state.file.size / chunkSize),
        hash_algorithm: null,
      } satisfies ManifestMessage));
    }
  };
  state.controlChannel.onmessage = (event) => {
    const message = JSON.parse(event.data as string) as { type: string };
    if (message.type === "manifest") handleManifest(message as ManifestMessage);
    else if (message.type === "ack") void handleAck(message as AckMessage);
    else if (message.type === "sender-finished" || message.type === "complete") handleSenderFinished(message as SenderFinishedMessage);
    else if (message.type === "receiver-complete") handleReceiverComplete(message as ReceiverCompleteMessage);
    else if (message.type === "error") failTransfer((message as ErrorMessage).message || "peer 오류");
  };
}

function setupFileChannel(channel: RTCDataChannel): void {
  channel.binaryType = "arraybuffer";
  channel.onmessage = (event) => {
    state.writeQueue = state.writeQueue
      .then(() => writeChunk(event.data))
      .catch((error: unknown) => failTransfer(errorMessage(error)));
  };
}

function handleManifest(manifest: ManifestMessage): void {
  if (!state.room || !("file_size" in state.room)) return;
  if (manifest.file_size !== state.room.file_size) {
    failTransfer("파일 크기 metadata가 일치하지 않습니다.");
    return;
  }
  state.manifest = manifest;
  state.bytesReceived = 0;
  state.chunkIndex = -1;
  state.receiverSenderFinished = false;
  state.startedAt = performance.now();
  setStatus("전송 중");
  sendWs({ type: "transfer-started" });
  sendAck(true);
}

async function handleAck(message: AckMessage): Promise<void> {
  state.ackedBytes = Math.max(state.ackedBytes, message.received_bytes || 0);
  updateProgress(state.ackedBytes, state.file?.size || 0);
  if (message.received_bytes === 0 && !state.sendingStarted) {
    await sendFileChunks();
  } else if (state.senderFinished && !state.completed) {
    setStatus("수신 완료 확인 대기 중");
  }
}

function handleSenderFinished(message: SenderFinishedMessage): void {
  state.receiverSenderFinished = true;
  const expected = state.manifest?.file_size || 0;
  if (message.total_bytes !== expected) {
    failTransfer("송신자가 보고한 전체 크기가 원본 파일 크기와 일치하지 않습니다.");
    return;
  }
  if (state.bytesReceived < expected) {
    setStatus("남은 데이터 대기 중");
    scheduleMissingDataTimeout();
  }
  void maybeFinalizeReceive();
}

function handleReceiverComplete(message: ReceiverCompleteMessage): void {
  if (!state.file || message.total_bytes !== state.file.size) {
    failTransfer("수신 완료 크기가 원본과 일치하지 않습니다.");
    return;
  }
  state.completed = true;
  updateProgress(state.file.size, state.file.size);
  setStatus("완료");
}

async function sendFileChunks(): Promise<void> {
  if (!state.file || !state.fileChannel) return;
  state.sendingStarted = true;
  state.startedAt = performance.now();
  state.bytesSent = 0;
  state.chunksSent = 0;
  setStatus("전송 중");
  sendWs({ type: "transfer-started" });
  await waitForOpen(state.fileChannel);
  const chunkSize = resolveChunkSize();
  for (let offset = 0; offset < state.file.size; offset += chunkSize) {
    await waitForSendBuffer(state.fileChannel, chunkSize, 30000);
    const chunk = await state.file.slice(offset, Math.min(offset + chunkSize, state.file.size)).arrayBuffer();
    state.fileChannel.send(chunk);
    state.bytesSent += chunk.byteLength;
    state.chunksSent += 1;
    const now = Date.now();
    if (now - state.lastProgressAt >= 1000) {
      state.lastProgressAt = now;
      sendWs({ type: "transfer-progress", bytes_sent: state.bytesSent });
    }
  }
  setStatus("전송 버퍼 비우는 중");
  await waitForBufferBelow(state.fileChannel, bufferThresholds(chunkSize).low, activeTimeoutMs());
  state.senderFinished = true;
  state.controlChannel?.send(JSON.stringify({
    type: "sender-finished",
    transfer_id: state.transferId || "",
    total_bytes: state.file.size,
    last_chunk_index: state.chunksSent - 1,
  } satisfies SenderFinishedMessage));
  setStatus("수신 완료 확인 대기 중");
}

async function writeChunk(data: ArrayBuffer | Blob): Promise<void> {
  if (state.completed || state.failed || !state.manifest) return;
  const arrayBuffer = data instanceof Blob ? await data.arrayBuffer() : data;
  const view = new Uint8Array(arrayBuffer);
  const expected = state.manifest.file_size;
  if (state.bytesReceived + view.byteLength > expected) {
    failTransfer(sizeFailureMessage(expected, state.bytesReceived + view.byteLength));
    return;
  }
  await state.writer?.write(view);
  state.bytesReceived += view.byteLength;
  state.chunkIndex += 1;
  updateProgress(state.bytesReceived, expected);
  sendPeriodicAck();
  const now = Date.now();
  if (now - state.lastProgressAt >= 1000) {
    state.lastProgressAt = now;
    sendWs({ type: "transfer-progress", bytes_received: state.bytesReceived });
  }
  if (state.receiverSenderFinished && state.bytesReceived < expected) scheduleMissingDataTimeout();
  await maybeFinalizeReceive();
}

async function maybeFinalizeReceive(): Promise<void> {
  if (state.completed || state.failed || !state.receiverSenderFinished || !state.manifest) return;
  if (state.bytesReceived !== state.manifest.file_size) return;
  clearMissingDataTimeout();
  setStatus("검증 중");
  await state.writer?.close();
  state.writer = null;
  state.completed = true;
  updateProgress(state.bytesReceived, state.manifest.file_size);
  state.controlChannel?.send(JSON.stringify({
    type: "receiver-complete",
    transfer_id: state.manifest.transfer_id,
    total_bytes: state.bytesReceived,
    last_chunk_index: state.chunkIndex,
  } satisfies ReceiverCompleteMessage));
  sendWs({
    type: "transfer-completed",
    total_bytes: state.bytesReceived,
    direct_p2p: byId("pathText").textContent !== "TURN 중계 가능성 있음",
    turn_used: byId("pathText").textContent === "TURN 중계 가능성 있음",
  });
  setStatus("완료");
}

function sendPeriodicAck(): void {
  const now = Date.now();
  if (now - state.lastAckAt >= 1000 || state.chunkIndex % ACK_CHUNK_INTERVAL === 0 || state.bytesReceived === state.manifest?.file_size) {
    sendAck(false);
    state.lastAckAt = now;
  }
}

function sendAck(initial: boolean): void {
  state.controlChannel?.send(JSON.stringify({
    type: "ack",
    transfer_id: state.manifest?.transfer_id || null,
    received_bytes: initial ? 0 : state.bytesReceived,
    last_chunk_index: state.chunkIndex,
  } satisfies AckMessage));
}

async function reportConnectionInfo(pc: RTCPeerConnection, role: Role): Promise<void> {
  const stats = await pc.getStats();
  const reports = Array.from(stats.values());
  const selected = reports.find((report) =>
    report.type === "candidate-pair" &&
    (report.selected || report.nominated) &&
    report.state === "succeeded"
  );
  const local = reports.find((report) => selected && report.id === selected.localCandidateId);
  const remote = reports.find((report) => selected && report.id === selected.remoteCandidateId);
  const turnUsed = local?.candidateType === "relay" || remote?.candidateType === "relay";
  setText("pathText", turnUsed ? "TURN 중계 가능성 있음" : "직접 연결됨");
  sendWs({ type: "connection-info", role, direct_p2p: !turnUsed, turn_used: turnUsed });
}

function configureFileChannel(channel: RTCDataChannel): void {
  channel.binaryType = "arraybuffer";
  channel.bufferedAmountLowThreshold = bufferThresholds(resolveChunkSize()).low;
}

function resolveChunkSize(): number {
  const configured = Number(state.config?.chunkSizeBytes) || DEFAULT_CHUNK_SIZE;
  const maxMessageSize = Number(state.pc?.sctp?.maxMessageSize) || 0;
  if (Number.isFinite(maxMessageSize) && maxMessageSize > 0 && configured > maxMessageSize) {
    return Math.max(1, Math.floor(maxMessageSize));
  }
  return configured;
}

function bufferThresholds(chunkSize: number): { low: number; high: number } {
  const normalizedChunkSize = Math.max(1, Number(chunkSize) || DEFAULT_CHUNK_SIZE);
  const low = Math.max(normalizedChunkSize * 4, LOW_WATER_BYTES);
  const high = Math.max(low + normalizedChunkSize, HIGH_WATER_BYTES);
  return { low, high };
}

function waitForOpen(channel: RTCDataChannel): Promise<void> {
  if (channel.readyState === "open") return Promise.resolve();
  return new Promise((resolve, reject) => {
    channel.onopen = () => resolve();
    channel.onerror = () => reject(new Error("DataChannel open failed"));
  });
}

function waitForSendBuffer(channel: RTCDataChannel, chunkSize: number, timeoutMs: number): Promise<void> {
  const thresholds = bufferThresholds(chunkSize);
  if (channel.bufferedAmount <= thresholds.high) return Promise.resolve();
  return waitForBufferBelow(channel, thresholds.low, timeoutMs);
}

function waitForBufferBelow(channel: RTCDataChannel, threshold: number, timeoutMs: number): Promise<void> {
  if (channel.bufferedAmount <= threshold) return Promise.resolve();
  return new Promise((resolve, reject) => {
    const started = Date.now();
    let timer: number | null = null;
    let settled = false;
    const cleanup = () => {
      if (timer !== null) window.clearInterval(timer);
      channel.removeEventListener("bufferedamountlow", check);
    };
    const finish = (error?: Error) => {
      if (settled) return;
      settled = true;
      cleanup();
      if (error) reject(error);
      else resolve();
    };
    const check = () => {
      if (channel.readyState === "closed") {
        finish(new Error("DataChannel closed before buffered data drained"));
      } else if (channel.bufferedAmount <= threshold) {
        finish();
      } else if (Date.now() - started > timeoutMs) {
        finish(new Error("DataChannel bufferedAmount drain timeout"));
      }
    };
    channel.addEventListener("bufferedamountlow", check);
    timer = window.setInterval(check, 50);
    check();
  });
}

async function apiJson<T>(path: string, options?: RequestInit): Promise<T> {
  const fetchImpl = isTauriApp() ? tauriFetch : fetch;
  const response = await fetchImpl(state.serverUrl.replace(/\/$/, "") + path, {
    headers: { "Content-Type": "application/json" },
    ...options,
  });
  const text = await response.text();
  const data = text ? JSON.parse(text) : {};
  if (!response.ok) throw new Error(data.detail || `HTTP ${response.status}`);
  return data as T;
}

function webSocketUrl(roomId: string, role: Role): string {
  const clean = state.serverUrl.replace(/\/$/, "");
  const base = clean.startsWith("https://")
    ? `wss://${clean.slice("https://".length)}`
    : `ws://${clean.replace(/^http:\/\//, "")}`;
  return `${base}/ws/${encodeURIComponent(roomId)}/${role}`;
}

function sendWs(payload: WsMessage): void {
  if (state.ws?.isOpen()) {
    void state.ws.send(JSON.stringify(payload));
  }
}

function saveServerUrl(): void {
  const value = byId<HTMLInputElement>("serverUrlInput").value.trim().replace(/\/$/, "");
  if (!isAllowedServerUrl(value)) {
    setError("서버 URL은 https:// 를 권장하며 개발용 http://127.0.0.1 또는 localhost만 허용합니다.");
    return;
  }
  state.serverUrl = value;
  localStorage.setItem("serverUrl", value);
  setText("currentServerChip", formatServerLabel(value));
  setStatus("설정 저장됨");
  clearError();
}

function isAllowedServerUrl(value: string): boolean {
  return value.startsWith("https://") || value.startsWith("http://127.0.0.1") || value.startsWith("http://localhost");
}

function scheduleMissingDataTimeout(): void {
  clearMissingDataTimeout();
  state.missingDataTimer = window.setTimeout(() => {
    const expected = state.manifest?.file_size || 0;
    if (!state.completed && state.bytesReceived < expected) {
      failTransfer(sizeFailureMessage(expected, state.bytesReceived));
    }
  }, activeTimeoutMs());
}

function clearMissingDataTimeout(): void {
  if (state.missingDataTimer !== null) {
    window.clearTimeout(state.missingDataTimer);
    state.missingDataTimer = null;
  }
}

function activeTimeoutMs(): number {
  return Math.max(1, state.config?.activeTransferIdleTimeoutSeconds || 180) * 1000;
}

function failTransfer(message: string): void {
  if (state.completed || state.failed) return;
  state.failed = true;
  clearMissingDataTimeout();
  state.controlChannel?.send(JSON.stringify({ type: "error", transfer_id: state.transferId || state.manifest?.transfer_id || null, message } satisfies ErrorMessage));
  sendWs({ type: "transfer-failed", reason: message });
  void state.writer?.close().catch(() => undefined);
  state.writer = null;
  setStatus("실패");
  setError(message);
}

function resetTransfer(): void {
  state.ws?.close();
  state.pc?.close();
  void state.writer?.close().catch(() => undefined);
  resetRuntime();
  setStatus("대기 중");
  setText("connectionText", "대기 중");
  setText("pathText", "대기 중");
  setText("roomCode", "------");
  byId("roomCodeBlock").hidden = true;
  clearReceiveFileUi();
  syncSendFileUi();
  updateProgress(0, 0);
  clearError();
}

function resetRuntime(): void {
  clearMissingDataTimeout();
  state.config = null;
  state.room = null;
  state.ws = null;
  state.pc = null;
  state.controlChannel = null;
  state.fileChannel = null;
  state.writer = null;
  state.writeQueue = Promise.resolve();
  state.manifest = null;
  state.transferId = null;
  state.startedAt = 0;
  state.bytesSent = 0;
  state.ackedBytes = 0;
  state.bytesReceived = 0;
  state.chunksSent = 0;
  state.chunkIndex = -1;
  state.sendingStarted = false;
  state.senderFinished = false;
  state.receiverSenderFinished = false;
  state.completed = false;
  state.failed = false;
  state.lastAckAt = 0;
  state.lastProgressAt = 0;
}

function updateProgress(bytes: number, total: number): void {
  const percent = total > 0 ? Math.min(100, (bytes / total) * 100) : 0;
  const elapsed = state.startedAt ? (performance.now() - state.startedAt) / 1000 : 0;
  const speed = elapsed > 0 ? bytes / elapsed : 0;
  const eta = speed > 0 ? (total - bytes) / speed : NaN;
  byId<HTMLProgressElement>("progressBar").value = percent;
  setText("progressText", `${percent.toFixed(1)}%`);
  setText("bytesText", total > 0 ? `${formatBytes(bytes)} / ${formatBytes(total)}` : "-");
  setText("speedText", speed > 0 ? `${formatBytes(speed)}/s` : "-");
  setText("etaText", Number.isFinite(eta) ? formatDuration(eta) : "-");
}

function formatBytes(bytes: number): string {
  const units = ["B", "KB", "MB", "GB", "TB"];
  let value = bytes;
  let index = 0;
  while (value >= 1024 && index < units.length - 1) {
    value /= 1024;
    index += 1;
  }
  return `${index === 0 ? value.toFixed(0) : value.toFixed(1)} ${units[index]}`;
}

function formatDuration(seconds: number): string {
  const whole = Math.max(0, Math.round(seconds));
  const minutes = Math.floor(whole / 60);
  const rest = whole % 60;
  return `${String(minutes).padStart(2, "0")}:${String(rest).padStart(2, "0")}`;
}

function extensionOf(fileName: string): string {
  const dot = fileName.lastIndexOf(".");
  return dot >= 0 ? fileName.slice(dot) : ".bin";
}

function sizeFailureMessage(expected: number, received: number): string {
  const missing = Math.max(0, expected - received);
  return `받은 파일 크기가 원본 파일 크기와 일치하지 않습니다. expected size: ${expected}, received size: ${received}, missing bytes: ${missing}`;
}

function setStatus(value: string): void {
  setText("statusText", value);
  const badge = byId("statusBadge");
  badge.textContent = value;
  badge.dataset.status = statusTone(value);
}

function setError(message: string): void {
  setText("errorText", message);
}

function clearError(): void {
  setText("errorText", "");
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

function isTauriApp(): boolean {
  return "__TAURI_INTERNALS__" in window;
}

function byId<T extends HTMLElement = HTMLElement>(id: string): T {
  return document.getElementById(id) as T;
}

function setText(id: string, value: string): void {
  byId(id).textContent = value;
}

function syncUi(): void {
  syncSendFileUi();
  clearReceiveFileUi();
  setStatus("대기 중");
}

function syncSendFileUi(): void {
  const file = state.file;
  setText("sendFileName", file?.name || "-");
  setText("sendFileSize", file ? formatBytes(file.size) : "-");
  setText("sendMimeType", file?.type || (file ? "application/octet-stream" : "-"));
  byId<HTMLButtonElement>("createRoomButton").disabled = !file;
  byId("sendFileMeta").classList.toggle("is-empty", !file);
}

function clearReceiveFileUi(): void {
  setText("receiveFileName", "-");
  setText("receiveFileSize", "-");
  setText("receiveMime", "-");
  byId<HTMLButtonElement>("saveAndReceiveButton").disabled = true;
}

function statusTone(value: string): string {
  if (value === "완료") return "complete";
  if (value === "실패") return "error";
  if (value === "대기 중" || value === "파일 선택됨" || value.includes("대기")) return "idle";
  return "active";
}

function formatServerLabel(value: string): string {
  return value.replace(/^https?:\/\//, "").replace(/\/$/, "");
}

function loadServerUrl(): string {
  const saved = (localStorage.getItem("serverUrl") || "").trim().replace(/\/$/, "");
  return !saved || saved === LEGACY_DEFAULT_SERVER_URL ? DEFAULT_SERVER_URL : saved;
}

function icon(name: IconName): string {
  const paths: Record<IconName, string> = {
    send: '<path d="M22 2 11 13"></path><path d="m22 2-7 20-4-9-9-4 20-7z"></path>',
    download: '<path d="M12 3v12"></path><path d="m7 10 5 5 5-5"></path><path d="M5 21h14"></path>',
    settings: '<path d="M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8z"></path><path d="M4.9 6.6 6.6 4.9"></path><path d="m17.4 4.9 1.7 1.7"></path><path d="m4.9 17.4 1.7 1.7"></path><path d="m17.4 19.1 1.7-1.7"></path><path d="M2 12h2"></path><path d="M20 12h2"></path><path d="M12 2v2"></path><path d="M12 20v2"></path>',
    upload: '<path d="M12 21V9"></path><path d="m7 14 5-5 5 5"></path><path d="M5 3h14"></path>',
    save: '<path d="M19 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11l5 5v11a2 2 0 0 1-2 2z"></path><path d="M17 21v-8H7v8"></path><path d="M7 3v5h8"></path>',
    refresh: '<path d="M21 12a9 9 0 0 1-15.5 6.2"></path><path d="M3 12A9 9 0 0 1 18.5 5.8"></path><path d="M3 18v-6h6"></path><path d="M21 6v6h-6"></path>',
  };
  return `<svg class="icon" viewBox="0 0 24 24" aria-hidden="true" focusable="false">${paths[name]}</svg>`;
}

function escapeHtml(value: string): string {
  return value.replace(/[&<>"']/g, (char) => ({
    "&": "&amp;",
    "<": "&lt;",
    ">": "&gt;",
    "\"": "&quot;",
    "'": "&#039;",
  })[char] || char);
}
