// Export module: CSV and Excel export
const Export = (function () {

    function exportCsv(data, filename) {
        if (!data || !data.rows || data.rows.length === 0) {
            Toast.warning('没有可导出的数据');
            return;
        }
        const columns = data.columns || Object.keys(data.rows[0]);
        const csvRows = [];
        // BOM for Excel UTF-8
        csvRows.push('\uFEFF' + columns.map(c => csvEscape(c)).join(','));
        data.rows.forEach(row => {
            csvRows.push(columns.map(c => csvEscape(row[c])).join(','));
        });
        const csv = csvRows.join('\r\n');
        const blob = new Blob([csv], { type: 'text/csv;charset=utf-8;' });
        downloadBlob(blob, (filename || 'export') + '.csv');
        Toast.success('已导出 ' + data.rows.length + ' 行 CSV');
    }

    function csvEscape(value) {
        if (value == null) return '';
        const s = String(value);
        if (s.includes(',') || s.includes('"') || s.includes('\n') || s.includes('\r')) {
            return '"' + s.replace(/"/g, '""') + '"';
        }
        return s;
    }

    // Excel export: generate a minimal .xls (HTML-based SpreadsheetML) that Excel can open
    function exportExcel(data, filename) {
        if (!data || !data.rows || data.rows.length === 0) {
            Toast.warning('没有可导出的数据');
            return;
        }
        const columns = data.columns || Object.keys(data.rows[0]);
        let html = '<html xmlns:o="urn:schemas-microsoft-com:office:office" xmlns:x="urn:schemas-microsoft-com:office:excel" xmlns="http://www.w3.org/TR/REC-html40">';
        html += '<head><meta charset="UTF-8"><!--[if gte mso 9]><xml><x:ExcelWorkbook><x:ExcelWorksheets><x:ExcelWorksheet><x:Name>Sheet1</x:Name><x:WorksheetOptions><x:DisplayGridlines/></x:WorksheetOptions></x:ExcelWorksheet></x:ExcelWorksheets></x:ExcelWorkbook></xml><![endif]--></head>';
        html += '<body><table border="1"><thead><tr>';
        columns.forEach(c => { html += '<th>' + escapeHtml(c) + '</th>'; });
        html += '</tr></thead><tbody>';
        data.rows.forEach(row => {
            html += '<tr>';
            columns.forEach(c => { html += '<td>' + escapeHtml(row[c]) + '</td>'; });
            html += '</tr>';
        });
        html += '</tbody></table></body></html>';
        const blob = new Blob(['\uFEFF', html], { type: 'application/vnd.ms-excel;charset=utf-8;' });
        downloadBlob(blob, (filename || 'export') + '.xls');
        Toast.success('已导出 ' + data.rows.length + ' 行 Excel');
    }

    function downloadBlob(blob, filename) {
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = filename;
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
        setTimeout(() => URL.revokeObjectURL(url), 1000);
    }

    return { exportCsv, exportExcel };
})();
