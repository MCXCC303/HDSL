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
package com.mcxcc303.hdsl.account;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for editing the harness's default model configuration.
 */
class DshDefaultModelTest {
	private static Path homeWith(String contents) throws Exception {
		Path home = Files.createTempDirectory("dsh-home");
		if (contents != null) {
			Files.writeString(home.resolve("settings.yaml"), contents);
		}
		return home;
	}

	@Test
	void theValueChangesAndNothingElseDoes() throws Exception {
		String before = """
				ui-onboarding:
				  welcomeNoticeVersion: 2026-08-13.1
				agent-default-model:
				  provider: deepseek-official
				  model: deepseek-flash
				  reasoningEffort: max
				remote-web-ui:
				  foo: 1
				""";
		Path home = homeWith(before);

		assertTrue(DshDefaultModel.apply(home, "deepseek", "deepseek-chat"));

		assertEquals("""
				             ui-onboarding:
				               welcomeNoticeVersion: 2026-08-13.1
				             agent-default-model:
				               provider: deepseek
				               model: deepseek-chat
				               reasoningEffort: max
				             remote-web-ui:
				               foo: 1
				             """, Files.readString(home.resolve("settings.yaml")));
	}

	@Test
	void theSectionIsAddedWhenThereIsNone() throws Exception {
		Path home = homeWith("ui-onboarding:\n  welcomeNoticeVersion: 1\n");

		assertTrue(DshDefaultModel.apply(home, "openai", "gpt-5"));

		assertEquals("""
				             ui-onboarding:
				               welcomeNoticeVersion: 1
				             agent-default-model:
				               provider: openai
				               model: gpt-5
				             """, Files.readString(home.resolve("settings.yaml")));
	}

	@Test
	void anEmptyFileIsFilledIn() throws Exception {
		Path home = homeWith("");

		assertTrue(DshDefaultModel.apply(home, "deepseek", "deepseek-chat"));

		assertEquals("agent-default-model:\n  provider: deepseek\n  model: deepseek-chat\n",
		             Files.readString(home.resolve("settings.yaml")));
	}

	@Test
	void aSectionWithoutTheKeysGetsThemBesideWhatItHas() throws Exception {
		Path home = homeWith("agent-default-model:\n  reasoningEffort: max\n");

		assertTrue(DshDefaultModel.apply(home, "deepseek", "deepseek-chat"));

		assertEquals("""
				             agent-default-model:
				               reasoningEffort: max
				               provider: deepseek
				               model: deepseek-chat
				             """, Files.readString(home.resolve("settings.yaml")));
	}

	@Test
	void theSectionsOwnIndentationIsKept() throws Exception {
		for (String indent : List.of("    ", "\t")) {
			Path home = homeWith("agent-default-model:\n" + indent + "provider: old\n" + indent + "model: old-model\n");

			assertTrue(DshDefaultModel.apply(home, "deepseek", "deepseek-chat"));

			assertEquals("agent-default-model:\n" + indent + "provider: deepseek\n" + indent + "model: deepseek-chat\n",
			             Files.readString(home.resolve("settings.yaml")));
		}
	}

	@Test
	void aFileWithoutATrailingNewlineKeepsEveryLine() throws Exception {
		Path home = homeWith("agent-default-model:\n  provider: old\n  model: old-model");

		assertTrue(DshDefaultModel.apply(home, "deepseek", "deepseek-chat"));

		assertEquals("agent-default-model:\n  provider: deepseek\n  model: deepseek-chat\n",
		             Files.readString(home.resolve("settings.yaml")));
	}

	@Test
	void noModelNamedLeavesTheFileAlone() throws Exception {
		String before = "agent-default-model:\n  provider: deepseek-official\n  model: deepseek-flash\n";
		Path home = homeWith(before);

		assertFalse(DshDefaultModel.apply(home, "deepseek", null));
		assertFalse(DshDefaultModel.apply(home, "deepseek", "   "));

		assertEquals(before, Files.readString(home.resolve("settings.yaml")));
	}

	@Test
	void aMissingFileIsCreatedOnlyWhenThereIsSomethingToSay() throws Exception {
		Path home = homeWith(null);

		assertFalse(DshDefaultModel.apply(home, "deepseek", null));
		assertFalse(Files.exists(home.resolve("settings.yaml")));

		assertTrue(DshDefaultModel.apply(home, "deepseek", "deepseek-chat"));
		assertTrue(Files.exists(home.resolve("settings.yaml")));
	}

	@Test
	void applyingTheSameAnswerTwiceChangesNothing() throws Exception {
		Path home = homeWith("agent-default-model:\n  provider: deepseek\n  model: deepseek-chat\n");
		String before = Files.readString(home.resolve("settings.yaml"));

		assertTrue(DshDefaultModel.apply(home, "deepseek", "deepseek-chat"));

		assertEquals(before,
		             Files.readString(home.resolve("settings.yaml")),
		             "an edit that would change nothing must not rewrite the file");
	}

	@Test
	void theProviderCanBeReadBack() throws Exception {
		Path home = homeWith("""
				                     ui-onboarding:
				                       welcomeNoticeVersion: 1
				                     agent-default-model:
				                       provider: deepseek
				                       model: deepseek-chat
				                     """);

		assertEquals("deepseek", DshDefaultModel.providerOf(home));
		assertNull(DshDefaultModel.providerOf(homeWith("ui-onboarding:\n  foo: 1\n")));
		assertNull(DshDefaultModel.providerOf(homeWith(null)));
	}

	@Test
	void aCrlfFileKeepsCrlf() throws Exception {
		Path home = homeWith("agent-default-model:\r\n  provider: old\r\n  model: old\r\n");

		assertTrue(DshDefaultModel.apply(home, "deepseek", "deepseek-chat"));

		assertEquals("agent-default-model:\r\n  provider: deepseek\r\n  model: deepseek-chat\r\n",
		             Files.readString(home.resolve("settings.yaml")));
	}

	@Test
	void aKeyNestedDeeperIsNotFlattened() throws Exception {
		Path home = homeWith("agent-default-model:\n  provider: old\n    model: nested\n");

		DshDefaultModel.apply(home, "deepseek", "deepseek-chat");

		assertEquals("agent-default-model:\n  provider: deepseek\n    model: nested\n" + "  model: deepseek-chat\n",
		             Files.readString(home.resolve("settings.yaml")));
	}

	@Test
	void aSectionNameThatMerelyStartsTheSameIsADifferentSection() throws Exception {
		Path home = homeWith("agent-default-modelish:\n  provider: keep\n");

		DshDefaultModel.apply(home, "deepseek", "deepseek-chat");

		assertEquals("agent-default-modelish:\n  provider: keep\n" +
		             "agent-default-model:\n  provider: deepseek\n  model: deepseek-chat\n",
		             Files.readString(home.resolve("settings.yaml")));
	}

	@Test
	void aFollowingSectionIsNotInvaded() throws Exception {
		Path home = homeWith("agent-default-model:\n  provider: old\nother:\n  model: not-ours\n");

		DshDefaultModel.apply(home, "deepseek", "deepseek-chat");

		assertEquals(
				"agent-default-model:\n  provider: deepseek\n  model: deepseek-chat\n" + "other:\n  model: not-ours\n",
				Files.readString(home.resolve("settings.yaml")));
	}

	@Test
	void everyResultIsParsableAsYaml() throws Exception {
		List<String> shapes = List.of("",
		                              "ui-onboarding:\n  a: 1\n",
		                              "agent-default-model:\n  provider: old\n  model: old\n",
		                              "agent-default-model:\n  reasoningEffort: max\n",
		                              "agent-default-model:\n  model: old\n",
		                              "agent-default-model:\n  provider: old\n",
		                              "agent-default-model:",
		                              "agent-default-model:\n    provider: old\n    model: old\n",
		                              "agent-default-model:\n  provider: old\n  model: old\n\nother:\n  b: 2\n");
		for (String shape : shapes) {
			Path home = homeWith(shape);
			DshDefaultModel.apply(home, "deepseek", "deepseek-chat");
			String result = Files.readString(home.resolve("settings.yaml"));

			// Every line is either blank or a mapping entry; no two entries share a line, and no
			// entry is glued to the section header.
			for (String line : result.split("\n")) {
				if (line.isBlank()) {
					continue;
				}
				long colons = line.chars().filter(c -> c == ':').count();
				assertTrue(colons >= 1, "a line that is not a mapping entry: <" + line + ">");
				assertFalse(line.endsWith(":") && line.stripLeading().startsWith("provider"),
				            "a key with no value: <" + line + ">");
			}
			assertEquals("deepseek",
			             DshDefaultModel.providerOf(home),
			             "the provider must be readable back from <" + shape.replace("\n", "\\n") + ">");
		}
	}
}
