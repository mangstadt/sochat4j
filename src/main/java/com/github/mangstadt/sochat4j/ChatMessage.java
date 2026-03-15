package com.github.mangstadt.sochat4j;

import java.time.LocalDateTime;

/**
 * Represents a chat message. Use its {@link Builder} class to construct new
 * instances.
 * @param timestamp the time the message was posted or last edited
 * @param id the message ID. This ID is unique across all chat rooms
 * @param parentMessageId the parent message ID or 0 if this message is not a
 * reply
 * @param userId the user ID of the message author
 * @param username the username of the message author
 * @param mentionedUserId the user ID of the first user that was mentioned in
 * the message or 0 if nobody was mentioned (field may not exist anymore)
 * @param roomId the room ID of the room that this message was posted in
 * @param roomName the room name of the room that this message was posted in
 * @param content the message content or null if the message was deleted
 * @param edits the number of times the message was edited
 * @param stars the number of stars the message has
 * @param parentId the ID of message that this message is replying to, or 0 if
 * this message is not a reply
 * @param parentUsername the username of the author of the message that this
 * message is replying to, or null if this message is not a reply
 * @param parentContent the content of the message that this message is replying
 * to, or null if this message is not a reply
 * @author Michael Angstadt
 */
public record ChatMessage(LocalDateTime timestamp, long id, int userId, String username, int mentionedUserId, int roomId, String roomName, Content content, int edits, int stars, long parentId, String parentUsername, Content parentContent) {

	/**
	 * Determines if the message was deleted.
	 * @return true if the message was deleted, false if not
	 */
	public boolean isDeleted() {
		return content == null;
	}

	/**
	 * Determines if the given user is mentioned in the message.
	 * @param userId the user ID
	 * @param username the username
	 * @return true if the user is mentioned, false if not
	 */
	public boolean isUserMentioned(int userId, String username) {
		//direct replies
		if (parentUsername != null) {
			var sanitizedUsername = username.toLowerCase().replace(" ", "");
			var sanitizedParentUsername = parentUsername.toLowerCase().replace(" ", "");

			if (sanitizedParentUsername.startsWith(sanitizedUsername)) {
				return true;
			}
		}

		return mentionedUserId == userId || content.isMentioned(username);
	}

	/**
	 * Used for constructing {@link ChatMessage} instances.
	 * @author Michael Angstadt
	 */
	public static class Builder {
		private LocalDateTime timestamp;

		private long id;

		private int userId;
		private String username;
		private int mentionedUserId;

		private int roomId;
		private String roomName;

		private Content content;

		private int edits;
		private int stars;

		private long parentId;
		private String parentUsername;
		private Content parentContent;

		/**
		 * Creates an empty builder.
		 */
		public Builder() {
			//empty
		}

		/**
		 * Initializes the builder from an existing chat message.
		 * @param original the original object
		 */
		public Builder(ChatMessage original) {
			timestamp = original.timestamp;

			id = original.id;

			userId = original.userId;
			username = original.username;
			mentionedUserId = original.mentionedUserId;

			roomId = original.roomId;
			roomName = original.roomName;

			content = original.content;

			edits = original.edits;
			stars = original.stars;

			parentId = original.parentId;
			parentUsername = original.parentUsername;
			parentContent = original.parentContent;
		}

		/**
		 * Sets the time the message was posted or modified.
		 * @param timestamp the timestamp
		 * @return this
		 */
		public Builder timestamp(LocalDateTime timestamp) {
			this.timestamp = timestamp;
			return this;
		}

		/**
		 * Sets the ID of the message. This ID is unique across all chat rooms.
		 * @param id the message ID
		 * @return this
		 */
		public Builder id(long id) {
			this.id = id;
			return this;
		}

		/**
		 * Sets the user ID of the message author.
		 * @param userId the user ID
		 * @return this
		 */
		public Builder userId(int userId) {
			this.userId = userId;
			return this;
		}

		/**
		 * Sets the username of the message author.
		 * @param username the username
		 * @return this
		 */
		public Builder username(String username) {
			this.username = username;
			return this;
		}

		/**
		 * Sets the ID of the user that was mentioned in the message content. If
		 * a message contains multiple mentions, only the ID of the first
		 * mentioned user is recorded. This field may not exist anymore.
		 * @param mentionedUserId the ID of the mentioned user or 0 if nobody
		 * was mentioned
		 * @return this
		 */
		public Builder mentionedUserId(int mentionedUserId) {
			this.mentionedUserId = mentionedUserId;
			return this;
		}

		/**
		 * Sets the ID of the room the message is currently in.
		 * @param roomId the room ID
		 * @return this
		 */
		public Builder roomId(int roomId) {
			this.roomId = roomId;
			return this;
		}

		/**
		 * Sets the name of the room the message is currently in.
		 * @param roomName the room name
		 * @return this
		 */
		public Builder roomName(String roomName) {
			this.roomName = roomName;
			return this;
		}

		/**
		 * Sets the content of the message.
		 * @param content the content or null if the author deleted the message
		 * @return this
		 */
		public Builder content(Content content) {
			this.content = content;
			return this;
		}

		/**
		 * Sets the content of the message.
		 * @param content the content or null if the author deleted the message
		 * @return this
		 */
		public Builder content(String content) {
			return content(content, false);
		}

		/**
		 * Sets the content of the message.
		 * @param content the content or null if the author deleted the message
		 * @param fixedWidthFont true if the content is formatted in a
		 * fixed-width font, false if not
		 * @return this
		 */
		public Builder content(String content, boolean fixedWidthFont) {
			return content((content == null) ? null : new Content(content, fixedWidthFont));
		}

		/**
		 * Sets the number of times the message was edited.
		 * @param edits the number of edits
		 * @return this
		 */
		public Builder edits(int edits) {
			this.edits = edits;
			return this;
		}

		/**
		 * Sets the number of stars the message has.
		 * @param stars the number of stars
		 * @return this
		 */
		public Builder stars(int stars) {
			this.stars = stars;
			return this;
		}

		/**
		 * Sets the ID of the message that this message is replying to. This ID
		 * is unique across all chat rooms.
		 * @param parentId the ID or 0 if this message is not a reply
		 * @return this
		 */
		public Builder parentId(long parentId) {
			this.parentId = parentId;
			return this;
		}

		/**
		 * Sets the username of the author of the message that this message is
		 * replying to.
		 * @param parentUsername the username
		 * @return this
		 */
		public Builder parentUsername(String parentUsername) {
			this.parentUsername = parentUsername;
			return this;
		}

		/**
		 * Sets the content of the message that this message is replying to.
		 * @param parentContent the message content
		 * @return this
		 */
		public Builder parentContent(Content parentContent) {
			this.parentContent = parentContent;
			return this;
		}

		/**
		 * Builds the chat message.
		 * @return the built object
		 */
		public ChatMessage build() {
			return new ChatMessage(timestamp, id, userId, username, mentionedUserId, roomId, roomName, content, edits, stars, parentId, parentUsername, parentContent);
		}
	}
}
