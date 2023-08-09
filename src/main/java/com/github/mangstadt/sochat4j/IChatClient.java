package com.github.mangstadt.sochat4j;

import java.io.Closeable;
import java.io.IOException;
import java.util.List;

/**
 * A connection to a Stack Overflow chat site.
 * @author Michael Angstadt
 * @see <a href="https://chat.stackoverflow.com">chat.stackoverflow.com</a>
 * @see <a href=
 * "https://github.com/Zirak/SO-ChatBot/blob/master/source/adapter.js">Good
 * explanation of how SO Chat works</a>
 */
public interface IChatClient extends Closeable {
	/**
	 * Logs into the chat system. This must be called before most other methods.
	 * @param email the login email
	 * @param password the login password
	 * @throws InvalidCredentialsException if the login credentials are bad
	 * @throws IOException if there's a network problem
	 */
	void login(String email, String password) throws InvalidCredentialsException, IOException;

	/**
	 * Joins a chat room. If the client is already connected to the room, the
	 * existing connection is returned.
	 * @param roomId the room ID
	 * @return the connection to the chat room
	 * @throws IllegalStateException if the user has not yet logged in
	 * successfully using the {@link #login} method
	 * @throws RoomNotFoundException if the room does not exist or the user does
	 * not have permission to view the room
	 * @throws PrivateRoomException if the room cannot be joined because it is
	 * private
	 * @throws IOException if there's a network problem
	 */
	IRoom joinRoom(int roomId) throws RoomNotFoundException, PrivateRoomException, IOException;

	/**
	 * Gets all of the rooms the chat client is currently connected to.
	 * @return the rooms
	 */
	List<IRoom> getRooms();

	/**
	 * Gets a room that the chat client is currently connected to
	 * @param roomId the room ID
	 * @return the room or null if the chat client is not connected to that room
	 */
	IRoom getRoom(int roomId);

	/**
	 * Determines if the chat client is currently connected to a room.
	 * @param roomId the room ID
	 * @return true if the chat client is connected to the room, false if not
	 */
	boolean isInRoom(int roomId);

	/**
	 * <p>
	 * Queries the chat service for the original, Markdown-encoded message that
	 * the user typed into the chat room. This differs from the HTML-formatted
	 * messages that you get from the web socket.
	 * </p>
	 * <p>
	 * This returns EXACTLY what the user typed into the chat. For example, if
	 * the user prefixed their message with a single space character, the space
	 * character will NOT appear the HTML-formatted message, but WILL appear in
	 * the string returned by this method.
	 * </p>
	 * <p>
	 * This method does not require authentication (see {@link #login}).
	 * </p>
	 * @param messageId the message ID
	 * @return the original message content
	 * @throws IOException if there's a network problem or a non-200 response
	 * was returned
	 */
	String getOriginalMessageContent(long messageId) throws IOException;

	/**
	 * Gets the account's username.
	 * @return the username or null if it couldn't be found
	 * @throws IllegalStateException if the user has not yet logged in
	 * successfully using the {@link #login} method
	 */
	String getUsername();

	/**
	 * Gets the account's user ID.
	 * @return the user ID or null if couldn't be found
	 * @throws IllegalStateException if the user has not yet logged in
	 * successfully using the {@link #login} method
	 */
	Integer getUserId();

	/**
	 * Gets the site that this chat client is connected to.
	 * @return the site
	 */
	Site getSite();
}
