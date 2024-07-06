package com.github.mangstadt.sochat4j.util;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * @author Michael Angstadt
 */
public class WebSocketClientImpl implements WebSocketClient {
	private final OkHttpClient client = new OkHttpClient();

	@Override
	public WebSocket connect(String url, String origin, WebSocketListener listener) {
		//@formatter:off
		var request = new Request.Builder()
			.url(url)
			.addHeader("Origin", origin)
		.build();
		//@formatter:on

		return client.newWebSocket(request, listener);
	}

	@Override
	public void close() {
		/*
		 * Without these method calls, various threads that OkHttp creates will
		 * continue to run for 1 minute after the bot has shutdown, preventing
		 * its Java process from terminating right away.
		 * 
		 * See: https://javadoc.io/doc/com.squareup.okhttp3/okhttp/3.14.9/okhttp3/OkHttpClient.html
		 */
		client.dispatcher().executorService().shutdown();
		client.connectionPool().evictAll();
	}
}
