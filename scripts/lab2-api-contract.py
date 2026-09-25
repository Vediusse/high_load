#!/usr/bin/env python3
"""Compare public HTTP contracts after splitting their OpenAPI documents."""
import json
import sys
import urllib.request
from pathlib import Path

base_url = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080"
paths, schemas = {}, {}
for suffix in ("", "/catalog"):
    with urllib.request.urlopen(base_url + "/v3/api-docs" + suffix, timeout=15) as response:
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
assert paths.keys() == baseline["paths"].keys(), "Изменились публичные маршруты"
assert schemas == baseline["components"]["schemas"], "Изменились DTO"
for path, operations in baseline["paths"].items():
    assert paths[path].keys() == operations.keys(), path
    for method, operation in operations.items():
        # operationId is unique within one document; it changes when controllers split.
        for field in ("requestBody", "responses", "parameters"):
            assert paths[path][method].get(field) == operation.get(field), (path, method, field)
print("PASS: публичные операции, параметры, DTO и ответы совпадают с первой лабой")
