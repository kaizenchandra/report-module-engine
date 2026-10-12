#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
umask 077
if [ -f .env.local ]; then
  if ! grep -q '^REPORT_ADMIN_TEST_PASSWORD=' .env.local; then
    printf '\nREPORT_ADMIN_TEST_PASSWORD=%s\n' "$(openssl rand -hex 24)" >> .env.local
  fi
  if ! grep -q '^REPORT_ALLOW_EMULATOR_LINKS=' .env.local; then
    printf '\nREPORT_ALLOW_EMULATOR_LINKS=true\n' >> .env.local
  fi
  if ! grep -q '^REPORT_TRUSTED_TEMPLATE_UPLOADS_ENABLED=' .env.local; then
    printf '\nREPORT_TRUSTED_TEMPLATE_UPLOADS_ENABLED=true\n' >> .env.local
  fi
  echo 'Preserved existing local values; added missing development settings.'
  exit 0
fi
{
  printf 'DATABASE_PASSWORD=%s\n' "$(openssl rand -hex 24)"
  printf 'OIDC_CLIENT_SECRET=%s\n' "$(openssl rand -hex 24)"
  printf 'DELIVERY_CLIENT_SECRET=%s\n' "$(openssl rand -hex 24)"
  printf 'DELIVERY_DATABASE_PASSWORD=%s\n' "$(openssl rand -hex 24)"
  printf 'S3_LOCAL_SECRET=%s\n' "$(openssl rand -hex 24)"
  printf 'REPORT_ADMIN_TEST_PASSWORD=%s\n' "$(openssl rand -hex 24)"
  printf 'REPORT_TEST_PASSWORD=%s\n' "$(openssl rand -hex 24)"
  printf 'REPORT_TRUSTED_TEMPLATE_UPLOADS_ENABLED=true\n'
  printf 'PORT=8085\nREPORT_ALLOW_EMULATOR_LINKS=true\nCOOKIE_SECURE=false\nAPP_ORIGIN=http://localhost:3005\nCORE_URL=http://localhost:8085\n'
} > .env.local
echo 'Created ignored local configuration with random development credentials.'
