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
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.traccar.api.BaseResource;
import org.traccar.model.Device;
import org.traccar.model.TripPurpose;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

import java.util.Collection;
import java.util.List;

// Stores the "ucel jazdy" (trip purpose) annotation used by the Slovak
// vehicle trip logbook ("kniha jazd") export - see TripPurpose.java for
// the legal background. Trips aren't first-class persisted rows in
// Traccar (they're computed on the fly from positions/events), so a
// purpose is keyed by the trip's (deviceId, startPositionId,
// endPositionId) triple rather than a trip id.
@Path("trippurposes")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class TripPurposeResource extends BaseResource {

    @GET
    public Collection<TripPurpose> get(
            @QueryParam("deviceId") long deviceId) throws StorageException {
        permissionsService.checkPermission(Device.class, getUserId(), deviceId);
        return storage.getObjects(TripPurpose.class, new Request(
                new Columns.All(),
                new Condition.Equals("deviceId", deviceId)));
    }

    @POST
    public Response upsert(TripPurpose purpose) throws StorageException {
        permissionsService.checkPermission(Device.class, getUserId(), purpose.getDeviceId());

        List<TripPurpose> existing = storage.getObjects(TripPurpose.class, new Request(
                new Columns.Include("id"),
                Condition.merge(List.of(
                        new Condition.Equals("deviceId", purpose.getDeviceId()),
                        new Condition.Equals("startPositionId", purpose.getStartPositionId()),
                        new Condition.Equals("endPositionId", purpose.getEndPositionId())))));

        if (existing.isEmpty()) {
            storage.addObject(purpose, new Request(new Columns.Exclude("id")));
        } else {
            purpose.setId(existing.get(0).getId());
            storage.updateObject(purpose, new Request(
                    new Columns.Include("purpose", "note", "startAddress", "endAddress"),
                    new Condition.Equals("id", purpose.getId())));
        }

        return Response.ok(purpose).build();
    }

    // Clears a trip's purpose back to "unset" (the logbook row then falls
    // back to showing the geofence/business-address suggestion, if any,
    // exactly as if it had never been reviewed).
    @DELETE
    @Path("{id}")
    public Response remove(@PathParam("id") long id) throws StorageException {
        TripPurpose purpose = storage.getObject(TripPurpose.class, new Request(
                new Columns.All(), new Condition.Equals("id", id)));
        if (purpose != null) {
            permissionsService.checkPermission(Device.class, getUserId(), purpose.getDeviceId());
            storage.removeObject(TripPurpose.class, new Request(new Condition.Equals("id", id)));
        }
        return Response.noContent().build();
    }

}
