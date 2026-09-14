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
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.traccar.api.BaseResource;
import org.traccar.model.BusinessAddress;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

import java.util.Collection;
import java.util.List;

// A flat, shared list of known business locations (see BusinessAddress.java)
// - not scoped per device or per user, since the same client site or office
// is normally relevant to every vehicle a small operation runs. Any
// authenticated user of this Traccar instance can manage the list.
@Path("businessaddresses")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class BusinessAddressResource extends BaseResource {

    @GET
    public Collection<BusinessAddress> get() throws StorageException {
        return storage.getObjects(BusinessAddress.class, new Request(new Columns.All()));
    }

    // Two save paths share this one endpoint:
    //  - The Stops report's "mark as business" dialog never sends an id
    //    (id defaults to 0) - upsert by name there, so marking the same
    //    location a second time (e.g. re-saving after this endpoint gained
    //    the `address` field) updates the existing row instead of creating
    //    a confusing duplicate that would still shadow it in
    //    ReportUtils.findGeofenceName()'s proximity scan.
    //  - The business-addresses management page edits a specific existing
    //    row and always sends its real id - update that row directly by id
    //    in that case, so renaming a location doesn't fall through to the
    //    by-name lookup (which would find nothing under the new name and
    //    insert a duplicate instead of renaming).
    @POST
    public Response add(BusinessAddress address) throws StorageException {
        if (address.getId() > 0) {
            storage.updateObject(address, new Request(
                    new Columns.Exclude("id"),
                    new Condition.Equals("id", address.getId())));
            return Response.ok(address).build();
        }
        List<BusinessAddress> existing = storage.getObjects(BusinessAddress.class, new Request(
                new Columns.Include("id"),
                new Condition.Equals("name", address.getName())));
        if (existing.isEmpty()) {
            address.setId(storage.addObject(address, new Request(new Columns.Exclude("id"))));
        } else {
            address.setId(existing.get(0).getId());
            storage.updateObject(address, new Request(
                    new Columns.Exclude("id"),
                    new Condition.Equals("id", address.getId())));
        }
        return Response.ok(address).build();
    }

    @DELETE
    @Path("{id}")
    public Response remove(@PathParam("id") long id) throws StorageException {
        storage.removeObject(BusinessAddress.class, new Request(new Condition.Equals("id", id)));
        return Response.noContent().build();
    }

}
