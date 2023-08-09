package com.github.mangstadt.sochat4j;

import java.time.LocalDateTime;

/**
 * Represents a user that is "pingable". Pingable users receive a notification
 * when they are mentioned. A user does not have to in the room in order to be
 * pingable--they remain pingable for several days (or weeks?) after leaving the
 * room.
 * @author Michael Angstadt
 * @see IRoom#getPingableUsers
 */
public class PingableUser {
	private final int roomId, userId;
	private final String username;
	private final LocalDateTime lastPost;

	/**
	 * @param roomId the room ID
	 * @param userId the user ID
	 * @param username the username
	 * @param lastPost the time of their last post
	 */
	public PingableUser(int roomId, int userId, String username, LocalDateTime lastPost) {
		this.roomId = roomId;
		this.userId = userId;
		this.username = username;
		this.lastPost = lastPost;
	}

	/**
	 * Gets the room that the user is pingable from.
	 * @return the room ID
	 */
	public int getRoomId() {
		return roomId;
	}

	/**
	 * Gets the user's ID.
	 * @return the user's ID
	 */
	public int getUserId() {
		return userId;
	}

	/**
	 * Gets the username.
	 * @return the username
	 */
	public String getUsername() {
		return username;
	}

	/**
	 * Gets the time that they last posted a message.
	 * @return the time of their last post
	 */
	public LocalDateTime getLastPost() {
		return lastPost;
	}

	@Override
	public String toString() {
		return "PingableUser [roomId=" + roomId + ", userId=" + userId + ", username=" + username + ", lastPost=" + lastPost + "]";
	}
}
