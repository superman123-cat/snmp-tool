// MIB tree navigation module
const MibTree = (function () {
    let treeData = [];
    let selectedNode = null;
    let onNodeSelect = null;
    let onNodeContext = null;
    let highlightText = null;
    // Lower-cased search keyword used by renderNode to tag matches in one pass
    // (set by filterTree, cleared on render). Avoids a second querySelectorAll.
    let matchKeywordLower = null;
    const containerId = 'mibTreeContainer';
    const expandedState = {}; // by oid

    async function load() {
        const container = document.getElementById(containerId);
        container.innerHTML = '<div class="loading-inline"><div class="spinner"></div>加载中...</div>';
        try {
            const res = await API.mib.tree();
            if (res.code === 0) {
                treeData = res.data || [];
                // Default-expand top-level roots only on a fresh load so the
                // tree isn't collapsed flat on first view. This must NOT run
                // inside render()/renderNode, otherwise collapseAll() (which
                // sets expandedState[oid]=false then calls render) would have
                // its top-level overrides immediately re-expanded.
                treeData.forEach(n => { if (n.oid) expandedState[n.oid] = true; });
                render();
            } else {
                container.innerHTML = '<div class="empty-state"><p>' + escapeHtml(res.message || '加载失败') + '</p></div>';
            }
        } catch (e) {
            container.innerHTML = '<div class="empty-state"><p>加载失败: ' + escapeHtml(e.message) + '</p></div>';
        }
    }

    function render() {
        const container = document.getElementById(containerId);
        if (!treeData || treeData.length === 0) {
            container.innerHTML = '<div class="empty-state"><p>尚未导入 MIB 文件</p>' +
                '<button class="btn btn-primary btn-sm" onclick="document.getElementById(\'uploadMibBtn\').click()">立即导入</button></div>';
            return;
        }
        // Use a DocumentFragment so the browser only does one reflow for the
        // whole top-level tree instead of one per appendChild.
        const frag = document.createDocumentFragment();
        treeData.forEach(node => {
            frag.appendChild(renderNode(node, 0));
        });
        container.innerHTML = '';
        container.appendChild(frag);
    }

    function renderNode(node, depth) {
        const el = document.createElement('div');
        el.className = 'mib-node';
        el.dataset.oid = node.oid || '';
        el.dataset.name = node.name || '';
        const hasChildren = node.children && node.children.length > 0;
        const showChildren = node.oid ? (expandedState[node.oid] === true) : false;

        // Toggle affordance: arrow for branch nodes, dot marker for leaves.
        const toggleIcon = hasChildren ? (showChildren ? '▼' : '▶') : '';
        const toggleClass = hasChildren ? 'toggle' : 'toggle empty';
        let icon = '📄';
        if (hasChildren) icon = showChildren ? '📂' : '📁';
        if (node.table) icon = '🗂';
        if (node.tableEntry) icon = '📋';

        let nameHtml = node.name || '(unnamed)';
        if (highlightText) {
            nameHtml = highlightMatch(nameHtml, highlightText);
        }
        let oidHtml = node.oid ? '<span class="node-oid">' + node.oid + '</span>' : '';
        let badge = '';
        if (node.access && node.access !== 'not-accessible') {
            badge = '<span class="badge">' + escapeHtml(node.access) + '</span>';
        }

        // Row that holds icon + name + oid. Per-depth indent is provided by
        // the .mib-children container's margin/padding (and the guide line),
        // so we do NOT set inline paddingLeft here.
        const row = document.createElement('div');
        row.className = 'mib-node-row';
        if (selectedNode && selectedNode.oid === node.oid) row.classList.add('selected');
        // Apply search-match class during render so filterTree doesn't need a
        // second full-DOM querySelectorAll pass to mark matches.
        if (matchKeywordLower) {
            const nm = (node.name || '').toLowerCase();
            const oid = node.oid || '';
            if ((nm && nm.indexOf(matchKeywordLower) !== -1) || oid.indexOf(matchKeywordLower) !== -1) {
                row.classList.add('search-match');
            }
        }
        row.innerHTML =
            '<span class="' + toggleClass + '" data-toggle>' + toggleIcon + '</span>' +
            '<span class="node-icon">' + icon + '</span>' +
            '<span class="node-name">' + nameHtml + '</span>' +
            oidHtml + badge;
        el.appendChild(row);

        row.addEventListener('click', (e) => {
            if (e.target.hasAttribute('data-toggle') && hasChildren) {
                toggleNode(node, el, depth);
            } else {
                selectNode(node, el, row);
            }
        });

        row.addEventListener('contextmenu', (e) => {
            e.preventDefault();
            selectNode(node, el, row);
            if (onNodeContext) onNodeContext(node, e);
        });

        if (hasChildren && showChildren) {
            const childrenWrap = document.createElement('div');
            childrenWrap.className = 'mib-children';
            node.children.forEach(child => {
                childrenWrap.appendChild(renderNode(child, depth + 1));
            });
            el.appendChild(childrenWrap);
        }
        return el;
    }

    function toggleNode(node, el, depth) {
        const oid = node.oid;
        const willShow = oid ? !expandedState[oid] : true;
        if (oid) expandedState[oid] = willShow;

        // Remove existing .mib-children container (if any)
        const existingChildren = el.querySelector(':scope > .mib-children');
        if (existingChildren) {
            el.removeChild(existingChildren);
        }

        // Update the toggle icon on the existing row
        const row = el.querySelector(':scope > .mib-node-row');
        if (row) {
            const toggleSpan = row.querySelector('.toggle');
            if (toggleSpan) {
                toggleSpan.textContent = willShow ? '▼' : '▶';
            }
            const iconSpan = row.querySelector('.node-icon');
            if (iconSpan && node.children && node.children.length > 0) {
                iconSpan.textContent = willShow ? '📂' : '📁';
            }
        }

        // Append children vertically beneath the row
        if (willShow && node.children && node.children.length > 0) {
            const childrenWrap = document.createElement('div');
            childrenWrap.className = 'mib-children';
            node.children.forEach(child => {
                childrenWrap.appendChild(renderNode(child, (depth || 0) + 1));
            });
            el.appendChild(childrenWrap);
        }
    }

    function selectNode(node, el, row) {
        selectedNode = node;
        document.querySelectorAll('.mib-node-row.selected').forEach(n => n.classList.remove('selected'));
        if (row) row.classList.add('selected');
        else if (el) {
            const r = el.querySelector(':scope > .mib-node-row');
            if (r) r.classList.add('selected');
        }
        if (onNodeSelect) onNodeSelect(node);
    }

    function expandAll() {
        function walk(nodes) {
            for (const n of nodes) {
                if (n.oid) expandedState[n.oid] = true;
                if (n.children && n.children.length) walk(n.children);
            }
        }
        walk(treeData);
        render();
    }

    function collapseAll() {
        function walk(nodes) {
            for (const n of nodes) {
                if (n.oid) expandedState[n.oid] = false;
                if (n.children && n.children.length) walk(n.children);
            }
        }
        walk(treeData);
        render();
    }

    function expandToLevel(maxLevel) {
        function walk(nodes, level) {
            for (const n of nodes) {
                if (n.oid) expandedState[n.oid] = level < maxLevel;
                if (n.children && n.children.length) walk(n.children, level + 1);
            }
        }
        walk(treeData, 0);
        render();
    }

    function highlightMatch(text, keyword) {
        if (!keyword) return escapeHtml(text);
        const lower = text.toLowerCase();
        const k = keyword.toLowerCase();
        const idx = lower.indexOf(k);
        if (idx < 0) return escapeHtml(text);
        return escapeHtml(text.substring(0, idx)) +
            '<span class="highlight">' + escapeHtml(text.substring(idx, idx + keyword.length)) + '</span>' +
            escapeHtml(text.substring(idx + keyword.length));
    }

    function filterTree(keyword) {
        if (!keyword) {
            highlightText = null;
            matchKeywordLower = null;
            render();
            return;
        }
        highlightText = keyword;
        matchKeywordLower = keyword.toLowerCase();
        // Expand all matches' ancestors. Walk with a mutable ancestors array
        // (push/pop) to avoid allocating a new array per recursion level, and
        // reuse the precomputed lowercase keyword instead of recomputing it
        // for every node x2 checks.
        const stack = [];
        (function walk(nodes) {
            for (const n of nodes) {
                const nm = n.name ? n.name.toLowerCase() : '';
                const oid = n.oid || '';
                const match = (nm && nm.indexOf(matchKeywordLower) !== -1) || oid.indexOf(matchKeywordLower) !== -1;
                if (match) {
                    for (let i = 0; i < stack.length; i++) {
                        const a = stack[i];
                        if (a.oid) expandedState[a.oid] = true;
                    }
                }
                if (n.children && n.children.length) {
                    stack.push(n);
                    walk(n.children);
                    stack.pop();
                }
            }
        })(treeData);
        render();
    }

    function locateByOid(oid) {
        // Expand ancestors and scroll to the node
        const path = findPath(treeData, oid);
        if (!path) {
            Toast.warning('未在树中找到 OID: ' + oid);
            return;
        }
        path.forEach(n => { if (n.oid) expandedState[n.oid] = true; });
        render();
        setTimeout(() => {
            const el = document.querySelector('.mib-node[data-oid="' + oid + '"]');
            if (el) {
                el.scrollIntoView({ behavior: 'smooth', block: 'center' });
                const row = el.querySelector(':scope > .mib-node-row');
                if (row) row.classList.add('selected');
                const node = path[path.length - 1];
                selectedNode = node;
                if (onNodeSelect) onNodeSelect(node);
            }
        }, 100);
    }

    function reset() {
        treeData = [];
        selectedNode = null;
        highlightText = null;
        matchKeywordLower = null;
        for (const k in expandedState) delete expandedState[k];
        const container = document.getElementById(containerId);
        if (container) container.innerHTML = '';
    }

    function findPath(nodes, oid) {
        for (const n of nodes) {
            if (n.oid === oid) return [n];
            if (n.children && n.children.length) {
                const sub = findPath(n.children, oid);
                if (sub) return [n].concat(sub);
            }
        }
        return null;
    }

    function getSelected() { return selectedNode; }

    return {
        load, render, expandAll, collapseAll, expandToLevel, filterTree,
        locateByOid, getSelected, reset,
        onSelect: (fn) => { onNodeSelect = fn; },
        onContext: (fn) => { onNodeContext = fn; }
    };
})();
