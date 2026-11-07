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

import com.jfoenix.controls.JFXButton;
import com.jfoenix.effects.JFXDepthManager;
import com.mcxcc303.hdsl.core.DshException;
import com.mcxcc303.hdsl.harness.HarnessInstaller;
import com.mcxcc303.hdsl.instance.DshInstance;
import com.mcxcc303.hdsl.instance.DshInstanceManager;
import com.mcxcc303.hdsl.instance.InstanceStore;
import com.mcxcc303.hdsl.pack.*;
import com.mcxcc303.hdsl.plugin.DshPluginInstaller;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane;
import org.jackhuang.hmcl.ui.construct.TwoLineListItem;
import org.jackhuang.hmcl.ui.decorator.DecoratorAnimatedPage;
import org.jackhuang.hmcl.ui.decorator.DecoratorPage;
import org.jackhuang.hmcl.ui.wizard.Refreshable;
import org.jackhuang.hmcl.util.logging.Logger;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

/**
 * Installs a pack from a local file, a URL, or the market catalogue.
 * <p>
 * Supports drag-and-drop file import across the entire pane. Once an archive source is resolved,
 * the container is validated and unpacked into a new instance profile.
 */
@NotNullByDefault
public final class PackInstallPage extends DecoratorAnimatedPage implements DecoratorPage, Refreshable {
	private final ReadOnlyObjectWrapper<State> state = new ReadOnlyObjectWrapper<>(State.fromTitle(i18n(
			"install.modpack")));

	public PackInstallPage() {
		VBox content = new VBox(8);
		content.setMaxWidth(400);
		content.setMaxHeight(Region.USE_PREF_SIZE);
		content.setSpacing(8);
		content.setPadding(new Insets(20));

		Label title = new Label(i18n("install.modpack"));
		title.setPadding(new Insets(8));

		content.getChildren()
		       .addAll(title,
		               card("modpack.choose.local", this::chooseLocalFile),
		               card("modpack.choose.remote", this::chooseRemoteFile),
		               card("dsh.pack.choose.market", this::openCatalogue));

		FXUtils.applyDragListener(content, path -> isPack(path), files -> {
			if (!files.isEmpty()) {
				install(files.get(0));
			}
		});

		StackPane pane = new StackPane(content);
		pane.setAlignment(Pos.CENTER);
		pane.setPadding(new Insets(10));
		setCenter(pane);
	}

	private static boolean isPack(Path file) {
		String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
		return name.endsWith(".dspack") || name.endsWith(".hdslp") || name.endsWith(".zip");
	}

	private static boolean isOldFormat(Path archive) {
		String name = archive.getFileName().toString().toLowerCase(Locale.ROOT);
		return !name.endsWith(".dspack");
	}

	private static String uniqueId(@Nullable String name) {
		String base = name == null || name.isBlank() ? "pack" : name;
		base = base.replaceAll("[^A-Za-z0-9._-]", "-");
		if (base.isEmpty() || !Character.isLetterOrDigit(base.charAt(0))) {
			base = "pack-" + base;
		}
		if (InstanceStore.find(base) == null) {
			return base;
		}
		for (int i = 2; i < 1000; i++) {
			if (InstanceStore.find(base + "-" + i) == null) {
				return base + "-" + i;
			}
		}
		return base + "-" + System.currentTimeMillis();
	}

	private Node card(String key, Runnable action) {
		JFXButton button = new JFXButton();
		button.getStyleClass().add("card");
		button.setStyle("-fx-cursor: HAND;");
		button.setMaxWidth(Double.MAX_VALUE);
		button.setOnAction(event -> action.run());

		BorderPane graphic = new BorderPane();
		graphic.setMouseTransparent(true);
		graphic.setLeft(new TwoLineListItem(i18n(key), i18n(key + ".detail")));

		Node arrow = SVG.ARROW_FORWARD.createIcon();
		BorderPane.setAlignment(arrow, Pos.CENTER);
		graphic.setRight(arrow);

		button.setGraphic(graphic);
		GridPane.setHgrow(button, Priority.ALWAYS);
		JFXDepthManager.setDepth(button, 1);
		return button;
	}

	private void chooseLocalFile() {
		FileChooser chooser = new FileChooser();
		chooser.setTitle(i18n("modpack.choose"));
		chooser.getExtensionFilters()
		       .add(new FileChooser.ExtensionFilter(i18n("modpack"), "*.dspack", "*.hdslp", "*.zip"));
		Path chosen = Controllers.showOpenDialog(chooser);
		if (chosen != null) {
			install(chosen);
		}
	}

	private void chooseRemoteFile() {
		Controllers.prompt(i18n("modpack.choose.remote.tooltip"), (url, handler) -> {
			if (url == null || url.isBlank()) {
				handler.reject(i18n("dsh.pack.url.empty"));
				return;
			}
			Path target = DspackDownload.cacheDirectory()
			                            .resolve("downloaded-" + System.currentTimeMillis() + ".dspack");
			try {
				Files.createDirectories(target.getParent());
				HttpRequest request = HttpRequest.newBuilder(URI.create(url.trim()))
				                                 .timeout(Duration.ofMinutes(10))
				                                 .GET()
				                                 .build();
				try (HttpClient client = HttpClient.newBuilder()
				                                   .connectTimeout(Duration.ofSeconds(20))
				                                   .followRedirects(HttpClient.Redirect.NORMAL)
				                                   .build()) {
					HttpResponse<Path> response = client.send(request, HttpResponse.BodyHandlers.ofFile(target));
					if (response.statusCode() < 200 || response.statusCode() >= 300) {
						handler.reject(i18n("dsh.pack.url.failed", response.statusCode()));
						return;
					}
				}
				handler.resolve();
				install(target);
			} catch (IOException | InterruptedException | RuntimeException e) {
				if (e instanceof InterruptedException) {
					Thread.currentThread().interrupt();
				}
				handler.reject(String.valueOf(e.getMessage()));
			}
		});
	}

	private void openCatalogue() {
		Controllers.navigate(new PackMarketPage());
	}

	private void install(Path archive) {
		if (isOldFormat(archive)) {
			installOlderFormat(archive);
			return;
		}

		String title = i18n("dsh.pack.installing", archive.getFileName().toString());
		DspackContainer.Container container;
		try {
			container = DspackContainer.identify(archive);
		} catch (DshException e) {
			Controllers.dialog(e.getMessage(), title, MessageDialogPane.MessageType.ERROR);
			return;
		}
		String version = container.text("dshVersion");
		if (version == null || version.isBlank()) {
			Controllers.dialog("这个整合包没有写明需要哪个 DeepSeek Harness 版本，" + "而必须先装好一个才能安装它",
			                   title,
			                   MessageDialogPane.MessageType.ERROR);
			return;
		}
		String id = uniqueId(container.text("name"));
		String profile = container.profileName("pack");

		PluginInstalls.runCreating(title, () -> InstanceStore.find(id), report -> {
			report.accept("Container version " + container.containerVersion() + ", manifest version " +
			              container.manifestVersion());

			DshInstance existing = InstanceStore.find(id);
			if (existing == null) {
				DspackLander.installNew(archive, id, profile, version, report::accept);
				return;
			}
			report.accept("Finishing " + id + " now that the install scripts are answered");
			DspackLander.finish(archive, existing, report::accept);
		}, () -> Controllers.navigate(MainPage.instance().getInstancesPage()));
	}

	private void installOlderFormat(Path archive) {
		String title = i18n("dsh.pack.installing", archive.getFileName().toString());
		String id;
		try {
			id = uniqueId(PackManifest.readManifest(archive).name());
		} catch (DshException e) {
			Controllers.dialog(e.getMessage(), title, MessageDialogPane.MessageType.ERROR);
			return;
		}

		boolean existed = InstanceStore.find(id) != null;
		PluginInstalls.runCreating(title, () -> InstanceStore.find(id), report -> {
			try {
				report.accept("Installing " + id + " from the older pack format");
				ModpackInstaller.install(archive, id, Path.of(System.getProperty("user.home")), report::accept);
			} catch (DshException | RuntimeException failed) {
				if (failed instanceof DshPluginInstaller.DshBuildScriptApprovalRequired) {
					throw failed;
				}
				DshInstance made = InstanceStore.find(id);
				if (!existed && made != null) {
					HarnessInstaller.discardPartial(made);
					try {
						DshInstanceManager.delete(id);
					} catch (DshException | RuntimeException cleanupFailure) {
						Logger.LOG.warning("Could not remove the half-made instance " + id, cleanupFailure);
					}
				}
				throw failed;
			}
		}, () -> Controllers.navigate(MainPage.instance().getInstancesPage()));
	}

	@Override
	public ReadOnlyObjectWrapper<State> stateProperty() {
		return state;
	}
}
