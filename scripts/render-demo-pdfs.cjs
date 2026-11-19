#!/usr/bin/env node
// Regenerate the checked-in examples with the same renderer used by Postman.
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const root = path.resolve(__dirname, '..');
const assets = JSON.parse(fs.readFileSync(path.join(root, 'postman/pdf-assets.json'), 'utf8'));
const renderer = fs.readFileSync(path.join(root, 'postman/pdf-renderer.js'), 'utf8');
const context = {
    organization: {name: 'ООО «Северный офис»'},
    point: {
        name: 'Офис на Кронверкском',
        address: 'Санкт-Петербург, Кронверкский проспект, 49',
        contactName: 'Анна Смирнова', contactPhone: '+79990000111'
    },
    order: {
        id: '456d124a-7380-4b97-8fc0-39498e2f5462',
        createdAt: '2026-11-20T08:30:00Z', requestedDeliveryAt: '2026-11-27T09:00:00Z',
        comment: 'Комплексный обед. Приборы и упаковка включены.', totalAmount: 840,
        lines: [
            {dishNameSnapshot: 'Куриное филе с гречкой', quantity: 2, unitPriceSnapshot: 250, lineAmount: 500},
            {dishNameSnapshot: 'Борщ со сметаной', quantity: 1, unitPriceSnapshot: 160, lineAmount: 160},
            {dishNameSnapshot: 'Компот из сухофруктов', quantity: 2, unitPriceSnapshot: 90, lineAmount: 180}
        ]
    },
    acceptedQuantity: 1
};
const examples = {
    'specification.pdf': 'demoPdfSpecification',
    'discrepancy-act.pdf': 'demoPdfAct',
    'order-summary.pdf': 'demoPdfSmall',
    'large-specification.pdf': 'demoPdfLarge'
};
for (const [name, key] of Object.entries(examples)) {
    const descriptor = assets[key];
    const asset = descriptor.alias ? assets[descriptor.alias] : descriptor;
    const pdf = vm.runInNewContext(renderer + '\nrenderOrderPdf(asset, context);', {asset, context});
    if (/[^\x00-\x7f]/.test(pdf)) throw new Error('PDF must be ASCII-safe for Postman raw multipart');
    fs.writeFileSync(path.join(root, 'postman/fixtures', name), Buffer.from(pdf, 'ascii'));
    console.log(name + ': ' + pdf.length + ' bytes');
}
