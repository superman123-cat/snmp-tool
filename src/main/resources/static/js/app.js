// Main application bootstrap and event wiring
(function () {
    // Redirect to login if not authenticated
    async function checkAuth() {
        try {
            const res = await API.auth.status();
            if (res.code === 0 && res.data) {
                if (res.data.authEnabled && !res.data.valid) {
                    // try token
                    const token = API.getToken();
                    if (!token) {
                        location.href = '/login.html';
                        return false;
                    }
                }
                if (res.data.valid) {
                    API.saveUser({
                        username: res.data.username,
                        displayName: res.data.displayName,
                        roles: res.data.roles
                    });
                    showUserInfo(res.data);
                    return true;
                } else if (res.data.authEnabled) {
                    location.href = '/login.html';
                    return false;
                }
                return true;
            }
        } catch (e) {
            // If auth disabled or network issue, allow access
            console.warn('Auth check failed', e);
        }
        return true;
    }

    function showUserInfo(userData) {
        const el = document.getElementById('userInfo');
        if (el && userData) {
            el.textContent = (userData.displayName || userData.username || '用户') +
                (userData.roles && userData.roles.length ? ' (' + userData.roles.join(',') + ')' : '');
        }
    }

    function init() {
        bindHeader();
        bindSidebar();
        bindTreeEvents();
        bindDeviceEvents();
        bindSearchEvents();
        bindKeyboard();
        // Initial loads
        MibTree.load();
        Devices.load();
        loadStats();
        checkFirstVisitGuide();
    }

    function bindHeader() {
        document.getElementById('logoutBtn').addEventListener('click', async () => {
            try { await API.auth.logout(); } catch (e) {}
            API.clearAuth();
            location.href = '/login.html';
        });
    }

    function bindSidebar() {
        document.querySelectorAll('.tab-btn').forEach(btn => {
            btn.addEventListener('click', () => {
                document.querySelectorAll('.tab-btn').forEach(b => b.classList.remove('active'));
                btn.classList.add('active');
                document.querySelectorAll('.tab-panel').forEach(p => p.classList.remove('active'));
                document.getElementById('tab-' + btn.dataset.tab).classList.add('active');
                if (btn.dataset.tab === 'favorite') renderFavorites();
            });
        });
    }

    function bindTreeEvents() {
        document.getElementById('uploadMibBtn').addEventListener('click', () => {
            Upload.openUploadDialog(() => { MibTree.load(); loadStats(); });
        });
        document.getElementById('expandAllBtn').addEventListener('click', () => MibTree.expandAll());
        document.getElementById('collapseAllBtn').addEventListener('click', () => MibTree.collapseAll());
        document.getElementById('refreshTreeBtn').addEventListener('click', () => { MibTree.load(); loadStats(); });

        const clearBtn = document.getElementById('clearAllMibBtn');
        if (clearBtn) {
            clearBtn.addEventListener('click', async () => {
                Modal.open({
                    title: '清空全部 MIB 模块',
                    body: (() => {
                        const b = document.createElement('div');
                        b.innerHTML = '<p>此操作将删除所有已导入的 MIB 模块及其节点，无法撤销。</p><p style="margin-top:8px;color:var(--danger);">确认要清空吗？</p>';
                        return b;
                    })(),
                    buttons: [
                        { text: '取消', class: '' },
                        {
                            text: '确认清空', class: 'btn-danger', onClick: async (m, b) => {
                                try {
                                    const res = await API.mib.clearAll();
                                    if (res.code === 0) {
                                        Toast.success(res.message || '已清空');
                                        MibTree.reset();
                                        MibTree.load();
                                        loadStats();
                                    } else {
                                        Toast.error(res.message || '清空失败');
                                    }
                                } catch (e) {
                                    Toast.error('清空失败: ' + e.message);
                                }
                                return true;
                            }
                        }
                    ]
                });
            });
        }

        // Expand to level menu
        const expandBtn = document.getElementById('expandAllBtn');
        expandBtn.addEventListener('contextmenu', (e) => {
            e.preventDefault();
            showExpandLevelMenu(e);
        });

        // Tree filter
        const filterInput = document.getElementById('treeFilterInput');
        let timer = null;
        filterInput.addEventListener('input', (e) => {
            clearTimeout(timer);
            timer = setTimeout(() => MibTree.filterTree(e.target.value.trim()), 250);
        });

        // Tree selection callback
        MibTree.onSelect((node) => {
            // Optionally show node details
        });
        // Tree context menu
        MibTree.onContext((node, e) => {
            showNodeContextMenu(node, e);
        });
    }

    function showExpandLevelMenu(e) {
        const menu = document.getElementById('contextMenu');
        menu.innerHTML =
            '<div class="context-menu-item" data-action="expand1">展开 1 层</div>' +
            '<div class="context-menu-item" data-action="expand2">展开 2 层</div>' +
            '<div class="context-menu-item" data-action="expand3">展开 3 层</div>' +
            '<div class="context-menu-item" data-action="expandAll">全部展开</div>' +
            '<div class="context-menu-item" data-action="collapseAll">全部折叠</div>';
        menu.style.display = 'block';
        menu.style.left = e.clientX + 'px';
        menu.style.top = e.clientY + 'px';
        menu.querySelectorAll('.context-menu-item').forEach(item => {
            item.addEventListener('click', () => {
                switch (item.dataset.action) {
                    case 'expand1': MibTree.expandToLevel(1); break;
                    case 'expand2': MibTree.expandToLevel(2); break;
                    case 'expand3': MibTree.expandToLevel(3); break;
                    case 'expandAll': MibTree.expandAll(); break;
                    case 'collapseAll': MibTree.collapseAll(); break;
                }
                menu.style.display = 'none';
            });
        });
        document.addEventListener('click', () => { menu.style.display = 'none'; }, { once: true });
    }

    function showNodeContextMenu(node, e) {
        const menu = document.getElementById('contextMenu');
        const isFavorite = Favorites.has(node.oid);
        const hasOid = !!node.oid;
        let html = '';
        if (hasOid) {
            html += '<div class="context-menu-item" data-action="querySingle"><span class="menu-icon">🔍</span>查询单个节点</div>';
            html += '<div class="context-menu-item" data-action="queryTable"><span class="menu-icon">📊</span>查询整表</div>';
            html += '<div class="context-menu-item" data-action="queryNext"><span class="menu-icon">→</span>查询下一个 (GETNEXT)</div>';
            html += '<div class="context-menu-divider"></div>';
            html += '<div class="context-menu-item" data-action="copyOid"><span class="menu-icon">📋</span>复制 OID</div>';
            html += '<div class="context-menu-item" data-action="copyName"><span class="menu-icon">📋</span>复制名称</div>';
            html += '<div class="context-menu-divider"></div>';
            html += '<div class="context-menu-item" data-action="favorite"><span class="menu-icon">' + (isFavorite ? '★' : '☆') + '</span>' + (isFavorite ? '取消收藏' : '添加到收藏夹') + '</div>';
            html += '<div class="context-menu-item" data-action="details"><span class="menu-icon">ℹ</span>节点详情</div>';
        }
        menu.innerHTML = html;
        menu.style.display = 'block';
        // Position - keep within viewport
        let x = e.clientX, y = e.clientY;
        const rect = menu.getBoundingClientRect();
        if (x + 200 > window.innerWidth) x = window.innerWidth - 210;
        if (y + 300 > window.innerHeight) y = window.innerHeight - 310;
        menu.style.left = x + 'px';
        menu.style.top = y + 'px';

        menu.querySelectorAll('.context-menu-item').forEach(item => {
            item.addEventListener('click', () => {
                handleNodeAction(item.dataset.action, node);
                menu.style.display = 'none';
            });
        });
        document.addEventListener('click', () => { menu.style.display = 'none'; }, { once: true });
    }

    function handleNodeAction(action, node) {
        switch (action) {
            case 'querySingle': Query.querySingleNode(node); break;
            case 'queryTable': Query.queryTable(node); break;
            case 'queryNext':
                if (!node.oid) return;
                const d1 = Devices.getSelected();
                if (!d1) { Toast.warning('请先选择设备'); return; }
                API.devices.getNext(d1.id, { oids: [node.oid] }).then(res => {
                    if (res.code === 0) {
                        const content = document.getElementById('mainContent');
                        content.innerHTML = '<div class="content-tabs"><div class="content-tab active"></div></div>';
                        const tab = content.querySelector('.content-tab');
                        tab.innerHTML = '<div class="panel"><div class="panel-header"><span class="panel-title">GETNEXT 结果</span></div><div class="panel-body" id="queryResultArea"></div></div>';
                        const data = { rows: res.data, columns: ['OID', 'Name', 'Value', 'DataType', 'Timestamp'] };
                        const rows = res.data.map(vb => ({ OID: vb.oid, Name: vb.name, Value: vb.value, DataType: vb.dataType, Timestamp: formatTime(vb.timestamp) }));
                        renderSimpleTable(tab.querySelector('#queryResultArea'), rows);
                    } else { Toast.error(res.message); }
                }).catch(err => Toast.error(err.message));
                break;
            case 'copyOid': navigator.clipboard.writeText(node.oid).then(() => Toast.success('OID 已复制')); break;
            case 'copyName': navigator.clipboard.writeText(node.name).then(() => Toast.success('名称已复制')); break;
            case 'favorite':
                const added = Favorites.toggle(node);
                Toast.success(added ? '已添加到收藏夹' : '已从收藏夹移除');
                renderFavorites();
                break;
            case 'details': showNodeDetails(node); break;
        }
    }

    function renderSimpleTable(area, rows) {
        if (!rows || !rows.length) { area.innerHTML = '<div class="table-empty">无数据</div>'; return; }
        const columns = Object.keys(rows[0]);
        let html = '<div class="table-wrap"><table class="data-table"><thead><tr>';
        columns.forEach(c => html += '<th>' + escapeHtml(c) + '</th>');
        html += '</tr></thead><tbody>';
        rows.forEach(r => {
            html += '<tr>';
            columns.forEach(c => html += '<td class="' + (c === 'OID' ? 'mono' : '') + '">' + escapeHtml(r[c] || '') + '</td>');
            html += '</tr>';
        });
        html += '</tbody></table></div>';
        area.innerHTML = html;
    }

    function showNodeDetails(node) {
        const body = document.createElement('div');
        body.innerHTML =
            '<div class="result-card">' +
            '<div class="card-row"><div class="card-label">名称</div><div class="card-value">' + escapeHtml(node.name || '-') + '</div></div>' +
            '<div class="card-row"><div class="card-label">OID</div><div class="card-value mono">' + escapeHtml(node.oid || '-') + '</div></div>' +
            '<div class="card-row"><div class="card-label">类型</div><div class="card-value">' + escapeHtml(node.type || '-') + '</div></div>' +
            '<div class="card-row"><div class="card-label">语法</div><div class="card-value mono">' + escapeHtml(node.syntax || '-') + '</div></div>' +
            '<div class="card-row"><div class="card-label">访问权限</div><div class="card-value">' + escapeHtml(node.access || '-') + '</div></div>' +
            '<div class="card-row"><div class="card-label">状态</div><div class="card-value">' + escapeHtml(node.status || '-') + '</div></div>' +
            '<div class="card-row"><div class="card-label">单位</div><div class="card-value">' + escapeHtml(node.units || '-') + '</div></div>' +
            '<div class="card-row"><div class="card-label">模块</div><div class="card-value">' + escapeHtml(node.moduleName || '-') + '</div></div>' +
            (node.indexes && node.indexes.length ? '<div class="card-row"><div class="card-label">索引</div><div class="card-value">' + escapeHtml(node.indexes.join(', ')) + '</div></div>' : '') +
            '<div class="card-row"><div class="card-label">描述</div><div class="card-value" style="white-space:pre-wrap;">' + escapeHtml(node.description || '-') + '</div></div>' +
            '</div>';
        Modal.open({ title: '节点详情', size: 'lg', body: body, buttons: [{ text: '关闭', class: '' }] });
    }

    function bindDeviceEvents() {
        document.getElementById('addDeviceBtn').addEventListener('click', () => Devices.add());
        document.getElementById('refreshDevicesBtn').addEventListener('click', () => Devices.refreshAll());
        Devices.onSelect((device) => {
            // Show quick actions for the selected device
        });
        // Context menu on device cards (delegation)
        document.getElementById('deviceList').addEventListener('contextmenu', (e) => {
            const card = e.target.closest('.device-card');
            if (!card) return;
            e.preventDefault();
            const id = card.dataset.id;
            const device = Devices.getSelected();
            // Find device
            API.devices.get(id).then(res => {
                if (res.code === 0) showDeviceContextMenu(res.data, e);
            });
        });
    }

    function showDeviceContextMenu(device, e) {
        const menu = document.getElementById('contextMenu');
        menu.innerHTML =
            '<div class="context-menu-item" data-action="test"><span class="menu-icon">🔌</span>测试连接</div>' +
            '<div class="context-menu-item" data-action="edit"><span class="menu-icon">✎</span>编辑设备</div>' +
            '<div class="context-menu-item" data-action="customQuery"><span class="menu-icon">🔍</span>自定义 OID 查询</div>' +
            '<div class="context-menu-divider"></div>' +
            '<div class="context-menu-item" data-action="copyHost"><span class="menu-icon">📋</span>复制 IP:端口</div>' +
            '<div class="context-menu-item danger" data-action="delete"><span class="menu-icon">🗑</span>删除设备</div>';
        menu.style.display = 'block';
        let x = e.clientX, y = e.clientY;
        if (x + 200 > window.innerWidth) x = window.innerWidth - 210;
        if (y + 300 > window.innerHeight) y = window.innerHeight - 310;
        menu.style.left = x + 'px';
        menu.style.top = y + 'px';
        menu.querySelectorAll('.context-menu-item').forEach(item => {
            item.addEventListener('click', () => {
                switch (item.dataset.action) {
                    case 'test': Devices.refreshAll(); break; // simplified
                    case 'edit': Devices.edit(device); break;
                    case 'customQuery': Query.queryCustomOid(); break;
                    case 'copyHost': navigator.clipboard.writeText(device.host + ':' + device.port).then(() => Toast.success('已复制')); break;
                    case 'delete': Devices.remove(device); break;
                }
                menu.style.display = 'none';
            });
        });
        document.addEventListener('click', () => { menu.style.display = 'none'; }, { once: true });
    }

    function bindSearchEvents() {
        const input = document.getElementById('globalSearchInput');
        document.getElementById('globalSearchBtn').addEventListener('click', () => {
            Search.globalSearch(input.value);
        });
        input.addEventListener('keydown', (e) => {
            if (e.key === 'Enter') Search.globalSearch(input.value);
        });
        document.getElementById('advancedSearchBtn').addEventListener('click', () => Search.openAdvancedSearch());
    }

    function bindKeyboard() {
        document.addEventListener('keydown', (e) => {
            // Ctrl+K focus search
            if ((e.ctrlKey || e.metaKey) && e.key === 'k') {
                e.preventDefault();
                document.getElementById('globalSearchInput').focus();
            }
            // Escape closes context menu
            if (e.key === 'Escape') {
                document.getElementById('contextMenu').style.display = 'none';
            }
        });
    }

    function renderFavorites() {
        const list = document.getElementById('favoriteList');
        const favs = Favorites.getAll();
        if (favs.length === 0) {
            list.innerHTML = '<div class="empty-state"><p>暂无收藏</p><p style="font-size:12px;">在 MIB 树节点上右键即可添加收藏</p></div>';
            return;
        }
        list.innerHTML = favs.map(f => {
            return '<div class="device-card" data-oid="' + escapeHtml(f.oid) + '">' +
                '<div class="device-card-header"><span><span class="status-dot online" style="background:#faad14;"></span>' +
                '<span class="device-card-name">' + escapeHtml(f.name) + '</span></span>' +
                '<button class="btn-link" data-remove="' + escapeHtml(f.oid) + '" style="color:#ff4d4f;">移除</button></span></div>' +
                '<div class="device-card-host">' + escapeHtml(f.oid) + '</div>' +
                '</div>';
        }).join('');
        list.querySelectorAll('.device-card').forEach(card => {
            card.addEventListener('click', (e) => {
                if (e.target.dataset.remove !== undefined) return;
                // Switch to MIB tab and locate
                document.querySelector('.tab-btn[data-tab="mib"]').click();
                MibTree.locateByOid(card.dataset.oid);
            });
        });
        list.querySelectorAll('[data-remove]').forEach(btn => {
            btn.addEventListener('click', (e) => {
                e.stopPropagation();
                Favorites.remove(e.target.dataset.remove);
                renderFavorites();
                Toast.success('已移除');
            });
        });
    }

    async function loadStats() {
        try {
            const [mibRes, devRes] = await Promise.all([API.mib.stats(), API.devices.list()]);
            if (mibRes.code === 0) {
                document.getElementById('statModules').textContent = mibRes.data.moduleCount;
                document.getElementById('statNodes').textContent = mibRes.data.nodeCount;
            }
            if (devRes.code === 0) {
                document.getElementById('statDevices').textContent = devRes.data.length;
                document.getElementById('statOnline').textContent = devRes.data.filter(d => d.status === 'online').length;
            }
        } catch (e) {
            console.warn('load stats failed', e);
        }
    }

    function checkFirstVisitGuide() {
        const dismissed = localStorage.getItem('guide_dismissed');
        if (!dismissed) {
            const guide = document.getElementById('homeGuide');
            if (guide) guide.style.display = 'block';
        } else {
            const guide = document.getElementById('homeGuide');
            if (guide) guide.style.display = 'none';
        }
        const dismissBtn = document.getElementById('dismissGuideBtn');
        if (dismissBtn) {
            dismissBtn.addEventListener('click', () => {
                localStorage.setItem('guide_dismissed', '1');
                document.getElementById('homeGuide').style.display = 'none';
                Toast.success('引导已关闭，可在设置中重新开启');
            });
        }
    }

    // Start
    document.addEventListener('DOMContentLoaded', () => {
        checkAuth().then(ok => { if (ok) init(); });
    });
})();
