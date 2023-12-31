package com.github.mangstadt.sochat4j;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Timer;
import java.util.TimerTask;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

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

import jakarta.websocket.ClientEndpointConfig;
import jakarta.websocket.ClientEndpointConfig.Configurator;
import jakarta.websocket.DeploymentException;
import jakarta.websocket.Endpoint;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.Session;
import jakarta.websocket.WebSocketContainer;

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
	private static final Duration WEBSOCKET_REFRESH_FREQUENCY = Duration.ofHours(8);

	private final int roomId;
	private final String fkey;
	private final boolean canPost;
	private final Http http;
	private final ChatClient chatClient;

	private final WebSocketContainer webSocketContainer;
	private Session webSocketSession;
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
	 * @param webSocketContainer the object used to create the web socket
	 * connection
	 * @param chatClient the {@link ChatClient} object that created this
	 * connection
	 * @throws IOException if there's a network problem
	 * @throws RoomNotFoundException if the room does not exist or the user does
	 * not have permission to view the room
	 * @throws PrivateRoomException if the room can't be joined because it is
	 * private
	 */
	Room(int roomId, Http http, WebSocketContainer webSocketContainer, ChatClient chatClient) throws IOException, RoomNotFoundException, PrivateRoomException {
		this.roomId = roomId;
		this.http = http;
		this.webSocketContainer = webSocketContainer;
		this.chatClient = chatClient;
		websocketReconnectTimer = new Timer(true);

		//@formatter:off
		String url = baseUri()
			.setPathSegments("rooms", Integer.toString(roomId))
		.toString();
		//@formatter:on

		Http.Response response = http.get(url);
		Document dom = response.getBodyAsHtml();

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
		createWebSocketRefreshTimer();
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
		URI wsUri = getWebSocketUrl();

		//@formatter:off
		ClientEndpointConfig config = ClientEndpointConfig.Builder.create()
			.configurator(new Configurator() {
				@Override
				public void beforeRequest(Map<String, List<String>> headers) {
					String origin = baseUri().toString();
					headers.put("Origin", Arrays.asList(origin));
				}
			})
		.build();
		//@formatter:on

		logger.info(() -> "Connecting to web socket [room=" + roomId + "]: " + wsUri);

		try {
			webSocketSession = webSocketContainer.connectToServer(new Endpoint() {
				@Override
				public void onOpen(Session session, EndpointConfig config) {
					session.addMessageHandler(String.class, Room.this::handleWebSocketMessage);
				}

				@Override
				public void onError(Session session, Throwable t) {
					logger.log(Level.SEVERE, t, () -> "Problem with web socket [room=" + roomId + "]. Leaving room.");
					leave();
				}
			}, config, wsUri);
		} catch (DeploymentException e) {
			throw new IOException(e);
		}

		logger.info(() -> "Web socket connection successful [room=" + roomId + "]: " + wsUri);
	}

	/**
	 * Creates a timer that recreates the websocket connection periodically.
	 * This is an attempt to fix the issue where every couple days, the bot
	 * will stop responding to messages.
	 */
	private void createWebSocketRefreshTimer() {
		long period = WEBSOCKET_REFRESH_FREQUENCY.toMillis();
		websocketReconnectTimer.schedule(new TimerTask() {
			@Override
			public void run() {
				synchronized (Room.this) {
					logger.info(() -> "[room=" + roomId + "]: Recreating websocket connection.");

					try {
						webSocketSession.close();
					} catch (IOException e) {
						logger.log(Level.SEVERE, e, () -> "[room=" + roomId + "]: Problem closing existing websocket session.");
					}

					boolean connected = false;
					int attempts = 0;
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

	private URI getWebSocketUrl() throws IOException {
		//@formatter:off
		String url = baseUri()
			.setPath("/ws-auth")
		.toString();
		
		Response response = http.post(url,
			"roomid", roomId,
			"fkey", fkey
		);
		//@formatter:on

		JsonNode wsUrlNode = response.getBodyAsJson().get("url");
		if (wsUrlNode == null) {
			throw new IOException("Web socket URL missing from response.");
		}

		String wsUrl = wsUrlNode.asText();

		List<ChatMessage> messages = getMessages(1);
		ChatMessage latest = messages.isEmpty() ? null : messages.get(0);
		long time = (latest == null) ? 0 : latest.getTimestamp().toEpochSecond(ZoneOffset.UTC);

		try {
			//@formatter:off
			return new URIBuilder(wsUrl)
				.setParameter("l", Long.toString(time))
			.build();
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

		JsonNode roomNode = node.get("r" + roomId);
		if (roomNode == null) {
			return;
		}

		JsonNode eventsNode = roomNode.get("e");
		if (eventsNode == null || !eventsNode.isArray()) {
			return;
		}

		Map<WebSocketEventType, List<JsonNode>> eventsByType = groupEventsByType(eventsNode);

		List<Event> eventsToPublish = new ArrayList<>();

		eventsToPublish.addAll(WebSocketEventParsers.reply(eventsByType));
		eventsToPublish.addAll(WebSocketEventParsers.mention(eventsByType));

		Event movedOut = WebSocketEventParsers.messagesMovedOut(eventsByType);
		if (movedOut != null) {
			eventsToPublish.add(movedOut);
		}

		Event movedIn = WebSocketEventParsers.messagesMovedIn(eventsByType);
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

		List<Consumer<Event>> genericListeners = listeners.get(Event.class);
		synchronized (genericListeners) {
			for (Consumer<Event> listener : genericListeners) {
				for (Event event : eventsToPublish) {
					listener.accept(event);
				}
			}
		}

		for (Event event : eventsToPublish) {
			List<Consumer<Event>> eventListeners = listeners.get(event.getClass());
			synchronized (eventListeners) {
				for (Consumer<Event> listener : eventListeners) {
					listener.accept(event);
				}
			}
		}
	}

	private Map<WebSocketEventType, List<JsonNode>> groupEventsByType(JsonNode eventsNode) {
		Map<WebSocketEventType, List<JsonNode>> eventsByType = new EnumMap<>(WebSocketEventType.class);
		for (WebSocketEventType type : WebSocketEventType.values()) {
			eventsByType.put(type, new ArrayList<>());
		}

		for (JsonNode eventNode : eventsNode) {
			JsonNode eventTypeNode = eventNode.get("event_type");
			if (eventTypeNode == null || !eventTypeNode.canConvertToInt()) {
				logger.warning(() -> "[room " + roomId + "]: Ignoring JSON object that does not have a valid \"event_type\" field:\n" + JsonUtils.prettyPrint(eventNode) + "\n");
				continue;
			}

			WebSocketEventType eventType = WebSocketEventType.get(eventTypeNode.asInt());
			if (eventType == null) {
				logger.warning(() -> "[room " + roomId + "]: Ignoring event with unknown \"event_type\":\n" + JsonUtils.prettyPrint(eventNode) + "\n");
				continue;
			}

			eventsByType.get(eventType).add(eventNode);
		}

		return eventsByType;
	}

	private Event parseEvent(JsonNode node) {
		int id = node.get("event_type").asInt();
		WebSocketEventType eventType = WebSocketEventType.get(id);

		switch (eventType) {
		case MESSAGE_POSTED:
			return WebSocketEventParsers.messagePosted(node);
		case MESSAGE_EDITED:
			return WebSocketEventParsers.messageEdited(node);
		case INVITATION:
			return WebSocketEventParsers.invitation(node);
		case USER_ENTERED:
			return WebSocketEventParsers.userEntered(node);
		case USER_LEFT:
			return WebSocketEventParsers.userLeft(node);
		case MESSAGE_STARRED:
			return WebSocketEventParsers.messageStarred(node);
		case MESSAGE_DELETED:
			return WebSocketEventParsers.messageDeleted(node);
		default:
			logger.warning(() -> "[room " + roomId + "]: Ignoring event with unknown \"event_type\":\n" + JsonUtils.prettyPrint(node) + "\n");
			return null;
		}
	}

	@Override
	@SuppressWarnings("unchecked")
	public <T extends Event> void addEventListener(Class<T> clazz, Consumer<T> listener) {
		List<Consumer<Event>> eventListeners = listeners.get(clazz);
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
			parts = Arrays.asList(message);
		} else {
			parts = splitStrategy.split(message, MAX_MESSAGE_LENGTH);
		}

		//@formatter:off
		String url = baseUri()
			.setPathSegments("chats", Integer.toString(roomId), "messages", "new")
		.toString();
		//@formatter:on

		List<Long> messageIds = new ArrayList<>(parts.size());
		for (String part : parts) {
			//@formatter:off
			Response response = http.post(url, new RateLimit409Handler(),
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

			JsonNode body = response.getBodyAsJson();
			JsonNode idNode = body.get("id");
			long id = (idNode == null) ? 0 : idNode.asLong();
			messageIds.add(id);
		}

		return messageIds;
	}

	@Override
	public List<ChatMessage> getMessages(int count) throws IOException {
		//@formatter:off
		String url = baseUri()
			.setPathSegments("chats", Integer.toString(roomId), "events")
		.toString();

		Response response = http.post(url,
			"mode", "messages",
			"msgCount", count,
			"fkey", fkey
		);
		//@formatter:on

		if (response.getStatusCode() == 404) {
			throw notFound(response, "get messages");
		}

		JsonNode body = response.getBodyAsJson();
		JsonNode events = body.get("events");

		if (events == null || !events.isArray()) {
			return new ArrayList<>(0);
		}

		List<ChatMessage> messages = new ArrayList<>(events.size());
		Iterator<JsonNode> it = events.elements();
		while (it.hasNext()) {
			JsonNode element = it.next();
			ChatMessage chatMessage = WebSocketEventParsers.extractChatMessage(element);
			messages.add(chatMessage);
		}
		return messages;
	}

	@Override
	public void deleteMessage(long messageId) throws IOException {
		//@formatter:off
		String url = baseUri()
			.setPathSegments("messages", Long.toString(messageId), "delete")
		.toString();

		Response response = http.post(url,
			"fkey", fkey
		);
		//@formatter:on

		int statusCode = response.getStatusCode();
		if (statusCode == 302) {
			throw new IOException("Message ID " + messageId + " was never assigned to a message.");
		}

		String body = response.getBody();
		switch (body) {
		case "\"ok\"":
		case "\"This message has already been deleted.\"":
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
		String url = baseUri()
			.setPathSegments("messages", Long.toString(messageId))
		.toString();

		Response response = http.post(url, new RateLimit409Handler(),
			"text", updatedMessage,
			"fkey", fkey
		);
		//@formatter:on

		int statusCode = response.getStatusCode();
		if (statusCode == 302) {
			throw new IOException("Message ID " + messageId + " was never assigned to a message.");
		}

		String body = response.getBody();
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
		String url = baseUri()
			.setPath("/user/info")
		.toString();

		String idsCommaSeparated = userIds.stream()
			.map(Object::toString)
		.collect(Collectors.joining(","));

		Response response = http.post(url,
			"ids", idsCommaSeparated,
			"roomId", roomId
		);
		//@formatter:on

		JsonNode usersNode = response.getBodyAsJson().get("users");
		if (usersNode == null || !usersNode.isArray()) {
			return List.of();
		}

		//@formatter:off
		return StreamSupport.stream(usersNode.spliterator(), false)
			.map(this::parseUserInfo)
		.collect(Collectors.toList());
		//@formatter:on
	}

	private UserInfo parseUserInfo(JsonNode userNode) {
		UserInfo.Builder builder = new UserInfo.Builder();

		builder.roomId(roomId);

		JsonNode node = userNode.get("id");
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
			String emailHash = node.asText();
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
		String url = baseUri()
			.setPathSegments("rooms", "pingable", Integer.toString(roomId))
		.toString();
		//@formatter:on

		Response response = http.get(url);

		if (response.getStatusCode() == 404) {
			throw notFound(response, "get pingable users");
		}

		JsonNode root = response.getBodyAsJson();
		List<PingableUser> users = new ArrayList<>(root.size());
		for (JsonNode node : root) {
			if (!node.isArray() || node.size() < 4) {
				continue;
			}

			int userId = node.get(0).asInt();
			String username = node.get(1).asText();
			LocalDateTime lastPost = WebSocketEventParsers.timestamp(node.get(3).asLong());

			users.add(new PingableUser(roomId, userId, username, lastPost));
		}
		return users;
	}

	@Override
	public RoomInfo getRoomInfo() throws IOException {
		//@formatter:off
		String url = baseUri()
			.setPathSegments("rooms", "thumbs", Integer.toString(roomId))
		.toString();
		//@formatter:on

		Response response = http.get(url);

		if (response.getStatusCode() == 404) {
			throw notFound(response, "get room info");
		}

		JsonNode root = response.getBodyAsJson();

		JsonNode node = root.get("id");
		int id = (node == null) ? 0 : node.asInt();

		node = root.get("name");
		String name = (node == null) ? null : node.asText();

		node = root.get("description");
		String description = (node == null) ? null : node.asText();

		node = root.get("tags");
		List<String> tags;
		if (node == null) {
			tags = new ArrayList<>(0);
		} else {
			Document dom = Jsoup.parse(node.asText());

			//@formatter:off
			tags = dom.getElementsByTag("a").stream()
				.map(Element::html)
			.collect(Collectors.toList());
			//@formatter:on
		}

		return new RoomInfo(id, name, description, tags);
	}

	@Override
	public void leave() {
		chatClient.removeRoom(this);

		try {
			//@formatter:off
			String url = baseUri()
				.setPathSegments("chats", "leave", Integer.toString(roomId))
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
	private URIBuilder baseUri() {
		//@formatter:off
		return new URIBuilder()
			.setScheme("https")
			.setHost(chatClient.getSite().getChatDomain());
		//@formatter:on
	}

	@Override
	public void close() throws IOException {
		synchronized (this) {
			websocketReconnectTimer.cancel();
			webSocketSession.close();
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
			String body = response.getBody();
			Matcher m = response409Regex.matcher(body);

			int seconds = m.find() ? Integer.parseInt(m.group(0)) : 5;
			return Duration.ofSeconds(seconds);
		}
	}
}
