package com.github.mangstadt.sochat4j.sample;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

import com.github.mangstadt.sochat4j.ChatClient;
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
		var site = Site.STACKOVERFLOW;
		var email = "email@example.com";
		var password = "password";
		var roomId = 1;

		try (var client = ChatClient.connect(site, email, password)) {
			var room = client.joinRoom(roomId);

			room.addEventListener(e -> {
				if (e instanceof MessageDeletedEvent event) {
					var message = event.getMessage();
					System.out.println("Message by " + message.username() + " was deleted.");
				}

				if (e instanceof MessageEditedEvent event) {
					var message = event.getMessage();
					System.out.println("Message by " + message.username() + " was edited: " + message.content().getContent());
				}

				if (e instanceof MessagePostedEvent event) {
					var message = event.getMessage();
					System.out.println(message.username() + " said: " + message.content().getContent());
				}

				if (e instanceof MessagesMovedEvent event) {
					System.out.println(event.getMessages().size() + " messages moved to room " + event.getDestRoomId() + " by " + event.getMoverUsername() + ".");
				}

				if (e instanceof MessageStarredEvent event) {
					var message = event.getMessage();
					System.out.println("Message now has " + message.stars() + " stars: " + message.content().getContent());
				}

				if (e instanceof UserEnteredEvent event) {
					System.out.println(event.getUsername() + " entered the room.");
				}

				if (e instanceof UserLeftEvent event) {
					System.out.println(event.getUsername() + " left the room.");
				}
			});

			System.out.println("Press Enter to terminate the bot.");
			try (var reader = new BufferedReader(new InputStreamReader(System.in))) {
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
