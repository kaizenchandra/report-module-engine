#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
set -a
. ./.env.local
DELIVERY_TEST_DATABASE_URL="postgres://delivery:${DELIVERY_DATABASE_PASSWORD}@localhost:5433/delivery?sslmode=disable"
RUN_PROVIDER_TESTS=true
set +a
cd services/delivery-go
go test -race -v ./...
go vet ./...
