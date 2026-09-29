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
package org.traccar.protocol;

import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpRequestDecoder;
import io.netty.handler.codec.http.HttpResponseEncoder;
import org.traccar.BaseProtocol;
import org.traccar.PipelineBuilder;
import org.traccar.TrackerServer;
import org.traccar.config.Config;

import jakarta.inject.Inject;

/**
 * The Freematics packet format over HTTP (2026-09-29), for devices that can
 * only speak HTTP - an OVMS module running a script. One POST = one packet
 * ("ID#383:boot,384:packet,0:ts,...*CS"), handled by the same decoder as the
 * UDP Freematics protocol; the HTTP response is the delivery ACK, sent once
 * the records are stored (see FreematicsAcks). No default port: it only runs
 * with freematicshttp.port set in the configuration.
 */
public class FreematicsHttpProtocol extends BaseProtocol {

    @Inject
    public FreematicsHttpProtocol(Config config) {
        addServer(new TrackerServer(config, getName(), false) {
            @Override
            protected void addProtocolHandlers(PipelineBuilder pipeline, Config config) {
                pipeline.addLast(new HttpResponseEncoder());
                pipeline.addLast(new HttpRequestDecoder());
                pipeline.addLast(new HttpObjectAggregator(MAX_HTTP_LENGTH));
                pipeline.addLast(new FreematicsHttpProtocolDecoder(FreematicsHttpProtocol.this));
            }
        });
    }

}
