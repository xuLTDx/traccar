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
package org.traccar.schedule;

import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.travelorder.RateService;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

// Travel allowance rates from slov-lex: once shortly after start, then weekly.
public class TaskTravelRates extends SingleScheduleTask {

    private static final Logger LOGGER = LoggerFactory.getLogger(TaskTravelRates.class);

    private final RateService rateService;

    @Inject
    public TaskTravelRates(RateService rateService) {
        this.rateService = rateService;
    }

    @Override
    public void schedule(ScheduledExecutorService executor) {
        executor.scheduleAtFixedRate(this, 2, TimeUnit.DAYS.toMinutes(7), TimeUnit.MINUTES);
    }

    @Override
    public void run() {
        try {
            rateService.update();
        } catch (Exception e) {
            LOGGER.warn("Travel rates update failed", e);
        }
    }

}
