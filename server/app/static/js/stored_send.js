(function () {
    "use strict";

    var MAX_TOTAL_SIZE = 30 * 1024 * 1024 * 1024;
    var AUTO_RESET_DELAY_MS = 2000;

    var state = {
        files: [],
        transfer: null,
        uploadToken: null,
        bytesUploaded: 0,
        startedAt: 0,
        failed: false,
        completedUpload: false,
        pollTimer: null,
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

    function chooseFiles(fileList) {
        clearTimers();
        state.files = Array.prototype.slice.call(fileList || []);
        state.transfer = null;
        state.uploadToken = null;
        state.bytesUploaded = 0;
        state.failed = false;
        state.completedUpload = false;
        App.show("transferPanel", false);
        App.show("restartUploadButton", false);
        App.setText("roomCode", "------");
        setError("");
        updateSelection();
    }

    function relativePath(file) {
        return file.webkitRelativePath || file.name;
    }

    function totalSize() {
        return state.files.reduce(function (sum, file) { return sum + file.size; }, 0);
    }

    function updateSelection() {
        var count = state.files.length;
        var total = totalSize();
        App.setText("selectionText", count ? count + "개 항목" : "-");
        App.setText("totalSizeText", count ? App.formatBytes(total) : "-");
        App.byId("startUploadButton").disabled = count === 0 || total > MAX_TOTAL_SIZE;
        if (total > MAX_TOTAL_SIZE) {
            setError("전체 전송 크기는 30GB를 넘을 수 없습니다.");
        }
    }

    async function createTransferIfNeeded() {
        if (state.transfer && state.uploadToken) {
            return;
        }
        var entries = state.files.map(function (file) {
            return {
                relative_path: relativePath(file),
                file_size: file.size,
                mime_type: file.type || "application/octet-stream"
            };
        });
        var response = await App.apiJson("/api/stored/transfers", {
            method: "POST",
            body: JSON.stringify({ client_type: "web", entries: entries })
        });
        state.transfer = response;
        state.uploadToken = response.upload_token;
        state.bytesUploaded = uploadedBytesFromEntries(response.entries);
        App.setText("archiveNameText", response.archive_name);
        App.setText("entryCountText", String(response.entries.length));
        App.setText("uploadedBytesText", App.formatBytes(state.bytesUploaded) + " / " + App.formatBytes(response.total_size));
        App.show("transferPanel", true);
    }

    async function fetchUploadStatus() {
        var response = await fetch("/api/stored/transfers/" + encodeURIComponent(state.transfer.transfer_id) + "/status", {
            headers: { "X-Upload-Token": state.uploadToken }
        });
        if (!response.ok) {
            throw new Error("전송 상태를 확인하지 못했습니다. 다시 코드를 생성하세요.");
        }
        return response.json();
    }

    function uploadedBytesFromEntries(entries) {
        return entries.reduce(function (sum, entry) { return sum + Number(entry.bytes_uploaded || 0); }, 0);
    }

    function chunkLength(file, chunkSize, chunkIndex) {
        var start = chunkIndex * chunkSize;
        return Math.min(chunkSize, file.size - start);
    }

    async function startUpload() {
        saveAutoResetSetting();
        clearTimers();
        setError("");
        if (!state.files.length) {
            setError("보낼 파일이나 폴더를 선택하세요.");
            return;
        }
        if (totalSize() > MAX_TOTAL_SIZE) {
            setError("전체 전송 크기는 30GB를 넘을 수 없습니다.");
            return;
        }
        App.byId("startUploadButton").disabled = true;
        App.show("restartUploadButton", false);
        state.failed = false;
        state.startedAt = performance.now();
        try {
            await createTransferIfNeeded();
            await uploadMissingChunks();
            await completeUpload();
        } catch (error) {
            state.failed = true;
            setStatus("실패");
            setError(error.message || String(error));
            App.show("restartUploadButton", true);
        } finally {
            App.byId("startUploadButton").disabled = false;
        }
    }

    async function uploadMissingChunks() {
        var status = await fetchUploadStatus();
        state.bytesUploaded = uploadedBytesFromEntries(status.entries);
        updateProgress(status.total_size);
        setStatus("업로드 중");

        for (var entryIndex = 0; entryIndex < status.entries.length; entryIndex += 1) {
            var entry = status.entries[entryIndex];
            var file = state.files[entryIndex];
            var uploaded = {};
            (entry.uploaded_chunks || []).forEach(function (chunk) { uploaded[chunk] = true; });
            for (var chunkIndex = 0; chunkIndex < entry.chunk_count; chunkIndex += 1) {
                if (uploaded[chunkIndex]) {
                    continue;
                }
                var start = chunkIndex * status.chunk_size_bytes;
                var end = start + chunkLength(file, status.chunk_size_bytes, chunkIndex);
                var chunk = file.slice(start, end);
                var response = await fetch(
                    "/api/stored/transfers/" + encodeURIComponent(status.transfer_id) +
                    "/entries/" + encodeURIComponent(entry.entry_id) +
                    "/chunks/" + chunkIndex,
                    {
                        method: "PUT",
                        headers: {
                            "Content-Type": "application/octet-stream",
                            "X-Upload-Token": state.uploadToken
                        },
                        body: chunk
                    }
                );
                if (!response.ok) {
                    var error = await response.json().catch(function () { return {}; });
                    throw new Error(error.detail || "청크 업로드 실패");
                }
                state.bytesUploaded += chunk.size;
                updateProgress(status.total_size);
            }
        }
    }

    async function completeUpload() {
        setStatus("업로드 검증 중");
        var response = await fetch("/api/stored/transfers/" + encodeURIComponent(state.transfer.transfer_id) + "/complete", {
            method: "POST",
            headers: { "X-Upload-Token": state.uploadToken }
        });
        var data = await response.json().catch(function () { return {}; });
        if (!response.ok) {
            throw new Error(typeof data.detail === "string" ? data.detail : "업로드 완료 검증 실패");
        }
        state.completedUpload = true;
        state.transfer = Object.assign({}, state.transfer, data);
        setStatus("코드 공유 대기 중");
        state.bytesUploaded = data.total_size;
        updateProgress(data.total_size);
        App.setText("roomCode", state.transfer.code);
        await renderShare(data);
        if (autoResetEnabled()) {
            startCompletionPoll();
        }
    }

    async function renderShare(data) {
        var code = state.transfer.code;
        var receiveUrl = App.buildReceiveUrl(code);
        var intentUrl = App.buildAndroidIntentUrl(code);
        var link = App.byId("shareLink");
        link.href = receiveUrl;
        link.textContent = receiveUrl;
        await App.renderQrCode("qrCode", intentUrl);
        App.show("qrPanel", true);
        App.setText("archiveNameText", data.archive_name);
        App.setText("entryCountText", String(data.entries.length));
    }

    function updateProgress(total) {
        App.updateProgress(state.bytesUploaded, total || totalSize(), state.startedAt);
        App.setText("uploadedBytesText", App.formatBytes(state.bytesUploaded) + " / " + App.formatBytes(total || totalSize()));
    }

    function startCompletionPoll() {
        clearPollTimer();
        state.pollTimer = window.setInterval(async function () {
            try {
                await fetchUploadStatus();
            } catch (error) {
                clearPollTimer();
                setStatus("완료");
                state.autoResetTimer = window.setTimeout(resetAll, AUTO_RESET_DELAY_MS);
            }
        }, 5000);
    }

    function clearPollTimer() {
        if (state.pollTimer !== null) {
            window.clearInterval(state.pollTimer);
            state.pollTimer = null;
        }
    }

    function clearTimers() {
        clearPollTimer();
        if (state.autoResetTimer !== null) {
            window.clearTimeout(state.autoResetTimer);
            state.autoResetTimer = null;
        }
    }

    function resetAll() {
        clearTimers();
        state.files = [];
        state.transfer = null;
        state.uploadToken = null;
        state.bytesUploaded = 0;
        state.failed = false;
        state.completedUpload = false;
        App.byId("fileInput").value = "";
        App.byId("folderInput").value = "";
        App.setText("selectionText", "-");
        App.setText("totalSizeText", "-");
        App.setText("roomCode", "------");
        App.setText("archiveNameText", "-");
        App.setText("entryCountText", "-");
        App.setText("uploadedBytesText", "-");
        App.updateProgress(0, 0, 0);
        setStatus("대기 중");
        setError("");
        App.show("transferPanel", false);
        App.show("qrPanel", false);
        App.show("restartUploadButton", false);
        App.byId("startUploadButton").disabled = true;
    }

    document.addEventListener("DOMContentLoaded", function () {
        loadAutoResetSetting();
        App.byId("autoResetToggle").addEventListener("change", saveAutoResetSetting);
        App.byId("pickFilesButton").addEventListener("click", function () {
            App.byId("fileInput").click();
        });
        App.byId("pickFolderButton").addEventListener("click", function () {
            App.byId("folderInput").click();
        });
        App.byId("fileInput").addEventListener("change", function (event) {
            chooseFiles(event.target.files);
        });
        App.byId("folderInput").addEventListener("change", function (event) {
            chooseFiles(event.target.files);
        });
        App.byId("startUploadButton").addEventListener("click", startUpload);
        App.byId("restartUploadButton").addEventListener("click", startUpload);
    });
}());
