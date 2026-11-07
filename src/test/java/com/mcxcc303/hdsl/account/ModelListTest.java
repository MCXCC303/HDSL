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

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for reading model listings from vendors.
 */
class ModelListTest {
	@Test
	void theIdsAreReadFromTheShapeOpenAiCompatibleServicesShare() {
		String body = """
				{"object":"list","data":[
				  {"id":"deepseek-chat","object":"model"},
				  {"id":"deepseek-reasoner","object":"model"}
				]}""";

		assertEquals(List.of("deepseek-chat", "deepseek-reasoner"), DshAccount.readModelIds(body));
	}

	@Test
	void whatIsNotAModelListReadsAsNothingRatherThanAsAModel() {
		assertTrue(DshAccount.readModelIds("{\"data\":\"not an array\"}").isEmpty());
		assertTrue(DshAccount.readModelIds("{\"models\":[]}").isEmpty());
		assertTrue(DshAccount.readModelIds("[]").isEmpty());
		assertTrue(DshAccount.readModelIds("not json").isEmpty());
		assertTrue(DshAccount.readModelIds("").isEmpty());
		assertTrue(DshAccount.readModelIds(null).isEmpty());

		assertEquals(List.of("a"),
		             DshAccount.readModelIds(
				             "{\"data\":[{\"id\":\"a\"},{\"object\":\"model\"},{\"id\":\"a\"},{\"id\":\"\"}]}"));
	}

	@Test
	void theEnrichedMapSomeGatewaysAnswerWithIsReadToo() {
		String body = """
				{"object":"list","models":{
				  "deepseek-chat":{"name":"DeepSeek Chat"},
				  "deepseek-reasoner":{"name":"DeepSeek Reasoner"}
				}}""";

		assertEquals(List.of("deepseek-chat", "deepseek-reasoner"), DshAccount.readModelIds(body));
	}

	@Test
	void aMapsKeyNamesTheModelAndItsOwnIdDoesNotRenameIt() {
		assertEquals(List.of("by-key"),
		             DshAccount.readModelIds("{\"models\":{\"by-key\":{\"id\":\"something-else\"}}}"));
		assertEquals(List.of("from-the-entry"),
		             DshAccount.readModelIds("{\"models\":{\"\":{\"id\":\"from-the-entry\"}}}"),
		             "an entry with no key still names a model by its own id");
		assertEquals(List.of("only-objects"),
		             DshAccount.readModelIds("{\"models\":{\"only-objects\":{},\"ignored\":\"not an object\"}}"),
		             "a value that is not an object is not a model");
	}

	@Test
	void theListingAddressFollowsTheProtocol() {
		assertEquals("https://api.opencode.ai/v1/models",
		             DshAccount.listingUrl("https://api.opencode.ai/v1", "openai-completions"));
		assertEquals("https://api.openai.com/v1/models",
		             DshAccount.listingUrl("https://api.openai.com/v1", "openai-responses"));
		assertEquals("https://api.opencode.ai/v1/models",
		             DshAccount.listingUrl("https://api.opencode.ai/v1///", "openai-completions"),
		             "trailing slashes do not become a second path segment");

		assertEquals("https://api.anthropic.com/v1/models?limit=1000",
		             DshAccount.listingUrl("https://api.anthropic.com", "anthropic-messages"));
		assertEquals("https://api.anthropic.com/v1/models?limit=1000",
		             DshAccount.listingUrl("https://api.anthropic.com/v1", "anthropic-messages"),
		             "one trailing /v1 is the root's, and is not counted twice");
		assertEquals("https://api.z.ai/api/coding/paas/v4/v1/models?limit=1000",
		             DshAccount.listingUrl("https://api.z.ai/api/coding/paas/v4", "anthropic-messages"),
		             "a deployment path keeps its segments");
	}

	@Test
	void aStatusAloneDoesNotMakeAnAddressASupplier() {
		DshAccount.Answer plainPage = new DshAccount.Answer(200, false);
		assertFalse(plainPage.listsModels(), "a page that answers 200 to everything is not a supplier");

		assertTrue(new DshAccount.Answer(200, true).listsModels());
		assertTrue(DshAccount.looksLikeAListing("{\"object\":\"list\",\"data\":[]}"));
		assertTrue(DshAccount.looksLikeAListing("{\"models\":{}}"));

		assertTrue(new DshAccount.Answer(401, false).listsModels());
		assertTrue(new DshAccount.Answer(403, false).listsModels());

		assertFalse(new DshAccount.Answer(404, false).listsModels());
		assertFalse(new DshAccount.Answer(500, false).listsModels());
		assertFalse(new DshAccount.Answer(-1, false).listsModels());
	}

	@Test
	void whatAVendorSaidAboutARefusalIsReadBack() {
		assertEquals("Token is invalid.",
		             DshAccount.saidIn("{\"code\":30014,\"data\":null,\"message\":\"Token is invalid.\"}"));

		// The other spellings services use.
		assertEquals("invalid api key", DshAccount.saidIn("{\"error\":{\"message\":\"invalid api key\"}}"));
		assertEquals("quota exceeded", DshAccount.saidIn("{\"error\":\"quota exceeded\"}"));

		// A reason is shown whole in a dialog, and a multi-line one is flattened onto one line.
		assertEquals("b".repeat(200), DshAccount.saidIn("{\"message\":\"" + "b".repeat(200) + "\"}"));
		assertEquals("first second", DshAccount.saidIn("{\"message\":\"first\\n  second\"}"));

		// Pages and errors with nothing to quote say nothing.
		assertNull(DshAccount.saidIn("Not Found"));
		assertNull(DshAccount.saidIn("<html><body>nope</body></html>"));
		assertNull(DshAccount.saidIn("{\"error\":\"\"}"));
		assertNull(DshAccount.saidIn("{\"error\":{\"code\":12}}"));
		assertNull(DshAccount.saidIn("{\"message\":42}"));
		assertNull(DshAccount.saidIn(""));
		assertNull(DshAccount.saidIn(null));
	}

	@Test
	void whatIsNotAListingIsNotOne() {
		assertFalse(DshAccount.looksLikeAListing("Not Found"), "a plain-text page");
		assertFalse(DshAccount.looksLikeAListing("<html><body>hi</body></html>"));
		assertFalse(DshAccount.looksLikeAListing("{\"error\":\"invalid api key\"}"));
		assertFalse(DshAccount.looksLikeAListing("{\"data\":\"not an array\"}"));
		assertFalse(DshAccount.looksLikeAListing("{\"models\":[]}"), "an array is not the enriched map");
		assertFalse(DshAccount.looksLikeAListing("[]"));
		assertFalse(DshAccount.looksLikeAListing(""));
		assertFalse(DshAccount.looksLikeAListing(null));
	}
}
