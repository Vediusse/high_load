#!/usr/bin/env python3
"""Create demo roles and a submitted order through the actual protected API."""
import argparse
import json
import os
from pathlib import Path
import urllib.error
import urllib.request
import uuid

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--env-file', default='.env')
parser.add_argument('--base-url', default='http://localhost:8080')
parser.add_argument('--output', default='CREDENTIALS.local.md')
args = parser.parse_args()
env = dict(os.environ)
for line in Path(args.env_file).read_text().splitlines():
    if line and not line.startswith('#') and '=' in line:
        key, value = line.split('=', 1)
        env[key] = value
password = env['IDENTITY_BOOTSTRAP_PASSWORD']
base = args.base_url.rstrip('/')


def api(method, path, token=None, body=None, expected=200):
    headers = {'Content-Type': 'application/json'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    request = urllib.request.Request(base + '/api/v1' + path, method=method, headers=headers,
        data=None if body is None else json.dumps(body).encode())
    try:
        response = urllib.request.urlopen(request, timeout=20)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        data = json.loads(response.read() or b'null')
        if response.status != expected:
            raise RuntimeError(method + ' ' + path + ': HTTP ' + str(response.status))
        return data


def login(name):
    return api('POST', '/auth/login', body={'login': name, 'password': password})['accessToken']


admin = login(env['IDENTITY_BOOTSTRAP_LOGIN'])
suffix = uuid.uuid4().hex[:6]
actors = {}
for role, label in [('CLIENT_MANAGER', 'client'), ('KITCHEN_MANAGER', 'kitchen')]:
    name = 'lab4.' + label + '.' + suffix
    api('POST', '/users', admin, {'login': name, 'password': password, 'roles': [role]}, 201)
    actors[role] = (name, login(name))
client = actors['CLIENT_MANAGER'][1]
kitchen = actors['KITCHEN_MANAGER'][1]
org = api('POST', '/organizations', client, {'name': 'Демо lab4 ' + suffix, 'phone': '+79990000111'}, 201)
point = api('POST', '/organizations/' + org['id'] + '/delivery-points', client,
    {'name': 'Демо офис', 'address': 'Кронверкский проспект, 49', 'contactName': 'Демо', 'contactPhone': '+79990000111'}, 201)
name = 'lab4.org.' + suffix
api('POST', '/users', admin, {'login': name, 'password': password,
    'roles': ['ORGANIZATION_REPRESENTATIVE'], 'organizationId': org['id']}, 201)
actors['ORGANIZATION_REPRESENTATIVE'] = (name, login(name))
category = api('POST', '/categories', kitchen, {'name': 'Демо lab4 ' + suffix}, 201)
dish = api('POST', '/dishes', kitchen, {'name': 'Демо обед ' + suffix, 'description': 'Постоянное меню',
    'currentPrice': 250, 'categoryIds': [category['id']]}, 201)
representative = actors['ORGANIZATION_REPRESENTATIVE'][1]
order = api('POST', '/orders', representative, {'deliveryPointId': point['id'], 'requestedDeliveryAt': '2099-01-01T12:00:00Z'}, 201)
order = api('PUT', '/orders/' + order['id'] + '/lines', representative,
    {'expectedVersion': order['version'], 'lines': [{'dishId': dish['id'], 'quantity': 2}]})
order = api('POST', '/orders/' + order['id'] + '/submit', representative, {'expectedVersion': order['version']})
permissions = {
    'SUPERVISOR': 'пользователи /users; /me; чтение каталога; свои уведомления',
    'CLIENT_MANAGER': 'организации/точки; заказы; submit/cancel; файлы; свои уведомления',
    'KITCHEN_MANAGER': 'каталог; заказы без DRAFT; confirm/reject/start-cooking/mark-ready/complete; файлы; свои уведомления',
    'ORGANIZATION_REPRESENTATIVE': 'своя организация/точки/заказы; submit/cancel; свои доступные файлы; свои уведомления',
}
users = [('SUPERVISOR', env['IDENTITY_BOOTSTRAP_LOGIN'])] + [(role, value[0]) for role, value in actors.items()]
lines = ['# Активные пользователи', '']
for role, name in users:
    lines += ['## ' + role, '', '- login: `' + name + '`', '- password: `' + password + '`', '- ручки: ' + permissions[role], '']
lines += ['Удаление файла — только загрузивший его пользователь.', '',
    'org: `' + org['id'] + '`', 'deliveryPoint: `' + point['id'] + '`',
    'category: `' + category['id'] + '`', 'dish: `' + dish['id'] + '`',
    'order: `' + order['id'] + '` — ' + order['status'] + ', expectedVersion=' + str(order['version']), '']
output = Path(args.output)
with os.fdopen(os.open(str(output), os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600), 'w') as file:
    file.write('\n'.join(lines))
output.chmod(0o600)
print('Created demo users and order; credentials written to ' + str(output))
