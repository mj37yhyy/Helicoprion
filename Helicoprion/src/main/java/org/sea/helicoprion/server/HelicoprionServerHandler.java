/*
 * Copyright 2013 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package org.sea.helicoprion.server;

import static io.netty.handler.codec.http.HttpHeaders.is100ContinueExpected;
import static io.netty.handler.codec.http.HttpHeaders.isKeepAlive;
import static io.netty.handler.codec.http.HttpHeaders.Names.CONNECTION;
import static io.netty.handler.codec.http.HttpHeaders.Names.CONTENT_LENGTH;
import static io.netty.handler.codec.http.HttpHeaders.Names.CONTENT_TYPE;
import static io.netty.handler.codec.http.HttpHeaders.Names.COOKIE;
import static io.netty.handler.codec.http.HttpHeaders.Names.SET_COOKIE;
import static io.netty.handler.codec.http.HttpResponseStatus.BAD_REQUEST;
import static io.netty.handler.codec.http.HttpResponseStatus.CONTINUE;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.DecoderResult;
import io.netty.handler.codec.http.Cookie;
import io.netty.handler.codec.http.CookieDecoder;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.codec.http.ServerCookieEncoder;
import io.netty.handler.codec.http.multipart.Attribute;
import io.netty.handler.codec.http.multipart.DefaultHttpDataFactory;
import io.netty.handler.codec.http.multipart.HttpDataFactory;
import io.netty.handler.codec.http.multipart.HttpPostRequestDecoder;
import io.netty.handler.codec.http.multipart.MixedAttribute;
import io.netty.handler.codec.http.multipart.HttpPostRequestDecoder.EndOfDataDecoderException;
import io.netty.handler.codec.http.multipart.HttpPostRequestDecoder.ErrorDataDecoderException;
import io.netty.handler.codec.http.multipart.InterfaceHttpData;
import io.netty.handler.codec.http.multipart.InterfaceHttpData.HttpDataType;
import io.netty.util.CharsetUtil;

import java.io.IOException;
import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import static io.netty.buffer.Unpooled.copiedBuffer;
import static io.netty.handler.codec.http.HttpHeaders.Names.*;

public class HelicoprionServerHandler extends
		SimpleChannelInboundHandler<Object> {

	@Override
	public void channelReadComplete(ChannelHandlerContext ctx) {
		ctx.flush();
	}

	@Override
	protected void channelRead0(ChannelHandlerContext ctx, Object msg)
			throws Exception {
		System.err.println(msg.getClass().getName());
		/**
		 * msg的类型 {@link DefaultHttpRequest} {@link LastHttpContent}
		 */
		if (msg instanceof HttpRequest) {
			HttpRequest request = this.request = (HttpRequest) msg;

			if (is100ContinueExpected(request)) {
				send100Continue(ctx);
			}
			URI uri = new URI(request.getUri());
			System.err.println("request uri==" + uri.getPath());

			if (uri.getPath().equals("/favicon.ico")) {
				return;
			}

			// headers
			headers = request.headers();

			// COOKIE
			String value = request.headers().get(COOKIE);
			if (value == null) {
				/**
				 * Returns an empty set (immutable).
				 */
				cookies = Collections.emptySet();
			} else {
				cookies = CookieDecoder.decode(value);
			}

			/**
			 * List<String>表示当参数相同时，把相同的参数的值放在list中
			 */
			QueryStringDecoder decoderQuery = new QueryStringDecoder(
					request.getUri());
			attributes = decoderQuery.parameters();

			// if GET Method: should not try to create a HttpPostRequestDecoder
			if (request.getMethod().equals(HttpMethod.GET)) {
				// GET Method: should not try to create a HttpPostRequestDecoder
				// So stop here
				writeResponse(ctx.channel());
				return;
			}

			// 判断request请求是否是post请求
			if (request.getMethod().equals(HttpMethod.POST)) {
				try {
					/**
					 * 通过HttpDataFactory和request构造解码器
					 */
					decoder = new HttpPostRequestDecoder(factory, request);
				} catch (ErrorDataDecoderException e1) {
					e1.printStackTrace();
					responseContent.append(e1.getMessage());
					writeResponse(ctx.channel());
					ctx.channel().close();
					return;
				}

				readingChunks = HttpHeaders.isTransferEncodingChunked(request);
				// responseContent.append("IsMultipart: " +
				// decoder.isMultipart()
				// + "\r\n");
				if (readingChunks) {
					// Chunk version
					readingChunks = true;
				}
			}
		}

		if (decoder != null) {
			if (msg instanceof HttpContent) {
				// New chunk is received
				HttpContent chunk = (HttpContent) msg;
				try {
					decoder.offer(chunk);
				} catch (ErrorDataDecoderException e1) {
					e1.printStackTrace();
					writeResponse(ctx.channel());
					ctx.channel().close();
					return;
				}
				try {
					while (decoder.hasNext()) {
						InterfaceHttpData data = decoder.next();
						if (data != null) {
							try {
								writeHttpData(data);
							} finally {
								data.release();
							}
						}
					}
				} catch (EndOfDataDecoderException e1) {
					responseContent
							.append("\r\n\r\nEND OF CONTENT CHUNK BY CHUNK\r\n\r\n");
				}

				// example of reading only if at the end
				if (chunk instanceof LastHttpContent) {
					writeResponse(ctx.channel());
					readingChunks = false;
					reset();
				}
			}
		}
	}

	private static void send100Continue(ChannelHandlerContext ctx) {
		FullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1,
				CONTINUE);
		ctx.write(response);
	}

	private void reset() {
		request = null;
		// destroy the decoder to release all resources
		decoder.destroy();
		decoder = null;
	}

	private void writeHttpData(InterfaceHttpData data) {
		Attribute attribute = (Attribute) data;
		try {
			/**
			 * HttpDataType有三种类型 Attribute, FileUpload, InternalAttribute
			 */
			if (data.getHttpDataType() == HttpDataType.Attribute) {
				MixedAttribute mixedAttribute = (MixedAttribute) data;
				mixedAttribute.setCharset(CharsetUtil.UTF_8);
				String value = mixedAttribute.getValue();
				attributes.put(attribute.getName(), value);
			} else if (data.getHttpDataType() == HttpDataType.FileUpload) {
				attributes.put(attribute.getName(), attribute.getFile());
			} else if (data.getHttpDataType() == HttpDataType.InternalAttribute) {
				attributes.put(attribute.getName(), attribute.get());
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	private void writeResponse(Channel channel) {
		// Convert the response content to a ChannelBuffer.
		ByteBuf buf = copiedBuffer(responseContent.toString(),
				CharsetUtil.UTF_8);

		// Build the response object.
		FullHttpResponse response = new DefaultFullHttpResponse(
				HttpVersion.HTTP_1_1, HttpResponseStatus.OK, buf);
		response.headers().set(CONTENT_TYPE, "text/plain; charset=UTF-8");

		boolean keepAlive = isKeepAlive(request);
		if (keepAlive) {
			// Add keep alive header as per:
			// -
			// http://www.w3.org/Protocols/HTTP/1.1/draft-ietf-http-v11-spec-01.html#Connection
			response.headers().set(CONNECTION, HttpHeaders.Values.KEEP_ALIVE);
		}
		// Decide whether to close the connection or not.
		boolean close = request.headers().contains(CONNECTION,
				HttpHeaders.Values.CLOSE, true)
				|| request.getProtocolVersion().equals(HttpVersion.HTTP_1_0)
				&& !request.headers().contains(CONNECTION,
						HttpHeaders.Values.KEEP_ALIVE, true);

		if (!close) {
			// There's no need to add 'Content-Length' header
			// if this is the last response.
			response.headers().set(CONTENT_LENGTH, buf.readableBytes());
		}

//		Set<Cookie> cookies;
//		String value = request.headers().get(COOKIE);
//		if (value == null) {
//			cookies = Collections.emptySet();
//		} else {
//			cookies = CookieDecoder.decode(value);
//		}
		if (!cookies.isEmpty()) {
			// Reset the cookies if necessary.
			for (Cookie cookie : cookies) {
				response.headers().add(SET_COOKIE,
						ServerCookieEncoder.encode(cookie));
			}
		}
		// Write the response.
		ChannelFuture future = channel.writeAndFlush(response);
		// Close the connection after the write operation is done if necessary.
		if (close) {
			future.addListener(ChannelFutureListener.CLOSE);
		}
	}

	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
		cause.printStackTrace();
		ctx.close();
	}

	private HttpRequest request;
	/** Buffer that stores the response content */
	private final StringBuilder responseContent = new StringBuilder();

	private boolean readingChunks;

	private static final HttpDataFactory factory = new DefaultHttpDataFactory(
			DefaultHttpDataFactory.MINSIZE); // Disk

	private HttpPostRequestDecoder decoder;

	private Set<Cookie> cookies;

	private Iterable<Map.Entry<String, String>> headers;
	
	private Map attributes;
}