#!/bin/sh
# Runs once, when PostgreSQL starts on an empty volume: the app's own login "finance", which owns the database
# "finance" and is no superuser. Flyway then creates the schema "app" in it.
set -eu
psql -v ON_ERROR_STOP=1 -v password="$APP_DB_PASSWORD" --username "$POSTGRES_USER" --dbname postgres <<'SQL'
CREATE ROLE finance LOGIN PASSWORD :'password';
CREATE DATABASE finance OWNER finance;
REVOKE ALL ON DATABASE finance FROM PUBLIC;
SQL
