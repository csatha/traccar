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

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import org.traccar.BaseFrameDecoder;

import java.nio.charset.StandardCharsets;

/**
 * Frame decoder for the TEDI ITS-IRNS-01 (AIS-140) protocol over raw TCP.
 *
 * <p>Every message starts with a single {@code $} character. There are three distinct
 * framing rules:
 * <ul>
 *     <li><b>LOGIN DATA STRING</b> - fields are separated by {@code $} itself
 *     (e.g. {@code $VEHICLE$IMEI$FW$PROTO$LAT$N$LON$E$}) and there are always exactly
 *     8 fields, i.e. 9 total {@code $} characters including the leading one. There is
 *     no {@code *} terminator for this message type.</li>
 *     <li><b>GENERAL / HEALTH / OTA PARAMETER CHANGE</b> - these start with {@code $,}
 *     and are terminated by a trailing {@code *} character.</li>
 *     <li><b>BATCH</b> - starts with {@code $,BTH,<imei>,<count>,} followed by one or more
 *     {@code $,<fields>,} sub-packets, with only the very last sub-packet terminated by
 *     {@code *}. A device can batch a large number of sub-packets (e.g. after a reconnect),
 *     which would produce a single frame well over Traccar's default per-frame size limit
 *     (~1KB, enforced generically by {@link BaseFrameDecoder} to guard against unbounded
 *     buffering) and get the whole batch discarded with a {@code TooLongFrameException}.
 *     To avoid that, BATCH sub-packets are framed <b>individually</b> here: the
 *     {@code $,BTH,<imei>,<count>,} header is remembered once and re-attached to every
 *     sub-packet frame, so each one becomes its own small, valid "single-record batch"
 *     message. {@link TediAis140MessageDecoder#decode} already accepts a batch containing
 *     just one sub-packet (with or without the trailing {@code *}), so nothing downstream
 *     needs to change.</li>
 * </ul>
 */
public class TediAis140FrameDecoder extends BaseFrameDecoder {

    // Login frames have exactly 8 '$'-delimited fields -> 9 '$' characters total.
    private static final int LOGIN_DOLLAR_COUNT = 9;

    private static final byte[] BATCH_HEADER_PREFIX = ",BTH,".getBytes(StandardCharsets.US_ASCII);

    // Remembers the "$,BTH,<imei>,<count>," header while a batch's sub-packets are being
    // framed one at a time. Reset per-connection (a new decoder instance is created for
    // each connection), and cleared once the sub-packet ending in '*' has been framed.
    private String pendingBatchHeader;

    @Override
    protected Object decode(
            ChannelHandlerContext ctx, Channel channel, ByteBuf buf) throws Exception {

        while (true) {

            if (buf.readableBytes() < 2) {
                return null;
            }

            int start = buf.readerIndex();

            if (buf.getByte(start) != '$') {
                // Re-sync on the next start-of-message marker (e.g. stray CR/LF between frames).
                int dollar = indexOf(buf, start, (byte) '$');
                if (dollar < 0) {
                    buf.skipBytes(buf.readableBytes());
                    return null;
                }
                buf.readerIndex(dollar);
                start = dollar;
                if (buf.readableBytes() < 2) {
                    return null;
                }
            }

            boolean commaDelimited = buf.getByte(start + 1) == ',';

            if (!commaDelimited) {
                // LOGIN DATA STRING
                int end = indexOfNth(buf, start, (byte) '$', LOGIN_DOLLAR_COUNT);
                if (end < 0) {
                    return null;
                }
                pendingBatchHeader = null; // a login can't appear in the middle of a batch
                return buf.readRetainedSlice(end - start + 1);
            }

            // Need enough bytes to reliably tell a batch header ("$,BTH,...") apart from
            // any other "$,..." message before deciding how to frame it.
            if (buf.writerIndex() - start < 1 + BATCH_HEADER_PREFIX.length) {
                return null;
            }

            if (isBatchHeader(buf, start)) {
                // "$,BTH,<imei>,<count>," - remember it and keep going; it isn't emitted
                // as a frame on its own, it gets re-attached to each following sub-packet.
                int end = indexOf(buf, start + 1 + BATCH_HEADER_PREFIX.length, (byte) '$');
                if (end < 0) {
                    return null; // wait for at least the first sub-packet to arrive too
                }
                pendingBatchHeader = buf.toString(start, end - start, StandardCharsets.US_ASCII);
                buf.readerIndex(end);
                continue;
            }

            if (pendingBatchHeader != null) {
                // BATCH sub-packet - ends at the next '$' (more sub-packets follow) or at
                // '*' (this is the last sub-packet in the batch).
                int nextDollar = indexOf(buf, start + 2, (byte) '$');
                int star = indexOf(buf, start + 2, (byte) '*');

                int end;
                boolean lastSubPacket;
                if (star >= 0 && (nextDollar < 0 || star < nextDollar)) {
                    end = star + 1; // include the '*'
                    lastSubPacket = true;
                } else if (nextDollar >= 0) {
                    end = nextDollar; // exclude the next '$' - it starts the next sub-packet
                    lastSubPacket = false;
                } else {
                    return null; // wait for more data
                }

                String subPacket = buf.toString(start, end - start, StandardCharsets.US_ASCII);
                buf.readerIndex(end);

                String frameText = pendingBatchHeader + subPacket;
                if (lastSubPacket) {
                    pendingBatchHeader = null;
                }
                return Unpooled.copiedBuffer(frameText, StandardCharsets.US_ASCII);
            }

            // Plain GENERAL / HEALTH / OTA PARAMETER CHANGE message - terminated by '*'.
            int end = indexOf(buf, start + 2, (byte) '*');
            if (end < 0) {
                return null;
            }
            return buf.readRetainedSlice(end - start + 1);
        }
    }

    private boolean isBatchHeader(ByteBuf buf, int start) {
        for (int i = 0; i < BATCH_HEADER_PREFIX.length; i++) {
            if (buf.getByte(start + 1 + i) != BATCH_HEADER_PREFIX[i]) {
                return false;
            }
        }
        return true;
    }

    private int indexOf(ByteBuf buf, int fromIndex, byte value) {
        int max = buf.writerIndex();
        for (int i = fromIndex; i < max; i++) {
            if (buf.getByte(i) == value) {
                return i;
            }
        }
        return -1;
    }

    private int indexOfNth(ByteBuf buf, int fromIndex, byte value, int occurrence) {
        int max = buf.writerIndex();
        int count = 0;
        for (int i = fromIndex; i < max; i++) {
            if (buf.getByte(i) == value) {
                count++;
                if (count == occurrence) {
                    return i;
                }
            }
        }
        return -1;
    }

}