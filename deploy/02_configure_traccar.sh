#!/bin/bash
# Single entry point for the Postgres + traccar.xml setup - no human ever
# has to type or remember the 'traccar' Postgres role's password:
#   - On a re-run against an already-configured deployment, the existing
#     password is read straight out of the current traccar.xml and reused.
#   - On a first-time setup, a random password is generated automatically
#     (this is a purely internal, machine-to-machine connection - Traccar
#     talking to its own local Postgres - nobody ever needs to type it in
#     by hand).
# This removes an earlier real near-miss (2026-09-21): a design that asked
# a human to type the same password twice across two separate scripts, with
# no shared state, where a mismatch would have broken the live DB
# connection. Auto-detect/auto-generate removes that whole class of risk.
#
# Run as: sudo bash 02_configure_traccar.sh
set -euo pipefail
cd "$(dirname "$0")"

CONF=/opt/traccar/conf/traccar.xml

EXISTING_PASSWORD=""
if [ -f "$CONF" ]; then
    EXISTING_PASSWORD=$(grep -oP "(?<=<entry key='database.password'>)[^<]*" "$CONF" || true)
fi

if [ -n "$EXISTING_PASSWORD" ]; then
    echo "Found an existing database.password in $CONF - reusing it (no new password needed)."
    TRACCAR_DB_PASSWORD="$EXISTING_PASSWORD"
else
    echo "No existing password found - generating a new random one for the 'traccar' Postgres role."
    TRACCAR_DB_PASSWORD=$(openssl rand -base64 24 | tr -dc 'a-zA-Z0-9' | head -c 24)
fi
export TRACCAR_DB_PASSWORD

bash ./01_bootstrap_postgres.sh

if [ -f "$CONF" ]; then
    cp "$CONF" "${CONF}.bak.$(date +%Y%m%d_%H%M%S)"
    echo "Backed up existing config to ${CONF}.bak.*"
fi

cat > "$CONF" <<XML
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE properties SYSTEM 'http://java.sun.com/dtd/properties.dtd'>
<properties>

    <!-- Documentation: https://www.traccar.org/configuration-file/ -->

    <entry key='database.driver'>org.postgresql.Driver</entry>
    <entry key='database.url'>jdbc:postgresql://localhost:5432/traccar</entry>
    <entry key='database.user'>traccar</entry>
    <entry key='database.password'>${TRACCAR_DB_PASSWORD}</entry>

    <entry key='geocoder.type'>nominatim</entry>
    <entry key='report.trip.useIgnition'>true</entry>

    <!-- Default (0.01 knots ~ 0.02 km/h) is below normal GPS noise while
         parked - confirmed live 2026-09-21 this device showed 0.05-0.16
         knots pure noise while stationary, which exceeded the default
         threshold, so motion never dropped to false during a real ~18min
         stop. 1 knot (~1.85 km/h) safely filters this noise without losing
         genuine slow motion. -->
    <entry key='event.motion.speedThreshold'>1</entry>

    <entry key='processing.copyAttributes.enable'>true</entry>
    <entry key='processing.copyAttributes'>versionFw,otaVariant</entry>

    <!-- Traccar reserves the whole 5001-5267 range - one port per built-in
         protocol it supports, auto-started regardless of use (see
         PortConfigSuffix.java). Our own ports are deliberately outside this
         range so they never collide with a real device's factory default in
         the future.
         Port map: 6000 = Freematics protocol, 6001 = freematics-ota pull
         server (separate process, not Traccar), 6002 = osmand protocol
         (Traccar Android app). -->
    <entry key='freematics.port'>6000</entry>
    <entry key='osmand.port'>6002</entry>
</properties>
XML

systemctl restart traccar
echo "Traccar restarted. Waiting for it to come up (up to 30s - a fixed"
echo "short sleep here previously gave a false-looking HTTP 000 on a real,"
echo "successful restart just because the JVM hadn't finished starting yet)."
for i in $(seq 1 30); do
    CODE=$(curl -s -o /dev/null -w '%{http_code}' http://localhost:8082 || true)
    if [ "$CODE" = "200" ]; then
        echo "Traccar is up (HTTP 200) after ${i}s."
        break
    fi
    sleep 1
done
systemctl is-active traccar
curl -s -o /dev/null -w 'HTTP %{http_code}\n' http://localhost:8082
