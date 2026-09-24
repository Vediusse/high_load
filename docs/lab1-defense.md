# Воспроизводимая защита лабораторной 1

Сценарий выполняется на чистой PostgreSQL только через публичный REST API. SQL используется отдельно лишь для показа служебной истории Flyway.

## 1. Чистый запуск

Чтобы не затронуть обычный volume разработчика и гарантированно получить новую БД, создай уникальное имя Compose-проекта для одного прогона:

```bash
export LAB1_COMPOSE_PROJECT="high-load-l1-acceptance-$(date +%Y%m%d%H%M%S)"
docker compose -p "$LAB1_COMPOSE_PROJECT" up --build -d --wait
```

Проверить контейнеры и историю миграций:

```bash
docker compose -p "$LAB1_COMPOSE_PROJECT" ps
docker compose -p "$LAB1_COMPOSE_PROJECT" exec -T postgres \
  psql -U catering -d catering \
  -c 'SELECT installed_rank, version, description, success FROM flyway_schema_history ORDER BY installed_rank;'
```

В истории должны быть успешные версии `1`–`4`. Swagger UI: <http://localhost:8080/swagger-ui/index.html>.

## 2. Сквозной сценарий

Требуются `curl` и `jq`:

```bash
./scripts/lab1-defense.sh
```

Скрипт наблюдаемо проверяет:

- readiness приложения;
- создание организации «Альфа» и точки «Главный офис»;
- три категории и блюда стоимостью 180, 320 и 140 ₽;
- две cursor-страницы меню без `total`;
- заказ из 25 борщей, 30 котлет и 20 салатов;
- итог `16 900`, снимки названий и цен;
- путь `DRAFT → SUBMITTED → CONFIRMED → IN_COOKING → READY → COMPLETED`;
- пять неизменяемых записей истории;
- список заказов и заголовок `X-Total-Count`;
- ошибки `400`, `409`, `422` и корреляцию через `X-Trace-Id`.

## 3. Тесты и покрытие

```bash
./mvnw -B -ntp clean verify
```

HTML-отчёт покрытия появится в `target/site/jacoco/index.html`. Сборка завершится ошибкой, если line coverage ниже 70%.

## 4. Остановка

Обычная остановка сохраняет отдельный проверочный volume:

```bash
docker compose -p "$LAB1_COMPOSE_PROJECT" down
```

Удалять volume командой `down -v` следует только когда демонстрационные данные больше не нужны.
