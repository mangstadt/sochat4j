package com.github.mangstadt.sochat4j;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * @author Michael Angstadt
 */
class ContentTest {
	@Test
	void getMentions() {
		assertMention("Hello, @Frank.", "Frank");
		assertMention("Hello, @Frank2Cool.", "Frank2Cool");
		assertMention("Hello@Frank.", "Frank");
		assertMention("Hello, @Frank", "Frank");
		assertMention("Hello, @@Frank", "Frank");
		assertMention("Hello, @Frank and @Robert", "Frank", "Robert");
		assertMention("Hello, @Fr an");
		assertMention("Hello.");
	}

	private static void assertMention(String message, String... expectedMentions) {
		var content = new Content(message, false);
		assertEquals(List.of(expectedMentions), content.getMentions());
	}

	@Test
	void isMentioned() {
		assertIsMentioned("Hello, @FrankSmi", "frank smith", true);
		assertIsMentioned("Hello", "frank smith", false);
		assertIsMentioned("Hello, @FrankSmi", "bob", false);
	}

	private static void assertIsMentioned(String message, String username, boolean expected) {
		var content = new Content(message, false);
		assertEquals(expected, content.isMentioned(username));
	}

	@Test
	void isOnebox() {
		var content = new Content("<div class=\"foooneboxbar\">onebox</div>", false);
		assertTrue(content.isOnebox());

		content = new Content("foobar", false);
		assertFalse(content.isOnebox());
	}

	@Test
	void parse() {
		var content = Content.parse("one\ntwo");
		assertFalse(content.isFixedWidthFont());
		assertEquals("one\ntwo", content.getContent());
		assertEquals("one\ntwo", content.getRawContent());

		content = Content.parse("<pre class='full'>one\ntwo</pre>");
		assertTrue(content.isFixedWidthFont());
		assertEquals("one\ntwo", content.getContent());
		assertEquals("<pre class='full'>one\ntwo</pre>", content.getRawContent());

		content = Content.parse("<pre class='partial'>one\ntwo</pre>");
		assertTrue(content.isFixedWidthFont());
		assertEquals("one\ntwo", content.getContent());
		assertEquals("<pre class='partial'>one\ntwo</pre>", content.getRawContent());

		content = Content.parse("<div class='full'>one <br> two</div>");
		assertFalse(content.isFixedWidthFont());
		assertEquals("one\ntwo", content.getContent());
		assertEquals("<div class='full'>one <br> two</div>", content.getRawContent());

		content = Content.parse("<div class='partial'>one <br> two</div>");
		assertFalse(content.isFixedWidthFont());
		assertEquals("one\ntwo", content.getContent());
		assertEquals("<div class='partial'>one <br> two</div>", content.getRawContent());
	}
}
