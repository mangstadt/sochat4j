package com.github.mangstadt.sochat4j;

import static java.util.function.Function.identity;

import java.io.EOFException;
import java.io.IOException;
import java.net.ProtocolException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URISyntaxException;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Timer;
import java.util.TimerTask;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.http.client.utils.URIBuilder;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.github.mangstadt.sochat4j.event.Event;
import com.github.mangstadt.sochat4j.event.InvitationEvent;
import com.github.mangstadt.sochat4j.event.MessageDeletedEvent;
import com.github.mangstadt.sochat4j.event.MessageEditedEvent;
import com.github.mangstadt.sochat4j.event.MessagePostedEvent;
import com.github.mangstadt.sochat4j.event.MessageStarredEvent;
import com.github.mangstadt.sochat4j.event.MessagesMovedEvent;
import com.github.mangstadt.sochat4j.event.UserEnteredEvent;
import com.github.mangstadt.sochat4j.event.UserLeftEvent;
import com.github.mangstadt.sochat4j.util.Http;
import com.github.mangstadt.sochat4j.util.Http.Response;
import com.github.mangstadt.sochat4j.util.JsonUtils;
import com.github.mangstadt.sochat4j.util.Sleeper;
import com.github.mangstadt.sochat4j.util.WebSocketClient;

import okhttp3.HttpUrl;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * Represents the connection to a room the user has joined. Use the
 * {@link ChatClient#joinRoom} method to properly create an instance of this
 * class.
 * @author Michael Angstadt
 */
public class Room implements IRoom {
	private static final Logger logger = LoggerFactory.getLogger(Room.class);
	private static final int MAX_MESSAGE_LENGTH = 500;

	private static final int CLOSE_NORMAL = 1000;
	private static final int CLOSE_GOING_AWAY = 1001;

	private final int roomId;
	private final String fkey;
	private final boolean canPost;
	private final Http http;
	private final ChatClient chatClient;
	private WebSocket webSocket;
	private WebSocketListenerImpl webSocketListener = new WebSocketListenerImpl();

	private final WebSocketClient webSocketClient;
	private final Duration webSocketRefreshFrequency;
	private final Timer websocketReconnectTimer;

	//@formatter:off
	private final Map<Class<? extends Event>, List<Consumer<Event>>> listeners = Map.of(
		Event.class, new ArrayList<>(),
		InvitationEvent.class, new ArrayList<>(),
		MessageDeletedEvent.class, new ArrayList<>(),
		MessageEditedEvent.class, new ArrayList<>(),
		MessagePostedEvent.class, new ArrayList<>(),
		MessagesMovedEvent.class, new ArrayList<>(),
		MessageStarredEvent.class, new ArrayList<>(),
		UserEnteredEvent.class, new ArrayList<>(),
		UserLeftEvent.class, new ArrayList<>()
	);
	//@formatter:on

	/**
	 * Creates a connection to a specific chat room. This constructor is meant
	 * to be called by {@link ChatClient#joinRoom}.
	 * @param roomId the room ID
	 * @param http the HTTP client
	 * @param webSocketClient the websocket client
	 * @param webSocketRefreshFrequency how often the room's websocket
	 * connection is reset to address disconnects that randomly occur
	 * @param chatClient the {@link ChatClient} object that created this
	 * connection
	 * @throws IOException if there's a network problem
	 * @throws RoomNotFoundException if the room does not exist or the user does
	 * not have permission to view the room
	 * @throws PrivateRoomException if the room can't be joined because it is
	 * private
	 */
	Room(int roomId, Http http, WebSocketClient webSocketClient, Duration webSocketRefreshFrequency, ChatClient chatClient) throws IOException, RoomNotFoundException, PrivateRoomException {
		this.roomId = roomId;
		this.http = http;
		this.webSocketClient = webSocketClient;
		this.webSocketRefreshFrequency = webSocketRefreshFrequency;
		this.chatClient = chatClient;
		websocketReconnectTimer = new Timer(true);

		//@formatter:off
		var url = baseUrl()
			.addPathSegments("rooms/" + roomId)
		.toString();
		//@formatter:on

		var response = http.get(url);
		var dom = response.getBodyAsHtml();

		if (roomDoesNotExist(response)) {
			throw new RoomNotFoundException(roomId);
		}

		if (roomIsPrivate(dom)) {
			throw new PrivateRoomException(roomId);
		}

		fkey = chatClient.parseFKey(dom);
		if (fkey == null) {
			throw new IOException("Could not get fkey of room " + roomId + ".");
		}

		/*
		 * The textbox for sending messages won't be there if the user can't
		 * post to the room.
		 */
		canPost = (dom.getElementById("input") != null);

		connectToWebSocket();
		if (webSocketRefreshFrequency != null) {
			createWebSocketRefreshTimer();
		}
	}

	private boolean roomDoesNotExist(Http.Response response) {
		/*
		 * A 404 response is also returned if the room is inactive, and the
		 * user does not have enough reputation/privileges to see inactive
		 * rooms.
		 */
		return (response.getStatusCode() == 404);
	}

	private boolean roomIsPrivate(Document dom) {
		//@formatter:off
		return dom.getElementsByTag("h2").stream()
			.map(Element::text)
		.anyMatch("Private Room"::equalsIgnoreCase);
		//@formatter:on
	}

	/**
	 * Connects to the room's websocket.
	 * @throws IOException if there's a problem getting the websocket URI. Any
	 * errors connecting to the websocket are handled by the WebSocketListener
	 * object.
	 */
	private void connectToWebSocket() throws IOException {
		var wsUri = getWebSocketUri();
		var origin = baseUrl().toString();

		logger.atInfo().log(() -> "[room=" + roomId + "]: Connecting to websocket: " + wsUri);

		webSocket = webSocketClient.connect(wsUri, origin, webSocketListener);
	}

	/**
	 * Creates a timer that recreates the websocket connection periodically.
	 * This is an attempt to fix the issue where every couple days, the bot
	 * will stop responding to messages due to the websocket connection going
	 * bad.
	 */
	private void createWebSocketRefreshTimer() {
		var period = webSocketRefreshFrequency.toMillis();
		websocketReconnectTimer.schedule(new TimerTask() {
			@Override
			public void run() {
				synchronized (Room.this) {
					logger.atInfo().log(() -> "[room=" + roomId + "]: Recreating websocket connection.");

					webSocketListener.gracefulClosure = true;
					webSocket.close(CLOSE_NORMAL, "");

					/*
					 * The "webSocket.close" method call is probably
					 * asynchronous. The websocket should not be reconnected to
					 * until it has been closed. So, give it a few seconds to
					 * close.
					 */
					Sleeper.sleep(Duration.ofSeconds(5));
					webSocket.cancel();
					webSocketListener.gracefulClosure = false;

					try {
						connectToWebSocket();
					} catch (IOException e) {
						logger.atError().setCause(e).log(() -> "[room=" + roomId + "]: Problem getting websocket URI from chat room. Leaving room.");
						leave();
					}
				}
			}
		}, period, period);
	}

	@Override
	public int getRoomId() {
		return roomId;
	}

	@Override
	public String getFkey() {
		return fkey;
	}

	@Override
	public boolean canPost() {
		return canPost;
	}

	private String getWebSocketUri() throws IOException {
		//@formatter:off
		var url = baseUrl()
			.addPathSegments("ws-auth")
		.toString();
		
		var response = http.post(url,
			"roomid", roomId,
			"fkey", fkey
		);
		//@formatter:on

		var wsUrlNode = response.getBodyAsJson().get("url");
		if (wsUrlNode == null) {
			throw new IOException("Websocket URL missing from response.");
		}

		var wsUrl = wsUrlNode.asText();

		var messages = getMessages(1);
		var latest = messages.isEmpty() ? null : messages.get(0);
		var time = (latest == null) ? 0 : latest.timestamp().toEpochSecond(ZoneOffset.UTC);

		/*
		 * HttpUrl.parse() returns null because the websocket URI has a
		 * non-HTTP scheme.
		 */
		try {
			//@formatter:off
			return new URIBuilder(wsUrl)
				.setParameter("l", Long.toString(time))
			.toString();
			//@formatter:on
		} catch (URISyntaxException e) {
			throw new IOException("Websocket URL is not a valid URI: " + wsUrl, e);
		}
	}

	/**
	 * Handles websocket messages.
	 * @param json the content of the message (formatted as a JSON object)
	 */
	private void handleWebSocketMessage(String json) {
		JsonNode node;
		try {
			node = JsonUtils.parse(json);
		} catch (JsonProcessingException e) {
			logger.atError().setCause(e).log(() -> "[room " + roomId + "]: Problem parsing JSON from websocket:\n" + json);
			return;
		}

		logger.atDebug().log(() -> "[room " + roomId + "]: Received message:\n" + JsonUtils.prettyPrint(node) + "\n");

		var roomNode = node.get("r" + roomId);
		if (roomNode == null) {
			return;
		}

		var eventsNode = roomNode.get("e");
		if (eventsNode == null || !eventsNode.isArray()) {
			return;
		}

		var eventsByType = groupEventsByType(eventsNode);

		var eventsToPublish = new ArrayList<Event>();

		eventsToPublish.addAll(WebSocketEventParsers.reply(eventsByType));
		eventsToPublish.addAll(WebSocketEventParsers.mention(eventsByType));

		var movedOut = WebSocketEventParsers.messagesMovedOut(eventsByType);
		if (movedOut != null) {
			eventsToPublish.add(movedOut);
		}

		var movedIn = WebSocketEventParsers.messagesMovedIn(eventsByType);
		if (movedIn != null) {
			eventsToPublish.add(movedIn);
		}

		//@formatter:off
		eventsByType.values().stream()
			.flatMap(List::stream)
			.map(this::parseEvent)
			.filter(Objects::nonNull)

			/*
			 * Sort by event ID to ensure they are processed in the same order
			 * they were received.
			 */
			.sorted(Comparator.comparing(Event::getEventId))
		.forEach(eventsToPublish::add);
		//@formatter:on

		var genericListeners = listeners.get(Event.class);
		synchronized (genericListeners) {
			genericListeners.forEach(listener -> eventsToPublish.forEach(listener::accept));
		}

		eventsToPublish.forEach(event -> {
			var eventListeners = listeners.get(event.getClass());
			synchronized (eventListeners) {
				eventListeners.forEach(listener -> listener.accept(event));
			}
		});
	}

	private Map<WebSocketEventType, List<JsonNode>> groupEventsByType(JsonNode eventsNode) {
		//@formatter:off
		Map<WebSocketEventType, List<JsonNode>> eventsByType = Arrays.stream(WebSocketEventType.values())
		.collect(Collectors.toMap(identity(), type -> new ArrayList<>()));
		//@formatter:on

		for (var eventNode : eventsNode) {
			var eventTypeNode = eventNode.get("event_type");
			if (eventTypeNode == null || !eventTypeNode.canConvertToInt()) {
				logger.atWarn().log(() -> "[room " + roomId + "]: Ignoring JSON object that does not have a valid \"event_type\" field:\n" + JsonUtils.prettyPrint(eventNode) + "\n");
				continue;
			}

			var eventType = WebSocketEventType.get(eventTypeNode.asInt());
			if (eventType == null) {
				logger.atWarn().log(() -> "[room " + roomId + "]: Ignoring event with unknown \"event_type\":\n" + JsonUtils.prettyPrint(eventNode) + "\n");
				continue;
			}

			eventsByType.get(eventType).add(eventNode);
		}

		return eventsByType;
	}

	private Event parseEvent(JsonNode node) {
		var id = node.get("event_type").asInt();
		var eventType = WebSocketEventType.get(id);

		return switch (eventType) {
		case MESSAGE_POSTED -> WebSocketEventParsers.messagePosted(node);
		case MESSAGE_EDITED -> WebSocketEventParsers.messageEdited(node);
		case INVITATION -> WebSocketEventParsers.invitation(node);
		case USER_ENTERED -> WebSocketEventParsers.userEntered(node);
		case USER_LEFT -> WebSocketEventParsers.userLeft(node);
		case MESSAGE_STARRED -> WebSocketEventParsers.messageStarred(node);
		case MESSAGE_DELETED -> WebSocketEventParsers.messageDeleted(node);
		default -> {
			logger.atWarn().log(() -> "[room " + roomId + "]: Ignoring event with unknown \"event_type\":\n" + JsonUtils.prettyPrint(node) + "\n");
			yield null;
		}
		};
	}

	@Override
	@SuppressWarnings("unchecked")
	public <T extends Event> void addEventListener(Class<T> clazz, Consumer<T> listener) {
		var eventListeners = listeners.get(clazz);
		synchronized (eventListeners) {
			eventListeners.add((Consumer<Event>) listener);
		}
	}

	@Override
	public void addEventListener(Consumer<Event> listener) {
		addEventListener(Event.class, listener);
	}

	@Override
	public List<Long> sendMessage(String message, long parentId, SplitStrategy splitStrategy) throws IOException {
		if (!canPost) {
			throw new RoomPermissionException(roomId);
		}

		List<String> parts;
		if (message.contains("\n")) {
			//messages with newlines have no length limit
			parts = List.of(message);
		} else {
			parts = splitStrategy.split(message, MAX_MESSAGE_LENGTH);
		}

		//@formatter:off
		var url = baseUrl()
			.addPathSegments("chats/" + roomId + "/messages/new")
		.toString();
		//@formatter:on

		var firstPart = true;
		var messageIds = new ArrayList<Long>(parts.size());
		for (var part : parts) {
			//@formatter:off
			var parameters = (parentId > 0 && firstPart) ?
				new Object[] { "text", part, "fkey", fkey, "parentId", parentId } :
				new Object[] { "text", part, "fkey", fkey };
			//@formatter:on

			firstPart = false;

			var response = http.post(url, new RateLimit409Handler(), parameters);

			if (response.getStatusCode() == 404) {
				/*
				 * We already checked to make sure the room exists. So, if a 404
				 * response is returned when trying to send a message, it likely
				 * means that the bot's permission to post messages has been
				 * revoked for one reason or another.
				 * 
				 * Examples of what the response body will be set to with 404
				 * responses:
				 * "The room does not exist, or you do not have permission"
				 * "Your account is blocked, ending in 5 hours"
				 * "Your account is blocked, ending on Oct 2 at 1:37"
				 * "The room is in timeout, ending in 58 seconds"
				 */
				throw notFound(response, "post a message");
			}

			var body = response.getBodyAsJson();
			var idNode = body.get("id");
			var id = (idNode == null) ? 0 : idNode.asLong();
			messageIds.add(id);
		}

		return messageIds;
	}

	@Override
	public List<ChatMessage> getMessages(int count) throws IOException {
		//@formatter:off
		var url = baseUrl()
			.addPathSegments("chats/" + roomId + "/events")
		.toString();

		var response = http.post(url,
			"mode", "messages",
			"msgCount", count,
			"fkey", fkey
		);
		//@formatter:on

		if (response.getStatusCode() == 404) {
			throw notFound(response, "get messages");
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

	@Override
	public void deleteMessage(long messageId) throws IOException {
		//@formatter:off
		var url = baseUrl()
			.addPathSegments("messages/" + messageId + "/delete")
		.toString();

		var response = http.post(url,
			"fkey", fkey
		);
		//@formatter:on

		var statusCode = response.getStatusCode();
		if (statusCode == 302) {
			throw new IOException("Message ID " + messageId + " was never assigned to a message.");
		}

		var body = response.getBody();
		switch (body) {
		case "\"ok\"", "\"This message has already been deleted.\"":
			//message successfully deleted
			break;
		case "\"It is too late to delete this message\"":
			throw new IOException("Message " + messageId + " cannot be deleted because it is too old.");
		case "\"You can only delete your own messages\"":
			throw new IOException("Message " + messageId + " cannot be deleted because it was posted by somebody else.");
		default:
			logger.atWarn().log(() -> "Unexpected response when attempting to delete message [room=" + roomId + ", id=" + messageId + "]: " + body);
			break;
		}
	}

	@Override
	public void editMessage(long messageId, long parentId, String updatedMessage) throws IOException {
		//@formatter:off
		var url = baseUrl()
			.addPathSegments("messages/" + messageId)
		.toString();

		var parameters = (parentId > 0) ?
			new Object[] { "text", updatedMessage, "fkey", fkey, "parentId", parentId } :
			new Object[] { "text", updatedMessage, "fkey", fkey };
		//@formatter:on

		var response = http.post(url, new RateLimit409Handler(), parameters);

		var statusCode = response.getStatusCode();
		if (statusCode == 302) {
			throw new IOException("Message ID " + messageId + " was never assigned to a message.");
		}

		var body = response.getBody();
		switch (body) {
		case "\"ok\"":
			//message successfully edited
			break;
		case "\"This message has already been deleted and cannot be edited\"":
			throw new IOException("Message " + messageId + " cannot be edited because it was deleted.");
		case "\"It is too late to edit this message\"":
			throw new IOException("Message " + messageId + " cannot be edited because it is too old.");
		case "\"You can only edit your own messages\"":
			throw new IOException("Message " + messageId + " cannot be edited because it was posted by somebody else.");
		default:
			logger.atWarn().log(() -> "Unexpected response when attempting to edit message [room=" + roomId + ", id=" + messageId + "]: " + body);
			break;
		}
	}

	@Override
	public List<UserInfo> getUserInfo(List<Integer> userIds) throws IOException {
		return chatClient.getUserInfo(roomId, userIds);
	}

	@Override
	public List<PingableUser> getPingableUsers() throws IOException {
		//@formatter:off
		var url = baseUrl()
			.addPathSegments("rooms/pingable/" + roomId)
		.toString();
		//@formatter:on

		var response = http.get(url);

		if (response.getStatusCode() == 404) {
			throw notFound(response, "get pingable users");
		}

		var root = response.getBodyAsJson();

		//@formatter:off
		return JsonUtils.streamArray(root)
			.filter(JsonNode::isArray)
			.filter(node -> node.size() >= 4)
			.map(node -> {
				var userId = node.get(0).asInt();
				var username = node.get(1).asText();
				var lastPost = WebSocketEventParsers.timestamp(node.get(3).asLong());
				return new PingableUser(roomId, userId, username, lastPost);
			})
		.toList();
		//@formatter:on
	}

	@Override
	public RoomInfo getRoomInfo() throws IOException {
		//@formatter:off
		var url = baseUrl()
			.addPathSegments("rooms/thumbs/" + roomId)
		.toString();
		//@formatter:on

		var response = http.get(url);

		if (response.getStatusCode() == 404) {
			throw notFound(response, "get room info");
		}

		var root = response.getBodyAsJson();

		var node = root.get("id");
		int id = (node == null) ? 0 : node.asInt();

		node = root.get("name");
		String name = (node == null) ? null : node.asText();

		node = root.get("description");
		String description = (node == null) ? null : node.asText();

		node = root.get("tags");
		List<String> tags;
		if (node == null) {
			tags = List.of();
		} else {
			var dom = Jsoup.parse(node.asText());

			//@formatter:off
			tags = dom.getElementsByTag("a").stream()
				.map(Element::html)
			.toList();
			//@formatter:on
		}

		return new RoomInfo(id, name, description, tags);
	}

	@Override
	public void leave() {
		chatClient.removeRoom(this);

		try {
			//@formatter:off
			var url = baseUrl()
				.addPathSegments("chats/leave/" + roomId)
			.toString();

			http.post(url,
				"quiet", "true", //setting this parameter to "false" results in an error
				"fkey", fkey
			);
			//@formatter:on
		} catch (Exception e) {
			logger.atError().setCause(e).log(() -> "[room=" + roomId + "]: Problem leaving room.");
		}

		try {
			close();
		} catch (IOException e) {
			logger.atError().setCause(e).log(() -> "[room=" + roomId + "]: Problem closing websocket session.");
		}
	}

	private IOException notFound(Response response, String action) {
		return new IOException("[roomId=" + roomId + "]: 404 response received when trying to " + action + ": " + response.getBody());
	}

	/**
	 * Gets a builder for the base URI of this chat site.
	 * @return the base URI (e.g. "https://chat.stackoverflow.com")
	 */
	private HttpUrl.Builder baseUrl() {
		//@formatter:off
		return new HttpUrl.Builder()
			.scheme("https")
			.host(chatClient.getSite().getChatDomain());
		//@formatter:on
	}

	@Override
	public void close() throws IOException {
		synchronized (this) {
			websocketReconnectTimer.cancel();
			webSocketListener.gracefulClosure = true;
			webSocket.close(CLOSE_GOING_AWAY, "");
		}
	}

	/**
	 * An HTTP 409 response means that the bot is sending messages too quickly.
	 * The response body contains the number of seconds the bot must wait before
	 * it can post another message.
	 * @author Michael Angstadt
	 */
	private static class RateLimit409Handler implements Http.RateLimitHandler {
		private static final Pattern response409Regex = Pattern.compile("\\d+");

		@Override
		public int getMaxAttempts() {
			return 5;
		}

		@Override
		public boolean isRateLimited(Response response) {
			return (response.getStatusCode() == 409);
		}

		@Override
		public Duration getWaitTime(Response response) {
			var body = response.getBody();
			var m = response409Regex.matcher(body);

			var seconds = m.find() ? Integer.parseInt(m.group(0)) : 5;
			return Duration.ofSeconds(seconds);
		}
	}

	/**
	 * This implementation attempts to reconnect to the websocket when
	 * unexpected failures occur.
	 * @see "https://square.github.io/okhttp/5.x/okhttp/okhttp3/-web-socket-listener/index.html"
	 */
	private class WebSocketListenerImpl extends WebSocketListener {
		private final int maxReconnectionAttempts = 5;
		private final Collection<Class<?>> recoverableFailures = List.of(EOFException.class, SocketException.class, SocketTimeoutException.class, ProtocolException.class);
		private boolean firstConnection = true;
		private int reconnectionAttempts = 0;
		private boolean gracefulClosure = false;

		@Override
		public void onOpen(WebSocket webSocket, okhttp3.Response response) {
			reconnectionAttempts = 0;
			firstConnection = false;
		}

		@Override
		public void onMessage(WebSocket webSocket, String text) {
			handleWebSocketMessage(text);
		}

		@Override
		public void onFailure(WebSocket webSocket, Throwable t, okhttp3.Response response) {
			if (gracefulClosure) {
				gracefulClosure = false;
				return;
			}

			if (firstConnection) {
				logger.atError().setCause(t).log(() -> "[room=" + roomId + "]: Error connecting to websocket. Leaving room.");
				leave();
			} else if (isRecoverableFailure(t)) {
				/*
				 * If certain exceptions are thrown, attempt to reconnect.
				 * 
				 * The websocket will sometimes abruptly disconnect for
				 * some unknown reason. This has been a long-running issue
				 * that began occurring more frequently in early June 2024.
				 * 
				 * See: https://chat.stackoverflow.com/transcript/message/
				 * 57407855#57407855
				 */
				logger.atError().setCause(t).log(() -> "[room=" + roomId + "]: Websocket abruptly disconnected.");
				attemptToReconnect(webSocket);
			} else {
				logger.atError().setCause(t).log(() -> "[room=" + roomId + "]: Unrecoverable problem with websocket. Leaving room.");
				leave();
			}
		}

		private boolean isRecoverableFailure(Throwable t) {
			return recoverableFailures.stream().anyMatch(e -> e.isInstance(t));
		}

		/**
		 * Invoked when both peers have indicated that no more messages will
		 * be transmitted and the connection has been successfully released.
		 * No further calls to this listener will be made.
		 */
		@Override
		public void onClosed(WebSocket webSocket, int code, String reason) {
			if (gracefulClosure) {
				gracefulClosure = false;
				return;
			}

			logger.atError().log(() -> "[room=" + roomId + "]: Websocket closed by server. Reason=\"" + reason + "\" Code=" + code);
			attemptToReconnect(webSocket);
		}

		/**
		 * Invoked when the remote peer has indicated that no more incoming
		 * messages will be transmitted.
		 */
		@Override
		public void onClosing(WebSocket webSocket, int code, String reason) {
			logger.atError().log(() -> "[room=" + roomId + "]: Websocket is being closed by the server. Reason=\"" + reason + "\" Code=" + code);
		}

		private void attemptToReconnect(WebSocket webSocket) {
			if (reconnectionAttempts >= maxReconnectionAttempts) {
				logger.atError().log(() -> "[room=" + roomId + "]: Unable to reconnect to websocket after " + reconnectionAttempts + " attempts. Leaving room.");
				leave();
				return;
			}

			synchronized (Room.this) {
				/*
				 * Immediately and violently release resources held by this web
				 * socket, discarding any enqueued messages. This does nothing
				 * if the web socket has already been closed or canceled.
				 */
				webSocket.cancel();

				var sleepSeconds = (reconnectionAttempts + 1) * 10;
				logger.atError().log(() -> "[room=" + roomId + "]: Attempting to reconnect websocket in " + sleepSeconds + " seconds.");
				Sleeper.sleep(Duration.ofSeconds(sleepSeconds));

				reconnectionAttempts++;
				try {
					connectToWebSocket();
				} catch (IOException e) {
					logger.atError().setCause(e).log(() -> "[room=" + roomId + "]: Problem getting websocket URI from chat room. Leaving room.");
					leave();
				}
			}
		}
	}
}
