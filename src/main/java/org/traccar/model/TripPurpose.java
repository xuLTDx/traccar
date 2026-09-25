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
package org.traccar.model;

import org.traccar.storage.StorageName;

// Business-purpose annotation for a single trip, for the Slovak vehicle
// trip logbook ("kniha jazd") export - see zakon c. 222/2004 Z.z. o DPH,
// par. 85n ods. 6 pism. d). Keyed by the trip's (deviceId, startPositionId,
// endPositionId) triple rather than having its own notion of a trip, since
// Traccar computes trips on the fly from positions/events and doesn't
// persist them as first-class rows.
@StorageName("tc_trip_purposes")
public class TripPurpose extends BaseModel {

    public static final String PURPOSE_BUSINESS = "business";
    public static final String PURPOSE_PRIVATE = "private";
    public static final String PURPOSE_COMMUTE = "commute";

    private long deviceId;

    public long getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(long deviceId) {
        this.deviceId = deviceId;
    }

    private long startPositionId;

    public long getStartPositionId() {
        return startPositionId;
    }

    public void setStartPositionId(long startPositionId) {
        this.startPositionId = startPositionId;
    }

    private long endPositionId;

    public long getEndPositionId() {
        return endPositionId;
    }

    public void setEndPositionId(long endPositionId) {
        this.endPositionId = endPositionId;
    }

    private String purpose = PURPOSE_BUSINESS;

    public String getPurpose() {
        return purpose;
    }

    public void setPurpose(String purpose) {
        this.purpose = purpose;
    }

    private String note;

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    // Manually chosen logbook start/end address (a business address) that
    // overrides the one derived from the trip's start/end position - null =
    // derived as usual. For trips whose first/last GPS fix is away from the
    // real place (e.g. no fix yet when leaving a parking spot).
    private String startAddress;

    public String getStartAddress() {
        return startAddress;
    }

    public void setStartAddress(String startAddress) {
        this.startAddress = startAddress;
    }

    private String endAddress;

    public String getEndAddress() {
        return endAddress;
    }

    public void setEndAddress(String endAddress) {
        this.endAddress = endAddress;
    }

}
