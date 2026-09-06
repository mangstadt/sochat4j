package com.github.mangstadt.sochat4j.util;

/**
 * Finds the locations of markdown formatting in a string to determine where
 * it's not safe to split the string.
 * @author Michael Angstadt
 */
public class MarkdownLocations {
	private final String message;
	private final CharIterator it;
	private final boolean[] inMarkdown;
	private boolean inBold;
	private boolean inItalic;
	private boolean inCode;
	private boolean inTag;
	private boolean inLink;
	private boolean skipNext;

	/**
	 * @param message the string containing markdown
	 */
	public MarkdownLocations(String message) {
		this.message = message;
		it = new CharIterator(message);
		inMarkdown = new boolean[message.length()];
	}

	/**
	 * Finds the locations of markdown formatting in a string.
	 * @return a boolean array representing each character in the string. If an
	 * element is false, that means it is not safe to split at that
	 * location
	 */
	public boolean[] find() {
		while (it.hasNext()) {
			var c = it.next();
			var result = processNext(c);
			inMarkdown[it.index()] = result;
		}

		return inMarkdown;
	}

	/**
	 * Checks the next character.
	 * @param c the next character
	 * @return true if the next character is inside of markdown-formatted
	 * content, false if not
	 */
	private boolean processNext(char c) {
		if (skipNext) {
			skipNext = false;
			return true;
		}

		return switch (c) {
		case '\\' -> processBackslash();
		case '`' -> processTilde();
		case '*' -> processAsterisk();
		case '[' -> processOpenBracket();
		case ']' -> processCloseBracket();
		case ')' -> processCloseParen();
		default -> inBold || inItalic || inCode || inLink || inTag;
		};
	}

	private boolean processBackslash() {
		var next = it.peek();
		skipNext = (inCode && next == '`') || (!inCode && isSpecialChar(next));
		return skipNext;
	}

	private boolean processTilde() {
		inCode = !inCode;
		return true;
	}

	private boolean processAsterisk() {
		if (!inCode) {
			if (it.peek() == '*') {
				inBold = !inBold;
				skipNext = true;
			} else {
				inItalic = !inItalic;
			}
		}

		return true;
	}

	private boolean processOpenBracket() {
		if (!inCode) {
			if (it.peek(4).equals("tag:")) {
				inTag = true;
			}

			inLink = true;
		}

		return true;
	}

	private boolean processCloseBracket() {
		if (inTag) {
			inTag = false;
			inLink = false;
			return true;
		}

		if (inLink) {
			if (it.peek() == '(') {
				return true;
			}

			//it's not a link, just some brackets, undo
			inLink = false;

			var i = it.index();
			do {
				i--;
				inMarkdown[i] = false;
			} while (message.charAt(i) != '[');

			return false;
		}

		return false;
	}

	private boolean processCloseParen() {
		//assumes there are no parens in the URL or title string
		if (inLink) {
			inLink = false;
			return true;
		}

		return false;
	}

	private boolean isSpecialChar(char c) {
		/*
		 * I don't escape () or _ in DescriptionNodeVisitor, so I'm not
		 * going to treat these characters as escapable.
		 */
		return "`*[]".indexOf(c) >= 0;
	}
}
