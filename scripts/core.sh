#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
set -a
. ./.env.local
set +a
if [ "$(uname)" = Darwin ]; then JAVA_HOME=$(/usr/libexec/java_home -v 21); export JAVA_HOME; fi
cd services/report-core
export ARTIFACT_ROOT="${ARTIFACT_ROOT:-../../.artifacts}"
exec ./mvnw spring-boot:run
