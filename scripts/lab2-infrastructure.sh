#!/usr/bin/env bash
set -euo pipefail

project="${1:?Укажи имя Compose-проекта}"
base_url="${2:-http://localhost:8080}"

for command in docker curl jq python3; do
    command -v "$command" >/dev/null || { echo "Нужен $command" >&2; exit 1; }
done

# Eureka обновляется асинхронно; дождаться маршрута, не создавать данные при каждом повторе.
ready=false
for attempt in {1..60}; do
    if curl -fsS --max-time 3 "$base_url/api/v1/categories?size=1" >/dev/null 2>&1; then
        ready=true
        break
    fi
    sleep 2
done
[[ "$ready" == true ]] || { echo 'Gateway не видит приложение' >&2; exit 1; }

registry="$(docker compose -p "$project" exec -T config-service \
    wget -qO- --header='Accept: application/json' http://discovery-service:8761/eureka/apps)"
for application in ORDER-SERVICE CATALOG-SERVICE KITCHEN-SERVICE GATEWAY-SERVICE; do
    jq -e --arg name "$application" \
        '.applications.application[] | select(.name == $name) | .instance[] | select(.status == "UP")' \
        <<< "$registry" >/dev/null
done

for service in order-service catalog-service kitchen-service gateway-service; do
    docker compose -p "$project" exec -T config-service \
        wget -qO- "http://$service:8080/actuator/info" \
        | jq -e '.configuration.source == "config-service"' >/dev/null
done

docker compose -p "$project" config --format json \
    | jq -e '[.services | to_entries[] | select((.value.ports // []) | length > 0) | .key] == ["gateway-service"]' >/dev/null

for path in /internal/v1/orders /eureka/apps /actuator/env; do
    status="$(curl -sS --max-time 5 -o /dev/null -w '%{http_code}' "$base_url$path")"
    [[ "$status" == 404 ]] || { echo "$path: ожидался 404, получен $status" >&2; exit 1; }
done

curl -fsS --max-time 10 "$base_url/swagger-ui/index.html" >/dev/null
(curl -fsS --max-time 10 "$base_url/v3/api-docs"; curl -fsS --max-time 10 "$base_url/v3/api-docs/catalog"; curl -fsS --max-time 10 "$base_url/v3/api-docs/kitchen") | jq -s -e \
    'all(.[]; .servers[0].url == "/") and ([.[].paths[] | to_entries[] | select(.key | IN("get","post","put","delete","patch"))] | length) == 32' >/dev/null

python3 "$(dirname "$0")/lab2-api-contract.py" "$base_url"
echo 'PASS: Config Server, Eureka, Gateway и Swagger'
"$(dirname "$0")/lab1-defense.sh" "$base_url"
