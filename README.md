# Проект по курсу «Высокопроизводительные системы»

Актуальные документы:

- [Артефакты для преподавателя](./Артефакты/README.md)
- [Инкрементальная планировка](./Планировка/README.md)

Файл [business-domain-options.md](./business-domain-options.md) сохранён как история первоначального выбора. Актуальная модель — сервис корпоративного питания для собственной кухни с постоянным меню и ручным подтверждением заказа.

Исходное ТЗ прочитано из `/Users/rublevvalerii/high_load.md`.

## Текущая версия

Лабораторная 1 содержит рабочие справочники и полный жизненный цикл корпоративного заказа:

- CRUD и логическая деактивация организаций и точек выдачи;
- CRUD категорий с запретом удаления используемой категории;
- CRUD и логическая деактивация блюд, связь блюда с несколькими категориями;
- активное постоянное меню с cursor pagination без общего количества;
- page pagination остальных списков с пределом 50;
- единый JSON-контракт ошибок с `code`, `message`, `fieldErrors` и `traceId`;
- создание и изменение заказа в `DRAFT`, атомарная полная замена его позиций;
- проверка активной организации, принадлежности и активности точки выдачи, активных блюд, количества и отсутствия дублей;
- отправка отдельной командой `submit` с повторным чтением цен, снимками названия/цены, итогом и записью истории `DRAFT → SUBMITTED`;
- optimistic locking через обязательный `expectedVersion` и `409 ORDER_VERSION_CONFLICT`;
- отдельные команды подтверждения и отклонения всего заказа; причина отклонения обязательна;
- отмена с обязательной причиной из `DRAFT`, `SUBMITTED` и `CONFIRMED`, но не после начала приготовления;
- последовательный процесс `CONFIRMED → IN_COOKING → READY → COMPLETED`;
- неизменяемая история каждого перехода с пагинацией;
- page pagination заказов по статусу и организации с `X-Total-Count`.

Универсального изменения статуса нет: каждый переход выражен отдельной бизнес-командой. Автор перехода (`changedBy`) до появления аутентифицированных пользователей остаётся `null`; передавать его со слов клиента API нельзя.

### Локальная проверка

Требуются Java 21+ и работающий Docker daemon:

```bash
./mvnw clean verify
docker compose config
docker compose up --build
```

После запуска доступны:

- health: <http://localhost:8080/actuator/health>;
- readiness: <http://localhost:8080/actuator/health/readiness>;
- OpenAPI JSON: <http://localhost:8080/v3/api-docs>;
- Swagger UI: <http://localhost:8080/swagger-ui/index.html>.

Полный сценарий защиты на изолированной чистой БД описан в [docs/lab1-defense.md](./docs/lab1-defense.md). После запуска окружения его можно выполнить одной командой:

```bash
./scripts/lab1-defense.sh
```

REST API использует префикс `/api/v1`:

- `/organizations` и `/organizations/{id}/delivery-points`;
- `/delivery-points/{id}`;
- `/categories`;
- `/dishes?afterId=&limit=&categoryId=`;
- `/orders?page=&size=&status=&organizationId=`;
- `/orders/{id}/details`, `/orders/{id}/lines` и `/orders/{id}/submit`;
- `/orders/{id}/confirm`, `/orders/{id}/reject`, `/orders/{id}/cancel`;
- `/orders/{id}/start-cooking`, `/orders/{id}/mark-ready`, `/orders/{id}/complete`;
- `/orders/{id}/history?page=&size=`.

Телефоны принимаются в нормализованном E.164, например `+79991234567`. Время заказа передаётся как ISO 8601 с обязательным offset. Размер `size`/`limit` — от 1 до 50. `GET /dishes` возвращает `items`, `nextCursor`, `hasNext` и не выполняет total count; списки организаций и заказов передают total в `X-Total-Count`. Каждая команда изменения существующего заказа принимает актуальный `expectedVersion` из предыдущего ответа.

Остановить окружение без удаления данных:

```bash
docker compose down
```
