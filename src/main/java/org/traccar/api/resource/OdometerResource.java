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
package org.traccar.api.resource;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.traccar.api.BaseResource;
import org.traccar.model.Device;
import org.traccar.model.Position;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

import java.time.Instant;
import java.util.Date;
import java.util.List;

// This device has no real vehicle odometer signal (see PositionUtil.calibratedOdometer) - the
// only way to know real km is a dashboard photo taken at some position. This endpoint takes one
// such real reading and (re)establishes the device's single calibration anchor from it:
//   - if an anchor already exists, the segment between the OLD anchor and this new position is
//     "closed" - its final factor is derived from these two real measurements and permanently
//     written into every position in that range (Position.KEY_ODOMETER), so it never needs
//     recalculating again even if the factor is later refined further.
//   - the new position becomes the anchor going forward, carrying the just-closed factor as its
//     starting estimate until the next real reading closes this segment too.
@Path("odometer")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class OdometerResource extends BaseResource {

    public static class CalibrationRequest {
        private long positionId;
        private double realOdometer;

        public long getPositionId() {
            return positionId;
        }

        public void setPositionId(long positionId) {
            this.positionId = positionId;
        }

        public double getRealOdometer() {
            return realOdometer;
        }

        public void setRealOdometer(double realOdometer) {
            this.realOdometer = realOdometer;
        }
    }

    @POST
    @Path("calibrate")
    public Response calibrate(CalibrationRequest calibrationRequest) throws StorageException {
        Position position = storage.getObject(Position.class, new Request(
                new Columns.All(), new Condition.Equals("id", calibrationRequest.getPositionId())));
        if (position == null) {
            throw new IllegalArgumentException("Position not found");
        }
        long deviceId = position.getDeviceId();
        permissionsService.checkPermission(Device.class, getUserId(), deviceId);

        Device device = storage.getObject(Device.class, new Request(
                new Columns.All(), new Condition.Equals("id", deviceId)));

        double newTotalDistance = position.getDouble(Position.KEY_TOTAL_DISTANCE);
        double newRealOdometer = calibrationRequest.getRealOdometer();

        if (device.hasAttribute("odometerAnchorReal")) {
            double anchorReal = device.getDouble("odometerAnchorReal");
            double anchorDistance = device.getDouble("odometerAnchorDistance");
            Date anchorTime = Date.from(Instant.parse(device.getString("odometerAnchorTime")));
            double distanceDelta = newTotalDistance - anchorDistance;
            double factor = distanceDelta != 0 ? (newRealOdometer - anchorReal) / distanceDelta : 1.0;

            List<Position> segment = storage.getObjects(Position.class, new Request(
                    new Columns.All(),
                    new Condition.And(
                            new Condition.Equals("deviceId", deviceId),
                            new Condition.Between("fixTime", anchorTime, position.getFixTime()))));
            for (Position segmentPosition : segment) {
                double calibrated = anchorReal
                        + (segmentPosition.getDouble(Position.KEY_TOTAL_DISTANCE) - anchorDistance) * factor;
                segmentPosition.set(Position.KEY_ODOMETER, calibrated);
                storage.updateObject(segmentPosition, new Request(
                        new Columns.Include("attributes"),
                        new Condition.Equals("id", segmentPosition.getId())));
            }

            device.set("odometerFactor", factor);
        } else {
            device.set("odometerFactor", 1.0);
        }

        device.set("odometerAnchorReal", newRealOdometer);
        device.set("odometerAnchorDistance", newTotalDistance);
        device.set("odometerAnchorTime", position.getFixTime().toInstant().toString());

        storage.updateObject(device, new Request(
                new Columns.Include("attributes"), new Condition.Equals("id", deviceId)));

        return Response.ok(device).build();
    }

}
