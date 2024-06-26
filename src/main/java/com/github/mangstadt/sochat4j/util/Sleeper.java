package com.github.mangstadt.sochat4j.util;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Use this class for when you want to call {@link Thread#sleep} in production,
 * but not while running unit tests.
 * @author Michael Angstadt
 */
public class Sleeper {
	private static final Logger logger = LoggerFactory.getLogger(Sleeper.class);

	/**
	 * Keeps a count of how long threads have slept for if {@link #unitTest} is
	 * set to {@code true}.
	 */
	private static long timeSlept;

	/**
	 * If set to true, then {@link Thread#sleep} will NOT be called (defaults to
	 * {@code false}).
	 */
	private static boolean unitTest = false;

	/**
	 * Disables calls to {@link Thread#sleep} and records sleep time.
	 */
	public static void startUnitTest() {
		unitTest = true;
		timeSlept = 0;
	}

	/**
	 * Re-enables calls to {@link Thread#sleep}.
	 */
	public static void endUnitTest() {
		unitTest = false;
	}

	/**
	 * Gets the total amount of time slept since {@link #startUnitTest} was
	 * called.
	 * @return total time slept (in milliseconds)
	 */
	public static long getTimeSlept() {
		return timeSlept;
	}

	/**
	 * Calls {@link Thread#sleep} (if unit testing hasn't been enabled with
	 * {@link #startUnitTest}).
	 * @param ms the amount of time to sleep (in milliseconds)
	 */
	public static void sleep(long ms) {
		if (unitTest) {
			timeSlept += ms;
			return;
		}

		try {
			Thread.sleep(ms);
		} catch (InterruptedException e) {
			/*
			 * This is how you're supposed to handle InterruptedExceptions.
			 * https://stackoverflow.com/q/1087475/13379
			 * https://rules.sonarsource.com/java/RSPEC-2142/
			 */
			Thread.currentThread().interrupt();
			logger.atWarn().setCause(e).log(() -> "Thread interrupted while sleeping.");
		}
	}

	/**
	 * Calls {@link Thread#sleep} (if unit testing hasn't been enabled with
	 * {@link #startUnitTest}).
	 * @param duration the amount of time to sleep
	 */
	public static void sleep(Duration duration) {
		sleep(duration.toMillis());
	}

	private Sleeper() {
		//hide constructor
	}
}
