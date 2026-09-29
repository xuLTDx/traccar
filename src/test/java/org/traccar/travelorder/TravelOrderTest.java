package org.traccar.travelorder;

import org.junit.jupiter.api.Test;
import org.traccar.model.AllowanceRate;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

// Fixtures in src/test/resources/travelorder are the real slov-lex pages
// (read 2026-09-29): 280/2025, 340/2025, 73/2024, 211/2024, 401/2012 as of
// 30.01.2026.
public class TravelOrderTest {

    private static final ZoneId ZONE = ZoneId.of("Europe/Bratislava");

    private static String fixture(String name) throws IOException {
        try (InputStream in = TravelOrderTest.class.getResourceAsStream("/travelorder/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Date day(int year, int month, int day) {
        return Date.from(LocalDate.of(year, month, day).atStartOfDay(ZONE).toInstant());
    }

    @Test
    public void testDomesticRates() throws IOException {
        AllowanceRate rate = RateParser.domestic(fixture("2025_280.txt"), "280/2025 Z. z.");
        assertNotNull(rate);
        assertEquals(9.30, rate.getAmount1());
        assertEquals(13.80, rate.getAmount2());
        assertEquals(20.60, rate.getAmount3());
        assertEquals(day(2025, 12, 1), rate.getValidFrom());

        rate = RateParser.domestic(fixture("2024_211.txt"), "211/2024 Z. z.");
        assertNotNull(rate);
        assertEquals(8.30, rate.getAmount1());
        assertEquals(12.30, rate.getAmount2());
        assertEquals(18.40, rate.getAmount3());
        assertEquals(day(2024, 9, 1), rate.getValidFrom());

        assertNull(RateParser.domestic(fixture("2025_340.txt"), "340/2025 Z. z."));
    }

    @Test
    public void testKmRates() throws IOException {
        AllowanceRate rate = RateParser.km(fixture("2025_340.txt"), "340/2025 Z. z.");
        assertNotNull(rate);
        assertEquals(0.313, rate.getAmount1());
        assertEquals(day(2026, 1, 1), rate.getValidFrom());

        rate = RateParser.km(fixture("2024_73.txt"), "73/2024 Z. z.");
        assertNotNull(rate);
        assertEquals(0.265, rate.getAmount1());
        assertEquals(day(2024, 5, 1), rate.getValidFrom());
    }

    @Test
    public void testForeignRates() throws IOException {
        List<AllowanceRate> rates = RateParser.foreign(
                fixture("2012_401_20260130.html"), day(2026, 1, 30), "401/2012 Z. z.");
        // § 1 table only (the § 1a special rates repeat countries after it)
        assertEquals(193, rates.size());
        AllowanceRate germany = rates.stream().filter(r -> "DE".equals(r.getCountry())).findFirst().orElseThrow();
        assertEquals(45, germany.getAmount1());
        assertEquals("EUR", germany.getCurrency());
        AllowanceRate sweden = rates.stream().filter(r -> "SE".equals(r.getCountry())).findFirst().orElseThrow();
        assertEquals(455, sweden.getAmount1());
        assertEquals("SEK", sweden.getCurrency());
        AllowanceRate czechia = rates.stream().filter(r -> "CZ".equals(r.getCountry())).findFirst().orElseThrow();
        assertEquals(600, czechia.getAmount1());
        AllowanceRate croatia = rates.stream().filter(r -> "HR".equals(r.getCountry())).findFirst().orElseThrow();
        assertEquals(40, croatia.getAmount1());
        AllowanceRate japan = rates.stream().filter(r -> "Japonsko".equals(r.getCountryName())).findFirst()
                .orElseThrow();
        assertEquals(6500, japan.getAmount1());
    }

    @Test
    public void testCountryLocator() {
        CountryLocator locator = new CountryLocator();
        assertEquals("SK", locator.locate(49.0795, 19.2869)); // Ružomberok
        assertEquals("AT", locator.locate(48.2082, 16.3738)); // Wien
        assertEquals("DE", locator.locate(48.3705, 10.8978)); // Augsburg
        assertEquals("SE", locator.locate(55.6050, 13.0038)); // Malmö
        assertNull(locator.locate(55.5, 16.0)); // Baltic sea east of Bornholm
    }

    private static Instant at(int month, int day, int hour, int minute) {
        return LocalDate.of(2026, month, day).atTime(hour, minute).atZone(ZONE).toInstant();
    }

    @Test
    public void testBands() {
        assertEquals(0, TravelTime.domesticBand(4.99));
        assertEquals(1, TravelTime.domesticBand(5));
        assertEquals(1, TravelTime.domesticBand(12));
        assertEquals(2, TravelTime.domesticBand(12.01));
        assertEquals(3, TravelTime.domesticBand(18.01));
        assertEquals(25, TravelTime.foreignPercent(6));
        assertEquals(50, TravelTime.foreignPercent(6.01));
        assertEquals(50, TravelTime.foreignPercent(12));
        assertEquals(100, TravelTime.foreignPercent(12.01));
    }

    // The user's example: leaving at 06:00, crossing SK/AT at 12:00, then
    // Germany - the first day has 6 h in SR and 12 h abroad (50 %, Germany
    // has the most hours), the next day is all in Germany (100 %).
    @Test
    public void testDaysAndCountries() {
        List<TravelTime.Fix> fixes = List.of(
                new TravelTime.Fix(at(6, 22, 6, 0), "SK"),
                new TravelTime.Fix(at(6, 22, 11, 59), "SK"),
                new TravelTime.Fix(at(6, 22, 12, 1), "AT"),
                new TravelTime.Fix(at(6, 22, 15, 59), "AT"),
                new TravelTime.Fix(at(6, 22, 16, 1), "DE"),
                new TravelTime.Fix(at(6, 22, 20, 0), "DE"),
                new TravelTime.Fix(at(6, 23, 18, 0), "DE"));
        List<TravelTime.Interval> intervals = List.of(new TravelTime.Interval(at(6, 22, 6, 0), at(6, 23, 18, 0)));
        Map<LocalDate, Map<String, Long>> days = TravelTime.perDay(
                intervals, TravelTime.timeline(fixes), "SK", ZONE);
        Map<String, Long> first = days.get(LocalDate.of(2026, 6, 22));
        assertEquals(6 * 3600000L, first.get("SK"));
        assertEquals(4 * 3600000L, first.get("AT"));
        assertEquals(8 * 3600000L, first.get("DE"));
        Map<String, Long> second = days.get(LocalDate.of(2026, 6, 23));
        assertEquals(18 * 3600000L, second.get("DE"));
        assertEquals(1, second.size());
    }

}
