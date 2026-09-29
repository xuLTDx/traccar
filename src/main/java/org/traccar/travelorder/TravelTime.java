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

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

// Time of a business trip per calendar day and country - the basis of
// stravné (zákon 283/2002 § 5 ods. 1 per calendar day; § 13 ods. 1, 4, 5
// per calendar day by hours outside SR and the country of most hours;
// § 16 ods. 2 the border crossing decides). Pure computation, no storage.
public final class TravelTime {

    private TravelTime() {
    }

    // [start, end) while away from the base (home / company)
    public record Interval(Instant start, Instant end) {
    }

    // a GPS position's time and country (null = sea / unknown)
    public record Fix(Instant time, String country) {
    }

    // from `start` the car is in `country` until the next segment
    public record Segment(Instant start, String country) {
    }

    // Country timeline from the positions: the country holds from a fix until
    // the next fix; where two consecutive fixes are in different countries
    // the border is taken as crossed half-way between them (fixes come every
    // few seconds while driving, so the error is seconds).
    public static List<Segment> timeline(List<Fix> fixes) {
        List<Segment> segments = new ArrayList<>();
        Fix previous = null;
        for (Fix fix : fixes) {
            if (previous == null) {
                segments.add(new Segment(fix.time(), fix.country()));
            } else if (!Objects.equals(previous.country(), fix.country())) {
                long middle = (previous.time().toEpochMilli() + fix.time().toEpochMilli()) / 2;
                segments.add(new Segment(Instant.ofEpochMilli(middle), fix.country()));
            }
            previous = fix;
        }
        return segments;
    }

    private static String countryAt(List<Segment> segments, Instant time, String fallback) {
        String country = fallback;
        for (Segment segment : segments) {
            if (segment.start().isAfter(time)) {
                break;
            }
            country = segment.country();
        }
        return country;
    }

    // day -> country -> milliseconds away. A country is null only for sea.
    // Before the first fix the car is in `startCountry` (normally SK).
    public static Map<LocalDate, Map<String, Long>> perDay(
            List<Interval> intervals, List<Segment> segments, String startCountry, ZoneId zone) {
        Map<LocalDate, Map<String, Long>> result = new TreeMap<>();
        for (Interval interval : intervals) {
            // cut points: segment starts and midnights inside the interval
            List<Instant> cuts = new ArrayList<>();
            cuts.add(interval.start());
            for (Segment segment : segments) {
                if (segment.start().isAfter(interval.start()) && segment.start().isBefore(interval.end())) {
                    cuts.add(segment.start());
                }
            }
            LocalDate day = interval.start().atZone(zone).toLocalDate().plusDays(1);
            Instant midnight = day.atStartOfDay(zone).toInstant();
            while (midnight.isBefore(interval.end())) {
                cuts.add(midnight);
                day = day.plusDays(1);
                midnight = day.atStartOfDay(zone).toInstant();
            }
            cuts.add(interval.end());
            cuts.sort(null);
            for (int i = 0; i + 1 < cuts.size(); i++) {
                Instant from = cuts.get(i);
                long length = cuts.get(i + 1).toEpochMilli() - from.toEpochMilli();
                if (length <= 0) {
                    continue;
                }
                String country = countryAt(segments, from, startCountry);
                result.computeIfAbsent(from.atZone(zone).toLocalDate(), k -> new TreeMap<>(
                        (a, b) -> a == null ? (b == null ? 0 : 1) : b == null ? -1 : a.compareTo(b)))
                        .merge(country, length, Long::sum);
            }
        }
        return result;
    }

    // § 5 ods. 1: 0 = under 5 h, 1 = 5-12 h, 2 = over 12-18 h, 3 = over 18 h
    public static int domesticBand(double hours) {
        if (hours > 18) {
            return 3;
        } else if (hours > 12) {
            return 2;
        } else if (hours >= 5) {
            return 1;
        }
        return 0;
    }

    // § 13 ods. 4: 25 % up to 6 h inclusive, 50 % over 6-12 h, 100 % over 12 h
    public static int foreignPercent(double hours) {
        if (hours > 12) {
            return 100;
        } else if (hours > 6) {
            return 50;
        } else if (hours > 0) {
            return 25;
        }
        return 0;
    }

}
