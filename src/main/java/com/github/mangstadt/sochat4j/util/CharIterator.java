package com.github.mangstadt.sochat4j.util;

/**
 * Iterates over the characters in a String. Provides additional functionality
 * over {@link String#chars}.
 * @author Michael Angstadt
 */
public class CharIterator {
	private final String s;
	private int i = -1;

	/**
	 * @param s the string to iterate over
	 */
	public CharIterator(String s) {
		this.s = s;
	}

	/**
	 * Determines if there are more characters to iterate over.
	 * @return true if there are more characters, false if not
	 */
	public boolean hasNext() {
		return i + 1 < s.length();
	}

	/**
	 * Advances to the next character.
	 * @return the next character
	 */
	public char next() {
		return s.charAt(++i);
	}

	/**
	 * Gets the next character without advancing.
	 * @return the next character
	 */
	public char peek() {
		return hasNext() ? s.charAt(i + 1) : 0;
	}

	/**
	 * Gets the next set of characters without advancing.
	 * @param length the number of characters to get
	 * @return the next characters
	 */
	public String peek(int length) {
		if (!hasNext()) {
			return "";
		}

		int start = i + 1;
		int end = start + length;
		if (end > s.length()) {
			end = s.length();
		}
		return s.substring(start, end);
	}

	/**
	 * Gets the index of the current character in the string.
	 * @return the index
	 */
	public int index() {
		return i;
	}
}
