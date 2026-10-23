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
package org.jackhuang.hmcl.ui.dsh;

import javafx.application.Platform;
import javafx.scene.image.Image;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.jackhuang.hmcl.task.Schedulers.io;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/**
 * Pictures fetched from an address, and kept.
 *
 * <p>A fetch that fails yields no picture: the failure is not remembered, and the next ask for
 * that address tries again.
 */
@NotNullByDefault
public final class RemoteIconLoader {

	/** How many pictures are kept */
	private static final int MAX_CACHED = 256;

	/** The pictures already fetched, by address. */
	private static final Map<String, Image> ICONS =
			Collections.synchronizedMap(new LinkedHashMap<>(64, 0.75f, true) {
				@Override
				protected boolean removeEldestEntry(Map.Entry<String, Image> eldest) {
					return size() > MAX_CACHED;
				}
			});

	/** The client the pictures are fetched with */
	private static final HttpClient CLIENT = HttpClient.newBuilder()
		.connectTimeout(Duration.ofSeconds(10))
		.followRedirects(HttpClient.Redirect.NORMAL)
		.build();

	private RemoteIconLoader() {
	}

	/**
	 * Returns the picture already fetched for an address.
	 *
	 * @param url the address
	 * @return the picture, or {@code null} when no successfully fetched picture is cached for the
	 *         address
	 */
	public static @Nullable Image cached(String url) {
		return ICONS.get(url);
	}

	/**
	 * Fetches a picture and hands it over on the interface thread.
	 *
	 * @param url      the address
	 * @param onLoaded what to do with the picture; it is not called when the fetch fails
	 */
	public static void load(String url, Consumer<Image> onLoaded) {
		CompletableFuture.supplyAsync(() -> {
			try {
				HttpRequest request = HttpRequest.newBuilder(URI.create(url))
				                                 .timeout(Duration.ofSeconds(15))
				                                 .GET()
				                                 .build();
				HttpResponse<byte[]> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofByteArray());
				if (response.statusCode() < 200 || response.statusCode() >= 300) {
					return null;
				}
				Image image = new Image(new ByteArrayInputStream(response.body()));
				return image.isError() || image.getWidth() <= 0 ? null : image;
			} catch (IOException | InterruptedException | RuntimeException e) {
				if (e instanceof InterruptedException) {
					Thread.currentThread().interrupt();
				}
				LOG.info("Could not fetch the picture at " + url + ": " + e.getMessage());
				return null;
			}
		}, io()).thenAccept(image -> Platform.runLater(() -> {
			if (image != null) {
				ICONS.put(url, image);
				onLoaded.accept(image);
			}
		}));
	}
}
