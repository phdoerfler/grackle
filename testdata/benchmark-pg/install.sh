#!/bin/bash
# testdata/benchmark-pg/install.sh
set -euo pipefail
psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -f /data/install.sql
