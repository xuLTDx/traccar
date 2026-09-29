package org.traccar.protocol;

import io.netty.handler.codec.http.HttpMethod;
import org.junit.jupiter.api.Test;
import org.traccar.ProtocolTest;
import org.traccar.model.Position;

public class FreematicsHttpProtocolDecoderTest extends ProtocolTest {

    @Test
    public void testDecode() throws Exception {

        var decoder = inject(new FreematicsHttpProtocolDecoder(null));

        // one data packet with the delivery header, as the Freematics box sends it over UDP
        verifyPositions(decoder, request(HttpMethod.POST, "/", buffer(
                "ZKUCA42T#383:53,384:1,0:644231,10:20410300,11:280926,A:49.079472,B:19.286924,385:1,"
                + "1A6:157638,380:2,381:1790628063,10C:0*00")));

        verifyAttribute(decoder, request(HttpMethod.POST, "/", buffer(
                "ZKUCA42T#383:53,384:2,0:644232,10:20410300,11:280926,A:49.079472,B:19.286924,1A6:157638*00")),
                Position.KEY_ODOMETER, 157638000L);

        // no delivery header, an event sentence, several packets, garbage: rejected (400), no position
        verifyNull(decoder, request(HttpMethod.POST, "/", buffer(
                "ZKUCA42T#0:644231,10:20410300,11:280926,A:49.079472,B:19.286924*00")));

        verifyNull(decoder, request(HttpMethod.POST, "/", buffer(
                "ZKUCA42T#EV=1,TS=23930,ID=ZKUCA42T*49")));

        verifyNull(decoder, request(HttpMethod.POST, "/", buffer(
                "ZKUCA42T#383:53,384:3,0:1,24:300*00\nZKUCA42T#383:53,384:4,0:2,24:300*00")));

        verifyNull(decoder, request(HttpMethod.POST, "/", buffer("hello")));

    }

}
