package com.github.mangstadt.sochat4j.event;

/**
 * Represents an event that is triggered when someone invites the user to
 * join another room.
 * @author Michael Angstadt
 */
public class InvitationEvent extends Event {
	private final int userId;
	private final String username;
	private final int roomId;
	private final String roomName;

	private InvitationEvent(Builder builder) {
		super(builder);
		userId = builder.userId;
		username = builder.username;
		roomId = builder.roomId;
		roomName = builder.roomName;
	}

	/**
	 * Gets the ID of the user who initiated the invite.
	 * @return the user ID
	 */
	public int getUserId() {
		return userId;
	}

	/**
	 * Gets the username of the user who initiated the invite.
	 * @return the username
	 */
	public String getUsername() {
		return username;
	}

	/**
	 * Gets the ID of the room that the user was invited to.
	 * @return the room ID
	 */
	public int getRoomId() {
		return roomId;
	}

	/**
	 * Gets the name of the room that the user was invited to.
	 * @return the room name
	 */
	public String getRoomName() {
		return roomName;
	}

	/**
	 * Used for constructing {@link InvitationEvent} instances.
	 * @author Michael Angstadt
	 */
	public static class Builder extends Event.Builder<InvitationEvent, Builder> {
		private int userId;
		private String username;
		private int roomId;
		private String roomName;

		/**
		 * Creates an empty builder.
		 */
		public Builder() {
			super();
		}

		/**
		 * Initializes the builder from an existing event.
		 * @param original the original event
		 */
		public Builder(InvitationEvent original) {
			super(original);
			userId = original.userId;
			username = original.username;
			roomId = original.roomId;
			roomName = original.roomName;
		}

		/**
		 * Sets the ID of the user who initiated the invite.
		 * @param userId the user ID
		 * @return this
		 */
		public Builder userId(int userId) {
			this.userId = userId;
			return this;
		}

		/**
		 * Sets the username of the user who initiated the invite.
		 * @param username the username
		 * @return this
		 */
		public Builder username(String username) {
			this.username = username;
			return this;
		}

		/**
		 * Sets the ID of the room the user was invited to.
		 * @param roomId the room ID
		 * @return this
		 */
		public Builder roomId(int roomId) {
			this.roomId = roomId;
			return this;
		}

		/**
		 * Sets the name of the room the user was invited to.
		 * @param roomName the room name
		 * @return this
		 */
		public Builder roomName(String roomName) {
			this.roomName = roomName;
			return this;
		}

		@Override
		public InvitationEvent build() {
			return new InvitationEvent(this);
		}
	}
}
