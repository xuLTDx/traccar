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

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

// ISO 3166-1 alpha-2 <-> the country name as opatrenie MF SR 401/2012 § 1
// writes it, for the countries of travelorder/countries.geojson (Europe and
// its neighbours - travel by car). A foreign rate row whose name is not here
// is stored without an ISO code and never matched to a GPS position.
public final class CountryNames {

    public static final String HOME = "SK";

    private static final Map<String, String> NAMES = new LinkedHashMap<>();
    private static final Map<String, String> CODES = new HashMap<>();

    static {
        put("SK", "Slovensko");
        put("AL", "Albánsko");
        put("AD", "Andorra");
        put("AM", "Arménsko");
        put("AT", "Rakúsko");
        put("AZ", "Azerbajdžan");
        put("BA", "Bosna a Hercegovina");
        put("BE", "Belgicko");
        put("BG", "Bulharsko");
        put("BY", "Bielorusko");
        put("CH", "Švajčiarsko");
        put("CY", "Cyprus");
        put("CZ", "Česko");
        put("DE", "Nemecko");
        put("DK", "Dánsko");
        put("EE", "Estónsko");
        put("ES", "Španielsko");
        put("FI", "Fínsko");
        put("FR", "Francúzsko");
        put("GB", "Spojené kráľovstvo");
        put("GE", "Gruzínsko");
        put("GR", "Grécko");
        put("HR", "Chorvátsko");
        put("HU", "Maďarsko");
        put("IE", "Írsko");
        put("IS", "Island");
        put("IT", "Taliansko");
        put("LI", "Lichtenštajnsko");
        put("LT", "Litva");
        put("LU", "Luxembursko");
        put("LV", "Lotyšsko");
        put("MC", "Monako");
        put("MD", "Moldavsko");
        put("ME", "Čierna Hora");
        put("MK", "Macedónsko");
        put("MT", "Malta");
        put("NL", "Holandsko");
        put("NO", "Nórsko");
        put("PL", "Poľsko");
        put("PT", "Portugalsko");
        put("RO", "Rumunsko");
        put("RS", "Srbsko");
        put("RU", "Rusko");
        put("SE", "Švédsko");
        put("SI", "Slovinsko");
        put("SM", "San Maríno");
        put("TR", "Turecko");
        put("UA", "Ukrajina");
        put("VA", "Vatikán");
        put("XK", "Kosovo");
    }

    private CountryNames() {
    }

    private static void put(String iso, String name) {
        NAMES.put(iso, name);
        CODES.put(name, iso);
    }

    public static String name(String iso) {
        if (iso == null) {
            return "mimo pevniny (more, trajekt)";
        }
        return NAMES.getOrDefault(iso, iso);
    }

    public static String code(String name) {
        return CODES.get(name);
    }

}
