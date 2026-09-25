DO $$
BEGIN
    IF to_regclass('public.dish') IS NOT NULL THEN
        IF EXISTS (SELECT 1 FROM dish) OR EXISTS (SELECT 1 FROM category) THEN
            RAISE EXCEPTION 'Catalog contains data. Stop applications and run scripts/migrate-catalog.sh before upgrading.';
        END IF;
    END IF;
END $$;
ALTER TABLE order_line DROP CONSTRAINT IF EXISTS fk_order_line_dish;
DROP TABLE IF EXISTS dish_category;
DROP TABLE IF EXISTS dish;
DROP TABLE IF EXISTS category;
