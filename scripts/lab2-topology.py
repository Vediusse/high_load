#!/usr/bin/env python3
"""Read-only acceptance checks for the running lab 2 Compose project."""
import json
import subprocess
import sys
import urllib.error
import urllib.request
import uuid

project = sys.argv[1]
base = sys.argv[2] if len(sys.argv) > 2 else "http://localhost:18080"
compose = ["docker", "compose", "-p", project]
applications = {name + "-service" for name in ("config", "discovery", "gateway", "catalog", "order", "kitchen")}
owners = {
    "catalog": {"dish", "category", "dish_category", "flyway_schema_history"},
    "order": {"organization", "delivery_point", "corporate_order", "order_line", "order_status_history", "kitchen_command", "flyway_schema_history"},
    "kitchen": {"kitchen_task", "flyway_schema_history"},
}
services = applications | {name + "-postgres" for name in owners}
ids = subprocess.check_output(compose + ["ps", "-q"], text=True).split()
assert len(ids) == 9, ("Expected six applications and three databases", ids)
containers = json.loads(subprocess.check_output(["docker", "inspect", *ids]))
assert {c["Config"]["Labels"]["com.docker.compose.service"] for c in containers} == services
for container in containers:
    service = container["Config"]["Labels"]["com.docker.compose.service"]
    assert container["State"]["Health"]["Status"] == "healthy", service
    published = any(container["NetworkSettings"]["Ports"].values())
    assert published == (service == "gateway-service"), (service, "published ports")


def internal(url):
    return json.loads(subprocess.check_output(compose + ["exec", "-T", "config-service", "wget", "-qO-", url]))


for service in applications - {"config-service"}:
    port = 8761 if service == "discovery-service" else 8080
    assert internal(f"http://{service}:{port}/actuator/info")["configuration"]["source"] == "config-service", service

for owner, tables in owners.items():
    query = "select tablename from pg_tables where schemaname='public' order by tablename"
    rows = subprocess.check_output(compose + ["exec", "-T", owner + "-postgres", "sh", "-c",
        'exec psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -At -c "$1"', "sh", query], text=True).splitlines()
    assert set(rows) == tables, (owner, rows)


def error(method, path, status, code, body=None):
    request = urllib.request.Request(base + path, method=method, data=None if body is None else body.encode(),
        headers={"X-Trace-Id": "lab2-topology", "Content-Type": "application/json"})
    try:
        response = urllib.request.urlopen(request, timeout=10)
    except urllib.error.HTTPError as failure:
        response = failure
    with response:
        data = json.load(response)
        assert response.status == status, (path, response.status, data)
        assert set(data) == {"code", "message", "fieldErrors", "traceId"}, (path, data)
        assert data["code"] == code and data["traceId"] == "lab2-topology", (path, data)
        assert response.headers.get_all("X-Trace-Id") == ["lab2-topology"], path


missing = str(uuid.uuid4())
for path in ("/internal/v1/orders/kitchen", "/internal/v1/kitchen/tasks", "/internal/v1/dishes/snapshots",
             "/eureka/apps", "/actuator/env", "/api/v1/unknown", "/api/v1/dishes/" + missing, "/api/v1/orders/" + missing):
    error("GET", path, 404, "RESOURCE_NOT_FOUND")
error("POST", f"/api/v1/orders/{missing}/start-cooking", 404, "RESOURCE_NOT_FOUND", '{"expectedVersion":0}')
error("GET", f"/api/v1/orders/{missing}/start-cooking", 405, "HTTP_405")
error("POST", f"/api/v1/orders/{missing}/mark-ready", 400, "MALFORMED_JSON", "{")
error("POST", f"/api/v1/orders/{missing}/complete", 400, "VALIDATION_FAILED", "{}")
print("PASS: six applications, three owned databases, only Gateway published, private routes and consistent errors")
