/*
 * Copyright 2026 Anton Tananaev (anton@traccar.org)
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
import org.traccar.NetworkMessage;
import org.traccar.helper.Checksum;
import org.traccar.model.FreematicsRecord;
import org.traccar.model.Position;
import org.traccar.storage.Storage;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

import java.net.SocketAddress;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Freematics delivery acknowledgement (2026-09-26). A data packet from the
 * box carries its boot number (PID 0x383) and a packet number (0x384); every
 * record in it is identified by (device, boot, PID 0 = ms since boot). The
 * packet is acknowledged ("1#ACK=&lt;packet&gt;*CS") only once every new record
 * of it has gone through the whole processing chain and was stored - a record
 * the database failed to store leaves the packet unacknowledged, so the box
 * sends it again. A record whose identity is already stored (or in flight) is
 * a re-send and is dropped in the decoder.
 * UDP has no delayed-acknowledgement support in Traccar (AcknowledgementHandler
 * is TCP only), hence ProcessingHandler calls {@link #finished} directly.
 */
public final class FreematicsAcks {

    private static final Logger LOGGER = LoggerFactory.getLogger(FreematicsAcks.class);
    private static final long STALE_MS = 120_000;

    private FreematicsAcks() {
    }

    private static final class Packet {
        private final Channel channel;
        private final SocketAddress remoteAddress;
        private final String number;
        private final long created = System.currentTimeMillis();
        private int left;
        private boolean failed;

        private Packet(Channel channel, SocketAddress remoteAddress, String number, int left) {
            this.channel = channel;
            this.remoteAddress = remoteAddress;
            this.number = number;
            this.left = left;
        }
    }

    private static volatile Storage storage;
    private static final Map<Position, Packet> PENDING = new IdentityHashMap<>();
    private static final Set<String> IN_FLIGHT = ConcurrentHashMap.newKeySet();

    static void setStorage(Storage value) {
        storage = value;
    }

    private static String key(long deviceId, long bootId, long ts) {
        return deviceId + ":" + bootId + ":" + ts;
    }

    /** True if this record is already stored or is being processed right now. */
    static boolean isDuplicate(long deviceId, long bootId, long ts) {
        if (IN_FLIGHT.contains(key(deviceId, bootId, ts))) {
            return true;
        }
        Storage s = storage;
        if (s == null) {
            return false;
        }
        try {
            return s.getObject(FreematicsRecord.class, new Request(
                    new Columns.Include("id"),
                    new Condition.And(
                            new Condition.Equals("deviceId", deviceId),
                            new Condition.And(
                                    new Condition.Equals("bootId", bootId),
                                    new Condition.Equals("ts", ts))))) != null;
        } catch (StorageException e) {
            LOGGER.warn("Freematics identity lookup failed", e);
            return false;
        }
    }

    static void send(Channel channel, SocketAddress remoteAddress, String number) {
        if (channel != null) {
            String message = "1#ACK=" + number;
            message += '*' + Checksum.sum(message);
            channel.writeAndFlush(new NetworkMessage(message, remoteAddress));
        }
    }

    /** Called by the decoder with the new (non-duplicate) records of one packet. */
    static void expect(
            Channel channel, SocketAddress remoteAddress, String number, List<Position> positions) {
        if (positions.isEmpty()) {
            send(channel, remoteAddress, number);
            return;
        }
        Packet packet = new Packet(channel, remoteAddress, number, positions.size());
        synchronized (PENDING) {
            long now = System.currentTimeMillis();
            Iterator<Map.Entry<Position, Packet>> it = PENDING.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<Position, Packet> entry = it.next();
                if (now - entry.getValue().created > STALE_MS) {
                    Position stale = entry.getKey();
                    IN_FLIGHT.remove(key(stale.getDeviceId(), stale.getLong("fmBoot"), stale.getLong("fmTs")));
                    it.remove();
                }
            }
            for (Position position : positions) {
                IN_FLIGHT.add(key(position.getDeviceId(), position.getLong("fmBoot"), position.getLong("fmTs")));
                PENDING.put(position, packet);
            }
        }
    }

    /** Called by ProcessingHandler when a position has left the processing chain. */
    public static void finished(Position position, boolean filtered) {
        Packet packet;
        synchronized (PENDING) {
            packet = PENDING.remove(position);
        }
        if (packet == null) {
            return;
        }
        long deviceId = position.getDeviceId();
        long bootId = position.getLong("fmBoot");
        long ts = position.getLong("fmTs");
        boolean stored = position.getId() > 0;
        if (stored) {
            FreematicsRecord record = new FreematicsRecord();
            record.setDeviceId(deviceId);
            record.setBootId(bootId);
            record.setTs(ts);
            try {
                storage.addObject(record, new Request(new Columns.Exclude("id")));
            } catch (StorageException e) {
                LOGGER.warn("Freematics identity insert failed", e);
            }
        }
        IN_FLIGHT.remove(key(deviceId, bootId, ts));
        boolean ack;
        synchronized (PENDING) {
            if (!stored && !filtered) {
                packet.failed = true;  // the database did not take it: no ACK, the box re-sends
            }
            ack = --packet.left == 0 && !packet.failed;
        }
        if (ack) {
            send(packet.channel, packet.remoteAddress, packet.number);
        }
    }

}
