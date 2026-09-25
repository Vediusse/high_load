#!/usr/bin/env bash
set -euo pipefail
python3 - "${1:?Укажи имя тестового Compose-проекта}" "${2:-http://localhost:8080}" <<'PY'
import json, subprocess, sys, time, urllib.request, urllib.error, uuid
project, base = sys.argv[1:]
def call(method, path, body=None, expected=200):
    request = urllib.request.Request(base + path, data=None if body is None else json.dumps(body).encode(),
        method=method, headers={'Content-Type':'application/json', 'X-Trace-Id':'catalog-failure-check'})
    try:
        response = urllib.request.urlopen(request, timeout=6)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        status = response.status
        data = json.loads(response.read() or b'null')
        assert response.headers['X-Trace-Id'] == 'catalog-failure-check'
    if expected is not None:
        assert status == expected, (method, path, status, data)
    return status, data
suffix = str(uuid.uuid4())[:8]
_, org = call('POST', '/api/v1/organizations', {'name':'Отказ каталога '+suffix, 'phone':'+79991234567'}, 201)
_, point = call('POST', '/api/v1/organizations/'+org['id']+'/delivery-points',
    {'name':'Офис', 'address':'Адрес', 'contactName':'Анна', 'contactPhone':'+79991234568'}, 201)
_, dish = call('POST', '/api/v1/dishes',
    {'name':'Суп '+suffix, 'description':'Проверка отказа', 'currentPrice':180, 'categoryIds':[]}, 201)
_, draft = call('POST', '/api/v1/orders', {'organizationId':org['id'], 'deliveryPointId':point['id'],
    'requestedDeliveryAt':'2099-01-01T12:00:00Z', 'comment':'Проверка отказа'}, 201)
path = '/api/v1/orders/'+draft['id']
_, filled = call('PUT', path+'/lines', {'expectedVersion':draft['version'], 'lines':[{'dishId':dish['id'], 'quantity':2}]})
command = ['docker', 'compose', '-p', project]
subprocess.run(command+['stop', 'catalog-service'], check=True)
try:
    for attempt in range(8):
        status, error = call('POST', path+'/submit', {'expectedVersion':filled['version']}, 503)
        assert error['code'] == 'DEPENDENCY_UNAVAILABLE'
        assert error['traceId'] == 'catalog-failure-check'
    _, unchanged = call('GET', path)
    assert unchanged == filled
    _, history = call('GET', path+'/history')
    assert history['items'] == []
    call('GET', '/api/v1/organizations/'+org['id'])
finally:
    subprocess.run(command+['start', 'catalog-service'], check=True)
deadline = time.monotonic()+120
while True:
    status, submitted = call('POST', path+'/submit', {'expectedVersion':filled['version']}, expected=None)
    if status == 200:
        break
    assert status == 503, (status, submitted)
    assert time.monotonic() < deadline, 'Catalog did not recover'
    time.sleep(2)
assert submitted['status'] == 'SUBMITTED'
assert submitted['totalAmount'] == 360
_, history = call('GET', path+'/history')
assert len(history['items']) == 1
print('PASS: при отказе каталога заказ неизменен; после восстановления SUBMITTED, снимок 360, одна запись истории')
print('orderId='+draft['id'])
PY
