package com.github.mangstadt.sochat4j;

import java.time.LocalDateTime;

/**
 * <p>
 * Represents a user that is "pingable". Pingable users receive a notification
 * when they are mentioned.
 * </p>
 * <p>
 * A user does not have to be in the room in order to be pingable. They remain
 * pingable for several days (or weeks?) after leaving the room.
 * </p>
 * @param roomId the room ID of the room that the user is pingable from
 * @param userId the user's ID
 * @param username the user's username
 * @param lastPost the time of the user's most recent post
 * @author Michael Angstadt
 * @see IRoom#getPingableUsers
 */
public record PingableUser(int roomId, int userId, String username, LocalDateTime lastPost) {
}
