// API client with auth token handling
const API = (function () {
    const TOKEN_KEY = 'snmp_token';
    const TOKEN_TYPE_KEY = 'snmp_token_type';
    const USER_KEY = 'snmp_user';

    function getToken() {
        return localStorage.getItem(TOKEN_KEY);
    }
    function getTokenType() {
        return localStorage.getItem(TOKEN_TYPE_KEY) || 'Bearer';
    }
    function setToken(token, tokenType) {
        if (token) {
            localStorage.setItem(TOKEN_KEY, token);
            localStorage.setItem(TOKEN_TYPE_KEY, tokenType || 'Bearer');
        } else {
            localStorage.removeItem(TOKEN_KEY);
            localStorage.removeItem(TOKEN_TYPE_KEY);
        }
    }
    function saveUser(user) {
        if (user) {
            localStorage.setItem(USER_KEY, JSON.stringify(user));
        } else {
            localStorage.removeItem(USER_KEY);
        }
    }
    function getUser() {
        const raw = localStorage.getItem(USER_KEY);
        return raw ? JSON.parse(raw) : null;
    }
    function clearAuth() {
        setToken(null);
        saveUser(null);
    }

    async function request(method, url, body, opts) {
        opts = opts || {};
        const headers = { 'Accept': 'application/json' };
        const token = getToken();
        if (token) {
            headers['Authorization'] = getTokenType() + ' ' + token;
        }
        let isForm = body instanceof FormData;
        if (body && !isForm && (method === 'POST' || method === 'PUT' || method === 'PATCH')) {
            headers['Content-Type'] = 'application/json';
            body = JSON.stringify(body);
        }
        let controller = null;
        let timeoutId = null;
        if (opts.timeout) {
            controller = new AbortController();
            timeoutId = setTimeout(() => controller.abort(), opts.timeout);
        }
        try {
            const res = await fetch(url, {
                method: method,
                headers: headers,
                body: body,
                signal: controller ? controller.signal : undefined,
                credentials: 'same-origin'
            });
            if (res.status === 401) {
                clearAuth();
                if (location.pathname !== '/login.html') {
                    location.href = '/login.html';
                    throw new Error('未登录或会话过期');
                }
            }
            let data;
            const ct = res.headers.get('content-type') || '';
            if (ct.includes('application/json')) {
                data = await res.json();
            } else {
                data = await res.text();
            }
            if (!res.ok && data && typeof data === 'object' && data.message) {
                const err = new Error(data.message);
                err.code = data.code;
                err.response = data;
                throw err;
            }
            return data;
        } catch (e) {
            if (e.name === 'AbortError') {
                throw new Error('请求超时');
            }
            throw e;
        } finally {
            if (timeoutId) clearTimeout(timeoutId);
        }
    }

    return {
        get: (url, opts) => request('GET', url, null, opts),
        post: (url, body, opts) => request('POST', url, body, opts),
        put: (url, body, opts) => request('PUT', url, body, opts),
        del: (url, opts) => request('DELETE', url, null, opts),
        getToken, getTokenType, setToken, getUser, saveUser, clearAuth,
        // Auth
        auth: {
            login: (username, password) => request('POST', '/api/auth/login', { username, password }),
            logout: () => request('POST', '/api/auth/logout'),
            status: () => request('GET', '/api/auth/status')
        },
        // MIB
        mib: {
            upload: (formData) => fetch('/api/mib/upload', {
                method: 'POST',
                headers: { 'Authorization': getTokenType() + ' ' + getToken() },
                body: formData,
                credentials: 'same-origin'
            }).then(r => r.json()),
            progress: (batchId) => request('GET', '/api/mib/upload/progress/' + batchId),
            modules: () => request('GET', '/api/mib/modules'),
            tree: (oid) => request('GET', '/api/mib/tree' + (oid ? '?oid=' + encodeURIComponent(oid) : '')),
            node: (params) => request('GET', '/api/mib/node?' + new URLSearchParams(params)),
            search: (params) => request('GET', '/api/mib/search?' + new URLSearchParams(params)),
            deleteModule: (name) => request('DELETE', '/api/mib/modules/' + encodeURIComponent(name)),
            clearAll: () => request('DELETE', '/api/mib/modules'),
            stats: () => request('GET', '/api/mib/stats')
        },
        // Devices
        devices: {
            list: () => request('GET', '/api/devices'),
            get: (id) => request('GET', '/api/devices/' + id),
            add: (device) => request('POST', '/api/devices', device),
            update: (id, device) => request('PUT', '/api/devices/' + id, device),
            remove: (id) => request('DELETE', '/api/devices/' + id),
            test: (id) => request('POST', '/api/devices/' + id + '/test'),
            testTransient: (device) => request('POST', '/api/devices/test', device),
            refreshAll: () => request('POST', '/api/devices/refresh-all'),
            getOid: (id, oids) => request('POST', '/api/devices/' + id + '/get', oids),
            getNext: (id, oids) => request('POST', '/api/devices/' + id + '/getnext', oids),
            getBulk: (id, body) => request('POST', '/api/devices/' + id + '/getbulk', body),
            walk: (id, body) => request('POST', '/api/devices/' + id + '/walk', body, { timeout: 60000 })
        },
        system: {
            info: () => request('GET', '/api/system/info')
        }
    };
})();
