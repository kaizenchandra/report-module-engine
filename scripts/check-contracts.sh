#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
output=$(mktemp)
trap 'rm -f "$output"' EXIT
npx openapi-typescript contracts/openapi.json -o "$output"
cmp "$output" lib/api.generated.ts
