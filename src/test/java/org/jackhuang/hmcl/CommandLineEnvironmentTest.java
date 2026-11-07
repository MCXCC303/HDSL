/*
 * HDSL
 * Copyright (C) 2026  HDSL contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.jackhuang.hmcl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Verifies that a CLI command sees the launcher's network settings before it starts
 * a child process.
 */
class CommandLineEnvironmentTest {
	private @TempDir Path home;

	/**
	 * A caller that never loads the settings keeps the built-in default, which is what
	 * a command must not silently read as the person's configuration.
	 */
	@Test
	void aCommandLineThatNeverReadsSettingsUsesTheDefaultConcurrency() throws Exception {
		String output = probe("raw");

		assertTrue(output.contains("npm_config_maxsockets=64"),
		           "the default concurrency is what an unconfigured launcher uses: " + output);
		assertFalse(output.contains("HTTP_PROXY"),
		            "nothing was configured, so no proxy may be invented: " + output);
	}

	/**
	 * The CLI branch loads the settings before dispatching, so the proxy and concurrency
	 * in the settings file reach the child environment.
	 */
	@Test
	void theCommandLineRegistersTheProxyAndConcurrencyBeforeACommandRuns() throws Exception {
		Files.writeString(home.resolve("launcher-settings.json"), """
				{
				  "proxyMode": "http",
				  "proxyHost": "127.0.0.1",
				  "proxyPort": "9999",
				  "proxyAuthenticated": false,
				  "downloadConcurrency": 3
				}
				""");

		String output = probe("run");

		assertTrue(output.contains("HTTP_PROXY=http://127.0.0.1:9999"), output);
		assertTrue(output.contains("HTTPS_PROXY=http://127.0.0.1:9999"), output);
		assertTrue(output.contains("ALL_PROXY=http://127.0.0.1:9999"), output);
		assertTrue(output.contains("npm_config_maxsockets=3"),
		           "the configured concurrency replaces the default: " + output);
	}

	/**
	 * Runs the probe in a fresh JVM so the settings class is not already initialized.
	 *
	 * @param mode {@code raw} leaves the settings unread, {@code run} goes through the
	 *             command-line entry point (with a help command) before reading them
	 * @return the probe's standard output
	 */
	private String probe(String mode) throws Exception {
		String executable = System.getProperty("os.name", "").toLowerCase().contains("win")
		                    ? "java.exe" : "java";
		ProcessBuilder builder = new ProcessBuilder(
				Path.of(System.getProperty("java.home"), "bin", executable).toString(),
				"-Dhdsl.home=" + home.toAbsolutePath(),
				"-cp", System.getProperty("java.class.path"),
				CommandLineEnvironmentProbe.class.getName(),
				mode);
		builder.redirectErrorStream(true);
		Process process = builder.start();
		CompletableFuture<String> output = CompletableFuture.supplyAsync(() -> read(process));
		if (!process.waitFor(60, TimeUnit.SECONDS)) {
			process.destroyForcibly();
			fail("the probe did not finish; its output was " + awaitQuietly(output));
		}
		String text = output.join();
		assertEquals(0, process.exitValue(), text);
		return text;
	}

	/**
	 * Reads a probe's output without blocking the thread that enforces the timeout.
	 *
	 * @param process the probe
	 * @return its output
	 */
	private static String read(Process process) {
		try {
			return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/**
	 * Returns what a timed-out probe had written so far, or a note that it said nothing.
	 *
	 * @param output the reader that may still be blocked
	 * @return the text, or a placeholder
	 */
	private static String awaitQuietly(CompletableFuture<String> output) {
		try {
			return output.get(1, TimeUnit.SECONDS);
		} catch (Exception e) {
			return "(no output)";
		}
	}
}
