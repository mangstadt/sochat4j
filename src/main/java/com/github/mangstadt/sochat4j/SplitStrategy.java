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
			var markdownLocations = markdownLocations(message);

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
		 * Figures out where it's *not* safe to split the string.
		 * @param message the message
		 * @return a boolean array representing each character in the string. If
		 * an element is false, that means it is not safe to split at that
		 * location
		 */
		private boolean[] markdownLocations(String message) {
			var inBold = false;
			var inItalic = false;
			var inCode = false;
			var inTag = false;
			var inLink = false;
			var inMarkdown = new boolean[message.length()];

			for (var i = 0; i < message.length(); i++) {
				var cur = message.charAt(i);
				var next = (i == message.length() - 1) ? 0 : message.charAt(i + 1);

				var skipAheadOne = false;
				switch (cur) {
				case '\\' -> skipAheadOne = (inCode && next == '`') || (!inCode && isSpecialChar(next));
				case '`' -> inCode = !inCode;
				case '*' -> {
					if (!inCode) {
						if (next == '*') {
							inBold = !inBold;
							skipAheadOne = true;
						} else {
							inItalic = !inItalic;
						}
					}
				}
				case '[' -> {
					if (!inCode) {
						if (i < message.length() - 4 && message.substring(i + 1, i + 5).equals("tag:")) {
							inTag = true;
						}
						inLink = true;
					}
				}
				case ']' -> {
					if (inLink) {
						if (next != '(') {
							//it's not a link, just some brackets!
							inLink = false;
						}
					}
					if (inTag) {
						inTag = false;
					}
				}
				case ')' -> inLink = false; //assumes there are no parens in the URL or title string
				default -> {
					//not a markdown character
				}
				}

				if (skipAheadOne) {
					inMarkdown[i] = inMarkdown[i + 1] = true;
					i++;
				} else {
					inMarkdown[i] = (inBold || inItalic || inCode || inLink || inTag);
				}
			}
			return inMarkdown;
		}

		private boolean isSpecialChar(char c) {
			/*
			 * I don't escape () or _ in DescriptionNodeVisitor, so I'm not
			 * going to treat these characters as escapable.
			 */
			return "`*[]".indexOf(c) >= 0;
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
