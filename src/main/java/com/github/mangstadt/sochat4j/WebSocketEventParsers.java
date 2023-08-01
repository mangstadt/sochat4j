package com.github.mangstadt.sochat4j;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.mangstadt.sochat4j.event.Event;
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
		MessagePostedEvent.Builder builder = new MessagePostedEvent.Builder();

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
		MessageEditedEvent.Builder builder = new MessageEditedEvent.Builder();

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
		MessageDeletedEvent.Builder builder = new MessageDeletedEvent.Builder();

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
		MessageStarredEvent.Builder builder = new MessageStarredEvent.Builder();

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
		UserEnteredEvent.Builder builder = new UserEnteredEvent.Builder();

		extractCommonEventFields(element, builder);

		JsonNode value = element.get("room_id");
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
		UserLeftEvent.Builder builder = new UserLeftEvent.Builder();

		extractCommonEventFields(element, builder);

		JsonNode value = element.get("room_id");
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
		Collection<JsonNode> moveEvents = eventsByType.remove(WebSocketEventType.MESSAGE_MOVED_OUT);
		if (moveEvents.isEmpty()) {
			return null;
		}

		MessagesMovedEvent.Builder builder = new MessagesMovedEvent.Builder();

		List<ChatMessage> messages = new ArrayList<>(moveEvents.size());
		for (JsonNode event : moveEvents) {
			ChatMessage message = extractChatMessage(event);
			messages.add(message);
		}
		builder.messages(messages);

		/*
		 * When messages are moved, the chat system posts a new message
		 * under the name of the user who moved the messages. This causes a
		 * "new message" event to be posted. The content of this message
		 * contains the ID and name of the room that the messages were moved
		 * to.
		 */
		Collection<JsonNode> messagePostedEvents = eventsByType.get(WebSocketEventType.MESSAGE_POSTED);
		JsonNode matchingEvent = null;
		for (JsonNode event : messagePostedEvents) {
			MessagePostedEvent messagePostedEvent = WebSocketEventParsers.messagePosted(event);
			ChatMessage message = messagePostedEvent.getMessage();

			Matcher m = messagesMovedOutRegex.matcher(message.getContent().getContent());
			if (!m.find()) {
				continue;
			}

			//@formatter:off
			builder
			.destRoomId(Integer.parseInt(m.group(1)))
			.destRoomName(m.group(2))
			.sourceRoomId(message.getRoomId())
			.sourceRoomName(message.getRoomName())
			.moverUserId(message.getUserId())
			.moverUsername(message.getUsername())
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
		Collection<JsonNode> moveEvents = eventsByType.remove(WebSocketEventType.MESSAGE_MOVED_IN);
		if (moveEvents.isEmpty()) {
			return null;
		}

		MessagesMovedEvent.Builder builder = new MessagesMovedEvent.Builder();

		List<ChatMessage> messages = new ArrayList<>(moveEvents.size());
		for (JsonNode event : moveEvents) {
			ChatMessage message = extractChatMessage(event);
			messages.add(message);
		}
		builder.messages(messages);

		/*
		 * When messages are moved, the chat system posts a new message
		 * under the name of the user who moved the messages. This causes a
		 * "new message" event to be posted. The content of this message
		 * contains the ID and name of the room that the messages were moved
		 * from.
		 */
		Collection<JsonNode> messagePostedEvents = eventsByType.get(WebSocketEventType.MESSAGE_POSTED);
		JsonNode matchingEvent = null;
		for (JsonNode event : messagePostedEvents) {
			MessagePostedEvent messagePostedEvent = WebSocketEventParsers.messagePosted(event);
			ChatMessage message = messagePostedEvent.getMessage();

			Matcher m = messagesMovedInRegex.matcher(message.getContent().getContent());
			if (!m.find()) {
				continue;
			}

			//@formatter:off
			builder
			.sourceRoomId(Integer.parseInt(m.group(1)))
			.sourceRoomName(m.group(2))
			.destRoomId(message.getRoomId())
			.destRoomName(message.getRoomName())
			.moverUserId(message.getUserId())
			.moverUsername(message.getUsername())
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
		List<Event> events = new ArrayList<>();

		Collection<JsonNode> newMessageEvents = eventsByType.get(WebSocketEventType.MESSAGE_POSTED);
		Collection<JsonNode> editedMessageEvents = eventsByType.get(WebSocketEventType.MESSAGE_EDITED);
		Collection<JsonNode> replyEvents = eventsByType.remove(WebSocketEventType.REPLY_POSTED);

		for (JsonNode replyEvent : replyEvents) {
			ChatMessage message = extractChatMessage(replyEvent);

			JsonNode value = replyEvent.get("id");
			long eventId = (value == null) ? 0 : value.asLong();

			/*
			 * Whenever a "reply" event is posted, an accompanying
			 * "new message" or "message edited" event is also posted. This
			 * event has less information than the "reply" event, so ignore
			 * it. But we need to know whether a "new message" or
			 * "message edited" event was fired so we know what kind of
			 * event to fire on our end.
			 */

			JsonNode event = findMessageWithId(newMessageEvents, message.getMessageId());
			if (event != null) {
				newMessageEvents.remove(event);

				//@formatter:off
				events.add(new MessagePostedEvent.Builder()
					.message(message)
					.eventId(eventId)
					.timestamp(message.getTimestamp())
					.build()
				);
				//@formatter:on

				continue;
			}

			event = findMessageWithId(editedMessageEvents, message.getMessageId());
			if (event != null) {
				editedMessageEvents.remove(event);

				//@formatter:off
				events.add(new MessageEditedEvent.Builder()
					.message(message)
					.eventId(eventId)
					.timestamp(message.getTimestamp())
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
		List<Event> events = new ArrayList<>();

		Collection<JsonNode> newMessageEvents = eventsByType.get(WebSocketEventType.MESSAGE_POSTED);
		Collection<JsonNode> editedMessageEvents = eventsByType.get(WebSocketEventType.MESSAGE_EDITED);
		Collection<JsonNode> mentionEvents = eventsByType.remove(WebSocketEventType.USER_MENTIONED);

		for (JsonNode mentionEvent : mentionEvents) {
			ChatMessage message = extractChatMessage(mentionEvent);

			JsonNode value = mentionEvent.get("id");
			long eventId = (value == null) ? 0 : value.asLong();

			/*
			 * Whenever a "user mentioned" event is posted, an accompanying
			 * "new message" or "message edited" event is also posted. This
			 * event has less information than the "user mentioned" event,
			 * so ignore it. But we need to know whether a "new message" or
			 * "message edited" event was fired so we know what kind of
			 * event to fire on our end.
			 */

			JsonNode event = findMessageWithId(newMessageEvents, message.getMessageId());
			if (event != null) {
				newMessageEvents.remove(event);

				//@formatter:off
				events.add(new MessagePostedEvent.Builder()
					.eventId(eventId)
					.timestamp(message.getTimestamp())
					.message(message)
					.build()
				);
				//@formatter:on
			}

			event = findMessageWithId(editedMessageEvents, message.getMessageId());
			if (event != null) {
				editedMessageEvents.remove(event);

				//@formatter:off
				events.add(new MessageEditedEvent.Builder()
					.eventId(eventId)
					.timestamp(message.getTimestamp())
					.message(message)
					.build()
				);
				//@formatter:on
			}
		}

		return events;
	}

	private static JsonNode findMessageWithId(Collection<JsonNode> events, long id) {
		for (JsonNode event : events) {
			JsonNode value = event.get("message_id");
			if (value == null) {
				continue;
			}

			if (id == value.asLong()) {
				return event;
			}
		}
		return null;
	}

	private static void extractCommonEventFields(JsonNode element, Event.Builder<?, ?> builder) {
		JsonNode value = element.get("id");
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
		ChatMessage.Builder builder = new ChatMessage.Builder();

		JsonNode value = element.get("message_id");
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
		Instant instant = Instant.ofEpochSecond(ts);
		return LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
	}

	private WebSocketEventParsers() {
		//hide
	}
}
