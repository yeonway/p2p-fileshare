(function () {
    "use strict";

    var state = {
        config: null,
        room: null,
        ws: null,
        pc: null,
        controlChannel: null,
        fileChannel: null,
        manifest: null,
        writer: null,
        fallbackChunks: [],
        fallbackMode: false,
        writeQueue: Promise.resolve(),
        bytesReceived: 0,
        chunkIndex: -1,
        startedAt: null,
        lastAckAt: 0,
        lastServerProgressAt: 0
    };

    function setError(message) {
        App.setText("errorText", message || "");
    }

    async function lookupRoom() {
        setError("");
        var code = App.byId("codeInput").value.trim();
        if (!/^\d{6}$/.test(code)) {
            setError("6자리 코드를 입력하세요.");
            return;
        }
        App.byId("lookupButton").disabled = true;
        try {
            state.config = await P2P.loadConfig();
            state.room = await App.apiJson("/api/rooms/join", {
                method: "POST",
                body: JSON.stringify({ code: code, client_type: "web" })
            });
            App.setText("fileNameText", state.room.file_name);
            App.setText("fileSizeText", App.formatBytes(state.room.file_size));
            App.setText("mimeText", state.room.mime_type || "application/octet-stream");
            App.show("transferPanel", true);
            App.show("connectButton", true);
        } catch (error) {
            App.byId("lookupButton").disabled = false;
            setError(error.message);
        }
    }

    async function prepareAndConnect() {
        setError("");
        App.byId("connectButton").disabled = true;
        try {
            await prepareWriter();
            openWebSocket();
        } catch (error) {
            App.byId("connectButton").disabled = false;
            setError(error.message);
        }
    }

    async function prepareWriter() {
        if ("showSaveFilePicker" in window) {
            var handle = await window.showSaveFilePicker({
                suggestedName: state.room.file_name
            });
            state.writer = await handle.createWritable();
            state.fallbackMode = false;
            App.show("fallbackWarning", false);
        } else {
            state.fallbackMode = true;
            state.fallbackChunks = [];
            App.show("fallbackWarning", true);
        }
    }

    function openWebSocket() {
        state.ws = new WebSocket(App.websocketUrl(state.room.room_id, "receiver"));
        state.ws.onopen = function () {
            App.setText("transferStatusText", "송신자 연결 대기 중");
            App.sendWs(state.ws, { type: "receiver-ready" });
        };
        state.ws.onmessage = async function (event) {
            var message = JSON.parse(event.data);
            if (message.type === "offer") {
                await acceptOffer(message.sdp);
            } else if (message.type === "ice-candidate" && state.pc) {
                await state.pc.addIceCandidate(P2P.normalizeCandidate(message.candidate));
            }
        };
    }

    async function acceptOffer(sdp) {
        state.pc = P2P.createPeerConnection(state.config, state.ws, "receiver", function (status) {
            if (status === "직접 P2P" || status === "TURN relay") {
                App.setText("pathStatusText", status);
            } else {
                App.setText("rtcStatusText", status);
            }
        });
        state.pc.ondatachannel = function (event) {
            if (event.channel.label === "control") {
                state.controlChannel = event.channel;
                setupControlChannel();
            } else if (event.channel.label === "file") {
                state.fileChannel = event.channel;
                P2P.configureFileChannel(state.fileChannel, state.config.chunkSizeBytes);
                setupFileChannel();
            }
        };
        await state.pc.setRemoteDescription(new RTCSessionDescription(sdp));
        var answer = await state.pc.createAnswer();
        await state.pc.setLocalDescription(answer);
        App.sendWs(state.ws, { type: "answer", sdp: state.pc.localDescription });
    }

    function setupControlChannel() {
        state.controlChannel.onmessage = function (event) {
            var message = JSON.parse(event.data);
            if (message.type === "manifest") {
                handleManifest(message);
            } else if (message.type === "complete") {
                finalizeReceive(message);
            } else if (message.type === "error") {
                App.setText("transferStatusText", "실패");
                setError(message.message || "송신자 오류");
            }
        };
    }

    function handleManifest(manifest) {
        state.manifest = manifest;
        if (manifest.file_size !== state.room.file_size) {
            sendControlError("파일 크기 metadata가 일치하지 않습니다.");
            return;
        }
        state.bytesReceived = 0;
        state.chunkIndex = -1;
        state.startedAt = performance.now();
        App.setText("transferStatusText", "전송 중");
        App.sendWs(state.ws, { type: "transfer-started" });
        sendAck(true);
    }

    function setupFileChannel() {
        state.fileChannel.binaryType = "arraybuffer";
        state.fileChannel.onmessage = function (event) {
            state.writeQueue = state.writeQueue.then(function () {
                return writeChunk(event.data);
            }).catch(function (error) {
                sendControlError(error.message);
                App.sendWs(state.ws, { type: "transfer-failed", reason: error.message });
            });
        };
    }

    async function writeChunk(data) {
        var arrayBuffer = data instanceof Blob ? await data.arrayBuffer() : data;
        var view = new Uint8Array(arrayBuffer);
        if (state.writer) {
            await state.writer.write(view);
        } else {
            state.fallbackChunks.push(arrayBuffer);
        }
        state.bytesReceived += view.byteLength;
        state.chunkIndex += 1;
        App.updateProgress(state.bytesReceived, state.room.file_size, state.startedAt);
        var now = Date.now();
        if (now - state.lastAckAt >= 1000 || state.bytesReceived === state.room.file_size) {
            sendAck(false);
            state.lastAckAt = now;
        }
        if (now - state.lastServerProgressAt >= 1000) {
            state.lastServerProgressAt = now;
            App.sendWs(state.ws, {
                type: "transfer-progress",
                bytes_received: state.bytesReceived
            });
        }
    }

    function sendAck(initial) {
        if (!state.controlChannel || state.controlChannel.readyState !== "open") {
            return;
        }
        state.controlChannel.send(JSON.stringify({
            type: "ack",
            transfer_id: state.manifest ? state.manifest.transfer_id : null,
            received_bytes: initial ? 0 : state.bytesReceived,
            last_chunk_index: state.chunkIndex
        }));
    }

    function sendControlError(message) {
        if (state.controlChannel && state.controlChannel.readyState === "open") {
            state.controlChannel.send(JSON.stringify({
                type: "error",
                transfer_id: state.manifest ? state.manifest.transfer_id : null,
                message: message
            }));
        }
        App.setText("transferStatusText", "실패");
        setError(message);
    }

    async function finalizeReceive(message) {
        await state.writeQueue;
        if (state.bytesReceived !== state.room.file_size || message.total_bytes !== state.room.file_size) {
            sendControlError("받은 파일 크기가 원본 파일 크기와 일치하지 않습니다.");
            App.sendWs(state.ws, {
                type: "transfer-failed",
                reason: "size verification failed"
            });
            return;
        }
        if (state.writer) {
            await state.writer.close();
        } else {
            var blob = new Blob(state.fallbackChunks, { type: state.room.mime_type || "application/octet-stream" });
            var link = App.byId("downloadLink");
            link.href = URL.createObjectURL(blob);
            link.download = state.room.file_name;
            App.show("downloadLink", true);
        }
        App.setText("transferStatusText", "완료");
        App.updateProgress(state.room.file_size, state.room.file_size, state.startedAt);
        state.controlChannel.send(JSON.stringify({
            type: "complete",
            transfer_id: state.manifest.transfer_id,
            total_bytes: state.bytesReceived
        }));
        App.sendWs(state.ws, {
            type: "transfer-completed",
            total_bytes: state.bytesReceived,
            direct_p2p: App.byId("pathStatusText").textContent !== "TURN relay",
            turn_used: App.byId("pathStatusText").textContent === "TURN relay"
        });
    }

    document.addEventListener("DOMContentLoaded", function () {
        App.byId("lookupButton").addEventListener("click", lookupRoom);
        App.byId("connectButton").addEventListener("click", prepareAndConnect);
    });
}());
