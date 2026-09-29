package org.traccar.travelorder;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class TravelOrderPdfTest {

    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            result.put((String) pairs[i], pairs[i + 1]);
        }
        return result;
    }

    @Test
    public void testRender() throws IOException {
        Map<String, Object> result = map(
                "company", map("name", "uLTD s.r.o.", "address", "K. F. Palmu 1752/25, 034 01 Ružomberok",
                        "ico", "47137304", "phone", "+421 951 232 880"),
                "driver", map("name", "Vojtech Plavecký", "initials", "VP", "role", "konateľ",
                        "address", "Karola Františka Palmu 1752/25, 034 01 Ružomberok", "mealAllowance", true),
                "vehicle", map("plate", "RK859CL", "name", "Volkswagen Passat 1.6 TDI", "private", true,
                        "fuel", "NAFTA", "consumption", 4.2, "consumptionSource", "dočasne NEDC auto-data.net"),
                "order", map("from", "22.06.2026 00:00", "to", "24.06.2026 00:00",
                        "places", "Wimmer, Joseph-von-Fraunhofer-Straße 3, 85254 Sulzemoos",
                        "purpose", "pracovná cesta", "mealsFull", false, "mealsPartial", false,
                        "accommodation", true, "breakfastIncluded", true, "travelCosts", false,
                        "pocketMoney", false, "insurance", false, "otherCosts", false,
                        "notes", "Služobná cesta konateľa firmy."),
                "trips", List.of(
                        map("start", "22.06.2026 06:00", "end", "22.06.2026 20:00",
                                "from", "Karola Františka Palmu 1752/25, Ružomberok",
                                "to", "Joseph-von-Fraunhofer-Straße 3, Sulzemoos", "business", true, "note", "",
                                "odoStart", "157700", "odoEnd", "158420", "km", "720"),
                        map("start", "23.06.2026 08:00", "end", "23.06.2026 08:20",
                                "from", "Sulzemoos", "to", "Dachau", "business", false, "note", "súkromná",
                                "odoStart", "158420", "odoEnd", "158432", "km", "12")),
                "days", List.of(
                        map("day", "22.06.2026", "hoursHome", 6.0, "hoursAbroad", 12.0,
                                "countries", "Slovensko 6,0 h, Rakúsko 4,0 h, Nemecko 8,0 h",
                                "domesticBand", "5 – 12 h", "domestic", 9.30, "foreignPercent", 50,
                                "foreignCountry", "Nemecko", "foreign", 22.5, "foreignRate", 45.0,
                                "foreignCurrency", "EUR"),
                        map("day", "23.06.2026", "hoursHome", 0.0, "hoursAbroad", 24.0,
                                "countries", "Nemecko 24,0 h", "domesticBand", "menej ako 5 h", "domestic", 0.0,
                                "foreignPercent", 100, "foreignCountry", "Nemecko", "foreign", 33.75,
                                "foreignRate", 45.0, "foreignCurrency", "EUR",
                                "foreignNote", "raňajky v rámci ubytovania −25 % (§ 13 ods. 8)")),
                "countries", "Slovensko · Rakúsko · Nemecko",
                "fuel", map("consumption", 4.2, "consumptionUsed", 4.62, "litres", 33.26, "receipts", 2,
                        "price", 1.559, "amount", 51.85),
                "km", map("total", 720L, "amount", 225.36, "odoStart", "157700", "odoEnd", "158420",
                        "private", true, "rate", 0.313, "rateSource", "340/2025 Z. z."),
                "totals", map("domestic", 9.30, "foreignEur", 56.25, "foreignOther", "", "km", 225.36,
                        "fuel", 51.85, "advance", 0.0, "total", 342.76),
                "start", "22.06.2026 06:00", "end", "24.06.2026 21:00",
                "startPlace", "Karola Františka Palmu 1752/25, Ružomberok",
                "endPlace", "Karola Františka Palmu 1752/25, Ružomberok",
                "warnings", List.of("Ukážka – vymyslené údaje pre test šablóny."));

        byte[] pdf = new TravelOrderPdf().pdf("VP202601", result);
        assertTrue(pdf.length > 10000);
        assertTrue(new String(pdf, 0, 5).startsWith("%PDF"));
        Path out = Path.of("build", "travelorder-sample.pdf");
        Files.write(out, pdf);
    }

}
