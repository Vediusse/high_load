#!/usr/bin/env python3
"""Exercise real Order -> outbox -> Kafka -> Notification, including recovery."""
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
parser.add_argument('--failure-tests', action='store_true')
args = parser.parse_args()
env = dict(os.environ)
for line in Path(args.env_file).read_text().splitlines():
    if line.strip() and not line.startswith('#') and '=' in line:
        key, value = line.split('=', 1)
        env[key] = value
project = env.get('COMPOSE_PROJECT_NAME', 'corporate-catering')
base = args.base_url.rstrip('/')
password = env['IDENTITY_BOOTSTRAP_PASSWORD']


def api(method, path, token=None, body=None, expected=200):
    headers = {'Content-Type': 'application/json', 'X-Trace-Id': 'lab4-outbox-smoke'}
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


def until(predicate, description, timeout=90):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            result = predicate()
            if result:
                return result
        except (AssertionError, urllib.error.URLError, TimeoutError):
            pass
        time.sleep(0.5)
    raise AssertionError('Timed out: ' + description)


def docker(*command, **kwargs):
    result = subprocess.run(['docker', *command], capture_output=True, text=True, env=env, **kwargs)
    if result.returncode:
        raise RuntimeError('Docker action failed: ' + command[0])
    return result.stdout


def db(service, sql):
    return docker('exec', project + '-' + service + '-postgres-1', 'sh', '-c',
        'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tA -c "$1"', 'sh', sql).strip()


def login(name):
    return api('POST', '/auth/login', body={'login': name, 'password': password})['accessToken']


admin = until(lambda: login(env['IDENTITY_BOOTSTRAP_LOGIN']), 'Gateway admission')
suffix = uuid.uuid4().hex[:10]
name = 'events-manager-' + suffix
api('POST', '/users', admin, {'login': name, 'password': password,
    'roles': ['CLIENT_MANAGER', 'KITCHEN_MANAGER']}, 201)
manager = login(name)
org = api('POST', '/organizations', manager, {'name': 'Events ' + suffix, 'phone': '+79991234567'}, 201)
point = api('POST', '/organizations/' + org['id'] + '/delivery-points', manager,
    {'name': 'Office', 'address': 'Test address', 'contactName': 'Contact', 'contactPhone': '+79991234567'}, 201)
representative = api('POST', '/users', admin, {'login': 'events-org-' + suffix,
    'password': password, 'roles': ['ORGANIZATION_REPRESENTATIVE'], 'organizationId': org['id']}, 201)
category = api('POST', '/categories', manager, {'name': 'Events ' + suffix}, 201)
dish = api('POST', '/dishes', manager, {'name': 'Events ' + suffix, 'description': 'Outbox test',
    'currentPrice': 100, 'categoryIds': [category['id']]}, 201)


def submitted():
    order = api('POST', '/orders', manager, {'organizationId': org['id'], 'deliveryPointId': point['id'],
        'requestedDeliveryAt': '2099-01-01T12:00:00Z', 'comment': 'Outbox recovery'}, 201)
    order = api('PUT', '/orders/' + order['id'] + '/lines', manager,
        {'expectedVersion': order['version'], 'lines': [{'dishId': dish['id'], 'quantity': 1}]})
    return api('POST', '/orders/' + order['id'] + '/submit', manager, {'expectedVersion': order['version']})


def confirm(order):
    result = api('POST', '/orders/' + order['id'] + '/confirm', manager, {'expectedVersion': order['version']})
    assert result['status'] == 'CONFIRMED'
    assert db('order', "select count(*) from order_status_history where order_id='" + order['id'] + "'") == '2'
    return result


def notifications(order):
    return db('notification', "select count(*) from notification where type='ORDER_CONFIRMED' and subject_id='"
        + order['id'] + "' and recipient_user_id='" + representative['id'] + "'")


def replay(order):
    raw = db('order', "select payload::text from outbox_event where aggregate_id='" + order['id'] + "' and payload->>'eventType'='ORDER_CONFIRMED'")
    docker('exec', '-i', '-e', 'KAFKA_HEAP_OPTS=-Xms32m -Xmx64m', project + '-kafka-1-1',
        '/opt/kafka/bin/kafka-console-producer.sh', '--bootstrap-server', 'kafka-1:9092,kafka-2:9092,kafka-3:9092',
        '--topic', 'order-events', '--sync', '--property', 'parse.key=true', '--property', 'key.separator=|',
        '--producer-property', 'acks=all', '--producer-property', 'delivery.timeout.ms=10000',
        '--producer-property', 'request.timeout.ms=3000', input=order['id'] + '|' + raw + '\n')
    event_id = json.loads(raw)['eventId']
    until(lambda: db('notification', "select count(*) from processed_event where event_id='" + event_id + "'") == '1', 'processed event')


order = confirm(submitted())
until(lambda: notifications(order) == '1', 'first notification')
replay(order)
assert notifications(order) == '1'
print('PASS: confirm/history/outbox, real Kafka delivery and duplicate effect suppression')

if args.failure_tests:
    order = submitted()
    brokers = [project + '-kafka-' + str(i) + '-1' for i in (1, 2, 3)]
    try:
        docker('stop', '--timeout=20', *brokers)
        order = confirm(order)
        assert db('order', "select count(*) from outbox_event where aggregate_id='" + order['id'] + "' and sent_at is null") == '1'
        assert notifications(order) == '0'
    finally:
        docker('start', *brokers)
    until(lambda: notifications(order) == '1', 'outbox catch-up after Kafka recovery', timeout=150)
    print('PASS: Kafka outage preserves confirmed order and pending event; recovery delivers it')
    service = project + '-notification-service-1'
    try:
        docker('stop', '--timeout=30', service)
        backlog = confirm(submitted())
        until(lambda: db('order', "select count(*) from outbox_event where aggregate_id='" + backlog['id'] + "' and sent_at is not null") == '1', 'broker acknowledgment')
        assert notifications(backlog) == '0'
    finally:
        docker('start', service)
    until(lambda: notifications(backlog) == '1', 'consumer backlog recovery', timeout=150)
    replay(backlog)
    assert notifications(backlog) == '1'
    print('PASS: Notification outage leaves business commands available; restart catches up without duplicates')
print('orderId=' + order['id'])
