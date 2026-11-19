#!/usr/bin/env python3
"""Write private local S3 and configuration operator access details."""
import argparse
import os
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--env-file', default='.env')
parser.add_argument('--output', default='ADMIN-ACCESS.local.md')
args = parser.parse_args()
env = dict(line.split('=', 1) for line in Path(args.env_file).read_text().splitlines()
           if line and not line.startswith('#') and '=' in line)
text = '''# Локальный S3 — Silo (ветка MinIO)

Console: http://localhost:{console}
S3 API: http://localhost:{api}
Bucket: `{bucket}`
Login: `{s3_user}`
Password: `{s3_password}`

File Service использует отдельный scoped access key `{access}`.
Его secret находится только в .env. Bucket закрыт; порты S3 доступны только с localhost.

# Runtime-конфигурация

Health: http://localhost:{admin_port}/actuator/health
API base: http://localhost:{admin_port} (служебный REST API, корень / возвращает 404)
Login: `{admin_user}`
Password: `{admin_password}`

Команда после изменения конфигов в Git:
`python3 scripts/refresh-config.py --destination 'file-service:**'`

Применённые значения: Swagger → File → GET /api/v1/files/settings, роль SUPERVISOR.
Готовые запросы и сценарий изменения: postman/README.md.
'''.format(console=env.get('S3_CONSOLE_PORT', '19001'), api=env.get('S3_API_PORT', '19000'),
           bucket=env.get('S3_BUCKET', 'order-files'), s3_user=env['S3_ROOT_USER'],
           s3_password=env['S3_ROOT_PASSWORD'], access=env['S3_ACCESS_KEY'],
           admin_port=env.get('CONFIG_ADMIN_PORT', '18889'), admin_user=env['CONFIG_ADMIN_USER'],
           admin_password=env['CONFIG_ADMIN_PASSWORD'])
path = Path(args.output)
with os.fdopen(os.open(str(path), os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600), 'w') as output:
    output.write(text)
path.chmod(0o600)
print('Private operator access written to ' + str(path))
