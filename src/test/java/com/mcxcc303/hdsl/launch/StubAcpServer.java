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
package com.mcxcc303.hdsl.launch;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

/**
 * A minimal Agent Client Protocol peer used by the client tests.
 */
public final class StubAcpServer {
	public static final String SESSION_ID = "stub-session";
	public static final String[] CHUNKS = {"PON", "G"};
	public static final String STOP_REASON = "end_turn";

	private StubAcpServer() {
	}

	public static void main(String[] args) throws Exception {
		BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
		BufferedWriter out = new BufferedWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8));

		String line;
		while ((line = in.readLine()) != null) {
			if (line.isBlank()) {
				continue;
			}
			JsonObject message = JsonParser.parseString(line).getAsJsonObject();
			JsonElement method = message.get("method");
			if (method == null) {
				continue;
			}

			switch (method.getAsString()) {
				case "initialize" -> {
					JsonObject result = new JsonObject();
					result.addProperty("protocolVersion", DshAcpClient.PROTOCOL_VERSION);
					respond(out, message, result);
				}
				case "session/new" -> {
					JsonObject result = new JsonObject();
					result.addProperty("sessionId", SESSION_ID);
					respond(out, message, result);
				}
				case "session/prompt" -> {
					String sessionId = message.getAsJsonObject("params").get("sessionId").getAsString();
					for (String chunk : CHUNKS) {
						notifyUpdate(out, sessionId, chunk);
					}
					JsonObject result = new JsonObject();
					result.addProperty("stopReason", STOP_REASON);
					respond(out, message, result);
				}
				default -> {
					// A notification such as session/cancel: nothing to answer.
				}
			}
		}
	}

	private static void respond(BufferedWriter out, JsonObject request, JsonObject result) throws Exception {
		JsonObject message = new JsonObject();
		message.addProperty("jsonrpc", "2.0");
		message.add("id", request.get("id"));
		message.add("result", result);
		write(out, message);
	}

	private static void notifyUpdate(BufferedWriter out, String sessionId, String text) throws Exception {
		JsonObject content = new JsonObject();
		content.addProperty("type", "text");
		content.addProperty("text", text);

		JsonObject update = new JsonObject();
		update.addProperty("sessionUpdate", "agent_message_chunk");
		update.add("content", content);

		JsonObject params = new JsonObject();
		params.addProperty("sessionId", sessionId);
		params.add("update", update);

		JsonObject message = new JsonObject();
		message.addProperty("jsonrpc", "2.0");
		message.addProperty("method", "session/update");
		message.add("params", params);
		write(out, message);
	}

	private static void write(BufferedWriter out, JsonObject message) throws Exception {
		out.write(message.toString());
		out.write('\n');
		out.flush();
	}
}
