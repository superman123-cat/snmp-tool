// Modal dialog manager
const Modal = (function () {
    function open(options) {
        options = options || {};
        const root = document.getElementById('modalRoot');
        const overlay = document.createElement('div');
        overlay.className = 'modal-overlay';
        if (options.size === 'lg') overlay.querySelector;
        const modal = document.createElement('div');
        modal.className = 'modal' + (options.size === 'lg' ? ' modal-lg' : options.size === 'sm' ? ' modal-sm' : '');
        modal.innerHTML =
            '<div class="modal-header">' +
            '  <div class="modal-title">' + escapeHtml(options.title || '') + '</div>' +
            '  <button class="modal-close" data-close>×</button>' +
            '</div>' +
            '<div class="modal-body"></div>' +
            (options.footer === false ? '' : '<div class="modal-footer"></div>');
        overlay.appendChild(modal);
        const body = modal.querySelector('.modal-body');
        if (typeof options.body === 'string') {
            body.innerHTML = options.body;
        } else if (options.body instanceof HTMLElement) {
            body.appendChild(options.body);
        }
        const footer = modal.querySelector('.modal-footer');
        if (footer && options.buttons) {
            options.buttons.forEach(btn => {
                const b = document.createElement('button');
                b.className = 'btn ' + (btn.class || '');
                b.textContent = btn.text;
                b.addEventListener('click', () => {
                    if (btn.onClick) {
                        const result = btn.onClick(modal, body);
                        if (result !== false) close();
                    } else {
                        close();
                    }
                });
                footer.appendChild(b);
            });
        }
        function close() {
            if (overlay.parentNode) overlay.parentNode.removeChild(overlay);
            if (options.onClose) options.onClose();
        }
        modal.querySelector('[data-close]').addEventListener('click', close);
        overlay.addEventListener('click', (e) => { if (e.target === overlay && options.closeOnBackdrop !== false) close(); });
        root.appendChild(overlay);
        return { modal, body, overlay, close };
    }

    function confirm(message, title, onConfirm, options) {
        options = options || {};
        return open({
            title: title || '确认',
            size: 'sm',
            body: '<p style="margin:0;color:#1f2937;">' + escapeHtml(message) + '</p>',
            buttons: [
                { text: '取消', class: '' },
                { text: options.okText || '确定', class: 'btn-primary', onClick: () => onConfirm && onConfirm() }
            ]
        });
    }

    function alert(message, title) {
        return open({
            title: title || '提示',
            size: 'sm',
            body: '<p style="margin:0;color:#1f2937;">' + escapeHtml(message) + '</p>',
            buttons: [{ text: '确定', class: 'btn-primary' }]
        });
    }

    return { open, confirm, alert };
})();
