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

// A company (employer) on the travel order header; a driver belongs to one
// (driver attribute companyId), the travel order dialog can pick another.
// Name/address/statutory come from the register (RPO, api.statistics.sk);
// DIČ and IČ DPH are not in that register and are typed in.
@StorageName("tc_companies")
public class Company extends BaseModel {

    private String name;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    private String address;

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    private String ico;

    public String getIco() {
        return ico;
    }

    public void setIco(String ico) {
        this.ico = ico;
    }

    private String dic;

    public String getDic() {
        return dic;
    }

    public void setDic(String dic) {
        this.dic = dic;
    }

    private String icDph;

    public String getIcDph() {
        return icDph;
    }

    public void setIcDph(String icDph) {
        this.icDph = icDph;
    }

    private String phone;

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    // konatelia, as the register lists them
    private String statutory;

    public String getStatutory() {
        return statutory;
    }

    public void setStatutory(String statutory) {
        this.statutory = statutory;
    }

}
