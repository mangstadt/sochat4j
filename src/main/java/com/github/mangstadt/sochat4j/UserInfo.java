package com.github.mangstadt.sochat4j;

import java.time.LocalDateTime;

/**
 * Contains information about a chat user.
 * @param userId the user ID
 * @param roomId the room ID of the room that this user info came from
 * @param username the username
 * @param profilePicture the URL of the user's profile picture
 * @param reputation the user's reputation score
 * @param moderator true if the user is a moderator, false if not
 * @param owner true if the user is a room owner, false if not
 * @param lastSeen the time the user was last seen in the room that this user
 * info came from
 * @param lastPost the time the user last posted in the room that this user info
 * came from
 * @author Michael Angstadt
 */
public record UserInfo(int userId, int roomId, String username, String profilePicture, int reputation, boolean moderator, boolean owner, LocalDateTime lastSeen, LocalDateTime lastPost) {
	public static class Builder {
		private int userId;
		private int roomId;
		private String username;
		private String profilePicture;
		private int reputation;
		private boolean moderator;
		private boolean owner;
		private LocalDateTime lastSeen;
		private LocalDateTime lastPost;

		public Builder userId(int userId) {
			this.userId = userId;
			return this;
		}

		public Builder roomId(int roomId) {
			this.roomId = roomId;
			return this;
		}

		public Builder username(String username) {
			this.username = username;
			return this;
		}

		public Builder reputation(int reputation) {
			this.reputation = reputation;
			return this;
		}

		public Builder profilePicture(String profilePicture) {
			this.profilePicture = profilePicture;
			return this;
		}

		public Builder moderator(boolean moderator) {
			this.moderator = moderator;
			return this;
		}

		public Builder owner(boolean owner) {
			this.owner = owner;
			return this;
		}

		public Builder lastSeen(LocalDateTime lastSeen) {
			this.lastSeen = lastSeen;
			return this;
		}

		public Builder lastPost(LocalDateTime lastPost) {
			this.lastPost = lastPost;
			return this;
		}

		public UserInfo build() {
			return new UserInfo(userId, roomId, username, profilePicture, reputation, moderator, owner, lastSeen, lastPost);
		}
	}
}
