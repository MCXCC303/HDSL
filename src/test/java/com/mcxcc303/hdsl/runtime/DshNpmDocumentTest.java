package com.mcxcc303.hdsl.runtime;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DshNpmDocumentTest {
	private static JsonObject tags() {
		return JsonParser.parseString("""
				{
				  "latest": "1.3.0-alpha.1",
				  "beta": "1.3.0",
				  "next": "1.4.0"
				}
				""").getAsJsonObject();
	}

	@Test
	void channelOfUsesVersionSuffixWhenPresent() {
		assertEquals(DshReleaseChannel.RC, DshNpmDocument.channelOf("1.3.0-rc.1", null),
				"suffix rc must map to RC");
		assertEquals(DshReleaseChannel.BETA, DshNpmDocument.channelOf("1.3.0-beta.2", null),
				"suffix beta must map to BETA");
		assertEquals(DshReleaseChannel.ALPHA, DshNpmDocument.channelOf("1.3.0-alpha.1", null),
				"suffix alpha must map to ALPHA");
		assertEquals(DshReleaseChannel.OTHER, DshNpmDocument.channelOf("1.3.0-0", null),
				"unknown suffix 0 must map to OTHER");
	}

	@Test
	void channelOfFallsBackToTagWhenSuffixAbsent() {
		assertEquals(DshReleaseChannel.STABLE, DshNpmDocument.channelOf("1.2.3", tags()),
				"no suffix and no tag must map to STABLE");
		assertEquals(DshReleaseChannel.BETA, DshNpmDocument.channelOf("1.3.0", tags()),
				"tag beta must map 1.3.0 to BETA");
		assertEquals(DshReleaseChannel.OTHER, DshNpmDocument.channelOf("1.4.0", tags()),
				"tag next must map 1.4.0 to OTHER");
	}

	@Test
	void channelOfPrefersSuffixOverTag() {
		assertEquals(DshReleaseChannel.ALPHA, DshNpmDocument.channelOf("1.3.0-alpha.1", tags()),
				"suffix alpha must override tag latest, giving ALPHA");
	}
}