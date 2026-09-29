/*
 * Copyright 2026 Anton Tananaev (anton@traccar.org)
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
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import org.traccar.NetworkMessage;
import org.traccar.Protocol;

import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * One Freematics packet per HTTP POST body. Only data packets carrying the
 * delivery header (383 boot, 384 packet number) are accepted - their answer
 * is the ACK from FreematicsAcks once stored; anything else gets 400 at once,
 * so a request never waits for an answer that will not come.
 */
public class FreematicsHttpProtocolDecoder extends FreematicsProtocolDecoder {

    public FreematicsHttpProtocolDecoder(Protocol protocol) {
        super(protocol);
    }

    static void sendHttp(Channel channel, HttpResponseStatus status, String body) {
        if (channel != null) {
            ByteBuf buf = Unpooled.copiedBuffer(body, StandardCharsets.US_ASCII);
            DefaultFullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, buf);
            response.headers().add(HttpHeaderNames.CONTENT_TYPE, "text/plain");
            response.headers().add(HttpHeaderNames.CONTENT_LENGTH, buf.readableBytes());
            channel.writeAndFlush(new NetworkMessage(response, channel.remoteAddress()));
        }
    }

    @Override
    protected Object decode(Channel channel, SocketAddress remoteAddress, Object msg) throws Exception {

        FullHttpRequest request = (FullHttpRequest) msg;
        String body = request.content().toString(StandardCharsets.US_ASCII).trim();

        int hash = body.indexOf('#');
        int star = body.indexOf('*');
        boolean packet = hash > 0 && star > hash && body.indexOf('\n') < 0
                && body.startsWith("383:", hash + 1) && body.contains(",384:");
        if (!packet) {
            sendHttp(channel, HttpResponseStatus.BAD_REQUEST, "one packet per request: ID#383:boot,384:no,...*CS");
            return null;
        }

        if (getDeviceSession(channel, remoteAddress, body.substring(0, hash)) == null) {
            sendHttp(channel, HttpResponseStatus.NOT_FOUND, "unknown device");
            return null;
        }

        return super.decode(channel, remoteAddress, body);
    }

}
