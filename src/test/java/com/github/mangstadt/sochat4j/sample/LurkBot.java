package com.github.mangstadt.sochat4j.sample;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

import com.github.mangstadt.sochat4j.ChatClient;
import com.github.mangstadt.sochat4j.ChatMessage;
import com.github.mangstadt.sochat4j.IChatClient;
import com.github.mangstadt.sochat4j.IRoom;
import com.github.mangstadt.sochat4j.InvalidCredentialsException;
import com.github.mangstadt.sochat4j.PrivateRoomException;
import com.github.mangstadt.sochat4j.RoomNotFoundException;
import com.github.mangstadt.sochat4j.Site;
import com.github.mangstadt.sochat4j.event.MessageDeletedEvent;
import com.github.mangstadt.sochat4j.event.MessageEditedEvent;
import com.github.mangstadt.sochat4j.event.MessagePostedEvent;
import com.github.mangstadt.sochat4j.event.MessageStarredEvent;
import com.github.mangstadt.sochat4j.event.MessagesMovedEvent;
import com.github.mangstadt.sochat4j.event.UserEnteredEvent;
import com.github.mangstadt.sochat4j.event.UserLeftEvent;

/**
 * Creates a bot that outputs all chat room events to stdout.
 * @author Michael Angstadt
 */
public class LurkBot {
	public static void main(String[] args) {
		Site site = Site.STACKOVERFLOW;
		String email = "email@example.com";
		String password = "password";
		int roomId = 1;

		try (IChatClient client = ChatClient.connect(site, email, password)) {
			IRoom room = client.joinRoom(roomId);

			room.addEventListener(e -> {
				if (e instanceof MessageDeletedEvent) {
					MessageDeletedEvent event = (MessageDeletedEvent) e;
					ChatMessage message = event.getMessage();
					System.out.println("Message by " + message.getUsername() + " was deleted.");
				}

				if (e instanceof MessageEditedEvent) {
					MessageEditedEvent event = (MessageEditedEvent) e;
					ChatMessage message = event.getMessage();
					System.out.println("Message by " + message.getUsername() + " was edited: " + message.getContent().getContent());
				}

				if (e instanceof MessagePostedEvent) {
					MessagePostedEvent event = (MessagePostedEvent) e;
					ChatMessage message = event.getMessage();
					System.out.println(message.getUsername() + " said: " + message.getContent().getContent());
				}

				if (e instanceof MessagesMovedEvent) {
					MessagesMovedEvent event = (MessagesMovedEvent) e;
					System.out.println(event.getMessages().size() + " messages moved to room " + event.getDestRoomId() + " by " + event.getMoverUsername() + ".");
				}

				if (e instanceof MessageStarredEvent) {
					MessageStarredEvent event = (MessageStarredEvent) e;
					ChatMessage message = event.getMessage();
					System.out.println("Message now has " + message.getStars() + " stars: " + message.getContent().getContent());
				}

				if (e instanceof UserEnteredEvent) {
					UserEnteredEvent event = (UserEnteredEvent) e;
					System.out.println(event.getUsername() + " entered the room.");
				}

				if (e instanceof UserLeftEvent) {
					UserLeftEvent event = (UserLeftEvent) e;
					System.out.println(event.getUsername() + " left the room.");
				}
			});

			System.out.println("Press Enter to terminate the bot.");
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
				reader.readLine();
			}
		} catch (InvalidCredentialsException e) {
			System.err.println("Login credentials invalid.");
		} catch (RoomNotFoundException e) {
			System.err.println("Room not found.");
		} catch (PrivateRoomException e) {
			System.err.println("Cannot join room because it is private.");
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
}
