package com.github.mangstadt.sochat4j;

import static java.util.Objects.requireNonNull;

/**
 * Represents a chat site.
 * @author Michael Angstadt
 */
public enum Site {
	//@formatter:off
	META("meta.stackexchange.com"),
	STACKEXCHANGE("stackexchange.com", "meta.stackexchange.com"),
	STACKOVERFLOW("stackoverflow.com");
	//@formatter:on

	private final String domain, loginDomain;

	/**
	 * @param domain the site's domain name (e.g. "stackoverflow.com")
	 */
	private Site(String domain) {
		this(domain, domain);
	}

	/**
	 * @param domain the site's domain name (e.g. "stackoverflow.com")
	 * @param loginDomain the domain of the login page
	 */
	private Site(String domain, String loginDomain) {
		this.domain = requireNonNull(domain);
		this.loginDomain = requireNonNull(loginDomain);
	}

	/**
	 * Gets the site's domain name.
	 * @return the domain name (e.g. "stackoverflow.com")
	 */
	public String getDomain() {
		return domain;
	}

	/**
	 * Gets the domain of the site's login page.
	 * @return the login domain (e.g. "stackoverflow.com")
	 */
	public String getLoginDomain() {
		return loginDomain;
	}

	/**
	 * Gets the domain of the site's chat service.
	 * @return the chat domain (e.g. "chat.stackoverflow.com")
	 */
	public String getChatDomain() {
		return "chat." + domain;
	}
}
