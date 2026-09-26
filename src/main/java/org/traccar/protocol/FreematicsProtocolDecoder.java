/*
 * Copyright 2018 - 2026 Anton Tananaev (anton@traccar.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.traccar.protocol;

import io.netty.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.BaseProtocolDecoder;
import org.traccar.session.DeviceSession;
import org.traccar.NetworkMessage;
import org.traccar.Protocol;
import org.traccar.helper.Checksum;
import org.traccar.helper.DateBuilder;
import org.traccar.helper.UnitsConverter;
import org.traccar.model.Position;

import java.net.SocketAddress;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;

public class FreematicsProtocolDecoder extends BaseProtocolDecoder {

    private static final Logger LOGGER = LoggerFactory.getLogger(FreematicsProtocolDecoder.class);

    // Defense in depth (2026-09-22): a GPS fix can never be from the future, but a
    // position built from a device that omits the date field (0x11) - either an
    // older firmware, or a stale backlog file recorded before a firmware update
    // added it - silently gets "today" stitched onto its own (possibly much older)
    // time-of-day, which can land arbitrarily in the future relative to the real
    // fix. Confirmed 2026-09-22: a backlog replay from Sep 21 decoded straight into
    // the middle of the next afternoon's live drive, corrupting trip/stop reports.
    // The firmware fix (always sending PID_GPS_DATE) addresses the root cause, but
    // this catches any position that still manages to slip through with a bogus
    // future date instead of silently accepting it.
    private static final long MAX_FUTURE_TOLERANCE_MS = 5 * 60 * 1000;

    public FreematicsProtocolDecoder(Protocol protocol) {
        super(protocol);
    }

    private Object decodeEvent(
            Channel channel, SocketAddress remoteAddress, String sentence) {

        DeviceSession deviceSession = null;
        String event = null;
        String time = null;
        String vin = null;
        String firmware = null;
        String variant = null;

        for (String pair : sentence.split(",")) {
            String[] data = pair.split("=");
            String key = data[0];
            String value = data[1];
            switch (key) {
                case "ID" -> {
                    if (deviceSession == null) {
                        deviceSession = getDeviceSession(channel, remoteAddress, value);
                    }
                }
                case "VIN" -> vin = value;
                case "EV" -> event = value;
                case "TS" -> time = value;
                // Both sent only on LOGIN (see teleclient.cpp's TeleClientUDP::connect())
                // - let an external OTA push-decision service read a device's current
                // build+variant from here instead of querying the device directly,
                // which only works while it's reachable on the same LAN. FW is the
                // build timestamp (ordering/anti-downgrade); VARIANT is the short
                // vehicle-module tag (e.g. "5.3-odo-PSA" vs "5.3-odo-VAG") - two
                // profiles compiled from the same source tree can share identical
                // timestamps' ordering semantics but are completely different
                // binaries, so only VARIANT answers "which vehicle build is this".
                case "FW" -> firmware = value;
                case "VARIANT" -> variant = value;
            }
        }

        if (channel != null && deviceSession != null && event != null && time != null) {
            String message = String.format("1#EV=%s,RX=1,TS=%s", event, time);
            message += '*' + Checksum.sum(message);
            channel.writeAndFlush(new NetworkMessage(message, remoteAddress));
        }

        if (deviceSession != null && (vin != null || firmware != null)) {
            Position position = new Position(getProtocolName());
            position.setDeviceId(deviceSession.getDeviceId());
            getLastLocation(position, null);
            if (vin != null) {
                position.set(Position.KEY_VIN, vin);
            }
            if (firmware != null) {
                position.set(Position.KEY_VERSION_FW, firmware);
                LOGGER.info("Freematics device {} reported firmware {} variant {}",
                        deviceSession.getDeviceId(), firmware, variant);
            }
            if (variant != null) {
                // No standard Traccar attribute for this - "otaVariant" is our own,
                // read directly by ota_push_watcher.py, not a built-in Position key.
                position.set("otaVariant", variant);
            }
            return position;
        }

        return null;
    }

    // A record without a GPS fix (2026-09-25): if it carries its own time (the
    // firmware stamps fix-less records with its GPS-synced clock), keep that
    // time and borrow only the last known coordinates, valid=false. Marking it
    // outdated instead makes OutdatedHandler overwrite the time with the last
    // FIX time too - on 2026-09-25 that moved 80 s of a drive (ignition on,
    // before the first fix after a standby wake) back to the previous parking
    // time, and Traccar merged both drives across the stop. Without a time of
    // its own the old outdated path stays: server receive time would be wrong
    // for SD backlog replays.
    private void finalizePosition(
            Position position, DateBuilder dateBuilder, boolean hasTime, List<Position> positions) {
        if (!position.getValid()) {
            Position last = hasTime && getCacheManager() != null
                    ? getCacheManager().getPosition(position.getDeviceId()) : null;
            if (last != null) {
                position.setLatitude(last.getLatitude());
                position.setLongitude(last.getLongitude());
                position.setAltitude(last.getAltitude());
            } else {
                getLastLocation(position, null);
            }
        }
        Date time = dateBuilder.getDate();
        if (time.getTime() > System.currentTimeMillis() + MAX_FUTURE_TOLERANCE_MS) {
            LOGGER.warn("Freematics device {} sent an implausible future fix time {} - "
                    + "falling back to server time (likely a backlog replay missing PID_GPS_DATE)",
                    position.getDeviceId(), time);
            time = new Date();
        }
        position.setTime(time);
        positions.add(position);
    }

    private Object decodePosition(
            Channel channel, SocketAddress remoteAddress, String sentence, String id) {

        DeviceSession deviceSession = getDeviceSession(channel, remoteAddress, id);
        if (deviceSession == null) {
            return null;
        }

        List<Position> positions = new LinkedList<>();
        Position position = null;
        DateBuilder dateBuilder = null;
        boolean hasTime = false;

        for (String pair : sentence.split(",")) {
            String[] data = pair.split("[=:]");
            int key;
            try {
                key = Integer.parseInt(data[0], 16);
            } catch (NumberFormatException e) {
                continue;
            }
            String value = data[1];
            if (key == 0x0) {
                if (position != null) {
                    finalizePosition(position, dateBuilder, hasTime, positions);
                }
                position = new Position(getProtocolName());
                position.setDeviceId(deviceSession.getDeviceId());
                dateBuilder = new DateBuilder(new Date());
                hasTime = false;
            } else if (position != null) {
                switch (key) {
                    case 0x11 -> {
                        value = ("000000" + value).substring(value.length());
                        dateBuilder.setDateReverse(
                                Integer.parseInt(value.substring(0, 2)),
                                Integer.parseInt(value.substring(2, 4)),
                                Integer.parseInt(value.substring(4)));
                    }
                    case 0x10 -> {
                        hasTime = true;
                        value = ("00000000" + value).substring(value.length());
                        dateBuilder.setTime(
                                Integer.parseInt(value.substring(0, 2)),
                                Integer.parseInt(value.substring(2, 4)),
                                Integer.parseInt(value.substring(4, 6)),
                                Integer.parseInt(value.substring(6)) * 10);
                    }
                    case 0xA -> {
                        position.setValid(true);
                        position.setLatitude(Double.parseDouble(value));
                    }
                    case 0xB -> {
                        position.setValid(true);
                        position.setLongitude(Double.parseDouble(value));
                    }
                    case 0xC -> position.setAltitude(Double.parseDouble(value));
                    case 0xD -> position.setSpeed(UnitsConverter.knotsFromKph(Double.parseDouble(value)));
                    case 0xE -> position.setCourse(Integer.parseInt(value));
                    case 0xF -> position.set(Position.KEY_SATELLITES, Integer.parseInt(value));
                    case 0x12 -> position.set(Position.KEY_HDOP, Integer.parseInt(value));
                    case 0x20 -> position.set(Position.KEY_ACCELERATION, value);
                    case 0x24 -> position.set(Position.KEY_BATTERY, Integer.parseInt(value) / 100.0);
                    case 0x81 -> position.set(Position.KEY_RSSI, Integer.parseInt(value));
                    case 0x82 -> position.set(Position.KEY_DEVICE_TEMP, Double.parseDouble(value) / 10.0);
                    case 0x104 -> position.set(Position.KEY_ENGINE_LOAD, Integer.parseInt(value));
                    case 0x105 -> position.set(Position.KEY_COOLANT_TEMP, Integer.parseInt(value));
                    // Also derives KEY_IGNITION from RPM > 0 - lets Traccar's
                    // report.trip.useIgnition config use "engine running" as
                    // the trip boundary instead of GPS speed, which produces
                    // spurious near-zero-distance "trips" from GPS jitter
                    // while the vehicle is parked with the engine off.
                    case 0x10c -> {
                        int rpm = Integer.parseInt(value);
                        position.set(Position.KEY_RPM, rpm);
                        position.set(Position.KEY_IGNITION, rpm > 0);
                    }
                    case 0x10d -> position.set(Position.KEY_OBD_SPEED, Integer.parseInt(value));
                    case 0x111 -> position.set(Position.KEY_THROTTLE, Integer.parseInt(value));
                    // PID_RUNTIME (0x1F): engine run time in seconds, as read by the
                    // Freematics OBD library - KEY_HOURS expects milliseconds.
                    case 0x11f -> position.set(Position.KEY_HOURS, Long.parseLong(value) * 1000L);
                    // PID_FUEL_LEVEL (0x2F): NOT read via the standard OBD PID on this
                    // device (see telelogger.ino's dedicated UDS block, DID 0x22B0 on
                    // the VAG instrument cluster) - the device sends whole vehicles'
                    // worth in deciliters (liters x10, e.g. 475 = 47.5 l), not a 0-100
                    // percentage, since tank capacity isn't known to compute one.
                    // KEY_FUEL (liters), not KEY_FUEL_LEVEL (percentage), is correct here.
                    case 0x12f -> position.set(Position.KEY_FUEL, Integer.parseInt(value) / 10.0);
                    // Odometer: device sends whole kilometres (UDS/PID reads normalised
                    // to km, or GPS-distance fallback) - KEY_ODOMETER expects meters.
                    case 0x1a6 -> position.set(Position.KEY_ODOMETER, Long.parseLong(value) * 1000L);
                    // Engine start/stop decided by the box (2026-09-26): 1 = start,
                    // 2 = stop. Start-stop at a light is NOT a stop (the engine ECU
                    // still answers); a stop's record is timed at the last RPM > 0.
                    case 0x380 -> {
                        boolean start = "1".equals(value);
                        position.set("engineEvent", start ? "start" : "stop");
                        position.set(Position.KEY_IGNITION, start);
                    }
                    case 0x381 -> position.set("engineEventTime", Long.parseLong(value)); // unix seconds, 0 = no clock
                    case 0x382 -> position.set("sdBacklog", !"0".equals(value)); // SD data not yet delivered
                    default -> position.set(Position.PREFIX_IO + key, value);
                }
            }
        }

        if (position != null) {
            finalizePosition(position, dateBuilder, hasTime, positions);
        }

        return positions.isEmpty() ? null : positions;
    }

    @Override
    protected Object decode(
            Channel channel, SocketAddress remoteAddress, Object msg) throws Exception {

        String sentence = (String) msg;
        int startIndex = sentence.indexOf('#');
        int endIndex = sentence.indexOf('*');

        if (startIndex > 0 && endIndex > 0) {
            String id = sentence.substring(0, startIndex);
            sentence = sentence.substring(startIndex + 1, endIndex);

            if (sentence.startsWith("EV")) {
                return decodeEvent(channel, remoteAddress, sentence);
            } else {
                return decodePosition(channel, remoteAddress, sentence, id);
            }
        }

        return null;
    }

}
