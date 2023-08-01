package com.github.mangstadt.sochat4j.sample;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

import com.github.mangstadt.sochat4j.ChatClient;
import com.github.mangstadt.sochat4j.IChatClient;
import com.github.mangstadt.sochat4j.IRoom;
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
		Site site = Site.STACKOVERFLOW;
		String email = "email@example.com";
		String password = "password";
		int roomId = 1;

		try (IChatClient client = ChatClient.connect(site, email, password)) {
			IRoom room = client.joinRoom(roomId);

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
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
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
