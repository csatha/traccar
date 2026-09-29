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
import org.traccar.Protocol;
import org.traccar.config.Keys;
import org.traccar.forward.NetworkForwarder;

import java.net.SocketAddress;

/** HTTP AIS-140 decoder that forwards each original request body to the configured TCP endpoint. */
public class EvueAis140HttpProtocolDecoder extends TediAis140HttpProtocolDecoder {

    private NetworkForwarder networkForwarder;

    public EvueAis140HttpProtocolDecoder(Protocol protocol) {
        super(protocol);
    }

    @Inject
    public void setNetworkForwarder(NetworkForwarder networkForwarder) {
        this.networkForwarder = networkForwarder;
    }

    @Override
    protected Object decode(Channel channel, SocketAddress remoteAddress, Object msg) throws Exception {
        if (networkForwarder != null && msg instanceof FullHttpRequest request) {
            byte[] rawData = new byte[request.content().readableBytes()];
            request.content().getBytes(request.content().readerIndex(), rawData);
            networkForwarder.forwardShared(
                    getConfig().getString(Keys.EVUE_FORWARD_HOST),
                    getConfig().getInteger(Keys.EVUE_FORWARD_PORT), rawData);
        }
        return super.decode(channel, remoteAddress, msg);
    }

}