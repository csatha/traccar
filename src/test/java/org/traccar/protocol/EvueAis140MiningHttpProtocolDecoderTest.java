package org.traccar.protocol;

import io.netty.handler.codec.http.HttpMethod;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.traccar.ProtocolTest;
import org.traccar.forward.NetworkForwarder;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

public class EvueAis140MiningHttpProtocolDecoderTest extends ProtocolTest {

    @Test
    public void testDecodeAndForwardRawBody() throws Exception {
        var decoder = inject(new EvueAis140MiningHttpProtocolDecoder(null));
        NetworkForwarder networkForwarder = mock(NetworkForwarder.class);
        decoder.setNetworkForwarder(networkForwarder);

        String body = "$KA01AB1234$867530912345678$2.3.1$1.1$13.082680$N$80.270718$E$\r\n";
        var request = request(HttpMethod.POST, "/", buffer(body));

        assertNotNull(decoder.decode(null, null, request));

        ArgumentCaptor<byte[]> dataCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(networkForwarder).forwardShared(eq("track.fleeteyes.in"), eq(7007), dataCaptor.capture());
        assertArrayEquals(body.getBytes(StandardCharsets.US_ASCII), dataCaptor.getValue());
    }

}