#!/usr/bin/env python3
"""L3.2 acceptance through Gateway. Credentials come only from the environment."""
import json
import os
import sys
import subprocess
import time
import urllib.error
import urllib.request
import uuid

base = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:18082"
password = os.environ["IDENTITY_BOOTSTRAP_PASSWORD"]
suffix = uuid.uuid4().hex[:8]


def call(method, path, body=None, token=None, expected=200):
    headers = {"Content-Type": "application/json", "X-Trace-Id": "identity-compose"}
    if token:
        headers["Authorization"] = "Bearer " + token
    request = urllib.request.Request(base + path, method=method, headers=headers,
        data=None if body is None else json.dumps(body).encode())
    try:
        response = urllib.request.urlopen(request, timeout=15)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        status = response.status
        data = json.loads(response.read() or b"null")
        assert response.headers["X-Trace-Id"] == "identity-compose", (path, "missing trace")
        # Never include a login response or request headers in failure diagnostics.
        expected_statuses = expected if isinstance(expected, tuple) else (expected,)
        assert status in expected_statuses, (method, path, status, expected,
            data.get("code") if isinstance(data, dict) else None)
        if status >= 400:
            assert set(data) == {"code", "message", "fieldErrors", "traceId"}
        return data


def login(name, secret=password, expected=200):
    data = call("POST", "/api/v1/auth/login", {"login": name, "password": secret}, expected=expected)
    return data["accessToken"] if expected == 200 else data


# A healthy replacement container may not yet be visible in Gateway's discovery cache.
# Retry only this startup condition; all acceptance requests below fail immediately.
deadline = time.monotonic() + 90
while True:
    readiness = call("GET", "/api/v1/me", expected=(401, 503))
    if readiness["code"] == "AUTHENTICATION_REQUIRED":
        break
    assert readiness["code"] == "DEPENDENCY_UNAVAILABLE", "Unexpected readiness error"
    assert time.monotonic() < deadline, "Gateway did not discover Identity within 90 seconds"
    time.sleep(1)
admin = login(os.environ["IDENTITY_BOOTSTRAP_LOGIN"])
me = call("GET", "/api/v1/me", token=admin)
assert me["roles"] == ["SUPERVISOR"]
assert "password" not in me and "passwordHash" not in me
call("GET", "/v3/api-docs/identity", expected=401)
schema = call("GET", "/v3/api-docs/identity", token=admin)
assert len([1 for item in schema["paths"].values() for method in item if method in ("get", "post", "put")]) == 7
call("GET", "/internal/v1/organizations/" + str(uuid.uuid4()), token=admin, expected=404)

# Business API authentication is now enforced at Gateway and the target service.
manager_login = "manager-" + suffix
manager = call("POST", "/api/v1/users", {"login": manager_login, "password": password,
    "roles": ["CLIENT_MANAGER"]}, token=admin, expected=201)
manager_token = login(manager_login)
org = call("POST", "/api/v1/organizations", {"name": "Identity " + suffix, "phone": "+79991234567"}, token=manager_token, expected=201)
call("POST", "/api/v1/categories", {"name": "forbidden-" + suffix}, token=manager_token, expected=403)
call("GET", "/api/v1/users", token=manager_token, expected=403)
call("POST", "/api/v1/users", {"login": "forbidden-" + suffix, "password": password,
    "roles": ["SUPERVISOR"]}, token=manager_token, expected=403)
representative_login = "representative-" + suffix
representative = call("POST", "/api/v1/users", {"login": representative_login, "password": password,
    "roles": ["ORGANIZATION_REPRESENTATIVE"], "organizationId": org["id"]}, token=admin, expected=201)
representative_token = login(representative_login)
assert call("GET", "/api/v1/me", token=representative_token)["organizationId"] == org["id"]
call("GET", "/api/v1/users?size=51", token=admin, expected=400)
page = call("GET", "/api/v1/users?size=1", token=admin)
assert len(page["items"]) == 1 and page["hasNext"]
assert "password" not in json.dumps(page) and "passwordHash" not in json.dumps(page)

call("PUT", "/api/v1/users/" + manager["id"] + "/roles", {"roles": ["KITCHEN_MANAGER"]}, token=admin)
call("GET", "/api/v1/me", token=manager_token, expected=401)
manager_token = login(manager_login)
assert call("GET", "/api/v1/me", token=manager_token)["roles"] == ["KITCHEN_MANAGER"]
call("POST", "/api/v1/categories", {"name": "kitchen-" + suffix}, token=manager_token, expected=201)
call("POST", "/api/v1/organizations", {"name": "forbidden-" + suffix, "phone": "+79991234567"}, token=manager_token, expected=403)
call("POST", "/api/v1/users/" + representative["id"] + "/block", token=admin)
call("GET", "/api/v1/me", token=representative_token, expected=401)
blocked = login(representative_login, expected=401)
missing = login("missing-" + suffix, expected=401)
wrong = login(manager_login, "wrong-password", expected=401)
assert blocked == missing == wrong
print("PASS: Gateway routes, login, supervisor-only users, role matrix, organization check, pagination and token revocation")
print("PASS: blocked/wrong/unknown credentials are indistinguishable; internal routes stay private; responses contain no passwords")

# Optional checks inside the supplied disposable Compose project, without publishing extra ports.
if len(sys.argv) > 2:
    compose = ["docker", "compose", "-p", sys.argv[2]]
    running = subprocess.check_output(compose + ["ps", "--format", "json"], text=True)
    containers = [json.loads(line) for line in running.splitlines() if line.strip()]
    expected = {name + "-service" for name in ("config", "discovery", "gateway", "catalog", "order", "kitchen", "identity")}
    expected |= {name + "-postgres" for name in ("catalog", "order", "kitchen", "identity")}
    assert {container["Service"] for container in containers} == expected
    assert all(container["Health"] == "healthy" for container in containers)
    for container in containers:
        published = any(port.get("PublishedPort", 0) > 0 for port in (container.get("Publishers") or []))
        assert published == (container["Service"] == "gateway-service")
    registry = json.loads(subprocess.check_output(compose + ["exec", "-T", "config-service", "wget", "-qO-",
        "--header=Accept: application/json", "http://discovery-service:8761/eureka/apps"]))
    assert any(app["name"] == "IDENTITY-SERVICE" and any(instance["status"] == "UP" for instance in app["instance"])
        for app in registry["applications"]["application"])
    tables = subprocess.check_output(compose + ["exec", "-T", "identity-postgres", "sh", "-c",
        'exec psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -At -c "$1"', "sh",
        "select tablename from pg_tables where schemaname='public'"], text=True).splitlines()
    assert set(tables) == {"app_user", "role", "user_role", "flyway_schema_history"}
    print("PASS: 11 healthy containers, only Gateway published, Identity registered in Eureka, owned schema migrated")
