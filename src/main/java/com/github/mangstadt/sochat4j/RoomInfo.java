package com.github.mangstadt.sochat4j;

import java.util.List;

/**
 * Contains information about a room, such as its name and description.
 * @author Michael Angstadt
 */
public record RoomInfo(int id, String name, String description, List<String> tags) {
	/**
	 * @param id the room ID
	 * @param name the room name
	 * @param description the room description
	 * @param tags the room tags
	 */
	public RoomInfo {
		tags = List.copyOf(tags);
	}
}
