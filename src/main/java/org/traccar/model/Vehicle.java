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
package org.traccar.model;

import org.traccar.storage.StorageName;

// A car in the vehicle registry (2026-09-29), separate from the tracking box:
// a box (Device) is assigned to a vehicle for a period (VehicleAssignment),
// so moving a box to another car keeps the old trips on the old car. The
// data comes from the car's technical certificate (TP / osvedčenie o
// evidencii časť II) and feeds the travel order (cestovný príkaz):
//  - ownership "private" = the km compensation and the fuel compensation are
//    paid (zákon 283/2002 § 7 ods. 1); "company" = neither is paid;
//  - the fuel consumption as the TP states it, in the form § 7 ods. 6 needs
//    (consumptionType picks the letter of § 7 ods. 6 that applies).
@StorageName("tc_vehicles")
public class Vehicle extends BaseModel {

    public static final String OWNERSHIP_COMPANY = "company";
    public static final String OWNERSHIP_PRIVATE = "private";

    // § 7 ods. 6: a) STN only, b) EHK, c) urban / extra-urban / combined
    // cycles, d) one value without cycles, e) electric only
    public static final String CONSUMPTION_STN = "stn";
    public static final String CONSUMPTION_EHK = "ehk";
    public static final String CONSUMPTION_CYCLES = "cycles";
    public static final String CONSUMPTION_SINGLE = "single";
    public static final String CONSUMPTION_ELECTRIC = "electric";

    private String plate;

    public String getPlate() {
        return plate;
    }

    public void setPlate(String plate) {
        this.plate = plate;
    }

    private String name;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    private String vin;

    public String getVin() {
        return vin;
    }

    public void setVin(String vin) {
        this.vin = vin;
    }

    // TP item 2 (J): M1, N1, ...
    private String category;

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    private String ownership = OWNERSHIP_COMPANY;

    public String getOwnership() {
        return ownership;
    }

    public void setOwnership(String ownership) {
        this.ownership = ownership;
    }

    // TP C.1.1 (držiteľ osvedčenia)
    private String holder;

    public String getHolder() {
        return holder;
    }

    public void setHolder(String holder) {
        this.holder = holder;
    }

    // TP item 18 (P.3): NAFTA, BENZÍN, ...
    private String fuel;

    public String getFuel() {
        return fuel;
    }

    public void setFuel(String fuel) {
        this.fuel = fuel;
    }

    // TP item 28 (W), litres; 0 = not stated
    private double tankCapacity;

    public double getTankCapacity() {
        return tankCapacity;
    }

    public void setTankCapacity(double tankCapacity) {
        this.tankCapacity = tankCapacity;
    }

    private String consumptionType;

    public String getConsumptionType() {
        return consumptionType;
    }

    public void setConsumptionType(String consumptionType) {
        this.consumptionType = consumptionType;
    }

    // l/100 km (kWh/100 km for electric); 0 = not stated
    private double consumptionCombined;

    public double getConsumptionCombined() {
        return consumptionCombined;
    }

    public void setConsumptionCombined(double consumptionCombined) {
        this.consumptionCombined = consumptionCombined;
    }

    private double consumptionUrban;

    public double getConsumptionUrban() {
        return consumptionUrban;
    }

    public void setConsumptionUrban(double consumptionUrban) {
        this.consumptionUrban = consumptionUrban;
    }

    private double consumptionExtraUrban;

    public double getConsumptionExtraUrban() {
        return consumptionExtraUrban;
    }

    public void setConsumptionExtraUrban(double consumptionExtraUrban) {
        this.consumptionExtraUrban = consumptionExtraUrban;
    }

    // Where the consumption comes from, printed on the travel order: "TP 51.8",
    // or for a TP without it the document of a vehicle of the same type
    // (§ 7 ods. 10)
    private String consumptionSource;

    public String getConsumptionSource() {
        return consumptionSource;
    }

    public void setConsumptionSource(String consumptionSource) {
        this.consumptionSource = consumptionSource;
    }

    private String note;

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

}
