#!/bin/bash
# testdata/benchmark-pg/install.sh
set -euo pipefail
psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -f /data/install.sql

# Settle query plans once. The data never changes after seeding, and autovacuum is disabled
# (see docker-compose.yml), so this is the only ANALYZE that will ever run.
psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "VACUUM ANALYZE;"

# Used by the benchmark's @Setup to warm the buffer cache; the extension itself persists in
# the seeded volume, but the cache it warms does not survive a container restart.
psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "CREATE EXTENSION IF NOT EXISTS pg_prewarm;"
