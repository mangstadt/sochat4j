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
}
