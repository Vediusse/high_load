#!/usr/bin/env bash
set -euo pipefail

base_url="${1:-http://localhost:8080}"
tmp_dir="$(mktemp -d)"
response_body=""
response_headers=""
response_status=""
trap 'rm -rf "${tmp_dir}"' EXIT

for command in curl jq; do
    if ! command -v "${command}" >/dev/null 2>&1; then
        echo "ERROR: для демонстрации требуется ${command}" >&2
        exit 1
    fi
done

fail() {
    echo "ERROR: $*" >&2
    if [[ -n "${response_body}" && -f "${response_body}" ]]; then
        cat "${response_body}" >&2
        echo >&2
    fi
    exit 1
}

call_api() {
    local method="$1"
    local path="$2"
    local expected_status="$3"
    local payload="${4:-}"
    response_body="${tmp_dir}/body.json"
    response_headers="${tmp_dir}/headers.txt"

    local curl_args=(
        --silent --show-error
        --request "${method}"
        --dump-header "${response_headers}"
        --output "${response_body}"
        --write-out '%{http_code}'
        --header 'Accept: application/json'
    )
    if [[ -n "${payload}" ]]; then
        curl_args+=(--header 'Content-Type: application/json' --data "${payload}")
    fi

    response_status="$(curl "${curl_args[@]}" "${base_url}${path}")"
    if [[ "${response_status}" != "${expected_status}" ]]; then
        fail "${method} ${path}: ожидался HTTP ${expected_status}, получен ${response_status}"
    fi
}

json_value() {
    jq -er "$1" "${response_body}" || fail "в ответе нет ожидаемого значения: $1"
}

assert_json() {
    local expression="$1"
    local message="$2"
    jq -e "${expression}" "${response_body}" >/dev/null || fail "${message}"
}

header_value() {
    local header_name="$1"
    awk -F ': *' -v expected="${header_name}" '
        tolower($1) == tolower(expected) { gsub("\r", "", $2); print $2 }
    ' "${response_headers}" | tail -n 1
}

unique_suffix="$(date -u +%Y%m%d%H%M%S)"

call_api GET /actuator/health/readiness 200
assert_json '.status == "UP"' "readiness должен быть UP"

organization_payload="$(jq -cn --arg suffix "${unique_suffix}" '{name: ("Альфа " + $suffix), phone: "+79991234567"}')"
call_api POST /api/v1/organizations 201 "${organization_payload}"
organization_id="$(json_value '.id')"

point_payload='{"name":"Главный офис","address":"Кронверкский проспект, 49","contactName":"Анна Смирнова","contactPhone":"+79997654321"}'
call_api POST "/api/v1/organizations/${organization_id}/delivery-points" 201 "${point_payload}"
delivery_point_id="$(json_value '.id')"

category_ids=()
for category_name in "Супы" "Основные блюда" "Салаты"; do
    category_payload="$(jq -cn --arg name "${category_name} ${unique_suffix}" '{name: $name}')"
    call_api POST /api/v1/categories 201 "${category_payload}"
    category_ids+=("$(json_value '.id')")
done

dish_ids=()
dish_names=("Борщ" "Куриная котлета" "Салат")
dish_prices=("180.00" "320.00" "140.00")
for index in 0 1 2; do
    dish_payload="$(jq -cn \
        --arg name "${dish_names[$index]} ${unique_suffix}" \
        --arg description "Демонстрационное блюдо" \
        --arg price "${dish_prices[$index]}" \
        --arg category_id "${category_ids[$index]}" \
        '{name: $name, description: $description, currentPrice: ($price | tonumber), categoryIds: [$category_id]}')"
    call_api POST /api/v1/dishes 201 "${dish_payload}"
    dish_ids+=("$(json_value '.id')")
done

call_api GET '/api/v1/dishes?limit=2' 200
assert_json '.items | length == 2' "первая cursor-страница должна содержать два блюда"
assert_json '.hasNext == true and (.nextCursor | type == "string")' "первая cursor-страница должна иметь продолжение"
assert_json 'has("total") | not' "cursor pagination не должна содержать total"
next_cursor="$(json_value '.nextCursor')"

call_api GET "/api/v1/dishes?afterId=${next_cursor}&limit=2" 200
assert_json '.items | length >= 1' "вторая cursor-страница должна содержать данные"

order_payload="$(jq -cn \
    --arg organization_id "${organization_id}" \
    --arg delivery_point_id "${delivery_point_id}" \
    '{organizationId: $organization_id, deliveryPointId: $delivery_point_id, requestedDeliveryAt: "2099-01-01T12:00:00Z", comment: "Демонстрационный заказ"}')"
call_api POST /api/v1/orders 201 "${order_payload}"
order_id="$(json_value '.id')"
version="$(json_value '.version')"

lines_payload="$(jq -cn \
    --argjson version "${version}" \
    --arg dish_1 "${dish_ids[0]}" \
    --arg dish_2 "${dish_ids[1]}" \
    --arg dish_3 "${dish_ids[2]}" \
    '{expectedVersion: $version, lines: [
        {dishId: $dish_1, quantity: 25},
        {dishId: $dish_2, quantity: 30},
        {dishId: $dish_3, quantity: 20}
    ]}')"
call_api PUT "/api/v1/orders/${order_id}/lines" 200 "${lines_payload}"
assert_json '.status == "DRAFT" and .totalAmount == 16900 and (.lines | length == 3)' \
    "черновик должен содержать три позиции на сумму 16 900"
version="$(json_value '.version')"

call_api POST "/api/v1/orders/${order_id}/submit" 200 "$(jq -cn --argjson version "${version}" '{expectedVersion: $version}')"
assert_json '.status == "SUBMITTED" and .totalAmount == 16900' "отправленный заказ должен зафиксировать сумму 16 900"
assert_json '[.lines[] | select(.dishNameSnapshot == null or .unitPriceSnapshot == null)] | length == 0' \
    "при отправке каждая позиция должна получить снимок названия и цены"
version="$(json_value '.version')"

for transition in 'confirm:CONFIRMED' 'start-cooking:IN_COOKING' 'mark-ready:READY' 'complete:COMPLETED'; do
    command_name="${transition%%:*}"
    expected_status="${transition##*:}"
    call_api POST "/api/v1/orders/${order_id}/${command_name}" 200 \
        "$(jq -cn --argjson version "${version}" '{expectedVersion: $version}')"
    assert_json ".status == \"${expected_status}\"" "команда ${command_name} должна дать статус ${expected_status}"
    version="$(json_value '.version')"
done

call_api GET "/api/v1/orders/${order_id}/history?page=0&size=20" 200
assert_json '(.items | length) == 5 and [.items[].toStatus] == ["SUBMITTED","CONFIRMED","IN_COOKING","READY","COMPLETED"]' \
    "история должна содержать пять переходов в хронологическом порядке"

call_api GET "/api/v1/orders?page=0&size=20&organizationId=${organization_id}" 200
total_count="$(header_value X-Total-Count)"
[[ "${total_count}" == "1" ]] || fail "X-Total-Count должен быть равен 1, получено: ${total_count:-<пусто>}"
assert_json '.items[0].status == "COMPLETED"' "список должен вернуть завершённый заказ"

call_api GET '/api/v1/orders?size=51' 400
assert_json '.code == "VALIDATION_FAILED" and .fieldErrors[0].field == "size"' \
    "size=51 должен вернуть структурированную ошибку валидации"

response_body="${tmp_dir}/trace-body.json"
response_headers="${tmp_dir}/trace-headers.txt"
response_status="$(curl --silent --show-error \
    --request POST \
    --header 'Accept: application/json' \
    --header 'Content-Type: application/json' \
    --header 'X-Trace-Id: lab1-defense-validation' \
    --data '{"name":" ","phone":"8999"}' \
    --dump-header "${response_headers}" \
    --output "${response_body}" \
    --write-out '%{http_code}' \
    "${base_url}/api/v1/organizations")"
[[ "${response_status}" == "400" ]] || fail "невалидная организация должна вернуть HTTP 400"
assert_json '.code == "VALIDATION_FAILED" and .traceId == "lab1-defense-validation"' \
    "тело ошибки должно содержать переданный traceId"
[[ "$(header_value X-Trace-Id)" == "lab1-defense-validation" ]] || fail "ответ должен вернуть X-Trace-Id"

call_api POST "/api/v1/orders/${order_id}/confirm" 409 \
    "$(jq -cn --argjson version "${version}" '{expectedVersion: $version}')"
assert_json '.code == "ORDER_STATUS_CONFLICT"' "повторное подтверждение завершённого заказа должно дать конфликт статуса"

call_api DELETE "/api/v1/categories/${category_ids[0]}" 422
assert_json '.code == "CATEGORY_IN_USE"' "используемую категорию нельзя удалить"

echo "PASS: сценарий лабораторной 1 завершён"
echo "orderId=${order_id} total=16900 status=COMPLETED history=5 X-Total-Count=${total_count}"
