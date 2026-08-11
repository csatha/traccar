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
import io.netty.handler.codec.http.HttpResponseStatus;
import org.traccar.BaseHttpProtocolDecoder;
import org.traccar.Protocol;

import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * HTTP decoder for the TEDI ITS-IRNS-01 (AIS-140) protocol.
 *
 * <p>The device posts its data string (LOGIN / GENERAL / HEALTH / OTA PARAMETER CHANGE /
 * BATCH - identical wire format to the TCP variant) as the raw HTTP request body. Field
 * parsing is shared with {@link TediAis140TcpProtocolDecoder} via
 * {@link TediAis140MessageDecoder}.
 */
public class TediAis140HttpProtocolDecoder extends BaseHttpProtocolDecoder {

    public TediAis140HttpProtocolDecoder(Protocol protocol) {
        super(protocol);
    }

    @Override
    protected Object decode(
            Channel channel, SocketAddress remoteAddress, Object msg) throws Exception {

        FullHttpRequest request = (FullHttpRequest) msg;
        String sentence = request.content().toString(StandardCharsets.US_ASCII).trim();

        Object result;
        try {
            result = TediAis140MessageDecoder.decode(
                    getProtocolName(),
                    imei -> getDeviceSession(channel, remoteAddress, imei),
                    sentence);
        } catch (RuntimeException error) {
            result = null;
        }

        // 200 when we successfully parsed the message and resolved a known device,
        // 400 for anything unparsable / an unknown IMEI - mirrors OsmAndProtocolDecoder's
        // sendResponse(channel, HttpResponseStatus.BAD_REQUEST) convention. Always fires,
        // even on failure, so the device never sees a hung/empty connection.
        sendResponse(channel, result != null ? HttpResponseStatus.OK : HttpResponseStatus.BAD_REQUEST);

        return result;
    }

    // BaseHttpProtocolDecoder normally sends a second response after decode() returns,
    // carrying any queued commands for the device. We already send our own response above,
    // so this must be suppressed (see OsmAndProtocolDecoder for the same pattern) - otherwise
    // two HTTP responses get written to the same connection, which corrupts the framing and
    // the client (e.g. Postman, or the device itself) just hangs waiting for a valid reply.
    @Override
    protected void sendQueuedCommands(Channel channel, SocketAddress remoteAddress, long deviceId) {
    }

}
