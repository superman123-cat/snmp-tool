// Device management module
const Devices = (function () {
    let devices = [];
    let selectedDevice = null;
    let onSelect = null;

    async function load() {
        const list = document.getElementById('deviceList');
        list.innerHTML = '<div class="loading-inline"><div class="spinner"></div>加载中...</div>';
        try {
            const res = await API.devices.list();
            if (res.code === 0) {
                devices = res.data || [];
                render();
            } else {
                list.innerHTML = '<div class="empty-state"><p>' + escapeHtml(res.message || '加载失败') + '</p></div>';
            }
        } catch (e) {
            list.innerHTML = '<div class="empty-state"><p>加载失败: ' + escapeHtml(e.message) + '</p></div>';
        }
    }

    function render() {
        const list = document.getElementById('deviceList');
        if (devices.length === 0) {
            list.innerHTML = '<div class="empty-state"><p>尚未添加设备</p>' +
                '<button class="btn btn-primary btn-sm" onclick="document.getElementById(\'addDeviceBtn\').click()">立即添加</button></div>';
            updateHomeStats();
            return;
        }
        list.innerHTML = devices.map(d => {
            return '<div class="device-card" data-id="' + d.id + '">' +
                '<div class="device-card-header">' +
                '<span><span class="status-dot ' + d.status + '"></span>' +
                '<span class="device-card-name">' + escapeHtml(d.name) + '</span></span>' +
                '<span class="status-badge ' + d.status + '">' + statusText(d.status) + '</span>' +
                '</div>' +
                '<div class="device-card-host">' + escapeHtml(d.host) + ':' + d.port + ' (' + escapeHtml(d.version || 'v2c') + ')</div>' +
                (d.lastCheckedAt ? '<div class="device-meta">最近检查: ' + formatTime(d.lastCheckedAt) + '</div>' : '') +
                (d.lastError ? '<div class="device-meta" style="color:#ff7a45;" title="' + escapeHtml(d.lastError) + '">错误: ' + escapeHtml(truncate(d.lastError, 40)) + '</div>' : '') +
                '</div>';
        }).join('');
        list.querySelectorAll('.device-card').forEach(card => {
            card.addEventListener('click', () => {
                const id = card.dataset.id;
                const d = devices.find(x => x.id === id);
                selectDevice(d, card);
            });
        });
        updateHomeStats();
    }

    function statusText(s) {
        const map = { online: '在线', offline: '离线', connecting: '连接中', auth_failed: '认证失败', unknown: '未知' };
        return map[s] || s;
    }

    function truncate(s, n) { return s && s.length > n ? s.substring(0, n) + '...' : s; }

    function selectDevice(d, el) {
        selectedDevice = d;
        document.querySelectorAll('.device-card').forEach(c => c.classList.remove('selected'));
        if (el) el.classList.add('selected');
        if (onSelect) onSelect(d);
    }

    function getSelected() { return selectedDevice; }

    function add() {
        openDeviceForm(null);
    }

    function edit(device) {
        openDeviceForm(device);
    }

    function openDeviceForm(device) {
        const d = device || { version: 'v2c', port: 161, timeoutMs: 3000, retries: 1, community: 'public' };
        const body = document.createElement('div');
        body.innerHTML =
            '<div class="form-section">' +
            '  <div class="form-section-title">基本信息</div>' +
            '  <div class="form-row">' +
            '    <div class="form-group"><label>设备名称 <span class="required">*</span></label><input class="form-control" id="f-name" value="' + escapeHtml(d.name || '') + '"></div>' +
            '    <div class="form-group"><label>IP 地址 <span class="required">*</span></label><input class="form-control" id="f-host" value="' + escapeHtml(d.host || '') + '" placeholder="如 192.168.1.1"></div>' +
            '    <div class="form-group" style="max-width:120px;"><label>端口</label><input class="form-control" id="f-port" type="number" value="' + (d.port || 161) + '"></div>' +
            '  </div>' +
            '</div>' +
            '<div class="form-section">' +
            '  <div class="form-section-title">SNMP 协议</div>' +
            '  <div class="form-row">' +
            '    <div class="form-group" style="max-width:160px;"><label>版本</label>' +
            '      <select class="form-control" id="f-version">' +
            '        <option value="v1"' + (d.version === 'v1' ? ' selected' : '') + '>SNMPv1</option>' +
            '        <option value="v2c"' + (d.version === 'v2c' ? ' selected' : '') + '>SNMPv2c</option>' +
            '        <option value="v3"' + (d.version === 'v3' ? ' selected' : '') + '>SNMPv3</option>' +
            '      </select></div>' +
            '    <div class="form-group" style="max-width:140px;"><label>超时(ms)</label><input class="form-control" id="f-timeout" type="number" value="' + (d.timeoutMs || 3000) + '"></div>' +
            '    <div class="form-group" style="max-width:140px;"><label>重试次数</label><input class="form-control" id="f-retries" type="number" value="' + (d.retries == null ? 1 : d.retries) + '"></div>' +
            '  </div>' +
            '  <div id="v2c-fields">' +
            '    <div class="form-group"><label>共同体名 (Community)</label><input class="form-control" id="f-community" value="' + escapeHtml(d.community || 'public') + '"></div>' +
            '  </div>' +
            '  <div id="v3-fields" style="display:none;">' +
            '    <div class="form-row">' +
            '      <div class="form-group"><label>用户名</label><input class="form-control" id="f-securityName" value="' + escapeHtml(d.securityName || '') + '"></div>' +
            '      <div class="form-group"><label>安全级别</label>' +
            '        <select class="form-control" id="f-securityLevel">' +
            '          <option value="noAuthNoPriv"' + (d.securityLevel === 'noAuthNoPriv' ? ' selected' : '') + '>noAuthNoPriv</option>' +
            '          <option value="authNoPriv"' + (d.securityLevel === 'authNoPriv' ? ' selected' : '') + '>authNoPriv</option>' +
            '          <option value="authPriv"' + (d.securityLevel === 'authPriv' ? ' selected' : '') + '>authPriv</option>' +
            '        </select></div>' +
            '    </div>' +
            '    <div class="form-row">' +
            '      <div class="form-group"><label>认证协议</label>' +
            '        <select class="form-control" id="f-authProtocol">' +
            '          <option value="MD5"' + (d.authProtocol === 'MD5' ? ' selected' : '') + '>MD5</option>' +
            '          <option value="SHA"' + (d.authProtocol === 'SHA' ? ' selected' : '') + '>SHA</option>' +
            '        </select></div>' +
            '      <div class="form-group"><label>认证密码</label><input class="form-control" id="f-authPassword" type="password" value="' + (d.authPassword === '********' ? '' : escapeHtml(d.authPassword || '')) + '" placeholder="' + (d.authPassword ? '(已设置，留空不修改)' : '') + '"></div>' +
            '    </div>' +
            '    <div class="form-row">' +
            '      <div class="form-group"><label>加密协议</label>' +
            '        <select class="form-control" id="f-privProtocol">' +
            '          <option value="DES"' + (d.privProtocol === 'DES' ? ' selected' : '') + '>DES</option>' +
            '          <option value="AES"' + (d.privProtocol === 'AES' ? ' selected' : '') + '>AES</option>' +
            '        </select></div>' +
            '      <div class="form-group"><label>加密密码</label><input class="form-control" id="f-privPassword" type="password" value="' + (d.privPassword === '********' ? '' : escapeHtml(d.privPassword || '')) + '" placeholder="' + (d.privPassword ? '(已设置，留空不修改)' : '') + '"></div>' +
            '    </div>' +
            '    <div class="form-group"><label>上下文名</label><input class="form-control" id="f-contextName" value="' + escapeHtml(d.contextName || '') + '"></div>' +
            '  </div>' +
            '</div>';

        const modal = Modal.open({
            title: device ? '编辑设备' : '添加设备',
            size: 'lg',
            body: body,
            buttons: [
                { text: '取消', class: '' },
                { text: '测试连接', class: '', onClick: () => { saveFromForm(body, device, true); return false; } },
                { text: '保存', class: 'btn-primary', onClick: () => saveFromForm(body, device, false) }
            ]
        });

        function toggleFields() {
            const v = body.querySelector('#f-version').value;
            body.querySelector('#v2c-fields').style.display = (v === 'v1' || v === 'v2c') ? 'block' : 'none';
            body.querySelector('#v3-fields').style.display = (v === 'v3') ? 'block' : 'none';
        }
        body.querySelector('#f-version').addEventListener('change', toggleFields);
        toggleFields();
    }

    async function saveFromForm(body, existing, testOnly) {
        const v = body.querySelector('#f-version').value;
        const authPass = body.querySelector('#f-authPassword');
        const privPass = body.querySelector('#f-privPassword');
        const device = {
            name: body.querySelector('#f-name').value.trim(),
            host: body.querySelector('#f-host').value.trim(),
            port: parseInt(body.querySelector('#f-port').value) || 161,
            version: v,
            timeoutMs: parseInt(body.querySelector('#f-timeout').value) || 3000,
            retries: parseInt(body.querySelector('#f-retries').value) || 0
        };
        if (!device.name) { Toast.warning('请填写设备名称'); return false; }
        if (!device.host) { Toast.warning('请填写 IP 地址'); return false; }
        if (v === 'v1' || v === 'v2c') {
            device.community = body.querySelector('#f-community').value;
        } else if (v === 'v3') {
            device.securityName = body.querySelector('#f-securityName').value;
            device.securityLevel = body.querySelector('#f-securityLevel').value;
            device.authProtocol = body.querySelector('#f-authProtocol').value;
            device.privProtocol = body.querySelector('#f-privProtocol').value;
            device.contextName = body.querySelector('#f-contextName').value;
            if (authPass.value) device.authPassword = authPass.value;
            else if (existing && existing.authPassword) device.authPassword = '********';
            if (privPass.value) device.privPassword = privPass.value;
            else if (existing && existing.privPassword) device.privPassword = '********';
        }

        if (testOnly) {
            // Test without saving - create temp device
            try {
                const tempDevice = Object.assign({}, device);
                if (existing) tempDevice.id = existing.id;
                await testDevice(tempDevice, true);
            } catch (e) { /* toast shown in testDevice */ }
            return false;
        }

        try {
            let res;
            if (existing) {
                res = await API.devices.update(existing.id, device);
            } else {
                res = await API.devices.add(device);
            }
            if (res.code === 0) {
                Toast.success(existing ? '设备已更新' : '设备已添加');
                await load();
                return true;
            } else {
                Toast.error(res.message || '保存失败');
                return false;
            }
        } catch (e) {
            Toast.error('保存失败: ' + e.message);
            return false;
        }
    }

    async function testDevice(device, isFormTest) {
        // If device has id, use the test endpoint on the saved device
        if (device.id && !isFormTest) {
            Toast.info('正在测试 ' + device.name + ' ...');
            try {
                const res = await API.devices.test(device.id);
                if (res.code === 0) {
                    Toast.success('连接成功: ' + (res.data.sysDescr || '').substring(0, 50));
                    await load();
                } else {
                    Toast.error('连接失败: ' + res.message);
                }
            } catch (e) {
                Toast.error('连接失败: ' + e.message);
            }
            return;
        }
        // Form test (no id yet): use the transient test endpoint which does NOT
        // persist a device record. Previously this called API.devices.add() to
        // obtain an id, which left a stray device; the subsequent "保存" then
        // created a second record.
        try {
            Toast.info('正在测试 ' + (device.name || device.host) + ' ...');
            const res = await API.devices.testTransient(device);
            if (res.code === 0) {
                Toast.success('连接成功: ' + (res.data.sysDescr || '').substring(0, 50));
            } else {
                Toast.error('连接失败: ' + res.message);
            }
        } catch (e) {
            Toast.error('连接失败: ' + e.message);
        }
    }

    async function refreshAll() {
        if (devices.length === 0) { Toast.info('没有设备需要刷新'); return; }
        Toast.info('正在刷新 ' + devices.length + ' 个设备...');
        try {
            const res = await API.devices.refreshAll();
            if (res.code === 0) {
                devices = res.data || [];
                render();
                const online = devices.filter(d => d.status === 'online').length;
                Toast.success('刷新完成，在线 ' + online + '/' + devices.length);
            } else {
                Toast.error(res.message || '刷新失败');
            }
        } catch (e) {
            Toast.error('刷新失败: ' + e.message);
        }
    }

    function remove(device) {
        Modal.confirm('确定要删除设备「' + device.name + '」吗？', '删除设备', async () => {
            try {
                const res = await API.devices.remove(device.id);
                if (res.code === 0) {
                    Toast.success('设备已删除');
                    await load();
                } else {
                    Toast.error(res.message || '删除失败');
                }
            } catch (e) {
                Toast.error('删除失败: ' + e.message);
            }
        });
    }

    function updateHomeStats() {
        const elOnline = document.getElementById('statOnline');
        const elDevices = document.getElementById('statDevices');
        if (elDevices) elDevices.textContent = devices.length;
        if (elOnline) elOnline.textContent = devices.filter(d => d.status === 'online').length;
    }

    return { load, add, edit, remove, refreshAll, getSelected, selectDevice, render,
        onSelect: (fn) => { onSelect = fn; } };
})();
