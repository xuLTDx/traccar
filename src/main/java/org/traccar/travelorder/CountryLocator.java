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
package org.traccar.travelorder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Singleton;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import org.locationtech.jts.index.strtree.STRtree;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

// Country (ISO 3166-1 alpha-2) of a GPS position, from the European borders
// bundled as travelorder/countries.geojson (Natural Earth 1:10m). Needed for
// the hours abroad and the border-crossing time (zákon 283/2002 § 13, § 16).
// A position outside every polygon (sea, ferry, outside Europe) gives null.
@Singleton
public class CountryLocator {

    private record Country(String iso, PreparedGeometry geometry) {
    }

    private final GeometryFactory factory = new GeometryFactory();
    private final STRtree index = new STRtree();

    public CountryLocator() {
        try (InputStream in = CountryLocator.class.getResourceAsStream("/travelorder/countries.geojson")) {
            if (in == null) {
                throw new IOException("travelorder/countries.geojson missing");
            }
            JsonNode root = new ObjectMapper().readTree(in);
            for (JsonNode feature : root.get("features")) {
                String iso = feature.get("properties").get("iso").asText();
                JsonNode geometry = feature.get("geometry");
                List<Polygon> polygons = new ArrayList<>();
                if ("Polygon".equals(geometry.get("type").asText())) {
                    polygons.add(polygon(geometry.get("coordinates")));
                } else {
                    for (JsonNode part : geometry.get("coordinates")) {
                        polygons.add(polygon(part));
                    }
                }
                for (Polygon polygon : polygons) {
                    index.insert(polygon.getEnvelopeInternal(),
                            new Country(iso, PreparedGeometryFactory.prepare(polygon)));
                }
            }
            index.build();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private LinearRing ring(JsonNode points) {
        Coordinate[] coordinates = new Coordinate[points.size()];
        for (int i = 0; i < points.size(); i++) {
            coordinates[i] = new Coordinate(points.get(i).get(0).asDouble(), points.get(i).get(1).asDouble());
        }
        return factory.createLinearRing(coordinates);
    }

    private Polygon polygon(JsonNode rings) {
        LinearRing shell = ring(rings.get(0));
        LinearRing[] holes = new LinearRing[rings.size() - 1];
        for (int i = 1; i < rings.size(); i++) {
            holes[i - 1] = ring(rings.get(i));
        }
        return factory.createPolygon(shell, holes);
    }

    public String locate(double latitude, double longitude) {
        Point point = factory.createPoint(new Coordinate(longitude, latitude));
        for (Object candidate : index.query(point.getEnvelopeInternal())) {
            Country country = (Country) candidate;
            if (country.geometry().covers(point)) {
                return country.iso();
            }
        }
        return null;
    }

}
