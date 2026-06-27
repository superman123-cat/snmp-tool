// Favorites manager (localStorage based)
const Favorites = (function () {
    const KEY = 'snmp_favorites';

    function getAll() {
        try {
            return JSON.parse(localStorage.getItem(KEY) || '[]');
        } catch (e) { return []; }
    }
    function save(list) {
        localStorage.setItem(KEY, JSON.stringify(list));
    }
    function add(node) {
        const list = getAll();
        if (list.some(f => f.oid === node.oid)) return false;
        list.push({ name: node.name, oid: node.oid, type: node.type, moduleName: node.moduleName, addedAt: Date.now() });
        save(list);
        return true;
    }
    function remove(oid) {
        const list = getAll();
        const filtered = list.filter(f => f.oid !== oid);
        save(filtered);
        return filtered.length !== list.length;
    }
    function has(oid) {
        return getAll().some(f => f.oid === oid);
    }
    function toggle(node) {
        if (has(node.oid)) {
            remove(node.oid);
            return false;
        } else {
            add(node);
            return true;
        }
    }
    return { getAll, add, remove, has, toggle };
})();
