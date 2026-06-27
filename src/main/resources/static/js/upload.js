// MIB file upload module
const Upload = (function () {
    let selectedFiles = [];

    // Accept MIB-like files: .mib/.txt/.my/.mib2, no-extension files, or names containing "mib".
    // Folder uploads go through every file (browser already recurses subfolders).
    function isAcceptableMib(file) {
        const name = file.name;
        // In folder mode the relative path is set; we still filter by extension/name.
        const dotIdx = name.lastIndexOf('.');
        const ext = dotIdx >= 0 ? name.slice(dotIdx + 1).toLowerCase() : '';
        if (['mib', 'txt', 'my', 'mib2'].includes(ext)) return true;
        // No extension (e.g. BRIDGE-MIB, IP-MIB) -> accept.
        if (dotIdx < 0) return true;
        // Has an extension we don't recognize: accept only if the name looks like a MIB.
        return /mib/i.test(name);
    }

    // Unique submission key for a File: the relative path (folder mode) preserves
    // same-named files in different subfolders, otherwise the base name.
    function fileKey(f) {
        return f.webkitRelativePath || f.name;
    }

    // Display name shown in the list: relative path when available (so the user
    // can see which subfolder each file came from), otherwise the base name.
    function fileDisplayName(f) {
        return f.webkitRelativePath || f.name;
    }

    function openUploadDialog(onComplete) {
        const body = document.createElement('div');
        body.innerHTML =
            '<div class="upload-zone" id="uploadZone">' +
            '  <div class="upload-icon">📁</div>' +
            '  <h3>拖拽 MIB 文件到此处，或点击下方按钮选择</h3>' +
            '  <p>支持 .mib / .txt / .my 及无扩展名 MIB 文件；可同时选择多个文件，单个文件最大 10MB</p>' +
            '  <div class="upload-actions">' +
            '    <button type="button" class="btn btn-sm" id="pickFilesBtn">选择文件</button>' +
            '    <button type="button" class="btn btn-sm" id="pickFolderBtn">选择文件夹</button>' +
            '    <button type="button" class="btn btn-link btn-sm" id="clearFilesBtn" style="margin-left:auto;">清空</button>' +
            '  </div>' +
            '  <input type="file" id="mibFileInput" multiple accept=".mib,.txt,.my,.mib2" style="display:none;">' +
            '  <input type="file" id="mibFolderInput" multiple webkitdirectory directory style="display:none;">' +
            '</div>' +
            '<div class="upload-file-list" id="uploadFileList"></div>' +
            '<div class="progress-bar" id="uploadProgressBar" style="display:none;">' +
            '  <div class="progress-fill" style="width:0%"></div>' +
            '</div>' +
            '<div class="upload-summary" id="uploadSummary" style="display:none;"></div>';

        const modal = Modal.open({
            title: '批量导入 MIB 文件',
            size: 'lg',
            body: body,
            buttons: [
                { text: '取消', class: '' },
                { text: '开始导入', class: 'btn-primary', onClick: (m, b) => startUpload(b, onComplete) }
            ]
        });

        const zone = body.querySelector('#uploadZone');
        const fileInput = body.querySelector('#mibFileInput');
        const folderInput = body.querySelector('#mibFolderInput');
        const pickFilesBtn = body.querySelector('#pickFilesBtn');
        const pickFolderBtn = body.querySelector('#pickFolderBtn');
        const clearBtn = body.querySelector('#clearFilesBtn');

        // Clicking the zone opens the file picker (default). Buttons override.
        zone.addEventListener('click', (e) => {
            if (e.target.closest('button') || e.target.closest('input')) return;
            fileInput.click();
        });
        pickFilesBtn.addEventListener('click', (e) => { e.stopPropagation(); fileInput.click(); });
        pickFolderBtn.addEventListener('click', (e) => { e.stopPropagation(); folderInput.click(); });
        clearBtn.addEventListener('click', (e) => { e.stopPropagation(); selectedFiles = []; renderFileList(body); });

        fileInput.addEventListener('change', (e) => handleFiles(e.target.files, body));
        folderInput.addEventListener('change', (e) => handleFiles(e.target.files, body));

        zone.addEventListener('dragover', (e) => { e.preventDefault(); zone.classList.add('dragover'); });
        zone.addEventListener('dragleave', () => zone.classList.remove('dragover'));
        zone.addEventListener('drop', (e) => {
            e.preventDefault();
            zone.classList.remove('dragover');
            handleFiles(e.dataTransfer.files, body);
        });
    }

    function handleFiles(fileList, body) {
        const valid = [];
        const errors = [];
        Array.from(fileList).forEach(f => {
            if (!isAcceptableMib(f)) {
                errors.push(fileDisplayName(f) + ' 不是支持的 MIB 文件格式');
                return;
            }
            if (f.size > 10 * 1024 * 1024) {
                errors.push(fileDisplayName(f) + ' 超过 10MB 限制');
                return;
            }
            valid.push(f);
        });
        // Deduplicate by submission key (relative path) + size so the same file
        // picked twice in different subfolders is kept, but true duplicates are skipped.
        const seen = new Set();
        selectedFiles = selectedFiles.concat(valid).filter(f => {
            const key = fileKey(f) + '_' + f.size;
            if (seen.has(key)) return false;
            seen.add(key);
            return true;
        });
        renderFileList(body);
        if (errors.length) Toast.warning(errors.join('\n'));
    }

    function renderFileList(body) {
        const list = body.querySelector('#uploadFileList');
        if (selectedFiles.length === 0) {
            list.innerHTML = '';
            return;
        }
        list.innerHTML = selectedFiles.map((f, i) => {
            const sizeKb = (f.size / 1024).toFixed(1);
            return '<div class="upload-file-item">' +
                '<span class="file-name">📄 ' + escapeHtml(fileDisplayName(f)) + ' <span style="color:#999">(' + sizeKb + ' KB)</span></span>' +
                '<span class="file-status pending">待导入</span>' +
                '<button class="btn-link" data-remove="' + i + '" style="margin-left:8px;">移除</button>' +
                '</div>';
        }).join('');
        list.querySelectorAll('[data-remove]').forEach(btn => {
            btn.addEventListener('click', (e) => {
                const idx = parseInt(e.target.getAttribute('data-remove'));
                selectedFiles.splice(idx, 1);
                renderFileList(body);
            });
        });
    }

    async function startUpload(body, onComplete) {
        if (selectedFiles.length === 0) {
            Toast.warning('请先选择 MIB 文件');
            return false;
        }
        const formData = new FormData();
        // Pass the relative path as the upload filename so the backend can
        // preserve subfolder structure and disambiguate same-named files.
        selectedFiles.forEach(f => formData.append('files', f, fileKey(f)));
        const progressBar = body.querySelector('#uploadProgressBar');
        const progressFill = progressBar.querySelector('.progress-fill');
        const summary = body.querySelector('#uploadSummary');
        progressBar.style.display = 'block';
        progressFill.style.width = '10%';
        summary.style.display = 'none';

        try {
            Toast.info('正在上传 ' + selectedFiles.length + ' 个文件...');
            const res = await API.mib.upload(formData);
            if (res.code !== 0) {
                Toast.error(res.message || '上传失败');
                progressFill.style.width = '0%';
                return false;
            }
            const batchId = res.data.batchId;
            Toast.info('文件已上传，正在解析 MIB...');
            progressFill.style.width = '30%';
            // Poll progress
            await pollProgress(batchId, body, onComplete);
        } catch (e) {
            Toast.error('上传失败: ' + e.message);
            progressFill.style.width = '0%';
        }
        return false; // keep modal open to show results
    }

    async function pollProgress(batchId, body, onComplete) {
        const progressBar = body.querySelector('#uploadProgressBar');
        const progressFill = progressBar.querySelector('.progress-fill');
        const summary = body.querySelector('#uploadSummary');

        let attempts = 0;
        const poll = async () => {
            attempts++;
            if (attempts > 600) { // 5 min
                Toast.error('解析超时');
                return;
            }
            try {
                const res = await API.mib.progress(batchId);
                if (res.code !== 0) {
                    Toast.error(res.message || '查询进度失败');
                    return;
                }
                const p = res.data;
                progressFill.style.width = Math.max(30, p.percent) + '%';
                // Update file list statuses
                updateFileListStatus(body, p);
                if (p.status === 'success' || p.status === 'failed' || p.status === 'partial') {
                    progressFill.style.width = '100%';
                    setTimeout(() => { progressBar.style.display = 'none'; }, 500);
                    showSummary(summary, p);
                    if (p.success > 0) {
                        Toast.success('解析完成: 成功 ' + p.success + ' 个，失败 ' + p.failed + ' 个');
                        selectedFiles = [];
                        if (onComplete) onComplete();
                    } else {
                        Toast.error('全部文件解析失败');
                    }
                    return;
                }
            } catch (e) {
                console.error(e);
            }
            setTimeout(poll, 500);
        };
        await poll();
    }

    function updateFileListStatus(body, progress) {
        const items = body.querySelectorAll('.upload-file-item .file-status');
        selectedFiles.forEach((f, i) => {
            const key = fileKey(f);
            const status = progress.fileResults[key];
            const error = progress.fileErrors[key];
            const el = items[i];
            if (!el) return;
            if (status === 'success') {
                el.textContent = '✓ 成功';
                el.className = 'file-status success';
            } else if (status === 'failed') {
                el.textContent = '✕ 失败';
                el.className = 'file-status failed';
                el.title = error || '';
            } else {
                el.textContent = '解析中...';
                el.className = 'file-status pending';
            }
        });
    }

    function showSummary(el, p) {
        el.style.display = 'flex';
        el.innerHTML =
            '<div class="summary-item"><div class="summary-value">' + p.total + '</div><div class="summary-label">总数</div></div>' +
            '<div class="summary-item"><div class="summary-value success">' + p.success + '</div><div class="summary-label">成功</div></div>' +
            '<div class="summary-item"><div class="summary-value failed">' + p.failed + '</div><div class="summary-label">失败</div></div>' +
            '<div class="summary-item"><div class="summary-value">' + (p.duration / 1000).toFixed(2) + 's</div><div class="summary-label">耗时</div></div>';
    }

    return { openUploadDialog };
})();
