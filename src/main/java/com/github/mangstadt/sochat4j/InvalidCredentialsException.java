package com.github.mangstadt.sochat4j;

/**
 * Thrown when the login credentials for connecting to a chat system are not
 * accepted.
 * @author Michael Angstadt
 * @see ChatClient#login(String, String)
 */
@SuppressWarnings("serial")
public class InvalidCredentialsException extends RuntimeException {
	public InvalidCredentialsException() {
		super("Login credentials were rejected.");
	}
}
