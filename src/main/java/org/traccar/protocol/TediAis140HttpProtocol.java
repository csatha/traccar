/*
 * Copyright 2026 TEDI Elite / Client Customization
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
 * HTTP variant of the TEDI ITS-IRNS-01 (AIS-140) protocol.
 *
 * <p>The device configuration commands in the protocol document (Primary/Secondary URL,
 * e.g. {@code SET PU:http://13.126.245.226/CDC}) confirm the device POSTs its data
 * strings, as-is, to an HTTP(S) endpoint - so this decoder simply reads the raw request
 * body as text and reuses the exact same field parsing as the TCP variant. The device's
 * configured path does not need to match anything specific here since the whole port is
 * dedicated to this protocol; adjust if the client's firmware expects a particular path.
 */
public class TediAis140HttpProtocol extends BaseProtocol {

    @Inject
    public TediAis140HttpProtocol(Config config) {
        addServer(new TrackerServer(config, getName(), false) {
            @Override
            protected void addProtocolHandlers(PipelineBuilder pipeline, Config config) {
                pipeline.addLast(new HttpResponseEncoder());
                pipeline.addLast(new HttpRequestDecoder());
                pipeline.addLast(new HttpObjectAggregator(65535));
                pipeline.addLast(new TediAis140HttpProtocolDecoder(TediAis140HttpProtocol.this));
            }
        });
    }

}
