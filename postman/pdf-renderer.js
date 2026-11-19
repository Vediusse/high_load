// The same ASCII-safe PDF renderer runs in Postman and in the fixture builder.
// Only page/content objects are duplicated; embedded fonts are shared by all pages.
function renderOrderPdf(asset, context) {
    const order = context.order;
    if (!order || !order.id || !Array.isArray(order.lines) || !order.lines.length) {
        throw new Error('Для PDF нужны сохранённые позиции заказа. Выполните подготовку и получение заказа.');
    }
    const money = value => Number(value).toFixed(2).replace('.', ',');
    const date = value => new Date(value).toLocaleString('ru-RU', {timeZone: 'Europe/Moscow', hour12: false});
    const latin = ['A','B','V','G','D','E','Zh','Z','I','Y','K','L','M','N','O','P','R','S','T','U','F','Kh','Ts','Ch','Sh','Sch','','Y','','E','Yu','Ya'];
    function normalized(value) {
        let text = String(value === undefined || value === null ? '' : value).replace(/[\r\n\t]/g, ' ');
        if (asset.kind === 'compact') {
            text = text.replace(/[А-Яа-яЁё№«»]/g, char => {
                if (char === 'Ё' || char === 'ё') return char === 'Ё' ? 'Yo' : 'yo';
                if (char === '№') return 'No';
                if (char === '«' || char === '»') return '"';
                const result = latin[char.toUpperCase().charCodeAt(0) - 1040] || '';
                return char === char.toUpperCase() ? result : result.toLowerCase();
            }).replace(/[^\x20-\x7e]/g, ' ');
        }
        return text;
    }
    const width = (text, size) => Array.from(text).reduce((sum, c) => sum + (asset.widths[c] || 600), 0) * size / 1000;
    function literal(value, field) {
        let text = normalized(value);
        if (field && width(text, field.size) > field.width) {
            while (text && width(text + '...', field.size) > field.width) text = text.slice(0, -1);
            text += '...';
        }
        return Array.from(text).map(char => {
            const code = asset.kind === 'compact' ? char.charCodeAt(0) : (asset.glyphs[char] === undefined ? 63 : asset.glyphs[char]);
            if (code < 32 || code > 126) return '\\' + code.toString(8).padStart(3, '0');
            return [40, 41, 92].includes(code) ? '\\' + String.fromCharCode(code) : String.fromCharCode(code);
        }).join('');
    }
    function wrap(value, maxWidth) {
        const result = [];
        let row = '';
        for (const word of normalized(value).split(/\s+/).filter(Boolean)) {
            if (row && width(row + ' ' + word, asset.table.size) <= maxWidth) {
                row += ' ' + word;
                continue;
            }
            if (row) result.push(row);
            row = '';
            for (const char of word) {
                if (row && width(row + char, asset.table.size) > maxWidth) {
                    result.push(row);
                    row = '';
                }
                row += char;
            }
        }
        if (row || !result.length) result.push(row);
        return result;
    }
    const quantities = context.receivedQuantities || {};
    const received = order.lines.map((line, index) => {
        const explicit = quantities[line.id] === undefined ? quantities[line.dishId] : quantities[line.id];
        const value = explicit === undefined
            ? (index === 0 && context.acceptedQuantity !== undefined ? context.acceptedQuantity : line.quantity)
            : explicit;
        const count = Number(value);
        if (!Number.isInteger(count) || count < 0 || count > line.quantity) {
            throw new Error('Некорректное принятое количество для позиции ' + (index + 1));
        }
        return count;
    });
    const missing = order.lines.reduce((sum, line, index) => sum + line.quantity - received[index], 0);
    const values = {
        NUMBER: '№ ' + (asset.kind === 'act' ? 'АКТ-' : 'ЗК-') + order.id.slice(0, 8).toUpperCase()
            + (context.copy > 1 ? ' / экз. ' + context.copy : ''),
        ISSUED: date(order.createdAt), ORGANIZATION: context.organization.name,
        POINT: context.point.name, ADDRESS: context.point.address,
        DELIVERY: date(order.requestedDeliveryAt) + ' (МСК)',
        CONTACT: context.point.contactName + ' / ' + context.point.contactPhone,
        TOTAL: money(order.totalAmount) + ' руб.',
        COMMENT: order.comment || 'Без дополнительных пожеланий',
        ORDER_ID: 'Заказ: ' + order.id,
        REMARK: missing ? 'Недопоставка: ' + missing + ' порц. Требуется согласование с кухней.' : 'При приемке расхождений не выявлено.'
    };
    const table = asset.table;
    const pages = [[]];
    let y = table.top;
    function nextPage() { pages.push([]); y = table.top; }
    order.lines.forEach((line, index) => {
        const names = wrap(line.dishNameSnapshot, table.columns[0].width);
        let offset = 0;
        while (offset < names.length) {
            let capacity = Math.floor((y - table.bottom - 14) / table.lineHeight);
            if (capacity < 1) { nextPage(); capacity = Math.floor((y - table.bottom - 14) / table.lineHeight); }
            const part = names.slice(offset, offset + capacity);
            pages[pages.length - 1].push({line, index, names: part, first: offset === 0, y});
            y -= Math.max(26, part.length * table.lineHeight + 14);
            offset += part.length;
            if (offset < names.length) nextPage();
        }
    });
    function draw(value, x, baseline, field) {
        return 'BT ' + table.font + ' ' + table.size + ' Tf 1 0 0 1 ' + x + ' ' + baseline + ' Tm (' + literal(value, field) + ') Tj ET\n';
    }
    function rows(items) {
        let commands = '0.09 0.21 0.29 rg\n';
        for (const item of items) {
            const line = item.line;
            const row = {QUANTITY: line.quantity, PRICE: money(line.unitPriceSnapshot), AMOUNT: money(line.lineAmount),
                ACCEPTED: received[item.index], DIFFERENCE: received[item.index] - line.quantity};
            item.names.forEach((name, index) => { commands += draw(name, table.columns[0].x, item.y - index * table.lineHeight); });
            if (item.first) {
                for (const column of table.columns.slice(1)) {
                    commands += draw(row[column.key], column.x, item.y, {width: column.width, size: table.size});
                }
            }
        }
        return commands;
    }
    const objects = [];
    const expression = /(\d+) 0 obj\r?\n([\s\S]*?)\r?\nendobj/g;
    let match;
    const stamp = new Date(order.createdAt).toISOString().replace(/[-:T]/g, '').slice(0, 14) + 'Z';
    while ((match = expression.exec(asset.template)) !== null) {
        objects.push({id: Number(match[1]), body: match[2].replace(/\/(CreationDate|ModDate)\s*\([^)]*\)/g,
            (value, key) => '/' + key + ' (D:' + stamp + ')')});
    }
    const byId = new Map(objects.map(object => [object.id, object]));
    const pageTemplate = byId.get(asset.pageObject).body;
    const contentTemplate = byId.get(asset.contentObject).body;
    let nextId = Math.max(...objects.map(object => object.id)) + 1;
    const pageIds = [];
    pages.forEach((items, index) => {
        const last = index === pages.length - 1;
        const pageValues = Object.assign({}, values, {
            PAGE: (index + 1) + ' / ' + pages.length,
            TOTAL_LABEL: last ? 'ИТОГО ПО ЗАКАЗУ' : 'ИТОГ НА ПОСЛЕДНЕМ ЛИСТЕ',
            TOTAL: last ? values.TOTAL : '',
            CONTINUATION: last ? 'Состав и цена заказа зафиксированы.' : 'Продолжение таблицы на следующем листе.'
        });
        let body = contentTemplate.replace('%TABLE_ROWS%', rows(items));
        body = body.replace(/FIELD_([A-Z_]+)/g, (token, key) => literal(pageValues[key], asset.fields[key]));
        const stream = body.match(/stream\r?\n([\s\S]*?)endstream/);
        if (!stream) throw new Error('Поврежден PDF-шаблон');
        body = body.replace(/\/Length\s+\d+/, '/Length ' + stream[1].length);
        const contentId = index === 0 ? asset.contentObject : nextId++;
        const pageId = index === 0 ? asset.pageObject : nextId++;
        if (index === 0) byId.get(contentId).body = body;
        else {
            objects.push({id: contentId, body});
            objects.push({id: pageId, body: pageTemplate.replace(/\/Contents\s+\d+ 0 R/, '/Contents ' + contentId + ' 0 R')});
        }
        pageIds.push(pageId);
    });
    const pageTree = byId.get(asset.pagesObject);
    pageTree.body = pageTree.body.replace(/\/Count\s+\d+/, '/Count ' + pages.length)
        .replace(/\/Kids\s*\[[\s\S]*?\]/, '/Kids [ ' + pageIds.map(id => id + ' 0 R').join(' ') + ' ]');
    let result = '%PDF-1.4\n%Document\n';
    const offsets = {};
    for (const object of objects) {
        offsets[object.id] = result.length;
        result += object.id + ' 0 obj\n' + object.body + '\nendobj\n';
    }
    const count = nextId, start = result.length;
    result += 'xref\n0 ' + count + '\n0000000000 65535 f \n';
    for (let i = 1; i < count; i++) result += (offsets[i] || 0).toString().padStart(10, '0') + ' ' + (offsets[i] ? '00000 n ' : '65535 f ') + '\n';
    const trailer = asset.template.match(/trailer\s*([\s\S]*?)startxref/)[1].replace(/\/Size\s+\d+/, '/Size ' + count);
    return result + 'trailer\n' + trailer.trim() + '\nstartxref\n' + start + '\n%%EOF\n';
}
