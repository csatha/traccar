package org.traccar.protocol;

import org.junit.jupiter.api.Test;
import org.traccar.ProtocolTest;
import org.traccar.model.Position;

public class TediAis140TcpProtocolDecoderTest extends ProtocolTest {

    @Test
    public void testDecode() throws Exception {

        var decoder = inject(new TediAis140TcpProtocolDecoder(null));

        // LOGIN DATA STRING
        verifyPosition(decoder, text(
                "$KA01AB1234$867530912345678$2.3.1$1.1$13.082680$N$80.270718$E$"));

        // GENERAL DATA STRING (2-fields-per-neighbour variant, as seen on real traffic)
        verifyPosition(decoder, text(
                "$,10,TEDI,2.3.1,NR,01,L,867530912345678,KA01AB1234,1,07082026,092530,"
                + "13.082680,N,80.270718,E,48.5,135.25,12,18.7,1.2,0.8,AIRTEL,1,1,13.8,4.2,0,27,"
                + "404,45,1A2B,12345678,1A2A,22,1A29,20,1A28,18,1A27,16,1010,01,000321,0000,*"));

        // GENERAL DATA STRING (3-fields-per-neighbour variant, as shown in the spec document)
        verifyPosition(decoder, text(
                "$,10,TEDI,1.1.2,NR,01,L,869247045236301,kl23m212,0,23112020,154924,"
                + "0.0,N,0.0,E,0,0,0,0,0,0,VODAFONE,0,1,14.0,4.1,0,31,404,84,C775,5510,5664,"
                + "C775,31,563D,C775,31,556B,C775,31,5DE4,C792,31,0000,00,002185,0033,*"));

        // HEALTH DATA STRING
        verifyAttribute(decoder, text(
                "$,101,TEDI,2.3.1,867530912345678,82,20,37,15,90,1010,0.75,1.20,*"),
                Position.KEY_BATTERY_LEVEL, 82);

        // OVER THE AIR PARAMETER CHANGE ALERT DATA STRING
        verifyAttribute(decoder, text(
                "$,PC,12,867530912345678,1,10.20.30.40,07082026,093015,SET UR:30,*"),
                Position.KEY_RESULT, "SET UR:30");

        // BATCH PACKET FORMAT (3 sub-packets) - concatenated with only "$," between them,
        // exactly as the device sends it on the wire (no line breaks)
        verifyPositions(decoder, text(
                "$,BTH,862567078088247,003,"
                + "$,01,L,1,070826,093000,13.082680,N,80.270718,E,404,045,1A2B,12345678,"
                + "048.50,135.25,12,01,27,1,1,M,0018.70,AIRTEL,"
                + "$,02,H,1,070826,092900,13.081950,N,80.269850,E,404,045,1A2A,12345679,"
                + "032.75,120.50,10,02,24,1,1,M,0017.90,AIRTEL,"
                + "$,07,H,1,070826,092800,13.081200,N,80.269100,E,404,045,1A29,12345680,"
                + "000.00,000.00,08,03,22,0,1,S,0017.20,AIRTEL*"));

        // malformed / unrecognised input should not throw, just be ignored
        verifyNull(decoder, text("garbage"));
    }

}
