package com.github.mangstadt.sochat4j;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
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
import java.util.Arrays;

import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.impl.client.CloseableHttpClient;
import org.junit.Test;

import jakarta.websocket.ClientEndpointConfig;
import jakarta.websocket.Endpoint;
import jakarta.websocket.Session;
import jakarta.websocket.WebSocketContainer;

/**
 * @author Michael Angstadt
 */
public class ChatClientTest {
	@Test
	public void user_info_cant_find_on_homepage() throws Exception {
		for (Site site : Site.values()) {
			//@formatter:off
			CloseableHttpClient httpClient = new MockHttpClientBuilder()
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

			WebSocketContainer ws = mock(WebSocketContainer.class);

			try (ChatClient client = new ChatClient(site, httpClient, ws)) {
				client.login("email", "password");

				assertNull(client.getUsername());
				assertNull(client.getUserId());
			}

			verifyNumberOfRequestsSent(httpClient, 3);
		}
	}

	@Test
	public void user_info() throws Exception {
		for (Site site : Site.values()) {
			//@formatter:off
			CloseableHttpClient httpClient = new MockHttpClientBuilder()
				.login(site, "0123456789abcdef0123456789abcdef", "email", "password", true, "Username", 12345)
			.build();
			//@formatter:on

			WebSocketContainer ws = mock(WebSocketContainer.class);

			try (ChatClient client = new ChatClient(site, httpClient, ws)) {
				client.login("email", "password");

				assertEquals("Username", client.getUsername());
				assertEquals(Integer.valueOf(12345), client.getUserId());
			}

			verifyNumberOfRequestsSent(httpClient, 3);
		}
	}

	@Test(expected = IllegalStateException.class)
	public void joinRoom_not_logged_in() throws Exception {
		//@formatter:off
		CloseableHttpClient httpClient = new MockHttpClientBuilder()
		.build();
		//@formatter:on

		WebSocketContainer ws = mock(WebSocketContainer.class);

		try (ChatClient client = new ChatClient(Site.STACKOVERFLOW, httpClient, ws)) {
			client.joinRoom(1);
		}
	}

	@Test
	public void getRoom_has_not_been_joined() throws Exception {
		//@formatter:off
		CloseableHttpClient httpClient = new MockHttpClientBuilder()
		.build();
		//@formatter:on

		WebSocketContainer ws = mock(WebSocketContainer.class);

		try (ChatClient client = new ChatClient(Site.STACKOVERFLOW, httpClient, ws)) {
			assertNull(client.getRoom(1));
		}

		verifyNumberOfRequestsSent(httpClient, 0);
	}

	@Test
	public void joinRoom_not_found() throws Exception {
		//@formatter:off
		CloseableHttpClient httpClient = new MockHttpClientBuilder()
			.login(Site.STACKOVERFLOW, "0123456789abcdef0123456789abcdef", "email", "password", true, "Username", 12345)
			.requestGet("https://chat.stackoverflow.com/rooms/1")
			.response(404, ResponseSamples.roomNotFound())
		.build();
		//@formatter:on

		WebSocketContainer ws = mock(WebSocketContainer.class);

		try (ChatClient client = new ChatClient(Site.STACKOVERFLOW, httpClient, ws)) {
			client.login("email", "password");
			client.joinRoom(1);
			fail();
		} catch (RoomNotFoundException expected) {
		}

		verifyNumberOfRequestsSent(httpClient, 4);
	}

	@Test
	public void joinRoom_private() throws Exception {
		//@formatter:off
		CloseableHttpClient httpClient = new MockHttpClientBuilder()
			.login(Site.STACKOVERFLOW, "0123456789abcdef0123456789abcdef", "email", "password", true, "Username", 12345)
			.requestGet("https://chat.stackoverflow.com/rooms/1")
			.responseOk(ResponseSamples.privateRoom(1))
		.build();
		//@formatter:on

		WebSocketContainer ws = mock(WebSocketContainer.class);

		try (ChatClient client = new ChatClient(Site.STACKOVERFLOW, httpClient, ws)) {
			client.login("email", "password");
			client.joinRoom(1);
			fail();
		} catch (PrivateRoomException expected) {
		}

		verifyNumberOfRequestsSent(httpClient, 4);
	}

	@Test
	public void joinRoom_no_fkey() throws Exception {
		//@formatter:off
		CloseableHttpClient httpClient = new MockHttpClientBuilder()
			.login(Site.STACKOVERFLOW, "0123456789abcdef0123456789abcdef", "email", "password", true, "Username", 12345)
			.requestGet("https://chat.stackoverflow.com/rooms/1")
			.responseOk("garbage data")
		.build();
		//@formatter:on

		WebSocketContainer ws = mock(WebSocketContainer.class);

		try (ChatClient client = new ChatClient(Site.STACKOVERFLOW, httpClient, ws)) {
			client.login("email", "password");
			client.joinRoom(1);
			fail();
		} catch (IOException expected) {
		}

		verifyNumberOfRequestsSent(httpClient, 4);
	}

	@Test
	public void joinRoom() throws Exception {
		//@formatter:off
		CloseableHttpClient httpClient = new MockHttpClientBuilder()
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

		WebSocketContainer ws = mock(WebSocketContainer.class);
		Session session = mock(Session.class);

		//@formatter:off
		when(ws.connectToServer(
			any(Endpoint.class),
			any(ClientEndpointConfig.class),
			eq(new URI("wss://chat.sockets.stackexchange.com/events/1/37516a6eb3464228bf48a33088b3c247?l=" + webSocketTimestamp(1417041460)))
		)).thenReturn(session);
		//@formatter:on

		try (ChatClient client = new ChatClient(Site.STACKOVERFLOW, httpClient, ws)) {
			client.login("email", "password");

			Room room = client.joinRoom(1);
			assertEquals(1, room.getRoomId());
			assertEquals("0123456789abcdef0123456789abcdef", room.getFkey());
			assertTrue(room.canPost());

			assertSame(room, client.getRoom(1));
			assertTrue(client.isInRoom(1));
			assertEquals(Arrays.asList(room), client.getRooms());

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
	public void joinRoom_cannot_post() throws Exception {
		//@formatter:off
		CloseableHttpClient httpClient = new MockHttpClientBuilder()
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

		WebSocketContainer ws = mock(WebSocketContainer.class);
		Session session = mock(Session.class);

		//@formatter:off
		when(ws.connectToServer(
			any(Endpoint.class),
			any(ClientEndpointConfig.class),
			eq(new URI("wss://chat.sockets.stackexchange.com/events/1/37516a6eb3464228bf48a33088b3c247?l=" + webSocketTimestamp(1417041460)))
		)).thenReturn(session);
		//@formatter:on

		try (ChatClient client = new ChatClient(Site.STACKOVERFLOW, httpClient, ws)) {
			client.login("email", "password");

			Room room = client.joinRoom(1);
			assertFalse(room.canPost());

			try {
				room.sendMessage("test");
				fail();
			} catch (RoomPermissionException expected) {
			}
		}

		verify(session).close();
		verifyNumberOfRequestsSent(httpClient, 7);
	}

	@Test
	public void joinRoom_that_has_no_messages() throws Exception {
		//@formatter:off
		CloseableHttpClient httpClient = new MockHttpClientBuilder()
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

		WebSocketContainer ws = mock(WebSocketContainer.class);
		Session session = mock(Session.class);

		//@formatter:off
		when(ws.connectToServer(
			any(Endpoint.class),
			any(ClientEndpointConfig.class),
			eq(new URI("wss://chat.sockets.stackexchange.com/events/1/37516a6eb3464228bf48a33088b3c247?l=0"))
		)).thenReturn(session);
		//@formatter:on

		try (ChatClient client = new ChatClient(Site.STACKOVERFLOW, httpClient, ws)) {
			client.login("email", "password");
			client.joinRoom(1);
		}

		verify(session).close();
		verifyNumberOfRequestsSent(httpClient, 7);
	}

	@Test
	@SuppressWarnings("resource")
	public void getOriginalMessageContent() throws Exception {
		//@formatter:off
		CloseableHttpClient httpClient = new MockHttpClientBuilder()	
			//login not required

			.requestGet("https://chat.stackoverflow.com/message/1234?plain=true")
			.responseOk("Message")
		.build();
		//@formatter:on

		WebSocketContainer ws = mock(WebSocketContainer.class);

		ChatClient client = new ChatClient(Site.STACKOVERFLOW, httpClient, ws);

		String expected = "Message";
		String actual = client.getOriginalMessageContent(1234);
		assertEquals(expected, actual);
	}

	@Test
	@SuppressWarnings("resource")
	public void getOriginalMessageContent_bad_response() throws Exception {
		//@formatter:off
		CloseableHttpClient httpClient = new MockHttpClientBuilder()	
			//login not required

			.requestGet("https://chat.stackoverflow.com/message/1234?plain=true")
			.response(404, "")
		.build();
		//@formatter:on

		WebSocketContainer ws = mock(WebSocketContainer.class);

		ChatClient client = new ChatClient(Site.STACKOVERFLOW, httpClient, ws);

		try {
			client.getOriginalMessageContent(1234);
			fail();
		} catch (IOException expected) {
		}
	}

	@Test
	public void leave_room() throws Exception {
		//@formatter:off
		CloseableHttpClient httpClient = new MockHttpClientBuilder()
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

		WebSocketContainer ws = mock(WebSocketContainer.class);
		Session session = mock(Session.class);

		//@formatter:off
		when(ws.connectToServer(
			any(Endpoint.class),
			any(ClientEndpointConfig.class),
			eq(new URI("wss://chat.sockets.stackexchange.com/events/1/37516a6eb3464228bf48a33088b3c247?l=" + webSocketTimestamp(1417041460)))
		)).thenReturn(session);
		//@formatter:on

		try (ChatClient client = new ChatClient(Site.STACKOVERFLOW, httpClient, ws)) {
			client.login("email", "password");

			Room room = client.joinRoom(1);
			assertEquals(1, room.getRoomId());
			assertEquals("0123456789abcdef0123456789abcdef", room.getFkey());

			assertSame(room, client.getRoom(1));
			assertTrue(client.isInRoom(1));
			assertEquals(Arrays.asList(room), client.getRooms());

			room.leave();
			assertNull(client.getRoom(1));
			assertFalse(client.isInRoom(1));
			assertEquals(Arrays.asList(), client.getRooms());
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
		Instant instant = Instant.ofEpochSecond(messageTs);
		LocalDateTime dt = LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
		return dt.toEpochSecond(ZoneOffset.UTC);
	}
}
