package com.mcxcc303.hdsl.account;

import com.mcxcc303.hdsl.core.DshException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the line edits makes to a profile's patch layer.
 */
class DshProfilePatchTest {
	@TempDir
	private Path profile;

	private Path patch(String body) throws Exception {
		Path file = profile.resolve("cordis.patch.yml");
		Files.writeString(file, body);
		return file;
	}

	@Test
	void dropRedundantInserts_removesInsertOfAppliedBundle() throws Exception {
		Path file = patch("""
				                  # Your patch layer for this dsh profile
				                  - insert:
				                      - id: dsh-im-connect
				                        name: '@michengai/dsh-im-connect'
				                  """);

		List<String> left = DshProfilePatch.dropRedundantInserts(file,
		                                                         List.of("@deepseek-ai/dsh-base",
		                                                                 "@michengai/dsh-im-connect"));
		assertEquals(List.of(), left, "nothing carried configuration");

		String after = Files.readString(file);
		assertTrue(after.contains("# Your patch layer"), "the comment stays: " + after);
		assertFalse(after.contains("dsh-im-connect"), "the duplicate insert is gone: " + after);
	}

	@Test
	void dropRedundantInserts_keepsInsertOfPluginNoBundleApplies() throws Exception {
		String body = """
				- insert:
				    - id: something-else
				      name: 'dsh-something-else'
				""";
		Path file = patch(body);
		DshProfilePatch.dropRedundantInserts(file, List.of("@deepseek-ai/dsh-base"));

		assertEquals(body, Files.readString(file), "a plugin nothing else applies is left where it is");
	}

	@Test
	void dropRedundantInserts_keepsConfiguredInsertAndReportsItsId() throws Exception {
		String body = """
				- insert:
				    - id: better-sidebar
				      name: 'dsh-better-sidebar'
				      config:
				        css: 'body { color: red }'
				""";
		Path file = patch(body);
		List<String> left = DshProfilePatch.dropRedundantInserts(file, List.of("dsh-better-sidebar"));

		assertEquals(List.of("better-sidebar"), left, "the entry is reported by its id");
		assertEquals(body, Files.readString(file), "its settings are not thrown away");
	}

	@Test
	void withoutRedundantInserts_returnsTextWithoutDuplicate() {
		String text = """
				- insert:
				    - id: dsh-im-connect
				      name: '@michengai/dsh-im-connect'
				""";

		DshProfilePatch.Edit edit = DshProfilePatch.withoutRedundantInserts(text, List.of("@michengai/dsh-im-connect"));

		assertTrue(edit.changed(), "the duplicate is taken out of the copy");
		assertFalse(edit.text().contains("dsh-im-connect"), edit.text());
		assertEquals(List.of(), edit.withConfiguration());
	}

	@Test
	void withoutRedundantInserts_reportsUnchangedWhenNothingToDrop() {
		DshProfilePatch.Edit edit = DshProfilePatch.withoutRedundantInserts("- id: x\n", List.of("y"));
		assertFalse(edit.changed(), "a file with nothing to repair is left as it is");
		assertEquals("- id: x\n", edit.text());
	}

	@Test
	void dropRedundantInserts_leavesPatchWithoutInsertsUntouched() throws Exception {
		String body = """
				- id: tool-web
				  config:
				    fetch: true
				""";
		Path file = patch(body);

		assertEquals(List.of(), DshProfilePatch.dropRedundantInserts(file, List.of("anything")));
		assertEquals(body, Files.readString(file));
	}

	@Test
	void putProvider_addsProviderToExistingProvidersBlock() throws Exception {
		String body = """
				- id: llm-pi-ai
				  name: '@deepseek-ai/dsh-llm-pi-ai'
				  config:
				    providers:
				      Mine:
				        apiKeyEnv: MY_KEY
				- id: locale
				  config:
				    language: en
				""";

		DshProfilePatch.Edit edit = DshProfilePatch.putProvider(body,
		                                                        "llm-pi-ai",
		                                                        "DeepSeek",
		                                                        "apiKeyEnv: HDSL_LAUNCH_API_KEY_DEEPSEEK_00000000\napi: openai-completions\n");
		assertTrue(edit.changed());

		String after = edit.text();
		assertTrue(after.contains("""
				                                Mine:
				                                  apiKeyEnv: MY_KEY
				                                DeepSeek:
				                                  apiKeyEnv: HDSL_LAUNCH_API_KEY_DEEPSEEK_00000000
				                                  api: openai-completions
				                          """), after);
		assertTrue(after.contains("- id: locale\n  config:\n    language: en\n"),
		           "the entry after it is untouched: " + after);
	}

	@Test
	void putProvider_replacesProviderInPlace() throws Exception {
		String body = """
				- id: llm-pi-ai
				  config:
				    providers:
				      A:
				        apiKeyEnv: A_KEY
				      DeepSeek:
				        apiKeyEnv: OLD
				        models:
				          - id: old
				      B:
				        apiKeyEnv: B_KEY
				""";

		DshProfilePatch.Edit edit = DshProfilePatch.putProvider(body, "llm-pi-ai", "DeepSeek", "apiKeyEnv: NEW\n");

		assertTrue(edit.changed());
		assertEquals("""
				             - id: llm-pi-ai
				               config:
				                 providers:
				                   A:
				                     apiKeyEnv: A_KEY
				                   DeepSeek:
				                     apiKeyEnv: NEW
				                   B:
				                     apiKeyEnv: B_KEY
				             """, edit.text(), "only the route's own block is rewritten");
	}

	@Test
	void putProvider_createsProvidersBlockWhenMissing() throws Exception {
		String body = """
				- id: llm-pi-ai
				  name: '@deepseek-ai/dsh-llm-pi-ai'
				""";

		DshProfilePatch.Edit edit = DshProfilePatch.putProvider(body, "llm-pi-ai", "DeepSeek", "apiKeyEnv: NEW\n");

		assertEquals("""
				             - id: llm-pi-ai
				               name: '@deepseek-ai/dsh-llm-pi-ai'
				               config:
				                 providers:
				                   DeepSeek:
				                     apiKeyEnv: NEW
				             """, edit.text());
	}

	@Test
	void putProvider_appendsEntryWhenIdNotMentioned() throws Exception {
		DshProfilePatch.Edit edit = DshProfilePatch.putProvider("# mine\n[]\n",
		                                                        "llm-pi-ai",
		                                                        "DeepSeek",
		                                                        "apiKeyEnv: NEW\n");

		assertEquals("""
				             # mine
				             # []
				             - id: llm-pi-ai
				               config:
				                 providers:
				                   DeepSeek:
				                     apiKeyEnv: NEW
				             """, edit.text());
	}

	@Test
	void putProvider_writesToLastEntryWithSameId() throws Exception {
		String body = """
				- id: llm-pi-ai
				  config:
				    providers:
				      First:
				        apiKeyEnv: FIRST
				- id: llm-pi-ai
				  config:
				    providers:
				      Second:
				        apiKeyEnv: SECOND
				""";

		DshProfilePatch.Edit edit = DshProfilePatch.putProvider(body, "llm-pi-ai", "DeepSeek", "apiKeyEnv: NEW\n");
		String after = edit.text();

		assertTrue(after.contains("      First:\n        apiKeyEnv: FIRST\n"),
		           "the entry that is not in effect is left alone: " + after);
		assertTrue(after.indexOf("Second:") < after.indexOf("DeepSeek:"),
		           "and the route joins the one the editor edits: " + after);
	}

	@Test
	void putProvider_reportsUnchangedWhenProviderIdentical() throws Exception {
		String body = """
				- id: llm-pi-ai
				  config:
				    providers:
				      DeepSeek:
				        apiKeyEnv: NEW
				""";

		DshProfilePatch.Edit edit = DshProfilePatch.putProvider(body, "llm-pi-ai", "DeepSeek", "apiKeyEnv: NEW\n");

		assertFalse(edit.changed(), "a launch that says the same thing must not rewrite the file");
		assertEquals(body, edit.text());
	}

	@Test
	void putProvider_replacesNullConfigOrProvidersWithBlock() throws Exception {
		String expected = """
				- id: llm-pi-ai
				  config:
				    providers:
				      DeepSeek:
				        apiKeyEnv: NEW
				""";

		assertEquals(expected, DshProfilePatch.putProvider("""
				                                                   - id: llm-pi-ai
				                                                     config:
				                                                       providers: null
				                                                   """, "llm-pi-ai", "DeepSeek", "apiKeyEnv: NEW\n")
		                                      .text());
		assertEquals(expected,
		             DshProfilePatch.putProvider("- id: llm-pi-ai\n  config: null\n",
		                                         "llm-pi-ai",
		                                         "DeepSeek",
		                                         "apiKeyEnv: NEW\n").text());
	}

	@Test
	void putProvider_rejectsInlineConfig() {
		DshException refused = Assertions.assertThrows(DshException.class,
		                                               () -> DshProfilePatch.putProvider(
				                                               "- id: llm-pi-ai\n  config: {providers: {Mine: {}}}\n",
				                                               "llm-pi-ai",
				                                               "DeepSeek",
				                                               "apiKeyEnv: NEW\n"));
		assertTrue(refused.getMessage().contains("inline"), refused.getMessage());
	}

	@Test
	void putProvider_keepsFourSpaceIndentation() throws Exception {
		String body = """
				- id: llm-pi-ai
				    config:
				        providers:
				            Mine:
				                apiKeyEnv: MY_KEY
				""";

		DshProfilePatch.Edit edit = DshProfilePatch.putProvider(body, "llm-pi-ai", "DeepSeek", "apiKeyEnv: NEW\n");
		assertTrue(edit.text().contains("""
				                                            DeepSeek:
				                                                apiKeyEnv: NEW
				                                """), edit.text());
	}
}
