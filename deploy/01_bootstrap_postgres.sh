#!/bin/bash
# Installs PostgreSQL and creates the traccar database + role.
# Run as: sudo bash 01_bootstrap_postgres.sh
set -euo pipefail

if ! command -v psql >/dev/null 2>&1; then
    echo "Installing PostgreSQL..."
    apt update
    apt install -y postgresql
fi

read -s -p "New password for the 'traccar' Postgres role (remember this for step 2): " TRACCAR_DB_PASSWORD
echo
read -s -p "Confirm password: " TRACCAR_DB_PASSWORD_CONFIRM
echo
if [ "$TRACCAR_DB_PASSWORD" != "$TRACCAR_DB_PASSWORD_CONFIRM" ]; then
    echo "Passwords did not match, aborting."
    exit 1
fi

sudo -u postgres psql <<SQL
CREATE DATABASE traccar;
CREATE USER traccar WITH ENCRYPTED PASSWORD '${TRACCAR_DB_PASSWORD}';
GRANT ALL PRIVILEGES ON DATABASE traccar TO traccar;
\c traccar
GRANT ALL ON SCHEMA public TO traccar;
SQL

echo "Done. traccar database and role created."
