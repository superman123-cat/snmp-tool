// Toast notifications
const Toast = (function () {
    const ICONS = { success: '✓', error: '✕', warning: '⚠', info: 'ℹ' };

    function show(message, type, duration) {
        type = type || 'info';
        duration = duration || 3000;
        const container = document.getElementById('toastContainer');
        if (!container) {
            alert(message);
            return;
        }
        const toast = document.createElement('div');
        toast.className = 'toast ' + type;
        toast.innerHTML =
            '<span class="toast-icon">' + (ICONS[type] || ICONS.info) + '</span>' +
            '<div class="toast-body"><div class="toast-message">' + escapeHtml(message) + '</div></div>' +
            '<button class="toast-close">×</button>';
        container.appendChild(toast);
        const close = () => {
            toast.style.opacity = '0';
            toast.style.transform = 'translateX(120%)';
            setTimeout(() => toast.remove(), 250);
        };
        toast.querySelector('.toast-close').addEventListener('click', close);
        if (duration > 0) setTimeout(close, duration);
        return toast;
    }

    function escapeHtml(s) {
        if (s == null) return '';
        return String(s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
    }

    return {
        success: (m, d) => show(m, 'success', d),
        error: (m, d) => show(m, 'error', d || 5000),
        warning: (m, d) => show(m, 'warning', d),
        info: (m, d) => show(m, 'info', d),
        show
    };
})();

// HTML escape helper (global)
function escapeHtml(s) {
    if (s == null) return '';
    return String(s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

// Loading overlay
const Loading = (function () {
    let overlay = null;
    function show(text) {
        hide();
        overlay = document.createElement('div');
        overlay.className = 'loading-overlay';
        overlay.innerHTML = '<div style="text-align:center;"><div class="spinner"></div>' +
            (text ? '<div style="margin-top:12px;color:#555;">' + escapeHtml(text) + '</div>' : '') + '</div>';
        document.body.appendChild(overlay);
    }
    function hide() {
        if (overlay && overlay.parentNode) {
            overlay.parentNode.removeChild(overlay);
        }
        overlay = null;
    }
    return { show, hide };
})();

// Format timestamp
function formatTime(ts) {
    if (!ts) return '-';
    const d = new Date(ts);
    const pad = n => String(n).padStart(2, '0');
    return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate()) +
        ' ' + pad(d.getHours()) + ':' + pad(d.getMinutes()) + ':' + pad(d.getSeconds());
}
