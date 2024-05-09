package com.github.mangstadt.sochat4j;

import static org.junit.Assert.assertEquals;

import java.util.List;

import org.junit.Test;

/**
 * @author Michael Angstadt
 */
public class SplitStrategyTest {
	@Test
	public void no_max_length() {
		for (SplitStrategy strategy : SplitStrategy.values()) {
			var actual = strategy.split("message", -1);
			var expected = List.of("message");
			assertEquals(expected, actual);
		}
	}

	@Test
	public void message_does_not_exceed_max_length() {
		for (SplitStrategy strategy : SplitStrategy.values()) {
			var actual = strategy.split("message", 50);
			var expected = List.of("message");
			assertEquals(expected, actual);
		}
	}

	@Test
	public void word() {
		var actual = SplitStrategy.WORD.split("Java is a general-purpose computer programming language that is concurrent, class-based, object-oriented, and specifically designed to have as few implementation dependencies as possible.", 100);
		//@formatter:off
		var expected = List.of(
			"Java is a general-purpose computer programming language that is concurrent, class-based, ...",
			"object-oriented, and specifically designed to have as few implementation dependencies as ...",
			"possible."
		);
		//@formatter:on
		assertEquals(expected, actual);
	}

	@Test
	public void word_markdown() {
		var actual = SplitStrategy.WORD.split("Java is a general-purpose computer programming language that is concurrent, class-based, object-oriented, and specifically designed to have **as few implementation dependencies as possible**.", 100);
		//@formatter:off
		var expected = List.of(
			"Java is a general-purpose computer programming language that is concurrent, class-based, ...",
			"object-oriented, and specifically designed to have ...",
			"**as few implementation dependencies as possible**."
		);
		//@formatter:on
		assertEquals(expected, actual);
	}

	@Test
	public void word_with_markdown_section_that_exceeds_max_length() {
		var actual = SplitStrategy.WORD.split("Java is a general-purpose computer programming language that is concurrent, class-based, object-oriented, and specifically designed to have **as few implementation dependencies as possible**.", 30);
		//@formatter:off
		var expected = List.of(
			"Java is a general-purpose ...",
			"computer programming ...",
			"language that is ...",
			"concurrent, class-based, ...",
			"object-oriented, and ...",
			"specifically designed to ...",
			"have ...",
			"**as few implementation ...",
			"dependencies as ...",
			"possible**."
		);
		//@formatter:on
		assertEquals(expected, actual);
	}

	@Test
	public void newline() {
		var actual = SplitStrategy.NEWLINE.split("Java is a general-purpose computer programming language that is\nconcurrent, class-based, object-oriented, and specifically designed to have as few implementation dependencies as possible.", 100);
		//@formatter:off
		var expected = List.of(
			"Java is a general-purpose computer programming language that is",
			"concurrent, class-based, object-oriented, and specifically designed to have as few implementation de",
			"pendencies as possible."
		);
		//@formatter:on
		assertEquals(expected, actual);
	}
}
