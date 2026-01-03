package com.github.mangstadt.sochat4j;

import java.io.IOException;
import java.time.ZoneOffset;
import java.util.List;
import java.util.regex.Pattern;

import org.apache.http.client.config.CookieSpecs;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.impl.client.HttpClients;
import org.jsoup.nodes.Document;

import com.github.mangstadt.sochat4j.util.Http;
import com.github.mangstadt.sochat4j.util.JsonUtils;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * Connects to a room and outputs message events to stdout. Useful for when you
 * need to see the raw message events for debugging purposes.
 * @author Michael Angstadt
 */
public class WebSocketUtility {
	//@formatter:off
	private static Http http = new Http(HttpClients.custom()
		.setDefaultRequestConfig(
			RequestConfig.custom()
				.setCookieSpec(CookieSpecs.STANDARD)
			.build()
		).build()
	);
	//@formatter:on

	public static void main(String[] args) throws Exception {
		var email = args[0];
		var password = args[1];

		System.out.println("Logging in...");

		login(email, password);
		var fkey = getRoomFKey(1);
		var wsUri = getWebsocketUri(1, fkey);

		System.out.println("Connecting to web socket: " + wsUri);
		wsConnect(wsUri);
	}

	static WebSocket wsConnect(String wsUri) {
		var request = new Request.Builder().url(wsUri).addHeader("Origin", "https://chat.stackoverflow.com").build();

		var okHttp = new OkHttpClient();
		return okHttp.newWebSocket(request, new WebSocketListener() {
			@Override
			public void onMessage(WebSocket webSocket, String text) {
				System.out.println(text);
				if (text.contains("shutdown")) {
					System.out.println("Closing web socket.");
					webSocket.close(1001, "");
					System.out.println("Web socket closed.");

					//okHttp.dispatcher().executorService().shutdown();
					//okHttp.connectionPool().evictAll();
				}
			}
		});
	}

	static void login(String email, String password) throws Exception {
		var url = "https://stackoverflow.com/users/login";

		var response = http.get(url);
		var fkey = parseFKey(response.getBodyAsHtml());
		if (fkey == null) {
			throw new Exception("fkey not found.");
		}

		//@formatter:off
		response = http.post(url,
			"email", email,
			"password", password,
			"fkey", fkey
		);
		//@formatter:on

		var statusCode = response.getStatusCode();
		var success = (statusCode == 302);
		if (!success) {
			throw new Exception("Bad credentials");
		}

		parseUserInfo();
	}

	/**
	 * Parses the "fkey" value from an HTML page.
	 * @param response the HTML page
	 * @return the fkey or null if not found
	 */
	static String parseFKey(Document dom) {
		var element = dom.selectFirst("input[name=fkey]");
		return (element == null) ? null : element.attr("value");
	}

	static void parseUserInfo() throws IOException {
		var url = "https://chat.stackoverflow.com";

		var response = http.get(url);
		var dom = response.getBodyAsHtml();

		var link = dom.selectFirst(".topbar-menu-links a");
		if (link != null) {
			var profileUrl = link.attr("href");
			var p = Pattern.compile("/users/(\\d+)");
			var m = p.matcher(profileUrl);
			if (m.find()) {
				var userId = Integer.valueOf(m.group(1));
				System.out.println("User ID: " + userId);
			}

			var username = link.text();
			System.out.println("Username: " + username);
		}
	}

	static String getRoomFKey(int roomId) throws Exception {
		var url = "https://chat.stackoverflow.com/rooms/" + roomId;
		var response = http.get(url);
		var dom = response.getBodyAsHtml();

		return parseFKey(dom);
	}

	static String getWebsocketUri(int roomId, String fkey) throws Exception {
		var url = "https://chat.stackoverflow.com/ws-auth";

		var response = http.post(url, "roomid", roomId, "fkey", fkey);

		var wsUrlNode = response.getBodyAsJson().get("url");

		var wsUrl = wsUrlNode.asText();

		var messages = getMessages(1, fkey);
		var latest = messages.isEmpty() ? null : messages.get(0);
		var time = (latest == null) ? 0 : latest.timestamp().toEpochSecond(ZoneOffset.UTC);

		/*
		 * HttpUrl.parse() returns null because the web socket URI has a
		 * non-HTTP scheme.
		 */
		//@formatter:off
		return new URIBuilder(wsUrl)
			.setParameter("l", Long.toString(time))
		.toString();
		//@formatter:on
	}

	static List<ChatMessage> getMessages(int count, String fkey) throws Exception {
		var url = "https://chat.stackoverflow.com/chats/1/events";

		//@formatter:off
		var response = http.post(url,
			"mode", "messages",
			"msgCount", count,
			"fkey", fkey
		);
		//@formatter:on

		if (response.getStatusCode() == 404) {
			throw new Exception("404");
		}

		var body = response.getBodyAsJson();
		var events = body.get("events");

		if (events == null || !events.isArray()) {
			return List.of();
		}

		//@formatter:off
		return JsonUtils.streamArray(events)
			.map(WebSocketEventParsers::extractChatMessage)
		.toList();
		//@formatter:on
	}

}
