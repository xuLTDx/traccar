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

import java.util.Date;

// A travel allowance rate as published in the Zbierka zákonov (slov-lex),
// valid from validFrom until the next rate of the same kind (and country):
//  - "domestic": stravné § 5 ods. 1, amount1/2/3 = 5-12 h / >12-18 h / >18 h
//  - "km": základná náhrada § 7 ods. 2 for osobné vozidlá, amount1 = €/km
//  - "foreign": základná sadzba stravného § 13 ods. 2 (opatrenie MF
//    401/2012 § 1) for one country, amount1 in `currency`
@StorageName("tc_allowance_rates")
public class AllowanceRate extends BaseModel {

    public static final String KIND_DOMESTIC = "domestic";
    public static final String KIND_KM = "km";
    public static final String KIND_FOREIGN = "foreign";

    private String kind;

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    // foreign: ISO 3166-1 alpha-2 (null when the country could not be mapped)
    private String country;

    public String getCountry() {
        return country;
    }

    public void setCountry(String country) {
        this.country = country;
    }

    // foreign: the country as the opatrenie names it
    private String countryName;

    public String getCountryName() {
        return countryName;
    }

    public void setCountryName(String countryName) {
        this.countryName = countryName;
    }

    private String currency = "EUR";

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    private double amount1;

    public double getAmount1() {
        return amount1;
    }

    public void setAmount1(double amount1) {
        this.amount1 = amount1;
    }

    private double amount2;

    public double getAmount2() {
        return amount2;
    }

    public void setAmount2(double amount2) {
        this.amount2 = amount2;
    }

    private double amount3;

    public double getAmount3() {
        return amount3;
    }

    public void setAmount3(double amount3) {
        this.amount3 = amount3;
    }

    private Date validFrom;

    public Date getValidFrom() {
        return validFrom;
    }

    public void setValidFrom(Date validFrom) {
        this.validFrom = validFrom;
    }

    // e.g. "280/2025 Z. z."
    private String source;

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

}
