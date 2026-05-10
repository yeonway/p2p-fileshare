(function () {
    "use strict";

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
        makeTransferId: makeTransferId
    };
}());
