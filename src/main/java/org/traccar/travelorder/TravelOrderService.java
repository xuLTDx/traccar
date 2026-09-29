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
import org.traccar.helper.DistanceCalculator;
import org.traccar.helper.model.PositionUtil;
import org.traccar.model.AllowanceRate;
import org.traccar.model.Device;
import org.traccar.model.Driver;
import org.traccar.model.FuelReceipt;
import org.traccar.model.Position;
import org.traccar.model.Server;
import org.traccar.model.TripPurpose;
import org.traccar.model.Vehicle;
import org.traccar.model.VehicleAssignment;
import org.traccar.reports.common.ReportUtils;
import org.traccar.reports.model.TripReportItem;
import org.traccar.storage.Storage;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

// Computes a travel order (cestovný príkaz + vyúčtovanie) for one box/driver
// and period, per zákon 283/2002 (text verified on slov-lex, version
// 01.11.2025):
//  - trips: the logbook's trips of the period, business purpose only;
//  - away intervals: from a business trip that starts at the base until a
//    business trip ends back at the base (base = start of the first trip,
//    plus the driver's home when set; radius BASE_RADIUS);
//  - stravné per calendar day (§ 5 ods. 1): domestic band by the hours in
//    SR that day (§ 16 ods. 1), foreign by the hours outside SR that day
//    (§ 13 ods. 1, 4) in the rate of the country of most hours (§ 13 ods. 5);
//    the country of every GPS position decides (border time, § 16 ods. 2);
//  - km compensation (§ 7 ods. 1, 2) only for a private vehicle, km only
//    from the car's odometer (a trip without readings has no km - listed as
//    a warning, never GPS), rate valid on the trip's day;
//  - fuel compensation (§ 7 ods. 4-6): km x consumption from the vehicle
//    document +10 % x unit price = arithmetic mean of the receipts' unit
//    prices in the period (§ 7 ods. 5); without a receipt: a warning.
// The result is a plain map (stored as JSON when issued, rendered to PDF).
@Singleton
public class TravelOrderService {

    public static final ZoneId ZONE = ZoneId.of("Europe/Bratislava");
    private static final double BASE_RADIUS = 500;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private final Storage storage;
    private final ReportUtils reportUtils;
    private final CountryLocator countryLocator;
    private final RateService rateService;

    @Inject
    public TravelOrderService(
            Storage storage, ReportUtils reportUtils, CountryLocator countryLocator, RateService rateService) {
        this.storage = storage;
        this.reportUtils = reportUtils;
        this.countryLocator = countryLocator;
        this.rateService = rateService;
    }

    private static String time(Date date) {
        return date == null ? "" : TIME.format(date.toInstant().atZone(ZONE));
    }

    private static double round2(double value) {
        return Math.round(value * 100) / 100.0;
    }

    private static String initials(String name) {
        StringBuilder result = new StringBuilder();
        for (String part : name.trim().split("\\s+")) {
            if (!part.isEmpty()) {
                result.append(Character.toUpperCase(part.charAt(0)));
            }
        }
        return result.toString();
    }

    public static String driverInitials(Driver driver) {
        String value = driver.getString("initials");
        return value != null && !value.isBlank() ? value.trim().toUpperCase(Locale.ROOT) : initials(driver.getName());
    }

    private static String tripEndAddress(TripReportItem trip, TripPurpose purpose) {
        if (purpose != null && purpose.getEndAddress() != null) {
            return purpose.getEndAddress();
        }
        if (trip.getEndBusinessAddress() != null) {
            return trip.getEndBusinessAddress();
        }
        if (trip.getEndGeofenceName() != null) {
            return trip.getEndGeofenceName();
        }
        return trip.getEndAddress() != null ? trip.getEndAddress() : trip.getEndLat() + ", " + trip.getEndLon();
    }

    private static String tripStartAddress(TripReportItem trip, TripPurpose purpose) {
        if (purpose != null && purpose.getStartAddress() != null) {
            return purpose.getStartAddress();
        }
        if (trip.getStartBusinessAddress() != null) {
            return trip.getStartBusinessAddress();
        }
        if (trip.getStartGeofenceName() != null) {
            return trip.getStartGeofenceName();
        }
        return trip.getStartAddress() != null ? trip.getStartAddress()
                : trip.getStartLat() + ", " + trip.getStartLon();
    }

    private record Trip(TripReportItem item, TripPurpose purpose) {
        boolean business() {
            return purpose == null || TripPurpose.PURPOSE_BUSINESS.equals(purpose.getPurpose());
        }
    }

    private List<Trip> trips(Device device, Date from, Date to) throws StorageException {
        Map<String, TripPurpose> purposes = new HashMap<>();
        for (TripPurpose purpose : storage.getObjects(TripPurpose.class, new Request(
                new Columns.All(), new Condition.Equals("deviceId", device.getId())))) {
            purposes.put(purpose.getStartPositionId() + "-" + purpose.getEndPositionId(), purpose);
        }
        List<Trip> result = new ArrayList<>();
        for (TripReportItem item : reportUtils.detectTripsAndStops(device, from, to, TripReportItem.class)) {
            result.add(new Trip(item, purposes.get(item.getStartPositionId() + "-" + item.getEndPositionId())));
        }
        return result;
    }

    public Vehicle vehicle(long deviceId, Date time) throws StorageException {
        for (VehicleAssignment assignment : storage.getObjects(VehicleAssignment.class, new Request(
                new Columns.All(), new Condition.Equals("deviceId", deviceId)))) {
            if (!assignment.getFromTime().after(time)
                    && (assignment.getToTime() == null || assignment.getToTime().after(time))) {
                return storage.getObject(Vehicle.class, new Request(
                        new Columns.All(), new Condition.Equals("id", assignment.getVehicleId())));
            }
        }
        return null;
    }

    // miesto konania candidates: business trip destinations away from the base
    public List<String> places(Device device, Driver driver, Date from, Date to) throws StorageException {
        List<Trip> trips = trips(device, from, to).stream().filter(Trip::business).toList();
        List<double[]> base = base(trips, driver);
        Set<String> result = new LinkedHashSet<>();
        for (Trip trip : trips) {
            if (!atBase(base, trip.item().getEndLat(), trip.item().getEndLon())) {
                result.add(tripEndAddress(trip.item(), trip.purpose()));
            }
        }
        return new ArrayList<>(result);
    }

    private static List<double[]> base(List<Trip> trips, Driver driver) {
        List<double[]> base = new ArrayList<>();
        if (!trips.isEmpty()) {
            base.add(new double[] {trips.get(0).item().getStartLat(), trips.get(0).item().getStartLon()});
        }
        if (driver != null && driver.hasAttribute("homeLatitude") && driver.hasAttribute("homeLongitude")) {
            base.add(new double[] {driver.getDouble("homeLatitude"), driver.getDouble("homeLongitude")});
        }
        return base;
    }

    private static boolean atBase(List<double[]> base, double latitude, double longitude) {
        for (double[] point : base) {
            if (DistanceCalculator.distance(point[0], point[1], latitude, longitude) <= BASE_RADIUS) {
                return true;
            }
        }
        return false;
    }

    public Map<String, Object> compute(TravelOrderRequest request) throws StorageException {
        Device device = storage.getObject(Device.class, new Request(
                new Columns.All(), new Condition.Equals("id", request.getDeviceId())));
        Driver driver = request.getDriverId() > 0 ? storage.getObject(Driver.class, new Request(
                new Columns.All(), new Condition.Equals("id", request.getDriverId()))) : null;
        Server server = storage.getObject(Server.class, new Request(new Columns.All()));
        List<AllowanceRate> rates = rateService.getAll();
        List<String> warnings = new ArrayList<>();

        List<Trip> all = trips(device, request.getFrom(), request.getTo());
        List<Trip> trips = all.stream().filter(Trip::business).toList();
        Vehicle vehicle = vehicle(device.getId(),
                trips.isEmpty() ? request.getFrom() : trips.get(0).item().getStartTime());
        boolean privateCar = vehicle != null && Vehicle.OWNERSHIP_PRIVATE.equals(vehicle.getOwnership());
        boolean mealAllowance = driver == null || !driver.hasAttribute("mealAllowance")
                || driver.getBoolean("mealAllowance");

        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, Object> company = new LinkedHashMap<>();
        company.put("name", server.getString("companyName", ""));
        company.put("address", server.getString("companyAddress", ""));
        company.put("ico", server.getString("companyIco", ""));
        company.put("phone", server.getString("companyPhone", ""));
        result.put("company", company);

        Map<String, Object> person = new LinkedHashMap<>();
        person.put("name", driver != null ? driver.getName() : "");
        person.put("initials", driver != null ? driverInitials(driver) : "");
        person.put("role", driver != null ? driver.getString("role", "") : "");
        person.put("address", driver != null ? driver.getString("address", "") : "");
        person.put("mealAllowance", mealAllowance);
        result.put("driver", person);

        Map<String, Object> car = new LinkedHashMap<>();
        if (vehicle != null) {
            car.put("plate", vehicle.getPlate());
            car.put("name", vehicle.getName());
            car.put("private", privateCar);
            car.put("fuel", vehicle.getFuel());
            car.put("consumption", vehicle.getConsumptionCombined());
            car.put("consumptionSource", vehicle.getConsumptionSource());
        } else {
            warnings.add("Krabička nie je v tomto období priradená k žiadnemu vozidlu (Reporty → Vozidlá).");
        }
        result.put("vehicle", car);

        Map<String, Object> order = new LinkedHashMap<>();
        order.put("from", time(request.getFrom()));
        order.put("to", time(request.getTo()));
        order.put("places", String.join(" + ", request.getPlaces()));
        order.put("purpose", request.getPurpose());
        order.put("mealsFull", request.getMealsFull());
        order.put("mealsPartial", request.getMealsPartial());
        order.put("accommodation", request.getAccommodation());
        order.put("breakfastIncluded", request.getBreakfastIncluded());
        order.put("travelCosts", request.getTravelCosts());
        order.put("pocketMoney", request.getPocketMoney());
        order.put("insurance", request.getInsurance());
        order.put("otherCosts", request.getOtherCosts());
        order.put("notes", request.getNotes() != null ? request.getNotes() : "");
        result.put("order", order);

        // trips (logbook page) and km
        List<Map<String, Object>> tripRows = new ArrayList<>();
        long kmTotal = 0;
        double kmAmount = 0;
        int missing = 0;
        Double odoStart = null;
        Double odoEnd = null;
        for (Trip trip : all) {
            TripReportItem item = trip.item();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("start", time(item.getStartTime()));
            row.put("end", time(item.getEndTime()));
            row.put("from", tripStartAddress(item, trip.purpose()));
            row.put("to", tripEndAddress(item, trip.purpose()));
            row.put("business", trip.business());
            row.put("note", trip.purpose() != null && trip.purpose().getNote() != null ? trip.purpose().getNote() : "");
            if (item.getOdometerMissing()) {
                row.put("odoStart", "");
                row.put("odoEnd", "");
                row.put("km", "");
                if (trip.business()) {
                    missing++;
                }
            } else {
                long km = Math.round(Math.ceil(item.getDistance() / 1000 - 1e-9));
                row.put("odoStart", String.valueOf(Math.round(item.getStartOdometer() / 1000)));
                row.put("odoEnd", String.valueOf(Math.round(item.getEndOdometer() / 1000)));
                row.put("km", String.valueOf(km));
                if (trip.business()) {
                    kmTotal += km;
                    if (odoStart == null) {
                        odoStart = item.getStartOdometer();
                    }
                    odoEnd = item.getEndOdometer();
                    if (privateCar) {
                        AllowanceRate rate = RateService.find(
                                rates, AllowanceRate.KIND_KM, null, item.getStartTime());
                        if (rate == null) {
                            warnings.add("Chýba sadzba náhrady za km pre " + time(item.getStartTime()) + ".");
                        } else {
                            kmAmount += km * rate.getAmount1();
                            row.put("kmRate", rate.getAmount1());
                        }
                    }
                }
            }
            tripRows.add(row);
        }
        if (missing > 0) {
            warnings.add(missing + " služobných jázd nemá stav tachometra - ich km nie sú započítané.");
        }
        result.put("trips", tripRows);

        // away intervals and the country of every position
        List<double[]> base = base(trips, driver);
        List<TravelTime.Interval> intervals = new ArrayList<>();
        Instant open = null;
        for (Trip trip : trips) {
            if (open == null) {
                open = trip.item().getStartTime().toInstant();
            }
            if (atBase(base, trip.item().getEndLat(), trip.item().getEndLon())) {
                intervals.add(new TravelTime.Interval(open, trip.item().getEndTime().toInstant()));
                open = null;
            }
        }
        if (open != null) {
            intervals.add(new TravelTime.Interval(open, trips.get(trips.size() - 1).item().getEndTime().toInstant()));
            warnings.add("Posledná jazda nekončí doma - cesta je ukončená koncom poslednej jazdy.");
        }
        List<TravelTime.Fix> fixes = new ArrayList<>();
        if (!intervals.isEmpty()) {
            Date first = Date.from(intervals.get(0).start());
            Date last = Date.from(intervals.get(intervals.size() - 1).end());
            try (var positions = PositionUtil.getPositionsStream(storage, device.getId(), first, last, 0)) {
                positions.filter(Position::getValid).forEach(p -> fixes.add(new TravelTime.Fix(
                        p.getFixTime().toInstant(), countryLocator.locate(p.getLatitude(), p.getLongitude()))));
            }
        }
        List<TravelTime.Segment> segments = TravelTime.timeline(fixes);
        Map<LocalDate, Map<String, Long>> perDay = TravelTime.perDay(
                intervals, segments, CountryNames.HOME, ZONE);

        // stravné per day
        List<Map<String, Object>> days = new ArrayList<>();
        double domesticTotal = 0;
        Map<String, Double> foreignTotals = new TreeMap<>();
        Set<String> countries = new LinkedHashSet<>();
        LocalDate previousAbroad = null;
        for (Map.Entry<LocalDate, Map<String, Long>> entry : perDay.entrySet()) {
            LocalDate day = entry.getKey();
            Date noon = Date.from(day.atTime(12, 0).atZone(ZONE).toInstant());
            Map<String, Long> hours = entry.getValue();
            double homeHours = hours.getOrDefault(CountryNames.HOME, 0L) / 3600000.0;
            double abroadHours = hours.entrySet().stream()
                    .filter(e -> !CountryNames.HOME.equals(e.getKey()))
                    .mapToLong(Map.Entry::getValue).sum() / 3600000.0;
            countries.addAll(hours.keySet().stream().map(CountryNames::name).toList());

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("day", DAY.format(day));
            row.put("hoursHome", round2(homeHours));
            row.put("hoursAbroad", round2(abroadHours));
            row.put("countries", hours.entrySet().stream()
                    .map(e -> CountryNames.name(e.getKey()) + " " + String.format(Locale.ROOT, "%.1f",
                            e.getValue() / 3600000.0).replace('.', ',') + " h")
                    .collect(Collectors.joining(", ")));

            double domestic = 0;
            int band = TravelTime.domesticBand(homeHours);
            if (mealAllowance && band > 0) {
                AllowanceRate rate = RateService.find(rates, AllowanceRate.KIND_DOMESTIC, null, noon);
                if (rate == null) {
                    warnings.add("Chýba sadzba tuzemského stravného pre " + DAY.format(day) + ".");
                } else {
                    domestic = band == 1 ? rate.getAmount1() : band == 2 ? rate.getAmount2() : rate.getAmount3();
                    row.put("domesticSource", rate.getSource());
                }
            }
            row.put("domesticBand", band == 0 ? "menej ako 5 h" : band == 1 ? "5 – 12 h"
                    : band == 2 ? "nad 12 – 18 h" : "nad 18 h");
            row.put("domestic", domestic);
            domesticTotal += domestic;

            int percent = TravelTime.foreignPercent(abroadHours);
            row.put("foreignPercent", percent);
            if (percent > 0) {
                String main = null;
                long mainTime = -1;
                double mainAmount = -1;
                for (Map.Entry<String, Long> country : hours.entrySet()) {
                    if (CountryNames.HOME.equals(country.getKey())) {
                        continue;
                    }
                    AllowanceRate rate = country.getKey() != null ? RateService.find(
                            rates, AllowanceRate.KIND_FOREIGN, country.getKey(), noon) : null;
                    double amount = rate != null ? rate.getAmount1() : 0;
                    // § 13 ods. 5: most hours; equal hours -> the better rate
                    if (country.getValue() > mainTime || country.getValue() == mainTime && amount > mainAmount) {
                        main = country.getKey();
                        mainTime = country.getValue();
                        mainAmount = amount;
                    }
                }
                row.put("foreignCountry", CountryNames.name(main));
                AllowanceRate rate = main != null ? RateService.find(
                        rates, AllowanceRate.KIND_FOREIGN, main, noon) : null;
                if (request.getMealsFull()) {
                    row.put("foreign", 0.0);
                    row.put("foreignCurrency", rate != null ? rate.getCurrency() : "EUR");
                    row.put("foreignNote", "bezplatné stravovanie v plnom rozsahu (§ 13 ods. 7)");
                } else if (rate == null) {
                    warnings.add("Chýba zahraničná sadzba pre " + CountryNames.name(main) + " (" + DAY.format(day)
                            + ").");
                } else {
                    double amount = rate.getAmount1() * percent / 100.0;
                    if (request.getBreakfastIncluded() && previousAbroad != null
                            && previousAbroad.equals(day.minusDays(1))) {
                        amount -= rate.getAmount1() * 0.25;
                        row.put("foreignNote", "raňajky v rámci ubytovania −25 % (§ 13 ods. 8)");
                    }
                    amount = Math.max(0, round2(amount));
                    row.put("foreign", amount);
                    row.put("foreignRate", rate.getAmount1());
                    row.put("foreignCurrency", rate.getCurrency());
                    row.put("foreignSource", rate.getSource());
                    foreignTotals.merge(rate.getCurrency(), amount, Double::sum);
                }
                previousAbroad = day;
            }
            days.add(row);
        }
        if (!mealAllowance) {
            warnings.add("Šofér nemá nárok na stravné (nastavenie šoféra).");
        }
        result.put("days", days);
        result.put("countries", String.join(" · ", countries));

        // fuel
        Map<String, Object> fuel = new LinkedHashMap<>();
        double fuelAmount = 0;
        if (privateCar) {
            double consumption = vehicle.getConsumptionCombined();
            List<FuelReceipt> receipts = storage.getObjects(FuelReceipt.class, new Request(
                    new Columns.All(), new Condition.And(
                            new Condition.Equals("vehicleId", vehicle.getId()),
                            new Condition.Between("time", request.getFrom(), request.getTo()))));
            List<FuelReceipt> usable = receipts.stream()
                    .filter(r -> r.getLitres() > 0 && "EUR".equals(r.getCurrency()) && !r.getCompanyCard())
                    .toList();
            double litres = kmTotal * consumption * 1.1 / 100;
            fuel.put("consumption", consumption);
            fuel.put("consumptionUsed", round2(consumption * 1.1));
            fuel.put("litres", round2(litres));
            fuel.put("receipts", receipts.size());
            if (consumption <= 0) {
                warnings.add("Vozidlo nemá zadanú spotrebu - náhrada za PHM sa nevypočítala.");
            } else if (usable.isEmpty()) {
                warnings.add("Žiadny bloček PHM v období - náhrada za PHM sa nevypočítala"
                        + " (bez dokladu platí cena ŠÚ SR v deň nástupu, § 7 ods. 5).");
            } else {
                double price = usable.stream().mapToDouble(r -> r.getAmount() / r.getLitres()).average().orElse(0);
                fuel.put("price", round2(price * 1000) / 1000);
                fuelAmount = round2(litres * price);
            }
        }
        fuel.put("amount", fuelAmount);
        result.put("fuel", fuel);

        Map<String, Object> km = new LinkedHashMap<>();
        km.put("total", kmTotal);
        km.put("amount", round2(kmAmount));
        km.put("odoStart", odoStart != null ? String.valueOf(Math.round(odoStart / 1000)) : "");
        km.put("odoEnd", odoEnd != null ? String.valueOf(Math.round(odoEnd / 1000)) : "");
        km.put("private", privateCar);
        AllowanceRate kmRate = privateCar && !trips.isEmpty() ? RateService.find(
                rates, AllowanceRate.KIND_KM, null, trips.get(0).item().getStartTime()) : null;
        km.put("rate", kmRate != null ? kmRate.getAmount1() : 0);
        km.put("rateSource", kmRate != null ? kmRate.getSource() : "");
        result.put("km", km);

        Map<String, Object> totals = new LinkedHashMap<>();
        double foreignEur = foreignTotals.getOrDefault("EUR", 0.0);
        totals.put("domestic", round2(domesticTotal));
        totals.put("foreignEur", round2(foreignEur));
        Map<String, Double> other = new TreeMap<>(foreignTotals);
        other.remove("EUR");
        totals.put("foreignOther", other.entrySet().stream()
                .map(e -> String.format(Locale.ROOT, "%.2f", e.getValue()).replace('.', ',') + " " + e.getKey())
                .collect(Collectors.joining(" + ")));
        totals.put("km", round2(kmAmount));
        totals.put("fuel", fuelAmount);
        totals.put("advance", round2(request.getAdvance()));
        totals.put("total", round2(domesticTotal + foreignEur + kmAmount + fuelAmount - request.getAdvance()));
        result.put("totals", totals);

        if (!intervals.isEmpty()) {
            result.put("start", time(Date.from(intervals.get(0).start())));
            result.put("end", time(Date.from(intervals.get(intervals.size() - 1).end())));
            result.put("startPlace", tripStartAddress(trips.get(0).item(), trips.get(0).purpose()));
            Trip lastTrip = trips.get(trips.size() - 1);
            result.put("endPlace", tripEndAddress(lastTrip.item(), lastTrip.purpose()));
        } else {
            warnings.add("V období nie je žiadna služobná jazda.");
        }
        result.put("warnings", warnings);
        return result;
    }

}
