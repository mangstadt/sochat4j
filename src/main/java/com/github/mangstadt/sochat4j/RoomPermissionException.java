package com.github.mangstadt.sochat4j;

/**
 * <p>
 * Thrown when a request is sent to a room and it fails. It could fail for any
 * of the following reasons:
 * <p>
 * <ul>
 * <li>The room is inactive/frozen.</li>
 * <li>The room is protected (the bot doesn't have permission to post).</li>
 * <li>The bot doesn't have enough reputation to post.</li>
 * <li>The bot was banned from the room (untested).</li>
 * </ul>
 * @author Michael Angstadt
 */
@SuppressWarnings("serial")
public class RoomPermissionException extends RuntimeException {
	public RoomPermissionException(int roomId) {
		super("Cannot post messages to room " + roomId + ".");
	}
}
