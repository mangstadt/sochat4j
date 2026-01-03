package com.github.mangstadt.sochat4j;

import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.http.client.config.CookieSpecs;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.mangstadt.sochat4j.util.Http;
import com.github.mangstadt.sochat4j.util.JsonUtils;
import com.github.mangstadt.sochat4j.util.WebSocketClient;
import com.github.mangstadt.sochat4j.util.WebSocketClientImpl;

/**
 * A connection to a chat site. This class is thread-safe.
 * @author Michael Angstadt
 * @see <a href=
 * "https://github.com/Zirak/SO-ChatBot/blob/master/source/adapter.js">Good
 * explanation of how SO Chat works</a>
 */
public class ChatClient implements IChatClient {
	/**
	 * The maximum number of characters a markdown-formatted chat message can
	 * be.
	 */
	public static final int MAX_MARKDOWN_MESSAGE_LENGTH = 500;

	private static final Logger logger = LoggerFactory.getLogger(ChatClient.class);

	private final Http http;
	private final WebSocketClient webSocketClient;
	private final Site site;
	private final Duration webSocketRefreshFrequency;
	private final Map<Integer, Room> rooms = new LinkedHashMap<>();
	private boolean loggedIn = false;
	private String username;
	private Integer userId;

	/**
	 * Connects to a chat site.
	 * @param site the chat site
	 * @param email the login email
	 * @param password the login password
	 * @return the chat client
	 * @throws InvalidCredentialsException if the credentials are bad
	 * @throws IOException if there's a network problem
	 */
	public static ChatClient connect(Site site, String email, String password) throws InvalidCredentialsException, IOException {
		return connect(site, email, password, null);
	}

	/**
	 * Connects to a chat site.
	 * @param site the chat site
	 * @param email the login email
	 * @param password the login password
	 * @param webSocketRefreshInterval how often each room's web socket
	 * connection is refreshed (a workaround to resolve random disconnects), or
	 * null to never refresh
	 * @return the chat client
	 * @throws InvalidCredentialsException if the credentials are bad
	 * @throws IOException if there's a network problem
	 */
	public static ChatClient connect(Site site, String email, String password, Duration webSocketRefreshInterval) throws InvalidCredentialsException, IOException {
		/*
		 * 4/2/2020: You cannot just call HttpClients.createDefault(). When this
		 * method is used, Apache's HTTP library does not properly parse the
		 * cookies it receives when logging into StackOverflow, and thus cannot
		 * join any chat rooms.
		 * 
		 * Solution: https://stackoverflow.com/q/36473478/13379
		 */
		//@formatter:off
		var httpClient = HttpClients.custom()
			.setDefaultRequestConfig(RequestConfig.custom().setCookieSpec(CookieSpecs.STANDARD).build())
		.build();
		//@formatter:on

		var webSocketClient = new WebSocketClientImpl();

		var client = new ChatClient(site, httpClient, webSocketClient, webSocketRefreshInterval);
		client.login(email, password);
		return client;
	}

	/**
	 * Creates a connection to a chat site. The connection is not established
	 * until {@link #login} is called.
	 * @param site the site to connect to
	 * @param httpClient the HTTP client
	 * @param webSocketClient the web socket client
	 */
	public ChatClient(Site site, CloseableHttpClient httpClient, WebSocketClient webSocketClient) {
		this(site, httpClient, webSocketClient, null);
	}

	/**
	 * Creates a connection to a chat site. The connection is not established
	 * until {@link #login} is called.
	 * @param site the site to connect to
	 * @param httpClient the HTTP client
	 * @param webSocketClient the web socket client
	 * @param webSocketRefreshFrequency how often each room's web socket
	 * connection is reset to address disconnects that randomly occur
	 */
	public ChatClient(Site site, CloseableHttpClient httpClient, WebSocketClient webSocketClient, Duration webSocketRefreshFrequency) {
		this.http = new Http(httpClient);
		this.webSocketClient = requireNonNull(webSocketClient);
		this.site = requireNonNull(site);
		this.webSocketRefreshFrequency = webSocketRefreshFrequency;
	}

	@Override
	public void login(String email, String password) throws InvalidCredentialsException, IOException {
		if (loggedIn) {
			return;
		}

		//@formatter:off
		var url = new URIBuilder()
			.setScheme("https")
			.setHost(site.getLoginDomain())
			.setPath("/users/login")
		.toString();
		//@formatter:on

		var response = http.get(url);
		var fkey = parseFKey(response.getBodyAsHtml());
		if (fkey == null) {
			throw new IOException("Unable to login. \"fkey\" field not found on login page.");
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
			throw new InvalidCredentialsException();
		}

		parseUserInfo();

		/*
		 * Note: The authenticated session info is stored in the HttpClient's
		 * cookie store.
		 */
		loggedIn = true;
	}

	/**
	 * Parses the "fkey" value from an HTML page.
	 * @param response the HTML page
	 * @return the fkey or null if not found
	 */
	String parseFKey(Document dom) {
		var element = dom.selectFirst("input[name=fkey]");
		return (element == null) ? null : element.attr("value");
	}

	private void parseUserInfo() throws IOException {
		//@formatter:off
		var url = new URIBuilder()
			.setScheme("https")
			.setHost(site.getChatDomain())
		.toString();
		//@formatter:on

		var response = http.get(url);
		var dom = response.getBodyAsHtml();

		var link = dom.selectFirst(".topbar-menu-links a");
		if (link != null) {
			var profileUrl = link.attr("href");
			var p = Pattern.compile("/users/(\\d+)");
			var m = p.matcher(profileUrl);
			if (m.find()) {
				userId = Integer.valueOf(m.group(1));
			}

			username = link.text();
		}
	}

	@Override
	public String getUsername() {
		assertLoggedIn();
		return username;
	}

	@Override
	public Integer getUserId() {
		assertLoggedIn();
		return userId;
	}

	@Override
	public Room joinRoom(int roomId) throws RoomNotFoundException, IOException {
		assertLoggedIn();

		synchronized (rooms) {
			var room = rooms.get(roomId);
			if (room != null) {
				return room;
			}

			room = new Room(roomId, http, webSocketClient, webSocketRefreshFrequency, this);
			rooms.put(roomId, room);

			return room;
		}
	}

	@Override
	public List<IRoom> getRooms() {
		synchronized (rooms) {
			return new ArrayList<>(rooms.values());
		}
	}

	@Override
	public Room getRoom(int roomId) {
		synchronized (rooms) {
			return rooms.get(roomId);
		}
	}

	@Override
	public boolean isInRoom(int roomId) {
		synchronized (rooms) {
			return rooms.containsKey(roomId);
		}
	}

	/**
	 * Removes a room from the list of connected rooms. For internal use only
	 * (invoked by {@link Room#leave}).
	 * @param room the room to remove from the list of connected rooms
	 */
	void removeRoom(Room room) {
		synchronized (rooms) {
			rooms.remove(room.getRoomId());
		}
	}

	@Override
	public String getOriginalMessageContent(long messageId) throws IOException {
		return getMessageContent(messageId, true);
	}

	@Override
	public String getMessageContent(long messageId) throws IOException {
		return getMessageContent(messageId, false);
	}

	private String getMessageContent(long messageId, boolean plain) throws IOException {
		//@formatter:off
		var url = baseUri()
			.setPathSegments("message", Long.toString(messageId))
			.setParameter("plain", plain ? "true" : "false")
		.toString();
		//@formatter:on

		var response = http.get(url);
		var statusCode = response.getStatusCode();
		if (statusCode != 200) {
			throw new IOException("HTTP " + statusCode + " response returned: " + response.getBody());
		}

		return response.getBody();
	}

	@Override
	public String uploadImage(String url) throws IOException {
		return uploadImage(null, url);
	}

	@Override
	public String uploadImage(byte[] data) throws IOException {
		return uploadImage(data, null);
	}

	private String uploadImage(byte[] imageData, String imageUrl) throws IOException {
		//@formatter:off
		var url = baseUri()
			.setPath("/upload/image")
		.toString();
		//@formatter:on

		var request = new HttpPost(url);

		var meb = MultipartEntityBuilder.create();
		if (imageData != null) {
			meb.addBinaryBody("filename", imageData, ContentType.DEFAULT_BINARY, "file.jpg");
		}
		if (imageUrl != null) {
			meb.addTextBody("upload-url", imageUrl);
		}
		request.setEntity(meb.build());

		String exceptionPreamble;
		if (imageData != null) {
			exceptionPreamble = "Uploading " + (imageData.length / 1024) + " KB image";
		} else {
			exceptionPreamble = "Uploading image '" + imageUrl + "'";
		}

		String html;
		try (var response = http.getClient().execute(request)) {
			var status = response.getStatusLine().getStatusCode();
			if (status != 200) {
				throw new IOException(exceptionPreamble + " resulted in HTTP " + status + " response.");
			}

			html = EntityUtils.toString(response.getEntity());
		}

		var p = Pattern.compile("var error = '(.*?)';");
		var m = p.matcher(html);
		if (m.find()) {
			throw new IOException(exceptionPreamble + " resulted in error: " + m.group(1));
		}

		p = Pattern.compile("var result = '(.*?)';");
		m = p.matcher(html);
		if (m.find()) {
			return m.group(1);
		}

		throw new IOException(exceptionPreamble + " resulted in unexpected response: " + html);
	}

	@Override
	public List<UserInfo> getUserInfo(int roomId, List<Integer> userIds) throws IOException {
		//@formatter:off
		var url = baseUri()
			.setPath("user/info")
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
			.map(n -> parseUserInfo(roomId, n))
		.toList();
		//@formatter:on
	}

	private UserInfo parseUserInfo(int roomId, JsonNode userNode) {
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
	public Site getSite() {
		return site;
	}

	@Override
	public void close() throws IOException {
		synchronized (rooms) {
			//leave all rooms
			if (!rooms.isEmpty()) {
				var anyRoom = rooms.values().iterator().next();
				var fkey = anyRoom.getFkey();

				try {
					//@formatter:off
					var url = baseUri()
						.setPath("/chats/leave/all")
					.toString();

					http.post(url,
						"quiet", "true", //setting this parameter to "false" results in an error
						"fkey", fkey
					);
					//@formatter:on
				} catch (IOException e) {
					logger.atError().setCause(e).log(() -> "Problem leaving all rooms.");
				}
			}

			for (var room : rooms.values()) {
				try {
					room.close();
				} catch (IOException e) {
					logger.atWarn().setCause(e).log(() -> "Problem closing websocket connection for room " + room.getRoomId() + ".");
				}
			}
			rooms.clear();
		}

		try {
			http.close();
		} catch (IOException e) {
			logger.atWarn().setCause(e).log(() -> "Problem closing HTTP connection.");
		}

		try {
			webSocketClient.close();
		} catch (IOException e) {
			logger.atWarn().setCause(e).log(() -> "Problem closing web socket client.");
		}
	}

	private void assertLoggedIn() {
		if (!loggedIn) {
			throw new IllegalStateException("Client is not authenticated. Call the \"login\" method first.");
		}
	}

	/**
	 * Gets a builder for the base URI of this chat site.
	 * @return the base URI (e.g. "https://chat.stackoverflow.com")
	 */
	private URIBuilder baseUri() {
		//@formatter:off
		return new URIBuilder()
			.setScheme("https")
			.setHost(site.getChatDomain());
		//@formatter:on
	}
}
