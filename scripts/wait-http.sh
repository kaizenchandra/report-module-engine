#!/bin/sh
set -eu
for attempt in $(seq 1 60); do
  if curl --max-time 3 -fsS "$1" >/dev/null; then exit 0; fi
  sleep 2
done
echo "Timed out waiting for $1" >&2
exit 1
