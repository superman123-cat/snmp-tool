// Login page logic
(function () {
    // If already authenticated, redirect to main app
    async function checkAuth() {
        try {
            const res = await API.auth.status();
            if (res.code === 0 && res.data && res.data.authEnabled && res.data.valid) {
                location.href = '/index.html';
                return true;
            }
        } catch (e) {}
        return false;
    }

    function bindForm() {
        const form = document.getElementById('loginForm');
        const errBox = document.getElementById('loginError');
        const btn = document.getElementById('loginBtn');
        form.addEventListener('submit', async (e) => {
            e.preventDefault();
            errBox.style.display = 'none';
            const username = document.getElementById('username').value.trim();
            const password = document.getElementById('password').value;
            if (!username || !password) {
                showError('请输入用户名和密码');
                return;
            }
            btn.disabled = true;
            btn.textContent = '登录中...';
            try {
                const res = await API.auth.login(username, password);
                if (res.code === 0) {
                    API.setToken(res.data.token, res.data.tokenType);
                    API.saveUser({
                        username: res.data.username,
                        displayName: res.data.displayName,
                        roles: res.data.roles
                    });
                    Toast.success('登录成功，正在跳转...');
                    setTimeout(() => location.href = '/index.html', 500);
                } else {
                    showError(res.message || '登录失败');
                }
            } catch (e) {
                showError(e.message || '登录失败，请检查网络');
            } finally {
                btn.disabled = false;
                btn.textContent = '登 录';
            }
        });

        function showError(msg) {
            errBox.textContent = msg;
            errBox.style.display = 'block';
        }
    }

    document.addEventListener('DOMContentLoaded', () => {
        checkAuth().then(authed => {
            if (!authed) bindForm();
        });
    });
})();
