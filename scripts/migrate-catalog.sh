#!/usr/bin/env bash
set -euo pipefail
source_pg="${1:?Укажи контейнер старой PostgreSQL}"
target_pg="${2:?Укажи контейнер PostgreSQL каталога}"
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
for container in "$source_pg" "$target_pg"; do
    sessions="$(psql_in "$container" -Atc "SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() AND pid <> pg_backend_pid() AND backend_type = 'client backend'")"
    [[ "$sessions" == 0 ]] || { echo "Останови приложения, использующие $container" >&2; exit 1; }
done
mkdir -p "$(dirname "$backup_path")"
docker exec "$source_pg" sh -c 'exec pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" --format=custom --no-owner --no-privileges' > "$backup_path"
cat > "$work_dir/manifest.sql" <<'SQL'
SELECT 'category:' || md5(COALESCE(jsonb_agg(to_jsonb(t) ORDER BY id)::text, '[]')) FROM category t;
SELECT 'dish:' || md5(COALESCE(jsonb_agg(to_jsonb(t) ORDER BY id)::text, '[]')) FROM dish t;
SELECT 'dish_category:' || md5(COALESCE(jsonb_agg(to_jsonb(t) ORDER BY dish_id, category_id)::text, '[]')) FROM dish_category t;
SQL
psql_in "$source_pg" -At < "$work_dir/manifest.sql" > "$work_dir/source.manifest"
count="$(psql_in "$target_pg" -Atc 'SELECT (SELECT count(*) FROM dish) + (SELECT count(*) FROM category) + (SELECT count(*) FROM dish_category)')"
[[ "$count" == 0 ]] || { echo 'Целевой каталог должен быть пустым; исходная база не изменена' >&2; exit 1; }
docker exec "$source_pg" sh -c 'exec pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" --data-only --no-owner --no-privileges --table=category --table=dish --table=dish_category' > "$work_dir/catalog.sql"
psql_in "$target_pg" --single-transaction < "$work_dir/catalog.sql" > /dev/null
psql_in "$target_pg" -At < "$work_dir/manifest.sql" > "$work_dir/target.manifest"
cmp "$work_dir/source.manifest" "$work_dir/target.manifest"
# Verify again under an exclusive lock before removing the old owner.
{
    echo 'BEGIN;'
    echo 'LOCK TABLE category, dish, dish_category, order_line IN ACCESS EXCLUSIVE MODE;'
    while IFS=: read -r table digest; do
        [[ "$digest" =~ ^[0-9a-f]{32}$ ]] || exit 1
        case "$table" in
            category|dish) ordering=id ;;
            dish_category) ordering='dish_id, category_id' ;;
            *) exit 1 ;;
        esac
        printf "DO \$\$ BEGIN IF (SELECT md5(COALESCE(jsonb_agg(to_jsonb(t) ORDER BY %s)::text, '[]')) FROM %s t) <> '%s' THEN RAISE EXCEPTION 'Source changed during transfer'; END IF; END \$\$;\n" "$ordering" "$table" "$digest"
    done < "$work_dir/source.manifest"
    echo 'ALTER TABLE order_line DROP CONSTRAINT IF EXISTS fk_order_line_dish;'
    echo 'DROP TABLE dish_category, dish, category;'
    echo 'COMMIT;'
} | psql_in "$source_pg" > /dev/null
cat "$work_dir/target.manifest"
echo 'PASS: каталог перенесён и сверен; старые таблицы удалены. Можно запускать новую версию.'
