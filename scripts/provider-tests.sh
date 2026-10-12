#!/bin/sh
set -eu
cd "$(dirname "$0")/../services/delivery-go"
RUN_PROVIDER_TESTS=true go test -race -run TestProviderContracts -v ./...
