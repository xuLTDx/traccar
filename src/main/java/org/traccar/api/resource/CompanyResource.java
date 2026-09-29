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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.traccar.model.Company;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

// Companies for the travel order (see Company.java). A flat, shared list like
// the vehicles; writes need a non-readonly account. lookup reads a company
// by IČO from the Slovak register of legal persons (RPO, Štatistický úrad
// SR, https://api.statistics.sk/rpo/v1) - only the currently valid values.
@Path("companies")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class CompanyResource extends BaseResource {

    private static final String RPO = "https://api.statistics.sk/rpo/v1/";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20)).build();

    @GET
    public Collection<Company> get() throws StorageException {
        return storage.getObjects(Company.class, new Request(new Columns.All()));
    }

    @POST
    public Company save(Company company) throws StorageException {
        permissionsService.checkEdit(getUserId(), Company.class, company.getId() == 0, false);
        if (company.getName() == null || company.getName().isBlank()) {
            throw new BadRequestException("name required");
        }
        if (company.getId() > 0) {
            storage.updateObject(company, new Request(
                    new Columns.Exclude("id"), new Condition.Equals("id", company.getId())));
        } else {
            company.setId(storage.addObject(company, new Request(new Columns.Exclude("id"))));
        }
        return company;
    }

    @DELETE
    @Path("{id}")
    public Response remove(@PathParam("id") long id) throws StorageException {
        permissionsService.checkEdit(getUserId(), Company.class, false, false);
        storage.removeObject(Company.class, new Request(new Condition.Equals("id", id)));
        return Response.noContent().build();
    }

    private static JsonNode get(String path) throws IOException, InterruptedException {
        HttpResponse<String> response = CLIENT.send(HttpRequest.newBuilder(URI.create(RPO + path))
                .timeout(Duration.ofSeconds(30)).header("Accept", "application/json").GET().build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("RPO " + path + " -> HTTP " + response.statusCode());
        }
        return MAPPER.readTree(response.body());
    }

    // the entry of a history list without validTo (= valid now)
    private static JsonNode current(JsonNode list) {
        JsonNode result = null;
        if (list != null) {
            for (JsonNode item : list) {
                if (!item.hasNonNull("validTo")) {
                    result = item;
                }
            }
        }
        return result;
    }

    private static String text(JsonNode node, String field) {
        return node != null && node.hasNonNull(field) ? node.get(field).asText() : "";
    }

    @GET
    @Path("lookup")
    public Company lookup(@QueryParam("ico") String ico) throws IOException, InterruptedException {
        if (ico == null || !ico.trim().matches("\\d{6,8}")) {
            throw new BadRequestException("IČO");
        }
        JsonNode results = get("search?identifier=" + ico.trim()).get("results");
        if (results == null || results.isEmpty()) {
            throw new NotFoundException();
        }
        JsonNode entity = get("entity/" + results.get(0).get("id").asText());
        Company company = new Company();
        company.setIco(ico.trim());
        company.setName(text(current(entity.get("fullNames")), "value"));
        JsonNode address = current(entity.get("addresses"));
        if (address != null) {
            String number = text(address, "regNumber");
            String building = text(address, "buildingNumber");
            String numbers = number.isEmpty() || "0".equals(number) ? building
                    : building.isEmpty() ? number : number + "/" + building;
            List<String> postal = new ArrayList<>();
            address.path("postalCodes").forEach(code -> postal.add(code.asText()));
            String zip = String.join(" ", postal);
            if (zip.length() == 5) {
                zip = zip.substring(0, 3) + " " + zip.substring(3);
            }
            company.setAddress((text(address, "street") + " " + numbers).trim() + ", "
                    + (zip + " " + text(address.get("municipality"), "value")).trim());
        }
        List<String> statutory = new ArrayList<>();
        for (JsonNode body : entity.path("statutoryBodies")) {
            if (!body.hasNonNull("validTo")) {
                JsonNode name = body.get("personName");
                List<String> parts = new ArrayList<>();
                name.path("givenNames").forEach(part -> parts.add(part.asText()));
                name.path("familyNames").forEach(part -> parts.add(part.asText()));
                statutory.add(text(body.get("stakeholderType"), "value") + " " + String.join(" ", parts));
            }
        }
        company.setStatutory(String.join(", ", statutory));
        return company;
    }

}
