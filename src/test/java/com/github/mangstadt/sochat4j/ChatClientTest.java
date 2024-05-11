package com.github.mangstadt.sochat4j;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.impl.client.CloseableHttpClient;
import org.junit.jupiter.api.Test;

import jakarta.websocket.ClientEndpointConfig;
import jakarta.websocket.Endpoint;
import jakarta.websocket.Session;
import jakarta.websocket.WebSocketContainer;

/**
 * @author Michael Angstadt
 */
class ChatClientTest {
	@Test
	void user_info_cant_find_on_homepage() throws Exception {
		for (Site site : Site.values()) {
			//@formatter:off
			var httpClient = new MockHttpClientBuilder()
				.requestGet("https://" + site.getLoginDomain() + "/users/login")
				.responseOk(ResponseSamples.loginPage("0123456789abcdef0123456789abcdef"))
			
				.requestPost("https://" + site.getLoginDomain() + "/users/login",
					"fkey", "0123456789abcdef0123456789abcdef",
					"email", "email",
					"password", "password"
				)
				.response(302, "")
				
				.requestGet("https://" + site.getChatDomain())
				.responseOk("<html>Page structure is different than what is expected.</html>")
			.build();
			//@formatter:on

			var ws = mock(WebSocketContainer.class);

			try (var client = new ChatClient(site, httpClient, ws)) {
				client.login("email", "password");

				assertNull(client.getUsername());
				assertNull(client.getUserId());
			}

			verifyNumberOfRequestsSent(httpClient, 3);
		}
	}

	@Test
	void user_info() throws Exception {
		for (Site site : Site.values()) {
			//@formatter:off
			var httpClient = new MockHttpClientBuilder()
				.login(site, "0123456789abcdef0123456789abcdef", "email", "password", true, "Username", 12345)
			.build();
			//@formatter:on

			WebSocketContainer ws = mock(WebSocketContainer.class);

			try (var client = new ChatClient(site, httpClient, ws)) {
				client.login("email", "password");

				assertEquals("Username", client.getUsername());
				assertEquals(Integer.valueOf(12345), client.getUserId());
			}

			verifyNumberOfRequestsSent(httpClient, 3);
		}
	}

	@SuppressWarnings("resource")
	@Test
	void joinRoom_not_logged_in() throws Exception {
		//@formatter:off
		var httpClient = new MockHttpClientBuilder()
		.build();
		//@formatter:on

		WebSocketContainer ws = mock(WebSocketContainer.class);

		try (var client = new ChatClient(Site.STACKOVERFLOW, httpClient, ws)) {
			assertThrows(IllegalStateException.class, () -> client.joinRoom(1));
		}
	}

	@Test
	void getRoom_has_not_been_joined() throws Exception {
		//@formatter:off
		var httpClient = new MockHttpClientBuilder()
		.build();
		//@formatter:on

		WebSocketContainer ws = mock(WebSocketContainer.class);

		try (var client = new ChatClient(Site.STACKOVERFLOW, httpClient, ws)) {
			assertNull(client.getRoom(1));
		}

		verifyNumberOfRequestsSent(httpClient, 0);
	}

	@Test
	void joinRoom_not_found() throws Exception {
		//@formatter:off
		var httpClient = new MockHttpClientBuilder()
			.login(Site.STACKOVERFLOW, "0123456789abcdef0123456789abcdef", "email", "password", true, "Username", 12345)
			.requestGet("https://chat.stackoverflow.com/rooms/1")
			.response(404, ResponseSamples.roomNotFound())
		.build();
		//@formatter:on

		var ws = mock(WebSocketContainer.class);

		try (var client = new ChatClient(Site.STACKOVERFLOW, httpClient, ws)) {
			client.login("email", "password");
			assertThrows(RoomNotFoundException.class, () -> client.joinRoom(1));
		}

		verifyNumberOfRequestsSent(httpClient, 4);
	}

	@Test
	void joinRoom_private() throws Exception {
		//@formatter:off
		var httpClient = new MockHttpClientBuilder()
			.login(Site.STACKOVERFLOW, "0123456789abcdef0123456789abcdef", "email", "password", true, "Username", 12345)
			.requestGet("https://chat.stackoverflow.com/rooms/1")
			.responseOk(ResponseSamples.privateRoom(1))
		.build();
		//@formatter:on

		var ws = mock(WebSocketContainer.class);

		try (var client = new ChatClient(Site.STACKOVERFLOW, httpClient, ws)) {
			client.login("email", "password");
			assertThrows(PrivateRoomException.class, () -> client.joinRoom(1));
		}

		verifyNumberOfRequestsSent(httpClient, 4);
	}

	@Test
	void joinRoom_no_fkey() throws Exception {
		//@formatter:off
		var httpClient = new MockHttpClientBuilder()
			.login(Site.STACKOVERFLOW, "0123456789abcdef0123456789abcdef", "email", "password", true, "Username", 12345)
			.requestGet("https://chat.stackoverflow.com/rooms/1")
			.responseOk("garbage data")
		.build();
		//@formatter:on

		var ws = mock(WebSocketContainer.class);

		try (var client = new ChatClient(Site.STACKOVERFLOW, httpClient, ws)) {
			client.login("email", "password");
			assertThrows(IOException.class, () -> client.joinRoom(1));
		}

		verifyNumberOfRequestsSent(httpClient, 4);
	}

	@Test
	void joinRoom() throws Exception {
		//@formatter:off
		var httpClient = new MockHttpClientBuilder()
			.login(Site.STACKOVERFLOW, "0123456789abcdef0123456789abcdef", "email", "password", true, "Username", 12345)
			
			.requestGet("https://chat.stackoverflow.com/rooms/1")
			.responseOk(ResponseSamples.room("0123456789abcdef0123456789abcdef"))
			
			.requestPost("https://chat.stackoverflow.com/ws-auth",
				"roomid", "1",
				"fkey", "0123456789abcdef0123456789abcdef"
			)
			.responseOk(ResponseSamples.wsAuth("wss://chat.sockets.stackexchange.com/events/1/37516a6eb3464228bf48a33088b3c247"))
			
			.requestPost("https://chat.stackoverflow.com/chats/1/events",
				"mode", "messages",
				"msgCount", "1",
				"fkey", "0123456789abcdef0123456789abcdef"
			)
			.responseOk(ResponseSamples.events()
				.event(1417041460, "message 1", 50, "User1", 1, 20157245)
			.build())
			
			.requestPost("https://chat.stackoverflow.com/chats/leave/all",
				"quiet", "true",
				"fkey", "0123456789abcdef0123456789abcdef"
			)
			.responseOk("")
		.build();
		//@formatter:on

		var ws = mock(WebSocketContainer.class);
		var session = mock(Session.class);

		//@formatter:off
		when(ws.connectToServer(
			any(Endpoint.class),
			any(ClientEndpointConfig.class),
			eq(new URI("wss://chat.sockets.stackexchange.com/events/1/37516a6eb3464228bf48a33088b3c247?l=" + webSocketTimestamp(1417041460)))
		)).thenReturn(session);
		//@formatter:on

		try (var client = new ChatClient(Site.STACKOVERFLOW, httpClient, ws)) {
			client.login("email", "password");

			var room = client.joinRoom(1);
			assertEquals(1, room.getRoomId());
			assertEquals("0123456789abcdef0123456789abcdef", room.getFkey());
			assertTrue(room.canPost());

			assertSame(room, client.getRoom(1));
			assertTrue(client.isInRoom(1));
			assertEquals(List.of(room), client.getRooms());

			/*
			 * If the room is joined again, it should just return the Room
			 * object.
			 */
			assertSame(room, client.joinRoom(1));
		}

		verify(session).close();
		verifyNumberOfRequestsSent(httpClient, 7);
	}

	@Test
	void joinRoom_cannot_post() throws Exception {
		//@formatter:off
		var httpClient = new MockHttpClientBuilder()
			.login(Site.STACKOVERFLOW, "0123456789abcdef0123456789abcdef", "email", "password", true, "Username", 12345)
			
			.requestGet("https://chat.stackoverflow.com/rooms/1")
			.responseOk(ResponseSamples.room("0123456789abcdef0123456789abcdef", false))
			
			.requestPost("https://chat.stackoverflow.com/ws-auth",
				"roomid", "1",
				"fkey", "0123456789abcdef0123456789abcdef"
			)
			.responseOk(ResponseSamples.wsAuth("wss://chat.sockets.stackexchange.com/events/1/37516a6eb3464228bf48a33088b3c247"))
			
			.requestPost("https://chat.stackoverflow.com/chats/1/events",
				"mode", "messages",
				"msgCount", "1",
				"fkey", "0123456789abcdef0123456789abcdef"
			)
			.responseOk(ResponseSamples.events()
				.event(1417041460, "message 1", 50, "User1", 1, 20157245)
			.build())
			
			.requestPost("https://chat.stackoverflow.com/chats/leave/all",
				"quiet", "true",
				"fkey", "0123456789abcdef0123456789abcdef"
			)
			.responseOk("")
		.build();
		//@formatter:on

		var ws = mock(WebSocketContainer.class);
		var session = mock(Session.class);

		//@formatter:off
		when(ws.connectToServer(
			any(Endpoint.class),
			any(ClientEndpointConfig.class),
			eq(new URI("wss://chat.sockets.stackexchange.com/events/1/37516a6eb3464228bf48a33088b3c247?l=" + webSocketTimestamp(1417041460)))
		)).thenReturn(session);
		//@formatter:on

		try (var client = new ChatClient(Site.STACKOVERFLOW, httpClient, ws)) {
			client.login("email", "password");

			var room = client.joinRoom(1);
			assertFalse(room.canPost());
			assertThrows(RoomPermissionException.class, () -> room.sendMessage("test"));
		}

		verify(session).close();
		verifyNumberOfRequestsSent(httpClient, 7);
	}

	@Test
	void joinRoom_that_has_no_messages() throws Exception {
		//@formatter:off
		var httpClient = new MockHttpClientBuilder()
			.login(Site.STACKOVERFLOW, "0123456789abcdef0123456789abcdef", "email", "password", true, "Username", 12345)
				
			.requestGet("https://chat.stackoverflow.com/rooms/1")
			.responseOk(ResponseSamples.room("0123456789abcdef0123456789abcdef"))
			
			.requestPost("https://chat.stackoverflow.com/ws-auth",
				"roomid", "1",
				"fkey", "0123456789abcdef0123456789abcdef"
			)
			.responseOk(ResponseSamples.wsAuth("wss://chat.sockets.stackexchange.com/events/1/37516a6eb3464228bf48a33088b3c247"))
			
			.requestPost("https://chat.stackoverflow.com/chats/1/events",
				"mode", "messages",
				"msgCount", "1",
				"fkey", "0123456789abcdef0123456789abcdef"
			)
			.responseOk(ResponseSamples.events()
				//empty
			.build())
			
			.requestPost("https://chat.stackoverflow.com/chats/leave/all",
				"quiet", "true",
				"fkey", "0123456789abcdef0123456789abcdef"
			)
			.responseOk("")
		.build();
		//@formatter:on

		var ws = mock(WebSocketContainer.class);
		var session = mock(Session.class);

		//@formatter:off
		when(ws.connectToServer(
			any(Endpoint.class),
			any(ClientEndpointConfig.class),
			eq(new URI("wss://chat.sockets.stackexchange.com/events/1/37516a6eb3464228bf48a33088b3c247?l=0"))
		)).thenReturn(session);
		//@formatter:on

		try (var client = new ChatClient(Site.STACKOVERFLOW, httpClient, ws)) {
			client.login("email", "password");
			client.joinRoom(1);
		}

		verify(session).close();
		verifyNumberOfRequestsSent(httpClient, 7);
	}

	@Test
	@SuppressWarnings("resource")
	void getMessageContent() throws Exception {
		//@formatter:off
		var httpClient = new MockHttpClientBuilder()	
			//login not required

			.requestGet("https://chat.stackoverflow.com/message/1234?plain=false")
			.responseOk("Message")
		.build();
		//@formatter:on

		var ws = mock(WebSocketContainer.class);

		var client = new ChatClient(Site.STACKOVERFLOW, httpClient, ws);

		var expected = "Message";
		var actual = client.getMessageContent(1234);
		assertEquals(expected, actual);
	}

	@Test
	@SuppressWarnings("resource")
	void getOriginalMessageContent() throws Exception {
		//@formatter:off
		var httpClient = new MockHttpClientBuilder()	
			//login not required

			.requestGet("https://chat.stackoverflow.com/message/1234?plain=true")
			.responseOk("Message")
		.build();
		//@formatter:on

		var ws = mock(WebSocketContainer.class);

		var client = new ChatClient(Site.STACKOVERFLOW, httpClient, ws);

		var expected = "Message";
		var actual = client.getOriginalMessageContent(1234);
		assertEquals(expected, actual);
	}

	@Test
	@SuppressWarnings("resource")
	void getOriginalMessageContent_bad_response() throws Exception {
		//@formatter:off
		var httpClient = new MockHttpClientBuilder()	
			//login not required

			.requestGet("https://chat.stackoverflow.com/message/1234?plain=true")
			.response(404, "")
		.build();
		//@formatter:on

		var ws = mock(WebSocketContainer.class);

		var client = new ChatClient(Site.STACKOVERFLOW, httpClient, ws);
		assertThrows(IOException.class, () -> client.getOriginalMessageContent(1234));
	}

	@Test
	void leave_room() throws Exception {
		//@formatter:off
		var httpClient = new MockHttpClientBuilder()
			.login(Site.STACKOVERFLOW, "0123456789abcdef0123456789abcdef", "email", "password", true, "Username", 12345)
				
			.requestGet("https://chat.stackoverflow.com/rooms/1")
			.responseOk(ResponseSamples.room("0123456789abcdef0123456789abcdef"))
			
			.requestPost("https://chat.stackoverflow.com/ws-auth",
				"roomid", "1",
				"fkey", "0123456789abcdef0123456789abcdef"
			)
			.responseOk(ResponseSamples.wsAuth("wss://chat.sockets.stackexchange.com/events/1/37516a6eb3464228bf48a33088b3c247"))
			
			.requestPost("https://chat.stackoverflow.com/chats/1/events",
				"mode", "messages",
				"msgCount", "1",
				"fkey", "0123456789abcdef0123456789abcdef"
			)
			.responseOk(ResponseSamples.events()
				.event(1417041460, "message 1", 50, "User1", 1, 20157245)
			.build())
			
			.requestPost("https://chat.stackoverflow.com/chats/leave/1",
				"quiet", "true",
				"fkey", "0123456789abcdef0123456789abcdef"
			)
			.responseOk("")
		.build();
		//@formatter:on

		var ws = mock(WebSocketContainer.class);
		var session = mock(Session.class);

		//@formatter:off
		when(ws.connectToServer(
			any(Endpoint.class),
			any(ClientEndpointConfig.class),
			eq(new URI("wss://chat.sockets.stackexchange.com/events/1/37516a6eb3464228bf48a33088b3c247?l=" + webSocketTimestamp(1417041460)))
		)).thenReturn(session);
		//@formatter:on

		try (var client = new ChatClient(Site.STACKOVERFLOW, httpClient, ws)) {
			client.login("email", "password");

			Room room = client.joinRoom(1);
			assertEquals(1, room.getRoomId());
			assertEquals("0123456789abcdef0123456789abcdef", room.getFkey());

			assertSame(room, client.getRoom(1));
			assertTrue(client.isInRoom(1));
			assertEquals(List.of(room), client.getRooms());

			room.leave();
			assertNull(client.getRoom(1));
			assertFalse(client.isInRoom(1));
			assertEquals(List.of(), client.getRooms());
		}

		verify(session).close();
		verifyNumberOfRequestsSent(httpClient, 7);
	}

	private static void verifyNumberOfRequestsSent(CloseableHttpClient httpClient, int requests) throws IOException {
		verify(httpClient, times(requests)).execute(any(HttpUriRequest.class));
		verify(httpClient).close();
	}

	/**
	 * This conversion is needed for the unit test to run on other machines. I
	 * think it has something to do with the default timezone.
	 * @param messageTs the timestamp of the chat message
	 * @return the value that will be put in the web socket URL
	 */
	private static long webSocketTimestamp(long messageTs) {
		var instant = Instant.ofEpochSecond(messageTs);
		var dt = LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
		return dt.toEpochSecond(ZoneOffset.UTC);
	}
}
