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
import org.traccar.BaseProtocolDecoder;
import org.traccar.Protocol;

import java.net.SocketAddress;

/**
 * TCP decoder for the TEDI ITS-IRNS-01 (AIS-140) protocol.
 *
 * <p>Handles LOGIN, GENERAL, HEALTH, OTA PARAMETER CHANGE and BATCH messages - see
 * {@link TediAis140MessageDecoder} for the actual field parsing, which is shared with
 * {@link TediAis140HttpProtocolDecoder}. Framing (splitting the TCP byte stream into
 * individual messages) is handled by {@link TediAis140FrameDecoder}, so by the time a
 * message reaches this class it is already a single complete text sentence.
 */
public class TediAis140TcpProtocolDecoder extends BaseProtocolDecoder {

    public TediAis140TcpProtocolDecoder(Protocol protocol) {
        super(protocol);
    }

    @Override
    protected Object decode(
            Channel channel, SocketAddress remoteAddress, Object msg) throws Exception {

        String sentence = (String) msg;

        return TediAis140MessageDecoder.decode(
                getProtocolName(),
                imei -> getDeviceSession(channel, remoteAddress, imei),
                sentence);
    }

}
