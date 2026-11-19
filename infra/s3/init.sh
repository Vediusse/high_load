#!/bin/sh
set -eu
case "$S3_BUCKET" in ''|*[!a-z0-9.-]*) echo 'Invalid bucket name' >&2; exit 1;; esac
mcli alias set local http://s3-storage:9000 "$S3_ROOT_USER" "$S3_ROOT_PASSWORD" >/dev/null
mcli mb --ignore-existing "local/$S3_BUCKET"
mcli anonymous set none "local/$S3_BUCKET"
cat > /tmp/file-service-policy.json <<EOF
{
  "Version": "2012-10-17",
  "Statement": [
    {"Effect":"Allow","Action":["s3:GetBucketLocation","s3:ListBucket"],"Resource":["arn:aws:s3:::$S3_BUCKET"]},
    {"Effect":"Allow","Action":["s3:GetObject","s3:PutObject","s3:DeleteObject"],"Resource":["arn:aws:s3:::$S3_BUCKET/documents/*"]}
  ]
}
EOF
mcli admin user add local "$S3_ACCESS_KEY" "$S3_SECRET_KEY" >/dev/null
mcli admin policy create local file-service /tmp/file-service-policy.json >/dev/null
mcli admin policy attach local file-service --user "$S3_ACCESS_KEY" >/dev/null
echo 'Private S3 bucket and scoped File Service account ready'
