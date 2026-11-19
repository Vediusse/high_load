#!/usr/bin/env python3
"""Create a private Postman environment from the existing local .env."""
import argparse
import json
import os
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--env-file', type=Path, default=ROOT / '.env')
parser.add_argument('--output', type=Path, default=ROOT / 'postman/lab4.local.postman_environment.json')
args = parser.parse_args()
env = dict(line.split('=', 1) for line in args.env_file.read_text().splitlines()
           if line and not line.startswith('#') and '=' in line)
data = json.loads((ROOT / 'postman/lab4.postman_environment.json').read_text())
mapping = {
    'baseUrl': 'http://localhost:' + env.get('HTTP_PORT', '8080'),
    'configAdminUrl': 'http://localhost:' + env.get('CONFIG_ADMIN_PORT', '18889'),
    'supervisorLogin': env['IDENTITY_BOOTSTRAP_LOGIN'], 'supervisorPassword': env['IDENTITY_BOOTSTRAP_PASSWORD'],
    'configAdminLogin': env['CONFIG_ADMIN_USER'], 'configAdminPassword': env['CONFIG_ADMIN_PASSWORD'],
}
for entry in data['values']:
    if entry['key'] in mapping:
        entry['value'] = mapping[entry['key']]
data['name'] = 'Lab4 — local (private)'
args.output.parent.mkdir(parents=True, exist_ok=True)
with os.fdopen(os.open(str(args.output), os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600), 'w') as output:
    json.dump(data, output, ensure_ascii=False, indent=2)
    output.write('\n')
args.output.chmod(0o600)
print('Private environment written: ' + str(args.output))
