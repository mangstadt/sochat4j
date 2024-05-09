package com.github.mangstadt.sochat4j.sample;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

import com.github.mangstadt.sochat4j.ChatClient;
import com.github.mangstadt.sochat4j.InvalidCredentialsException;
import com.github.mangstadt.sochat4j.PrivateRoomException;
import com.github.mangstadt.sochat4j.RoomNotFoundException;
import com.github.mangstadt.sochat4j.RoomPermissionException;
import com.github.mangstadt.sochat4j.Site;
import com.github.mangstadt.sochat4j.event.UserEnteredEvent;
import com.github.mangstadt.sochat4j.event.UserLeftEvent;

/**
 * Creates a bot that welcomes people who join the room, and says goodbye to
 * people who leave.
 * @author Michael Angstadt
 */
public class WelcomeBot {
	public static void main(String[] args) {
		var site = Site.STACKOVERFLOW;
		var email = "email@example.com";
		var password = "password";
		var roomId = 1;

		try (var client = ChatClient.connect(site, email, password)) {
			var room = client.joinRoom(roomId);

			room.addEventListener(UserEnteredEvent.class, event -> {
				try {
					room.sendMessage("Welcome, " + event.getUsername() + "!");
				} catch (RoomPermissionException | IOException e) {
					e.printStackTrace();
				}
			});

			room.addEventListener(UserLeftEvent.class, event -> {
				try {
					room.sendMessage("Bye, " + event.getUsername() + "!");
				} catch (RoomPermissionException | IOException e) {
					e.printStackTrace();
				}
			});

			room.sendMessage("WelcomeBot Online!");

			System.out.println("Press Enter to terminate the bot.");
			try (var reader = new BufferedReader(new InputStreamReader(System.in))) {
				reader.readLine();
			}

			room.sendMessage("Leaving, bye!");
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
