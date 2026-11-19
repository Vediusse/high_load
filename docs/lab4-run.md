# Запуск и защита лабораторной 4

## Первое окружение

```bash
python3 scripts/setup-env.py
python3 scripts/write-admin-access.py
docker compose up -d --build --wait --wait-timeout 600
```

Секреты и имя Compose-проекта находятся в `.env`; файл не публикуется. Bootstrap-супервизор создаётся только при пустой Identity DB. Команда `python3 scripts/seed-demo.py --env-file .env` создаёт демонстрационные роли и отправленный заказ через API, записывая доступы в игнорируемый `CREDENTIALS.local.md`. Для ручного бизнес-сценария супервизор создаёт пользователей с ролями `CLIENT_MANAGER`, `KITCHEN_MANAGER`, `ORGANIZATION_REPRESENTATIVE` (последнему нужна активная организация).

Все команды ниже выполняются из корня нужного checkout. Для другого порта smoke-сценариям передаётся `--base-url http://localhost:PORT`; файл окружения задаётся через `--env-file`.

## Локальная Git-конфигурация

Если Docker не может обратиться к GitHub, используется полноценная Git-копия той же ветки. Это Git backend Config Server; свойства не включаются в JAR и не переключаются на native profile.

```bash
git clone --branch lab4 https://github.com/Vediusse/high_load_configs.git ../high_load_configs_lab4
```

Добавить в `.env` абсолютный путь `LOCAL_CONFIG_PATH` к этой копии. Нужен обычный clone с каталогом `.git`, а не Git worktree с `.git`-файлом.

```bash
docker compose -f compose.yaml -f compose.local-config.yaml up -d --build --wait --wait-timeout 600
```

Для обычной команды `docker compose up` можно создать локальную ссылку `compose.override.yaml` на `compose.local-config.yaml`; этот файл игнорируется Git. Host-копия монтируется read-only. Config Server делает временную Git-копию без origin, чтобы локальный backend не пытался выполнять SSH fetch. После изменения настроек нужен commit в config-repo и пересоздание Config Server, чтобы обновить временную копию; runtime-политику File затем обновляет `scripts/refresh-config.py`. В обычном режиме HTTPS GitHub пересоздавать Config Server не нужно.

## Автотесты

```bash
DOCKER_AUTH_CONFIG='{"auths":{}}' JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw clean verify
```

Весь reactor включает unit, `*IT`, PostgreSQL/Testcontainers, настоящий S3 Silo и трёхброкерный Kafka-кластер, проверку полного перезапуска/отказа брокера, ArchUnit и JaCoCo ≥70%. На Linux с Java 21 достаточно `./mvnw clean verify`.

## Сценарии текущего Compose

```bash
python3 scripts/lab4-notifications-smoke.py --env-file .env
python3 scripts/lab4-files-smoke.py --env-file .env
python3 scripts/lab4-outbox-smoke.py --env-file .env --failure-tests
```

Они используют текущий bootstrap login/password из `.env`, создают отдельных тестовых пользователей и бизнес-данные. Запускать последовательно: отказные проверки намеренно меняют доступность сервисов. Старые проверки ролей/JWT и бизнес-цикла сохранены в `scripts/lab3-authentication.py`, `scripts/lab1-defense.sh`, `scripts/lab2-api-contract.py`; им требуется токен или окружение соответствующей версии.

## Kafka и восстановление

```bash
docker compose exec kafka-1 /opt/kafka/bin/kafka-metadata-quorum.sh --bootstrap-server kafka-1:9092 describe --status
docker compose exec kafka-1 /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka-1:9092 --describe
docker compose exec kafka-1 /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server kafka-1:9092 --describe --group notification-v1
```

Основные топики: `order-events`, `production-events`, `file-events`; каждый — 3 partition, RF=3, min ISR=2. DLT имеют суффикс `.DLT` и такую же репликацию. `runtime-config-events` с теми же параметрами используется Spring Cloud Bus. Groups: `notification-v1`, `kitchen-projection-v1`, `order-file-references-v1`; Bus использует отдельную подписку каждого экземпляра.

Compose передаёт приложениям и брокерам `infra/jvm/network.security` через `java.security.properties`. DNS-кэш JVM отключён: при перезапуске Docker может назначить прежний IP другому broker ID, поэтому новое соединение должно заново разрешить имя сервиса. Файл дополняет стандартные security properties JDK. Основание: [настройка security properties Java 21](https://docs.oracle.com/en/java/javase/21/security/security-properties-file.html) и [DNS-поведение Kafka client](https://kafka.apache.org/39/generated/consumer_config.html).

Проверить отказ одного брокера и затем восстановить:

```bash
docker compose stop kafka-3
# Продолжить submit/confirm/ready/upload через Swagger.
docker compose up -d --no-deps --wait kafka-3
```

Остановка и восстановление уведомлений:

```bash
docker compose stop notification-service
# Выполнить бизнес-команды; API заказов продолжает работать.
docker compose up -d --no-deps --wait notification-service
```

При полной недоступности Kafka успешная команда сохраняет outbox, а relay повторяет отправку с ограниченными ожиданиями и задержкой. При восстановлении группы автоматически продолжают чтение. `processed_event` защищает от повторного эффекта.

Прочитать outbox без раскрытия payload:

```bash
docker compose exec order-postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "select event_id,aggregate_id,topic,sent_at,attempts,last_error from outbox_event order by sequence desc limit 20"'
```

Неверные сообщения после ограниченных попыток попадают в DLT; исходный offset не подтверждается при неудачной публикации DLT. DLT не реплеится автоматически: сообщение разбирается и при необходимости исправляется. Для повторной отправки сохранить исходные `eventId`, `aggregateId`, version и соответствие topic/eventType; key должен равняться `aggregateId`. Пример отправки проверенного JSON из файла:

```bash
# event.json содержит один проверенный envelope; ORDER_ID совпадает с aggregateId.
printf '%s|%s\n' "$ORDER_ID" "$(cat event.json)" | docker compose exec -T kafka-1 \
  /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server kafka-1:9092 \
  --topic order-events --sync --property parse.key=true --property 'key.separator=|' \
  --producer-property acks=all
```

## Сценарий через Swagger

Текущий локальный стенд: `http://localhost:18084/swagger-ui/index.html`. Доступы и готовые ID находятся в локальном `CREDENTIALS.local.md`. Для свежего набора демоданных:

```bash
python3 scripts/seed-demo.py --env-file .env --base-url http://localhost:18084
```

1. В Identity выполнить `POST /api/v1/auth/login` с логином/паролем **KITCHEN_MANAGER**, вставить `accessToken` в Authorize. Супервизор предназначен для управления пользователями; бизнес-сценарий выполняют бизнес-роли.
2. В Order получить `GET /api/v1/orders/{id}` для demo order. Он создан в `SUBMITTED`. Выполнить `POST /orders/{id}/confirm` с телом `{"expectedVersion": <текущая version>}`. В каждом следующем переходе брать новую `version` из ответа.
3. Войти как **ORGANIZATION_REPRESENTATIVE**. В Notification вызвать `GET /api/v1/notifications`: после асинхронной доставки появится `ORDER_CONFIRMED`. `POST /notifications/{notificationId}/read` отметить прочитанным; повтор не меняет время отметки.
4. В File выполнить `POST /api/v1/files`, multipart: `orderId` из файла доступов, `relationType=SPECIFICATION`, настоящий PDF/PNG/JPEG до 10 MiB. Сохранить `id` ответа. `GET /files/{id}` скачивает содержимое; `GET /orders/{id}/files` показывает метаданные; Order `/orders/{id}/file-references` показывает событийную ссылку после доставки.
5. Войти как **KITCHEN_MANAGER**, в Kitchen выполнить `POST /orders/{id}/start-cooking`, затем `/mark-ready`, каждый раз с актуальной `expectedVersion`. Представитель увидит `ORDER_READY` в своей ленте. Теперь разрешена загрузка `DISCREPANCY_ACT`.
6. Под логином пользователя, загрузившего спецификацию, вызвать `DELETE /files/{fileId}`. Повтор даст `204`, последующее скачивание — `404`. Физические bytes сохраняются, ссылка Order скрывается после события удаления.
7. Проверить запреты: устаревшая `expectedVersion` → `409`; бизнес-запрос супервизора без бизнес-роли → `403`; акт до `READY` → `422`; запрещённый MIME → `415`; слишком большой файл → `413`. Удалять файл может только загрузивший его пользователь.

Переходы в этих шагах используют префикс `/api/v1`; Swagger подставляет его сам. При смене роли заменить токен в Authorize. Для проверки чужой организации и всей матрицы файлов/уведомлений предусмотрены последовательные `lab4-files-smoke.py` и `lab4-notifications-smoke.py`: они сами создают вторую организацию и нужные тестовые роли.

Полная проверка недоступности Kafka, backlog Notification и повторов:

```bash
python3 scripts/lab4-outbox-smoke.py --env-file .env --base-url http://localhost:18084 --failure-tests
```

## Сохранение данных

Файлы: PostgreSQL хранит метаданные, локальный S3 — содержимое. Консоль, доступы,
SQL и сценарий изменения runtime-настроек: [docs/lab4-s3-runtime.md](lab4-s3-runtime.md).

`docker compose stop` и `docker compose down` сохраняют именованные volumes. Не использовать `down -v` для рабочего окружения: этот вариант удаляет базы, Kafka и файлы. Чистые проверочные окружения создаются с отдельными project name, портом и секретами.
