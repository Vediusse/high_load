#!/usr/bin/env python3
"""Exercise kitchen routing, idempotency and dependency failures on a disposable Compose project."""
import concurrent.futures
import json
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid

project = sys.argv[1]
base = sys.argv[2] if len(sys.argv) > 2 else "http://localhost:18080"
compose = ["docker", "compose", "-p", project]


def api(method, path, data=None, expected=200, *, retry_unavailable=False):
    request = urllib.request.Request(base + "/api/v1" + path, method=method,
        data=None if data is None else json.dumps(data).encode(),
        headers={"Content-Type": "application/json", "X-Trace-Id": "kitchen-compose"})
    deadline = time.monotonic() + 120
    while True:
        try:
            response = urllib.request.urlopen(request, timeout=15)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            body = json.load(response)
            status = response.status
            assert response.headers["X-Trace-Id"] == "kitchen-compose"
        # A successful probe does not mean the circuit has finished recovery.
        # Retry only a known availability error, with the same command/version.
        if (retry_unavailable and status == 503 and body.get("code") == "DEPENDENCY_UNAVAILABLE"
                and time.monotonic() < deadline):
            print(f"Recovery: retry {method} {path} after 503 DEPENDENCY_UNAVAILABLE", flush=True)
            time.sleep(2)
            continue
        assert status == expected, (path, status, body)
        return body


def internal(path="/internal/v1/kitchen/tasks?size=50"):
    return json.loads(subprocess.check_output(compose + ["exec", "-T", "config-service", "wget", "-qO-",
        "http://kitchen-service:8080" + path]))


def sql(service, query):
    return subprocess.check_output(compose + ["exec", "-T", service, "sh", "-c",
        'exec psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -At -c "$1"', "sh", query], text=True).strip()


def stop(service):
    subprocess.run(compose + ["stop", service], check=True)


def start(service):
    subprocess.run(compose + ["up", "-d", "--no-deps", "--wait", service], check=True)


suffix = str(uuid.uuid4())[:8]
org = api("POST", "/organizations", {"name": "Кухня " + suffix, "phone": "+79991234567"}, 201)
point = api("POST", f"/organizations/{org['id']}/delivery-points", {"name": "Офис", "address": "Адрес",
    "contactName": "Иван", "contactPhone": "+79991234567"}, 201)
dish = api("POST", "/dishes", {"name": "Суп " + suffix, "description": "Проверка кухни",
    "currentPrice": 180, "categoryIds": []}, 201)


def confirmed():
    order = api("POST", "/orders", {"organizationId": org["id"], "deliveryPointId": point["id"],
        "requestedDeliveryAt": "2099-01-01T12:00:00Z", "comment": "Проверка кухни"}, 201)
    order = api("PUT", f"/orders/{order['id']}/lines", {"expectedVersion": order["version"],
        "lines": [{"dishId": dish["id"], "quantity": 2}]})
    for action in ("submit", "confirm"):
        order = api("POST", f"/orders/{order['id']}/{action}", {"expectedVersion": order["version"]})
    return order


order = confirmed()
cancelled = confirmed()
internal()
api("POST", f"/orders/{cancelled['id']}/cancel", {"expectedVersion": cancelled["version"], "reason": "Офис закрыт"})
internal()
assert sql("kitchen-postgres", f"select status from kitchen_task where order_id='{cancelled['id']}'") == "CANCELLED"

# Kitchen is the only public cooking path; its absence cannot mutate Order.
stop("kitchen-service")
try:
    api("POST", f"/orders/{order['id']}/start-cooking", {"expectedVersion": order["version"]}, 503)
    assert api("GET", f"/orders/{order['id']}") == order
finally:
    start("kitchen-service")

# Let registration refresh before injecting the next failure.
api("POST", f"/orders/{order['id']}/start-cooking", {}, 400, retry_unavailable=True)

stop("order-service")
try:
    for attempt in range(8):
        response = api("POST", f"/orders/{order['id']}/start-cooking", {"expectedVersion": order["version"]}, 503)
        assert response["code"] == "DEPENDENCY_UNAVAILABLE"
    result = subprocess.run(compose + ["exec", "-T", "config-service", "wget", "-SO-",
        "http://kitchen-service:8080/internal/v1/kitchen/tasks"], capture_output=True, text=True)
    assert result.returncode != 0 and "503" in result.stderr, result
    assert sql("kitchen-postgres", f"select status from kitchen_task where order_id='{order['id']}'") == "CONFIRMED"
finally:
    start("order-service")

# Every command may meet a transient OPEN/HALF_OPEN circuit after the outage.
# Identical retries are safe; the receipt and history checks below detect duplicates.
cooking = api("POST", f"/orders/{order['id']}/start-cooking",
    {"expectedVersion": order["version"]}, retry_unavailable=True)
with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
    responses = list(pool.map(lambda _: api("POST", f"/orders/{order['id']}/start-cooking",
        {"expectedVersion": order["version"]}, retry_unavailable=True), range(4)))
assert all(response == cooking for response in responses)
ready = api("POST", f"/orders/{order['id']}/mark-ready",
    {"expectedVersion": cooking["version"]}, retry_unavailable=True)
completed = api("POST", f"/orders/{order['id']}/complete",
    {"expectedVersion": ready["version"]}, retry_unavailable=True)
assert api("POST", f"/orders/{order['id']}/start-cooking",
    {"expectedVersion": order["version"]}, retry_unavailable=True) == cooking
assert sql("kitchen-postgres", f"select status from kitchen_task where order_id='{order['id']}'") == "COMPLETED"
assert sql("order-postgres", f"select count(*) from kitchen_command where order_id='{order['id']}'") == "3"
assert len(api("GET", f"/orders/{order['id']}/history", retry_unavailable=True)["items"]) == 5
assert completed["totalAmount"] == 360
print("PASS: Kitchen routing, cancellation, concurrent replay, Order outage and recovery")
print(json.dumps({"orderId": order["id"], "status": completed["status"], "history": 5, "commands": 3}))
