#!/bin/bash
# Installs PostgreSQL and creates the traccar database + role.
# Run as: sudo bash 01_bootstrap_postgres.sh
#
# Meant to be sourced by 02_configure_traccar.sh (which asks for the
# password ONCE and passes it to both this script and traccar.xml - do not
# run this script standalone and then 02 separately, that re-prompts for a
# SECOND password entry that has no effect if the role already exists,
# which can silently leave traccar.xml with the WRONG password. Confirmed
# as a real near-miss 2026-09-21 - keep both scripts' password prompts
# consolidated into one entry point (02_configure_traccar.sh) going forward.
set -euo pipefail

if ! command -v psql >/dev/null 2>&1; then
    echo "Installing PostgreSQL..."
    apt update
    apt install -y postgresql
fi

# If TRACCAR_DB_PASSWORD isn't already set in the environment (i.e. this
# script is being run standalone, not sourced by 02_configure_traccar.sh),
# prompt for it here.
if [ -z "${TRACCAR_DB_PASSWORD:-}" ]; then
    read -s -p "New password for the 'traccar' Postgres role: " TRACCAR_DB_PASSWORD
    echo
fi

ROLE_EXISTS=$(sudo -u postgres psql -tAc "SELECT 1 FROM pg_roles WHERE rolname='traccar'")
DB_EXISTS=$(sudo -u postgres psql -tAc "SELECT 1 FROM pg_database WHERE datname='traccar'")

if [ "$ROLE_EXISTS" = "1" ]; then
    echo "Role 'traccar' already exists - updating its password to match what you just entered."
    sudo -u postgres psql -c "ALTER USER traccar WITH ENCRYPTED PASSWORD '${TRACCAR_DB_PASSWORD}';"
else
    sudo -u postgres psql -c "CREATE USER traccar WITH ENCRYPTED PASSWORD '${TRACCAR_DB_PASSWORD}';"
fi

if [ "$DB_EXISTS" = "1" ]; then
    echo "Database 'traccar' already exists - leaving it as-is (not touching its data)."
else
    sudo -u postgres psql -c "CREATE DATABASE traccar OWNER traccar;"
fi

sudo -u postgres psql -d traccar -c "GRANT ALL PRIVILEGES ON DATABASE traccar TO traccar;"
sudo -u postgres psql -d traccar -c "GRANT ALL ON SCHEMA public TO traccar;"

echo "Done. traccar database and role are ready, password is set to what you just entered."
