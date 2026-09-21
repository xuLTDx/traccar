#!/bin/bash
# Writes /opt/traccar/conf/traccar.xml with PostgreSQL connection settings
# and restarts Traccar. Run as: sudo bash 02_configure_traccar.sh
set -euo pipefail

read -s -p "Postgres 'traccar' role password (from step 1): " TRACCAR_DB_PASSWORD
echo

CONF=/opt/traccar/conf/traccar.xml

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
sleep 3
echo "Traccar restarted. Checking status..."
systemctl is-active traccar
curl -s -o /dev/null -w 'HTTP %{http_code}\n' http://localhost:8082
