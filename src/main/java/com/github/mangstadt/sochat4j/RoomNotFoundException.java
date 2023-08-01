package com.github.mangstadt.sochat4j;

/**
 * Thrown when a room doesn't exist, or a room is inactive and the user
 * can't see inactive rooms.
 * @author Michael Angstadt
 */
@SuppressWarnings("serial")
public class RoomNotFoundException extends RuntimeException {
	public RoomNotFoundException(int roomId) {
		super("Room " + roomId + " does not exist.");
	}
}
