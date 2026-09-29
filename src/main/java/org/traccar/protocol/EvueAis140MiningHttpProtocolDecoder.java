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

import io.netty.channel.Channel;
import io.netty.handler.codec.http.FullHttpRequest;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.Client;
import org.traccar.Protocol;

import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;

/** Evue HTTP decoder that also forwards decoded positions to the configured mining API. */
public class EvueAis140MiningHttpProtocolDecoder extends EvueAis140HttpProtocolDecoder {

    private Client client;

    public EvueAis140MiningHttpProtocolDecoder(Protocol protocol) {
        super(protocol);
    }

    @Inject
    public void setClient(Client client) {
        this.client = client;
    }

    @Override
    protected Object decode(Channel channel, SocketAddress remoteAddress, Object msg) throws Exception {
        Object result = super.decode(channel, remoteAddress, msg);
        String rawData = msg instanceof FullHttpRequest request
                ? request.content().toString(StandardCharsets.US_ASCII).trim() : null;
        MiningGpsForwarder.forwardResult(getConfig(), client, getCacheManager(), result, rawData);
        return result;
    }

}