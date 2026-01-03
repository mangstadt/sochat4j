package com.github.mangstadt.sochat4j.util;

import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.PrettyPrinter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * JSON utility methods.
 * @author Michael Angstadt
 */
public final class JsonUtils {
	private static final ObjectMapper mapper = new ObjectMapper();

	/**
	 * Creates a new object node that's not attached to anything.
	 * @return the object node
	 */
	public static ObjectNode newObject() {
		return mapper.createObjectNode();
	}

	/**
	 * Creates a new array node that's not attached to anything.
	 * @return the array node
	 */
	public static ArrayNode newArray() {
		return mapper.createArrayNode();
	}

	/**
	 * Parses JSON from a string.
	 * @param string the string
	 * @return the parsed JSON
	 * @throws JsonProcessingException if there's a problem parsing the JSON
	 */
	public static JsonNode parse(String string) throws JsonProcessingException {
		return mapper.readTree(string);
	}

	/**
	 * Parses JSON from a string and binds it to an object.
	 * @param string the string
	 * @param clazz the class
	 * @return the parsed JSON
	 * @throws JsonProcessingException if there's a problem parsing the JSON
	 */
	public static <T> T parse(String string, Class<T> clazz) throws JsonProcessingException {
		return mapper.readValue(string, clazz);
	}

	/**
	 * Pretty prints the given JSON node.
	 * @param node the JSON node
	 * @return the pretty-printed JSON
	 */
	public static String prettyPrint(JsonNode node) {
		return toString(node, new DefaultPrettyPrinter());
	}

	/**
	 * Converts the given JSON node to a string.
	 * @param node the JSON node
	 * @return the JSON string
	 */
	public static String toString(JsonNode node) {
		return toString(node, null);
	}

	private static String toString(JsonNode node, PrettyPrinter pp) {
		var writer = mapper.writer(pp);
		try {
			return writer.writeValueAsString(node);
		} catch (JsonProcessingException e) {
			//should never be thrown
			throw new RuntimeException(e);
		}
	}

	/**
	 * Streams the elements of an array.
	 * @param array the array
	 * @return the stream
	 */
	public static Stream<JsonNode> streamArray(JsonNode array) {
		return StreamSupport.stream(array.spliterator(), false);
	}

	private JsonUtils() {
		//hide constructor
	}
}
