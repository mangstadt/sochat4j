package com.github.mangstadt.sochat4j;

/**
 * Thrown when attempting to join a private room.
 * @author Michael Angstadt
 */
@SuppressWarnings("serial")
public class PrivateRoomException extends RuntimeException {
	public PrivateRoomException(int roomId) {
		super("Cannot join room " + roomId + " because it is private.");
	}
}
