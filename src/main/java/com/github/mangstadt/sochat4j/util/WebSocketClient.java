package com.github.mangstadt.sochat4j.util;

import java.io.Closeable;

import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * Creates web socket connections.
 * @author Michael Angstadt
 */
public interface WebSocketClient extends Closeable {
	/**
	 * Creates a web socket connection.
	 * @param url the URL to the web socket
	 * @param origin the value of the Origin header
	 * @param listener listens for incoming messages
	 * @return the web socket connection
	 */
	WebSocket connect(String url, String origin, WebSocketListener listener);
}
