package com.github.mangstadt.sochat4j.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.mangstadt.sochat4j.Site;

import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * @author Michael Angstadt
 */
public class MockWebSocketServer {
	private final WebSocketClient client;
	private final WebSocket webSocket;
	private WebSocketListener listener;

	public MockWebSocketServer(Site site, String url) {
		client = mock(WebSocketClient.class);
		webSocket = mock(WebSocket.class);

		var origin = "https://" + site.getChatDomain() + "/";
		when(client.connect(anyString(), anyString(), any(WebSocketListener.class))).thenAnswer(invocation -> {
			var actualUrl = invocation.getArgument(0, String.class);
			assertEquals(url, actualUrl);
			
			var actualOrigin = invocation.getArgument(1, String.class);
			assertEquals(origin, actualOrigin);
			
			listener = invocation.getArgument(2, WebSocketListener.class);
			return webSocket;
		});
	}

	public WebSocketClient getClient() {
		return client;
	}
	
	public WebSocket getSocket() {
		return webSocket;
	}

	public void send(String text) {
		if (listener == null) {
			fail("Web socket connection did not take place.");
		}
		listener.onMessage(webSocket, text);
	}
}
