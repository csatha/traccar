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

import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import org.traccar.BaseProtocol;
import org.traccar.PipelineBuilder;
import org.traccar.TrackerServer;
import org.traccar.config.Config;

import jakarta.inject.Inject;

/**
 * TCP variant of the TEDI ITS-IRNS-01 (AIS-140) protocol dedicated to mining vehicles - positions
 * decoded here are also forwarded to the external mining vehicle-tracking API, see
 * {@link MiningGpsForwarder}. Normal (non-mining) devices keep using {@link TediAis140TcpProtocol}.
 */
public class TediAis140MiningTcpProtocol extends BaseProtocol {

    @Inject
    public TediAis140MiningTcpProtocol(Config config) {
        addServer(new TrackerServer(config, getName(), false) {
            @Override
            protected void addProtocolHandlers(PipelineBuilder pipeline, Config config) {
                pipeline.addLast(new TediAis140FrameDecoder());
                pipeline.addLast(new StringEncoder());
                pipeline.addLast(new StringDecoder());
                pipeline.addLast(new TediAis140MiningTcpProtocolDecoder(TediAis140MiningTcpProtocol.this));
            }
        });
    }

}
