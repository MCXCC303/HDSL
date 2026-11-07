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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mcxcc303.hdsl.core.DshException;
import com.mcxcc303.hdsl.instance.DshInstance;
import com.mcxcc303.hdsl.harness.HarnessInstaller;
import com.mcxcc303.hdsl.runtime.DshNodeRuntime;
import org.jackhuang.hmcl.Metadata;
import org.jackhuang.hmcl.util.platform.ManagedProcess;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/**
 * Client for DeepSeek Harness's Agent Client Protocol (ACP) automation interface.
 * <p>
 * Communicates with an instance using newline-delimited JSON-RPC 2.0 over stdio for
 * session initialization, message prompting, cancellation, and streaming update events.
 */
@NotNullByDefault
public final class DshAcpClient implements AutoCloseable {
	public static final int PROTOCOL_VERSION = 1;

	private static final long REQUEST_TIMEOUT_SECONDS = 120;
	private final DshInstance instance;
	private final ManagedProcess process;
	private final BufferedWriter writer;
	private final Thread readerThread;
	private final AtomicLong nextId = new AtomicLong(1);
	private final Map<Long, CompletableFuture<JsonObject>> pending = new ConcurrentHashMap<>();
	private final Deque<String> stderrTail = new ArrayDeque<>();
	private volatile @Nullable Listener listener;
	private volatile boolean closed;

	private DshAcpClient(DshInstance instance,
	                     List<String> command,
	                     Path workingDirectory,
	                     Map<String, String> environment,
	                     Listener listener) throws DshException {
		this.instance = instance;
		this.listener = listener;

		ProcessBuilder builder = new ProcessBuilder(command);
		builder.directory(workingDirectory.toFile());
		builder.environment().putAll(environment);

		try {
			this.process = new ManagedProcess(builder);
		} catch (IOException e) {
			throw new DshException("Failed to start the ACP session: " + e.getMessage(), e);
		}

		this.writer = new BufferedWriter(new OutputStreamWriter(process.getProcess().getOutputStream(),
		                                                        StandardCharsets.UTF_8));

		process.pumpInputStream(this::handleLine);
		process.pumpErrorStream(line -> {
			synchronized (stderrTail) {
				stderrTail.addLast(line);
				while (stderrTail.size() > 200) {
					stderrTail.removeFirst();
				}
			}
			LOG.info("[acp] " + line);
		});

		this.readerThread = new Thread(this::awaitExit, "HDSL acp waiter");
		this.readerThread.setDaemon(true);
		this.readerThread.start();
	}

	private static List<String> commandFor(DshInstance instance) throws DshException {
		DshNodeRuntime runtime = DshLauncher.resolveRuntime(instance);
		HarnessInstaller.requireInstalled(instance);
		Path script = instance.dshEntryPoint();
		return List.of(runtime.node().toString(), script.toString(), "--profile", "acp");
	}

	private static Map<String, String> environmentFor(DshInstance instance) throws DshException {
		Map<String, String> environment = new LinkedHashMap<>();
		environment.put("DSH_HOME", instance.homeDirectory().toString());
		environment.putAll(DshEnvironment.of(instance));
		return environment;
	}

	/**
	 * Starts a client for a DeepSeek Harness instance.
	 *
	 * @param instance the instance to connect to
	 * @param listener the listener for session events
	 * @return the connected client
	 * @throws DshException when the process fails to start or the handshake fails
	 */
	public static DshAcpClient connect(DshInstance instance, Listener listener) throws DshException {
		return connect(instance, commandFor(instance), instance.workspacePath(), environmentFor(instance), listener);
	}

	/**
	 * Starts a client over an explicitly supplied command and transport.
	 *
	 * @param instance         the instance the session is attributed to
	 * @param command          the exact command to run
	 * @param workingDirectory the directory sessions are scoped to
	 * @param environment      extra environment variables for the process
	 * @param listener         the listener for session events
	 * @return the connected client
	 * @throws DshException when the process fails to start or the handshake fails
	 */
	public static DshAcpClient connect(DshInstance instance,
	                                   List<String> command,
	                                   Path workingDirectory,
	                                   Map<String, String> environment,
	                                   Listener listener) throws DshException {
		DshAcpClient client = new DshAcpClient(instance, command, workingDirectory, environment, listener);
		try {
			client.handshake();
		} catch (DshException e) {
			client.close();
			throw e;
		}
		return client;
	}

	private static String textOf(JsonObject update) {
		JsonObject content = update.getAsJsonObject("content");
		return content == null ? "" : stringOf(content, "text");
	}

	private static String stringOf(JsonObject object, String name) {
		JsonElement element = object.get(name);
		return element == null || element.isJsonNull() || !element.isJsonPrimitive() ? "" : element.getAsString();
	}

	private void handshake() throws DshException {
		JsonObject clientInfo = new JsonObject();
		clientInfo.addProperty("name", "HDSL");
		clientInfo.addProperty("version", Metadata.VERSION);

		JsonObject params = new JsonObject();
		params.addProperty("protocolVersion", PROTOCOL_VERSION);
		params.add("clientCapabilities", new JsonObject());
		params.add("clientInfo", clientInfo);

		JsonObject result = request("initialize", params);
		JsonElement version = result.get("protocolVersion");
		if (version != null && version.getAsInt() != PROTOCOL_VERSION) {
			throw new DshException(
					"DeepSeek Harness speaks ACP v" + version.getAsInt() + " but this launcher speaks v" +
					PROTOCOL_VERSION);
		}
	}

	/**
	 * Creates a new session rooted at the instance working directory.
	 *
	 * @return the new session ID
	 * @throws DshException when the server rejects the request
	 */
	public String newSession() throws DshException {
		JsonObject params = new JsonObject();
		params.addProperty("cwd", instance.workspacePath().toString());
		params.add("mcpServers", new JsonArray());

		JsonObject result = request("session/new", params);
		JsonElement sessionId = result.get("sessionId");
		if (sessionId == null || !sessionId.isJsonPrimitive()) {
			throw new DshException("DeepSeek Harness returned no session id");
		}
		return sessionId.getAsString();
	}

	/**
	 * Sends a prompt to an active session and waits for it to complete.
	 *
	 * @param sessionId the session to prompt
	 * @param text      the prompt text
	 * @throws DshException when the request fails
	 */
	public void prompt(String sessionId, String text) throws DshException {
		JsonObject prompt = new JsonObject();
		prompt.addProperty("type", "text");
		prompt.addProperty("text", text);
		JsonArray blocks = new JsonArray();
		blocks.add(prompt);

		JsonObject params = new JsonObject();
		params.addProperty("sessionId", sessionId);
		params.add("prompt", blocks);

		JsonObject result = request("session/prompt", params);
		JsonElement stopReason = result.get("stopReason");
		Listener current = listener;
		if (current != null) {
			current.onPromptFinished(stopReason == null ? "unknown" : stopReason.getAsString());
		}
	}

	/**
	 * Requests cancellation of an in-flight prompt in the specified session.
	 *
	 * @param sessionId the session to cancel
	 */
	public void cancel(String sessionId) {
		JsonObject params = new JsonObject();
		params.addProperty("sessionId", sessionId);
		sendNotification("session/cancel", params);
	}

	/**
	 * Returns recent lines captured from the child process standard error stream.
	 *
	 * @return list of stderr lines
	 */
	public List<String> stderrTail() {
		synchronized (stderrTail) {
			return List.copyOf(stderrTail);
		}
	}

	/**
	 * Reports whether the child process is still running.
	 *
	 * @return true if the connection is active
	 */
	public boolean isRunning() {
		return !closed && process.isRunning();
	}

	/**
	 * Detaches the event listener from this client.
	 */
	public void detachListener() {
		listener = null;
	}

	@Override
	public void close() {
		if (closed) {
			return;
		}
		closed = true;

		for (CompletableFuture<JsonObject> future : pending.values()) {
			future.completeExceptionally(new DshException("The ACP connection was closed"));
		}
		pending.clear();

		// Closing stdin sends EOF to trigger graceful server shutdown
		try {
			writer.close();
		} catch (IOException e) {
			LOG.warning("Failed to close the ACP input", e);
		}

		Process raw = process.getProcess();
		try {
			if (!raw.waitFor(5, TimeUnit.SECONDS)) {
				raw.destroy();
				if (!raw.waitFor(5, TimeUnit.SECONDS)) {
					raw.destroyForcibly();
				}
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			raw.destroyForcibly();
		}
		process.destroyRelatedThreads();
	}

	private JsonObject request(String method, JsonObject params) throws DshException {
		long id = nextId.getAndIncrement();
		CompletableFuture<JsonObject> future = new CompletableFuture<>();
		pending.put(id, future);

		JsonObject message = new JsonObject();
		message.addProperty("jsonrpc", "2.0");
		message.addProperty("id", id);
		message.addProperty("method", method);
		message.add("params", params);
		send(message);

		try {
			return future.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
		} catch (TimeoutException e) {
			pending.remove(id);
			throw new DshException(
					"DeepSeek Harness did not answer `" + method + "` within " + REQUEST_TIMEOUT_SECONDS + "s");
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new DshException("Interrupted while waiting for `" + method + "`", e);
		} catch (ExecutionException e) {
			Throwable cause = e.getCause() == null ? e : e.getCause();
			throw cause instanceof DshException dsh ? dsh : new DshException(String.valueOf(cause.getMessage()), cause);
		}
	}

	private void sendNotification(String method, JsonObject params) {
		JsonObject message = new JsonObject();
		message.addProperty("jsonrpc", "2.0");
		message.addProperty("method", method);
		message.add("params", params);
		send(message);
	}

	private synchronized void send(JsonObject message) {
		if (closed) {
			return;
		}
		try {
			writer.write(message.toString());
			writer.write('\n');
			writer.flush();
		} catch (IOException e) {
			LOG.warning("Failed to write to the ACP connection", e);
		}
	}

	private void handleLine(String line) {
		String trimmed = line.trim();
		if (trimmed.isEmpty()) {
			return;
		}

		JsonObject message;
		try {
			JsonElement parsed = JsonParser.parseString(trimmed);
			if (!parsed.isJsonObject()) {
				return;
			}
			message = parsed.getAsJsonObject();
		} catch (RuntimeException e) {
			LOG.info("[acp stdout] " + trimmed);
			return;
		}

		JsonElement idElement = message.get("id");
		if (idElement != null && idElement.isJsonPrimitive()) {
			CompletableFuture<JsonObject> future = pending.remove(idElement.getAsLong());
			if (future == null) {
				return;
			}
			JsonElement error = message.get("error");
			if (error != null && error.isJsonObject()) {
				JsonElement text = error.getAsJsonObject().get("message");
				future.completeExceptionally(new DshException(
						text == null ? "The ACP request failed" : text.getAsString()));
				return;
			}
			JsonElement result = message.get("result");
			future.complete(result != null && result.isJsonObject() ? result.getAsJsonObject() : new JsonObject());
			return;
		}

		JsonElement method = message.get("method");
		if (method != null && "session/update".equals(method.getAsString())) {
			dispatchUpdate(message.getAsJsonObject("params"));
		}
	}

	private void dispatchUpdate(@Nullable JsonObject params) {
		Listener current = listener;
		if (current == null || params == null) {
			return;
		}
		JsonObject update = params.getAsJsonObject("update");
		if (update == null) {
			return;
		}
		JsonElement kindElement = update.get("sessionUpdate");
		if (kindElement == null) {
			return;
		}

		switch (kindElement.getAsString()) {
			case "agent_message_chunk" -> current.onText(textOf(update));
			case "agent_thought_chunk" -> current.onThought(textOf(update));
			case "tool_call", "tool_call_update" ->
					current.onToolCall(stringOf(update, "title"), stringOf(update, "status"));
			default -> {
			}
		}
	}

	private void awaitExit() {
		try {
			int code = process.getProcess().waitFor();
			for (CompletableFuture<JsonObject> future : pending.values()) {
				future.completeExceptionally(new DshException("DeepSeek Harness exited with code " + code));
			}
			pending.clear();
			Listener current = listener;
			if (current != null && !closed) {
				current.onFailure("DeepSeek Harness exited with code " + code);
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	/**
	 * Receives events from an active ACP session.
	 */
	public interface Listener {
		/**
		 * Called when an assistant text response chunk is received.
		 *
		 * @param text the text chunk
		 */
		default void onText(String text) {
		}

		/**
		 * Called when an assistant reasoning thought chunk is received.
		 *
		 * @param text the thought chunk
		 */
		default void onThought(String text) {
		}

		/**
		 * Called when a tool call starts or its status updates.
		 *
		 * @param title  the tool title
		 * @param status the reported status
		 */
		default void onToolCall(String title, String status) {
		}

		/**
		 * Called when prompt execution finishes.
		 *
		 * @param stopReason the reason execution stopped
		 */
		default void onPromptFinished(String stopReason) {
		}

		/**
		 * Called when connection failure or process exit occurs.
		 *
		 * @param message description of the failure
		 */
		default void onFailure(String message) {
		}
	}
}
