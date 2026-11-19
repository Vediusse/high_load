#!/usr/bin/env python3
"""Commit a demo file limit to Git; Postman performs the Kafka refresh separately."""
import argparse
from pathlib import Path
import re
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
mode = parser.add_mutually_exclusive_group(required=True)
mode.add_argument('--max-bytes', type=int)
mode.add_argument('--restore', action='store_true')
parser.add_argument('--repo', type=Path, default=ROOT.parent / 'high_load_configs_lab4')
args = parser.parse_args()
if args.max_bytes is not None and not 2048 <= args.max_bytes <= 10485760:
    parser.error('Demo limit must be between 2048 and 10485760 bytes')
repo = args.repo.resolve()


def git(*arguments):
    result = subprocess.run(['git', *arguments], cwd=repo, capture_output=True, text=True)
    if result.returncode:
        raise SystemExit('Git action failed: ' + arguments[0] + '. Local files were preserved.')
    return result.stdout.strip()


if git('branch', '--show-current') != 'lab4':
    raise SystemExit('Expected branch lab4 in ' + str(repo))
if git('status', '--porcelain'):
    raise SystemExit('Config repository has uncommitted changes; commit them before the demo.')
remote = git('remote', 'get-url', 'origin')
if remote not in ('https://github.com/Vediusse/high_load_configs.git', 'git@github.com:Vediusse/high_load_configs.git'):
    raise SystemExit('Unexpected config repository origin; no changes made.')
path = repo / 'file-service/application.yml'
original = path.read_text()
target = '${FILE_MAX_BYTES:10485760}' if args.restore else str(args.max_bytes)
updated, matches = re.subn(r'^  max-bytes:.*$', '  max-bytes: ' + target, original, flags=re.MULTILINE)
if matches != 1:
    raise SystemExit('Expected exactly one files.max-bytes property; no changes made.')
if updated != original:
    path.write_text(updated)
    git('add', 'file-service/application.yml')
    git('commit', '-m', 'demo: ' + ('restore default file limit' if args.restore else 'set file limit to ' + target))
options = ['-c', 'http.lowSpeedLimit=100', '-c', 'http.lowSpeedTime=20']
if shutil.which('gh'):
    options += ['-c', 'credential.helper=', '-c', 'credential.helper=!gh auth git-credential']
git(*options, 'push', 'https://github.com/Vediusse/high_load_configs.git', 'HEAD:refs/heads/lab4')
value = 10485760 if args.restore else args.max_bytes
print('Git lab4: ' + git('rev-parse', '--short', 'HEAD') + '; target files.max-bytes=' + str(value))
print('Live policy has NOT been refreshed by this command.')
print('Postman: set expectedFileMaxBytes=' + str(value) + ' and Run folder 04 from its first request.')
