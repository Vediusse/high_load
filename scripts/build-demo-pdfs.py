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
    field('TABLE', 54, 425, 507, 9)
    rule(264)
    if kind == 'specification':
        field('TOTAL_LABEL', 355, 240, 196, 9)
        field('TOTAL', 355, 214, 196, 20)
        text(44, 184, 'Комментарий к заказу', 9, True, GREY)
        field('COMMENT', 44, 167, 507, 9)
        field('CONTINUATION', 44, 141, 507, 9)
    else:
        text(44, 240, 'Основание / замечания', 9, True, GREY)
        field('REMARK', 44, 219, 507, 9)
        text(44, 184, 'Подписи подтверждают сведения о приемке на данном листе.', 9, color=GREY)
        text(44, 167, 'Состав и стоимость заказа приведены в спецификации.', 9, color=GREY)
        field('CONTINUATION', 44, 141, 507, 9)
    rule(124)
    text(44, 105, 'От производственной кухни', 9, True)
    text(330, 105, 'От заказчика', 9, True)
    c.line(44, 77, 265, 77)
    c.line(330, 77, 551, 77)
    text(44, 62, 'подпись / Ф.И.О.', 8, color=GREY)
    text(330, 62, 'подпись / Ф.И.О.', 8, color=GREY)
    field('ORDER_ID', 44, 34, 507, 8)
    text(44, 17, 'Документ к заказу корпоративного питания', 8, color=GREY)
    field('PAGE', 500, 17, 51, 8)
    c.showPage()
    c.save()
    reader = PdfReader(io.BytesIO(buffer.getvalue()))
    glyphs = {}
    resource = None
    for name, font in reader.pages[0]['/Resources']['/Font'].items():
        font = font.get_object()
        if str(font.get('/BaseFont', '')).endswith('ArialMT'):
            resource = str(name)
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
            obj._data = re.sub(rb'BT[^\n]*\(FIELD_TABLE\)[^\n]*ET', b'%TABLE_ROWS%', obj.get_data())
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
    page = writer.pages[0]
    return {'kind': kind, 'template': data.decode('ascii'), 'glyphs': glyphs, 'widths': widths, 'fields': fields,
            'pageObject': page.indirect_reference.idnum, 'contentObject': page.raw_get('/Contents').idnum,
            'pagesObject': page.raw_get('/Parent').idnum,
            'table': {'font': resource, 'size': 9, 'top': 425, 'bottom': 280, 'lineHeight': 12,
                      'columns': [{'key': key, 'x': x, 'width': width} for key, x, width in cells]}}


def compact():
    content = ('0.09 0.21 0.29 rg 42 725 510 3 re f\n'
               'BT /F1 18 Tf 42 752 Td (ORDER SUMMARY) Tj ET\n')
    for y, key, label in [(696, 'NUMBER', 'Document: '), (672, 'ORGANIZATION', 'Customer: '),
                          (648, 'DELIVERY', 'Delivery: '), (620, 'ORDER_ID', ''),
                          (150, 'TOTAL_LABEL', ''), (125, 'TOTAL', ''), (40, 'PAGE', '')]:
        content += f'BT /F1 11 Tf 42 {y} Td ({label}FIELD_{key}) Tj ET\n'
    content += 'BT /F1 9 Tf 42 595 Td (Meal / Quantity / Unit price / Amount, RUB) Tj ET\n%TABLE_ROWS%\n'
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
    return {'kind': 'compact', 'template': data, 'glyphs': {}, 'widths': {}, 'fields': {},
            'pageObject': 3, 'contentObject': 5, 'pagesObject': 2,
            'table': {'font': '/F1', 'size': 9, 'top': 570, 'bottom': 190, 'lineHeight': 12,
                      'columns': [{'key': key, 'x': x, 'width': width} for key, x, width in
                                  [('DISH', 42, 270), ('QUANTITY', 338, 44), ('PRICE', 400, 70), ('AMOUNT', 480, 70)]]}}


assets = {'demoPdfSpecification': template('specification'), 'demoPdfAct': template('act'), 'demoPdfSmall': compact()}
assets['demoPdfLarge'] = {'alias': 'demoPdfSpecification'}
path = ROOT / 'postman/pdf-assets.json'
path.write_text(json.dumps(assets, ensure_ascii=False, indent=2) + '\n')
print('PDF templates saved: ' + str(path))
