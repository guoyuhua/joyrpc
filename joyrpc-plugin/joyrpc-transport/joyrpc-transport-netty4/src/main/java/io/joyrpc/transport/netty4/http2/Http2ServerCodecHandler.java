package io.joyrpc.transport.netty4.http2;

/*-
 * #%L
 * joyrpc
 * %%
 * Copyright (C) 2019 joyrpc.io
 * %%
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
 * #L%
 */

import io.joyrpc.exception.CodecException;
import io.joyrpc.transport.channel.Channel;
import io.joyrpc.transport.codec.Http2Codec;
import io.joyrpc.transport.http2.DefaultHttp2RequestMessage;
import io.joyrpc.transport.http2.Http2ResponseMessage;
import io.joyrpc.transport.netty4.buffer.NettyChannelBuffer;
import io.joyrpc.transport.netty4.transport.NettyServer;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http2.*;
import io.netty.handler.logging.LogLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;

/**
 * http2 server端 编解码器
 */
public class Http2ServerCodecHandler extends Http2ConnectionHandler implements Http2FrameListener {

    private static final Logger logger = LoggerFactory.getLogger(NettyServer.class);
    protected final Http2ConnectionDecoder decoder;
    protected final Http2ConnectionEncoder encoder;
    /**
     * 消息头键
     */
    protected Http2Connection.PropertyKey headerKey;
    /**
     * 编解码
     */
    protected Http2Codec codec;
    /**
     * 通道
     */
    protected Channel channel;

    public Http2ServerCodecHandler(Http2ConnectionDecoder decoder,
                                   Http2ConnectionEncoder encoder,
                                   Http2Settings initialSettings,
                                   Channel channel,
                                   Http2Codec codec) {
        super(decoder, encoder, initialSettings);
        this.decoder = decoder;
        this.encoder = encoder;
        this.channel = channel;
        this.codec = codec;
        this.headerKey = encoder().connection().newKey();
        decoder.frameListener(this);
    }

    @Override
    public void onSettingsRead(final ChannelHandlerContext ctx, final Http2Settings settings) {

    }

    @Override
    public int onDataRead(final ChannelHandlerContext ctx, final int streamId, final ByteBuf data,
                          final int padding, final boolean endOfStream) throws Http2Exception {
        int processed = data.readableBytes() + padding;
        if (endOfStream) {
            Http2Stream http2Stream = connection().stream(streamId);
            // read cached http2 header from stream
            Http2Headers headers = http2Stream.getProperty(headerKey);
            handleRequest(ctx, streamId, headers, data);
        }
        return processed;
    }

    @Override
    public void onHeadersRead(final ChannelHandlerContext ctx, final int streamId, final Http2Headers headers,
                              final int padding, final boolean endStream) throws Http2Exception {
        if (streamId > 0) {
            // 正常的请求（streamId==1 的是settings请求）
            if (endStream) {
                // 没有DATA帧的请求，可能是DATA
                handleRequest(ctx, streamId, headers, null);
            } else {
                // 缓存起来
                Http2Stream stream = connection().stream(streamId);
                if (stream != null) {
                    stream.setProperty(headerKey, headers);
                }
            }
        }
    }

    @Override
    public void onHeadersRead(final ChannelHandlerContext ctx, final int streamId, final Http2Headers headers,
                              final int streamDependency, final short weight, final boolean exclusive,
                              final int padding, final boolean endStream) throws Http2Exception {
        onHeadersRead(ctx, streamId, headers, padding, endStream);
    }

    @Override
    public void onRstStreamRead(final ChannelHandlerContext ctx, final int streamId, final long errorCode) {
        logger.error("onRstStreamRead streamId:" + streamId + " errorCode:" + errorCode);
    }

    @Override
    public void onPingRead(final ChannelHandlerContext ctx, final long data) throws Http2Exception {
        logger.warn("onPingRead data:" + data);
    }

    @Override
    public void onPingAckRead(final ChannelHandlerContext ctx, final long data) throws Http2Exception {
        logger.warn("onPingAckRead data:" + data);
    }

    @Override
    public void onPriorityRead(final ChannelHandlerContext ctx, final int streamId, final int streamDependency,
                               final short weight, final boolean exclusive) throws Http2Exception {

    }

    @Override
    public void onSettingsAckRead(final ChannelHandlerContext ctx) throws Http2Exception {

    }

    @Override
    public void onPushPromiseRead(final ChannelHandlerContext ctx, final int streamId, final int promisedStreamId,
                                  final Http2Headers headers, final int padding) throws Http2Exception {

    }

    @Override
    public void onGoAwayRead(final ChannelHandlerContext ctx, final int lastStreamId, final long errorCode,
                             final ByteBuf debugData) throws Http2Exception {

    }

    @Override
    public void onWindowUpdateRead(final ChannelHandlerContext ctx, final int streamId, final int windowSizeIncrement) throws Http2Exception {

    }

    @Override
    public void onUnknownFrame(final ChannelHandlerContext ctx, final byte frameType, final int streamId,
                               final Http2Flags flags, final ByteBuf payload) throws Http2Exception {

    }

    @Override
    public void write(final ChannelHandlerContext ctx, final Object msg, final ChannelPromise promise) throws Exception {
        if (!(msg instanceof Http2ResponseMessage)) {
            super.write(ctx, msg, promise);
            return;
        }
        //response对象
        Http2ResponseMessage response = (Http2ResponseMessage) msg;
        //应答头
        io.joyrpc.transport.http2.Http2Headers headers = response.headers();
        if (headers != null && !headers.isEmpty()) {
            //构建http2响应header
            Http2Headers http2Headers = new DefaultHttp2Headers();
            headers.getAll().forEach((k, v) -> http2Headers.add(k, v.toString()));
            //write
            encoder.writeHeaders(ctx, response.getStreamId(), http2Headers, 0, false, promise);
        }
        //是否有结束头
        io.joyrpc.transport.http2.Http2Headers endHeaders = response.getEndHeaders();
        //写应答内容
        if (response.content() != null) {
            ByteBuf byteBuf = ctx.alloc().buffer();
            try {
                codec.encode(new Http2EncodeContext(channel).attribute(Http2Codec.HEADER, headers),
                        new NettyChannelBuffer(byteBuf), response.content());
            } catch (CodecException e) {
                byteBuf.release();
                throw e;
            }
            encoder.writeData(ctx, response.getStreamId(), byteBuf, 0, endHeaders == null, ctx.voidPromise());
        }
        //write end header
        if (endHeaders != null) {
            Http2Headers http2Headers = new DefaultHttp2Headers();
            endHeaders.getAll().forEach((k, v) -> http2Headers.add(k, v.toString()));
            //write
            encoder.writeHeaders(ctx, response.getStreamId(), http2Headers, 0, true, promise);
        }
    }

    /**
     * 创建http2服务端编解码处理器
     *
     * @param channel    通道
     * @param http2Codec http2编解码
     * @return http2服务端编解码处理器
     */
    public static Http2ServerCodecHandler create(final Channel channel, final Http2Codec http2Codec) {
        Http2FrameLogger frameLogger = new Http2FrameLogger(LogLevel.DEBUG, Http2ServerCodecHandler.class);
        int payload = channel.getPayloadSize();

        Http2HeadersDecoder headersDecoder = new DefaultHttp2HeadersDecoder(true, payload);
        Http2FrameReader frameReader = new Http2InboundFrameLogger(new DefaultHttp2FrameReader(headersDecoder), frameLogger);
        Http2FrameWriter frameWriter = new Http2OutboundFrameLogger(new DefaultHttp2FrameWriter(), frameLogger);

        Http2Connection connection = new DefaultHttp2Connection(true);
        Http2ConnectionEncoder encoder = new DefaultHttp2ConnectionEncoder(connection, frameWriter);
        Http2ConnectionDecoder decoder = new DefaultHttp2ConnectionDecoder(connection, encoder, frameReader);

        Http2Settings settings = new Http2Settings();
        settings.initialWindowSize(1048576);
        settings.maxConcurrentStreams(Integer.MAX_VALUE);
        settings.maxHeaderListSize(8192);
        return new Http2ServerCodecHandler(decoder, encoder, settings, channel, http2Codec);
    }

    protected void handleRequest(final ChannelHandlerContext ctx,
                                 final int streamId,
                                 final Http2Headers http2Headers,
                                 final ByteBuf body) throws Http2Exception {
        try {
            //获取请求header
            io.joyrpc.transport.http2.Http2Headers reqHeaders = new io.joyrpc.transport.http2.DefaultHttp2Headers();
            if (http2Headers != null) {
                http2Headers.forEach(t -> {
                    try {
                        reqHeaders.set(t.getKey().toString(), URLDecoder.decode(t.getValue().toString(), "UTF-8"));
                    } catch (UnsupportedEncodingException ignored) {
                    }
                });
            }
            //获取请求body
            byte[] content = body != null ? (byte[]) codec.decode(new Http2DecodeContext(channel), new NettyChannelBuffer(body)) : null;
            //触发下一个channelread
            //server端收到消息，没有bizId，这里用streamId充当bizId
            ctx.fireChannelRead(new DefaultHttp2RequestMessage(streamId, streamId, reqHeaders, content));
        } catch (Exception e) {
            //TODO 异常处理
            throw Http2Exception.streamError(streamId, Http2Error.PROTOCOL_ERROR, e, "has error when codec");
        }

    }

}
