#!/usr/bin/env python3
"""Create a private Compose environment without replacing existing credentials."""
import argparse
import base64
import os
from pathlib import Path
import re
import secrets
import subprocess
import tempfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--env-file', default='.env')
parser.add_argument('--project', default='high-load-lab4')
parser.add_argument('--port', type=int, default=8080)
args = parser.parse_args()
path = Path(args.env_file)
if path.exists():
    print('Existing environment preserved: ' + str(path))
    raise SystemExit(0)
if not re.fullmatch(r'[a-z0-9][a-z0-9_-]+', args.project) or not 1 <= args.port <= 65535:
    parser.error('Invalid Compose project or HTTP port')
with tempfile.TemporaryDirectory(prefix='lab4-keys-') as temporary:
    root = Path(temporary)
    pem, private, public = root / 'key.pem', root / 'private.der', root / 'public.der'
    commands = [
        ['openssl', 'genpkey', '-algorithm', 'RSA', '-pkeyopt', 'rsa_keygen_bits:2048', '-out', str(pem)],
        ['openssl', 'pkcs8', '-topk8', '-nocrypt', '-in', str(pem), '-outform', 'DER', '-out', str(private)],
        ['openssl', 'pkey', '-in', str(pem), '-pubout', '-outform', 'DER', '-out', str(public)],
    ]
    for command in commands:
        subprocess.run(command, check=True, capture_output=True)
    encode = lambda data: base64.b64encode(data).decode()
    values = {
        'COMPOSE_PROJECT_NAME': args.project, 'HTTP_PORT': str(args.port),
        'CONFIG_REPO_URI': 'https://github.com/Vediusse/high_load_configs.git', 'CONFIG_REPO_LABEL': 'lab4',
        'KAFKA_CLUSTER_ID': secrets.token_urlsafe(16),
        'ORDER_DB_PASSWORD': secrets.token_urlsafe(32), 'CATALOG_DB_PASSWORD': secrets.token_urlsafe(32),
        'KITCHEN_DB_PASSWORD': secrets.token_urlsafe(32), 'IDENTITY_DB_PASSWORD': secrets.token_urlsafe(32),
        'NOTIFICATION_DB_PASSWORD': secrets.token_urlsafe(32), 'FILE_DB_PASSWORD': secrets.token_urlsafe(32),
        'JWT_SECRET_BASE64': encode(secrets.token_bytes(32)),
        'GATEWAY_EXCHANGE_SECRET': encode(secrets.token_bytes(32)),
        'NOTIFICATION_IDENTITY_SECRET': secrets.token_urlsafe(32),
        'INTERNAL_JWT_PRIVATE_KEY_BASE64': encode(private.read_bytes()),
        'INTERNAL_JWT_PUBLIC_KEY_BASE64': encode(public.read_bytes()),
        'IDENTITY_BOOTSTRAP_LOGIN': 'supervisor', 'IDENTITY_BOOTSTRAP_PASSWORD': secrets.token_urlsafe(24),
        'FILE_MAX_BYTES': '10485760', 'FILE_ALLOWED_MIME_TYPES': 'application/pdf,image/png,image/jpeg',
    }
path.parent.mkdir(parents=True, exist_ok=True)
with os.fdopen(os.open(str(path), os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), 'w') as output:
    output.write('# Private local environment. Never commit this file.\n')
    output.write(''.join(key + '=' + value + '\n' for key, value in values.items()))
print('Created private environment: ' + str(path))
