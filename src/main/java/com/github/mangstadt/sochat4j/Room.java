package com.github.mangstadt.sochat4j;

import static java.util.function.Function.identity;

import java.io.EOFException;
import java.io.IOException;
import java.net.SocketException;
import java.net.URISyntaxException;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Timer;
import java.util.TimerTask;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.http.client.utils.URIBuilder;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

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
	private static final Logger logger = Logger.getLogger(Room.class.getName());
	private static final int MAX_MESSAGE_LENGTH = 500;

	private static final int MAX_WEBSOCKET_REFRESH_ATTEMPTS = 3;
	private static final Duration PAUSE_BETWEEN_WEBSOCKET_REFRESH_ATTEMPTS = Duration.ofSeconds(10);

	private static final int CLOSE_NORMAL = 1000;
	private static final int CLOSE_GOING_AWAY = 1001;

	private final int roomId;
	private final String fkey;
	private final boolean canPost;
	private final Http http;
	private final ChatClient chatClient;
	private WebSocket webSocket;

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
	 * @param webSocketClient the web socket client
	 * @param webSocketRefreshFrequency how often the room's web socket
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

	private void connectToWebSocket() throws IOException {
		var wsUri = getWebSocketUri();
		var origin = baseUrl().toString();

		logger.info(() -> "Connecting to web socket [room=" + roomId + "]: " + wsUri);

		webSocket = webSocketClient.connect(wsUri, origin, new WebSocketListener() {
			@Override
			public void onMessage(WebSocket webSocket, String text) {
				handleWebSocketMessage(text);
			}

			@Override
			public void onFailure(WebSocket webSocket, Throwable t, okhttp3.Response response) {
				if (t instanceof EOFException || t instanceof SocketException) {
					/*
					 * If any of these exceptions are thrown, reconnect to the
					 * web socket.
					 * 
					 * The web socket will sometimes abruptly disconnect for
					 * some unknown reason. This has been a long-running issue
					 * that began occurring more frequently in early June 2024.
					 * 
					 * See: https://chat.stackoverflow.com/transcript/message/
					 * 57407855#57407855
					 */
					logger.log(Level.WARNING, t, () -> "[room=" + roomId + "]: Web socket abrubtly disconnected. Attempting to reconnect.");
					try {
						synchronized (Room.this) {
							connectToWebSocket();
						}
					} catch (IOException e) {
						logger.log(Level.SEVERE, e, () -> "[room=" + roomId + "]: Problem reconnecting to web socket. Leaving room.");
						leave();
					}
				} else {
					logger.log(Level.SEVERE, t, () -> "[room=" + roomId + "]: Problem with web socket. Leaving room.");
					leave();
				}
			}

			@Override
			public void onClosed(WebSocket webSocket, int code, String reason) {
				//Invoked when both peers have indicated that no more messages will be transmitted and the connection has been successfully released.
				logger.log(Level.SEVERE, () -> "[room=" + roomId + "]: Web socket closed. Reason=" + reason + ". Code=" + code);
			}

			@Override
			public void onClosing(WebSocket webSocket, int code, String reason) {
				//Invoked when the remote peer has indicated that no more incoming messages will be transmitted.
				logger.log(Level.SEVERE, () -> "[room=" + roomId + "]: Web socket closed by server. Reason=" + reason + ". Code=" + code);
			}
		});

		logger.info(() -> "Web socket connection successful [room=" + roomId + "]: " + wsUri);
	}

	/**
	 * Creates a timer that recreates the websocket connection periodically.
	 * This is an attempt to fix the issue where every couple days, the bot
	 * will stop responding to messages.
	 */
	private void createWebSocketRefreshTimer() {
		var period = webSocketRefreshFrequency.toMillis();
		websocketReconnectTimer.schedule(new TimerTask() {
			@Override
			public void run() {
				synchronized (Room.this) {
					logger.info(() -> "[room=" + roomId + "]: Recreating websocket connection.");

					webSocket.close(CLOSE_NORMAL, "");

					var connected = false;
					var attempts = 0;
					while (!connected && attempts < MAX_WEBSOCKET_REFRESH_ATTEMPTS) {
						try {
							attempts++;
							connectToWebSocket();
							connected = true;
						} catch (IOException e) {
							logger.log(Level.SEVERE, e, () -> "[room=" + roomId + "]: Could not recreate websocket session. Trying again in " + PAUSE_BETWEEN_WEBSOCKET_REFRESH_ATTEMPTS.getSeconds() + " seconds.");
							Sleeper.sleep(PAUSE_BETWEEN_WEBSOCKET_REFRESH_ATTEMPTS);
						}
					}

					if (!connected) {
						logger.severe(() -> "[room=" + roomId + "]: Could not recreate websocket session after " + MAX_WEBSOCKET_REFRESH_ATTEMPTS + " tries. Leaving the room.");
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
			throw new IOException("Web socket URL missing from response.");
		}

		var wsUrl = wsUrlNode.asText();

		var messages = getMessages(1);
		var latest = messages.isEmpty() ? null : messages.get(0);
		var time = (latest == null) ? 0 : latest.getTimestamp().toEpochSecond(ZoneOffset.UTC);

		/*
		 * HttpUrl.parse() returns null because the web socket URI has a
		 * non-HTTP scheme.
		 */
		try {
			//@formatter:off
			return new URIBuilder(wsUrl)
				.setParameter("l", Long.toString(time))
			.toString();
			//@formatter:on
		} catch (URISyntaxException e) {
			throw new IOException("Web socket URL is not a valid URI: " + wsUrl, e);
		}
	}

	/**
	 * Handles web socket messages.
	 * @param json the content of the message (formatted as a JSON object)
	 */
	private void handleWebSocketMessage(String json) {
		JsonNode node;
		try {
			node = JsonUtils.parse(json);
		} catch (JsonProcessingException e) {
			logger.log(Level.SEVERE, e, () -> "[room " + roomId + "]: Problem parsing JSON from web socket:\n" + json);
			return;
		}

		logger.fine(() -> "[room " + roomId + "]: Received message:\n" + JsonUtils.prettyPrint(node) + "\n");

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
				logger.warning(() -> "[room " + roomId + "]: Ignoring JSON object that does not have a valid \"event_type\" field:\n" + JsonUtils.prettyPrint(eventNode) + "\n");
				continue;
			}

			var eventType = WebSocketEventType.get(eventTypeNode.asInt());
			if (eventType == null) {
				logger.warning(() -> "[room " + roomId + "]: Ignoring event with unknown \"event_type\":\n" + JsonUtils.prettyPrint(eventNode) + "\n");
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
			logger.warning(() -> "[room " + roomId + "]: Ignoring event with unknown \"event_type\":\n" + JsonUtils.prettyPrint(node) + "\n");
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
	public long sendMessage(String message) throws IOException {
		return sendMessage(message, SplitStrategy.NONE).get(0);
	}

	@Override
	public List<Long> sendMessage(String message, SplitStrategy splitStrategy) throws IOException {
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

		var messageIds = new ArrayList<Long>(parts.size());
		for (var part : parts) {
			//@formatter:off
			var response = http.post(url, new RateLimit409Handler(),
				"text", part,
				"fkey", fkey
			);
			//@formatter:on

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
			logger.warning(() -> "Unexpected response when attempting to delete message [room=" + roomId + ", id=" + messageId + "]: " + body);
			break;
		}
	}

	@Override
	public void editMessage(long messageId, String updatedMessage) throws IOException {
		//@formatter:off
		var url = baseUrl()
			.addPathSegments("messages/" + messageId)
		.toString();

		var response = http.post(url, new RateLimit409Handler(),
			"text", updatedMessage,
			"fkey", fkey
		);
		//@formatter:on

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
			logger.warning(() -> "Unexpected response when attempting to edit message [room=" + roomId + ", id=" + messageId + "]: " + body);
			break;
		}
	}

	@Override
	public List<UserInfo> getUserInfo(List<Integer> userIds) throws IOException {
		//@formatter:off
		var url = baseUrl()
			.addPathSegments("user/info")
		.toString();

		var idsCommaSeparated = userIds.stream()
			.map(Object::toString)
		.collect(Collectors.joining(","));

		var response = http.post(url,
			"ids", idsCommaSeparated,
			"roomId", roomId
		);
		//@formatter:on

		var usersNode = response.getBodyAsJson().get("users");
		if (usersNode == null || !usersNode.isArray()) {
			return List.of();
		}

		//@formatter:off
		return JsonUtils.streamArray(usersNode)
			.map(this::parseUserInfo)
		.toList();
		//@formatter:on
	}

	private UserInfo parseUserInfo(JsonNode userNode) {
		var builder = new UserInfo.Builder();

		builder.roomId(roomId);

		var node = userNode.get("id");
		if (node != null) {
			builder.userId(node.asInt());
		}

		node = userNode.get("name");
		if (node != null) {
			builder.username(node.asText());
		}

		node = userNode.get("email_hash");
		if (node != null) {
			String profilePicture;
			var emailHash = node.asText();
			if (emailHash.startsWith("!")) {
				profilePicture = emailHash.substring(1);
			} else {
				//@formatter:off
				profilePicture = new URIBuilder()
					.setScheme("https")
					.setHost("www.gravatar.com")
					.setPathSegments("avatar", emailHash)
					.setParameter("d", "identicon")
					.setParameter("s", "128")
				.toString();
				//@formatter:on
			}
			builder.profilePicture(profilePicture);
		}

		node = userNode.get("reputation");
		if (node != null) {
			builder.reputation(node.asInt());
		}

		node = userNode.get("is_moderator");
		if (node != null) {
			builder.moderator(node.asBoolean());
		}

		node = userNode.get("is_owner");
		if (node != null) {
			builder.owner(node.asBoolean());
		}

		node = userNode.get("last_post");
		if (node != null) {
			builder.lastPost(WebSocketEventParsers.timestamp(node.asLong()));
		}

		node = userNode.get("last_seen");
		if (node != null) {
			builder.lastSeen(WebSocketEventParsers.timestamp(node.asLong()));
		}

		return builder.build();
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
			logger.log(Level.SEVERE, e, () -> "[room=" + roomId + "]: Problem leaving room.");
		}

		try {
			close();
		} catch (IOException e) {
			logger.log(Level.SEVERE, e, () -> "[room=" + roomId + "]: Problem closing websocket session.");
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
}
