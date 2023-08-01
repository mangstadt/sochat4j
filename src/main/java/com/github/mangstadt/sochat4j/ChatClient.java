package com.github.mangstadt.sochat4j;

import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.http.client.config.CookieSpecs;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.glassfish.tyrus.client.ClientManager;
import org.glassfish.tyrus.client.ClientProperties;
import org.glassfish.tyrus.container.jdk.client.JdkClientContainer;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import com.github.mangstadt.sochat4j.util.Http;

import jakarta.websocket.WebSocketContainer;

/**
 * A connection to a Stack Exchange chat site that uses web sockets to retrieve
 * new messages. This class is thread-safe.
 * @author Michael Angstadt
 * @see <a href="https://chat.stackoverflow.com">chat.stackoverflow.com</a>
 * @see <a href=
 * "https://github.com/Zirak/SO-ChatBot/blob/master/source/adapter.js">Good
 * explanation of how SO Chat works</a>
 */
public class ChatClient implements IChatClient {
	private static final Logger logger = Logger.getLogger(ChatClient.class.getName());

	private final Http http;
	private final WebSocketContainer webSocketClient;
	private final Site site;
	private final Map<Integer, Room> rooms = new LinkedHashMap<>();
	private boolean loggedIn = false;

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
		/*
		 * 4/2/2020: You cannot just call HttpClients.createDefault(). When this
		 * method is used, Apache's HTTP library does not properly parse the
		 * cookies it receives when logging into StackOverflow, and thus cannot
		 * join any chat rooms.
		 * 
		 * Solution: https://stackoverflow.com/q/36473478/13379
		 */
		//@formatter:off
		CloseableHttpClient httpClient = HttpClients.custom() 
			.setDefaultRequestConfig(RequestConfig.custom().setCookieSpec(CookieSpecs.STANDARD).build())
		.build();
		//@formatter:on

		ClientManager webSocketClient = ClientManager.createClient(JdkClientContainer.class.getName());
		webSocketClient.setDefaultMaxSessionIdleTimeout(0);
		webSocketClient.getProperties().put(ClientProperties.RETRY_AFTER_SERVICE_UNAVAILABLE, true);

		ChatClient client = new ChatClient(site, httpClient, webSocketClient);
		client.login(email, password);
		return client;
	}

	/**
	 * Creates a connection to a Stack Exchange chat site. Note that the
	 * connection is not established until {@link #login} is called.
	 * @param site the Stack Exchange site to connect to
	 * @param httpClient the HTTP client
	 * @param webSocketClient the web socket client
	 */
	public ChatClient(Site site, CloseableHttpClient httpClient, WebSocketContainer webSocketClient) {
		this.http = new Http(httpClient);
		this.webSocketClient = requireNonNull(webSocketClient);
		this.site = requireNonNull(site);
	}

	@Override
	public void login(String email, String password) throws InvalidCredentialsException, IOException {
		if (loggedIn) {
			return;
		}

		//@formatter:off
		String url = new URIBuilder()
			.setScheme("https")
			.setHost(site.getLoginDomain())
			.setPath("/users/login")
		.toString();
		//@formatter:on

		Http.Response response = http.get(url);
		String fkey = parseFKey(response.getBodyAsHtml());
		if (fkey == null) {
			throw new IOException("\"fkey\" field not found on Stack Exchange login page, cannot login.");
		}

		//@formatter:off
		response = http.post(url,
			"email", email,
			"password", password,
			"fkey", fkey
		);
		//@formatter:on

		int statusCode = response.getStatusCode();
		boolean success = (statusCode == 302);
		if (!success) {
			throw new InvalidCredentialsException();
		}

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
		Element element = dom.selectFirst("input[name=fkey]");
		return (element == null) ? null : element.attr("value");
	}

	@Override
	public Room joinRoom(int roomId) throws RoomNotFoundException, IOException {
		if (!loggedIn) {
			throw new IllegalStateException("Client is not authenticated. Call the \"login\" method first.");
		}

		synchronized (rooms) {
			Room room = rooms.get(roomId);
			if (room != null) {
				return room;
			}

			room = new Room(roomId, http, webSocketClient, this);
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
		//@formatter:off
		String url = baseUri()
			.setPathSegments("message", Long.toString(messageId))
			.setParameter("plain", "true")
		.toString();
		//@formatter:off
		
		Http.Response response = http.get(url);
		int statusCode = response.getStatusCode();
		if (statusCode != 200) {
			throw new IOException("HTTP " + statusCode + " response returned: " + response.getBody());
		}

		return response.getBody();
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
				Room anyRoom = rooms.values().iterator().next();
				String fkey = anyRoom.getFkey();

				try {
					//@formatter:off
					String url = baseUri()
						.setPath("/chats/leave/all")
					.toString();

					http.post(url,
						"quiet", "true", //setting this parameter to "false" results in an error
						"fkey", fkey
					);
					//@formatter:on
				} catch (IOException e) {
					logger.log(Level.SEVERE, "Problem leaving all rooms.", e);
				}
			}

			for (Room room : rooms.values()) {
				try {
					room.close();
				} catch (IOException e) {
					if (logger.isLoggable(Level.WARNING)) {
						logger.log(Level.WARNING, "Problem closing websocket connection for room " + room.getRoomId() + ".", e);
					}
				}
			}
			rooms.clear();
		}

		try {
			http.close();
		} catch (IOException e) {
			if (logger.isLoggable(Level.WARNING)) {
				logger.log(Level.WARNING, "Problem closing HTTP connection.", e);
			}
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
