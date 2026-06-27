// Search module: global and advanced search
const Search = (function () {
    let debounceTimer = null;

    function globalSearch(keyword) {
        if (!keyword || !keyword.trim()) {
            Toast.info('请输入搜索关键词');
            return;
        }
        doSearch({ keyword: keyword.trim() });
    }

    function openAdvancedSearch() {
        const body = document.createElement('div');
        body.innerHTML =
            '<div class="form-group"><label>节点名称 (支持模糊)</label>' +
            '<input type="text" class="form-control" id="advName" placeholder="如 sysDescr"></div>' +
            '<div class="form-group"><label>OID (精确或前缀)</label>' +
            '<input type="text" class="form-control" id="advOid" placeholder="如 1.3.6.1.2.1.1"></div>' +
            '<div class="form-group"><label>描述关键词</label>' +
            '<input type="text" class="form-control" id="advDesc" placeholder="如 system description"></div>' +
            '<div class="form-group"><label>匹配模式</label>' +
            '<select class="form-control" id="advMode"><option value="and">全部满足 (AND)</option><option value="or">任一满足 (OR)</option></select></div>' +
            '<div class="form-hint">提示: 留空的条件将被忽略。仅填一个条件即等同于普通搜索。</div>';
        Modal.open({
            title: '高级搜索',
            body: body,
            buttons: [
                { text: '取消', class: '' },
                { text: '搜索', class: 'btn-primary', onClick: () => {
                    const name = body.querySelector('#advName').value.trim();
                    const oid = body.querySelector('#advOid').value.trim();
                    const desc = body.querySelector('#advDesc').value.trim();
                    if (!name && !oid && !desc) {
                        Toast.warning('请至少输入一个搜索条件');
                        return false;
                    }
                    doSearch({ name: name, oid: oid, description: desc });
                } }
            ]
        });
    }

    async function doSearch(params) {
        const searchParams = new URLSearchParams();
        Object.keys(params).forEach(k => { if (params[k]) searchParams.append(k, params[k]); });
        searchParams.append('limit', '200');
        Loading.show('搜索中...');
        try {
            const res = await API.mib.search(Object.fromEntries(searchParams));
            if (res.code === 0) {
                showResults(res.data, params);
            } else {
                Toast.error(res.message || '搜索失败');
            }
        } catch (e) {
            Toast.error('搜索失败: ' + e.message);
        } finally {
            Loading.hide();
        }
    }

    function showResults(data, params) {
        const keyword = params.keyword || params.name || params.oid || params.description || '';
        const body = document.createElement('div');
        body.innerHTML =
            '<div style="margin-bottom:12px;color:#6b7280;font-size:13px;">' +
            '共找到 <b style="color:#1677ff;">' + data.total + '</b> 个结果，耗时 ' + data.elapsedMs + 'ms' +
            '</div>' +
            '<div class="search-results" id="searchResultsContainer"></div>';
        Modal.open({
            title: '搜索结果',
            size: 'lg',
            body: body,
            buttons: [{ text: '关闭', class: '' }]
        });
        const container = body.querySelector('#searchResultsContainer');
        if (!data.items || data.items.length === 0) {
            container.innerHTML = '<div class="empty-state"><p>未找到匹配的 MIB 节点</p></div>';
            return;
        }
        const html = data.items.map(item => {
            return '<div class="search-result-item" data-oid="' + escapeHtml(item.oid || '') + '">' +
                '<div class="result-name">' + highlight(item.name || '(unnamed)', keyword) + '</div>' +
                '<div class="result-oid">' + highlight(item.oid || '', keyword) + '</div>' +
                (item.description ? '<div class="result-desc">' + highlight(truncate(item.description, 100), keyword) + '</div>' : '') +
                '<div class="result-meta">' + escapeHtml(item.type || '') + (item.access ? ' · ' + escapeHtml(item.access) : '') + (item.moduleName ? ' · ' + escapeHtml(item.moduleName) : '') + '</div>' +
                '</div>';
        }).join('');
        container.innerHTML = html;
        container.querySelectorAll('.search-result-item').forEach(el => {
            el.addEventListener('click', () => {
                const oid = el.dataset.oid;
                Modal.open({ title: '关闭', size: 'sm', body: '', buttons: [] });
                document.querySelectorAll('.modal-overlay').forEach(m => m.remove());
                // Switch to MIB tab and locate
                switchToMibTab();
                MibTree.locateByOid(oid);
            });
        });
    }

    function switchToMibTab() {
        document.querySelectorAll('.tab-btn').forEach(b => b.classList.remove('active'));
        document.querySelector('.tab-btn[data-tab="mib"]').classList.add('active');
        document.querySelectorAll('.tab-panel').forEach(p => p.classList.remove('active'));
        document.getElementById('tab-mib').classList.add('active');
    }

    function highlight(text, keyword) {
        if (!text) return '';
        const safe = escapeHtml(text);
        if (!keyword) return safe;
        const lower = text.toLowerCase();
        const k = keyword.toLowerCase();
        let result = '';
        let i = 0;
        while (i < text.length) {
            const idx = lower.indexOf(k, i);
            if (idx < 0) {
                result += escapeHtml(text.substring(i));
                break;
            }
            result += escapeHtml(text.substring(i, idx));
            result += '<span class="highlight">' + escapeHtml(text.substring(idx, idx + keyword.length)) + '</span>';
            i = idx + keyword.length;
        }
        return result;
    }

    function truncate(s, n) {
        if (!s) return '';
        return s.length > n ? s.substring(0, n) + '...' : s;
    }

    return { globalSearch, openAdvancedSearch };
})();
