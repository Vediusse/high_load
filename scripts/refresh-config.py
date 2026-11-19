#!/usr/bin/env python3
"""Publish a local Spring Cloud Bus refresh after committing configuration to Git."""
import argparse
import base64
from pathlib import Path
import urllib.error
import urllib.parse
import urllib.request

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--env-file', default='.env')
parser.add_argument('--destination', default='file-service:**')
args = parser.parse_args()
env = dict(line.split('=', 1) for line in Path(args.env_file).read_text().splitlines()
           if line and not line.startswith('#') and '=' in line)
destination = urllib.parse.quote(args.destination, safe=':*')
url = 'http://127.0.0.1:' + str(int(env.get('CONFIG_ADMIN_PORT', '18889'))) + '/actuator/busrefresh/' + destination
authorization = base64.b64encode((env['CONFIG_ADMIN_USER'] + ':' + env['CONFIG_ADMIN_PASSWORD']).encode()).decode()
request = urllib.request.Request(url, method='POST', data=b'', headers={
    'Authorization': 'Basic ' + authorization, 'Content-Type': 'application/json'})
try:
    with urllib.request.urlopen(request, timeout=20) as response:
        assert response.status in (200, 202, 204)
except urllib.error.HTTPError as error:
    raise SystemExit('Refresh rejected: HTTP ' + str(error.code))
print('Refresh published to local Kafka for ' + args.destination + '; check /api/v1/files/settings as SUPERVISOR')
