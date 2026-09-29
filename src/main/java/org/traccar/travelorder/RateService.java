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

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.model.AllowanceRate;
import org.traccar.storage.Storage;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Request;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Allowance rates: looked up by date for the travel order, and kept up to
// date from slov-lex (zákon 283/2002 § 8 ods. 3: the new amounts are
// published in the Zbierka zákonov as an "oznámenie"; foreign rates are
// opatrenie MF SR 401/2012 and its amendments). update() scans the yearly
// index pages, reads every relevant item and stores rates it does not have
// yet; an existing rate (same kind, country, validFrom) is left alone.
@Singleton
public class RateService {

    private static final Logger LOGGER = LoggerFactory.getLogger(RateService.class);

    private static final String BASE = "https://static.slov-lex.sk/static/SK/ZZ/";
    // slov-lex answers 403 to a request without a browser user agent
    private static final String USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) Traccar-travelorder";
    private static final int FIRST_YEAR = 2023;
    private static final ZoneId ZONE = ZoneId.of("Europe/Bratislava");

    private static final Pattern LINK = Pattern.compile("<a[^>]*href=\"(\\d+)/\"[^>]*>([^<]*)</a>");
    private static final Pattern VERSION = Pattern.compile("href=\"(\\d{8})\\.html\"");
    private static final Pattern AMENDS_401 = Pattern.compile("401/2012");

    private final Storage storage;
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20)).followRedirects(HttpClient.Redirect.NORMAL).build();

    @Inject
    public RateService(Storage storage) {
        this.storage = storage;
    }

    public List<AllowanceRate> getAll() throws StorageException {
        return storage.getObjects(AllowanceRate.class, new Request(new Columns.All()));
    }

    // the rate of this kind (and country) valid at `time`: latest validFrom <= time
    public static AllowanceRate find(List<AllowanceRate> rates, String kind, String country, Date time) {
        return rates.stream()
                .filter(r -> r.getKind().equals(kind))
                .filter(r -> country == null || country.equals(r.getCountry()))
                .filter(r -> !r.getValidFrom().after(time))
                .max(Comparator.comparing(AllowanceRate::getValidFrom))
                .orElse(null);
    }

    private String get(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", USER_AGENT).timeout(Duration.ofSeconds(60)).GET().build();
        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IOException(url + " -> HTTP " + response.statusCode());
        }
        return new String(response.body(), StandardCharsets.UTF_8);
    }

    private boolean store(List<AllowanceRate> existing, AllowanceRate rate) throws StorageException {
        for (AllowanceRate other : existing) {
            if (other.getKind().equals(rate.getKind())
                    && Objects.equals(other.getCountryName(), rate.getCountryName())
                    && other.getValidFrom().equals(rate.getValidFrom())) {
                return false;
            }
        }
        rate.setId(storage.addObject(rate, new Request(new Columns.Exclude("id"))));
        existing.add(rate);
        return true;
    }

    // Returns the list of new rates (empty = nothing changed).
    public synchronized List<AllowanceRate> update() throws StorageException {
        List<AllowanceRate> existing = new ArrayList<>(getAll());
        List<AllowanceRate> added = new ArrayList<>();
        List<String> foreignBases = new ArrayList<>();
        int lastYear = LocalDate.now(ZONE).getYear();
        for (int year = FIRST_YEAR; year <= lastYear; year++) {
            String index;
            try {
                index = get(BASE + year + "/");
            } catch (IOException | InterruptedException e) {
                LOGGER.warn("Travel rates: index {} not read", year, e);
                continue;
            }
            Matcher link = LINK.matcher(index);
            while (link.find()) {
                String number = link.group(1);
                String title = link.group(2);
                String source = number + "/" + year + " Z. z.";
                boolean domestic = title.contains("o sumách stravného");
                boolean km = title.contains("o sumách základnej náhrady");
                boolean foreign = title.contains("základné sadzby stravného v eurách alebo v cudzej mene");
                if (!domestic && !km && !foreign) {
                    continue;
                }
                if (foreign) {
                    String base = AMENDS_401.matcher(title).find() ? "2012/401" : year + "/" + number;
                    if (!foreignBases.contains(base)) {
                        foreignBases.add(base);
                    }
                    continue;
                }
                try {
                    String text = RateParser.text(get(BASE + year + "/" + number + "/vyhlasene_znenie.html"));
                    AllowanceRate rate = domestic ? RateParser.domestic(text, source) : RateParser.km(text, source);
                    if (rate == null) {
                        LOGGER.warn("Travel rates: {} not understood: {}", source, title);
                    } else if (store(existing, rate)) {
                        added.add(rate);
                    }
                } catch (IOException | InterruptedException e) {
                    LOGGER.warn("Travel rates: {} not read", source, e);
                }
            }
        }
        if (!foreignBases.contains("2012/401")) {
            foreignBases.add(0, "2012/401");
        }
        for (String base : foreignBases) {
            try {
                Matcher version = VERSION.matcher(get(BASE + base + "/"));
                while (version.find()) {
                    String day = version.group(1);
                    Date validFrom = Date.from(LocalDate.of(
                            Integer.parseInt(day.substring(0, 4)), Integer.parseInt(day.substring(4, 6)),
                            Integer.parseInt(day.substring(6, 8))).atStartOfDay(ZONE).toInstant());
                    String[] parts = base.split("/");
                    String source = parts[1] + "/" + parts[0] + " Z. z. (znenie od "
                            + day.substring(6, 8) + "." + day.substring(4, 6) + "." + day.substring(0, 4) + ")";
                    List<AllowanceRate> rates = RateParser.foreign(
                            get(BASE + base + "/" + day + ".html"), validFrom, source);
                    if (rates.isEmpty()) {
                        LOGGER.warn("Travel rates: no foreign table in {}", source);
                    }
                    for (AllowanceRate rate : rates) {
                        if (store(existing, rate)) {
                            added.add(rate);
                        }
                    }
                }
            } catch (IOException | InterruptedException e) {
                LOGGER.warn("Travel rates: foreign rates {} not read", base, e);
            }
        }
        if (!added.isEmpty()) {
            LOGGER.info("Travel rates: {} new rates stored", added.size());
        }
        return added;
    }

}
