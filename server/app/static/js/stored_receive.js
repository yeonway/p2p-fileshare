(function () {
    "use strict";

    var BLOB_FALLBACK_LIMIT = 512 * 1024 * 1024;
    var AUTO_RESET_DELAY_MS = 2000;

    var state = {
        transfer: null,
        downloadToken: null,
        writer: null,
        fallbackMode: false,
        downloadUrl: null,
        bytesWritten: 0,
        startedAt: 0,
        failed: false,
        completed: false,
        autoResetTimer: null
    };

    function setError(message) {
        App.setText("errorText", message || "");
    }

    function setStatus(message) {
        App.setText("transferStatusText", message || "대기 중");
    }

    function autoResetEnabled() {
        return App.byId("autoResetToggle").checked;
    }

    function saveAutoResetSetting() {
        localStorage.setItem("storedAutoReset", autoResetEnabled() ? "1" : "0");
    }

    function loadAutoResetSetting() {
        var saved = localStorage.getItem("storedAutoReset");
        App.byId("autoResetToggle").checked = saved !== "0";
    }

    async function lookupRoom() {
        clearAutoResetTimer();
        setError("");
        var code = App.byId("codeInput").value.trim();
        if (!/^\d{6}$/.test(code)) {
            setError("6자리 코드를 입력하세요.");
            return;
        }
        try {
            var response = await App.apiJson("/api/stored/transfers/join", {
                method: "POST",
                body: JSON.stringify({ code: code, client_type: "web" })
            });
            state.transfer = response;
            state.downloadToken = response.download_token;
            state.bytesWritten = 0;
            state.failed = false;
            state.completed = false;
            clearDownloadLink();
            renderMetadata(response);
            App.show("transferPanel", true);
            App.show("downloadButton", true);
            App.show("restartDownloadButton", false);
            setStatus("다운로드 대기 중");
        } catch (error) {
            setError(error.message);
        }
    }

    function renderMetadata(data) {
        App.setText("fileNameText", data.archive_name);
        App.setText("fileSizeText", App.formatBytes(data.total_size));
        App.setText("downloadSizeText", App.formatBytes(data.download_size));
        App.setText("bundleText", data.is_bundle ? "tar 묶음 " + data.entries.length + "개" : "단일 파일");
        App.updateProgress(0, data.download_size, 0);
    }

    async function startDownload() {
        saveAutoResetSetting();
        clearAutoResetTimer();
        setError("");
        if (!state.transfer || !state.downloadToken) {
            setError("먼저 6자리 코드로 파일 정보를 확인하세요.");
            return;
        }
        App.byId("downloadButton").disabled = true;
        App.show("restartDownloadButton", false);
        state.failed = false;
        try {
            await prepareWriterIfNeeded();
            await downloadStream();
            completeDownload();
        } catch (error) {
            state.failed = true;
            setStatus("실패");
            setError(error.message || String(error));
            App.show("restartDownloadButton", true);
        } finally {
            App.byId("downloadButton").disabled = false;
        }
    }

    async function prepareWriterIfNeeded() {
        if (state.writer || state.fallbackMode) {
            return;
        }
        clearDownloadLink();
        if (typeof window.showSaveFilePicker !== "function") {
            if (state.transfer.download_size > BLOB_FALLBACK_LIMIT) {
                throw new Error("이 브라우저는 직접 저장을 지원하지 않아 512MB 초과 파일을 받을 수 없습니다.");
            }
            state.fallbackMode = true;
            App.show("fallbackWarning", true);
            return;
        }
        var handle = await window.showSaveFilePicker({ suggestedName: state.transfer.archive_name });
        state.writer = await handle.createWritable();
        state.fallbackMode = false;
        App.show("fallbackWarning", false);
    }

    async function downloadStream() {
        state.startedAt = state.startedAt || performance.now();
        setStatus("다운로드 중");
        if (state.fallbackMode) {
            await downloadBlobFallback();
            return;
        }
        var resume = state.bytesWritten > 0 && !state.transfer.is_bundle;
        if (state.transfer.is_bundle && state.bytesWritten > 0) {
            await state.writer.truncate(0);
            await state.writer.seek(0);
            state.bytesWritten = 0;
        } else if (resume) {
            await state.writer.seek(state.bytesWritten);
        }

        var headers = { "X-Download-Token": state.downloadToken };
        if (resume) {
            headers.Range = "bytes=" + state.bytesWritten + "-";
        }
        var response = await fetch("/api/stored/transfers/" + encodeURIComponent(state.transfer.transfer_id) + "/download", {
            headers: headers
        });
        if (!response.ok) {
            var detail = await response.json().catch(function () { return {}; });
            throw new Error(detail.detail || "다운로드를 시작하지 못했습니다.");
        }
        if (resume && response.status !== 206) {
            await state.writer.truncate(0);
            await state.writer.seek(0);
            state.bytesWritten = 0;
        }
        var reader = response.body.getReader();
        while (true) {
            var result = await reader.read();
            if (result.done) {
                break;
            }
            await state.writer.write(result.value);
            state.bytesWritten += result.value.byteLength;
            updateProgress();
        }
        await state.writer.close();
        state.writer = null;
    }

    async function downloadBlobFallback() {
        var response = await fetch("/api/stored/transfers/" + encodeURIComponent(state.transfer.transfer_id) + "/download", {
            headers: { "X-Download-Token": state.downloadToken }
        });
        if (!response.ok) {
            throw new Error("다운로드를 시작하지 못했습니다.");
        }
        var reader = response.body.getReader();
        var chunks = [];
        while (true) {
            var result = await reader.read();
            if (result.done) {
                break;
            }
            chunks.push(result.value);
            state.bytesWritten += result.value.byteLength;
            updateProgress();
        }
        var blob = new Blob(chunks, { type: state.transfer.is_bundle ? "application/x-tar" : (state.transfer.entries[0].mime_type || "application/octet-stream") });
        var link = App.byId("downloadLink");
        state.downloadUrl = URL.createObjectURL(blob);
        link.href = state.downloadUrl;
        link.download = state.transfer.archive_name;
        App.show("downloadLink", true);
    }

    function updateProgress() {
        App.updateProgress(state.bytesWritten, state.transfer.download_size, state.startedAt);
    }

    function completeDownload() {
        state.completed = true;
        state.bytesWritten = state.transfer.download_size;
        updateProgress();
        setStatus("완료");
        App.show("downloadButton", false);
        App.show("restartDownloadButton", false);
        if (autoResetEnabled()) {
            state.autoResetTimer = window.setTimeout(resetAll, AUTO_RESET_DELAY_MS);
        }
    }

    function clearDownloadLink() {
        if (state.downloadUrl) {
            URL.revokeObjectURL(state.downloadUrl);
            state.downloadUrl = null;
        }
        var link = App.byId("downloadLink");
        link.removeAttribute("href");
        link.removeAttribute("download");
        App.show("downloadLink", false);
    }

    function clearAutoResetTimer() {
        if (state.autoResetTimer !== null) {
            window.clearTimeout(state.autoResetTimer);
            state.autoResetTimer = null;
        }
    }

    function resetAll() {
        clearAutoResetTimer();
        clearDownloadLink();
        state.transfer = null;
        state.downloadToken = null;
        state.writer = null;
        state.fallbackMode = false;
        state.bytesWritten = 0;
        state.startedAt = 0;
        state.failed = false;
        state.completed = false;
        App.byId("codeInput").value = "";
        App.setText("fileNameText", "-");
        App.setText("fileSizeText", "-");
        App.setText("downloadSizeText", "-");
        App.setText("bundleText", "-");
        App.updateProgress(0, 0, 0);
        setStatus("대기 중");
        setError("");
        App.show("transferPanel", false);
        App.show("downloadButton", false);
        App.show("restartDownloadButton", false);
        App.show("fallbackWarning", false);
    }

    document.addEventListener("DOMContentLoaded", function () {
        loadAutoResetSetting();
        App.byId("autoResetToggle").addEventListener("change", saveAutoResetSetting);
        App.byId("lookupButton").addEventListener("click", lookupRoom);
        App.byId("downloadButton").addEventListener("click", startDownload);
        App.byId("restartDownloadButton").addEventListener("click", startDownload);
        var code = App.queryCode().replace(/\D/g, "").slice(0, 6);
        if (code.length === 6) {
            App.byId("codeInput").value = code;
            lookupRoom();
        }
    });
}());
