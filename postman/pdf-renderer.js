// Portable renderer for the embedded ReportLab templates. All PDF bytes are ASCII.
// File Service receives these exact bytes as an ordinary multipart upload.
function renderOrderPdf(asset, context) {
    const order = context.order;
    if (!order || !order.id || !order.lines || order.lines.length !== 1) {
        throw new Error('Для PDF нужны данные заказа с одной позицией из подготовки сценария.');
    }
    const line = order.lines[0];
    const money = value => Number(value).toFixed(2).replace('.', ',');
    const date = value => new Date(value).toLocaleString('ru-RU', {timeZone:'Europe/Moscow', hour12:false});
    const accepted = context.acceptedQuantity === undefined ? line.quantity : Number(context.acceptedQuantity);
    if (!Number.isInteger(accepted) || accepted < 0 || accepted > line.quantity) throw new Error('Некорректное принятое количество для акта.');
    const values = {
        NUMBER: '№ ' + (asset.kind === 'act' ? 'АКТ-' : 'ЗК-') + order.id.slice(0,8).toUpperCase(),
        ISSUED: date(order.createdAt), ORGANIZATION: context.organization.name,
        POINT: context.point.name, ADDRESS: context.point.address,
        DELIVERY: date(order.requestedDeliveryAt) + ' (МСК)',
        CONTACT: context.point.contactName + ' / ' + context.point.contactPhone,
        DISH: line.dishNameSnapshot, QUANTITY: String(line.quantity), PRICE: money(line.unitPriceSnapshot),
        AMOUNT: money(line.lineAmount), TOTAL: money(order.totalAmount) + ' руб.',
        COMMENT: order.comment || 'Без дополнительных пожеланий',
        ORDER_ID: 'Заказ: ' + order.id, ACCEPTED: String(accepted), DIFFERENCE: String(accepted-line.quantity),
        REMARK: accepted === line.quantity ? 'При приемке расхождений не выявлено.' : 'Недопоставка: ' + (line.quantity-accepted) + ' порц. Требуется согласование с кухней.'
    };
    const latin = ['A','B','V','G','D','E','Zh','Z','I','Y','K','L','M','N','O','P','R','S','T','U','F','Kh','Ts','Ch','Sh','Sch','','Y','','E','Yu','Ya'];
    function ascii(text) {
        return text.replace(/[А-Яа-яЁё№«»]/g, char => {
            if (char === 'Ё' || char === 'ё') return char === 'Ё' ? 'Yo' : 'yo';
            if (char === '№') return 'No';
            if (char === '«' || char === '»') return '"';
            const upper = char.toUpperCase(), result = latin[upper.charCodeAt(0)-1040] || '';
            return char === upper ? result : result.toLowerCase();
        }).replace(/[^\x20-\x7e]/g, ' ');
    }
    function literal(value, field) {
        let text = String(value || '').replace(/[\r\n\t]/g,' ');
        if (asset.kind === 'compact') text = ascii(text);
        if (field) {
            const width = s => Array.from(s).reduce((n,c)=>n+(asset.widths[c] || 600),0)*field.size/1000;
            if (width(text)>field.width) {
                while (text && width(text+'...')>field.width) text=text.slice(0,-1);
                text+='...';
            }
        }
        return Array.from(text).map(char => {
            const code = asset.kind === 'compact' ? char.charCodeAt(0) : (asset.glyphs[char] === undefined ? 63 : asset.glyphs[char]);
            if (code<32 || code>126) return '\\'+code.toString(8).padStart(3,'0');
            return [40,41,92].includes(code) ? '\\'+String.fromCharCode(code) : String.fromCharCode(code);
        }).join('');
    }
    const objects=[];
    const expression=/(\d+) 0 obj\r?\n([\s\S]*?)\r?\nendobj/g;
    let match;
    while ((match=expression.exec(asset.template)) !== null) {
        let body=match[2];
        const stamp = new Date(order.createdAt).toISOString().replace(/[-:T]/g,'').slice(0,14)+'Z';
        body=body.replace(/\/(CreationDate|ModDate)\s*\([^)]*\)/g, (value,key)=>'/'+key+' (D:'+stamp+')');
        if (body.includes('FIELD_')) {
            for (const [key,value] of Object.entries(values)) {
                body=body.split('FIELD_'+key).join(literal(value,asset.fields[key]));
            }
            const stream=body.match(/stream\r?\n([\s\S]*?)endstream/);
            if (!stream) throw new Error('Поврежден PDF-шаблон');
            body=body.replace(/\/Length\s+\d+/, '/Length '+stream[1].length);
        }
        objects.push({id:Number(match[1]),body});
    }
    let result='%PDF-1.4\n%Document\n';
    const offsets={};
    for (const object of objects) {
        offsets[object.id]=result.length;
        result+=object.id+' 0 obj\n'+object.body+'\nendobj\n';
    }
    const count=Math.max(...objects.map(o=>o.id))+1, start=result.length;
    result+='xref\n0 '+count+'\n0000000000 65535 f \n';
    for (let i=1;i<count;i++) result+=(offsets[i] || 0).toString().padStart(10,'0')+' '+(offsets[i] ? '00000 n ' : '65535 f ')+'\n';
    const trailer=asset.template.match(/trailer\s*([\s\S]*?)startxref/)[1].replace(/\/Size\s+\d+/,'/Size '+count);
    return result+'trailer\n'+trailer.trim()+'\nstartxref\n'+start+'\n%%EOF\n';
}
