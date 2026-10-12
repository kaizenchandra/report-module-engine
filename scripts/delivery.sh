#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
set -a
. ./.env.local
DELIVERY_DATABASE_URL="postgres://delivery:${DELIVERY_DATABASE_PASSWORD}@localhost:5433/delivery?sslmode=disable"
DELIVERY_TOKEN_URL=http://localhost:8180/realms/reporting/protocol/openid-connect/token
AWS_ACCESS_KEY_ID=local-report-engine
AWS_SECRET_ACCESS_KEY=$S3_LOCAL_SECRET
AWS_REGION=us-east-1
S3_ENDPOINT=http://localhost:9000
S3_BUCKET=report-results
STORAGE_EMULATOR_HOST=http://localhost:4443
GCS_BUCKET=report-results
set +a
cd services/delivery-go
exec go run . "$@"
