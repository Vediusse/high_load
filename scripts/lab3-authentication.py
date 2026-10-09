#!/usr/bin/env python3
"""Verify external admission and local internal JWT validation on a disposable Compose stack."""
import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid

base = sys.argv[1] if len(sys.argv) > 1 else 'http://localhost:18082'
project = sys.argv[2] if len(sys.argv) > 2 else os.environ['COMPOSE_PROJECT_NAME']
container = project + '-config-service-1'
password = os.environ['IDENTITY_BOOTSTRAP_PASSWORD']


def external(method, path, token=None, body=None, expected=200, extra=None):
    headers = {'Content-Type': 'application/json', 'X-Trace-Id': 'request-auth-smoke'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    headers.update(extra or {})
    request = urllib.request.Request(base + path, method=method, headers=headers,
        data=None if body is None else json.dumps(body).encode())
    try:
        response = urllib.request.urlopen(request, timeout=15)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        data = json.loads(response.read() or b'null')
        assert response.status == expected, (method, path, response.status, expected)
        assert response.headers['X-Trace-Id'] == 'request-auth-smoke'
        return data


def internal(service, path, token=None, exchange=False, expected=200):
    args = ['docker', 'exec', container, 'wget', '-S', '-O', '-', '-T', '5']
    if token:
        args += ['--header=Authorization: Bearer ' + token]
    if exchange:
        args += ['--post-data=', '--header=X-Gateway-Credential: ' + os.environ['GATEWAY_EXCHANGE_SECRET']]
    args += ['http://' + service + ':8080' + path]
    response = subprocess.run(args, capture_output=True, text=True)
    statuses = re.findall(r'HTTP/\S+\s+(\d+)', response.stderr)
    assert statuses and int(statuses[-1]) == expected, (service, path, statuses, expected)
    # BusyBox wget may omit error response bodies. Never print command arguments or token bodies.
    return json.loads(response.stdout) if response.stdout.strip() else None


def login(name):
    return external('POST', '/api/v1/auth/login', body={'login': name, 'password': password})['accessToken']


# Gateway/Eureka registration is asynchronous after container health becomes ready.
for attempt in range(60):
    try:
        admin = login(os.environ['IDENTITY_BOOTSTRAP_LOGIN'])
        external('GET', '/api/v1/categories', admin)
        break
    except (urllib.error.URLError, TimeoutError, AssertionError):
        if attempt == 59:
            raise RuntimeError('Authenticated Gateway route did not become ready') from None
        time.sleep(1)

external('GET', '/api/v1/categories', expected=401)
external('GET', '/api/v1/categories', admin)
external('GET', '/internal/v1/auth/exchange', admin, expected=404)
name = 'admission-' + uuid.uuid4().hex[:10]
user = external('POST', '/api/v1/users', admin,
    {'login': name, 'password': password, 'roles': ['KITCHEN_MANAGER']}, expected=201)
access = login(name)
proof = internal('identity-service', '/internal/v1/auth/exchange', access, exchange=True)['accessToken']
for service, path in [('catalog-service', '/api/v1/categories'), ('order-service', '/api/v1/orders'),
                      ('kitchen-service', '/internal/v1/kitchen/tasks')]:
    internal(service, path, access, expected=401)
    internal(service, path, proof)
external('GET', '/api/v1/categories', proof, expected=401)
internal('identity-service', '/internal/v1/auth/exchange', proof, exchange=True, expected=401)
internal('identity-service', '/internal/v1/auth/exchange', access, expected=403)
internal('order-service', '/internal/v1/organizations/' + str(uuid.uuid4()), proof, expected=403)
external('GET', '/api/v1/users', access, expected=403, extra={'X-Roles': 'SUPERVISOR', 'X-User-Id': user['id']})
external('POST', '/api/v1/users/' + user['id'] + '/block', admin)
external('GET', '/api/v1/categories', access, expected=401)
internal('catalog-service', '/api/v1/categories', proof)
print('PASS: external/internal JWT separation, Gateway-only exchange, explicit Kitchen->Order auth, roles and revocation boundary')

# Verify the availability boundary without changing persistent data or stopping the user's original stack.
proof = internal('identity-service', '/internal/v1/auth/exchange', admin, exchange=True)['accessToken']
identity = project + '-identity-service-1'
paused = subprocess.run(['docker', 'pause', identity], capture_output=True).returncode == 0
assert paused, 'Could not pause disposable Identity'
try:
    external('GET', '/api/v1/categories', admin, expected=503)
    internal('catalog-service', '/api/v1/categories', proof)
finally:
    result = subprocess.run(['docker', 'unpause', identity], capture_output=True)
    assert result.returncode == 0, 'Could not unpause disposable Identity'
external('GET', '/api/v1/categories', admin)
print('PASS: unavailable Identity denies new admission; already admitted internal request works; recovery succeeds')

# Reuse the complete previous business scenario and OpenAPI contract through authenticated Gateway.
workflow_login = 'workflow-' + uuid.uuid4().hex[:10]
external('POST', '/api/v1/users', admin,
         {'login': workflow_login, 'password': password,
          'roles': ['CLIENT_MANAGER', 'KITCHEN_MANAGER']}, expected=201)
environment = dict(os.environ, AUTH_TOKEN=login(workflow_login))
for command in [['bash', 'scripts/lab1-defense.sh', base], ['python3', 'scripts/lab2-api-contract.py', base]]:
    result = subprocess.run(command, env=environment, capture_output=True, text=True)
    if result.returncode:
        # These scripts do not print request headers. Limit diagnostics to their ordinary output.
        print(result.stdout)
        print(result.stderr)
        raise RuntimeError('Authenticated regression failed')
    print(result.stdout.strip())
print('PASS: previous business lifecycle and API contracts with authentication')
