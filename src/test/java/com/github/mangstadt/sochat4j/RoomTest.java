package com.github.mangstadt.sochat4j;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
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
import java.util.ArrayList;
import java.util.List;

import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.impl.client.CloseableHttpClient;
import org.junit.jupiter.api.Test;

import com.github.mangstadt.sochat4j.event.Event;
import com.github.mangstadt.sochat4j.event.MessageDeletedEvent;
import com.github.mangstadt.sochat4j.event.MessageEditedEvent;
import com.github.mangstadt.sochat4j.event.MessagePostedEvent;
import com.github.mangstadt.sochat4j.event.MessageStarredEvent;
import com.github.mangstadt.sochat4j.event.MessagesMovedEvent;
import com.github.mangstadt.sochat4j.event.UserEnteredEvent;
import com.github.mangstadt.sochat4j.event.UserLeftEvent;
import com.github.mangstadt.sochat4j.util.Sleeper;

import jakarta.websocket.ClientEndpointConfig;
import jakarta.websocket.Endpoint;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.MessageHandler.Whole;
import jakarta.websocket.Session;
import jakarta.websocket.WebSocketContainer;

/**
 * @author Michael Angstadt
 */
@SuppressWarnings("resource")
class RoomTest {
	/**
	 * Anything that is not JSON or that doesn't have the proper JSON fields
	 * should be silently ignored.
	 */
	@Test
	void webSocket_ignore_bad_data() throws Exception {
		//@formatter:off
		var httpClient = new MockHttpClientBuilder()
			.login(Site.STACKOVERFLOW, "0123456789abcdef0123456789abcdef", "email", "password", true, "Username", 12345)
			.joinRoom(Site.STACKOVERFLOW, 1, "0123456789abcdef0123456789abcdef", "wss://chat.sockets.stackexchange.com/events/1/37516a6eb3464228bf48a33088b3c247", 1417023460)
		.build();
		//@formatter:on

		var wsContainer = mock(WebSocketContainer.class);
		var wsRoom1 = new MockWebSocketServer(wsContainer, "wss://chat.sockets.stackexchange.com/events/1/37516a6eb3464228bf48a33088b3c247?l=" + webSocketTimestamp(1417023460));

		var chatClient = new ChatClient(Site.STACKOVERFLOW, httpClient, wsContainer);
		chatClient.login("email", "password");
		chatClient.joinRoom(1);

		wsRoom1.send("not JSON"); //not JSON
		wsRoom1.send("{\"r20\": {} }"); //this is not room 20
		wsRoom1.send("{\"r1\": {} }"); //no "e" field
		wsRoom1.send("{\"r1\": { \"e\": {} } }"); //"e" is not an array
		wsRoom1.send("{\"r1\": { \"e\": [] } }"); //"e" field is empty
		wsRoom1.send("{\"r1\": { \"e\": [ {} ] } }"); //no "event_type" field
		wsRoom1.send("{\"r1\": { \"e\": [ {\"event_type\": \"invalid\"} ] } }"); //"event_type" field is not an integer
		wsRoom1.send("{\"r1\": { \"e\": [ {\"event_type\": 9001} ] } }"); //"event_type" field has an unknown value

		verifyNumberOfRequestsSent(httpClient, 6);
	}

	@Test
	void webSocket_MessagePostedEvent() throws Exception {
		//@formatter:off
		var httpClient = new MockHttpClientBuilder()
			.login(Site.STACKOVERFLOW, "0123456789abcdef0123456789abcdef", "email", "password", true, "Username", 12345)
			.joinRoom(Site.STACKOVERFLOW, 1, "0123456789abcdef0123456789abcdef", "wss://chat.sockets.stackexchange.com/events/1/37516a6eb3464228bf48a33088b3c247", 1417023460)
		.build();
		//@formatter:on

		var wsContainer = mock(WebSocketContainer.class);
		var wsRoom1 = new MockWebSocketServer(wsContainer, "wss://chat.sockets.stackexchange.com/events/1/37516a6eb3464228bf48a33088b3c247?l=" + webSocketTimestamp(1417023460));

		var chatClient = new ChatClient(Site.STACKOVERFLOW, httpClient, wsContainer);
		chatClient.login("email", "password");
		var room = chatClient.joinRoom(1);

		var events = new ArrayList<Event>();
		room.addEventListener(MessagePostedEvent.class, events::add);

		//@formatter:off
		wsRoom1.send(ResponseSamples.webSocket()
		.room(1, "Sandbox")
			.newMessage().id(1).timestamp(1417041460).content("one").user(50, "User").messageId(20157245).done()
		.build());
		//@formatter:on

		var it = events.iterator();

		var event = (MessagePostedEvent) it.next();
		assertEquals(1, event.getEventId());
		assertEquals(timestamp(1417041460), event.getTimestamp());
		assertEquals("one", event.getMessage().getContent().getContent());
		assertEquals(0, event.getMessage().getEdits());
		assertEquals(0, event.getMessage().getMentionedUserId());
		assertEquals(20157245, event.getMessage().getMessageId());
		assertEquals(0, event.getMessage().getParentMessageId());
		assertEquals(1, event.getMessage().getRoomId());
		assertEquals("Sandbox", event.getMessage().getRoomName());
		assertEquals(0, event.getMessage().getStars());
		assertEquals(timestamp(1417041460), event.getMessage().getTimestamp());
		assertEquals(50, event.getMessage().getUserId());
		assertEquals("User", event.getMessage().getUsername());

		assertFalse(it.hasNext());
		verifyNumberOfRequestsSent(httpClient, 6);
	}

	@Test
	void webSocket_MessagePostedEvent_reply() throws Exception {
		//@formatter:off
		var httpClient = new MockHttpClientBuilder()
			.login(Site.STACKOVERFLOW, "0123456789abcdef0123456789abcdef", "email", "password", true, "Username", 12345)
			.joinRoom(Site.STACKOVERFLOW, 1, "0123456789abcdef0123456789abcdef", "wss://chat.sockets.stackexchange.com/events/1/37516a6eb3464228bf48a33088b3c247", 1417023460)
		.build();
		//@formatter:on

		var wsContainer = mock(WebSocketContainer.class);
		var wsRoom1 = new MockWebSocketServer(wsContainer, "wss://chat.sockets.stackexchange.com/events/1/37516a6eb3464228bf48a33088b3c247?l=" + webSocketTimestamp(1417023460));

		var chatClient = new ChatClient(Site.STACKOVERFLOW, httpClient, wsContainer);
		chatClient.login("email", "password");
		var room = chatClient.joinRoom(1);

		var events = new ArrayList<Event>();
		room.addEventListener(MessagePostedEvent.class, events::add);
		room.addEventListener(MessageEditedEvent.class, events::add);

		//@formatter:off
		wsRoom1.send(ResponseSamples.webSocket()
		.room(1, "Sandbox")
			.reply().id(1).timestamp(1417041460).content("@Bob Howdy.").user(50, "User").targetUser(100).messageId(20157245).parentId(20157230).done()
			.newMessage().id(2).timestamp(1417041460).content("@Bob Howdy.").user(50, "User").messageId(20157245).parentId(20157230).done()
			.reply().id(3).timestamp(1417041470).edits(1).content("@Greg Sup.").user(50, "User").targetUser(150).messageId(20157240).parentId(20157220).done()
			.messageEdited().id(4).timestamp(1417041470).edits(1).content("@Greg Sup.").user(50, "User").messageId(20157240).parentId(20157220).done()
		.build());
		//@formatter:on

		var it = events.iterator();

		var postedEvent = (MessagePostedEvent) it.next();
		assertEquals(1, postedEvent.getEventId());
		assertEquals(timestamp(1417041460), postedEvent.getTimestamp());
		assertEquals("@Bob Howdy.", postedEvent.getMessage().getContent().getContent());
		assertEquals(0, postedEvent.getMessage().getEdits());
		assertEquals(100, postedEvent.getMessage().getMentionedUserId());
		assertEquals(20157245, postedEvent.getMessage().getMessageId());
		assertEquals(20157230, postedEvent.getMessage().getParentMessageId());
		assertEquals(1, postedEvent.getMessage().getRoomId());
		assertEquals("Sandbox", postedEvent.getMessage().getRoomName());
		assertEquals(0, postedEvent.getMessage().getStars());
		assertEquals(timestamp(1417041460), postedEvent.getMessage().getTimestamp());
		assertEquals(50, postedEvent.getMessage().getUserId());
		assertEquals("User", postedEvent.getMessage().getUsername());

		var editedEvent = (MessageEditedEvent) it.next();
		assertEquals(3, editedEvent.getEventId());
		assertEquals(timestamp(1417041470), editedEvent.getTimestamp());
		assertEquals("@Greg Sup.", editedEvent.getMessage().getContent().getContent());
		assertEquals(1, editedEvent.getMessage().getEdits());
		assertEquals(150, editedEvent.getMessage().getMentionedUserId());
		assertEquals(20157240, editedEvent.getMessage().getMessageId());
		assertEquals(20157220, editedEvent.getMessage().getParentMessageId());
		assertEquals(1, editedEvent.getMessage().getRoomId());
		assertEquals("Sandbox", editedEvent.getMessage().getRoomName());
		assertEquals(0, editedEvent.getMessage().getStars());
		assertEquals(timestamp(1417041470), editedEvent.getMessage().getTimestamp());
		assertEquals(50, editedEvent.getMessage().getUserId());
		assertEquals("User", editedEvent.getMessage().getUsername());

		assertFalse(it.hasNext());
		verifyNumberOfRequestsSent(httpClient, 6);
	}

	@Test
	void webSocket_MessagePostedEvent_mention() throws Exception {
		//@formatter:off
		var httpClient = new MockHttpClientBuilder()
			.login(Site.STACKOVERFLOW, "0123456789abcdef0123456789abcdef", "email", "password", true, "Username", 12345)
			.joinRoom(Site.STACKOVERFLOW, 1, "0123456789abcdef0123456789abcdef", "wss://chat.sockets.stackexchange.com/events/1/37516a6eb3464228bf48a33088b3c247", 1417023460)
		.build();
		//@formatter:on

		var wsContainer = mock(WebSocketContainer.class);
		var wsRoom1 = new MockWebSocketServer(wsContainer, "wss://chat.sockets.stackexchange.com/events/1/37516a6eb3464228bf48a33088b3c247?l=" + webSocketTimestamp(1417023460));

		var chatClient = new ChatClient(Site.STACKOVERFLOW, httpClient, wsContainer);
		chatClient.login("email", "password");
		var room = chatClient.joinRoom(1);

		var events = new ArrayList<Event>();
		room.addEventListener(MessagePostedEvent.class, events::add);
		room.addEventListener(MessageEditedEvent.class, events::add);

		//@formatter:off
		wsRoom1.send(ResponseSamples.webSocket()
		.room(1, "Sandbox")
			.mention().id(1).timestamp(1417041460).content("@Bob Howdy.").user(50, "User").targetUser(100).messageId(20157245).done()
			.newMessage().id(2).timestamp(1417041460).content("@Bob Howdy.").user(50, "User").messageId(20157245).done()
			.mention().id(3).timestamp(1417041470).edits(1).content("@Greg Sup.").user(50, "User").targetUser(150).messageId(20157240).done()
			.messageEdited().id(4).timestamp(1417041470).edits(1).content("@Greg Sup.").user(50, "User").messageId(20157240).done()
		.build());
		//@formatter:on

		var it = events.iterator();

		var postedEvent = (MessagePostedEvent) it.next();
		assertEquals(1, postedEvent.getEventId());
		assertEquals(timestamp(1417041460), postedEvent.getTimestamp());
		assertEquals("@Bob Howdy.", postedEvent.getMessage().getContent().getContent());
		assertEquals(0, postedEvent.getMessage().getEdits());
		assertEquals(100, postedEvent.getMessage().getMentionedUserId());
		assertEquals(20157245, postedEvent.getMessage().getMessageId());
		assertEquals(0, postedEvent.getMessage().getParentMessageId());
		assertEquals(1, postedEvent.getMessage().getRoomId());
		assertEquals("Sandbox", postedEvent.getMessage().getRoomName());
		assertEquals(0, postedEvent.getMessage().getStars());
		assertEquals(timestamp(1417041460), postedEvent.getMessage().getTimestamp());
		assertEquals(50, postedEvent.getMessage().getUserId());
		assertEquals("User", postedEvent.getMessage().getUsername());

		var editedEvent = (MessageEditedEvent) it.next();
		assertEquals(3, editedEvent.getEventId());
		assertEquals(timestamp(1417041470), editedEvent.getTimestamp());
		assertEquals("@Greg Sup.", editedEvent.getMessage().getContent().getContent());
		assertEquals(1, editedEvent.getMessage().getEdits());
		assertEquals(150, editedEvent.getMessage().getMentionedUserId());
		assertEquals(20157240, editedEvent.getMessage().getMessageId());
		assertEquals(0, editedEvent.getMessage().getParentMessageId());
		assertEquals(1, editedEvent.getMessage().getRoomId());
		assertEquals("Sandbox", editedEvent.getMessage().getRoomName());
		assertEquals(0, editedEvent.getMessage().getStars());
		assertEquals(timestamp(1417041470), editedEvent.getMessage().getTimestamp());
		assertEquals(50, editedEvent.getMessage().getUserId());
		assertEquals("User", editedEvent.getMessage().getUsername());

		assertFalse(it.hasNext());
		verifyNumberOfRequestsSent(httpClient, 6);
	}

	@Test
	void webSocket_MessageEditedEvent() throws Exception {
		//@formatter:off
		var httpClient = new MockHttpClientBuilder()
			.login(Site.STACKOVERFLOW, "0123456789abcdef0123456789abcdef", "email", "password", true, "Username", 12345)
			.joinRoom(Site.STACKOVERFLOW, 1, "0123456789abcdef0123456789abcdef", "wss://chat.sockets.stackexchange.com/events/1/37516a6eb3464228bf48a33088b3c247", 1417023460)
		.build();
		//@formatter:on

		var wsContainer = mock(WebSocketContainer.class);
		var wsRoom1 = new MockWebSocketServer(wsContainer, "wss://chat.sockets.stackexchange.com/events/1/37516a6eb3464228bf48a33088b3c247?l=" + webSocketTimestamp(1417023460));

		var chatClient = new ChatClient(Site.STACKOVERFLOW, httpClient, wsContainer);
		chatClient.login("email", "password");
		var room = chatClient.joinRoom(1);

		var events = new ArrayList<Event>();
		room.addEventListener(MessageEditedEvent.class, events::add);

		//@formatter:off
		wsRoom1.send(ResponseSamples.webSocket()
		.room(1, "Sandbox")
			.messageEdited().id(1).timestamp(1417041460).content("one").user(50, "User").messageId(20157245).edits(1).done()
		.build());
		//@formatter:on

		var it = events.iterator();

		var event = (MessageEditedEvent) it.next();
		assertEquals(1, event.getEventId());
		assertEquals(timestamp(1417041460), event.getTimestamp());
		assertEquals("one", event.getMessage().getContent().getContent());
		assertEquals(1, event.getMessage().getEdits());
		assertEquals(0, event.getMessage().getMentionedUserId());
		assertEquals(20157245, event.getMessage().getMessageId());
		assertEquals(0, event.getMessage().getParentMessageId());
		assertEquals(1, event.getMessage().getRoomId());
		assertEquals("Sandbox", event.getMessage().getRoomName());
		assertEquals(0, event.getMessage().getStars());
		assertEquals(timestamp(1417041460), event.getMessage().getTimestamp());
		assertEquals(50, event.getMessage().getUserId());
		assertEquals("User", event.getMessage().getUsername());

		assertFalse(it.hasNext());
		verifyNumberOfRequestsSent(httpClient, 6);
	}

	@Test
	void webSocket_MessageStarredEvent() throws Exception {
		//@formatter:off
		var httpClient = new MockHttpClientBuilder()
			.login(Site.STACKOVERFLOW, "0123456789abcdef0123456789abcdef", "email", "password", true, "Username", 12345)
			.joinRoom(Site.STACKOVERFLOW, 1, "0123456789abcdef0123456789abcdef", "wss://chat.sockets.stackexchange.com/events/1/37516a6eb3464228bf48a33088b3c247", 1417023460)
		.build();
		//@formatter:on

		var wsContainer = mock(WebSocketContainer.class);
		var wsRoom1 = new MockWebSocketServer(wsContainer, "wss://chat.sockets.stackexchange.com/events/1/37516a6eb3464228bf48a33088b3c247?l=" + webSocketTimestamp(1417023460));

		var chatClient = new ChatClient(Site.STACKOVERFLOW, httpClient, wsContainer);
		chatClient.login("email", "password");
		var room = chatClient.joinRoom(1);

		var events = new ArrayList<Event>();
		room.addEventListener(MessageStarredEvent.class, events::add);

		//@formatter:off
		wsRoom1.send(ResponseSamples.webSocket()
		.room(1, "Sandbox")
			.messageStarred().id(1).timestamp(1417041460).content("one").messageId(20157245).stars(1).done()
		.build());
		//@formatter:on

		var it = events.iterator();

		var event = (MessageStarredEvent) it.next();
		assertEquals(1, event.getEventId());
		assertEquals(timestamp(1417041460), event.getTimestamp());
		assertEquals("one", event.getMessage().getContent().getContent());
		assertEquals(0, event.getMessage().getEdits());
		assertEquals(0, event.getMessage().getMentionedUserId());
		assertEquals(20157245, event.getMessage().getMessageId());
		assertEquals(0, event.getMessage().getParentMessageId());
		assertEquals(1, event.getMessage().getRoomId());
		assertEquals("Sandbox", event.getMessage().getRoomName());
		assertEquals(1, event.getMessage().getStars());
		assertEquals(timestamp(1417041460), event.getMessage().getTimestamp());
		assertEquals(0, event.getMessage().getUserId());
		assertNull(event.getMessage().getUsername());

		assertFalse(it.hasNext());
		verifyNumberOfRequestsSent(httpClient, 6);
	}

	@Test
	void webSocket_MessageDeletedEvent() throws Exception {
		//@formatter:off
		var httpClient = new MockHttpClientBuilder()
			.login(Site.STACKOVERFLOW, "0123456789abcdef0123456789abcdef", "email", "password", true, "Username", 12345)
			.joinRoom(Site.STACKOVERFLOW, 1, "0123456789abcdef0123456789abcdef", "wss://chat.sockets.stackexchange.com/events/1/37516a6eb3464228bf48a33088b3c247", 1417023460)
		.build();
		//@formatter:on

		var wsContainer = mock(WebSocketContainer.class);
		var wsRoom1 = new MockWebSocketServer(wsContainer, "wss://chat.sockets.stackexchange.com/events/1/37516a6eb3464228bf48a33088b3c247?l=" + webSocketTimestamp(1417023460));

		var chatClient = new ChatClient(Site.STACKOVERFLOW, httpClient, wsContainer);
		chatClient.login("email", "password");
		var room = chatClient.joinRoom(1);

		var events = new ArrayList<Event>();
		room.addEventListener(MessageDeletedEvent.class, events::add);

		//@formatter:off
		wsRoom1.send(ResponseSamples.webSocket()
		.room(1, "Sandbox")
			.messageDeleted().id(1).timestamp(1417041460).user(50, "User").messageId(20157245).done()
		.build());
		//@formatter:on

		var it = events.iterator();

		var event = (MessageDeletedEvent) it.next();
		assertEquals(1, event.getEventId());
		assertEquals(timestamp(1417041460), event.getTimestamp());
		assertNull(event.getMessage().getContent());
		assertEquals(0, event.getMessage().getEdits());
		assertEquals(0, event.getMessage().getMentionedUserId());
		assertEquals(20157245, event.getMessage().getMessageId());
		assertEquals(0, event.getMessage().getParentMessageId());
		assertEquals(1, event.getMessage().getRoomId());
		assertEquals("Sandbox", event.getMessage().getRoomName());
		assertEquals(0, event.getMessage().getStars());
		assertEquals(timestamp(1417041460), event.getMessage().getTimestamp());
		assertEquals(50, event.getMessage().getUserId());
		assertEquals("User", event.getMessage().getUsername());

		assertFalse(it.hasNext());
		verifyNumberOfRequestsSent(httpClient, 6);
	}

	@Test
	void webSocket_MessagesMovedEvent_out() throws Exception {
		//@formatter:off
		var httpClient = new MockHttpClientBuilder()
			.login(Site.STACKOVERFLOW, "0123456789abcdef0123456789abcdef", "email", "password", true, "Username", 12345)
			.joinRoom(Site.STACKOVERFLOW, 1, "0123456789abcdef0123456789abcdef", "wss://chat.sockets.stackexchange.com/events/1/37516a6eb3464228bf48a33088b3c247", 1417023460)
		.build();
		//@formatter:on

		var wsContainer = mock(WebSocketContainer.class);
		var wsRoom1 = new MockWebSocketServer(wsContainer, "wss://chat.sockets.stackexchange.com/events/1/37516a6eb3464228bf48a33088b3c247?l=" + webSocketTimestamp(1417023460));

		var chatClient = new ChatClient(Site.STACKOVERFLOW, httpClient, wsContainer);
		chatClient.login("email", "password");
		var room = chatClient.joinRoom(1);

		var events = new ArrayList<Event>();
		room.addEventListener(MessagesMovedEvent.class, events::add);

		//@formatter:off
		wsRoom1.send(ResponseSamples.webSocket()
		.room(1, "Sandbox")
			.movedOut().id(1).timestamp(1417041460).content("one").user(50, "User").messageId(20157245).moved().done()
			.movedOut().id(2).timestamp(1417041470).content("two").user(50, "User").messageId(20157246).moved().done()
			.movedOut().id(3).timestamp(1417041480).content("three").user(50, "User").messageId(20157247).moved().done()
			.newMessage().id(4).timestamp(1417041490).content("&rarr; <i><a href=\"http://chat.stackoverflow.com/transcript/message/38258010#38258010\">3 messages</a> moved to <a href=\"http://chat.stackoverflow.com/rooms/48058/trash\">Trash</a></i>").user(100, "RoomOwner").messageId(20157248).done()
		.build());
		//@formatter:on

		var it = events.iterator();

		var event = (MessagesMovedEvent) it.next();
		assertEquals(4, event.getEventId());
		assertEquals(timestamp(1417041490), event.getTimestamp());
		assertEquals(1, event.getSourceRoomId());
		assertEquals("Sandbox", event.getSourceRoomName());
		assertEquals(48058, event.getDestRoomId());
		assertEquals("Trash", event.getDestRoomName());
		assertEquals(100, event.getMoverUserId());
		assertEquals("RoomOwner", event.getMoverUsername());

		var messagesIt = event.getMessages().iterator();

		var message = messagesIt.next();
		assertEquals("one", message.getContent().getContent());
		assertEquals(0, message.getEdits());
		assertEquals(0, message.getMentionedUserId());
		assertEquals(20157245, message.getMessageId());
		assertEquals(0, message.getParentMessageId());
		assertEquals(1, message.getRoomId());
		assertEquals("Sandbox", message.getRoomName());
		assertEquals(0, message.getStars());
		assertEquals(timestamp(1417041460), message.getTimestamp());
		assertEquals(50, message.getUserId());
		assertEquals("User", message.getUsername());

		message = messagesIt.next();
		assertEquals("two", message.getContent().getContent());
		assertEquals(0, message.getEdits());
		assertEquals(0, message.getMentionedUserId());
		assertEquals(20157246, message.getMessageId());
		assertEquals(0, message.getParentMessageId());
		assertEquals(1, message.getRoomId());
		assertEquals("Sandbox", message.getRoomName());
		assertEquals(0, message.getStars());
		assertEquals(timestamp(1417041470), message.getTimestamp());
		assertEquals(50, message.getUserId());
		assertEquals("User", message.getUsername());

		message = messagesIt.next();
		assertEquals("three", message.getContent().getContent());
		assertEquals(0, message.getEdits());
		assertEquals(0, message.getMentionedUserId());
		assertEquals(20157247, message.getMessageId());
		assertEquals(0, message.getParentMessageId());
		assertEquals(1, message.getRoomId());
		assertEquals("Sandbox", message.getRoomName());
		assertEquals(0, message.getStars());
		assertEquals(timestamp(1417041480), message.getTimestamp());
		assertEquals(50, message.getUserId());
		assertEquals("User", message.getUsername());

		assertFalse(messagesIt.hasNext());
		assertFalse(it.hasNext());
		verifyNumberOfRequestsSent(httpClient, 6);
	}

	@Test
	void webSocket_MessagesMovedEvent_in() throws Exception {
		//@formatter:off
		var httpClient = new MockHttpClientBuilder()
			.login(Site.STACKOVERFLOW, "0123456789abcdef0123456789abcdef", "email", "password", true, "Username", 12345)
			.joinRoom(Site.STACKOVERFLOW, 1, "0123456789abcdef0123456789abcdef", "wss://chat.sockets.stackexchange.com/events/1/37516a6eb3464228bf48a33088b3c247", 1417023460)
		.build();
		//@formatter:on

		var wsContainer = mock(WebSocketContainer.class);
		var wsRoom1 = new MockWebSocketServer(wsContainer, "wss://chat.sockets.stackexchange.com/events/1/37516a6eb3464228bf48a33088b3c247?l=" + webSocketTimestamp(1417023460));

		var chatClient = new ChatClient(Site.STACKOVERFLOW, httpClient, wsContainer);
		chatClient.login("email", "password");
		var room = chatClient.joinRoom(1);

		var events = new ArrayList<Event>();
		room.addEventListener(MessagesMovedEvent.class, events::add);

		//@formatter:off
		wsRoom1.send(ResponseSamples.webSocket()
		.room(1, "Sandbox")
			.movedIn().id(1).timestamp(1417041460).content("one").user(50, "User").messageId(20157245).moved().done()
			.movedIn().id(2).timestamp(1417041470).content("two").user(50, "User").messageId(20157246).moved().done()
			.movedIn().id(3).timestamp(1417041480).content("three").user(50, "User").messageId(20157247).moved().done()
			.newMessage().id(4).timestamp(1417041490).content("&larr; <i>3 messages moved from <a href=\"http://chat.stackoverflow.com/rooms/139/jaba\">Jaba</a></i>").user(100, "RoomOwner").messageId(20157248).done()
		.build());
		//@formatter:on

		var it = events.iterator();

		var event = (MessagesMovedEvent) it.next();
		assertEquals(4, event.getEventId());
		assertEquals(timestamp(1417041490), event.getTimestamp());
		assertEquals(139, event.getSourceRoomId());
		assertEquals("Jaba", event.getSourceRoomName());
		assertEquals(1, event.getDestRoomId());
		assertEquals("Sandbox", event.getDestRoomName());
		assertEquals(100, event.getMoverUserId());
		assertEquals("RoomOwner", event.getMoverUsername());

		var messagesIt = event.getMessages().iterator();

		var message = messagesIt.next();
		assertEquals("one", message.getContent().getContent());
		assertEquals(0, message.getEdits());
		assertEquals(0, message.getMentionedUserId());
		assertEquals(20157245, message.getMessageId());
		assertEquals(0, message.getParentMessageId());
		assertEquals(1, message.getRoomId());
		assertEquals("Sandbox", message.getRoomName());
		assertEquals(0, message.getStars());
		assertEquals(timestamp(1417041460), message.getTimestamp());
		assertEquals(50, message.getUserId());
		assertEquals("User", message.getUsername());

		message = messagesIt.next();
		assertEquals("two", message.getContent().getContent());
		assertEquals(0, message.getEdits());
		assertEquals(0, message.getMentionedUserId());
		assertEquals(20157246, message.getMessageId());
		assertEquals(0, message.getParentMessageId());
		assertEquals(1, message.getRoomId());
		assertEquals("Sandbox", message.getRoomName());
		assertEquals(0, message.getStars());
		assertEquals(timestamp(1417041470), message.getTimestamp());
		assertEquals(50, message.getUserId());
		assertEquals("User", message.getUsername());

		message = messagesIt.next();
		assertEquals("three", message.getContent().getContent());
		assertEquals(0, message.getEdits());
		assertEquals(0, message.getMentionedUserId());
		assertEquals(20157247, message.getMessageId());
		assertEquals(0, message.getParentMessageId());
		assertEquals(1, message.getRoomId());
		assertEquals("Sandbox", message.getRoomName());
		assertEquals(0, message.getStars());
		assertEquals(timestamp(1417041480), message.getTimestamp());
		assertEquals(50, message.getUserId());
		assertEquals("User", message.getUsername());

		assertFalse(messagesIt.hasNext());
		assertFalse(it.hasNext());
		verifyNumberOfRequestsSent(httpClient, 6);
	}

	@Test
	void webSocket_UserEnteredEvent() throws Exception {
		//@formatter:off
		var httpClient = new MockHttpClientBuilder()
			.login(Site.STACKOVERFLOW, "0123456789abcdef0123456789abcdef", "email", "password", true, "Username", 12345)
			.joinRoom(Site.STACKOVERFLOW, 1, "0123456789abcdef0123456789abcdef", "wss://chat.sockets.stackexchange.com/events/1/37516a6eb3464228bf48a33088b3c247", 1417023460)
		.build();
		//@formatter:on

		var wsContainer = mock(WebSocketContainer.class);
		var wsRoom1 = new MockWebSocketServer(wsContainer, "wss://chat.sockets.stackexchange.com/events/1/37516a6eb3464228bf48a33088b3c247?l=" + webSocketTimestamp(1417023460));

		var chatClient = new ChatClient(Site.STACKOVERFLOW, httpClient, wsContainer);
		chatClient.login("email", "password");
		var room = chatClient.joinRoom(1);

		var events = new ArrayList<Event>();
		room.addEventListener(UserEnteredEvent.class, events::add);

		//@formatter:off
		wsRoom1.send(ResponseSamples.webSocket()
		.room(1, "Sandbox")
			.userEntered().id(1).timestamp(1417041460).user(50, "User").done()
		.build());
		//@formatter:on

		var it = events.iterator();

		var event = (UserEnteredEvent) it.next();
		assertEquals(1, event.getEventId());
		assertEquals(1, event.getRoomId());
		assertEquals("Sandbox", event.getRoomName());
		assertEquals(timestamp(1417041460), event.getTimestamp());
		assertEquals(50, event.getUserId());
		assertEquals("User", event.getUsername());

		assertFalse(it.hasNext());
		verifyNumberOfRequestsSent(httpClient, 6);
	}

	@Test
	void webSocket_UserLeftEvent() throws Exception {
		//@formatter:off
		var httpClient = new MockHttpClientBuilder()
			.login(Site.STACKOVERFLOW, "0123456789abcdef0123456789abcdef", "email", "password", true, "Username", 12345)
			.joinRoom(Site.STACKOVERFLOW, 1, "0123456789abcdef0123456789abcdef", "wss://chat.sockets.stackexchange.com/events/1/37516a6eb3464228bf48a33088b3c247", 1417023460)
		.build();
		//@formatter:on

		var wsContainer = mock(WebSocketContainer.class);
		var wsRoom1 = new MockWebSocketServer(wsContainer, "wss://chat.sockets.stackexchange.com/events/1/37516a6eb3464228bf48a33088b3c247?l=" + webSocketTimestamp(1417023460));

		var chatClient = new ChatClient(Site.STACKOVERFLOW, httpClient, wsContainer);
		chatClient.login("email", "password");
		var room = chatClient.joinRoom(1);

		var events = new ArrayList<Event>();
		room.addEventListener(UserLeftEvent.class, events::add);

		//@formatter:off
		wsRoom1.send(ResponseSamples.webSocket()
		.room(1, "Sandbox")
			.userLeft().id(1).timestamp(1417041460).user(50, "User").done()
		.build());
		//@formatter:on

		var it = events.iterator();

		var event = (UserLeftEvent) it.next();
		assertEquals(1, event.getEventId());
		assertEquals(1, event.getRoomId());
		assertEquals("Sandbox", event.getRoomName());
		assertEquals(timestamp(1417041460), event.getTimestamp());
		assertEquals(50, event.getUserId());
		assertEquals("User", event.getUsername());

		assertFalse(it.hasNext());
		verifyNumberOfRequestsSent(httpClient, 6);
	}

	@Test
	void webSocket_ignore_events_from_other_rooms() throws Exception {
		//@formatter:off
		var httpClient = new MockHttpClientBuilder()
			.login(Site.STACKOVERFLOW, "0123456789abcdef0123456789abcdef", "email", "password", true, "Username", 12345)
			.joinRoom(Site.STACKOVERFLOW, 1, "0123456789abcdef0123456789abcdef", "wss://chat.sockets.stackexchange.com/events/1/37516a6eb3464228bf48a33088b3c247", 1417023460)
		.build();
		//@formatter:on

		var wsContainer = mock(WebSocketContainer.class);
		var wsRoom1 = new MockWebSocketServer(wsContainer, "wss://chat.sockets.stackexchange.com/events/1/37516a6eb3464228bf48a33088b3c247?l=" + webSocketTimestamp(1417023460));

		var chatClient = new ChatClient(Site.STACKOVERFLOW, httpClient, wsContainer);
		chatClient.login("email", "password");
		var room = chatClient.joinRoom(1);

		var events = new ArrayList<Event>();
		room.addEventListener(MessagePostedEvent.class, events::add);

		//@formatter:off
		wsRoom1.send(ResponseSamples.webSocket()
		.room(1, "Sandbox")
			.newMessage().id(1).timestamp(1417041460).content("one").user(50, "User").messageId(20157245).done()
		.room(139, "Jaba")
			.newMessage().id(2).timestamp(1417041460).content("two").user(50, "User").messageId(20157245).done()
		.build());
		//@formatter:on

		var it = events.iterator();

		var event = (MessagePostedEvent) it.next();
		assertEquals(1, event.getEventId());
		assertEquals(timestamp(1417041460), event.getTimestamp());
		assertEquals("one", event.getMessage().getContent().getContent());
		assertEquals(0, event.getMessage().getEdits());
		assertEquals(0, event.getMessage().getMentionedUserId());
		assertEquals(20157245, event.getMessage().getMessageId());
		assertEquals(0, event.getMessage().getParentMessageId());
		assertEquals(1, event.getMessage().getRoomId());
		assertEquals("Sandbox", event.getMessage().getRoomName());
		assertEquals(0, event.getMessage().getStars());
		assertEquals(timestamp(1417041460), event.getMessage().getTimestamp());
		assertEquals(50, event.getMessage().getUserId());
		assertEquals("User", event.getMessage().getUsername());

		assertFalse(it.hasNext());
		verifyNumberOfRequestsSent(httpClient, 6);
	}

	@Test
	void webSocket_listen_for_all_events() throws Exception {
		//@formatter:off
		var httpClient = new MockHttpClientBuilder()
			.login(Site.STACKOVERFLOW, "0123456789abcdef0123456789abcdef", "email", "password", true, "Username", 12345)
			.joinRoom(Site.STACKOVERFLOW, 1, "0123456789abcdef0123456789abcdef", "wss://chat.sockets.stackexchange.com/events/1/37516a6eb3464228bf48a33088b3c247", 1417023460)
		.build();
		//@formatter:on

		var wsContainer = mock(WebSocketContainer.class);
		var wsRoom1 = new MockWebSocketServer(wsContainer, "wss://chat.sockets.stackexchange.com/events/1/37516a6eb3464228bf48a33088b3c247?l=" + webSocketTimestamp(1417023460));

		var chatClient = new ChatClient(Site.STACKOVERFLOW, httpClient, wsContainer);
		chatClient.login("email", "password");
		var room = chatClient.joinRoom(1);

		var events = new ArrayList<Event>();
		room.addEventListener(events::add);

		//@formatter:off
		wsRoom1.send(ResponseSamples.webSocket()
		.room(1, "Sandbox")
			.userEntered().id(1).timestamp(1417041460).user(50, "User").done()
			.newMessage().id(2).timestamp(1417041470).content("<i>meow</i>").user(50, "User").messageId(20157245).done()
		.build());
		
		wsRoom1.send(ResponseSamples.webSocket()
		.room(1, "Sandbox")
			.userLeft().id(3).timestamp(1417041480).user(50, "User").done()
		.build());
		//@formatter:on

		var it = events.iterator();
		{
			var event = (UserEnteredEvent) it.next();
			assertEquals(1, event.getEventId());
			assertEquals(1, event.getRoomId());
			assertEquals("Sandbox", event.getRoomName());
			assertEquals(timestamp(1417041460), event.getTimestamp());
			assertEquals(50, event.getUserId());
			assertEquals("User", event.getUsername());
		}

		{
			var event = (MessagePostedEvent) it.next();
			assertEquals(2, event.getEventId());
			assertEquals(timestamp(1417041470), event.getTimestamp());
			assertEquals("<i>meow</i>", event.getMessage().getContent().getContent());
			assertEquals(0, event.getMessage().getEdits());
			assertEquals(0, event.getMessage().getMentionedUserId());
			assertEquals(20157245, event.getMessage().getMessageId());
			assertEquals(0, event.getMessage().getParentMessageId());
			assertEquals(1, event.getMessage().getRoomId());
			assertEquals("Sandbox", event.getMessage().getRoomName());
			assertEquals(0, event.getMessage().getStars());
			assertEquals(timestamp(1417041470), event.getMessage().getTimestamp());
			assertEquals(50, event.getMessage().getUserId());
			assertEquals("User", event.getMessage().getUsername());
		}

		{
			var event = (UserLeftEvent) it.next();
			assertEquals(3, event.getEventId());
			assertEquals(1, event.getRoomId());
			assertEquals("Sandbox", event.getRoomName());
			assertEquals(timestamp(1417041480), event.getTimestamp());
			assertEquals(50, event.getUserId());
			assertEquals("User", event.getUsername());
		}

		assertFalse(it.hasNext());
		verifyNumberOfRequestsSent(httpClient, 6);
	}

	@Test
	void getMessages() throws Exception {
		//@formatter:off
		var httpClient = new MockHttpClientBuilder()
			.login(Site.STACKOVERFLOW, "0123456789abcdef0123456789abcdef", "email", "password", true, "Username", 12345)
			.joinRoom(Site.STACKOVERFLOW, 1, "0123456789abcdef0123456789abcdef", "wss://chat.sockets.stackexchange.com/events/1/37516a6eb3464228bf48a33088b3c247", 1417023460)

			.requestPost("https://chat.stackoverflow.com/chats/1/events",
				"mode", "messages",
				"msgCount", "3",
				"fkey", "0123456789abcdef0123456789abcdef"
			)
			.responseOk(ResponseSamples.events()
				.event(1417041460, "one", 50, "User", 1, 20157245)
				.event(1417041470, "two", 50, "User", 1, 20157246)
				.event(1417041480, "three", 50, "User", 1, 20157247)
			.build())
		.build();
		//@formatter:on

		var wsContainer = mock(WebSocketContainer.class);

		var chatClient = new ChatClient(Site.STACKOVERFLOW, httpClient, wsContainer);
		chatClient.login("email", "password");
		var room = chatClient.joinRoom(1);
		var messages = room.getMessages(3);

		var it = messages.iterator();

		var message = it.next();
		assertEquals("one", message.getContent().getContent());
		assertEquals(0, message.getEdits());
		assertEquals(0, message.getMentionedUserId());
		assertEquals(20157245, message.getMessageId());
		assertEquals(0, message.getParentMessageId());
		assertEquals(1, message.getRoomId());
		assertNull(message.getRoomName());
		assertEquals(0, message.getStars());
		assertEquals(timestamp(1417041460), message.getTimestamp());
		assertEquals(50, message.getUserId());
		assertEquals("User", message.getUsername());

		message = it.next();
		assertEquals("two", message.getContent().getContent());
		assertEquals(0, message.getEdits());
		assertEquals(0, message.getMentionedUserId());
		assertEquals(20157246, message.getMessageId());
		assertEquals(0, message.getParentMessageId());
		assertEquals(1, message.getRoomId());
		assertNull(message.getRoomName());
		assertEquals(0, message.getStars());
		assertEquals(timestamp(1417041470), message.getTimestamp());
		assertEquals(50, message.getUserId());
		assertEquals("User", message.getUsername());

		message = it.next();
		assertEquals("three", message.getContent().getContent());
		assertEquals(0, message.getEdits());
		assertEquals(0, message.getMentionedUserId());
		assertEquals(20157247, message.getMessageId());
		assertEquals(0, message.getParentMessageId());
		assertEquals(1, message.getRoomId());
		assertNull(message.getRoomName());
		assertEquals(0, message.getStars());
		assertEquals(timestamp(1417041480), message.getTimestamp());
		assertEquals(50, message.getUserId());
		assertEquals("User", message.getUsername());

		assertFalse(it.hasNext());
		verifyNumberOfRequestsSent(httpClient, 7);
	}

	@Test
	void getMessages_bad_responses() throws Exception {
		//@formatter:off
		var httpClient = new MockHttpClientBuilder()
			.login(Site.STACKOVERFLOW, "0123456789abcdef0123456789abcdef", "email", "password", true, "Username", 12345)
			.joinRoom(Site.STACKOVERFLOW, 1, "0123456789abcdef0123456789abcdef", "wss://chat.sockets.stackexchange.com/events/1/37516a6eb3464228bf48a33088b3c247", 1417023460)

			.requestPost("https://chat.stackoverflow.com/chats/1/events",
				"mode", "messages",
				"msgCount", "3",
				"fkey", "0123456789abcdef0123456789abcdef"
			)
			.response(404, "")
			
			.requestPost("https://chat.stackoverflow.com/chats/1/events",
				"mode", "messages",
				"msgCount", "3",
				"fkey", "0123456789abcdef0123456789abcdef"
			)
			.responseOk("not JSON")
			
			.requestPost("https://chat.stackoverflow.com/chats/1/events",
				"mode", "messages",
				"msgCount", "3",
				"fkey", "0123456789abcdef0123456789abcdef"
			)
			.responseOk("{}")
			
			.requestPost("https://chat.stackoverflow.com/chats/1/events",
				"mode", "messages",
				"msgCount", "3",
				"fkey", "0123456789abcdef0123456789abcdef"
			)
			.responseOk("{\"events\":{}}")
			
			.requestPost("https://chat.stackoverflow.com/chats/1/events",
				"mode", "messages",
				"msgCount", "3",
				"fkey", "0123456789abcdef0123456789abcdef"
			)
			.responseOk("{\"events\":[]}")
		.build();
		//@formatter:on

		var wsContainer = mock(WebSocketContainer.class);

		var chatClient = new ChatClient(Site.STACKOVERFLOW, httpClient, wsContainer);
		chatClient.login("email", "password");
		var room = chatClient.joinRoom(1);

		try {
			room.getMessages(3);
			fail();
		} catch (IOException expected) {
		}

		try {
			room.getMessages(3);
			fail();
		} catch (IOException expected) {
		}

		assertEquals(List.of(), room.getMessages(3));
		assertEquals(List.of(), room.getMessages(3));
		assertEquals(List.of(), room.getMessages(3));

		verifyNumberOfRequestsSent(httpClient, 11);
	}

	@Test
	void sendMessage() throws Exception {
		//@formatter:off
		var httpClient = new MockHttpClientBuilder()
			.login(Site.STACKOVERFLOW, "0123456789abcdef0123456789abcdef", "email", "password", true, "Username", 12345)
			.joinRoom(Site.STACKOVERFLOW, 1, "0123456789abcdef0123456789abcdef", "wss://chat.sockets.stackexchange.com/events/1/37516a6eb3464228bf48a33088b3c247", 1417023460)

			.requestPost("https://chat.stackoverflow.com/chats/1/messages/new",
				"text", "one",
				"fkey", "0123456789abcdef0123456789abcdef"
			)
			.responseOk(ResponseSamples.newMessage(1))
		.build();
		//@formatter:on

		var wsContainer = mock(WebSocketContainer.class);

		var chatClient = new ChatClient(Site.STACKOVERFLOW, httpClient, wsContainer);
		chatClient.login("email", "password");
		var room = chatClient.joinRoom(1);
		assertEquals(1, room.sendMessage("one"));

		verifyNumberOfRequestsSent(httpClient, 7);
	}

	@Test
	void sendMessage_split_strategy() throws Exception {
		//@formatter:off
		var httpClient = new MockHttpClientBuilder()
			.login(Site.STACKOVERFLOW, "0123456789abcdef0123456789abcdef", "email", "password", true, "Username", 12345)
			.joinRoom(Site.STACKOVERFLOW, 1, "0123456789abcdef0123456789abcdef", "wss://chat.sockets.stackexchange.com/events/1/37516a6eb3464228bf48a33088b3c247", 1417023460)
			
			.requestPost("https://chat.stackoverflow.com/chats/1/messages/new",
				"text", "Java is an island of Indonesia. With a population of over 141 million (the island itself) or 145 million (the administrative region), Java is home to 56.7 percent of the Indonesian population and is the most populous island on Earth. The Indonesian capital city, Jakarta, is located on western Java. Much of Indonesian history took place on Java. It was the center of powerful Hindu-Buddhist empires, the Islamic sultanates, and the core of the colonial Dutch East Indies. Java was also the ...",
				"fkey", "0123456789abcdef0123456789abcdef"
			)
			.responseOk(ResponseSamples.newMessage(1))
			
			.requestPost("https://chat.stackoverflow.com/chats/1/messages/new",
				"text", "center of the Indonesian struggle for independence during the 1930s and 1940s. Java dominates Indonesia politically, economically and culturally.",
				"fkey", "0123456789abcdef0123456789abcdef"
			)
			.responseOk(ResponseSamples.newMessage(2))
			
			.requestPost("https://chat.stackoverflow.com/chats/1/messages/new",
				"text", "Java is an island of Indonesia. With a population of over 141 million (the island itself) or 145 million (the administrative region), Java is home to 56.7 percent of the Indonesian population and is the most populous island on Earth.\nThe Indonesian capital city, Jakarta, is located on western Java. Much of Indonesian history took place on Java. It was the center of powerful Hindu-Buddhist empires, the Islamic sultanates, and the core of the colonial Dutch East Indies. Java was also the center of the Indonesian struggle for independence during the 1930s and 1940s. Java dominates Indonesia politically, economically and culturally.",
				"fkey", "0123456789abcdef0123456789abcdef"
			)
			.responseOk(ResponseSamples.newMessage(3))
		.build();
		//@formatter:on

		var wsContainer = mock(WebSocketContainer.class);
		var session = mock(Session.class);
		doReturn(session).when(wsContainer).connectToServer(any(Endpoint.class), any(ClientEndpointConfig.class), eq(new URI("wss://chat.sockets.stackexchange.com/events/1/37516a6eb3464228bf48a33088b3c247?l=1417023460")));

		var chatClient = new ChatClient(Site.STACKOVERFLOW, httpClient, wsContainer);
		chatClient.login("email", "password");
		var room = chatClient.joinRoom(1);

		//split up into 2 posts
		assertEquals(List.of(1L, 2L), room.sendMessage("Java is an island of Indonesia. With a population of over 141 million (the island itself) or 145 million (the administrative region), Java is home to 56.7 percent of the Indonesian population and is the most populous island on Earth. The Indonesian capital city, Jakarta, is located on western Java. Much of Indonesian history took place on Java. It was the center of powerful Hindu-Buddhist empires, the Islamic sultanates, and the core of the colonial Dutch East Indies. Java was also the center of the Indonesian struggle for independence during the 1930s and 1940s. Java dominates Indonesia politically, economically and culturally.", SplitStrategy.WORD));

		//do not split because it has a newline
		assertEquals(List.of(3L), room.sendMessage("Java is an island of Indonesia. With a population of over 141 million (the island itself) or 145 million (the administrative region), Java is home to 56.7 percent of the Indonesian population and is the most populous island on Earth.\nThe Indonesian capital city, Jakarta, is located on western Java. Much of Indonesian history took place on Java. It was the center of powerful Hindu-Buddhist empires, the Islamic sultanates, and the core of the colonial Dutch East Indies. Java was also the center of the Indonesian struggle for independence during the 1930s and 1940s. Java dominates Indonesia politically, economically and culturally.", SplitStrategy.WORD));

		verifyNumberOfRequestsSent(httpClient, 9);
	}

	@Test
	void sendMessage_posting_too_fast() throws Exception {
		//@formatter:off
		var httpClient = new MockHttpClientBuilder()
			.login(Site.STACKOVERFLOW, "0123456789abcdef0123456789abcdef", "email", "password", true, "Username", 12345)
			.joinRoom(Site.STACKOVERFLOW, 1, "0123456789abcdef0123456789abcdef", "wss://chat.sockets.stackexchange.com/events/1/37516a6eb3464228bf48a33088b3c247", 1417023460)
			
			.requestPost("https://chat.stackoverflow.com/chats/1/messages/new",
				"text", "one",
				"fkey", "0123456789abcdef0123456789abcdef"
			)
			.response(409, "You can perform this action again in 2 seconds")
			
			.requestPost("https://chat.stackoverflow.com/chats/1/messages/new",
				"text", "one",
				"fkey", "0123456789abcdef0123456789abcdef"
			)
			.responseOk(ResponseSamples.newMessage(1))
		.build();
		//@formatter:on

		var wsContainer = mock(WebSocketContainer.class);
		var session = mock(Session.class);
		doReturn(session).when(wsContainer).connectToServer(any(Endpoint.class), any(ClientEndpointConfig.class), eq(new URI("wss://chat.sockets.stackexchange.com/events/1/37516a6eb3464228bf48a33088b3c247?l=1417023460")));

		var chatClient = new ChatClient(Site.STACKOVERFLOW, httpClient, wsContainer);
		chatClient.login("email", "password");
		var room1 = chatClient.joinRoom(1);

		Sleeper.startUnitTest();
		try {
			assertEquals(1, room1.sendMessage("one"));
			assertEquals(2000, Sleeper.getTimeSlept());
		} finally {
			Sleeper.endUnitTest();
		}

		verifyNumberOfRequestsSent(httpClient, 8);
	}

	@Test
	void sendMessage_posting_too_fast_cant_parse_wait_time() throws Exception {
		//TODO
	}

	@Test
	void sendMessage_posting_too_fast_give_up() throws Exception {
		//TODO
	}

	@Test
	void sendMessage_permission_problem() throws Exception {
		//@formatter:off
		var httpClient = new MockHttpClientBuilder()
			.login(Site.STACKOVERFLOW, "0123456789abcdef0123456789abcdef", "email", "password", true, "Username", 12345)
			
			.requestGet("https://chat.stackoverflow.com/rooms/1")
			.responseOk(ResponseSamples.protectedRoom("0123456789abcdef0123456789abcdef")) //room is protected
			
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
		.build();
		//@formatter:on

		var wsContainer = mock(WebSocketContainer.class);
		var session = mock(Session.class);
		doReturn(session).when(wsContainer).connectToServer(any(Endpoint.class), any(ClientEndpointConfig.class), eq(new URI("wss://chat.sockets.stackexchange.com/events/1/37516a6eb3464228bf48a33088b3c247?l=1417023460")));

		var chatClient = new ChatClient(Site.STACKOVERFLOW, httpClient, wsContainer);
		chatClient.login("email", "password");
		var room = chatClient.joinRoom(1);
		try {
			room.sendMessage("one");
			fail();
		} catch (RoomPermissionException expected) {
		}

		verifyNumberOfRequestsSent(httpClient, 6);
	}

	@Test
	void sendMessage_bad_responses() throws Exception {
		//@formatter:off
		var httpClient = new MockHttpClientBuilder()
			.login(Site.STACKOVERFLOW, "0123456789abcdef0123456789abcdef", "email", "password", true, "Username", 12345)
			.joinRoom(Site.STACKOVERFLOW, 1, "0123456789abcdef0123456789abcdef", "wss://chat.sockets.stackexchange.com/events/1/37516a6eb3464228bf48a33088b3c247", 1417023460)

			.requestPost("https://chat.stackoverflow.com/chats/1/messages/new",
				"text", "one",
				"fkey", "0123456789abcdef0123456789abcdef"
			)
			.response(404, "The room does not exist, or you do not have permission")
			
			.requestPost("https://chat.stackoverflow.com/chats/1/messages/new",
				"text", "one",
				"fkey", "0123456789abcdef0123456789abcdef"
			)
			.responseOk("not JSON")
			
			.requestPost("https://chat.stackoverflow.com/chats/1/messages/new",
				"text", "one",
				"fkey", "0123456789abcdef0123456789abcdef"
			)
			.responseOk("{}")
			
			.requestPost("https://chat.stackoverflow.com/chats/1/messages/new",
				"text", "one",
				"fkey", "0123456789abcdef0123456789abcdef"
			)
			.responseOk("{\"id\": \"value\"}")
		.build();
		//@formatter:on

		var wsContainer = mock(WebSocketContainer.class);

		var chatClient = new ChatClient(Site.STACKOVERFLOW, httpClient, wsContainer);
		chatClient.login("email", "password");
		var room = chatClient.joinRoom(1);

		try {
			room.sendMessage("one");
			fail();
		} catch (IOException expected) {
		}

		try {
			room.sendMessage("one");
			fail();
		} catch (IOException expected) {
		}

		assertEquals(0, room.sendMessage("one"));
		assertEquals(0, room.sendMessage("one"));

		verifyNumberOfRequestsSent(httpClient, 10);
	}

	@Test
	void editMessage() throws Exception {
		//@formatter:off
		var httpClient = new MockHttpClientBuilder()
			.login(Site.STACKOVERFLOW, "0123456789abcdef0123456789abcdef", "email", "password", true, "Username", 12345)
			.joinRoom(Site.STACKOVERFLOW, 1, "0123456789abcdef0123456789abcdef", "wss://chat.sockets.stackexchange.com/events/1/37516a6eb3464228bf48a33088b3c247", 1417023460)

			.requestPost("https://chat.stackoverflow.com/messages/20157247",
				"text", "edited",
				"fkey", "0123456789abcdef0123456789abcdef"
			)
			.responseOk("\"ok\"")
			
			.requestPost("https://chat.stackoverflow.com/messages/20157247",
				"text", "edited",
				"fkey", "0123456789abcdef0123456789abcdef"
			)
			.response(302, "")
			
			.requestPost("https://chat.stackoverflow.com/messages/20157247",
				"text", "edited",
				"fkey", "0123456789abcdef0123456789abcdef"
			)
			.responseOk("\"This message has already been deleted and cannot be edited\"")
				
			.requestPost("https://chat.stackoverflow.com/messages/20157247",
				"text", "edited",
				"fkey", "0123456789abcdef0123456789abcdef"
			)
			.responseOk("\"It is too late to edit this message\"")
					
			.requestPost("https://chat.stackoverflow.com/messages/20157247",
				"text", "edited",
				"fkey", "0123456789abcdef0123456789abcdef"
			)
			.responseOk("\"You can only edit your own messages\"")
			
			.requestPost("https://chat.stackoverflow.com/messages/20157247",
				"text", "edited",
				"fkey", "0123456789abcdef0123456789abcdef"
			)
			.responseOk("unexpected response")
		.build();
		//@formatter:on

		var wsContainer = mock(WebSocketContainer.class);

		var chatClient = new ChatClient(Site.STACKOVERFLOW, httpClient, wsContainer);
		chatClient.login("email", "password");
		var room = chatClient.joinRoom(1);

		room.editMessage(20157247, "edited");

		try {
			room.editMessage(20157247, "edited");
			fail();
		} catch (IOException expected) {
		}
		try {
			room.editMessage(20157247, "edited");
			fail();
		} catch (IOException expected) {
		}
		try {
			room.editMessage(20157247, "edited");
			fail();
		} catch (IOException expected) {
		}
		try {
			room.editMessage(20157247, "edited");
			fail();
		} catch (IOException expected) {
		}

		room.editMessage(20157247, "edited");

		verifyNumberOfRequestsSent(httpClient, 12);
	}

	@Test
	void deleteMessage() throws Exception {
		//@formatter:off
		var httpClient = new MockHttpClientBuilder()
			.login(Site.STACKOVERFLOW, "0123456789abcdef0123456789abcdef", "email", "password", true, "Username", 12345)
			.joinRoom(Site.STACKOVERFLOW, 1, "0123456789abcdef0123456789abcdef", "wss://chat.sockets.stackexchange.com/events/1/37516a6eb3464228bf48a33088b3c247", 1417023460)

			.requestPost("https://chat.stackoverflow.com/messages/20157247/delete",
				"fkey", "0123456789abcdef0123456789abcdef"
			)
			.responseOk("\"ok\"")
			
			.requestPost("https://chat.stackoverflow.com/messages/20157247/delete",
				"fkey", "0123456789abcdef0123456789abcdef"
			)
			.response(302, "")
			
			.requestPost("https://chat.stackoverflow.com/messages/20157247/delete",
				"fkey", "0123456789abcdef0123456789abcdef"
			)
			.responseOk("\"This message has already been deleted.\"")
				
			.requestPost("https://chat.stackoverflow.com/messages/20157247/delete",
				"fkey", "0123456789abcdef0123456789abcdef"
			)
			.responseOk("\"It is too late to delete this message\"")
					
			.requestPost("https://chat.stackoverflow.com/messages/20157247/delete",
				"fkey", "0123456789abcdef0123456789abcdef"
			)
			.responseOk("\"You can only delete your own messages\"")
			
			.requestPost("https://chat.stackoverflow.com/messages/20157247/delete",
				"fkey", "0123456789abcdef0123456789abcdef"
			)
			.responseOk("unexpected response")
		.build();
		//@formatter:on

		var wsContainer = mock(WebSocketContainer.class);

		var chatClient = new ChatClient(Site.STACKOVERFLOW, httpClient, wsContainer);
		chatClient.login("email", "password");
		var room = chatClient.joinRoom(1);

		room.deleteMessage(20157247);

		try {
			room.deleteMessage(20157247);
			fail();
		} catch (IOException expected) {
		}

		room.deleteMessage(20157247);

		try {
			room.deleteMessage(20157247);
			fail();
		} catch (IOException expected) {
		}
		try {
			room.deleteMessage(20157247);
			fail();
		} catch (IOException expected) {
		}

		room.deleteMessage(20157247);

		verifyNumberOfRequestsSent(httpClient, 12);
	}

	@Test
	void getPingableUsers() throws Exception {
		//@formatter:off
		var httpClient = new MockHttpClientBuilder()
			.login(Site.STACKOVERFLOW, "0123456789abcdef0123456789abcdef", "email", "password", true, "Username", 12345)
			.joinRoom(Site.STACKOVERFLOW, 1, "0123456789abcdef0123456789abcdef", "wss://chat.sockets.stackexchange.com/events/1/37516a6eb3464228bf48a33088b3c247", 1417023460)

			.requestGet("https://chat.stackoverflow.com/rooms/pingable/1"	)
			.responseOk(ResponseSamples.pingableUsers()
				.user(13379, "Michael", 1501806926, 1501769526)
				.user(4258326, "OakBot", 1501806934, 1501802068)
			.build())
		.build();
		//@formatter:on

		var wsContainer = mock(WebSocketContainer.class);

		var chatClient = new ChatClient(Site.STACKOVERFLOW, httpClient, wsContainer);
		chatClient.login("email", "password");
		var room = chatClient.joinRoom(1);

		var users = room.getPingableUsers();

		var it = users.iterator();

		var user = it.next();
		assertEquals(timestamp(1501769526), user.getLastPost());
		assertEquals(1, user.getRoomId());
		assertEquals(13379, user.getUserId());
		assertEquals("Michael", user.getUsername());

		user = it.next();
		assertEquals(timestamp(1501802068), user.getLastPost());
		assertEquals(1, user.getRoomId());
		assertEquals(4258326, user.getUserId());
		assertEquals("OakBot", user.getUsername());

		assertFalse(it.hasNext());

		verifyNumberOfRequestsSent(httpClient, 7);
	}

	@Test
	void getPingableUsers_bad_responses() throws Exception {
		//@formatter:off
		var httpClient = new MockHttpClientBuilder()
			.login(Site.STACKOVERFLOW, "0123456789abcdef0123456789abcdef", "email", "password", true, "Username", 12345)
			.joinRoom(Site.STACKOVERFLOW, 1, "0123456789abcdef0123456789abcdef", "wss://chat.sockets.stackexchange.com/events/1/37516a6eb3464228bf48a33088b3c247", 1417023460)
			
			.requestGet("https://chat.stackoverflow.com/rooms/pingable/1"	)
			.response(404, "")

			.requestGet("https://chat.stackoverflow.com/rooms/pingable/1"	)
			.responseOk("not JSON")
			
			.requestGet("https://chat.stackoverflow.com/rooms/pingable/1"	)
			.responseOk("{}")
			
			.requestGet("https://chat.stackoverflow.com/rooms/pingable/1"	)
			.responseOk("[]")
			
			.requestGet("https://chat.stackoverflow.com/rooms/pingable/1"	)
			.responseOk("[ {}, [1, \"User\"], [13379, \"Michael\", 1501806926, 1501769526] ]")
		.build();
		//@formatter:on

		var wsContainer = mock(WebSocketContainer.class);

		var chatClient = new ChatClient(Site.STACKOVERFLOW, httpClient, wsContainer);
		chatClient.login("email", "password");
		var room = chatClient.joinRoom(1);

		try {
			room.getPingableUsers();
			fail();
		} catch (IOException expected) {
		}

		try {
			room.getPingableUsers();
			fail();
		} catch (IOException expected) {
		}

		assertEquals(0, room.getPingableUsers().size());
		assertEquals(0, room.getPingableUsers().size());
		assertEquals(1, room.getPingableUsers().size());

		verifyNumberOfRequestsSent(httpClient, 11);
	}

	@Test
	void getUserInfo() throws Exception {
		//@formatter:off
		var httpClient = new MockHttpClientBuilder()
			.login(Site.STACKOVERFLOW, "0123456789abcdef0123456789abcdef", "email", "password", true, "Username", 12345)
			.joinRoom(Site.STACKOVERFLOW, 1, "0123456789abcdef0123456789abcdef", "wss://chat.sockets.stackexchange.com/events/1/37516a6eb3464228bf48a33088b3c247", 1417023460)

			.requestPost("https://chat.stackoverflow.com/user/info",
				"ids", "13379,4258326",
				"roomId", "1"
			)
			.responseOk(ResponseSamples.userInfo()
				.user(13379, "Michael", "!https://i.stack.imgur.com/awces.jpg?s=128\\u0026g=1", 23145, false, true, 1501724997, 1501770855)
				.user(4258326, "OakBot", "f5166c4602a6deaf2accdc98c89e9b82", 408, false, false, 1501769545, 1501771253)
			.build())
		.build();
		//@formatter:on

		var wsContainer = mock(WebSocketContainer.class);

		var chatClient = new ChatClient(Site.STACKOVERFLOW, httpClient, wsContainer);
		chatClient.login("email", "password");
		var room = chatClient.joinRoom(1);

		var users = room.getUserInfo(List.of(13379, 4258326));

		var it = users.iterator();

		UserInfo user = it.next();
		assertEquals(13379, user.getUserId());
		assertEquals("Michael", user.getUsername());
		assertEquals("https://i.stack.imgur.com/awces.jpg?s=128&g=1", user.getProfilePicture());
		assertEquals(23145, user.getReputation());
		assertFalse(user.isModerator());
		assertTrue(user.isOwner());
		assertEquals(timestamp(1501724997), user.getLastPost());
		assertEquals(timestamp(1501770855), user.getLastSeen());

		user = it.next();
		assertEquals(4258326, user.getUserId());
		assertEquals("OakBot", user.getUsername());
		assertEquals("https://www.gravatar.com/avatar/f5166c4602a6deaf2accdc98c89e9b82?d=identicon&s=128", user.getProfilePicture());
		assertEquals(408, user.getReputation());
		assertFalse(user.isModerator());
		assertFalse(user.isOwner());
		assertEquals(timestamp(1501769545), user.getLastPost());
		assertEquals(timestamp(1501771253), user.getLastSeen());

		assertFalse(it.hasNext());

		verifyNumberOfRequestsSent(httpClient, 7);
	}

	@Test
	void getUserInfo_bad_responses() throws Exception {
		//@formatter:off
		var httpClient = new MockHttpClientBuilder()
			.login(Site.STACKOVERFLOW, "0123456789abcdef0123456789abcdef", "email", "password", true, "Username", 12345)
			.joinRoom(Site.STACKOVERFLOW, 1, "0123456789abcdef0123456789abcdef", "wss://chat.sockets.stackexchange.com/events/1/37516a6eb3464228bf48a33088b3c247", 1417023460)

			.requestPost("https://chat.stackoverflow.com/user/info",
				"ids", "13379",
				"roomId", "1"
			)
			.responseOk("not JSON")
			
			.requestPost("https://chat.stackoverflow.com/user/info",
				"ids", "13379",
				"roomId", "1"
			)
			.responseOk("{}")
			
			.requestPost("https://chat.stackoverflow.com/user/info",
				"ids", "13379",
				"roomId", "1"
			)
			.responseOk("{ \"users\":{} }")
			
			.requestPost("https://chat.stackoverflow.com/user/info",
				"ids", "13379",
				"roomId", "1"
			)
			.responseOk("{ \"users\":[ {} ] }")
		.build();
		//@formatter:on

		var wsContainer = mock(WebSocketContainer.class);

		var chatClient = new ChatClient(Site.STACKOVERFLOW, httpClient, wsContainer);
		chatClient.login("email", "password");
		var room = chatClient.joinRoom(1);

		try {
			room.getUserInfo(List.of(13379));
			fail();
		} catch (IOException expected) {
		}

		assertEquals(0, room.getUserInfo(List.of(13379)).size());
		assertEquals(0, room.getUserInfo(List.of(13379)).size());

		var users = room.getUserInfo(List.of(13379));
		assertEquals(1, users.size());
		UserInfo user = users.get(0);
		assertEquals(0, user.getUserId());
		assertNull(user.getUsername());
		assertNull(user.getProfilePicture());
		assertEquals(0, user.getReputation());
		assertFalse(user.isModerator());
		assertFalse(user.isOwner());
		assertNull(user.getLastPost());
		assertNull(user.getLastSeen());

		verifyNumberOfRequestsSent(httpClient, 10);
	}

	@Test
	void getRoomInfo() throws Exception {
		//@formatter:off
		var httpClient = new MockHttpClientBuilder()
			.login(Site.STACKOVERFLOW, "0123456789abcdef0123456789abcdef", "email", "password", true, "Username", 12345)
			.joinRoom(Site.STACKOVERFLOW, 1, "0123456789abcdef0123456789abcdef", "wss://chat.sockets.stackexchange.com/events/1/37516a6eb3464228bf48a33088b3c247", 1417023460)

			.requestGet("https://chat.stackoverflow.com/rooms/thumbs/1")
			.responseOk(ResponseSamples.roomInfo(
				1,
				"Sandbox",
				"Where you can play with regular chat features (except flagging) without upsetting anyone",
				true,
				List.of("one", "two")
			))
		.build();
		//@formatter:on

		var wsContainer = mock(WebSocketContainer.class);

		var chatClient = new ChatClient(Site.STACKOVERFLOW, httpClient, wsContainer);
		chatClient.login("email", "password");
		var room = chatClient.joinRoom(1);

		var info = room.getRoomInfo();
		assertEquals("Where you can play with regular chat features (except flagging) without upsetting anyone", info.getDescription());
		assertEquals(1, info.getId());
		assertEquals("Sandbox", info.getName());
		assertEquals(List.of("one", "two"), info.getTags());

		verifyNumberOfRequestsSent(httpClient, 7);
	}

	@Test
	void getRoomInfo_bad_responses() throws Exception {
		//@formatter:off
		var httpClient = new MockHttpClientBuilder()
			.login(Site.STACKOVERFLOW, "0123456789abcdef0123456789abcdef", "email", "password", true, "Username", 12345)
			.joinRoom(Site.STACKOVERFLOW, 1, "0123456789abcdef0123456789abcdef", "wss://chat.sockets.stackexchange.com/events/1/37516a6eb3464228bf48a33088b3c247", 1417023460)
			
			.requestGet("https://chat.stackoverflow.com/rooms/thumbs/1")
			.response(404, "")

			.requestGet("https://chat.stackoverflow.com/rooms/thumbs/1")
			.responseOk("not JSON")
			
			.requestGet("https://chat.stackoverflow.com/rooms/thumbs/1")
			.responseOk("[]")
			
			.requestGet("https://chat.stackoverflow.com/rooms/thumbs/1")
			.responseOk("{}")
			
			.requestGet("https://chat.stackoverflow.com/rooms/thumbs/1")
			.responseOk("{\"tags\":\"garbage\"}")
		.build();
		//@formatter:on

		var wsContainer = mock(WebSocketContainer.class);

		var chatClient = new ChatClient(Site.STACKOVERFLOW, httpClient, wsContainer);
		chatClient.login("email", "password");
		var room = chatClient.joinRoom(1);

		try {
			room.getRoomInfo();
			fail();
		} catch (IOException expected) {
		}

		try {
			room.getRoomInfo();
			fail();
		} catch (IOException expected) {
		}

		var info = room.getRoomInfo();
		assertNull(info.getDescription());
		assertEquals(0, info.getId());
		assertNull(info.getName());
		assertEquals(List.of(), info.getTags());

		info = room.getRoomInfo();
		assertNull(info.getDescription());
		assertEquals(0, info.getId());
		assertNull(info.getName());
		assertEquals(List.of(), info.getTags());

		info = room.getRoomInfo();
		assertNull(info.getDescription());
		assertEquals(0, info.getId());
		assertNull(info.getName());
		assertEquals(List.of(), info.getTags());

		verifyNumberOfRequestsSent(httpClient, 11);
	}

	/**
	 * @see ChatClientTest#leave_room
	 */
	@Test
	void leave() throws Exception {
		//empty
	}

	/**
	 * Verifies how many HTTP requests were passed to the HTTP client.
	 * @param httpClient the HTTP client
	 * @param requests the expected number of requests
	 */
	private static void verifyNumberOfRequestsSent(CloseableHttpClient httpClient, int requests) throws IOException {
		verify(httpClient, times(requests)).execute(any(HttpUriRequest.class));
	}

	/**
	 * Represents a mock web socket server.
	 * @author Michael Angstadt
	 */
	private static class MockWebSocketServer {
		private Whole<String> messageHandler;

		/**
		 * @param container the mock web socket container. This object is shared
		 * amongst all the {@link MockWebSocketServer} instances (each instance
		 * is for a single chat room).
		 * @param url the expected URL that the room will use to connect to the
		 * web socket
		 */
		@SuppressWarnings("unchecked")
		public MockWebSocketServer(WebSocketContainer container, String url) throws Exception {
			var session = mock(Session.class);

			doAnswer(invocation -> {
				messageHandler = (Whole<String>) invocation.getArguments()[1];
				return null;
			}).when(session).addMessageHandler(eq(String.class), any(Whole.class));

			when(container.connectToServer(any(Endpoint.class), any(ClientEndpointConfig.class), eq(new URI(url)))).then(invocation -> {
				var endpoint = (Endpoint) invocation.getArguments()[0];
				endpoint.onOpen(session, mock(EndpointConfig.class));
				return session;
			});
		}

		/**
		 * Sends a web socket message out from the server.
		 * @param message the message to send
		 */
		public void send(String message) {
			messageHandler.onMessage(message);
		}
	}

	/**
	 * Converts a timestamp to a {@link LocalDateTime} instance.
	 * @param ts the timestamp (seconds since epoch)
	 * @return the {@link LocalDateTime} instance
	 */
	private static LocalDateTime timestamp(long ts) {
		var instant = Instant.ofEpochSecond(ts);
		return LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
	}

	/**
	 * This conversion is needed for the unit test to run on other machines. I
	 * think it has something to do with the default timezone.
	 * @param messageTs the timestamp of the chat message
	 * @return the value that will be put in the web socket URL
	 */
	private static long webSocketTimestamp(long messageTs) {
		var dt = timestamp(messageTs);
		return dt.toEpochSecond(ZoneOffset.UTC);
	}
}
