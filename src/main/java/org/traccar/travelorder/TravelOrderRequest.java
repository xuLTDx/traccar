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

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

// What the user fills in before the export (the dialog on the logbook page).
public class TravelOrderRequest {

    private long deviceId;

    public long getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(long deviceId) {
        this.deviceId = deviceId;
    }

    private long driverId;

    public long getDriverId() {
        return driverId;
    }

    public void setDriverId(long driverId) {
        this.driverId = driverId;
    }

    // 0 = the driver's company (driver attribute companyId)
    private long companyId;

    public long getCompanyId() {
        return companyId;
    }

    public void setCompanyId(long companyId) {
        this.companyId = companyId;
    }

    private Date from;

    public Date getFrom() {
        return from;
    }

    public void setFrom(Date from) {
        this.from = from;
    }

    private Date to;

    public Date getTo() {
        return to;
    }

    public void setTo(Date to) {
        this.to = to;
    }

    // miesto konania, chosen from the offered trip destinations
    private List<String> places = new ArrayList<>();

    public List<String> getPlaces() {
        return places;
    }

    public void setPlaces(List<String> places) {
        this.places = places;
    }

    private String purpose = "pracovná cesta";

    public String getPurpose() {
        return purpose;
    }

    public void setPurpose(String purpose) {
        this.purpose = purpose;
    }

    // hosťujúca organizácia poskytne
    private boolean mealsFull;

    public boolean getMealsFull() {
        return mealsFull;
    }

    public void setMealsFull(boolean mealsFull) {
        this.mealsFull = mealsFull;
    }

    private boolean mealsPartial;

    public boolean getMealsPartial() {
        return mealsPartial;
    }

    public void setMealsPartial(boolean mealsPartial) {
        this.mealsPartial = mealsPartial;
    }

    private boolean accommodation;

    public boolean getAccommodation() {
        return accommodation;
    }

    public void setAccommodation(boolean accommodation) {
        this.accommodation = accommodation;
    }

    // raňajky v rámci ubytovania (§ 13 ods. 8): foreign stravné of every day
    // after a night abroad is reduced by 25 %
    private boolean breakfastIncluded;

    public boolean getBreakfastIncluded() {
        return breakfastIncluded;
    }

    public void setBreakfastIncluded(boolean breakfastIncluded) {
        this.breakfastIncluded = breakfastIncluded;
    }

    private boolean travelCosts;

    public boolean getTravelCosts() {
        return travelCosts;
    }

    public void setTravelCosts(boolean travelCosts) {
        this.travelCosts = travelCosts;
    }

    private boolean pocketMoney;

    public boolean getPocketMoney() {
        return pocketMoney;
    }

    public void setPocketMoney(boolean pocketMoney) {
        this.pocketMoney = pocketMoney;
    }

    private boolean insurance;

    public boolean getInsurance() {
        return insurance;
    }

    public void setInsurance(boolean insurance) {
        this.insurance = insurance;
    }

    private boolean otherCosts;

    public boolean getOtherCosts() {
        return otherCosts;
    }

    public void setOtherCosts(boolean otherCosts) {
        this.otherCosts = otherCosts;
    }

    private double advance;

    public double getAdvance() {
        return advance;
    }

    public void setAdvance(double advance) {
        this.advance = advance;
    }

    private String notes;

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

}
