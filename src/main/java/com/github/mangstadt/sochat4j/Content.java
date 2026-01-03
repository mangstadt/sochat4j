package com.github.mangstadt.sochat4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Represents the message content of a chat message.
 * @author Michael Angstadt
 */
public class Content {
	private static final Pattern fixedWidthRegex = Pattern.compile("^<pre class='(full|partial)'>(.*?)</pre>$", Pattern.DOTALL);
	private static final Pattern multiLineRegex = Pattern.compile("^<div class='(full|partial)'>(.*?)</div>$", Pattern.DOTALL);
	private static final Predicate<String> oneboxRegex = Pattern.compile("^<div class=\"([^\"]*?)onebox([^\"]*?)\"[^>]*?>").asPredicate();

	private final String rawContent;
	private final String sanitizedContent;
	private final boolean fixedWidthFont;

	/**
	 * @param rawContent the message as it was received off the wire
	 * @param sanitizedContent a sanitized version of the raw message content,
	 * which should be used for most purposes (may contain HTML formatting)
	 * @param fixedWidthFont true if the message content is in fixed-width font,
	 * false if not
	 */
	private Content(String rawContent, String sanitizedContent, boolean fixedWidthFont) {
		this.rawContent = rawContent;
		this.sanitizedContent = sanitizedContent;
		this.fixedWidthFont = fixedWidthFont;
	}

	/**
	 * @param content the message content (may contain HTML formatting)
	 * @param fixedWidthFont true if the message content is in fixed-width font,
	 * false if not
	 */
	public Content(String content, boolean fixedWidthFont) {
		this(null, content, fixedWidthFont);
	}

	/**
	 * Parses the content from off the wire.
	 * @param rawContent the raw content from off the wire (may contain HTML)
	 * @return the parsed content
	 */
	public static Content parse(String rawContent) {
		var sanitizedContent = extractFixedWidthFontContent(rawContent);
		var fixedWidthFont = (sanitizedContent != null);
		if (!fixedWidthFont) {
			sanitizedContent = extractMultiLineContent(rawContent);
			var multiLine = (sanitizedContent != null);
			if (!multiLine) {
				sanitizedContent = rawContent;
			}
		}

		return new Content(rawContent, sanitizedContent, fixedWidthFont);
	}

	/**
	 * Gets the message content as it was retrieved directly off the wire. This
	 * method contrasts with the {@link #getContent} method, which contains a
	 * sanitized version of the raw content to make it more usable (see the
	 * source code of the {@link #parse} method for details on how the content
	 * is sanitized).
	 * @return the raw content or null if this object was not created from a
	 * parsed message
	 */
	public String getRawContent() {
		return rawContent;
	}

	/**
	 * Determines whether the message content is a onebox.
	 * @return true if the message content is a onebox, false if not
	 */
	public boolean isOnebox() {
		return oneboxRegex.test(sanitizedContent);
	}

	/**
	 * Determines whether the message content is formatted in a monospace font.
	 * @return true if it's formatted in a monospace font, false if not
	 */
	public boolean isFixedWidthFont() {
		return fixedWidthFont;
	}

	/**
	 * <p>
	 * Gets the message content, lightly-sanitized for usability. May contain
	 * basic HTML formatting. If you need the raw content as it was retrieved
	 * off the wire, use {@link #getRawContent}.
	 * </p>
	 * <p>
	 * Messages that consist of a single line of content may contain basic HTML
	 * formatting if the message author included any Markdown in the message.
	 * </p>
	 * <p>
	 * Messages that contain multiple lines of text will not contain any
	 * formatting because the chat system does not allow multi-lined messages to
	 * contain formatting.
	 * </p>
	 * <p>
	 * Messages that are formatted using a fixed-width font will not contain any
	 * formatting either. Fixed-width font messages may contain a single line
	 * of text or multiple lines of text. If a message is formatted in
	 * fixed-width font, the {@link #isFixedWidthFont} method will return true.
	 * </p>
	 * <p>
	 * Note that onebox messages will contain significant HTML code. Use the
	 * {@link #isOnebox} method to determine if the message is a onebox.
	 * </p>
	 * @return the message content
	 */
	public String getContent() {
		return sanitizedContent;
	}

	/**
	 * <p>
	 * Parses any mentions out of the message.
	 * </p>
	 * <p>
	 * "Mentioning" someone in chat will make a "ping" sound on the mentioned
	 * user's computer. A mention consists of an "at" symbol followed by a
	 * username. For example, this chat message contains two mentions:
	 * </p>
	 * 
	 * <pre>
	 * Good morning, {@literal @}Frank and {@literal @}Bob!
	 * </pre>
	 * 
	 * <p>
	 * Mentions cannot contain spaces, so if a username contains spaces, those
	 * spaces are removed from the mention.
	 * </p>
	 * <p>
	 * A mention does not have to contain a user's entire username. It may only
	 * contain the beginning of the username. For example, if someone's username
	 * is "Bob Smith", then typing "{@literal @}BobS" will ping that user.
	 * </p>
	 * <p>
	 * Because mentions can contain only part of a person's username, and
	 * because usernames are not unique on the platform, it's possible for a
	 * single mention to refer to multiple users.
	 * </p>
	 * <p>
	 * Mentions must be at least 3 characters long (not including the "at"
	 * symbol). Mentions less than 3 characters long are treated as normal text.
	 * </p>
	 * @return the mentions or empty list of none were found. The "at" symbol is
	 * not included in the returned output.
	 */
	public List<String> getMentions() {
		final var minLength = 3;
		var mentions = new ArrayList<String>(1);

		var inMention = false;
		var buffer = new StringBuilder();
		for (var i = 0; i < sanitizedContent.length(); i++) {
			var c = sanitizedContent.charAt(i);

			if (inMention) {
				if (Character.isLetter(c) || Character.isDigit(c)) {
					buffer.append(c);
					continue;
				}

				inMention = false;
				if (buffer.length() >= minLength) {
					mentions.add(buffer.toString());
				}
			}

			if (c == '@') {
				inMention = true;
				buffer.setLength(0);
				continue;
			}
		}

		if (inMention) {
			if (buffer.length() >= minLength) {
				mentions.add(buffer.toString());
			}
		}

		return mentions;
	}

	/**
	 * Determines if a user is mentioned in the message.
	 * @param username the username to look for
	 * @return true if the user is mentioned, false if not
	 */
	public boolean isMentioned(String username) {
		var mentions = getMentions();
		if (mentions.isEmpty()) {
			return false;
		}

		username = username.toLowerCase().replace(" ", "");

		//@formatter:off
		return mentions.stream()
			.map(String::toLowerCase)
		.anyMatch(username::startsWith);
		//@formatter:on
	}

	@Override
	public String toString() {
		return "[fixedWidthFont=" + fixedWidthFont + "] " + sanitizedContent;
	}

	@Override
	public int hashCode() {
		return Objects.hash(sanitizedContent, fixedWidthFont, rawContent);
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj) return true;
		if (obj == null) return false;
		if (getClass() != obj.getClass()) return false;
		var other = (Content) obj;
		return Objects.equals(sanitizedContent, other.sanitizedContent) && fixedWidthFont == other.fixedWidthFont && Objects.equals(rawContent, other.rawContent);
	}

	/**
	 * <p>
	 * Determines if a message is formatted in fixed-width font, and if so,
	 * sanitizes it.
	 * </p>
	 * <p>
	 * Fixed font messages are enclosed in a &lt;pre&gt; tag.
	 * </p>
	 * @param rawContent the raw message content as it was retrieved off the
	 * wire
	 * @return the sanitized message content or null if the message does not use
	 * fixed-width font
	 */
	private static String extractFixedWidthFontContent(String rawContent) {
		var m = fixedWidthRegex.matcher(rawContent);
		return m.find() ? m.group(2) : null;
	}

	/**
	 * <p>
	 * Determines if a message is a multi-line message, and if so, sanitizes it.
	 * </p>
	 * <p>
	 * Multi-line messages are enclosed in a &lt;div&gt; tag and use &lt;br&gt;
	 * tags for newlines.
	 * </p>
	 * @param rawContent the raw message content as it was retrieved off the
	 * wire
	 * @return the sanitized message content or null if the message is not
	 * multi-line
	 */
	private static String extractMultiLineContent(String rawContent) {
		var m = multiLineRegex.matcher(rawContent);
		return m.find() ? m.group(2).replace(" <br> ", "\n") : null;
	}
}
