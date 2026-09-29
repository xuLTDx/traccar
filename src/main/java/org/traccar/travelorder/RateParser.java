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

import org.traccar.model.AllowanceRate;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Reads the allowance rates out of slov-lex pages (static.slov-lex.sk). The
// wording these patterns match was checked 2026-09-29 against 73/2024,
// 211/2024, 22/2025, 39/2025, 97/2025, 280/2025, 340/2025 and the
// 401/2012 consolidated version of 30.01.2026. A page that does not match
// gives no rate (never a guessed one) and the updater logs it.
public final class RateParser {

    private static final ZoneId ZONE = ZoneId.of("Europe/Bratislava");

    private static final String[] MONTHS = {
        "januára", "februára", "marca", "apríla", "mája", "júna",
        "júla", "augusta", "septembra", "októbra", "novembra", "decembra",
    };

    private static final Pattern DATE = Pattern.compile(
            "(?:uplatňujú od|nadobúda účinnosť)\\s+(\\d{1,2})\\.\\s*(\\p{L}+)\\s+(\\d{4})");
    private static final Pattern DOMESTIC_1 = Pattern.compile("(\\d+,\\d+) eura pre časové pásmo 5 až 12 hodín");
    private static final Pattern DOMESTIC_2 = Pattern.compile(
            "(\\d+,\\d+) eura pre časové pásmo nad 12 hodín až 18 hodín");
    private static final Pattern DOMESTIC_3 = Pattern.compile("(\\d+,\\d+) eura pre časové pásmo nad 18 hodín");
    private static final Pattern KM = Pattern.compile(
            "(\\d+,\\d+) eura pre osobné vozidlá|osobné cestné motorové vozidlá je (\\d+,\\d+) eura");
    private static final Pattern ROW = Pattern.compile("<tr[^>]*>(.*?)</tr>", Pattern.DOTALL);
    private static final Pattern CELL = Pattern.compile("<td[^>]*>(.*?)</td>", Pattern.DOTALL);
    private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");

    private RateParser() {
    }

    public static String text(String html) {
        return html.replaceAll("(?s)<script.*?</script>", " ")
                .replaceAll("(?s)<style.*?</style>", " ")
                .replaceAll("<[^>]+>", " ")
                .replace("&nbsp;", " ").replace(' ', ' ')
                .replaceAll("\\s+", " ");
    }

    private static double number(String value) {
        return Double.parseDouble(value.replace(" ", "").replace(',', '.'));
    }

    static Date date(String text) {
        Matcher m = DATE.matcher(text);
        if (!m.find()) {
            return null;
        }
        for (int i = 0; i < MONTHS.length; i++) {
            if (MONTHS[i].equals(m.group(2))) {
                LocalDate day = LocalDate.of(Integer.parseInt(m.group(3)), i + 1, Integer.parseInt(m.group(1)));
                return Date.from(day.atStartOfDay(ZONE).toInstant());
            }
        }
        return null;
    }

    public static AllowanceRate domestic(String text, String source) {
        Matcher m1 = DOMESTIC_1.matcher(text);
        Matcher m2 = DOMESTIC_2.matcher(text);
        Matcher m3 = DOMESTIC_3.matcher(text);
        Date validFrom = date(text);
        if (!m1.find() || !m2.find() || !m3.find() || validFrom == null) {
            return null;
        }
        AllowanceRate rate = new AllowanceRate();
        rate.setKind(AllowanceRate.KIND_DOMESTIC);
        rate.setAmount1(number(m1.group(1)));
        rate.setAmount2(number(m2.group(1)));
        rate.setAmount3(number(m3.group(1)));
        rate.setValidFrom(validFrom);
        rate.setSource(source);
        return rate;
    }

    public static AllowanceRate km(String text, String source) {
        Matcher m = KM.matcher(text);
        Date validFrom = date(text);
        if (!m.find() || validFrom == null) {
            return null;
        }
        AllowanceRate rate = new AllowanceRate();
        rate.setKind(AllowanceRate.KIND_KM);
        rate.setAmount1(number(m.group(1) != null ? m.group(1) : m.group(2)));
        rate.setValidFrom(validFrom);
        rate.setSource(source);
        return rate;
    }

    // The § 1 table (Krajina, Menový kód, Mena, Základné sadzby stravného) of a
    // consolidated version; the § 1a table of special rates for some groups
    // of employees follows it and starts repeating countries - it stops there.
    public static List<AllowanceRate> foreign(String html, Date validFrom, String source) {
        List<AllowanceRate> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Matcher row = ROW.matcher(html);
        while (row.find()) {
            List<String> cells = new ArrayList<>();
            Matcher cell = CELL.matcher(row.group(1));
            while (cell.find()) {
                cells.add(text(cell.group(1)).trim());
            }
            if (cells.size() < 4 || !CURRENCY.matcher(cells.get(1)).matches()) {
                continue;
            }
            String name = cells.get(0);
            String iso = CountryNames.code(name);
            if (iso == null && name.length() > 1 && CountryNames.code(name.substring(1)) != null) {
                name = name.substring(1); // "tChorvátsko" in the 30.01.2026 version
                iso = CountryNames.code(name);
            }
            if (!seen.add(name)) {
                break;
            }
            AllowanceRate rate = new AllowanceRate();
            rate.setKind(AllowanceRate.KIND_FOREIGN);
            rate.setCountry(iso);
            rate.setCountryName(name);
            rate.setCurrency(cells.get(1));
            rate.setAmount1(number(cells.get(3)));
            rate.setValidFrom(validFrom);
            rate.setSource(source);
            result.add(rate);
        }
        return result;
    }

}
