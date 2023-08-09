package com.github.mangstadt.sochat4j;

import java.util.Arrays;

/**
 * Defines each web socket event type that this library knows how to handle.
 * @author Michael Angstadt
 */
enum WebSocketEventType {
	//@formatter:off
	MESSAGE_POSTED(1),
	MESSAGE_EDITED(2),
	USER_ENTERED(3),
	USER_LEFT(4),
	MESSAGE_STARRED(6),
	USER_MENTIONED(8),
	MESSAGE_DELETED(10),
	REPLY_POSTED(18),
	MESSAGE_MOVED_OUT(19),
	MESSAGE_MOVED_IN(20);
	//@formatter:on

	/**
	 * The value of the "event_type" field in the JSON object that the web
	 * socket sends.
	 */
	private final int id;

	/**
	 * @param id the event type ID
	 */
	private WebSocketEventType(int id) {
		this.id = id;
	}

	/**
	 * Gets an event type given its ID.
	 * @param id the event type ID
	 * @return the event type or null if not found
	 */
	public static WebSocketEventType get(int id) {
		//@formatter:off
		return Arrays.stream(values())
			.filter(eventType -> eventType.id == id)
		.findAny().orElse(null);
		//@formatter:on
	}
}
