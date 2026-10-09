#!/usr/bin/env python3
"""Compare public HTTP contracts after splitting their OpenAPI documents."""
import json
import os
import sys
from copy import deepcopy
import urllib.request
from pathlib import Path

base_url = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080"
paths, schemas = {}, {}
for suffix in ("", "/catalog", "/kitchen"):
    request = urllib.request.Request(base_url + "/v3/api-docs" + suffix)
    if os.environ.get("AUTH_TOKEN"):
        request.add_header("Authorization", "Bearer " + os.environ["AUTH_TOKEN"])
    with urllib.request.urlopen(request, timeout=15) as response:
        document = json.load(response)
    assert document["servers"][0]["url"] == "/"
    for path, operations in document["paths"].items():
        assert path not in paths, f"Два владельца маршрута {path}"
        paths[path] = operations
    for name, schema in document["components"]["schemas"].items():
        assert name not in schemas or schemas[name] == schema, f"Конфликт DTO {name}"
        schemas[name] = schema

baseline_file = Path(__file__).resolve().parent.parent / "docs/baseline/openapi.json"
baseline = json.loads(baseline_file.read_text())
expected_schemas = deepcopy(baseline["components"]["schemas"])
# Lab 3 derives organizationId from the authenticated representative. It is still
# required for a client manager, but that conditional requirement cannot be
# represented by OpenAPI's static `required` list.
expected_schemas["CreateOrderRequest"]["required"].remove("organizationId")
assert paths.keys() == baseline["paths"].keys(), "Изменились публичные маршруты"
assert schemas == expected_schemas, "Изменились DTO"
for path, operations in baseline["paths"].items():
    assert paths[path].keys() == operations.keys(), path
    for method, operation in operations.items():
        # operationId is unique within one document; it changes when controllers split.
        for field in ("requestBody", "parameters"):
            assert paths[path][method].get(field) == operation.get(field), (path, method, field)
        responses = paths[path][method]["responses"]
        for code, response in operation["responses"].items():
            assert responses.get(code) == response, (path, method, code)
        assert responses.keys() - operation["responses"].keys() <= {"503"}, (path, method, "new response")
        if "503" in responses:
            assert responses["503"]["content"]["application/json"]["schema"]["$ref"] == "#/components/schemas/ApiError"
print("PASS: публичные операции, параметры, DTO и ответы совпадают с первой лабой")
