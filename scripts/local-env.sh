#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
umask 077
if [ -f .env.local ]; then
  for setting in JDBC_DATABASE_PASSWORD REPORT_JDBC_PASSWORD; do
    if ! grep -q "^${setting}=" .env.local; then
      printf '\n%s=%s\n' "$setting" "$(openssl rand -hex 24)" >> .env.local
    fi
  done
  for setting in REPORT_JDBC_HOST=localhost REPORT_JDBC_PORT=5434 REPORT_JDBC_DATABASE=report_input REPORT_JDBC_USERNAME=report_reader REPORT_JDBC_SSL_MODE=disable; do
    if ! grep -q "^${setting%%=*}=" .env.local; then
      printf '\n%s\n' "$setting" >> .env.local
    fi
  done
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
  printf 'JDBC_DATABASE_PASSWORD=%s\n' "$(openssl rand -hex 24)"
  printf 'REPORT_JDBC_PASSWORD=%s\n' "$(openssl rand -hex 24)"
  printf 'REPORT_JDBC_HOST=localhost\nREPORT_JDBC_PORT=5434\nREPORT_JDBC_DATABASE=report_input\nREPORT_JDBC_USERNAME=report_reader\nREPORT_JDBC_SSL_MODE=disable\n'
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
