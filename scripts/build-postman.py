#!/usr/bin/env python3
"""Build the portable Lab4 collection and public environment template."""
import json
from pathlib import Path
import textwrap
import uuid

ROOT = Path(__file__).resolve().parents[1]
POSTMAN = ROOT / 'postman'
NAMESPACE = uuid.UUID('bd37b377-c538-45bc-9a1b-9ec82e115c59')


def pdf(title, padding=0):
    content = ('BT /F1 20 Tf 50 740 Td (' + title + ') Tj '
               '/F1 12 Tf 0 -35 Td (Lab 4 - local S3 demonstration) Tj '
               '0 -25 Td (This is a generated test document.) Tj '
               '0 -25 Td (PostgreSQL stores metadata. S3 stores this PDF.) Tj ET\n')
    objects = [
        '<< /Type /Catalog /Pages 2 0 R >>',
        '<< /Type /Pages /Kids [3 0 R] /Count 1 >>',
        '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] '
        '/Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>',
        '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>',
        '<< /Length ' + str(len(content)) + ' >>\nstream\n' + content + 'endstream',
    ]
    result = '%PDF-1.4\n'
    offsets = [0]
    for index, value in enumerate(objects, 1):
        offsets.append(len(result))
        result += str(index) + ' 0 obj\n' + value + '\nendobj\n'
    if padding:
        result += '%' + 'x' * padding + '\n'
    xref = len(result)
    result += 'xref\n0 6\n0000000000 65535 f \n'
    result += ''.join(f'{offset:010} 00000 n \n' for offset in offsets[1:])
    result += f'trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n'
    return result


SMALL = pdf('ORDER SPECIFICATION')
ACT = pdf('DISCREPANCY ACT')
LARGE = pdf('LARGE SPECIFICATION', 5000)
assert len(SMALL) < 2048 < len(LARGE) < 10485760


def lines(source):
    body = textwrap.dedent(source).strip().splitlines()
    return ['(() => {'] + ['  ' + line for line in body] + ['})();']


def visualizer(title):
    return '''
pm.visualizer.set(`<html><style>
body{font:14px system-ui;padding:20px;color:#182638} table{border-collapse:collapse;width:100%}
td,th{border-bottom:1px solid #ddd;padding:10px;text-align:left} th{background:#eef3f9}
</style><h2>''' + title + '''</h2><table><tr><th>Тип</th><th>Текст</th><th>Заказ</th><th>Создано</th><th>Прочитано</th></tr>
{{#each items}}<tr><td>{{type}}</td><td>{{text}}</td><td>{{subjectId}}</td><td>{{createdAt}}</td><td>{{readAt}}</td></tr>{{/each}}
</table></html>`, pm.response.json());
'''


class Folder:
    def __init__(self, prefix, name, description):
        self.prefix = prefix
        self.node = {'name': name, 'description': description, 'item': []}

    def v(self, name):
        return '{{' + self.prefix + '_' + name + '}}'

    def js(self, source):
        return textwrap.dedent(source).replace('$P', self.prefix).strip()

    def add(self, title, method, path, actor=None, body=None, status=200,
            tests='', pre='', description='', admin=False, raw=False):
        index = len(self.node['item']) + 1
        request = {'method': method, 'header': [], 'url': '{{baseUrl}}' + path,
                   'auth': {'type': 'noauth'}, 'description': description}
        if actor:
            request['auth'] = {'type': 'bearer', 'bearer': [
                {'key': 'token', 'value': self.v(actor + 'Token'), 'type': 'string'}]}
        if admin:
            request['url'] = '{{configAdminUrl}}' + path
            request['auth'] = {'type': 'basic', 'basic': [
                {'key': 'username', 'value': '{{configAdminLogin}}', 'type': 'string'},
                {'key': 'password', 'value': '{{configAdminPassword}}', 'type': 'string'}]}
        if body is not None:
            value = body if isinstance(body, str) else json.dumps(body, ensure_ascii=False, indent=2)
            if isinstance(body, dict) and 'expectedVersion' in body:
                version = body['expectedVersion']
                if isinstance(version, str) and version.startswith('{{'):
                    value = value.replace('"' + version + '"', version)
            request['body'] = {'mode': 'raw', 'raw': value,
                               'options': {'raw': {'language': 'text' if raw else 'json'}}}
            if not raw:
                request['header'].append({'key': 'Content-Type', 'value': 'application/json'})
        scripts = []
        if pre:
            scripts.append({'listen': 'prerequest', 'script': {'type': 'text/javascript', 'exec': lines(self.js(pre))}})
        checks = '' if status is None else (
            'const errorHint = pm.response.code >= 400 ? JSON.stringify(pm.response.json()) : "";\n'
            f'pm.test("HTTP {status}", () => pm.expect(pm.response.code, errorHint).to.eql({status}));\n')
        checks += self.js(tests)
        scripts.append({'listen': 'test', 'script': {'type': 'text/javascript', 'exec': lines(checks)}})
        item = {'id': str(uuid.uuid5(NAMESPACE, f'{self.prefix}/{index}/{title}')),
                'name': f'{index:02}. {title}', 'request': request, 'event': scripts}
        self.node['item'].append(item)
        return item

    def save(self, key, field='id'):
        return f'pm.environment.set("$P_{key}", pm.response.json().{field});'

    def login(self, actor):
        supervisor = actor == 'supervisor'
        login = '{{supervisorLogin}}' if supervisor else self.v(actor + 'Login')
        password = '{{supervisorPassword}}' if supervisor else self.v('demoPassword')
        pre = ''
        if supervisor:
            pre = '''
            if (!pm.environment.get('supervisorPassword')) {
                throw new Error('Выберите импортированное Lab4 local environment с supervisorPassword.');
            }
            Object.keys(pm.environment.toObject()).filter(k => k.startsWith('$P_'))
                .forEach(k => pm.environment.set(k, ''));
            const run = Date.now().toString(36) + '-' + pm.variables.replaceIn('{{$guid}}').slice(0, 8);
            pm.environment.set('$P_runId', run);
            pm.environment.set('$P_demoPassword', 'Pm9!' + pm.variables.replaceIn('{{$guid}}'));
            pm.environment.set('$P_future', new Date(Date.now() + 7 * 86400000).toISOString());
            ['client','kitchen','owner','foreign','colleague','observer'].forEach(role =>
                pm.environment.set('$P_' + role + 'Login', 'pm-$P-' + role + '-' + run));
            '''
        self.add('SUPERVISOR — вход / новый прогон' if supervisor else actor.upper() + ' — вход',
                 'POST', '/api/v1/auth/login', body={'login': login, 'password': password}, pre=pre,
                 tests=f'''
                 pm.test('Получен JWT', () => pm.expect(pm.response.json().accessToken).to.be.a('string'));
                 {self.save(actor + 'Token', 'accessToken')}
                 ''', description='Каждый запуск папки начинается здесь. Новые имена/ID, существующие данные не изменяются.' if supervisor else '')

    def user(self, actor, role, organization=False):
        body = {'login': self.v(actor + 'Login'), 'password': self.v('demoPassword'), 'roles': [role]}
        if organization:
            body['organizationId'] = self.v('foreignOrgId' if actor == 'foreign' else 'orgId')
        self.add('SUPERVISOR — создать ' + role + ' (' + actor + ')', 'POST', '/api/v1/users',
                 'supervisor', body, 201, self.save(actor + 'Id'))
        self.login(actor)

    def setup(self):
        self.login('supervisor')
        self.user('client', 'CLIENT_MANAGER')
        self.user('kitchen', 'KITCHEN_MANAGER')
        self.add('CLIENT_MANAGER — создать организацию', 'POST', '/api/v1/organizations', 'client',
                 {'name': 'Postman ' + self.v('runId'), 'phone': '+79990000111'}, 201, self.save('orgId'))
        self.add('CLIENT_MANAGER — создать точку выдачи', 'POST', '/api/v1/organizations/' + self.v('orgId') + '/delivery-points',
                 'client', {'name': 'Postman office', 'address': 'Кронверкский проспект, 49',
                            'contactName': 'Demo', 'contactPhone': '+79990000111'}, 201, self.save('pointId'))
        self.user('owner', 'ORGANIZATION_REPRESENTATIVE', True)
        self.add('Представитель — проверить /me и организацию', 'GET', '/api/v1/me', 'owner', tests='''
        const data = pm.response.json();
        pm.test('Роль и привязка к организации', () => {
            pm.expect(data.roles).to.include('ORGANIZATION_REPRESENTATIVE');
            pm.expect(data.organizationId).to.eql(pm.environment.get('$P_orgId'));
        });
        ''')
        self.add('KITCHEN_MANAGER — категория меню', 'POST', '/api/v1/categories', 'kitchen',
                 {'name': 'Postman ' + self.v('runId')}, 201, self.save('categoryId'))
        self.add('KITCHEN_MANAGER — блюдо за 250', 'POST', '/api/v1/dishes', 'kitchen',
                 self.dish_body(250), 201, self.save('dishId'))

    def dish_body(self, price):
        return {'name': 'Postman meal ' + self.v('runId'), 'description': 'Постоянное меню',
                'currentPrice': price, 'categoryIds': [self.v('categoryId')]}

    def foreign(self):
        self.add('CLIENT_MANAGER — чужая организация', 'POST', '/api/v1/organizations', 'client',
                 {'name': 'Foreign ' + self.v('runId'), 'phone': '+79990000222'}, 201, self.save('foreignOrgId'))
        self.user('foreign', 'ORGANIZATION_REPRESENTATIVE', True)

    def order_checks(self, key, status):
        return f'''
        const data = pm.response.json();
        pm.test('Статус {status}', () => pm.expect(data.status).to.eql('{status}'));
        pm.environment.set('$P_{key}Id', data.id);
        pm.environment.set('$P_{key}Version', data.version);
        '''

    def create_order(self, key='order'):
        self.add('Представитель — новый DRAFT (' + key + ')', 'POST', '/api/v1/orders', 'owner',
                 {'deliveryPointId': self.v('pointId'), 'requestedDeliveryAt': self.v('future'), 'comment': 'Postman demo'},
                 201, self.order_checks(key, 'DRAFT'))

    def order_lines(self, key='order'):
        self.add('Представитель — позиции заказа (' + key + ')', 'PUT', '/api/v1/orders/' + self.v(key + 'Id') + '/lines',
                 'owner', {'expectedVersion': self.v(key + 'Version'), 'lines': [{'dishId': self.v('dishId'), 'quantity': 2}]},
                 tests=self.order_checks(key, 'DRAFT') + '''
                 pm.test('Сумма 500', () => pm.expect(Number(data.totalAmount)).to.eql(500));
                 ''')

    def transition(self, action, status, actor='kitchen', key='order', reason=None):
        body = {'expectedVersion': self.v(key + 'Version')}
        if reason:
            body['reason'] = reason
        self.add(actor.upper() + ' — ' + action + ' → ' + status + ' (' + key + ')', 'POST',
                 '/api/v1/orders/' + self.v(key + 'Id') + '/' + action, actor, body,
                 tests=self.order_checks(key, status))

    def prepared_order(self, confirmed=True):
        self.create_order()
        self.order_lines()
        self.transition('submit', 'SUBMITTED', 'owner')
        if confirmed:
            self.transition('confirm', 'CONFIRMED')

    def poll(self, predicate, label):
        return """
        const data = pm.response.json();
        const ready = (@PREDICATE@);
        const key = '$P_poll_' + pm.info.requestId;
        const start = Number(pm.environment.get(key) || Date.now());
        if (!ready && Date.now() - start < Number(pm.environment.get('pollTimeoutMs') || 60000)) {
            pm.environment.set(key, start);
            pm.execution.setNextRequest(pm.info.requestId);
            setTimeout(() => {}, Number(pm.environment.get('pollIntervalMs') || 500));
        } else {
            pm.environment.unset(key);
            pm.test(@LABEL@, () => pm.expect(ready, 'Асинхронная доставка не завершилась').to.eql(true));
        }
        """.replace('@PREDICATE@', predicate).replace('@LABEL@', json.dumps(label, ensure_ascii=False))

    def notice(self, actor, event, store=False):
        predicate = "data.items.some(n => n.subjectId === pm.environment.get('$P_orderId') && n.type === '" + event + "')"
        tests = self.poll(predicate, 'Доставлено ' + event)
        if store:
            tests += "\nif (ready) pm.environment.set('$P_noticeId', data.items.find(n => n.subjectId === pm.environment.get('$P_orderId') && n.type === '" + event + "').id);"
        self.add(actor.upper() + ' — дождаться ' + event, 'GET', '/api/v1/notifications?size=50', actor,
                 tests=tests + visualizer(event), description='В Runner запрос повторяется до появления события, максимум pollTimeoutMs. При ручном Send повторите запрос, если событие ещё в пути. Visualize показывает ленту.')

    def upload(self, key='file', fixture='demoPdfSmall', relation='SPECIFICATION', status=201, mime='application/pdf', tests=''):
        pre = r"""
        const boundary = 'postman-' + pm.variables.replaceIn('{{$guid}}');
        const content = pm.collectionVariables.get('@FIXTURE@');
        const order = pm.environment.get('$P_orderId');
        const filename = '$P-@KEY@-' + pm.environment.get('$P_runId') + '.pdf';
        const body = '--' + boundary + '\r\nContent-Disposition: form-data; name="orderId"\r\n\r\n' + order + '\r\n'
            + '--' + boundary + '\r\nContent-Disposition: form-data; name="relationType"\r\n\r\n@RELATION@\r\n'
            + '--' + boundary + '\r\nContent-Disposition: form-data; name="file"; filename="' + filename + '"\r\nContent-Type: @MIME@\r\n\r\n'
            + content + '\r\n--' + boundary + '--\r\n';
        pm.variables.set('multipartBody', body);
        pm.request.headers.upsert({key:'Content-Type',value:'multipart/form-data; boundary=' + boundary});
        """.replace('@FIXTURE@', fixture).replace('@KEY@', key).replace('@RELATION@', relation).replace('@MIME@', mime)
        if status == 201:
            tests = self.save(key + 'Id') + '\n' + self.save(key + 'Checksum', 'checksum') + '''
            pm.test('Только метаданные, без storageKey', () => {
                pm.expect(pm.response.json().status).to.eql('ACTIVE');
                pm.expect(pm.response.json()).not.to.have.property('storageKey');
            });
            ''' + tests
        self.add('Представитель — загрузить ' + key + ' (' + relation + ')', 'POST', '/api/v1/files', 'owner',
                 '{{multipartBody}}', status, tests, pre, raw=True,
                 description='Настоящий multipart с готовым PDF внутри коллекции. Файлы с диска и настройка Working directory не нужны. PDF также находится в postman/fixtures; запрос формирует тело в Pre-request Script.')

    def download(self, key='file', actor='owner', status=200):
        tests = '' if status != 200 else '''
        const crypto = require('crypto-js');
        pm.test('PDF и SHA-256 совпадают', () => {
            pm.expect(pm.response.headers.get('Content-Type')).to.include('application/pdf');
            pm.expect(pm.response.headers.get('Content-Disposition')).to.include('attachment');
            pm.expect(pm.response.text().startsWith('%PDF-')).to.eql(true);
            pm.expect(crypto.SHA256(pm.response.text()).toString()).to.eql(pm.environment.get('$P_''' + key + '''Checksum'));
        });
        '''
        self.add(actor.upper() + ' — скачать ' + key + (' / Send and Download' if status == 200 else ''),
                 'GET', '/api/v1/files/' + self.v(key + 'Id'), actor, status=status, tests=tests,
                 description='После Run откройте запрос и нажмите Send and Download либо Save response → Save to a file. Для повторного входа используйте OWNER — вход в этой же папке. Основная спецификация и акт остаются ACTIVE.')


business = Folder('biz', '01 — Бизнес: пользователи → организация → меню → заказ → кухня',
                  'Самостоятельный повторяемый сценарий. Создаёт отдельные роли, организацию и меню, проводит заказ до COMPLETED, показывает снимок цены, историю, отклонение, отмену и удаление черновика. Все запросы видимы; чужие данные не меняются.')
business.setup()
business.add('Представитель — своя организация', 'GET', '/api/v1/organizations/' + business.v('orgId'), 'owner')
business.prepared_order(False)
business.add('Кухня — новая цена блюда 350', 'PUT', '/api/v1/dishes/' + business.v('dishId'), 'kitchen', business.dish_body(350))
business.add('Представитель — снимок цены заказа не изменился', 'GET', '/api/v1/orders/' + business.v('orderId'), 'owner', tests='''
const data = pm.response.json();
pm.test('Зафиксированы 250 × 2 = 500, хотя в каталоге уже 350', () => {
    pm.expect(Number(data.totalAmount)).to.eql(500);
    pm.expect(Number(data.lines[0].unitPriceSnapshot)).to.eql(250);
});
''')
business.add('Кухня — список SUBMITTED своей организации', 'GET', '/api/v1/orders?status=SUBMITTED&organizationId=' + business.v('orgId'), 'kitchen', tests='''
pm.test('Новый заказ виден кухне', () => pm.expect(pm.response.json().items.map(o => o.id)).to.include(pm.environment.get('$P_orderId')));
''')
for action, status in [('confirm', 'CONFIRMED'), ('start-cooking', 'IN_COOKING'), ('mark-ready', 'READY'), ('complete', 'COMPLETED')]:
    business.transition(action, status)
business.add('Представитель — история и аудит исполнителей', 'GET', '/api/v1/orders/' + business.v('orderId') + '/history?size=50', 'owner', tests='''
const items = pm.response.json().items;
pm.test('Вся цепочка и changedBy', () => {
    pm.expect(items.map(i => i.toStatus)).to.include.members(['SUBMITTED','CONFIRMED','IN_COOKING','READY','COMPLETED']);
    items.forEach(i => pm.expect(i.changedBy).to.be.a('string'));
});
''')
business.add('Кухня — вернуть цену 250 для следующих заказов', 'PUT', '/api/v1/dishes/' + business.v('dishId'), 'kitchen', business.dish_body(250))
for key, action, status in [('rejected', 'reject', 'REJECTED'), ('cancelled', 'cancel', 'CANCELLED')]:
    business.create_order(key)
    business.order_lines(key)
    business.transition('submit', 'SUBMITTED', 'owner', key)
    business.transition(action, status, 'kitchen' if action == 'reject' else 'owner', key, 'Postman: демонстрация причины')
business.create_order('draft')
details = {'deliveryPointId': business.v('pointId'), 'requestedDeliveryAt': business.v('future'),
           'comment': 'Исправленный черновик', 'expectedVersion': business.v('draftVersion')}
business.add('Представитель — изменить черновик', 'PUT', '/api/v1/orders/' + business.v('draftId') + '/details', 'owner', details,
             tests=business.order_checks('draft', 'DRAFT'))
business.add('Представитель — старая version → 409', 'PUT', '/api/v1/orders/' + business.v('draftId') + '/details',
             'owner', {**details, 'expectedVersion': 0}, 409)
business.add('Представитель — удалить пустой черновик', 'DELETE', '/api/v1/orders/' + business.v('draftId'), 'owner', status=204)
business.add('Удалённый черновик → 404', 'GET', '/api/v1/orders/' + business.v('draftId'), 'owner', status=404)
business.add('SUPERVISOR без бизнес-роли → 403', 'GET', '/api/v1/orders/' + business.v('orderId'), 'supervisor', status=403)
business.add('Представитель — итоговый выданный заказ', 'GET', '/api/v1/orders/' + business.v('orderId'), 'owner',
             tests=business.order_checks('order', 'COMPLETED'))

files = Folder('files', '02 — Файлы: S3, скачать PDF, доступ, акт, удаление',
               'Независимый сценарий с собственными пользователями и подтверждённым заказом. PDF встроен; никакие пути к файлам выбирать не нужно. Спецификация и акт остаются доступными после Run; удаляется отдельный тестовый экземпляр.')
files.setup()
files.prepared_order()
files.upload()
files.download()
files.add('Метаданные файла в заказе', 'GET', '/api/v1/orders/' + files.v('orderId') + '/files', 'owner', tests='''
pm.test('Есть спецификация', () => pm.expect(pm.response.json().items.map(f => f.id)).to.include(pm.environment.get('$P_fileId')));
''')
files.add('Дождаться ссылки из Kafka в Order', 'GET', '/api/v1/orders/' + files.v('orderId') + '/file-references', 'owner',
          tests=files.poll("data.items.some(f => f.fileId === pm.environment.get('$P_fileId'))", 'Событийная ссылка создана'))
files.user('colleague', 'ORGANIZATION_REPRESENTATIVE', True)
files.download(actor='colleague')
files.add('Коллега не может удалить чужой файл → 404', 'DELETE', '/api/v1/files/' + files.v('fileId'), 'colleague', status=404)
files.foreign()
files.download(actor='foreign', status=404)
files.download(actor='supervisor', status=403)
files.upload('badMime', mime='application/octet-stream', status=415)
files.upload('badSignature', fixture='demoInvalidPdf', status=422)
files.upload('earlyAct', fixture='demoPdfAct', relation='DISCREPANCY_ACT', status=422)
files.upload('temporary')
for title in ['Удалить только временный экземпляр', 'Повтор удаления — снова 204']:
    files.add(title, 'DELETE', '/api/v1/files/' + files.v('temporaryId'), 'owner', status=204)
files.download('temporary', status=404)
files.transition('start-cooking', 'IN_COOKING')
files.transition('mark-ready', 'READY')
files.upload('act', fixture='demoPdfAct', relation='DISCREPANCY_ACT')
files.download('act')
files.add('Дождаться двух активных ссылок и удаления временной', 'GET', '/api/v1/orders/' + files.v('orderId') + '/file-references', 'owner',
          tests=files.poll("data.items.some(f => f.fileId === pm.environment.get('$P_fileId')) && data.items.some(f => f.fileId === pm.environment.get('$P_actId')) && !data.items.some(f => f.fileId === pm.environment.get('$P_temporaryId'))", 'Order догнал file-events'))
files.download()

notifications = Folder('notify', '03 — Уведомления: Kafka → лента → прочитано → защита',
                       'Самостоятельный сценарий. Создаёт заказ и показывает уведомления кухни/представителя, чтение без дублей и запрет чужого ID. Для таблицы откройте Response → Visualize. Ожидание Kafka ограничено по времени.')
notifications.setup()
notifications.foreign()
notifications.prepared_order(False)
notifications.notice('kitchen', 'ORDER_SUBMITTED')
notifications.transition('confirm', 'CONFIRMED')
notifications.notice('owner', 'ORDER_CONFIRMED', True)
notifications.add('Представитель — отметить уведомление прочитанным', 'POST', '/api/v1/notifications/' + notifications.v('noticeId') + '/read', 'owner', tests='''
pm.test('Есть readAt', () => pm.expect(pm.response.json().readAt).to.be.a('string'));
pm.environment.set('$P_readAt', pm.response.json().readAt);
''')
notifications.add('Повтор прочтения — прежний readAt', 'POST', '/api/v1/notifications/' + notifications.v('noticeId') + '/read', 'owner', tests='''
pm.test('Отметка идемпотентна', () => pm.expect(pm.response.json().readAt).to.eql(pm.environment.get('$P_readAt')));
''')
notifications.add('Чужой пользователь не читает наш ID → 404', 'POST', '/api/v1/notifications/' + notifications.v('noticeId') + '/read', 'foreign', status=404)
notifications.add('Чужая лента не содержит наш заказ', 'GET', '/api/v1/notifications?size=50', 'foreign', tests='''
pm.test('Нет утечки чужой организации', () => pm.expect(pm.response.json().items.some(n => n.subjectId === pm.environment.get('$P_orderId'))).to.eql(false));
''')
for action, status, event in [('start-cooking', 'IN_COOKING', 'COOKING_STARTED'), ('mark-ready', 'READY', 'ORDER_READY'), ('complete', 'COMPLETED', 'ORDER_COMPLETED')]:
    notifications.transition(action, status)
    notifications.notice('owner', event)
notifications.add('Итоговая лента — открыть Visualize', 'GET', '/api/v1/notifications?size=50', 'owner', tests='''
const own = pm.response.json().items.filter(n => n.subjectId === pm.environment.get('$P_orderId'));
pm.test('Четыре типа уведомлений и одна отметка прочтения', () => {
    pm.expect(own.map(n => n.type)).to.include.members(['ORDER_CONFIRMED','COOKING_STARTED','ORDER_READY','ORDER_COMPLETED']);
    pm.expect(own.find(n => n.id === pm.environment.get('$P_noticeId')).readAt).to.eql(pm.environment.get('$P_readAt'));
});
''' + visualizer('Лента представителя организации'))

runtime = Folder('runtime', '04 — Runtime: Git → Kafka refresh → лимит загрузки',
                 'Источник значений — Git, Kafka передаёт refresh. Перед демонстрацией выполните python3 scripts/demo-runtime-config.py --max-bytes 2048 и задайте expectedFileMaxBytes=2048 в environment. Затем Run этой папки. Для восстановления helper --restore, expectedFileMaxBytes=10485760, Run снова. Без изменений Git папка также проходит повторно. localhost:18889/ не имеет главной страницы (404); верный health — /actuator/health. Текущая политика также видна в Swagger File → GET /api/v1/files/settings под SUPERVISOR.')
runtime.setup()
runtime.prepared_order()
runtime.add('Management health — правильный путь, не корень порта', 'GET', '/actuator/health', admin=True,
            tests="pm.test('UP', () => pm.expect(pm.response.json().status).to.eql('UP'));")
runtime.add('SUPERVISOR — политика ДО refresh', 'GET', '/api/v1/files/settings', 'supervisor', tests='''
const data = pm.response.json();
pm.environment.set('$P_beforeMaxBytes', data.limits.maxBytes);
pm.environment.set('$P_beforeGeneration', data.generation);
pm.environment.set('$P_beforeAppliedAt', data.appliedAt);
pm.test('Выбран ожидаемый лимит', () => {
    const target = Number(pm.environment.get('expectedFileMaxBytes'));
    pm.expect(target).to.be.within(2048, 10485760);
});
''')
runtime.upload('existing')
runtime.add('CONFIG_ADMIN — отправить refresh через Kafka', 'POST', '/actuator/busrefresh/file-service:**',
            body='', status=204, admin=True,
            description='POST с Content-Type: application/json. Меняет не Git, а публикует сигнал перечитать Git. Использует отдельный CONFIG_ADMIN, не JWT супервизора.')
runtime.add('SUPERVISOR — дождаться применённой политики', 'GET', '/api/v1/files/settings', 'supervisor',
            tests=runtime.poll("data.limits.maxBytes === Number(pm.environment.get('expectedFileMaxBytes'))", 'Лимит из Git применён в runtime') + '''
if (ready) {
    pm.test('Политика валидна; поколение меняется только при новом значении', () => {
        pm.expect(data.lastRejection).to.eql(null);
        const changed = Number(pm.environment.get('$P_beforeMaxBytes')) !== Number(pm.environment.get('expectedFileMaxBytes'));
        if (changed) pm.expect(data.generation).to.be.above(Number(pm.environment.get('$P_beforeGeneration')));
        else pm.expect(data.generation).to.be.at.least(Number(pm.environment.get('$P_beforeGeneration')));
    });
    pm.environment.set('$P_afterGeneration', data.generation);
}
''')
runtime.upload('large', fixture='demoPdfLarge', status=None, tests=f'''
const expected = Number(pm.environment.get('expectedFileMaxBytes')) >= {len(LARGE)} ? 201 : 413;
const errorHint = pm.response.code >= 400 ? JSON.stringify(pm.response.json()) : '';
pm.test('Большой PDF: ' + expected + ' при текущем лимите', () => pm.expect(pm.response.code, errorHint).to.eql(expected));
''')
runtime.upload('small')
runtime.download('existing')
runtime.add('Представителю настройки недоступны → 403', 'GET', '/api/v1/files/settings', 'owner', status=403)
runtime.add('SUPERVISOR — итоговая политика', 'GET', '/api/v1/files/settings', 'supervisor', tests='''
pm.test('Работает целевой лимит', () => pm.expect(pm.response.json().limits.maxBytes).to.eql(Number(pm.environment.get('expectedFileMaxBytes'))));
''')

folders = [business, files, notifications, runtime]
collection = {
    'info': {'_postman_id': str(NAMESPACE), 'name': 'Lab4 — 4 повторяемых сценария',
             'schema': 'https://schema.getpostman.com/json/collection/v2.1.0/collection.json',
             'description': 'Импортировать этот JSON и lab4.local.postman_environment.json, выбрать environment. Run всей коллекции или одной папки, с первого запроса, 1+ iterations, delay 0. Каждая папка независима: собственные роли, уникальные имена, новые ID и версии. Токены подставляются автоматически. Все HTTP-запросы видимы, скрытых pm.sendRequest нет. Kafka-проверки повторяют только GET до pollTimeoutMs. PDF встроены, Working directory не нужен. Основная спецификация и акт остаются доступными для скачивания.\n\nRuntime: для видимого изменения сначала commit/push через scripts/demo-runtime-config.py, затем выставить expectedFileMaxBytes и запустить папку 04. После демонстрации --restore и повторный Run. Корень localhost:18889/ возвращает 404: это management API без UI. Swagger: http://localhost:18084/swagger-ui/index.html, File/settings под SUPERVISOR.\n\nСценарии создают тестовые данные и не удаляют прежние. Не экспортируйте заполненное environment с паролями и JWT. Генератор: scripts/build-postman.py; инструкция: postman/README.md.'},
    'auth': {'type': 'noauth'}, 'item': [f.node for f in folders],
    'variable': [{'key': key, 'value': value, 'type': 'string'} for key, value in [
        ('demoPdfSmall', SMALL), ('demoPdfAct', ACT), ('demoPdfLarge', LARGE), ('demoInvalidPdf', 'This is not a PDF.\n')]],
}
values = [
    ('baseUrl', 'http://localhost:18084', 'default'),
    ('configAdminUrl', 'http://localhost:18889', 'default'),
    ('supervisorLogin', 'supervisor', 'default'), ('supervisorPassword', '', 'secret'),
    ('configAdminLogin', 'config-admin', 'default'), ('configAdminPassword', '', 'secret'),
    ('expectedFileMaxBytes', '10485760', 'default'), ('pollTimeoutMs', '60000', 'default'),
    ('pollIntervalMs', '500', 'default'),
]
for folder in folders:
    for key in ['demoPassword', 'supervisorToken', 'clientToken', 'kitchenToken', 'ownerToken', 'foreignToken', 'colleagueToken']:
        values.append((folder.prefix + '_' + key, '', 'secret'))
environment = {'id': str(uuid.uuid5(NAMESPACE, 'environment')), 'name': 'Lab4 — заполнить доступы',
               'values': [{'key': key, 'value': value, 'type': kind, 'enabled': True} for key, value, kind in values],
               '_postman_variable_scope': 'environment'}
POSTMAN.mkdir(exist_ok=True)
for filename, data in [('lab4.postman_collection.json', collection), ('lab4.postman_environment.json', environment)]:
    (POSTMAN / filename).write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n')
(POSTMAN / 'fixtures').mkdir(exist_ok=True)
for name, content in [('specification.pdf', SMALL), ('discrepancy-act.pdf', ACT), ('large-specification.pdf', LARGE)]:
    (POSTMAN / 'fixtures' / name).write_bytes(content.encode('ascii'))
print('Built ' + str(sum(len(f.node['item']) for f in folders)) + ' visible requests in 4 independent folders')
