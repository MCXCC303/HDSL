package org.jackhuang.hmcl.ui.dsh;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PageStringsTest {
	private static final List<String> LEAST_BUNDLES = List.of("I18N.properties", "I18N_zh_Hans.properties");
	private static final String OWN_PREFIX = "dsh.";
	private static final Pattern ASKED = Pattern.compile("i18n\\(\"([^\"]+)\"");
	private static final Pattern BRACE_PLACEHOLDER = Pattern.compile("\\{\\d+}");

	@Test
	@DisplayName("Every i18n key used in the code exists in both bundles")
	void allRequestedKeysPresentInBothBundles() throws IOException {
		assertEveryAskedKeyIsPresent(LEAST_BUNDLES);
	}

	@Test
	@Tag("all-locales")
	@DisplayName("Every i18n key used in the code exists in every bundle")
	void allRequestedKeysPresentInEveryBundle() throws IOException {
		assertEveryAskedKeyIsPresent(bundles());
	}

	@Test
	void shouldWritePlaceholdersInFormatterSyntax() throws IOException {
		List<String> wrong = new ArrayList<>();
		for (String bundle : LEAST_BUNDLES) {
			Properties strings = load(bundle);
			for (String key : strings.stringPropertyNames()) {
				if (key.startsWith(OWN_PREFIX) && BRACE_PLACEHOLDER.matcher(strings.getProperty(key)).find()) {
					wrong.add(bundle + " " + key + " = " + strings.getProperty(key));
				}
			}
		}
		assertTrue(wrong.isEmpty(),
		           "these strings use a placeholder the formatter does not read:\n  " + String.join("\n  ", wrong));
	}

	/**
	 * Returns every key the code asks a bundle for by name.
	 *
	 * @return the keys, in order
	 */
	private static Set<String> keysTheCodeAsksFor() throws IOException {
		Set<String> keys = new TreeSet<>();
		for (Path file : sources()) {
			String source = Files.readString(file, StandardCharsets.UTF_8);
			Matcher matcher = ASKED.matcher(source);
			while (matcher.find()) {
				// A literal a caller adds to at run time is considered not a key
				int after = matcher.end();
				while (after < source.length() && Character.isWhitespace(source.charAt(after))) {
					after++;
				}
				if (after < source.length() && source.charAt(after) == '+') continue;

				keys.add(matcher.group(1));
			}
		}
		return keys;
	}

	private static void assertEveryAskedKeyIsPresent(List<String> bundles) throws IOException {
		Set<String> asked = keysTheCodeAsksFor();
		List<String> gaps = new ArrayList<>();
		for (String bundle : bundles) {
			Properties strings = load(bundle);
			List<String> missing = asked.stream().filter(key -> !strings.containsKey(key)).toList();
			if (!missing.isEmpty()) {
				gaps.add(bundle + " (" + missing.size() + "): " + String.join(", ", missing));
			}
		}
		assertTrue(gaps.isEmpty(), "these bundles have no string for some keys:\n  " + String.join("\n  ", gaps));
	}

	/**
	 * Returns every bundle shipped under assets/lang.
	 *
	 * @return the bundle file names, in name order
	 */
	private static List<String> bundles() throws IOException {
		try (Stream<Path> files = Files.list(Path.of("src/main/resources/assets/lang"))) {
			return files.map(file -> file.getFileName().toString())
			            .filter(name -> name.startsWith("I18N") && name.endsWith(".properties"))
			            .sorted()
			            .toList();
		}
	}

	/**
	 * Returns the sources this launcher is built from.
	 *
	 * @return the Java files
	 */
	private static List<Path> sources() throws IOException {
		Path root = Path.of("src/main/java");
		assertTrue(Files.isDirectory(root), "no sources at " + root.toAbsolutePath());
		try (Stream<Path> walk = Files.walk(root)) {
			return walk.filter(path -> path.toString().endsWith(".java")).toList();
		}
	}

	/**
	 * Reads one bundle from the resources.
	 *
	 * @param name the bundle's file name
	 * @return its strings
	 */
	private static Properties load(String name) throws IOException {
		Properties strings = new Properties();
		try (InputStream input = PageStringsTest.class.getResourceAsStream("/assets/lang/" + name)) {
			if (input == null) throw new IOException("no bundle named " + name);
			strings.load(new InputStreamReader(input, StandardCharsets.UTF_8));
		}
		return strings;
	}
}
