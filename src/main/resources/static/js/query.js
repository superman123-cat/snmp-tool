// Query module: single node query (card) and table query (paginated table)
const Query = (function () {
    let currentResult = null; // {type: 'card'|'table', data}
    let tableState = { page: 1, pageSize: 20, sortCol: null, sortDir: 'asc', filter: {} };

    function clearContent() {
        document.getElementById('mainContent').innerHTML =
            '<div class="content-tabs"><div class="content-tab active" id="content-query"></div></div>';
        return document.getElementById('content-query');
    }

    function showHome() {
        location.reload(); // simplest reset
    }

    function getSelectedDevice() {
        const d = Devices.getSelected();
        if (!d) {
            Toast.warning('请先在左侧「设备」标签中选择一个设备');
            switchToDeviceTab();
            return null;
        }
        return d;
    }

    function switchToDeviceTab() {
        document.querySelectorAll('.tab-btn').forEach(b => b.classList.remove('active'));
        document.querySelector('.tab-btn[data-tab="device"]').classList.add('active');
        document.querySelectorAll('.tab-panel').forEach(p => p.classList.remove('active'));
        document.getElementById('tab-device').classList.add('active');
    }

    async function querySingleNode(node) {
        if (!node || !node.oid) {
            Toast.warning('该节点无 OID，无法查询');
            return;
        }
        const device = getSelectedDevice();
        if (!device) return;
        const content = clearContent();

        // The MIB tree stores the BASE OID of a scalar (e.g. sysDescr =
        // 1.3.6.1.2.1.1.1) without the ".0" instance suffix that SNMP GET
        // requires. A table COLUMN (e.g. ifDescr under ifEntry) is also a leaf
        // but must NOT receive ".0" — those are queried via "查询整表" (WALK).
        //
        // We classify the node as scalar when:
        //   - it is a leaf (no children), AND
        //   - its parent is NOT a tableEntry (parent name ends with
        //     "entry"/"row"), AND
        //   - it is not itself flagged as table/tableEntry.
        // This is robust even if the "leaf" flag is missing (treated as false)
        // — the GETNEXT fallback below will still recover the value.
        const baseOid = node.oid;
        const parentName = (node.parentName || '').toLowerCase();
        const parentIsTableEntry = parentName.endsWith('entry') || parentName.endsWith('row');
        const isScalar = !!node.leaf && !parentIsTableEntry && !node.table && !node.tableEntry;
        const scalarInstanceOid = (isScalar && !baseOid.endsWith('.0')) ? baseOid + '.0' : baseOid;

        content.innerHTML = renderQueryHeader(device, node, '单个节点查询',
            '<div class="loading-inline"><div class="spinner"></div>查询中...</div>');

        // Try SNMP GET with the (possibly ".0"-suffixed) OID first. If that
        // fails — e.g. the node is actually a scalar whose leaf flag wasn't
        // set, or the device returns noSuchInstance — fall back to GETNEXT on
        // the BASE oid and keep the result only when the returned instance
        // belongs to the requested node (i.e. startsWith baseOid + ".").
        // This mirrors SnmpClient.test() and is what users observe working
        // when right-clicking -> "查询下一个".
        try {
            let res = await API.devices.getOid(device.id, { oids: [scalarInstanceOid] });
            if (res.code !== 0 || !res.data || res.data.length === 0 || !res.data[0].value) {
                // GET did not yield a value — fall back to GETNEXT.
                const next = await API.devices.getNext(device.id, { oids: [baseOid] });
                if (next.code === 0 && next.data && next.data.length > 0) {
                    const vb = next.data[0];
                    // Accept only if the returned OID is an instance of the
                    // requested base OID (e.g. baseOid + ".0" or baseOid + ".1").
                    if (vb.oid && (vb.oid === baseOid || vb.oid.startsWith(baseOid + '.'))) {
                        res = { code: 0, data: next.data };
                    } else {
                        res = { code: 0, data: [] };
                    }
                }
            }
            if (res.code === 0) {
                renderCardResult(content, res.data, device, node);
                currentResult = { type: 'card', data: res.data, device: device, node: node };
            } else {
                content.querySelector('#queryResultArea').innerHTML = errorBox(res.message);
            }
        } catch (e) {
            content.querySelector('#queryResultArea').innerHTML = errorBox(e.message);
        }
    }

    async function queryTable(node) {
        if (!node || !node.oid) {
            Toast.warning('该节点无 OID，无法查询');
            return;
        }
        const device = getSelectedDevice();
        if (!device) return;
        const content = clearContent();
        content.innerHTML = renderQueryHeader(device, node, '整表查询 (Walk)',
            '<div class="loading-inline"><div class="spinner"></div>正在获取整表数据，请稍候...</div>');
        tableState = { page: 1, pageSize: 20, sortCol: null, sortDir: 'asc', filter: {} };
        try {
            const res = await API.devices.walk(device.id, { oid: node.oid, maxRows: 500 });
            if (res.code === 0) {
                renderTableResult(content, res.data, device, node);
                currentResult = { type: 'table', data: res.data, device: device, node: node };
            } else {
                content.querySelector('#queryResultArea').innerHTML = errorBox(res.message);
            }
        } catch (e) {
            content.querySelector('#queryResultArea').innerHTML = errorBox(e.message);
        }
    }

    async function queryCustomOid() {
        const device = getSelectedDevice();
        if (!device) return;
        const body = document.createElement('div');
        body.innerHTML =
            '<div class="form-group"><label>OID</label><input class="form-control" id="q-oid" placeholder="如 1.3.6.1.2.1.1.1.0"></div>' +
            '<div class="form-group"><label>操作</label>' +
            '<select class="form-control" id="q-op"><option value="get">GET (单个)</option><option value="getnext">GETNEXT (下一个)</option><option value="getbulk">GETBULK (批量)</option><option value="walk">WALK (整表)</option></select></div>' +
            '<div class="form-group" id="bulk-params" style="display:none;"><label>最大行数</label><input class="form-control" id="q-max" type="number" value="50"></div>';
        Modal.open({
            title: '自定义 OID 查询',
            body: body,
            buttons: [
                { text: '取消', class: '' },
                { text: '查询', class: 'btn-primary', onClick: async () => {
                    const oid = body.querySelector('#q-oid').value.trim();
                    const op = body.querySelector('#q-op').value;
                    if (!oid) { Toast.warning('请输入 OID'); return false; }
                    await executeCustomQuery(device, oid, op, body.querySelector('#q-max').value || 50);
                } }
            ]
        });
        body.querySelector('#q-op').addEventListener('change', (e) => {
            body.querySelector('#bulk-params').style.display = (e.target.value === 'getbulk' || e.target.value === 'walk') ? 'block' : 'none';
        });
    }

    async function executeCustomQuery(device, oid, op, maxRows) {
        const content = clearContent();
        content.innerHTML = '<div class="panel"><div class="panel-header"><span class="panel-title">自定义查询 - ' + escapeHtml(device.name) + '</span>' +
            '<span class="status-badge ' + device.status + '">' + escapeHtml(device.name) + '</span></div>' +
            '<div class="panel-body" id="queryResultArea"><div class="loading-inline"><div class="spinner"></div>查询中...</div></div></div>';
        try {
            let res;
            if (op === 'get') res = await API.devices.getOid(device.id, { oids: [oid] });
            else if (op === 'getnext') res = await API.devices.getNext(device.id, { oids: [oid] });
            else if (op === 'getbulk') res = await API.devices.getBulk(device.id, { oid: oid, nonRepeaters: 0, maxRepetitions: parseInt(maxRows) });
            else res = await API.devices.walk(device.id, { oid: oid, maxRows: parseInt(maxRows) });
            if (res.code === 0) {
                if (op === 'walk' || (res.data.rows && res.data.rows.length)) {
                    renderTableResult(content, res.data, device, { oid: oid, name: oid });
                } else {
                    renderCardResult(content, res.data, device, { oid: oid, name: oid });
                }
            } else {
                content.querySelector('#queryResultArea').innerHTML = errorBox(res.message);
            }
        } catch (e) {
            content.querySelector('#queryResultArea').innerHTML = errorBox(e.message);
        }
    }

    function renderQueryHeader(device, node, actionTitle, bodyHtml) {
        return '<div class="panel">' +
            '<div class="panel-header">' +
            '  <div><span class="panel-title">' + escapeHtml(actionTitle) + '</span>' +
            '  <span style="margin-left:8px;color:#6b7280;font-size:13px;">' + escapeHtml(node.name || '') + ' <code>' + escapeHtml(node.oid || '') + '</code></span></div>' +
            '  <span class="status-badge ' + device.status + '">' + escapeHtml(device.name) + ' · ' + escapeHtml(device.host) + '</span>' +
            '</div>' +
            '<div class="panel-body" id="queryResultArea">' + (bodyHtml || '') + '</div>' +
            '</div>';
    }

    function renderCardResult(content, varBinds, device, node) {
        const area = content.querySelector('#queryResultArea') || content;
        if (!varBinds || varBinds.length === 0) {
            area.innerHTML = errorBox('设备未返回任何数据');
            return;
        }
        const vb = varBinds[0];
        const html =
            '<div class="result-card">' +
            '  <div class="card-row"><div class="card-label">OID</div><div class="card-value mono">' + escapeHtml(vb.oid || '') + '</div></div>' +
            '  <div class="card-row"><div class="card-label">名称</div><div class="card-value">' + escapeHtml(vb.name || node.name || '-') + '</div></div>' +
            '  <div class="card-row"><div class="card-label">值</div><div class="card-value" style="font-weight:600;color:#1677ff;">' + escapeHtml(vb.value || '(空)') + '</div></div>' +
            '  <div class="card-row"><div class="card-label">数据类型</div><div class="card-value">' + escapeHtml(vb.dataType || vb.syntax || '-') + '</div></div>' +
            '  <div class="card-row"><div class="card-label">时间戳</div><div class="card-value">' + formatTime(vb.timestamp) + '</div></div>' +
            '  <div class="card-row"><div class="card-label">设备</div><div class="card-value">' + escapeHtml(device.name) + ' (' + escapeHtml(device.host) + ')</div></div>' +
            '</div>' +
            '<div style="margin-top:12px;display:flex;gap:8px;">' +
            '  <button class="btn" onclick="Query.refresh()">刷新</button>' +
            '  <button class="btn" onclick="Query.copyOid(\'' + escapeHtml(vb.oid || '') + '\')">复制 OID</button>' +
            '  <button class="btn" onclick="Query.exportCurrent()">导出 CSV</button>' +
            '</div>';
        area.innerHTML = html;
    }

    function renderTableResult(content, data, device, node) {
        const area = content.querySelector('#queryResultArea') || content;
        const rawRows = data.rows || [];
        if (rawRows.length === 0) {
            area.innerHTML = errorBox('整表查询未返回数据');
            return;
        }

        // Pivot the raw varbinds into a table where each row is one index and
        // each column is an attribute (varbind name). The original display put
        // one varbind per row with OID/Name/Value/DataType columns; per the
        // requirement, same-index entries are now consolidated into one row
        // with attribute names as column headers, without type info.
        const pivoted = pivotTableData(rawRows, data.rootOid);
        const rows = pivoted.rows;
        const columns = pivoted.columns;

        const filtered = applyFilterAndSort(rows, columns);
        const totalPages = Math.max(1, Math.ceil(filtered.length / tableState.pageSize));
        if (tableState.page > totalPages) tableState.page = totalPages;
        const start = (tableState.page - 1) * tableState.pageSize;
        const pageRows = filtered.slice(start, start + tableState.pageSize);

        let tableHtml = '<div style="margin-bottom:8px;color:#6b7280;font-size:13px;">' +
            '共 ' + rows.length + ' 行，耗时 ' + (data.elapsedMs || 0) + 'ms · 表名: ' + escapeHtml(data.tableName || node.name || '') +
            '</div>';
        // Filters are placed inside each <th> so that they vertically align
        // with their corresponding column (previously they were in a separate
        // flex row that wrapped independently and got out of sync with the
        // table columns).
        tableHtml += '<div class="table-wrap"><table class="data-table"><thead><tr>';
        columns.forEach(col => {
            const sorted = tableState.sortCol === col ? (tableState.sortDir === 'asc' ? 'sorted-asc' : 'sorted-desc') : '';
            const filterVal = tableState.filter[col] || '';
            tableHtml += '<th class="' + sorted + '" data-sort="' + escapeHtml(col) + '">' +
                '<div class="th-head">' + escapeHtml(col) + '<span class="sort-icon"></span></div>' +
                '<input class="th-filter" placeholder="筛选" data-filter="' + escapeHtml(col) + '" value="' + escapeHtml(filterVal) + '">' +
                '</th>';
        });
        tableHtml += '</tr></thead><tbody>';
        if (pageRows.length === 0) {
            tableHtml += '<tr><td class="table-empty" colspan="' + columns.length + '">无匹配数据</td></tr>';
        } else {
            pageRows.forEach(row => {
                tableHtml += '<tr>';
                columns.forEach(col => {
                    const val = row[col];
                    tableHtml += '<td>' + escapeHtml(val == null ? '' : String(val)) + '</td>';
                });
                tableHtml += '</tr>';
            });
        }
        tableHtml += '</tbody></table></div>';

        // Pagination
        tableHtml += '<div class="pagination">' +
            '<div class="pagination-info">第 ' + (start + 1) + '-' + Math.min(start + tableState.pageSize, filtered.length) + ' 条 / 共 ' + filtered.length + ' 条</div>' +
            '<div class="pagination-controls">' +
            '  <select class="page-size-select" id="pageSizeSelect">' +
            '    <option value="10"' + (tableState.pageSize === 10 ? ' selected' : '') + '>10/页</option>' +
            '    <option value="20"' + (tableState.pageSize === 20 ? ' selected' : '') + '>20/页</option>' +
            '    <option value="50"' + (tableState.pageSize === 50 ? ' selected' : '') + '>50/页</option>' +
            '    <option value="100"' + (tableState.pageSize === 100 ? ' selected' : '') + '>100/页</option>' +
            '  </select>' +
            '  <button class="btn" data-page="1">«</button>' +
            '  <button class="btn" data-page="' + Math.max(1, tableState.page - 1) + '">‹</button>' +
            '  <span style="padding:0 8px;">' + tableState.page + ' / ' + totalPages + '</span>' +
            '  <button class="btn" data-page="' + Math.min(totalPages, tableState.page + 1) + '">›</button>' +
            '  <button class="btn" data-page="' + totalPages + '">»</button>' +
            '</div></div>';

        tableHtml += '<div style="margin-top:12px;display:flex;gap:8px;">' +
            '<button class="btn" onclick="Query.refresh()">刷新</button>' +
            '<button class="btn" onclick="Export.exportCsv(Query.getCurrentData(), \'' + escapeHtml(data.tableName || 'table') + '\')">导出 CSV</button>' +
            '<button class="btn" onclick="Export.exportExcel(Query.getCurrentData(), \'' + escapeHtml(data.tableName || 'table') + '\')">导出 Excel</button>' +
            '</div>';

        area.innerHTML = tableHtml;

        // Bind sort (clicking the column header, but not its filter input)
        area.querySelectorAll('th[data-sort]').forEach(th => {
            th.addEventListener('click', (e) => {
                if (e.target && e.target.classList && e.target.classList.contains('th-filter')) return;
                const col = th.dataset.sort;
                if (tableState.sortCol === col) {
                    tableState.sortDir = tableState.sortDir === 'asc' ? 'desc' : 'asc';
                } else {
                    tableState.sortCol = col;
                    tableState.sortDir = 'asc';
                }
                renderTableResult(content, data, device, node);
            });
        });
        // Bind filters
        area.querySelectorAll('.th-filter').forEach(input => {
            input.addEventListener('click', (e) => e.stopPropagation());
            input.addEventListener('input', (e) => {
                tableState.filter[e.target.dataset.filter] = e.target.value;
                tableState.page = 1;
                renderTableResult(content, data, device, node);
            });
        });
        // Bind pagination
        area.querySelectorAll('[data-page]').forEach(btn => {
            btn.addEventListener('click', () => {
                tableState.page = parseInt(btn.dataset.page);
                renderTableResult(content, data, device, node);
            });
        });
        const sizeSel = area.querySelector('#pageSizeSelect');
        if (sizeSel) {
            sizeSel.addEventListener('change', (e) => {
                tableState.pageSize = parseInt(e.target.value);
                tableState.page = 1;
                renderTableResult(content, data, device, node);
            });
        }
    }

    /**
     * Pivot raw SNMP WALK varbinds into rows keyed by instance index.
     *
     * A table walk returns one varbind per (column, index) pair, e.g.:
     *   ifIndex.1 = 1, ifDescr.1 = eth0, ifIndex.2 = 2, ifDescr.2 = eth1
     * The pivoted result is one row per index with columns being the
     * attribute names:
     *   索引 | ifIndex | ifDescr
     *   1    | 1       | eth0
     *   2    | 2        | eth1
     *
     * Column OIDs are derived by taking the longest common prefix of the OIDs
     * sharing the same varbind name; the index is the OID suffix after that
     * prefix. If a column has only one varbind, the last OID component is
     * treated as the index.
     */
    function pivotTableData(rawRows, rootOid) {
        const INDEX_COL = '索引';

        // Group varbinds by column name, preserving first-seen order.
        const byName = {};
        const nameOrder = [];
        for (const r of rawRows) {
            const name = r.name || r.oid || '(unknown)';
            if (!byName[name]) { byName[name] = []; nameOrder.push(name); }
            byName[name].push(r);
        }

        // Determine each column's base OID (the OID without the instance suffix).
        const columnOids = {};
        for (const name of nameOrder) {
            const group = byName[name];
            if (group.length <= 1) {
                // Single varbind: strip the last OID component as the index.
                const oid = group[0].oid || '';
                const lastDot = oid.lastIndexOf('.');
                columnOids[name] = lastDot > 0 ? oid.substring(0, lastDot) : oid;
            } else {
                columnOids[name] = longestCommonPrefixOid(group.map(g => g.oid || ''));
            }
        }

        // Group by index, pivot values into per-column fields.
        const byIndex = {};
        const indexOrder = [];
        for (const r of rawRows) {
            const name = r.name || r.oid || '(unknown)';
            const colOid = columnOids[name];
            const oid = r.oid || '';
            let index;
            if (colOid && oid.startsWith(colOid + '.')) {
                index = oid.substring(colOid.length + 1);
            } else if (colOid && oid === colOid) {
                index = '';
            } else {
                index = oid;
            }
            if (!byIndex[index]) {
                byIndex[index] = {};
                byIndex[index][INDEX_COL] = index;
                indexOrder.push(index);
            }
            byIndex[index][name] = r.value;
        }

        const columns = [INDEX_COL].concat(nameOrder);
        const rows = indexOrder.map(idx => byIndex[idx]);
        return { columns: columns, rows: rows };
    }

    function longestCommonPrefixOid(oids) {
        if (!oids || oids.length === 0) return '';
        const parts = oids.map(o => o.split('.'));
        let prefix = [];
        for (let i = 0; i < parts[0].length; i++) {
            const comp = parts[0][i];
            if (parts.every(p => i < p.length && p[i] === comp)) {
                prefix.push(comp);
            } else {
                break;
            }
        }
        let result = prefix.join('.');
        // If the common prefix equals a full OID, all varbinds share the same
        // OID (same index). Strip the last component so the index is non-empty.
        if (oids.some(o => o === result)) {
            const lastDot = result.lastIndexOf('.');
            if (lastDot > 0) result = result.substring(0, lastDot);
        }
        return result;
    }

    function detectColumns(rows) {
        // Use first row's keys, with preferred order
        const preferred = ['OID', 'oid', 'Name', 'name', 'Value', 'value', 'DataType', 'dataType', 'Syntax', 'syntax', 'DisplayValue', 'displayValue', 'Timestamp', 'timestamp'];
        const keys = Object.keys(rows[0]);
        const ordered = [];
        preferred.forEach(p => { if (keys.includes(p)) ordered.push(p); });
        keys.forEach(k => { if (!ordered.includes(k)) ordered.push(k); });
        return ordered;
    }

    function applyFilterAndSort(rows, columns) {
        let filtered = rows.slice();
        Object.keys(tableState.filter).forEach(col => {
            const val = tableState.filter[col];
            if (val) {
                filtered = filtered.filter(r => String(r[col] || '').toLowerCase().includes(val.toLowerCase()));
            }
        });
        if (tableState.sortCol) {
            const col = tableState.sortCol;
            const dir = tableState.sortDir === 'asc' ? 1 : -1;
            filtered.sort((a, b) => {
                const av = a[col], bv = b[col];
                if (av == null) return 1;
                if (bv == null) return -1;
                // Numeric sort for OID-like or numeric
                if (!isNaN(av) && !isNaN(bv)) return (Number(av) - Number(bv)) * dir;
                return String(av).localeCompare(String(bv)) * dir;
            });
        }
        return filtered;
    }

    function errorBox(msg) {
        return '<div class="panel"><div class="panel-body" style="color:#ff4d4f;">⚠ 查询失败: ' + escapeHtml(msg) + '</div></div>' +
            '<div style="margin-top:12px;color:#6b7280;font-size:13px;"><b>可能原因:</b><ul style="margin-top:4px;padding-left:20px;">' +
            '<li>设备未开启 SNMP 服务或端口被防火墙拦截</li>' +
            '<li>共同体名/认证参数错误</li>' +
            '<li>设备响应超时，请尝试增大超时时间或重试次数</li>' +
            '<li>请求的 OID 在设备上不存在</li>' +
            '</ul></div>';
    }

    function copyOid(oid) {
        navigator.clipboard.writeText(oid).then(() => Toast.success('OID 已复制: ' + oid));
    }

    async function refresh() {
        if (!currentResult) return;
        if (currentResult.type === 'card') {
            await querySingleNode(currentResult.node);
        } else {
            await queryTable(currentResult.node);
        }
    }

    function getCurrentData() {
        if (!currentResult) return null;
        if (currentResult.type === 'card') {
            return { columns: ['OID', 'Name', 'Value', 'DataType', 'Timestamp'], rows: currentResult.data.map(vb => ({
                OID: vb.oid, Name: vb.name, Value: vb.value, DataType: vb.dataType, Timestamp: formatTime(vb.timestamp)
            })) };
        } else {
            // Return the pivoted (index-per-row, attribute-per-column) form so
            // CSV/Excel exports match the on-screen table layout.
            const rawRows = currentResult.data.rows || [];
            const pivoted = pivotTableData(rawRows, currentResult.data.rootOid);
            return { columns: pivoted.columns, rows: pivoted.rows };
        }
    }

    function exportCurrent() {
        const data = getCurrentData();
        if (data) Export.exportCsv(data, 'query-result');
    }

    return { querySingleNode, queryTable, queryCustomOid, refresh, copyOid, exportCurrent, getCurrentData };
})();
