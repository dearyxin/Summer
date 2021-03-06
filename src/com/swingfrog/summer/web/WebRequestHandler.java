package com.swingfrog.summer.web;

import java.io.FileNotFoundException;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.util.Calendar;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.fastjson.JSONObject;
import com.swingfrog.summer.app.Summer;
import com.swingfrog.summer.concurrent.SessionQueueMgr;
import com.swingfrog.summer.concurrent.SingleQueueMgr;
import com.swingfrog.summer.ioc.ContainerMgr;
import com.swingfrog.summer.server.RemoteDispatchMgr;
import com.swingfrog.summer.server.ServerContext;
import com.swingfrog.summer.server.SessionContext;
import com.swingfrog.summer.server.exception.CodeException;
import com.swingfrog.summer.server.exception.CodeMsg;
import com.swingfrog.summer.server.exception.SessionException;
import com.swingfrog.summer.server.rpc.RpcClientMgr;
import com.swingfrog.summer.web.view.FileView;
import com.swingfrog.summer.web.view.WebView;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.TooLongFrameException;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http.multipart.Attribute;
import io.netty.handler.codec.http.multipart.DefaultHttpDataFactory;
import io.netty.handler.codec.http.multipart.FileUpload;
import io.netty.handler.codec.http.multipart.HttpDataFactory;
import io.netty.handler.codec.http.multipart.HttpPostRequestDecoder;
import io.netty.handler.codec.http.multipart.InterfaceHttpData;
import io.netty.handler.codec.http.multipart.HttpPostRequestDecoder.EndOfDataDecoderException;
import io.netty.handler.codec.http.multipart.InterfaceHttpData.HttpDataType;

public class WebRequestHandler extends SimpleChannelInboundHandler<HttpObject> {

	private static final Logger log = LoggerFactory.getLogger(WebRequestHandler.class);
	private static final HttpDataFactory factory =
            new DefaultHttpDataFactory(DefaultHttpDataFactory.MINSIZE);
	private ServerContext serverContext;
	private HttpRequest httpRequest;
	private HttpPostRequestDecoder postRequestDecoder;
	
	public WebRequestHandler(ServerContext serverContext) {
		this.serverContext = serverContext;
	}
	
	@Override
	public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
		if (serverContext.getConfig().isAllowAddressEnable()) {
			String address = ((InetSocketAddress)ctx.channel().remoteAddress()).getHostString();
			String[] addressList = serverContext.getConfig().getAllowAddressList();
			boolean allow = false;
			for (int i = 0; i < addressList.length; i ++) {
				if (address.equals(addressList[i])) {
					allow = true;
					break;
				}
			}
			if (!allow) {
				log.warn("not allow {} connect", address);
				ctx.close();
				return;
			}
			log.info("allow {} connect", address);
		}
		serverContext.getSessionContextGroup().createSession(ctx);
		SessionContext sctx = serverContext.getSessionContextGroup().getSessionByChannel(ctx);
		if (!serverContext.getSessionHandlerGroup().accpet(sctx)) {
			log.warn("not accpet clinet {}", sctx);
			ctx.close();
		}
	}
	
	@Override
	public void channelActive(ChannelHandlerContext ctx) throws Exception {
		SessionContext sctx = serverContext.getSessionContextGroup().getSessionByChannel(ctx);
		log.info("added client {}", sctx);
		serverContext.getSessionHandlerGroup().added(sctx);
	}
	
	@Override
	public void channelInactive(ChannelHandlerContext ctx) throws Exception {
		SessionContext sctx = serverContext.getSessionContextGroup().getSessionByChannel(ctx);
		if (sctx != null) {
			log.info("removed clinet {}", sctx);
			serverContext.getSessionHandlerGroup().removed(sctx);
			serverContext.getSessionContextGroup().destroySession(ctx);
			RpcClientMgr.get().remove(sctx);
			SessionQueueMgr.get().shutdown(sctx);
		}
	}
	
	@Override
	protected void channelRead0(ChannelHandlerContext ctx, HttpObject httpObject) throws Exception {
		SessionContext sctx = serverContext.getSessionContextGroup().getSessionByChannel(ctx);
		long now = Calendar.getInstance().getTimeInMillis();
		long last = sctx.getLastRecvTime();
		sctx.setLastRecvTime(now);
		if ((now - last) < serverContext.getConfig().getColdDownMs()) {
			serverContext.getSessionHandlerGroup().sendTooFastMsg(sctx);
		}
		sctx.setHeartCount(0);
		try {
			if (httpObject instanceof HttpRequest) {
				httpRequest = (HttpRequest) httpObject;
				if (!httpRequest.decoderResult().isSuccess()) {
					return;
				}
				String uri = httpRequest.uri();
				if (uri == null) {
					return;
				}
				HttpMethod method = httpRequest.method();
				if (HttpMethod.GET.equals(method)) {
					doGet(ctx, sctx);
				} else if (HttpMethod.POST.equals(method)) {
					postRequestDecoder = new HttpPostRequestDecoder(factory, httpRequest);
					processHttpContent(ctx, sctx, (HttpContent) httpObject);
				} else {
					log.warn("unkonw request method[%s]", method.name());
				}
			} else if (httpObject instanceof HttpContent) {
				processHttpContent(ctx, sctx, (HttpContent) httpObject);
			}
		} catch (Exception e) {
			log.error(e.getMessage(), e);
			serverContext.getSessionHandlerGroup().unableParseMsg(sctx);
		}
	}
	
	private void processHttpContent(ChannelHandlerContext ctx, SessionContext sctx, HttpContent httpContent) {
		if (postRequestDecoder != null) {
			try {
				postRequestDecoder.offer(httpContent);
			} catch (Exception e) {
				log.error(e.getMessage(), e);
				return;
			}
			if (httpContent instanceof LastHttpContent) {
				doPost(ctx, sctx);
				postRequestDecoder.destroy();
				postRequestDecoder = null;
				httpRequest = null;
			}
		}
	}

	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause)
			throws Exception {
		SessionContext sctx = serverContext.getSessionContextGroup().getSessionByChannel(ctx);
		if (cause instanceof TooLongFrameException) {
			serverContext.getSessionHandlerGroup().lengthTooLongMsg(sctx);
		} else {
			log.error(cause.getMessage(), cause);
		}
	}
	
	private WebRequest getWebRequest() {
		String uri = httpRequest.uri();
		if (uri.length() == 1) {
			uri = uri + WebMgr.get().getIndex();
		}
		if (WebMgr.DEFAULT_FAVICON.equals(uri)) {
			uri = "/" + WebMgr.get().getFavicon();
		}
		try {
			uri = URLDecoder.decode(uri, serverContext.getConfig().getCharset());
		} catch (UnsupportedEncodingException e) {
			log.error(e.getMessage(), e);
		}
		return WebRequest.build(httpRequest, uri);
	}
	
	private void doGet(ChannelHandlerContext ctx, SessionContext sctx) {
		WebRequest webRequest = getWebRequest();
		if (webRequest.isDynamic()) {
			doWork(ctx, sctx, webRequest);
		} else {
			doFile(ctx, sctx, webRequest);
		}
	}
	
	private void doPost(ChannelHandlerContext ctx, SessionContext sctx) {
		WebRequest webRequest = getWebRequest();
		JSONObject data = webRequest.getData();
		try {
			while (postRequestDecoder.hasNext()) {
				InterfaceHttpData httpData = postRequestDecoder.next();
				try {
					if (httpData.getHttpDataType() == HttpDataType.Attribute || 
							httpData.getHttpDataType() == HttpDataType.InternalAttribute) {
						Attribute attribute = (Attribute) httpData;
						data.put(attribute.getName(), attribute.getValue());
					} else if (httpData.getHttpDataType() == HttpDataType.FileUpload) {
						FileUpload fileUpload = (FileUpload) httpData;
						if (fileUpload.isCompleted()) {
							webRequest.getFileUploadMap().put(fileUpload.getName(), new WebFileUpload(fileUpload));
						} else {
							log.error("fileUpload not complete name[%s]", fileUpload.getName());
						}
					}
				} catch (Exception e) {
					log.error(e.getMessage(), e);
				} finally {
					postRequestDecoder.removeHttpDataFromClean(httpData);
					httpData.release();
				}
			}
		} catch (EndOfDataDecoderException e) {
			
		}
		if (webRequest.isDynamic()) {
			doWork(ctx, sctx, webRequest);
		} else {
			doFile(ctx, sctx, webRequest);
		}
	}
	
	private void doFile(ChannelHandlerContext ctx, SessionContext sctx, WebRequest request) {
		log.debug("server request {} from {}", request.getPath(), sctx);
		EventLoopGroup eventLoopGroup = serverContext.getEventGroup();
		eventLoopGroup.execute(()->{
			try {
				writeResponse(ctx, sctx, request, new FileView(WebMgr.get().getWebContentPath() + request.getPath()));
			} catch (FileNotFoundException e) {
				writeResponse(ctx, sctx, request, WebMgr.get().getInteriorViewFactory().createErrorView(404, "Not Found"));
			}
		});
	}
	
	private void doWork(ChannelHandlerContext ctx, SessionContext sctx, WebRequest request) {
		log.debug("server request {} from {}", request, sctx);
		if (serverContext.getSessionHandlerGroup().receive(sctx, request)) {
			Runnable event = ()->{
				try {
					WebView webView = RemoteDispatchMgr.get().webProcess(request, sctx);
					if (webView == null) {
						writeResponse(ctx, sctx, request, WebMgr.get().getInteriorViewFactory().createBlankView());
					} else {
						webView.ready();
						writeResponse(ctx, sctx, request, webView);
					}
				} catch (CodeException ce) {
					log.error(ce.getMessage(), ce);
					writeResponse(ctx, sctx, request, WebMgr.get().getInteriorViewFactory().createErrorView(500, ce.getCode(), ce.getMsg()));
				} catch (Exception e) {
					log.error(e.getMessage(), e);
					Throwable cause = e;
					WebView webView = null;
					for (int i = 0; i < 5; i ++) {
						if ((cause = cause.getCause()) != null) {
							if (cause instanceof CodeException) {
								CodeException ce = (CodeException) cause;
								webView = WebMgr.get().getInteriorViewFactory().createErrorView(500, ce.getCode(), ce.getMsg());
								break;
							}
						} else {
							break;
						}
					}
					if (webView == null) {
						CodeMsg ce = SessionException.INVOKE_ERROR;
						webView = WebMgr.get().getInteriorViewFactory().createErrorView(500, ce.getCode(), ce.getMsg());
					}
					writeResponse(ctx, sctx, request, webView);
				}
			};
			Method method = RemoteDispatchMgr.get().getMethod(request);
			if (method != null) {
				String singleQueueName = ContainerMgr.get().getSingleQueueName(method);
				if (singleQueueName != null) {
					SingleQueueMgr.get().execute(singleQueueName, event);
				} else {
					if (ContainerMgr.get().isSessionQueue(method)) {
						SessionQueueMgr.get().execute(sctx, event);
					} else {
						serverContext.getEventGroup().execute(event);
					}
				}
			} else {
				serverContext.getEventGroup().execute(event);
			}
		}
	}
	
	public void writeResponse(ChannelHandlerContext ctx, SessionContext sctx, WebRequest request, WebView webView) {
		log.debug("server response {} status[{}] from {}", webView, webView.getStatus(), sctx);
		try {
			DefaultHttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, 
					HttpResponseStatus.valueOf(webView.getStatus()));
			if (HttpUtil.isKeepAlive(request.getHttpRequest())) {
				response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
			}
			response.headers().set(HttpHeaderNames.CONTENT_TYPE, webView.getConentType());
			response.headers().set(HttpHeaderNames.CONTENT_LENGTH, webView.getLength());
			response.headers().set(HttpHeaderNames.SERVER, Summer.NAME);
			response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
			ctx.write(response);
			ctx.write(webView.getChunkedInput());
			ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
		} catch (Exception e) {
			log.error(e.getMessage(), e);
		}
	}
}
