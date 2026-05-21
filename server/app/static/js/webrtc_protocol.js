(function () {
    "use strict";

    var DEFAULT_CHUNK_SIZE = 64 * 1024;
    var MAX_SAFE_CHUNK_SIZE = 64 * 1024;
    var HIGH_WATER_BYTES = 4 * 1024 * 1024;
    var LOW_WATER_BYTES = 1024 * 1024;

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

    function resolveChunkSize(config, pc) {
        var configured = Number(config && config.chunkSizeBytes) || DEFAULT_CHUNK_SIZE;
        var maxMessageSize = pc && pc.sctp ? Number(pc.sctp.maxMessageSize) : 0;
        if (Number.isFinite(maxMessageSize) && maxMessageSize > 0 && configured > maxMessageSize) {
            configured = Math.max(1, Math.floor(maxMessageSize));
        }
        return Math.max(1, Math.min(configured, MAX_SAFE_CHUNK_SIZE));
    }

    function configureFileChannel(channel, chunkSize) {
        var thresholds = bufferThresholds(chunkSize);
        channel.binaryType = "arraybuffer";
        channel.bufferedAmountLowThreshold = thresholds.low;
    }

    function bufferThresholds(chunkSize) {
        var normalizedChunkSize = Math.max(1, Number(chunkSize) || DEFAULT_CHUNK_SIZE);
        var low = Math.max(normalizedChunkSize * 4, LOW_WATER_BYTES);
        var high = Math.max(low + normalizedChunkSize, HIGH_WATER_BYTES);
        return { low: low, high: high };
    }

    function waitForChannelBufferBelow(channel, threshold, timeoutMs) {
        if (channel.bufferedAmount <= threshold) {
            return Promise.resolve();
        }
        return new Promise(function (resolve, reject) {
            var settled = false;
            var startedAt = Date.now();
            var interval = null;

            function cleanup() {
                if (interval) {
                    window.clearInterval(interval);
                }
                if (channel.removeEventListener) {
                    channel.removeEventListener("bufferedamountlow", check);
                }
            }

            function finish(error) {
                if (settled) {
                    return;
                }
                settled = true;
                cleanup();
                if (error) {
                    reject(error);
                } else {
                    resolve();
                }
            }

            function check() {
                if (settled) {
                    return;
                }
                if (channel.readyState === "closed") {
                    finish(new Error("DataChannel closed before buffered data drained"));
                    return;
                }
                if (channel.bufferedAmount <= threshold) {
                    finish();
                    return;
                }
                if (timeoutMs && Date.now() - startedAt > timeoutMs) {
                    finish(new Error("DataChannel bufferedAmount drain timeout"));
                }
            }

            if (channel.addEventListener) {
                channel.addEventListener("bufferedamountlow", check);
            }
            interval = window.setInterval(check, 50);
            check();
        });
    }

    function waitForBufferedAmount(channel, chunkSize) {
        var thresholds = bufferThresholds(chunkSize);
        if (channel.bufferedAmount <= thresholds.high) {
            return Promise.resolve();
        }
        return waitForChannelBufferBelow(channel, thresholds.low, 180000);
    }

    function waitForDrain(channel, threshold, timeoutMs) {
        return waitForChannelBufferBelow(channel, threshold, timeoutMs || 180000);
    }

    async function sendBinary(channel, data, chunkSize, timeoutMs) {
        var startedAt = Date.now();
        var thresholds = bufferThresholds(chunkSize);
        while (true) {
            await waitForChannelBufferBelow(channel, thresholds.high, remainingTimeout(startedAt, timeoutMs));
            if (channel.readyState !== "open") {
                throw new Error("DataChannel is not open");
            }
            try {
                channel.send(data);
                return;
            } catch (error) {
                if (!isBackpressureError(error) || timedOut(startedAt, timeoutMs)) {
                    throw error;
                }
                await waitForChannelBufferBelow(channel, thresholds.low, remainingTimeout(startedAt, timeoutMs));
            }
        }
    }

    function remainingTimeout(startedAt, timeoutMs) {
        if (!timeoutMs) {
            return 180000;
        }
        return Math.max(1, timeoutMs - (Date.now() - startedAt));
    }

    function timedOut(startedAt, timeoutMs) {
        return Boolean(timeoutMs && Date.now() - startedAt > timeoutMs);
    }

    function isBackpressureError(error) {
        var name = error && error.name ? String(error.name) : "";
        var message = error && error.message ? String(error.message).toLowerCase() : "";
        return name === "OperationError" ||
            name === "InvalidStateError" ||
            message.indexOf("buffer") >= 0 ||
            message.indexOf("queue") >= 0 ||
            message.indexOf("full") >= 0;
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
        resolveChunkSize: resolveChunkSize,
        configureFileChannel: configureFileChannel,
        waitForBufferedAmount: waitForBufferedAmount,
        waitForDrain: waitForDrain,
        waitForOpen: waitForOpen,
        sendBinary: sendBinary,
        normalizeCandidate: normalizeCandidate
    };
}());
