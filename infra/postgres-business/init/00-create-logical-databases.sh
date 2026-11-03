#!/usr/bin/env bash
set -Eeuo pipefail

# This script runs only when Docker initializes a new business PostgreSQL volume.
# The names are hard-coded deliberately; they are the service-owned logical databases.
for database_name in base_db image_db history_db payment_db; do
  database_exists="$(
    psql --username "$POSTGRES_USER" --dbname postgres --tuples-only --no-align \
      --command "SELECT 1 FROM pg_database WHERE datname = '${database_name}'"
  )"

  if [[ "$database_exists" != "1" ]]; then
    psql --username "$POSTGRES_USER" --dbname postgres --set ON_ERROR_STOP=1 \
      --command "CREATE DATABASE \"${database_name}\""
  fi
done
