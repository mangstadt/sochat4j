package com.github.mangstadt.sochat4j.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.time.Duration;

import org.apache.http.HttpVersion;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.message.BasicStatusLine;
import org.apache.http.util.EntityUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.github.mangstadt.sochat4j.util.Http.RateLimitHandler;
import com.github.mangstadt.sochat4j.util.Http.Response;

/**
 * @author Michael Angstadt
 */
@SuppressWarnings("resource")
class HttpTest {
	@BeforeEach
	void before() {
		Sleeper.startUnitTest();
	}

	@AfterEach
	void after() {
		Sleeper.endUnitTest();
	}

	@Test
	void response_plaintext() throws Exception {
		var r = mockResponse(200, "The body");
		var client = mock(CloseableHttpClient.class);
		when(client.execute(any(HttpUriRequest.class))).thenReturn(r);

		var http = new Http(client);

		var response = http.get("uri");
		assertEquals(200, response.getStatusCode());
		assertEquals("The body", response.getBody());
		assertThrows(JsonProcessingException.class, () -> response.getBodyAsJson());
		assertNotNull(response.getBodyAsHtml());
	}

	@Test
	void response_json() throws Exception {
		var r = mockResponse(200, "{}");
		var client = mock(CloseableHttpClient.class);
		when(client.execute(any(HttpUriRequest.class))).thenReturn(r);

		var http = new Http(client);

		var response = http.get("uri");
		assertEquals(200, response.getStatusCode());
		assertEquals("{}", response.getBody());
		assertNotNull(response.getBodyAsJson());
		assertNotNull(response.getBodyAsHtml());
	}

	@Test
	void response_html() throws Exception {
		var r = mockResponse(200, "<html><a href=\"foo.html\">link</a></html>");
		var client = mock(CloseableHttpClient.class);
		when(client.execute(any(HttpUriRequest.class))).thenReturn(r);

		var http = new Http(client);

		var response = http.get("http://www.example.com/test/index.html");
		assertEquals(200, response.getStatusCode());
		assertEquals("<html><a href=\"foo.html\">link</a></html>", response.getBody());
		assertThrows(JsonProcessingException.class, () -> response.getBodyAsJson());

		/*
		 * Make sure it resolves relative URLs.
		 */
		var document = response.getBodyAsHtml();
		var link = document.select("a").first();
		assertEquals("http://www.example.com/test/foo.html", link.absUrl("href"));
	}

	/**
	 * Vararg parameter must have an even number of arguments.
	 */
	@Test
	void post_parameters_odd() throws Exception {
		var http = new Http(mock(CloseableHttpClient.class));
		assertThrows(IllegalArgumentException.class, () -> http.post("uri", "one"));
	}

	/**
	 * Vararg parameter may be empty.
	 */
	@Test
	void post_parameters_none() throws Exception {
		var client = mock(CloseableHttpClient.class);
		when(client.execute(any(HttpUriRequest.class))).then(invocation -> {
			var request = (HttpPost) invocation.getArguments()[0];
			assertNull(request.getEntity());
			return mockResponse(200, "");
		});

		var http = new Http(client);
		http.post("uri");
		verify(client).execute(any(HttpUriRequest.class));
	}

	/**
	 * Parameter names cannot be null.
	 */
	@Test
	void post_parameters_null_name() throws Exception {
		var http = new Http(mock(CloseableHttpClient.class));
		assertThrows(NullPointerException.class, () -> http.post("uri", (String) null, "value"));
	}

	/**
	 * Parameter values may be null.
	 */
	@Test
	void post_parameters_null_value() throws Exception {
		var client = mock(CloseableHttpClient.class);
		when(client.execute(any(HttpUriRequest.class))).then(invocation -> {
			var request = (HttpPost) invocation.getArguments()[0];
			var body = EntityUtils.toString(request.getEntity());
			assertEquals("one=null", body);

			return mockResponse(200, "");
		});

		var http = new Http(client);
		http.post("uri", "one", null);
		verify(client).execute(any(HttpUriRequest.class));
	}

	@Test
	void rate_limit_handler() throws Exception {
		var response1 = mockResponse(400, "");
		var response2 = mockResponse(200, "");

		var client = mock(CloseableHttpClient.class);
		when(client.execute(any(HttpUriRequest.class))).thenReturn(response1, response2);

		var rateLimitHandler = mock(RateLimitHandler.class);
		when(rateLimitHandler.isRateLimited(any(Response.class))).thenReturn(true, false);
		when(rateLimitHandler.getMaxAttempts()).thenReturn(3);
		when(rateLimitHandler.getWaitTime(any(Response.class))).thenReturn(Duration.ofSeconds(2));

		var http = new Http(client);
		http.post("uri", rateLimitHandler);
		assertEquals(2000, Sleeper.getTimeSlept());
	}

	@Test
	void rate_limit_handler_give_up() throws Exception {
		var response1 = mockResponse(400, "");
		var response2 = mockResponse(400, "");
		var response3 = mockResponse(400, "");

		var client = mock(CloseableHttpClient.class);
		when(client.execute(any(HttpUriRequest.class))).thenReturn(response1, response2, response3);

		var rateLimitHandler = mock(RateLimitHandler.class);
		when(rateLimitHandler.isRateLimited(any(Response.class))).thenReturn(true, true, true);
		when(rateLimitHandler.getMaxAttempts()).thenReturn(3);
		when(rateLimitHandler.getWaitTime(any(Response.class))).thenReturn(Duration.ofSeconds(2));

		var http = new Http(client);
		assertThrows(IOException.class, () -> http.post("uri", rateLimitHandler));
		assertEquals(4000, Sleeper.getTimeSlept());
	}

	private static CloseableHttpResponse mockResponse(int statusCode, String body) throws Exception {
		var response = mock(CloseableHttpResponse.class);
		when(response.getStatusLine()).thenReturn(new BasicStatusLine(HttpVersion.HTTP_1_1, statusCode, ""));
		when(response.getEntity()).thenReturn(new StringEntity(body));
		return response;
	}
}
