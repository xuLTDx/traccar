/*
 * Copyright 2016 - 2025 Anton Tananaev (anton@traccar.org)
 * Copyright 2016 - 2017 Andrey Kunitsyn (andrey@traccar.org)
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
package org.traccar.reports.common;

import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import org.apache.velocity.app.VelocityEngine;
import org.apache.velocity.tools.generic.DateTool;
import org.apache.velocity.tools.generic.NumberTool;
import org.jxls.area.Area;
import org.jxls.builder.xls.XlsCommentAreaBuilder;
import org.jxls.common.CellRef;
import org.jxls.formula.StandardFormulaProcessor;
import org.jxls.transform.Transformer;
import org.jxls.transform.poi.PoiTransformer;
import org.jxls.util.TransformerFactory;
import org.traccar.api.security.PermissionsService;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.geocoder.Geocoder;
import org.traccar.helper.DistanceCalculator;
import org.traccar.helper.UnitsConverter;
import org.traccar.helper.model.AttributeUtil;
import org.traccar.helper.model.PositionUtil;
import org.traccar.helper.model.UserUtil;
import org.traccar.model.BaseModel;
import org.traccar.model.BusinessAddress;
import org.traccar.model.Device;
import org.traccar.model.Driver;
import org.traccar.model.Event;
import org.traccar.model.Geofence;
import org.traccar.model.Position;
import org.traccar.model.User;
import org.traccar.reports.model.BaseReportItem;
import org.traccar.reports.model.StopReportItem;
import org.traccar.reports.model.TripReportItem;
import org.traccar.session.state.MotionProcessor;
import org.traccar.session.state.MotionState;
import org.traccar.session.state.NewMotionProcessor;
import org.traccar.session.state.NewMotionState;
import org.traccar.storage.Storage;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Order;
import org.traccar.storage.query.Request;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class ReportUtils {

    private final Config config;
    private final Storage storage;
    private final PermissionsService permissionsService;
    private final VelocityEngine velocityEngine;
    private final Geocoder geocoder;

    @Inject
    public ReportUtils(
            Config config, Storage storage, PermissionsService permissionsService,
            VelocityEngine velocityEngine, @Nullable Geocoder geocoder) {
        this.config = config;
        this.storage = storage;
        this.permissionsService = permissionsService;
        this.velocityEngine = velocityEngine;
        this.geocoder = geocoder;
    }

    public <T extends BaseModel> T getObject(long userId, Class<T> clazz, long objectId) {
        try {
            return storage.getObject(clazz, new Request(
                    new Columns.All(),
                    new Condition.And(
                            new Condition.Equals("id", objectId),
                            new Condition.Permission(User.class, userId, clazz))));
        } catch (StorageException e) {
            return null;
        }
    }

    public void checkPeriodLimit(Date from, Date to) {
        long limit = config.getLong(Keys.REPORT_PERIOD_LIMIT) * 1000;
        if (limit > 0 && to.getTime() - from.getTime() > limit) {
            throw new IllegalArgumentException("Time period exceeds the limit");
        }
    }

    public double calculateFuel(Position first, Position last, Device device) {
        if (first.hasAttribute(Position.KEY_FUEL_USED) && last.hasAttribute(Position.KEY_FUEL_USED)) {
            return last.getDouble(Position.KEY_FUEL_USED) - first.getDouble(Position.KEY_FUEL_USED);
        } else if (first.hasAttribute(Position.KEY_FUEL) && last.hasAttribute(Position.KEY_FUEL)) {
            return first.getDouble(Position.KEY_FUEL) - last.getDouble(Position.KEY_FUEL);
        } else if (first.hasAttribute(Position.KEY_FUEL_LEVEL) && last.hasAttribute(Position.KEY_FUEL_LEVEL)
                && device.hasAttribute(Keys.FUEL_CAPACITY.getKey())) {
            return ((first.getDouble(Position.KEY_FUEL_LEVEL) - last.getDouble(Position.KEY_FUEL_LEVEL)) / 100)
                    * device.getDouble(Keys.FUEL_CAPACITY.getKey());
        }
        return 0;
    }

    // Nominatim's fair-use policy caps anonymous usage at 1 request/second,
    // shared across the whole process regardless of which report/user
    // triggered it - a report covering many historical positions that were
    // never geocoded at ingest time (e.g. the first "kniha jazd" run after
    // switching geocoder providers) would otherwise fire off a burst of
    // calls well over that limit. Static + synchronized so it throttles
    // globally, not per ReportUtils instance.
    private static final Object GEOCODE_RATE_LOCK = new Object();
    private static long lastGeocodeRequestMs;
    private static final long GEOCODE_MIN_INTERVAL_MS = 1000;

    // Resolves an address for a position that doesn't have one yet, and -
    // unlike a plain geocoder.getAddress() call - persists it back onto the
    // position so the same historical position never needs re-resolving on
    // a later report view. Without this, an unreliable/rate-limited
    // geocoder makes the same trip's address flicker between a real address
    // and raw coordinates depending on whether that particular retry beat
    // the rate limit.
    private String resolveAndPersistAddress(Position position) throws StorageException {
        if (geocoder == null || !config.getBoolean(Keys.GEOCODER_ON_REQUEST)) {
            return null;
        }
        synchronized (GEOCODE_RATE_LOCK) {
            long wait = GEOCODE_MIN_INTERVAL_MS - (System.currentTimeMillis() - lastGeocodeRequestMs);
            if (wait > 0) {
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
            lastGeocodeRequestMs = System.currentTimeMillis();
        }
        String address = geocoder.getAddress(position.getLatitude(), position.getLongitude(), null);
        if (address != null) {
            position.setAddress(address);
            storage.updateObject(position, new Request(
                    new Columns.Include("address"),
                    new Condition.Equals("id", position.getId())));
        }
        return address;
    }

    // name: short label for the "kniha jazd" (trip logbook) UI to prefill a
    // trip purpose note with (e.g. "Business" - name). address: the real
    // street address to show in the logbook's Start/End Address columns
    // instead of the generic reverse-geocoded one - only BusinessAddress
    // matches have one (a plain Geofence is just a named shape, with no
    // address of its own), so it's null for a geofence match.
    // isGeofence distinguishes a real Traccar geofence match (name()) from a
    // BusinessAddress proximity match (name() + optionally address()) -
    // needed because a geofence's name is fine to show in the trip
    // logbook's Start/End Address columns (e.g. "Domov"), but a
    // BusinessAddress match without a stored address is NOT - that should
    // fall through to the real reverse-geocoded address instead. See
    // calculateTrip() for how this is used.
    public record LocationSuggestion(String name, String address, boolean isGeofence) {
    }

    // Tried in two ways:
    //  1. A position already carries the ids of every geofence it falls
    //     inside (computed at ingest time by the regular geofence handler),
    //     so no new matching logic is needed - just resolve the first one.
    //  2. Failing that, check proximity to any saved BusinessAddress (see
    //     BusinessAddress.java) - a flat list of named points saved
    //     directly from a Stop report row's coordinates, for cases where
    //     drawing a precise geofence circle would be overkill.
    private LocationSuggestion findGeofenceName(Position position) throws StorageException {
        var geofenceIds = position.getGeofenceIds();
        if (geofenceIds != null && !geofenceIds.isEmpty()) {
            var geofence = storage.getObject(Geofence.class, new Request(
                    new Columns.Include("name"),
                    new Condition.Equals("id", geofenceIds.get(0))));
            if (geofence != null) {
                return new LocationSuggestion(geofence.getName(), null, true);
            }
        }
        for (var address : storage.getObjects(BusinessAddress.class, new Request(new Columns.All()))) {
            double distance = DistanceCalculator.distance(
                    position.getLatitude(), position.getLongitude(),
                    address.getLatitude(), address.getLongitude());
            if (distance <= address.getRadius()) {
                return new LocationSuggestion(address.getName(), address.getAddress(), false);
            }
        }
        return null;
    }

    public String findDriver(Position firstPosition, Position lastPosition) {
        if (firstPosition.hasAttribute(Position.KEY_DRIVER_UNIQUE_ID)) {
            return firstPosition.getString(Position.KEY_DRIVER_UNIQUE_ID);
        } else if (lastPosition.hasAttribute(Position.KEY_DRIVER_UNIQUE_ID)) {
            return lastPosition.getString(Position.KEY_DRIVER_UNIQUE_ID);
        }
        return null;
    }

    public String findDriverName(String driverUniqueId) throws StorageException {
        if (driverUniqueId != null) {
            Driver driver = storage.getObject(Driver.class, new Request(
                    new Columns.All(),
                    new Condition.Equals("uniqueId", driverUniqueId)));
            if (driver != null) {
                return driver.getName();
            }
        }
        return null;
    }

    public org.jxls.common.Context initializeContext(long userId) throws StorageException {
        var server = permissionsService.getServer();
        var user = permissionsService.getUser(userId);
        var context = PoiTransformer.createInitialContext();
        context.putVar("distanceUnit", UserUtil.getDistanceUnit(server, user));
        context.putVar("speedUnit", UserUtil.getSpeedUnit(server, user));
        context.putVar("volumeUnit", UserUtil.getVolumeUnit(server, user));
        context.putVar("webUrl", velocityEngine.getProperty("web.url"));
        context.putVar("dateTool", new DateTool());
        context.putVar("numberTool", new NumberTool());
        context.putVar("timezone", UserUtil.getTimezone(server, user));
        context.putVar("locale", Locale.getDefault());
        context.putVar("bracketsRegex", "[\\{\\}\"]");
        return context;
    }

    public void processTemplateWithSheets(
            InputStream templateStream, OutputStream targetStream, org.jxls.common.Context context) throws IOException {

        Transformer transformer = TransformerFactory.createTransformer(templateStream, targetStream);
        List<Area> xlsAreas = new XlsCommentAreaBuilder(transformer).build();
        for (Area xlsArea : xlsAreas) {
            xlsArea.applyAt(new CellRef(xlsArea.getStartCellRef().getCellName()), context);
            xlsArea.setFormulaProcessor(new StandardFormulaProcessor());
            xlsArea.processFormulas();
        }
        transformer.deleteSheet(xlsAreas.get(0).getStartCellRef().getSheetName());
        transformer.write();
    }

    private TripReportItem calculateTrip(
            Device device, Position startTrip, Position endTrip, double maxSpeed,
            boolean ignoreOdometer) throws StorageException {

        TripReportItem trip = new TripReportItem();

        long tripDuration = endTrip.getFixTime().getTime() - startTrip.getFixTime().getTime();
        long deviceId = startTrip.getDeviceId();
        trip.setDeviceId(deviceId);
        trip.setDeviceName(device.getName());

        trip.setStartPositionId(startTrip.getId());
        trip.setStartLat(startTrip.getLatitude());
        trip.setStartLon(startTrip.getLongitude());
        trip.setStartTime(startTrip.getFixTime());
        String startAddress = startTrip.getAddress();
        if (startAddress == null) {
            startAddress = resolveAndPersistAddress(startTrip);
        }
        trip.setStartAddress(startAddress);
        var startSuggestion = findGeofenceName(startTrip);
        if (startSuggestion != null) {
            trip.setStartSuggestedNote(startSuggestion.name());
            if (startSuggestion.isGeofence()) {
                trip.setStartGeofenceName(startSuggestion.name());
            } else {
                trip.setStartBusinessAddress(startSuggestion.address());
            }
        }

        trip.setEndPositionId(endTrip.getId());
        trip.setEndLat(endTrip.getLatitude());
        trip.setEndLon(endTrip.getLongitude());
        trip.setEndTime(endTrip.getFixTime());
        String endAddress = endTrip.getAddress();
        if (endAddress == null) {
            endAddress = resolveAndPersistAddress(endTrip);
        }
        trip.setEndAddress(endAddress);
        var endSuggestion = findGeofenceName(endTrip);
        if (endSuggestion != null) {
            trip.setEndSuggestedNote(endSuggestion.name());
            if (endSuggestion.isGeofence()) {
                trip.setEndGeofenceName(endSuggestion.name());
            } else {
                trip.setEndBusinessAddress(endSuggestion.address());
            }
        }

        trip.setDistance(PositionUtil.calculateDistance(startTrip, endTrip, !ignoreOdometer));
        trip.setDuration(tripDuration);
        if (tripDuration > 0) {
            trip.setAverageSpeed(UnitsConverter.knotsFromMps(trip.getDistance() * 1000 / tripDuration));
        }
        trip.setMaxSpeed(maxSpeed);
        trip.setSpentFuel(calculateFuel(startTrip, endTrip, device));

        trip.setDriverUniqueId(findDriver(startTrip, endTrip));
        trip.setDriverName(findDriverName(trip.getDriverUniqueId()));

        if (!ignoreOdometer
                && startTrip.getDouble(Position.KEY_ODOMETER) != 0
                && endTrip.getDouble(Position.KEY_ODOMETER) != 0) {
            trip.setStartOdometer(startTrip.getDouble(Position.KEY_ODOMETER));
            trip.setEndOdometer(endTrip.getDouble(Position.KEY_ODOMETER));
        } else {
            trip.setStartOdometer(PositionUtil.calibratedOdometer(device, startTrip));
            trip.setEndOdometer(PositionUtil.calibratedOdometer(device, endTrip));
        }

        return trip;
    }

    private StopReportItem calculateStop(
            Device device, Position startStop, Position endStop,
            boolean ignoreOdometer) throws StorageException {

        StopReportItem stop = new StopReportItem();

        long deviceId = startStop.getDeviceId();
        stop.setDeviceId(deviceId);
        stop.setDeviceName(device.getName());

        stop.setPositionId(startStop.getId());
        stop.setLatitude(startStop.getLatitude());
        stop.setLongitude(startStop.getLongitude());
        stop.setStartTime(startStop.getFixTime());
        String address = startStop.getAddress();
        if (address == null) {
            address = resolveAndPersistAddress(startStop);
        }
        stop.setAddress(address);

        stop.setEndTime(endStop.getFixTime());

        long stopDuration = endStop.getFixTime().getTime() - startStop.getFixTime().getTime();
        stop.setDuration(stopDuration);
        stop.setSpentFuel(calculateFuel(startStop, endStop, device));

        if (startStop.hasAttribute(Position.KEY_HOURS) && endStop.hasAttribute(Position.KEY_HOURS)) {
            stop.setEngineHours(endStop.getLong(Position.KEY_HOURS) - startStop.getLong(Position.KEY_HOURS));
        }

        if (!ignoreOdometer
                && startStop.getDouble(Position.KEY_ODOMETER) != 0
                && endStop.getDouble(Position.KEY_ODOMETER) != 0) {
            stop.setStartOdometer(startStop.getDouble(Position.KEY_ODOMETER));
            stop.setEndOdometer(endStop.getDouble(Position.KEY_ODOMETER));
        } else {
            stop.setStartOdometer(PositionUtil.calibratedOdometer(device, startStop));
            stop.setEndOdometer(PositionUtil.calibratedOdometer(device, endStop));
        }

        return stop;

    }

    @SuppressWarnings("unchecked")
    private <T extends BaseReportItem> T calculateTripOrStop(
            Device device, Position startPosition, Position endPosition, double maxSpeed,
            boolean ignoreOdometer, Class<T> reportClass) throws StorageException {

        if (reportClass.equals(TripReportItem.class)) {
            return (T) calculateTrip(device, startPosition, endPosition, maxSpeed, ignoreOdometer);
        } else {
            return (T) calculateStop(device, startPosition, endPosition, ignoreOdometer);
        }
    }

    // ------------------------------------------------------------------
    // Engine-based trip logbook (2026-09-26) - used whenever useIgnition is
    // on, for every range (no fast path), so trips, stops, the logbook and
    // its exports all come from this one computation:
    //  - a trip = engine start -> engine stop. Engine on = ignition true,
    //    or (no ignition attribute) battery >= ENGINE_ON_VOLTAGE; a
    //    position without either keeps the previous state. Engine-off
    //    gaps shorter than MERGE_GAP (start-stop, a short halt) join one
    //    trip; an engine run in which the vehicle never moved is no trip.
    //  - a trip starts where the previous one ended (position, address,
    //    business address), unless it starts > CONTINUITY_RADIUS away
    //    (the vehicle moved without the tracker).
    //  - the odometer is a chain from the calibration anchor: start = the
    //    previous trip's end, end = start + trip distance x factor. Parking
    //    (GPS jitter, catch-up duplicates) never adds to it, and a trip
    //    shows the same numbers in any report range. Trip distance = path
    //    over the trip's positions in fixTime order, duplicates skipped.
    // ------------------------------------------------------------------
    private static final double ENGINE_ON_VOLTAGE = 12.8; // = the Freematics firmware's threshold
    private static final double MAX_SYSTEM_VOLTAGE = 20;  // above: not a 12 V vehicle reading
    private static final long MERGE_GAP = 5 * 60 * 1000;
    private static final long SPLIT_GAP = 30 * 60 * 1000; // no data for this long ends a trip
    private static final double MIN_TRIP_DISTANCE = 200;  // meters
    private static final double CONTINUITY_RADIUS = 500;  // meters
    private static final long CHAIN_LOOKBACK = 7L * 24 * 3600 * 1000; // context without an anchor

    private static final class EngineRun {
        private Position start;
        private Position lastOn;
        private double distance;
        private double maxSpeed;
        private Position lastPoint; // last position with coordinates, for the path
    }

    private static Boolean engineState(Position position) {
        if (position.hasAttribute(Position.KEY_IGNITION)) {
            return position.getBoolean(Position.KEY_IGNITION);
        }
        if (position.hasAttribute(Position.KEY_BATTERY)) {
            double battery = position.getDouble(Position.KEY_BATTERY);
            return battery >= ENGINE_ON_VOLTAGE && battery < MAX_SYSTEM_VOLTAGE;
        }
        return null;
    }

    private static boolean hasCoordinates(Position position) {
        return position.getLatitude() != 0 || position.getLongitude() != 0;
    }

    private static void addPathPoint(EngineRun run, Position position) {
        if (!hasCoordinates(position)) {
            return;
        }
        if (run.lastPoint != null) {
            run.distance += DistanceCalculator.distance(
                    run.lastPoint.getLatitude(), run.lastPoint.getLongitude(),
                    position.getLatitude(), position.getLongitude());
        }
        run.lastPoint = position;
    }

    private List<EngineRun> detectEngineRuns(Device device, Date from, Date to) throws StorageException {
        List<EngineRun> runs = new ArrayList<>();
        EngineRun current = null;
        boolean on = false;
        Date lastFixTime = null;
        Position previous = null;
        try (var stream = PositionUtil.getPositionsStream(storage, device.getId(), from, to, 0)) {
            for (var iterator = stream.iterator(); iterator.hasNext();) {
                Position position = iterator.next();
                if (lastFixTime != null && position.getFixTime().equals(lastFixTime)) {
                    continue; // duplicate (e.g. an SD catch-up replay of a sample that went out live)
                }
                if (previous != null && current != null
                        && position.getFixTime().getTime() - previous.getFixTime().getTime() > SPLIT_GAP) {
                    current = null;
                    on = false;
                }
                lastFixTime = position.getFixTime();
                previous = position;
                Boolean state = engineState(position);
                if (state != null) {
                    on = state;
                }
                if (on) {
                    if (current != null
                            && position.getFixTime().getTime() - current.lastOn.getFixTime().getTime() <= MERGE_GAP) {
                        addPathPoint(current, position); // running, or back on after a short stop
                    } else {
                        current = new EngineRun();
                        current.start = position;
                        runs.add(current);
                        addPathPoint(current, position);
                    }
                    current.lastOn = position;
                    current.maxSpeed = Math.max(current.maxSpeed, position.getSpeed());
                }
            }
        }
        runs.removeIf(run -> run.distance < MIN_TRIP_DISTANCE);
        return runs;
    }

    private static Date parseDate(String value) {
        try {
            return value != null ? Date.from(java.time.Instant.parse(value)) : null;
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private <T extends BaseReportItem> List<T> engineTripsAndStops(
            Device device, Date from, Date to, Class<T> reportClass) throws StorageException {

        boolean ignoreOdometer = new TripsConfig(
                new AttributeUtil.StorageProvider(config, storage, permissionsService, device)).getIgnoreOdometer();
        Date anchorTime = device.hasAttribute("odometerAnchorReal")
                ? parseDate(device.getString("odometerAnchorTime")) : null;
        double factor = device.getDouble("odometerFactor", 1.0);

        // computed from the anchor (or a week back) so the chain and the
        // "previous trip" are the same whatever range is requested
        Date chainFrom = anchorTime != null && anchorTime.before(from)
                ? anchorTime : new Date(from.getTime() - CHAIN_LOOKBACK);
        List<EngineRun> runs = detectEngineRuns(device, chainFrom, to);

        List<TripReportItem> trips = new ArrayList<>();
        double odometer = anchorTime != null ? device.getDouble("odometerAnchorReal") : 0;
        TripReportItem previousTrip = null;
        for (EngineRun run : runs) {
            TripReportItem trip = calculateTrip(device, run.start, run.lastOn, run.maxSpeed, ignoreOdometer);
            trip.setDistance(run.distance);
            if (trip.getDuration() > 0) {
                trip.setAverageSpeed(UnitsConverter.knotsFromMps(run.distance * 1000 / trip.getDuration()));
            }
            if (previousTrip != null && DistanceCalculator.distance(
                    previousTrip.getEndLat(), previousTrip.getEndLon(),
                    trip.getStartLat(), trip.getStartLon()) <= CONTINUITY_RADIUS) {
                trip.setStartLat(previousTrip.getEndLat());
                trip.setStartLon(previousTrip.getEndLon());
                trip.setStartAddress(previousTrip.getEndAddress());
                trip.setStartBusinessAddress(previousTrip.getEndBusinessAddress());
                trip.setStartGeofenceName(previousTrip.getEndGeofenceName());
                trip.setStartSuggestedNote(previousTrip.getEndSuggestedNote());
            }
            double realStart = run.start.getDouble(Position.KEY_ODOMETER);
            double realEnd = run.lastOn.getDouble(Position.KEY_ODOMETER);
            if (!ignoreOdometer && realStart != 0 && realEnd != 0) {
                odometer = realEnd; // a real or closed-segment reading resets the chain
            } else if (anchorTime != null && !run.start.getFixTime().before(anchorTime)) {
                trip.setStartOdometer(odometer);
                odometer += run.distance * factor;
                trip.setEndOdometer(odometer);
            }
            trips.add(trip);
            previousTrip = trip;
        }

        if (reportClass.equals(TripReportItem.class)) {
            List<T> result = new ArrayList<>();
            for (TripReportItem trip : trips) {
                if (trip.getEndTime().after(from) && trip.getStartTime().before(to)) {
                    result.add((T) trip);
                }
            }
            return result;
        }

        // stops = the gaps between trips, at the previous trip's end
        List<T> result = new ArrayList<>();
        for (int i = 0; i < runs.size(); i++) {
            Position stopStart = runs.get(i).lastOn;
            Position stopEnd = i + 1 < runs.size()
                    ? runs.get(i + 1).start : PositionUtil.getEdgePosition(storage, device.getId(), from, to, true);
            if (stopEnd == null || !stopEnd.getFixTime().after(stopStart.getFixTime())) {
                continue;
            }
            if (!stopEnd.getFixTime().after(from) || !stopStart.getFixTime().before(to)) {
                continue;
            }
            StopReportItem stop = calculateStop(device, stopStart, stopEnd, ignoreOdometer);
            TripReportItem trip = trips.get(i);
            stop.setLatitude(trip.getEndLat());
            stop.setLongitude(trip.getEndLon());
            stop.setAddress(trip.getEndAddress());
            if (trip.getEndOdometer() != 0) {
                stop.setStartOdometer(trip.getEndOdometer());
                stop.setEndOdometer(trip.getEndOdometer());
            }
            result.add((T) stop);
        }
        return result;
    }

    public <T extends BaseReportItem> List<T> detectTripsAndStops(
            Device device, Date from, Date to, Class<T> reportClass) throws StorageException {

        if (new TripsConfig(new AttributeUtil.StorageProvider(config, storage, permissionsService, device))
                .getUseIgnition()) {
            return engineTripsAndStops(device, from, to, reportClass);
        }

        long threshold = config.getLong(Keys.REPORT_FAST_THRESHOLD);
        if (Duration.between(from.toInstant(), to.toInstant()).toSeconds() > threshold) {
            return fastTripsAndStops(device, from, to, reportClass);
        } else {
            return slowTripsAndStops(device, from, to, reportClass);
        }
    }

    public <T extends BaseReportItem> List<T> slowTripsAndStops(
            Device device, Date from, Date to, Class<T> reportClass) throws StorageException {

        List<T> result = new ArrayList<>();
        var attributeProvider = new AttributeUtil.StorageProvider(config, storage, permissionsService, device);
        TripsConfig tripsConfig = new TripsConfig(attributeProvider);
        boolean ignoreOdometer = tripsConfig.getIgnoreOdometer();
        boolean trips = reportClass.equals(TripReportItem.class);
        boolean useNewLogic = config.getBoolean(Keys.REPORT_TRIP_NEW_LOGIC);

        List<Event> events = new ArrayList<>();
        Map<Long, Position> positionMap = new HashMap<>();
        Position startPosition = null;
        double maxSpeed = 0;
        Position lastPosition = null;

        if (useNewLogic) {
            double minDistance = AttributeUtil.lookup(attributeProvider, Keys.REPORT_TRIP_MIN_DISTANCE);
            long minDuration = AttributeUtil.lookup(attributeProvider, Keys.REPORT_TRIP_MIN_DURATION) * 1000;
            long stopGap = AttributeUtil.lookup(attributeProvider, Keys.REPORT_TRIP_STOP_GAP) * 1000;
            Deque<Position> motionPositions = new ArrayDeque<>();
            NewMotionState motionState = new NewMotionState();
            motionState.setPositions(motionPositions);

            try (var stream = PositionUtil.getPositionsStream(storage, device.getId(), from, to, 0)) {
                for (var iterator = stream.iterator(); iterator.hasNext();) {
                    Position position = iterator.next();
                    if (lastPosition == null) {
                        boolean initialValue = position.getBoolean(Position.KEY_MOTION);
                        if (initialValue == trips) {
                            startPosition = position;
                            maxSpeed = position.getSpeed();
                        }
                        motionState.setMotionStreak(initialValue);
                        motionState.setEventPosition(position);
                    }
                    maxSpeed = Math.max(maxSpeed, position.getSpeed());
                    positionMap.put(position.getId(), position);
                    NewMotionProcessor.updateState(motionState, position, minDistance, minDuration, stopGap);
                    if (!motionState.getEvents().isEmpty()) {
                        for (Event event : motionState.getEvents()) {
                            event.set("maxSpeed", maxSpeed);
                            events.add(event);
                        }
                        maxSpeed = 0;
                    }
                    motionPositions.add(position);
                    while (motionPositions.size() > 1) {
                        var motionIterator = motionPositions.iterator();
                        motionIterator.next();
                        Position second = motionIterator.next();
                        Position last = motionPositions.peekLast();
                        if (last.getFixTime().getTime() - second.getFixTime().getTime() >= minDuration) {
                            motionPositions.poll();
                        } else {
                            break;
                        }
                    }
                    lastPosition = position;
                }
            }
        } else {
            MotionState motionState = new MotionState();
            // With useIgnition a trip starts when the engine starts, not at the
            // first moving position: before its first GPS fix a vehicle sends
            // fix-less positions (ignition on, speed 0, last known = parking
            // coordinates), and the first moving fix can already be ~1 km away
            // (2026-09-25). ignitionRunStart = first position of the current
            // unbroken ignition=true run (a position without the attribute
            // breaks it too); never earlier than the previous stop.
            boolean useIgnition = tripsConfig.getUseIgnition();
            Position ignitionRunStart = null;
            Date lastStopTime = null;

            try (var stream = PositionUtil.getPositionsStream(storage, device.getId(), from, to, 0)) {
                for (var iterator = stream.iterator(); iterator.hasNext();) {
                    Position position = iterator.next();
                    if (lastPosition == null) {
                        boolean initialValue = position.getBoolean(Position.KEY_MOTION);
                        if (initialValue == trips) {
                            startPosition = position;
                            maxSpeed = position.getSpeed();
                        }
                        motionState.setMotionStreak(initialValue);
                        motionState.setMotionState(initialValue);
                    }
                    maxSpeed = Math.max(maxSpeed, position.getSpeed());
                    positionMap.put(position.getId(), position);
                    if (position.hasAttribute(Position.KEY_IGNITION) && position.getBoolean(Position.KEY_IGNITION)) {
                        if (ignitionRunStart == null) {
                            ignitionRunStart = position;
                        }
                    } else {
                        ignitionRunStart = null;
                    }
                    boolean motion = position.getBoolean(Position.KEY_MOTION);
                    MotionProcessor.updateState(motionState, lastPosition, position, motion, tripsConfig);
                    Event event = motionState.getEvent();
                    if (event != null) {
                        if (useIgnition && ignitionRunStart != null
                                && event.getType().equals(Event.TYPE_DEVICE_MOVING)) {
                            Position motionStart = positionMap.get(event.getPositionId());
                            if (motionStart != null
                                    && ignitionRunStart.getFixTime().before(motionStart.getFixTime())
                                    && (lastStopTime == null || ignitionRunStart.getFixTime().after(lastStopTime))) {
                                event.setPositionId(ignitionRunStart.getId());
                                event.setEventTime(ignitionRunStart.getFixTime());
                            }
                        }
                        if (event.getType().equals(Event.TYPE_DEVICE_STOPPED)) {
                            lastStopTime = event.getEventTime();
                        }
                        event.set("maxSpeed", maxSpeed);
                        events.add(event);
                        maxSpeed = 0;
                    }
                    lastPosition = position;
                }
            }
        }

        for (Event event : events) {
            boolean motion = event.getType().equals(Event.TYPE_DEVICE_MOVING);
            if (motion == trips) {
                startPosition = positionMap.get(event.getPositionId());
            } else if (startPosition != null) {
                Position endPosition = positionMap.get(event.getPositionId());
                if (endPosition != null) {
                    result.add(calculateTripOrStop(
                            device, startPosition, endPosition,
                            event.getDouble("maxSpeed"), ignoreOdometer, reportClass));
                }
                startPosition = null;
            }
        }

        if (startPosition != null) {
            result.add(calculateTripOrStop(
                    device, startPosition, lastPosition, maxSpeed, ignoreOdometer, reportClass));
        }

        return result;
    }

    public <T extends BaseReportItem> List<T> fastTripsAndStops(
            Device device, Date from, Date to, Class<T> reportClass) throws StorageException {

        List<T> result = new ArrayList<>();
        TripsConfig tripsConfig = new TripsConfig(
                new AttributeUtil.StorageProvider(config, storage, permissionsService, device));
        boolean ignoreOdometer = tripsConfig.getIgnoreOdometer();
        boolean trips = reportClass.equals(TripReportItem.class);

        var events = storage.getObjects(Event.class, new Request(
                new Columns.All(),
                Condition.merge(List.of(
                        new Condition.Equals("deviceId", device.getId()),
                        new Condition.Between("eventTime", from, to),
                        new Condition.Or(
                                new Condition.Equals("type", Event.TYPE_DEVICE_MOVING),
                                new Condition.Equals("type", Event.TYPE_DEVICE_STOPPED)))),
                new Order("eventTime")));

        Position startPosition = PositionUtil.getEdgePosition(storage, device.getId(), from, to, false);
        if (startPosition != null && !startPosition.getBoolean(Position.KEY_MOTION)) {
            startPosition = null;
        }

        for (Event event : events) {
            boolean motion = event.getType().equals(Event.TYPE_DEVICE_MOVING);
            if (motion == trips) {
                startPosition = storage.getObject(Position.class, new Request(
                        new Columns.All(),
                        new Condition.And(
                                new Condition.Equals("deviceId", device.getId()),
                                new Condition.Equals("id", event.getPositionId()))));
            } else if (startPosition != null) {
                Position endPosition = storage.getObject(Position.class, new Request(
                        new Columns.All(),
                        new Condition.And(
                                new Condition.Equals("deviceId", device.getId()),
                                new Condition.Equals("id", event.getPositionId()))));
                if (endPosition != null) {
                    result.add(calculateTripOrStop(
                            device, startPosition, endPosition, 0, ignoreOdometer, reportClass));
                }
                startPosition = null;
            }
        }

        if (startPosition != null) {
            Position endPosition = PositionUtil.getEdgePosition(storage, device.getId(), from, to, true);
            result.add(calculateTripOrStop(
                    device, startPosition, endPosition, 0, ignoreOdometer, reportClass));
        }

        return result;
    }

}
