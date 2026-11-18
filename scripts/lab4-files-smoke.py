#!/usr/bin/env python3
"""Verify file storage, ownership, order links and file notifications on real Compose."""
import argparse
import hashlib
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
base = args.base_url.rstrip('/')
password = env['IDENTITY_BOOTSTRAP_PASSWORD']
pdf = b'%PDF-1.4\nlab4 specification\n%%EOF\n'


def request(method, path, token=None, data=None, content_type='application/json', expected=200):
    headers = {'Content-Type': content_type, 'X-Trace-Id': 'lab4-files-smoke'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    req = urllib.request.Request(base + '/api/v1' + path, method=method, data=data, headers=headers)
    try:
        response = urllib.request.urlopen(req, timeout=20)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        body = response.read()
        assert response.status == expected, (method, path, response.status, expected)
        return response.headers, body


def api(method, path, token=None, body=None, expected=200):
    _, raw = request(method, path, token, None if body is None else json.dumps(body).encode(), expected=expected)
    return json.loads(raw or b'null')


def until(predicate, description, timeout=120):
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
name = 'file-manager-' + suffix
api('POST', '/users', admin, {'login': name, 'password': password, 'roles': ['CLIENT_MANAGER', 'KITCHEN_MANAGER']}, 201)
manager = login(name)
org = api('POST', '/organizations', manager, {'name': 'Files ' + suffix, 'phone': '+79991234567'}, 201)
foreign_org = api('POST', '/organizations', manager, {'name': 'Foreign files ' + suffix, 'phone': '+79991234567'}, 201)
point = api('POST', '/organizations/' + org['id'] + '/delivery-points', manager,
    {'name': 'Office', 'address': 'Test address', 'contactName': 'Contact', 'contactPhone': '+79991234567'}, 201)


def representative(organization):
    name = 'file-org-' + uuid.uuid4().hex[:9]
    user = api('POST', '/users', admin, {'login': name, 'password': password,
        'roles': ['ORGANIZATION_REPRESENTATIVE'], 'organizationId': organization}, 201)
    return user, login(name)


owner, own = representative(org['id'])
_, colleague = representative(org['id'])
_, foreign = representative(foreign_org['id'])
category = api('POST', '/categories', manager, {'name': 'Files ' + suffix}, 201)
dish = api('POST', '/dishes', manager, {'name': 'Files ' + suffix, 'description': 'File test',
    'currentPrice': 100, 'categoryIds': [category['id']]}, 201)
order = api('POST', '/orders', own, {'organizationId': org['id'], 'deliveryPointId': point['id'],
    'requestedDeliveryAt': '2099-01-01T12:00:00Z'}, 201)
order = api('PUT', '/orders/' + order['id'] + '/lines', own,
    {'expectedVersion': order['version'], 'lines': [{'dishId': dish['id'], 'quantity': 1}]})
order = api('POST', '/orders/' + order['id'] + '/submit', own, {'expectedVersion': order['version']})
order = api('POST', '/orders/' + order['id'] + '/confirm', manager, {'expectedVersion': order['version']})


def upload(content=pdf, mime='application/pdf', relation='SPECIFICATION', expected=201):
    boundary = 'lab4-' + uuid.uuid4().hex
    chunks = []
    for key, value in [('orderId', order['id']), ('relationType', relation)]:
        chunks.append(('--' + boundary + '\r\nContent-Disposition: form-data; name="' + key + '"\r\n\r\n' + value + '\r\n').encode())
    chunks.append(('--' + boundary + '\r\nContent-Disposition: form-data; name="file"; filename="../../spec-' + suffix + '.pdf"\r\nContent-Type: ' + mime + '\r\n\r\n').encode())
    chunks.extend([content, ('\r\n--' + boundary + '--\r\n').encode()])
    _, raw = request('POST', '/files', own, b''.join(chunks), 'multipart/form-data; boundary=' + boundary, expected)
    return json.loads(raw or b'null')


file = upload()
assert file['uploadedBy'] == owner['id'] and file['checksum'] == hashlib.sha256(pdf).hexdigest()
assert 'storageKey' not in file and file['size'] == len(pdf)
headers, downloaded = request('GET', '/files/' + file['id'], own)
assert downloaded == pdf and headers['X-Content-Type-Options'] == 'nosniff'
assert request('GET', '/files/' + file['id'], colleague)[1] == pdf
request('GET', '/files/' + file['id'], foreign, expected=404)
request('GET', '/files/' + file['id'], admin, expected=403)
api('GET', '/orders/' + order['id'] + '/files', foreign, expected=404)
api('GET', '/orders/' + order['id'] + '/file-references', foreign, expected=404)
api('DELETE', '/files/' + file['id'], colleague, expected=404)
until(lambda: any(item['fileId'] == file['id'] for item in api('GET', '/orders/' + order['id'] + '/file-references', own)['items']), 'Order file reference')
print('PASS: PDF/checksum round-trip, opaque storage key, order access and owner-only deletion')

upload(mime='application/octet-stream', expected=415)
upload(content=b'not a PDF', expected=422)
upload(content=pdf + b'x' * int(env.get('FILE_MAX_BYTES', '10485760')), expected=413)
upload(relation='DISCREPANCY_ACT', expected=422)
api('GET', '/orders/' + order['id'] + '/files?size=51', own, expected=400)
assert len(api('GET', '/orders/' + order['id'] + '/files', own)['items']) == 1
print('PASS: disallowed MIME, signature mismatch, oversized payload and premature discrepancy act rejected')

key = str(uuid.UUID(db('file', "select storage_key from file_document where id='" + file['id'] + "'")))
path = '/var/lib/catering-files/' + key
corrupted = pdf[:-1] + bytes([pdf[-1] ^ 1])
try:
    docker('exec', '-i', project + '-file-service-1', 'sh', '-c', 'cat > "$1"', 'sh', path, input=corrupted.decode())
    _, raw = request('GET', '/files/' + file['id'], own, expected=500)
    assert json.loads(raw)['code'] == 'FILE_INTEGRITY_ERROR'
finally:
    docker('exec', '-i', project + '-file-service-1', 'sh', '-c', 'cat > "$1"', 'sh', path, input=pdf.decode())
assert request('GET', '/files/' + file['id'], own)[1] == pdf
print('PASS: same-size corruption is detected; original binary restored')

for action in ['start-cooking', 'mark-ready']:
    order = api('POST', '/orders/' + order['id'] + '/' + action, manager, {'expectedVersion': order['version']})
act = upload(relation='DISCREPANCY_ACT')
for _ in range(2):
    api('DELETE', '/files/' + file['id'], own, expected=204)
request('GET', '/files/' + file['id'], own, expected=404)
docker('exec', project + '-file-service-1', 'test', '-f', path)
until(lambda: [item['fileId'] for item in api('GET', '/orders/' + order['id'] + '/file-references', own)['items']] == [act['id']], 'deleted reference disappears')
assert [item['id'] for item in api('GET', '/orders/' + order['id'] + '/files', own)['items']] == [act['id']]


def file_notices():
    result = api('GET', '/notifications?size=50', own)['items']
    types = {n['type'] for n in result if n['subjectId'] == order['id']}
    return {'FILE_UPLOADED', 'FILE_DELETED'} <= types


until(file_notices, 'file event notifications')
print('PASS: discrepancy act at READY, logical deletion, Order links and uploaded/deleted notifications')
print('orderId=' + order['id'] + ' fileId=' + act['id'])
