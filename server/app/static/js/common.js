(function () {
    "use strict";

    var deferredInstallPrompt = null;
    var wakeLock = null;
    var keepAliveActive = false;
    var beforeUnloadHandler = null;

    function byId(id) {
        return document.getElementById(id);
    }

    function setText(id, value) {
        var element = byId(id);
        if (element) {
            element.textContent = value;
        }
    }

    function show(id, visible) {
        var element = byId(id);
        if (!element) {
            return;
        }
        element.classList.toggle("hidden", !visible);
    }

    async function apiJson(path, options) {
        var response = await fetch(path, Object.assign({
            headers: { "Content-Type": "application/json" }
        }, options || {}));
        var data = await response.json().catch(function () { return {}; });
        if (!response.ok) {
            throw new Error(data.detail || "요청을 처리하지 못했습니다.");
        }
        return data;
    }

    function websocketUrl(roomId, role) {
        var scheme = window.location.protocol === "https:" ? "wss:" : "ws:";
        return scheme + "//" + window.location.host + "/ws/" + encodeURIComponent(roomId) + "/" + role;
    }

    function sendWs(ws, payload) {
        if (ws && ws.readyState === WebSocket.OPEN) {
            ws.send(JSON.stringify(payload));
        }
    }

    function formatBytes(bytes) {
        if (!Number.isFinite(bytes)) {
            return "-";
        }
        var units = ["B", "KB", "MB", "GB", "TB"];
        var value = bytes;
        var index = 0;
        while (value >= 1024 && index < units.length - 1) {
            value /= 1024;
            index += 1;
        }
        return (index === 0 ? value.toFixed(0) : value.toFixed(1)) + " " + units[index];
    }

    function formatSpeed(bytesPerSecond) {
        return formatBytes(bytesPerSecond) + "/s";
    }

    function formatDuration(seconds) {
        if (!Number.isFinite(seconds) || seconds < 0) {
            return "-";
        }
        var whole = Math.round(seconds);
        var minutes = Math.floor(whole / 60);
        var rest = whole % 60;
        return String(minutes).padStart(2, "0") + ":" + String(rest).padStart(2, "0");
    }

    function updateProgress(bytes, total, startedAt) {
        var percent = total > 0 ? Math.min(100, (bytes / total) * 100) : 0;
        var elapsed = startedAt ? (performance.now() - startedAt) / 1000 : 0;
        var speed = elapsed > 0 ? bytes / elapsed : 0;
        var eta = speed > 0 ? (total - bytes) / speed : NaN;
        setText("progressText", percent.toFixed(1) + "%");
        setText("speedText", formatSpeed(speed));
        setText("etaText", "남은 시간 " + formatDuration(eta));
        var bar = byId("progressBar");
        if (bar) {
            bar.value = percent;
        }
    }

    function makeTransferId() {
        if (window.crypto && typeof window.crypto.randomUUID === "function") {
            return window.crypto.randomUUID();
        }
        return String(Date.now()) + "-" + Math.random().toString(16).slice(2);
    }

    function queryCode() {
        return new URLSearchParams(window.location.search).get("code") || "";
    }

    function buildReceiveUrl(code) {
        return window.location.origin + "/receive?code=" + encodeURIComponent(code);
    }

    function buildAndroidIntentUrl(code) {
        var fallback = encodeURIComponent(buildReceiveUrl(code));
        return "intent://receive?code=" + encodeURIComponent(code) +
            "#Intent;scheme=sendhoney;package=site.sexyminup.p2pfileshare;S.browser_fallback_url=" +
            fallback + ";end";
    }

    async function renderQrCode(containerId, value) {
        var container = byId(containerId);
        if (!container) {
            return;
        }
        container.textContent = "";
        var response = await fetch("/api/qr/svg", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ value: value })
        });
        if (!response.ok) {
            throw new Error("QR 생성 실패");
        }
        container.innerHTML = await response.text();
    }

    var ErrorCodes = {
        UNKNOWN: "E_UNKNOWN",
        SIGNALING_CLOSED: "E_SIGNALING_CLOSED",
        SIGNALING_ERROR: "E_SIGNALING_ERROR",
        PEER_CONNECTION_FAILED: "E_PEER_CONNECTION_FAILED",
        DATA_CHANNEL_CLOSED: "E_DATA_CHANNEL_CLOSED",
        DATA_CHANNEL_TIMEOUT: "E_DATA_CHANNEL_TIMEOUT",
        TRANSFER_TIMEOUT: "E_TRANSFER_TIMEOUT",
        SIZE_MISMATCH: "E_SIZE_MISMATCH",
        METADATA_MISMATCH: "E_METADATA_MISMATCH",
        STORAGE_OPEN_FAILED: "E_STORAGE_OPEN_FAILED",
        STORAGE_WRITE_FAILED: "E_STORAGE_WRITE_FAILED",
        STORAGE_CLOSE_FAILED: "E_STORAGE_CLOSE_FAILED",
        REMOTE_ERROR: "E_REMOTE_ERROR"
    };

    function displayError(code, message) {
        return "[" + (code || ErrorCodes.UNKNOWN) + "] " + message;
    }

    function isStandaloneApp() {
        return window.matchMedia("(display-mode: standalone)").matches || window.navigator.standalone === true;
    }

    function updateInstallButton() {
        var button = byId("installAppButton");
        if (!button) {
            return;
        }
        show("installAppButton", Boolean(deferredInstallPrompt) && !isStandaloneApp());
    }

    async function promptInstall() {
        if (!deferredInstallPrompt) {
            return;
        }
        var promptEvent = deferredInstallPrompt;
        deferredInstallPrompt = null;
        updateInstallButton();
        await promptEvent.prompt();
        await promptEvent.userChoice.catch(function () { return null; });
    }

    function registerServiceWorker() {
        if (!("serviceWorker" in navigator)) {
            return;
        }
        var isLocalhost = window.location.hostname === "localhost" || window.location.hostname === "127.0.0.1";
        if (window.location.protocol !== "https:" && !isLocalhost) {
            return;
        }
        window.addEventListener("load", function () {
            navigator.serviceWorker.register("/sw.js").catch(function () { return null; });
        });
    }

    async function requestWakeLock() {
        if (!keepAliveActive || wakeLock || document.visibilityState !== "visible" || !("wakeLock" in navigator)) {
            return;
        }
        try {
            wakeLock = await navigator.wakeLock.request("screen");
            wakeLock.addEventListener("release", function () {
                wakeLock = null;
            });
        } catch (error) {
            wakeLock = null;
        }
    }

    async function releaseWakeLock() {
        var lock = wakeLock;
        wakeLock = null;
        if (lock) {
            await lock.release().catch(function () { return null; });
        }
    }

    function startTransferKeepAlive(message) {
        if (beforeUnloadHandler) {
            window.removeEventListener("beforeunload", beforeUnloadHandler);
        }
        keepAliveActive = true;
        beforeUnloadHandler = function (event) {
            event.preventDefault();
            event.returnValue = message || "전송 중입니다.";
            return event.returnValue;
        };
        window.addEventListener("beforeunload", beforeUnloadHandler);
        requestWakeLock();
    }

    function stopTransferKeepAlive() {
        keepAliveActive = false;
        if (beforeUnloadHandler) {
            window.removeEventListener("beforeunload", beforeUnloadHandler);
            beforeUnloadHandler = null;
        }
        releaseWakeLock();
    }

    window.addEventListener("beforeinstallprompt", function (event) {
        event.preventDefault();
        deferredInstallPrompt = event;
        updateInstallButton();
    });

    window.addEventListener("appinstalled", function () {
        deferredInstallPrompt = null;
        updateInstallButton();
    });

    document.addEventListener("DOMContentLoaded", function () {
        var installButton = byId("installAppButton");
        if (installButton) {
            installButton.addEventListener("click", function () {
                promptInstall();
            });
        }
        updateInstallButton();
    });

    document.addEventListener("visibilitychange", function () {
        if (document.visibilityState === "visible") {
            requestWakeLock();
        }
    });

    registerServiceWorker();

    window.App = {
        byId: byId,
        setText: setText,
        show: show,
        apiJson: apiJson,
        websocketUrl: websocketUrl,
        sendWs: sendWs,
        formatBytes: formatBytes,
        formatSpeed: formatSpeed,
        formatDuration: formatDuration,
        updateProgress: updateProgress,
        makeTransferId: makeTransferId,
        queryCode: queryCode,
        buildReceiveUrl: buildReceiveUrl,
        buildAndroidIntentUrl: buildAndroidIntentUrl,
        renderQrCode: renderQrCode,
        ErrorCodes: ErrorCodes,
        displayError: displayError,
        startTransferKeepAlive: startTransferKeepAlive,
        stopTransferKeepAlive: stopTransferKeepAlive
    };
}());
