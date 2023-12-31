package com.github.mangstadt.sochat4j.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;
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
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.github.mangstadt.sochat4j.util.Http.RateLimitHandler;
import com.github.mangstadt.sochat4j.util.Http.Response;

/**
 * @author Michael Angstadt
 */
@SuppressWarnings("resource")
public class HttpTest {
	@Before
	public void before() {
		Sleeper.startUnitTest();
	}

	@After
	public void after() {
		Sleeper.endUnitTest();
	}

	@Test
	public void response_plaintext() throws Exception {
		CloseableHttpResponse r = mockResponse(200, "The body");
		CloseableHttpClient client = mock(CloseableHttpClient.class);
		when(client.execute(any(HttpUriRequest.class))).thenReturn(r);

		Http http = new Http(client);

		Http.Response response = http.get("uri");
		assertEquals(200, response.getStatusCode());
		assertEquals("The body", response.getBody());
		try {
			response.getBodyAsJson();
			fail();
		} catch (JsonProcessingException e) {
			//expected
		}
		assertNotNull(response.getBodyAsHtml());
	}

	@Test
	public void response_json() throws Exception {
		CloseableHttpResponse r = mockResponse(200, "{}");
		CloseableHttpClient client = mock(CloseableHttpClient.class);
		when(client.execute(any(HttpUriRequest.class))).thenReturn(r);

		Http http = new Http(client);

		Http.Response response = http.get("uri");
		assertEquals(200, response.getStatusCode());
		assertEquals("{}", response.getBody());
		assertNotNull(response.getBodyAsJson());
		assertNotNull(response.getBodyAsHtml());
	}

	@Test
	public void response_html() throws Exception {
		CloseableHttpResponse r = mockResponse(200, "<html><a href=\"foo.html\">link</a></html>");
		CloseableHttpClient client = mock(CloseableHttpClient.class);
		when(client.execute(any(HttpUriRequest.class))).thenReturn(r);

		Http http = new Http(client);

		Http.Response response = http.get("http://www.example.com/test/index.html");
		assertEquals(200, response.getStatusCode());
		assertEquals("<html><a href=\"foo.html\">link</a></html>", response.getBody());
		try {
			response.getBodyAsJson();
			fail();
		} catch (JsonProcessingException e) {
			//expected
		}

		/*
		 * Make sure it resolves relative URLs.
		 */
		Document document = response.getBodyAsHtml();
		Element link = document.select("a").first();
		assertEquals("http://www.example.com/test/foo.html", link.absUrl("href"));
	}

	/**
	 * Vararg parameter must have an even number of arguments.
	 */
	@Test(expected = IllegalArgumentException.class)
	public void post_parameters_odd() throws Exception {
		Http http = new Http(mock(CloseableHttpClient.class));
		http.post("uri", "one");
	}

	/**
	 * Vararg parameter may be empty.
	 */
	@Test
	public void post_parameters_none() throws Exception {
		CloseableHttpClient client = mock(CloseableHttpClient.class);
		when(client.execute(any(HttpUriRequest.class))).then(invocation -> {
			HttpPost request = (HttpPost) invocation.getArguments()[0];
			assertNull(request.getEntity());
			return mockResponse(200, "");
		});

		Http http = new Http(client);
		http.post("uri");
		verify(client).execute(any(HttpUriRequest.class));
	}

	/**
	 * Parameter names cannot be null.
	 */
	@Test(expected = NullPointerException.class)
	public void post_parameters_null_name() throws Exception {
		Http http = new Http(mock(CloseableHttpClient.class));
		http.post("uri", (String) null, "value");
	}

	/**
	 * Parameter values may be null.
	 */
	@Test
	public void post_parameters_null_value() throws Exception {
		CloseableHttpClient client = mock(CloseableHttpClient.class);
		when(client.execute(any(HttpUriRequest.class))).then(invocation -> {
			HttpPost request = (HttpPost) invocation.getArguments()[0];
			String body = EntityUtils.toString(request.getEntity());
			assertEquals("one=null", body);

			return mockResponse(200, "");
		});

		Http http = new Http(client);
		http.post("uri", "one", null);
		verify(client).execute(any(HttpUriRequest.class));
	}

	@Test
	public void rate_limit_handler() throws Exception {
		CloseableHttpResponse response1 = mockResponse(400, "");
		CloseableHttpResponse response2 = mockResponse(200, "");

		CloseableHttpClient client = mock(CloseableHttpClient.class);
		when(client.execute(any(HttpUriRequest.class))).thenReturn(response1, response2);

		RateLimitHandler rateLimitHandler = mock(RateLimitHandler.class);
		when(rateLimitHandler.isRateLimited(any(Response.class))).thenReturn(true, false);
		when(rateLimitHandler.getMaxAttempts()).thenReturn(3);
		when(rateLimitHandler.getWaitTime(any(Response.class))).thenReturn(Duration.ofSeconds(2));

		Http http = new Http(client);
		http.post("uri", rateLimitHandler);
		assertEquals(2000, Sleeper.getTimeSlept());
	}

	@Test
	public void rate_limit_handler_give_up() throws Exception {
		CloseableHttpResponse response1 = mockResponse(400, "");
		CloseableHttpResponse response2 = mockResponse(400, "");
		CloseableHttpResponse response3 = mockResponse(400, "");

		CloseableHttpClient client = mock(CloseableHttpClient.class);
		when(client.execute(any(HttpUriRequest.class))).thenReturn(response1, response2, response3);

		RateLimitHandler rateLimitHandler = mock(RateLimitHandler.class);
		when(rateLimitHandler.isRateLimited(any(Response.class))).thenReturn(true, true, true);
		when(rateLimitHandler.getMaxAttempts()).thenReturn(3);
		when(rateLimitHandler.getWaitTime(any(Response.class))).thenReturn(Duration.ofSeconds(2));

		Http http = new Http(client);

		try {
			http.post("uri", rateLimitHandler);
			fail();
		} catch (IOException e) {
			//expected
		}
		assertEquals(4000, Sleeper.getTimeSlept());
	}

	private static CloseableHttpResponse mockResponse(int statusCode, String body) throws Exception {
		CloseableHttpResponse response = mock(CloseableHttpResponse.class);
		when(response.getStatusLine()).thenReturn(new BasicStatusLine(HttpVersion.HTTP_1_1, statusCode, ""));
		when(response.getEntity()).thenReturn(new StringEntity(body));
		return response;
	}
}
