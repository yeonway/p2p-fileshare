(function () {
    "use strict";

    var HIGH_WATER_FACTOR = 16;
    var LOW_WATER_FACTOR = 4;

    async function loadConfig() {
        return App.apiJson("/api/config");
    }

    function createPeerConnection(config, ws, role, statusCallback) {
        var pc = new RTCPeerConnection({ iceServers: config.iceServers || [] });
        pc.onicecandidate = function (event) {
            if (event.candidate) {
                App.sendWs(ws, { type: "ice-candidate", candidate: event.candidate });
            }
        };
        pc.onconnectionstatechange = function () {
            statusCallback(pc.connectionState);
            if (pc.connectionState === "connected") {
                setTimeout(function () {
                    reportConnectionInfo(pc, ws, role, statusCallback);
                }, 1200);
            }
        };
        return pc;
    }

    function configureFileChannel(channel, chunkSize) {
        channel.binaryType = "arraybuffer";
        channel.bufferedAmountLowThreshold = chunkSize * LOW_WATER_FACTOR;
    }

    function waitForBufferedAmount(channel, chunkSize) {
        if (channel.bufferedAmount <= chunkSize * HIGH_WATER_FACTOR) {
            return Promise.resolve();
        }
        return new Promise(function (resolve) {
            var timer = window.setTimeout(resolve, 3000);
            channel.onbufferedamountlow = function () {
                window.clearTimeout(timer);
                resolve();
            };
        });
    }

    function waitForOpen(channel) {
        if (channel.readyState === "open") {
            return Promise.resolve();
        }
        return new Promise(function (resolve, reject) {
            channel.onopen = function () { resolve(); };
            channel.onerror = function () { reject(new Error("DataChannel open failed")); };
        });
    }

    async function reportConnectionInfo(pc, ws, role, statusCallback) {
        var stats = await pc.getStats();
        var candidates = {};
        var selectedPair = null;
        stats.forEach(function (report) {
            candidates[report.id] = report;
            if (report.type === "candidate-pair" && (report.selected || report.nominated) && report.state === "succeeded") {
                selectedPair = report;
            }
        });
        var turnUsed = false;
        if (selectedPair) {
            var local = candidates[selectedPair.localCandidateId];
            var remote = candidates[selectedPair.remoteCandidateId];
            turnUsed = Boolean(
                local && local.candidateType === "relay" ||
                remote && remote.candidateType === "relay"
            );
        }
        var direct = !turnUsed;
        App.sendWs(ws, {
            type: "connection-info",
            role: role,
            direct_p2p: direct,
            turn_used: turnUsed
        });
        statusCallback(turnUsed ? "TURN relay" : "직접 P2P");
    }

    function normalizeCandidate(candidate) {
        if (!candidate) {
            return null;
        }
        return new RTCIceCandidate(candidate);
    }

    window.P2P = {
        loadConfig: loadConfig,
        createPeerConnection: createPeerConnection,
        configureFileChannel: configureFileChannel,
        waitForBufferedAmount: waitForBufferedAmount,
        waitForOpen: waitForOpen,
        normalizeCandidate: normalizeCandidate
    };
}());
