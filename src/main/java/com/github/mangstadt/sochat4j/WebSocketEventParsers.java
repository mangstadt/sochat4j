package com.github.mangstadt.sochat4j;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.mangstadt.sochat4j.event.Event;
import com.github.mangstadt.sochat4j.event.InvitationEvent;
import com.github.mangstadt.sochat4j.event.MessageDeletedEvent;
import com.github.mangstadt.sochat4j.event.MessageEditedEvent;
import com.github.mangstadt.sochat4j.event.MessagePostedEvent;
import com.github.mangstadt.sochat4j.event.MessageStarredEvent;
import com.github.mangstadt.sochat4j.event.MessagesMovedEvent;
import com.github.mangstadt.sochat4j.event.UserEnteredEvent;
import com.github.mangstadt.sochat4j.event.UserLeftEvent;

/**
 * Parses web socket events into {@link Event} objects.
 * @author Michael Angstadt
 */
class WebSocketEventParsers {
	/**
	 * Parses a "message posted" event.
	 * @param element the JSON element to parse
	 * @return the parsed event
	 */
	public static MessagePostedEvent messagePosted(JsonNode element) {
		var builder = new MessagePostedEvent.Builder();

		extractCommonEventFields(element, builder);
		builder.message(extractChatMessage(element));

		return builder.build();
	}

	/**
	 * Parses a "message edited" event.
	 * @param element the JSON element to parse
	 * @return the parsed event
	 */
	public static MessageEditedEvent messageEdited(JsonNode element) {
		var builder = new MessageEditedEvent.Builder();

		extractCommonEventFields(element, builder);
		builder.message(extractChatMessage(element));

		return builder.build();
	}

	/**
	 * Parses a "message deleted" event.
	 * @param element the JSON element to parse
	 * @return the parsed event
	 */
	public static MessageDeletedEvent messageDeleted(JsonNode element) {
		var builder = new MessageDeletedEvent.Builder();

		extractCommonEventFields(element, builder);
		builder.message(extractChatMessage(element));

		return builder.build();
	}

	/**
	 * Parses a "message starred" event.
	 * @param element the JSON element to parse
	 * @return the parsed event
	 */
	public static MessageStarredEvent messageStarred(JsonNode element) {
		var builder = new MessageStarredEvent.Builder();

		extractCommonEventFields(element, builder);
		builder.message(extractChatMessage(element));

		return builder.build();
	}

	/**
	 * Parses a "user entered" event.
	 * @param element the JSON element to parse
	 * @return the parsed event
	 */
	public static UserEnteredEvent userEntered(JsonNode element) {
		var builder = new UserEnteredEvent.Builder();

		extractCommonEventFields(element, builder);

		var value = element.get("room_id");
		if (value != null) {
			builder.roomId(value.asInt());
		}

		value = element.get("room_name");
		if (value != null) {
			builder.roomName(value.asText());
		}

		value = element.get("user_id");
		if (value != null) {
			builder.userId(value.asInt());
		}

		value = element.get("user_name");
		if (value != null) {
			builder.username(value.asText());
		}

		return builder.build();
	}

	/**
	 * Parses a "user left" event.
	 * @param element the JSON element to parse
	 * @return the parsed event
	 */
	public static UserLeftEvent userLeft(JsonNode element) {
		var builder = new UserLeftEvent.Builder();

		extractCommonEventFields(element, builder);

		var value = element.get("room_id");
		if (value != null) {
			builder.roomId(value.asInt());
		}

		value = element.get("room_name");
		if (value != null) {
			builder.roomName(value.asText());
		}

		value = element.get("user_id");
		if (value != null) {
			builder.userId(value.asInt());
		}

		value = element.get("user_name");
		if (value != null) {
			builder.username(value.asText());
		}

		return builder.build();
	}

	/**
	 * Parses an "room invitation" event.
	 * @param element the JSON element to parse
	 * @return the parsed event
	 */
	public static InvitationEvent invitation(JsonNode element) {
		var builder = new InvitationEvent.Builder();

		extractCommonEventFields(element, builder);

		var value = element.get("room_id");
		if (value != null) {
			builder.roomId(value.asInt());
		}

		value = element.get("room_name");
		if (value != null) {
			builder.roomName(value.asText());
		}

		value = element.get("user_id");
		if (value != null) {
			builder.userId(value.asInt());
		}

		value = element.get("user_name");
		if (value != null) {
			builder.username(value.asText());
		}

		return builder.build();
	}

	/*
	 * When messages are moved out of a room, the chat system posts a new
	 * message under the name of the user who moved the messages. The
	 * content of this message contains the ID and name of the room that the
	 * messages were moved to. This regex parses that content.
	 */
	private static final Pattern messagesMovedOutRegex = Pattern.compile("^&rarr; <i><a href=\".*?\">\\d+ messages?</a> moved to <a href=\".*?/rooms/(\\d+)/.*?\">(.*?)</a></i>$");

	/**
	 * Parses a "messages moved out" event.
	 * @param eventsByType the complete list of events pushed to us by the
	 * web socket. This parameter must be mutable because this method will
	 * remove items from it to indicate that they shouldn't be processed by
	 * another event handler.
	 * @return the event to fire on our end or null if the given map does
	 * not contain any "message moved" events
	 */
	public static MessagesMovedEvent messagesMovedOut(Map<WebSocketEventType, List<JsonNode>> eventsByType) {
		var moveEvents = eventsByType.remove(WebSocketEventType.MESSAGE_MOVED_OUT);
		if (moveEvents.isEmpty()) {
			return null;
		}

		var builder = new MessagesMovedEvent.Builder();

		//@formatter:off
		builder.messages(moveEvents.stream()
			.map(WebSocketEventParsers::extractChatMessage)
		.toList());
		//@formatter:on

		/*
		 * When messages are moved, the chat system posts a new message
		 * under the name of the user who moved the messages. This causes a
		 * "new message" event to be posted. The content of this message
		 * contains the ID and name of the room that the messages were moved
		 * to.
		 */
		var messagePostedEvents = eventsByType.get(WebSocketEventType.MESSAGE_POSTED);

		JsonNode matchingEvent = null;
		for (var event : messagePostedEvents) {
			var messagePostedEvent = WebSocketEventParsers.messagePosted(event);
			var message = messagePostedEvent.getMessage();

			var m = messagesMovedOutRegex.matcher(message.content().getContent());
			if (!m.find()) {
				continue;
			}

			//@formatter:off
			builder
			.destRoomId(Integer.parseInt(m.group(1)))
			.destRoomName(m.group(2))
			.sourceRoomId(message.roomId())
			.sourceRoomName(message.roomName())
			.moverUserId(message.userId())
			.moverUsername(message.username())
			.eventId(messagePostedEvent.getEventId())
			.timestamp(messagePostedEvent.getTimestamp());
			//@formatter:on

			matchingEvent = event;

			break;
		}

		/*
		 * Remove the "new message" event so it is not processed again as a
		 * normal message. The event cannot be removed from within the
		 * foreach loop because a "concurrent modification" exception will
		 * be thrown.
		 */
		if (matchingEvent != null) {
			messagePostedEvents.remove(matchingEvent);
		}

		return builder.build();
	}

	/*
	 * When messages are moved into a room, the chat system posts a new
	 * message under the name of the user who moved the messages. The
	 * content of this message contains the ID and name of the room that the
	 * messages were moved from. This regex parses that content.
	 */
	private static final Pattern messagesMovedInRegex = Pattern.compile("^&larr; <i>\\d+ messages? moved from <a href=\".*?/rooms/(\\d+)/.*?\">(.*?)</a></i>$");

	/**
	 * Parses a "messages moved in" event.
	 * @param eventsByType the complete list of events pushed to us by the
	 * web socket. This parameter must be mutable because this method will
	 * remove items from it to indicate that they shouldn't be processed by
	 * another event handler.
	 * @return the event to fire on our end or null if the given map does
	 * not contain any "message moved" events
	 */
	public static MessagesMovedEvent messagesMovedIn(Map<WebSocketEventType, List<JsonNode>> eventsByType) {
		var moveEvents = eventsByType.remove(WebSocketEventType.MESSAGE_MOVED_IN);
		if (moveEvents.isEmpty()) {
			return null;
		}

		var builder = new MessagesMovedEvent.Builder();
		
		builder.messages(moveEvents.stream()
			.map(WebSocketEventParsers::extractChatMessage)
		.toList());

		/*
		 * When messages are moved, the chat system posts a new message
		 * under the name of the user who moved the messages. This causes a
		 * "new message" event to be posted. The content of this message
		 * contains the ID and name of the room that the messages were moved
		 * from.
		 */
		var messagePostedEvents = eventsByType.get(WebSocketEventType.MESSAGE_POSTED);
		JsonNode matchingEvent = null;
		for (var event : messagePostedEvents) {
			var messagePostedEvent = WebSocketEventParsers.messagePosted(event);
			var message = messagePostedEvent.getMessage();

			var m = messagesMovedInRegex.matcher(message.content().getContent());
			if (!m.find()) {
				continue;
			}

			//@formatter:off
			builder
			.sourceRoomId(Integer.parseInt(m.group(1)))
			.sourceRoomName(m.group(2))
			.destRoomId(message.roomId())
			.destRoomName(message.roomName())
			.moverUserId(message.userId())
			.moverUsername(message.username())
			.eventId(messagePostedEvent.getEventId())
			.timestamp(messagePostedEvent.getTimestamp());
			//@formatter:on

			matchingEvent = event;

			break;
		}

		/*
		 * Remove the "new message" event so it is not processed again as a
		 * normal message. The event cannot be removed from within the
		 * foreach loop because a "concurrent modification" exception will
		 * be thrown.
		 */
		if (matchingEvent != null) {
			messagePostedEvents.remove(matchingEvent);
		}

		return builder.build();
	}

	/**
	 * Parses any "reply" events that were pushed to us by the web socket.
	 * @param eventsByType the complete list of events pushed to us by the
	 * web socket. This parameter must be mutable because this method will
	 * remove items from it to indicate that they shouldn't be processed by
	 * another event handler.
	 * @return the events to fire on our end. This list will consist of
	 * {@link MessagePostedEvent} and {@link MessageEditedEvent} objects.
	 */
	public static List<Event> reply(Map<WebSocketEventType, List<JsonNode>> eventsByType) {
		var events = new ArrayList<Event>();

		var newMessageEvents = eventsByType.get(WebSocketEventType.MESSAGE_POSTED);
		var editedMessageEvents = eventsByType.get(WebSocketEventType.MESSAGE_EDITED);
		var replyEvents = eventsByType.remove(WebSocketEventType.REPLY_POSTED);

		for (var replyEvent : replyEvents) {
			var message = extractChatMessage(replyEvent);

			var value = replyEvent.get("id");
			long eventId = (value == null) ? 0 : value.asLong();

			/*
			 * Whenever a "reply" event is posted, an accompanying
			 * "new message" or "message edited" event is also posted. This
			 * event has less information than the "reply" event, so ignore
			 * it. But we need to know whether a "new message" or
			 * "message edited" event was fired so we know what kind of
			 * event to fire on our end.
			 */

			var event = findMessageWithId(newMessageEvents, message.messageId());
			if (event != null) {
				newMessageEvents.remove(event);

				//@formatter:off
				events.add(new MessagePostedEvent.Builder()
					.message(message)
					.eventId(eventId)
					.timestamp(message.timestamp())
					.build()
				);
				//@formatter:on

				continue;
			}

			event = findMessageWithId(editedMessageEvents, message.messageId());
			if (event != null) {
				editedMessageEvents.remove(event);

				//@formatter:off
				events.add(new MessageEditedEvent.Builder()
					.message(message)
					.eventId(eventId)
					.timestamp(message.timestamp())
					.build()
				);
				//@formatter:on

				continue;
			}

			/*
			 * If an accompanying "new message" or "message edited" event is
			 * not found, it means that the "reply" event is from another
			 * room, so ignore it.
			 */
		}

		return events;
	}

	/**
	 * Parses any "user mentioned" events that were pushed to us by the web
	 * socket.
	 * @param eventsByType the complete list of events pushed to us by the
	 * web socket. This parameter must be mutable because this method will
	 * remove items from it to indicate that they shouldn't be processed by
	 * another event handler.
	 * @return the events to fire on our end. This list will consist of
	 * {@link MessagePostedEvent} and {@link MessageEditedEvent} objects.
	 */
	public static List<Event> mention(Map<WebSocketEventType, List<JsonNode>> eventsByType) {
		var events = new ArrayList<Event>();

		var newMessageEvents = eventsByType.get(WebSocketEventType.MESSAGE_POSTED);
		var editedMessageEvents = eventsByType.get(WebSocketEventType.MESSAGE_EDITED);
		var mentionEvents = eventsByType.remove(WebSocketEventType.USER_MENTIONED);

		for (var mentionEvent : mentionEvents) {
			var message = extractChatMessage(mentionEvent);

			var value = mentionEvent.get("id");
			var eventId = (value == null) ? 0 : value.asLong();

			/*
			 * Whenever a "user mentioned" event is posted, an accompanying
			 * "new message" or "message edited" event is also posted. This
			 * event has less information than the "user mentioned" event,
			 * so ignore it. But we need to know whether a "new message" or
			 * "message edited" event was fired so we know what kind of
			 * event to fire on our end.
			 */

			var event = findMessageWithId(newMessageEvents, message.messageId());
			if (event != null) {
				newMessageEvents.remove(event);

				//@formatter:off
				events.add(new MessagePostedEvent.Builder()
					.eventId(eventId)
					.timestamp(message.timestamp())
					.message(message)
					.build()
				);
				//@formatter:on
			}

			event = findMessageWithId(editedMessageEvents, message.messageId());
			if (event != null) {
				editedMessageEvents.remove(event);

				//@formatter:off
				events.add(new MessageEditedEvent.Builder()
					.eventId(eventId)
					.timestamp(message.timestamp())
					.message(message)
					.build()
				);
				//@formatter:on
			}
		}

		return events;
	}

	private static JsonNode findMessageWithId(Collection<JsonNode> events, long id) {
		//@formatter:off
		return events.stream()
			.filter(event -> {
				JsonNode value = event.get("message_id");
				return (value != null) && (value.asLong() == id);
			})
		.findFirst().orElse(null);
		//@formatter:on
	}

	private static void extractCommonEventFields(JsonNode element, Event.Builder<?, ?> builder) {
		var value = element.get("id");
		if (value != null) {
			builder.eventId(value.asLong());
		}

		value = element.get("time_stamp");
		if (value != null) {
			builder.timestamp(timestamp(value.asLong()));
		}
	}

	/**
	 * Parses a {@link ChatMessage} object from the given JSON node.
	 * @param element the JSON node
	 * @return the parsed chat message
	 */
	public static ChatMessage extractChatMessage(JsonNode element) {
		var builder = new ChatMessage.Builder();

		var value = element.get("message_id");
		if (value != null) {
			builder.messageId(value.asLong());
		}

		value = element.get("time_stamp");
		if (value != null) {
			LocalDateTime ts = timestamp(value.asLong());
			builder.timestamp(ts);
		}

		value = element.get("room_id");
		if (value != null) {
			builder.roomId(value.asInt());
		}

		value = element.get("room_name");
		if (value != null) {
			builder.roomName(value.asText());
		}

		/*
		 * This field is not present for "message starred" events.
		 */
		value = element.get("user_id");
		if (value != null) {
			builder.userId(value.asInt());
		}

		/*
		 * This field is not present for "message starred" events.
		 */
		value = element.get("user_name");
		if (value != null) {
			builder.username(value.asText());
		}

		/*
		 * This field is only present if the message has been edited.
		 */
		value = element.get("message_edits");
		if (value != null) {
			builder.edits(value.asInt());
		}

		/*
		 * This field is only present if the message has been starred.
		 */
		value = element.get("message_stars");
		if (value != null) {
			builder.stars(value.asInt());
		}

		/*
		 * This field is only present when the message is a reply to another
		 * message.
		 */
		value = element.get("parent_id");
		if (value != null) {
			builder.parentMessageId(value.asLong());
		}

		/*
		 * This field is only present if the message contains a valid
		 * mention or if the message is a reply to another message.
		 */
		value = element.get("target_user_id");
		if (value != null) {
			builder.mentionedUserId(value.asInt());
		}

		/*
		 * This field is not present for "message deleted" events.
		 */
		value = element.get("content");
		if (value != null && !value.isNull()) {
			builder.content(Content.parse(value.asText()));
		}

		return builder.build();
	}

	/**
	 * Converts a timestamp to a {@link LocalDateTime} instance.
	 * @param ts the timestamp (seconds since epoch)
	 * @return the {@link LocalDateTime} instance
	 */
	public static LocalDateTime timestamp(long ts) {
		var instant = Instant.ofEpochSecond(ts);
		return LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
	}

	private WebSocketEventParsers() {
		//hide
	}
}
