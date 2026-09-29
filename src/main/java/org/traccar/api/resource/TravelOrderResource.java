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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.traccar.api.BaseResource;
import org.traccar.model.AllowanceRate;
import org.traccar.model.Device;
import org.traccar.model.Driver;
import org.traccar.model.FuelReceipt;
import org.traccar.model.TravelOrder;
import org.traccar.model.Vehicle;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Order;
import org.traccar.storage.query.Request;
import org.traccar.travelorder.RateService;
import org.traccar.travelorder.TravelOrderPdf;
import org.traccar.travelorder.TravelOrderRequest;
import org.traccar.travelorder.TravelOrderService;

import java.io.IOException;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;

// Travel orders (cestovný príkaz) - see TravelOrderService for the rules.
//  GET  travelorders/places      miesto konania candidates for the dialog
//  POST travelorders/preview     computation only, nothing stored
//  POST travelorders             issue: number + frozen computation stored
//  GET  travelorders             issued orders (of one device)
//  GET  travelorders/{id}/pdf    the PDF of an issued order
//  POST travelorders/preview/pdf the PDF of a preview (marked NÁHĽAD)
// plus the fuel receipts and the allowance rates it uses.
@Path("travelorders")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class TravelOrderResource extends BaseResource {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject
    private TravelOrderService travelOrderService;

    @Inject
    private TravelOrderPdf travelOrderPdf;

    @Inject
    private RateService rateService;

    private void check(TravelOrderRequest request) throws StorageException {
        permissionsService.checkPermission(Device.class, getUserId(), request.getDeviceId());
        if (request.getDriverId() > 0) {
            permissionsService.checkPermission(Driver.class, getUserId(), request.getDriverId());
        }
        if (request.getFrom() == null || request.getTo() == null || !request.getTo().after(request.getFrom())) {
            throw new BadRequestException("from/to");
        }
    }

    @GET
    @Path("places")
    public List<String> places(
            @QueryParam("deviceId") long deviceId, @QueryParam("driverId") long driverId,
            @QueryParam("from") Date from, @QueryParam("to") Date to) throws StorageException {
        permissionsService.checkPermission(Device.class, getUserId(), deviceId);
        Device device = storage.getObject(Device.class, new Request(
                new Columns.All(), new Condition.Equals("id", deviceId)));
        Driver driver = null;
        if (driverId > 0) {
            permissionsService.checkPermission(Driver.class, getUserId(), driverId);
            driver = storage.getObject(Driver.class, new Request(
                    new Columns.All(), new Condition.Equals("id", driverId)));
        }
        return travelOrderService.places(device, driver, from, to);
    }

    @POST
    @Path("preview")
    public Map<String, Object> preview(TravelOrderRequest request) throws StorageException {
        check(request);
        return travelOrderService.compute(request);
    }

    @POST
    @Path("preview/pdf")
    @Produces("application/pdf")
    public Response previewPdf(TravelOrderRequest request) throws StorageException, IOException {
        check(request);
        byte[] pdf = travelOrderPdf.pdf("NÁHĽAD", travelOrderService.compute(request));
        return Response.ok(pdf).header("Content-Disposition", "attachment; filename=CP_nahlad.pdf").build();
    }

    // The number is the driver's initials + year + his sequence number in that
    // year (VP202601, VP202602, ...), unique per driver and year.
    @POST
    public TravelOrder issue(TravelOrderRequest request) throws StorageException, IOException {
        check(request);
        if (request.getDriverId() <= 0) {
            throw new BadRequestException("driver required");
        }
        permissionsService.checkEdit(getUserId(), TravelOrder.class, true, false);
        Driver driver = storage.getObject(Driver.class, new Request(
                new Columns.All(), new Condition.Equals("id", request.getDriverId())));
        Map<String, Object> result = travelOrderService.compute(request);
        int year = request.getFrom().toInstant().atZone(TravelOrderService.ZONE).getYear();
        synchronized (TravelOrderResource.class) {
            int sequence = storage.getObjects(TravelOrder.class, new Request(
                    new Columns.Include("sequence"), new Condition.And(
                            new Condition.Equals("driverId", request.getDriverId()),
                            new Condition.Equals("year", year))))
                    .stream().mapToInt(TravelOrder::getSequence).max().orElse(0) + 1;
            TravelOrder order = new TravelOrder();
            order.setNumber(String.format("%s%d%02d", TravelOrderService.driverInitials(driver), year, sequence));
            order.setYear(year);
            order.setSequence(sequence);
            order.setDriverId(request.getDriverId());
            order.setCompanyId(((Number) ((Map<?, ?>) result.get("company")).get("id")).longValue());
            order.setDeviceId(request.getDeviceId());
            order.setFromTime(request.getFrom());
            order.setToTime(request.getTo());
            order.setCreated(new Date());
            order.setTotal(((Number) ((Map<?, ?>) result.get("totals")).get("total")).doubleValue());
            order.setResult(MAPPER.writeValueAsString(result));
            order.setId(storage.addObject(order, new Request(new Columns.Exclude("id"))));
            return order;
        }
    }

    @GET
    public Collection<TravelOrder> get(@QueryParam("deviceId") long deviceId) throws StorageException {
        permissionsService.checkPermission(Device.class, getUserId(), deviceId);
        return storage.getObjects(TravelOrder.class, new Request(
                new Columns.Exclude("result"), new Condition.Equals("deviceId", deviceId),
                new Order("created", true, 0)));
    }

    @GET
    @Path("{id}/pdf")
    @Produces("application/pdf")
    public Response pdf(@PathParam("id") long id) throws StorageException, IOException {
        TravelOrder order = storage.getObject(TravelOrder.class, new Request(
                new Columns.All(), new Condition.Equals("id", id)));
        if (order == null) {
            throw new NotFoundException();
        }
        permissionsService.checkPermission(Device.class, getUserId(), order.getDeviceId());
        Map<String, Object> result = MAPPER.readValue(order.getResult(), new TypeReference<>() {
        });
        return Response.ok(travelOrderPdf.pdf(order.getNumber(), result))
                .header("Content-Disposition", "attachment; filename=CP_" + order.getNumber() + ".pdf").build();
    }

    @DELETE
    @Path("{id}")
    public Response remove(@PathParam("id") long id) throws StorageException {
        TravelOrder order = storage.getObject(TravelOrder.class, new Request(
                new Columns.Include("id", "deviceId"), new Condition.Equals("id", id)));
        if (order != null) {
            permissionsService.checkPermission(Device.class, getUserId(), order.getDeviceId());
            permissionsService.checkEdit(getUserId(), TravelOrder.class, false, false);
            storage.removeObject(TravelOrder.class, new Request(new Condition.Equals("id", id)));
        }
        return Response.noContent().build();
    }

    @GET
    @Path("rates")
    public Collection<AllowanceRate> rates() throws StorageException {
        return rateService.getAll();
    }

    // read slov-lex now instead of waiting for the weekly task
    @POST
    @Path("rates/update")
    public Collection<AllowanceRate> updateRates() throws StorageException {
        permissionsService.checkAdmin(getUserId());
        return rateService.update();
    }

    @GET
    @Path("receipts")
    public Collection<FuelReceipt> receipts(@QueryParam("vehicleId") long vehicleId) throws StorageException {
        return storage.getObjects(FuelReceipt.class, new Request(
                new Columns.All(), new Condition.Equals("vehicleId", vehicleId), new Order("time", true, 0)));
    }

    @POST
    @Path("receipts")
    public FuelReceipt saveReceipt(FuelReceipt receipt) throws StorageException {
        permissionsService.checkEdit(getUserId(), FuelReceipt.class, receipt.getId() == 0, false);
        if (receipt.getTime() == null || storage.getObject(Vehicle.class, new Request(
                new Columns.Include("id"), new Condition.Equals("id", receipt.getVehicleId()))) == null) {
            throw new BadRequestException("time and vehicle required");
        }
        if (receipt.getCurrency() == null || receipt.getCurrency().isBlank()) {
            receipt.setCurrency("EUR");
        }
        if (receipt.getId() > 0) {
            storage.updateObject(receipt, new Request(
                    new Columns.Exclude("id"), new Condition.Equals("id", receipt.getId())));
        } else {
            receipt.setId(storage.addObject(receipt, new Request(new Columns.Exclude("id"))));
        }
        return receipt;
    }

    @DELETE
    @Path("receipts/{id}")
    public Response removeReceipt(@PathParam("id") long id) throws StorageException {
        permissionsService.checkEdit(getUserId(), FuelReceipt.class, false, false);
        storage.removeObject(FuelReceipt.class, new Request(new Condition.Equals("id", id)));
        return Response.noContent().build();
    }

}
