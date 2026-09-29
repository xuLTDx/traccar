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

import jakarta.ws.rs.BadRequestException;
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
import org.traccar.model.Vehicle;
import org.traccar.model.VehicleAssignment;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

import java.util.Collection;

// Vehicle registry (see Vehicle.java): a flat, shared list like the business
// addresses; writes need a non-readonly account. Assignments of a box to a
// vehicle live under /vehicles/assignments.
@Path("vehicles")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class VehicleResource extends BaseResource {

    @GET
    public Collection<Vehicle> get() throws StorageException {
        return storage.getObjects(Vehicle.class, new Request(new Columns.All()));
    }

    // id 0 = new vehicle, else update that one
    @POST
    public Response save(Vehicle vehicle) throws StorageException {
        permissionsService.checkEdit(getUserId(), Vehicle.class, vehicle.getId() == 0, false);
        if (vehicle.getPlate() == null || vehicle.getPlate().isBlank()) {
            throw new BadRequestException("plate required");
        }
        if (!Vehicle.OWNERSHIP_PRIVATE.equals(vehicle.getOwnership())) {
            vehicle.setOwnership(Vehicle.OWNERSHIP_COMPANY);
        }
        if (vehicle.getId() > 0) {
            storage.updateObject(vehicle, new Request(
                    new Columns.Exclude("id"),
                    new Condition.Equals("id", vehicle.getId())));
        } else {
            vehicle.setId(storage.addObject(vehicle, new Request(new Columns.Exclude("id"))));
        }
        return Response.ok(vehicle).build();
    }

    // a vehicle with trips assigned keeps its history: removing it also
    // removes its assignments, so the caller must confirm in the UI
    @DELETE
    @Path("{id}")
    public Response remove(@PathParam("id") long id) throws StorageException {
        permissionsService.checkEdit(getUserId(), Vehicle.class, false, false);
        storage.removeObject(VehicleAssignment.class, new Request(new Condition.Equals("vehicleId", id)));
        storage.removeObject(Vehicle.class, new Request(new Condition.Equals("id", id)));
        return Response.noContent().build();
    }

    @GET
    @Path("assignments")
    public Collection<VehicleAssignment> getAssignments(
            @QueryParam("deviceId") long deviceId) throws StorageException {
        if (deviceId > 0) {
            permissionsService.checkPermission(Device.class, getUserId(), deviceId);
            return storage.getObjects(VehicleAssignment.class, new Request(
                    new Columns.All(), new Condition.Equals("deviceId", deviceId)));
        }
        return storage.getObjects(VehicleAssignment.class, new Request(new Columns.All()));
    }

    // A box is in one vehicle at a time: an assignment may not overlap another
    // one of the same box (open-ended = until now and later).
    @POST
    @Path("assignments")
    public Response saveAssignment(VehicleAssignment assignment) throws StorageException {
        permissionsService.checkPermission(Device.class, getUserId(), assignment.getDeviceId());
        permissionsService.checkEdit(getUserId(), VehicleAssignment.class, assignment.getId() == 0, false);
        if (assignment.getFromTime() == null) {
            throw new BadRequestException("fromTime required");
        }
        if (assignment.getToTime() != null && !assignment.getToTime().after(assignment.getFromTime())) {
            throw new BadRequestException("toTime must be after fromTime");
        }
        if (storage.getObject(Vehicle.class, new Request(
                new Columns.Include("id"), new Condition.Equals("id", assignment.getVehicleId()))) == null) {
            throw new BadRequestException("unknown vehicle");
        }
        long from = assignment.getFromTime().getTime();
        long to = assignment.getToTime() != null ? assignment.getToTime().getTime() : Long.MAX_VALUE;
        for (VehicleAssignment other : storage.getObjects(VehicleAssignment.class, new Request(
                new Columns.All(), new Condition.Equals("deviceId", assignment.getDeviceId())))) {
            if (other.getId() == assignment.getId()) {
                continue;
            }
            long otherFrom = other.getFromTime().getTime();
            long otherTo = other.getToTime() != null ? other.getToTime().getTime() : Long.MAX_VALUE;
            if (from < otherTo && otherFrom < to) {
                throw new BadRequestException("overlaps assignment " + other.getId());
            }
        }
        if (assignment.getId() > 0) {
            storage.updateObject(assignment, new Request(
                    new Columns.Exclude("id"),
                    new Condition.Equals("id", assignment.getId())));
        } else {
            assignment.setId(storage.addObject(assignment, new Request(new Columns.Exclude("id"))));
        }
        return Response.ok(assignment).build();
    }

    @DELETE
    @Path("assignments/{id}")
    public Response removeAssignment(@PathParam("id") long id) throws StorageException {
        VehicleAssignment assignment = storage.getObject(VehicleAssignment.class, new Request(
                new Columns.All(), new Condition.Equals("id", id)));
        if (assignment != null) {
            permissionsService.checkPermission(Device.class, getUserId(), assignment.getDeviceId());
            permissionsService.checkEdit(getUserId(), VehicleAssignment.class, false, false);
            storage.removeObject(VehicleAssignment.class, new Request(new Condition.Equals("id", id)));
        }
        return Response.noContent().build();
    }

}
