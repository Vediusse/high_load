#!/usr/bin/env python3
"""Create styled, ASCII-transportable PDF templates for the portable Postman demo.

Run with Python containing reportlab and pypdf. Normal collection generation only
reads the resulting asset file and has no third-party Python dependencies.
"""
import io
import json
import re
from pathlib import Path

from pypdf import PdfReader, PdfWriter
from pypdf.generic import ArrayObject, NameObject, StreamObject
from reportlab.lib.colors import HexColor, white
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.pdfgen import canvas

ROOT = Path(__file__).resolve().parents[1]
FONT = Path('/System/Library/Fonts/Supplemental/Arial.ttf')
BOLD = FONT.with_name('Arial Bold.ttf')
pdfmetrics.registerFont(TTFont('Document', str(FONT)))
pdfmetrics.registerFont(TTFont('DocumentBold', str(BOLD)))
NAVY = HexColor('#18354a')
GREY = HexColor('#61717d')
LIGHT = HexColor('#eef3f5')
ALPHABET = ''.join(chr(n) for n in range(32, 127)) + ''.join(chr(n) for n in range(0x410, 0x450)) + 'Ёё№«»'


def template(kind):
    buffer = io.BytesIO()
    c = canvas.Canvas(buffer, pagesize=(595, 842), pageCompression=0, invariant=1)
    c.setTitle('Спецификация заказа' if kind == 'specification' else 'Акт расхождений')
    c.setAuthor('Corporate Catering')
    c.setCreator('Order documents')
    for font in ('Document', 'DocumentBold'):
        c.setFont(font, 1)
        c.drawString(-1000, -1000, ALPHABET)
    fields = {}

    def text(x, y, value, size=10, bold=False, color=NAVY):
        c.setFillColor(color)
        c.setFont('DocumentBold' if bold else 'Document', size)
        c.drawString(x, y, value)

    def field(key, x, y, width, size=10):
        token = 'FIELD_' + key
        fields[key] = {'token': token, 'width': width, 'size': size}
        text(x, y, token, size)

    def rule(y):
        c.setStrokeColor(HexColor('#d5dfe5'))
        c.setLineWidth(0.6)
        c.line(44, y, 551, y)

    c.setFillColor(NAVY)
    c.rect(0, 794, 595, 48, fill=1, stroke=0)
    text(44, 814, 'КОРПОРАТИВНОЕ ПИТАНИЕ', 11, True, white)
    title = 'СПЕЦИФИКАЦИЯ К ЗАКАЗУ' if kind == 'specification' else 'АКТ РАСХОЖДЕНИЙ'
    text(44, 755, title, 22, True)
    field('NUMBER', 44, 730, 290, 11)
    field('ISSUED', 405, 730, 146, 10)
    rule(712)
    text(44, 690, 'ИСПОЛНИТЕЛЬ', 8, True, GREY)
    text(44, 671, 'Производственная кухня', 12, True)
    text(44, 648, 'ЗАКАЗЧИК', 8, True, GREY)
    field('ORGANIZATION', 44, 629, 507, 11)
    text(44, 602, 'ТОЧКА ВЫДАЧИ', 8, True, GREY)
    field('POINT', 44, 584, 507)
    field('ADDRESS', 44, 566, 507)
    text(44, 540, 'ПОЛУЧЕНИЕ', 8, True, GREY)
    field('DELIVERY', 44, 522, 245)
    field('CONTACT', 300, 522, 251)
    rule(504)
    text(44, 483, 'СОСТАВ ЗАКАЗА' if kind == 'specification' else 'СВЕРКА ПРИ ПРИЕМКЕ', 10, True)
    c.setFillColor(LIGHT)
    c.rect(44, 445, 507, 26, fill=1, stroke=0)
    if kind == 'specification':
        headers = [(54, 'Блюдо / позиция'), (330, 'Кол-во'), (395, 'Цена, руб.'), (480, 'Сумма, руб.')]
        cells = [('DISH', 54, 260), ('QUANTITY', 337, 44), ('PRICE', 395, 76), ('AMOUNT', 480, 65)]
    else:
        headers = [(54, 'Блюдо / позиция'), (320, 'Заказано'), (400, 'Принято'), (482, 'Разница')]
        cells = [('DISH', 54, 245), ('QUANTITY', 335, 50), ('ACCEPTED', 416, 50), ('DIFFERENCE', 499, 45)]
    for x, value in headers:
        text(x, 454, value, 8, True)
    for key, x, width in cells:
        field(key, x, 420, width, 9)
    rule(402)
    if kind == 'specification':
        text(355, 370, 'ИТОГО К ВЫДАЧЕ', 9, True, GREY)
        field('TOTAL', 355, 343, 196, 20)
        text(44, 296, 'Комментарий к заказу', 9, True, GREY)
        field('COMMENT', 44, 277, 507, 9)
        text(44, 228, 'Заказ подтверждён кухней. Состав и цена зафиксированы.', 10)
        text(44, 210, 'Изменения согласовываются до начала приготовления.', 9, color=GREY)
    else:
        text(44, 371, 'Основание / замечания', 9, True, GREY)
        field('REMARK', 44, 350, 507, 10)
        text(44, 302, 'Подписи подтверждают зафиксированные сведения о приемке.', 9, color=GREY)
        text(44, 283, 'Состав заказа и его стоимость приведены в спецификации.', 9, color=GREY)
    rule(174)
    text(44, 150, 'От производственной кухни', 9, True)
    text(330, 150, 'От заказчика', 9, True)
    c.line(44, 113, 265, 113)
    c.line(330, 113, 551, 113)
    text(44, 98, 'подпись / Ф.И.О.', 8, color=GREY)
    text(330, 98, 'подпись / Ф.И.О.', 8, color=GREY)
    field('ORDER_ID', 44, 53, 507, 8)
    text(44, 36, 'Документ к заказу корпоративного питания', 8, color=GREY)
    text(519, 36, '1 / 1', 8, color=GREY)
    c.showPage()
    c.save()
    reader = PdfReader(io.BytesIO(buffer.getvalue()))
    glyphs = {}
    for font in reader.pages[0]['/Resources']['/Font'].values():
        font = font.get_object()
        if str(font.get('/BaseFont', '')).endswith('ArialMT'):
            cmap = font['/ToUnicode'].get_data().decode('ascii')
            for block in re.findall(r'beginbfchar(.*?)endbfchar', cmap, flags=re.S):
                for code, unicode in re.findall(r'<([0-9A-Fa-f]+)>\s*<([0-9A-Fa-f]+)>', block):
                    glyphs[chr(int(unicode, 16))] = int(code, 16)
    assert all(char in glyphs for char in 'Спецификация заказа')
    writer = PdfWriter()
    writer.clone_document_from_reader(reader)
    for obj in writer._objects:
        if not isinstance(obj, StreamObject):
            continue
        if b'FIELD_' in obj.get_data():
            obj._data = obj.get_data()
            for key in ('/Filter', '/DecodeParms'):
                if key in obj:
                    del obj[key]
        else:
            original_filter = obj.get('/Filter')
            obj._data = obj._data.hex().encode('ascii') + b'>'
            filters = [NameObject('/ASCIIHexDecode')]
            if original_filter:
                filters.extend(original_filter if isinstance(original_filter, ArrayObject) else [original_filter])
            obj[NameObject('/Filter')] = ArrayObject(filters)
    output = io.BytesIO()
    writer.write(output)
    data = output.getvalue().replace(b'%\xe2\xe3\xcf\xd3', b'%PDF!')
    data.decode('ascii')
    widths = {char: pdfmetrics.stringWidth(char, 'Document', 1000) for char in glyphs}
    return {'kind': kind, 'template': data.decode('ascii'), 'glyphs': glyphs, 'widths': widths, 'fields': fields}


def compact():
    content = ('0.09 0.21 0.29 rg 42 725 510 3 re f\n'
               'BT /F1 18 Tf 42 752 Td (ORDER SUMMARY) Tj ET\n')
    for y, key, label in [(696, 'NUMBER', 'Document: '), (672, 'ORGANIZATION', 'Customer: '),
                          (648, 'DELIVERY', 'Delivery: '), (620, 'ORDER_ID', ''),
                          (570, 'DISH', 'Meal: '), (540, 'QUANTITY', 'Quantity: '),
                          (510, 'PRICE', 'Unit price, RUB: '), (470, 'TOTAL', 'TOTAL: ')]:
        content += f'BT /F1 11 Tf 42 {y} Td ({label}FIELD_{key}) Tj ET\n'
    objects = ['<< /Type /Catalog /Pages 2 0 R >>', '<< /Type /Pages /Kids [3 0 R] /Count 1 >>',
               '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>',
               '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>',
               f'<< /Length {len(content)} >>\nstream\n{content}endstream']
    data = '%PDF-1.4\n'
    offsets = [0]
    for n, obj in enumerate(objects, 1):
        offsets.append(len(data))
        data += f'{n} 0 obj\n{obj}\nendobj\n'
    start = len(data)
    data += 'xref\n0 6\n0000000000 65535 f \n'
    data += ''.join(f'{v:010} 00000 n \n' for v in offsets[1:])
    data += f'trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n{start}\n%%EOF\n'
    return {'kind': 'compact', 'template': data, 'glyphs': {}, 'widths': {}, 'fields': {}}


assets = {'demoPdfSpecification': template('specification'), 'demoPdfAct': template('act'), 'demoPdfSmall': compact()}
assets['demoPdfLarge'] = {'alias': 'demoPdfSpecification'}
path = ROOT / 'postman/pdf-assets.json'
path.write_text(json.dumps(assets, ensure_ascii=False, indent=2) + '\n')
print('PDF templates saved: ' + str(path))
