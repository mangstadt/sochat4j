package com.github.mangstadt.sochat4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import com.github.mangstadt.sochat4j.util.CharIterator;

/**
 * Defines how a chat message should be split up if it exceeds the max message
 * size.
 * @author Michael Angstadt
 */
public enum SplitStrategy {
	/**
	 * Split by word, appending an ellipsis onto the end of each part.
	 */
	WORD {
		@Override
		public List<String> _split(String message, int maxLength) {
			var markdownLocations = new MarkdownLocations(message).find();

			var ellipsis = " ...";
			maxLength -= ellipsis.length();

			var posts = new ArrayList<String>();
			var it = new WordSplitter(message, maxLength, markdownLocations);
			while (it.hasNext()) {
				var post = it.next();
				posts.add(it.hasNext() ? post + ellipsis : post);
			}
			return Collections.unmodifiableList(posts);
		}

		/**
		 * Splits a string into substrings of a given max length, avoiding
		 * splitting the string at certain locations.
		 */
		class WordSplitter implements Iterator<String> {
			private final String message;
			private final int maxLength;
			private final boolean[] badSplitLocations;
			private int leftBound = 0;

			/**
			 * @param message the message to split
			 * @param maxLength the max length each substring can be
			 * @param badSplitLocations the locations where the string should
			 * *not* be split. This array is equal in size to the string being
			 * split. If an element is false, that means it is not safe to split
			 * the string at that index
			 */
			public WordSplitter(String message, int maxLength, boolean[] badSplitLocations) {
				this.message = message;
				this.maxLength = maxLength;
				this.badSplitLocations = badSplitLocations;
			}

			@Override
			public boolean hasNext() {
				return leftBound < message.length();
			}

			@Override
			public String next() {
				if (!hasNext()) {
					throw new NoSuchElementException();
				}

				final String post;

				var charactersLeft = message.length() - leftBound;
				if (charactersLeft <= maxLength) {
					post = message.substring(leftBound);
					leftBound = message.length();
					return post;
				}

				var spacePos = message.lastIndexOf(' ', leftBound + maxLength);
				if (spacePos < leftBound) {
					spacePos = -1;
				}

				if (spacePos < 0) {
					post = message.substring(leftBound, leftBound + maxLength);
					leftBound += maxLength;
					return post;
				}

				//find a safe place to split the text so that text contained within markdown is not split up
				while (spacePos >= leftBound && badSplitLocations[spacePos]) {
					spacePos = message.lastIndexOf(' ', spacePos - 1);
				}

				if (spacePos < leftBound) {
					//the markdown section is too long and cannot be split, so just pretend that the markdown is not there
					spacePos = message.lastIndexOf(' ', leftBound + maxLength);
					if (spacePos < leftBound) {
						spacePos = -1;
					}

					if (spacePos < 0) {
						post = message.substring(leftBound, leftBound + maxLength);
						leftBound += maxLength;
						return post;
					}

					post = message.substring(leftBound, spacePos);
					leftBound = spacePos + 1;
					return post;
				}

				post = message.substring(leftBound, spacePos);
				leftBound = spacePos + 1;
				return post;
			}
		}
		
		/**
		 * Finds the locations of markdown formatting in a string to determine
		 * where it's not safe to split the string.
		 */
		class MarkdownLocations {
			private final CharIterator it;
			private final boolean[] inMarkdown;
			private boolean inBold;
			private boolean inItalic;
			private boolean inCode;
			private boolean inTag;
			private boolean inLink;
			private boolean skipAheadOne;

			public MarkdownLocations(String message) {
				inMarkdown = new boolean[message.length()];
				it = new CharIterator(message);
			}

			/**
			 * Finds the locations of markdown formatting in a string.
			 * @return a boolean array representing each character in the string. If
			 * an element is false, that means it is not safe to split at that
			 * location
			 */
			public boolean[] find() {
				while (it.hasNext()) {
					processNext();

					if (skipAheadOne) {
						inMarkdown[it.index()] = inMarkdown[it.index() + 1] = true;
						it.next();
					} else {
						inMarkdown[it.index()] = (inBold || inItalic || inCode || inLink || inTag);
					}
				}

				return inMarkdown;
			}

			private void processNext() {
				skipAheadOne = false;

				switch (it.next()) {
				case '\\' -> processBackslash();
				case '`' -> processTilde();
				case '*' -> processAsterisk();
				case '[' -> processOpenBracket();
				case ']' -> processCloseBracket();
				case ')' -> processCloseParen();
				default -> {
					//do nothing
				}
				}
			}

			private void processBackslash() {
				var next = it.peek();
				skipAheadOne = (inCode && next == '`') || (!inCode && isSpecialChar(next));
			}

			private void processTilde() {
				inCode = !inCode;
			}

			private void processAsterisk() {
				if (inCode) {
					return;
				}

				if (it.peek() == '*') {
					inBold = !inBold;
					skipAheadOne = true;
				} else {
					inItalic = !inItalic;
				}
			}

			private void processOpenBracket() {
				if (inCode) {
					return;
				}

				if (it.peek(4).equals("tag:")) {
					inTag = true;
				}

				inLink = true;
			}

			private void processCloseBracket() {
				if (inLink && it.peek() != '(') {
					//it's not a link, just some brackets
					inLink = false;
				}

				if (inTag) {
					inTag = false;
				}
			}

			private void processCloseParen() {
				//assumes there are no parens in the URL or title string
				inLink = false;
			}

			private boolean isSpecialChar(char c) {
				/*
				 * I don't escape () or _ in DescriptionNodeVisitor, so I'm not
				 * going to treat these characters as escapable.
				 */
				return "`*[]".indexOf(c) >= 0;
			}
		}
	},

	/**
	 * Split by newline.
	 */
	NEWLINE {
		@Override
		public List<String> _split(String message, int maxLength) {
			var it = new NewlineSplitter(message, maxLength);
			return stream(it).toList();
		}

		class NewlineSplitter implements Iterator<String> {
			private final String message;
			private final int maxLength;
			private int leftBound = 0;

			public NewlineSplitter(String message, int maxLength) {
				this.message = message;
				this.maxLength = maxLength;
			}

			@Override
			public boolean hasNext() {
				return leftBound < message.length();
			}

			@Override
			public String next() {
				if (!hasNext()) {
					throw new NoSuchElementException();
				}

				final String post;

				var charactersLeft = message.length() - leftBound;
				if (charactersLeft <= maxLength) {
					post = message.substring(leftBound);
					leftBound = message.length();
					return post;
				}

				var newlinePos = message.lastIndexOf('\n', leftBound + maxLength);
				if (newlinePos < leftBound) {
					newlinePos = -1;
				}

				if (newlinePos < 0) {
					post = message.substring(leftBound, leftBound + maxLength);
					leftBound += maxLength;
					return post;
				}

				post = message.substring(leftBound, newlinePos);
				leftBound = newlinePos + 1;
				return post;
			}
		}
	},

	/**
	 * Just truncate the message.
	 */
	NONE {
		@Override
		protected List<String> _split(String message, int maxLength) {
			return List.of(message.substring(0, maxLength));
		}
	};

	/**
	 * Splits a chat message into multiple parts
	 * @param message the message to split
	 * @param maxLength the max length a message part can be or &lt; 1 for no
	 * limit
	 * @return the split parts
	 */
	public List<String> split(String message, int maxLength) {
		if (maxLength < 1 || message.length() <= maxLength) {
			return List.of(message);
		}
		return _split(message, maxLength);
	}

	private static Stream<String> stream(Iterator<String> it) {
		return StreamSupport.stream(Spliterators.spliteratorUnknownSize(it, Spliterator.ORDERED), false);
	}

	protected abstract List<String> _split(String message, int maxLength);
}
