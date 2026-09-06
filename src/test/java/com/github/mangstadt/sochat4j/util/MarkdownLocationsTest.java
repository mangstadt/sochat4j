package com.github.mangstadt.sochat4j.util;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import org.junit.jupiter.api.Test;

/**
 * @author Michael Angstadt
 */
class MarkdownLocationsTest {
	@Test
	void find() {
		//               ----- --------- ---      --------------------------         -----------                          -----------------
		var input = "one *two* **three** \\* four [five](https://google.com) ] seven [tag:eight] \\a [just some brackets] `code * \\` code`";
		var actual = new MarkdownLocations(input).find();

		var expected = new boolean[input.length()];
		setTrue(expected, 4, 8);
		setTrue(expected, 10, 18);
		setTrue(expected, 20, 21);
		setTrue(expected, 28, 53);
		setTrue(expected, 63, 73);
		setTrue(expected, 99, 114);

		assertArrayEquals(expected, actual);
	}

	static void setTrue(boolean[] array, int start, int endInclusive) {
		for (var i = start; i <= endInclusive; i++) {
			array[i] = true;
		}
	}
}
