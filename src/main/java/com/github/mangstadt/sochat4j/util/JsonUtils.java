package com.github.mangstadt.sochat4j.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;

/**
 * JSON utility methods.
 * @author Michael Angstadt
 */
public final class JsonUtils {
	private static final ObjectMapper mapper = new ObjectMapper();

	/**
	 * Pretty prints the given JSON node.
	 * @param node the JSON node
	 * @return the pretty-printed JSON
	 */
	public static String prettyPrint(JsonNode node) {
		ObjectWriter writer = mapper.writer(new DefaultPrettyPrinter());
		try {
			return writer.writeValueAsString(node);
		} catch (JsonProcessingException e) {
			//should never be thrown
			throw new RuntimeException(e);
		}
	}

	private JsonUtils() {
		//hide constructor
	}
}
