#!/usr/bin/env bash
set -euo pipefail
source_pg="${1:?Укажи контейнер старой PostgreSQL}"
target_pg="${2:?Укажи контейнер PostgreSQL заказов}"
catalog_pg="${4:?Укажи контейнер PostgreSQL каталога для сверки dishId}"
backup_path="${3:?Укажи новый файл резервной копии исходной базы}"
[[ ! -e "$backup_path" ]] || { echo 'Файл резервной копии уже существует' >&2; exit 1; }
umask 077
[[ "$source_pg" != "$target_pg" ]] || { echo 'Базы должны быть разными' >&2; exit 1; }
work_dir="$(mktemp -d)"
trap 'rm -rf "$work_dir"' EXIT
psql_in() {
    local container="$1"
    shift
    docker exec -i "$container" sh -c 'exec psql -X -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" "$@"' sh "$@"
}
# Both applications must be stopped; the script deliberately does not stop user containers itself.
for container in "$source_pg" "$target_pg" "$catalog_pg"; do
    sessions="$(psql_in "$container" -Atc "SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() AND pid <> pg_backend_pid() AND backend_type = 'client backend'")"
    [[ "$sessions" == 0 ]] || { echo "Останови приложения, использующие $container" >&2; exit 1; }
done
mkdir -p "$(dirname "$backup_path")"
docker exec "$source_pg" sh -c 'exec pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" --format=custom --no-owner --no-privileges' > "$backup_path"
cat > "$work_dir/manifest.sql" <<'SQL'
SELECT 'organization:' || md5(COALESCE(jsonb_agg(to_jsonb(t) ORDER BY id)::text, '[]')) FROM organization t;
SELECT 'delivery_point:' || md5(COALESCE(jsonb_agg(to_jsonb(t) ORDER BY id)::text, '[]')) FROM delivery_point t;
SELECT 'corporate_order:' || md5(COALESCE(jsonb_agg(to_jsonb(t) ORDER BY id)::text, '[]')) FROM corporate_order t;
SELECT 'order_line:' || md5(COALESCE(jsonb_agg(to_jsonb(t) ORDER BY id)::text, '[]')) FROM order_line t;
SELECT 'order_status_history:' || md5(COALESCE(jsonb_agg(to_jsonb(t) ORDER BY id)::text, '[]')) FROM order_status_history t;
SQL
psql_in "$source_pg" -Atc 'SELECT DISTINCT dish_id FROM order_line ORDER BY dish_id' > "$work_dir/references.txt"
psql_in "$catalog_pg" -Atc 'SELECT id FROM dish ORDER BY id' > "$work_dir/catalog.txt"
python3 - "$work_dir/references.txt" "$work_dir/catalog.txt" <<'CHECK'
import sys
from pathlib import Path
missing = set(Path(sys.argv[1]).read_text().splitlines()) - set(Path(sys.argv[2]).read_text().splitlines())
if missing:
    raise SystemExit("Missing catalog dishes: " + ", ".join(sorted(missing)))
CHECK
psql_in "$source_pg" -At < "$work_dir/manifest.sql" > "$work_dir/source.manifest"
count="$(psql_in "$target_pg" -Atc 'SELECT (SELECT count(*) FROM organization) + (SELECT count(*) FROM delivery_point) + (SELECT count(*) FROM corporate_order) + (SELECT count(*) FROM order_line) + (SELECT count(*) FROM order_status_history)')"
[[ "$count" == 0 ]] || { echo 'Целевая база заказов должна быть пустой; исходная база не изменена' >&2; exit 1; }
docker exec "$source_pg" sh -c 'exec pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" --data-only --no-owner --no-privileges --table=organization --table=delivery_point --table=corporate_order --table=order_line --table=order_status_history' > "$work_dir/orders.sql"
psql_in "$target_pg" --single-transaction < "$work_dir/orders.sql" > /dev/null
psql_in "$target_pg" -At < "$work_dir/manifest.sql" > "$work_dir/target.manifest"
cmp "$work_dir/source.manifest" "$work_dir/target.manifest"
# Verify again under an exclusive lock before removing the old owner.
{
    echo 'BEGIN;'
    echo 'LOCK TABLE organization, delivery_point, corporate_order, order_line, order_status_history IN ACCESS EXCLUSIVE MODE;'
    while IFS=: read -r table digest; do
        [[ "$digest" =~ ^[0-9a-f]{32}$ ]] || exit 1
        case "$table" in
            organization|delivery_point|corporate_order|order_line|order_status_history) ordering=id ;;
            *) exit 1 ;;
        esac
        printf "DO \$\$ BEGIN IF (SELECT md5(COALESCE(jsonb_agg(to_jsonb(t) ORDER BY %s)::text, '[]')) FROM %s t) <> '%s' THEN RAISE EXCEPTION 'Source changed during transfer'; END IF; END \$\$;\n" "$ordering" "$table" "$digest"
    done < "$work_dir/source.manifest"
    echo 'DROP TABLE order_status_history, order_line, corporate_order, delivery_point, organization;'
    echo 'DROP FUNCTION reject_order_status_history_mutation();'
    echo 'COMMIT;'
} | psql_in "$source_pg" > /dev/null
cat "$work_dir/target.manifest"
echo 'PASS: заказы перенесены и сверены; старые таблицы удалены. Можно запускать новую версию.'
