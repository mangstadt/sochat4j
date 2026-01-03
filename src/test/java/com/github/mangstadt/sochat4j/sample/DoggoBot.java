package com.github.mangstadt.sochat4j.sample;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Random;

import com.github.mangstadt.sochat4j.ChatClient;
import com.github.mangstadt.sochat4j.InvalidCredentialsException;
import com.github.mangstadt.sochat4j.PrivateRoomException;
import com.github.mangstadt.sochat4j.RoomNotFoundException;
import com.github.mangstadt.sochat4j.Site;
import com.github.mangstadt.sochat4j.event.MessagePostedEvent;

/**
 * Creates a good boy that responds when petted.
 * @author Michael Angstadt
 */
public class DoggoBot {
	public static void main(String[] args) throws Exception {
		var site = Site.STACKOVERFLOW;
		var email = "email@example.com";
		var password = "password";
		var roomId = 1;

		var rng = new Random();
		try (var client = ChatClient.connect(site, email, password)) {
			var room = client.joinRoom(roomId);

			var responses = new String[] { "wimpers", "barks", "pants", "wags tail" };

			room.addEventListener(MessagePostedEvent.class, event -> {
				var content = event.getMessage().content().getContent();

				if ("#pet".equals(content)) {
					var index = rng.nextInt(responses.length);
					var response = responses[index];
					try {
						room.sendMessage("*" + response + "*");
					} catch (Exception e) {
						e.printStackTrace();
					}
				}
			});

			room.sendMessage("*barks*");

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
		}
	}
}
