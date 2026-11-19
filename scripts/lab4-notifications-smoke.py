#!/usr/bin/env python3
"""Verify all order/production events, private feed and poison recovery on Compose."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import time
import urllib.error
import urllib.request
import uuid

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--env-file', default='.env')
parser.add_argument('--base-url', default='http://localhost:8080')
args = parser.parse_args()
env = dict(os.environ)
for line in Path(args.env_file).read_text().splitlines():
    if line and not line.startswith('#') and '=' in line:
        key, value = line.split('=', 1)
        env[key] = value
project = env.get('COMPOSE_PROJECT_NAME', 'corporate-catering')
password = env['IDENTITY_BOOTSTRAP_PASSWORD']
base = args.base_url.rstrip('/')


def api(method, path, token=None, body=None, expected=200):
    headers = {'Content-Type': 'application/json', 'X-Trace-Id': 'lab4-notifications'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    request = urllib.request.Request(base + '/api/v1' + path, method=method,
        headers=headers, data=None if body is None else json.dumps(body).encode())
    try:
        response = urllib.request.urlopen(request, timeout=15)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        payload = json.loads(response.read() or b'null')
        assert response.status == expected, (method, path, response.status, expected)
        return payload


def until(predicate, label, timeout=120):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            value = predicate()
            if value:
                return value
        except (AssertionError, urllib.error.URLError, TimeoutError):
            pass
        time.sleep(0.5)
    raise AssertionError('Timed out: ' + label)


def docker(*command, **kwargs):
    result = subprocess.run(['docker', *command], env=env, capture_output=True, text=True, **kwargs)
    if result.returncode:
        raise RuntimeError('Docker action failed: ' + command[0])
    return result.stdout.strip()


def db(service, sql):
    return docker('exec', project + '-' + service + '-postgres-1', 'sh', '-c',
        'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tA -c "$1"', 'sh', sql)


def login(name):
    return api('POST', '/auth/login', body={'login': name, 'password': password})['accessToken']


admin = until(lambda: login(env['IDENTITY_BOOTSTRAP_LOGIN']), 'Gateway')
suffix = uuid.uuid4().hex[:9]


def user(role, organization=None):
    name = 'feed-' + role.lower().replace('organization_representative', 'org') + '-' + uuid.uuid4().hex[:8]
    body = {'login': name, 'password': password, 'roles': [role]}
    if organization:
        body['organizationId'] = organization
    created = api('POST', '/users', admin, body, 201)
    return created, login(name)


km, kitchen = user('KITCHEN_MANAGER')
cm, client = user('CLIENT_MANAGER')
org = api('POST', '/organizations', client, {'name': 'Feed ' + suffix, 'phone': '+79991234567'}, 201)
other_org = api('POST', '/organizations', client, {'name': 'Foreign ' + suffix, 'phone': '+79991234567'}, 201)
point = api('POST', '/organizations/' + org['id'] + '/delivery-points', client,
    {'name': 'Office', 'address': 'Test address', 'contactName': 'Contact', 'contactPhone': '+79991234567'}, 201)
representative, own = user('ORGANIZATION_REPRESENTATIVE', org['id'])
foreign, foreign_token = user('ORGANIZATION_REPRESENTATIVE', other_org['id'])
category = api('POST', '/categories', kitchen, {'name': 'Feed ' + suffix}, 201)
dish = api('POST', '/dishes', kitchen, {'name': 'Feed ' + suffix, 'description': 'Event test',
    'currentPrice': 100, 'categoryIds': [category['id']]}, 201)


def create_order():
    order = api('POST', '/orders', own, {'organizationId': org['id'], 'deliveryPointId': point['id'],
        'requestedDeliveryAt': '2099-01-01T12:00:00Z'}, 201)
    order = api('PUT', '/orders/' + order['id'] + '/lines', own,
        {'expectedVersion': order['version'], 'lines': [{'dishId': dish['id'], 'quantity': 1}]})
    return transition(order, 'submit', own)


def transition(order, action, token, reason=None):
    body = {'expectedVersion': order['version']}
    if reason:
        body['reason'] = reason
    return api('POST', '/orders/' + order['id'] + '/' + action, token, body)


def feed(token):
    items = []
    page = 0
    while True:
        result = api('GET', '/notifications?page=' + str(page) + '&size=50', token)
        items.extend(result['items'])
        if not result['hasNext']:
            return items
        page += 1


def notice(token, order, event_type):
    return next((item for item in feed(token) if item['subjectId'] == order['id'] and item['type'] == event_type), None)


order = create_order()
until(lambda: notice(kitchen, order, 'ORDER_SUBMITTED'), 'new order notification')
order = transition(order, 'confirm', kitchen)
until(lambda: notice(own, order, 'ORDER_CONFIRMED'), 'confirmation notification')
until(lambda: db('kitchen', "select count(*) from kitchen_task where order_id='" + order['id'] + "' and status='CONFIRMED'") == '1', 'event-built kitchen projection')
order = transition(order, 'start-cooking', kitchen)
until(lambda: notice(own, order, 'COOKING_STARTED'), 'cooking notification')
order = transition(order, 'mark-ready', kitchen)
until(lambda: notice(own, order, 'ORDER_READY'), 'ready notification')
order = transition(order, 'complete', kitchen)
until(lambda: notice(own, order, 'ORDER_COMPLETED'), 'completed notification')
rejected = transition(create_order(), 'reject', kitchen, 'Kitchen rejected entire order')
until(lambda: notice(own, rejected, 'ORDER_REJECTED'), 'rejection notification')
cancelled = transition(create_order(), 'cancel', own, 'Organization cancelled')
until(lambda: notice(kitchen, cancelled, 'ORDER_CANCELLED'), 'kitchen cancellation')
until(lambda: notice(client, cancelled, 'ORDER_CANCELLED'), 'client cancellation')
print('PASS: submitted/confirmed/rejected/cancelled/cooking/ready/completed events and real recipients')

api('GET', '/notifications', expected=401)
api('GET', '/notifications?size=51', own, expected=400)
assert api('GET', '/notifications?size=1', own)['hasNext']
entry = notice(own, order, 'ORDER_READY')
first = api('POST', '/notifications/' + entry['id'] + '/read', own)
assert first['readAt']
assert api('POST', '/notifications/' + entry['id'] + '/read', own) == first
api('POST', '/notifications/' + entry['id'] + '/read', foreign_token, expected=404)
assert not feed(foreign_token)
print('PASS: private paginated feed, immutable read time, foreign ID hidden as 404')


def send(topic, key, value):
    docker('exec', '-i', '-e', 'KAFKA_HEAP_OPTS=-Xms32m -Xmx64m', project + '-kafka-1-1',
        '/opt/kafka/bin/kafka-console-producer.sh', '--bootstrap-server', 'kafka-1:9092,kafka-2:9092,kafka-3:9092',
        '--topic', topic, '--sync', '--property', 'parse.key=true', '--property', 'key.separator=|',
        '--producer-property', 'acks=all', '--producer-property', 'delivery.timeout.ms=10000',
        '--producer-property', 'request.timeout.ms=3000', input=key + '|' + value + '\n')


before = db('notification', "select count(*) from notification where subject_id='" + order['id'] + "'")
send('order-events', order['id'], '{bad-' + suffix)
raw = db('order', "select payload::text from outbox_event where aggregate_id='" + order['id'] + "' and payload->>'eventType'='ORDER_CONFIRMED'")
send('order-events', order['id'], raw)
raw_ready = db('kitchen', "select payload::text from outbox_event where aggregate_id='" + order['id'] + "' and payload->>'eventType'='ORDER_READY'")
send('production-events', order['id'], raw_ready)


def caught_up():
    output = docker('exec', '-e', 'KAFKA_HEAP_OPTS=-Xms32m -Xmx64m', project + '-kafka-1-1',
        '/opt/kafka/bin/kafka-consumer-groups.sh', '--bootstrap-server', 'kafka-1:9092',
        '--describe', '--group', 'notification-v1')
    rows = [line.split() for line in output.splitlines() if line.startswith('notification-v1 ')]
    return len(rows) == 9 and all(row[5] == '0' or row[4] == '0' for row in rows)


until(caught_up, 'consumer catches up after poison and repeats')
assert db('notification', "select count(*) from notification where subject_id='" + order['id'] + "'") == before
print('PASS: invalid message does not block partition; repeated order and production events have no duplicate effect')
print('orderId=' + order['id'])
