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
import com.mcxcc303.hdsl.core.DshCommand;
import com.mcxcc303.hdsl.core.DshException;
import com.mcxcc303.hdsl.instance.DshInstance;
import com.mcxcc303.hdsl.plugin.Catalog;
import com.mcxcc303.hdsl.plugin.DshPluginInstaller;
import com.mcxcc303.hdsl.runtime.DshNodeRuntime;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.image.Image;
import javafx.scene.layout.*;
import javafx.stage.DirectoryChooser;
import org.jackhuang.hmcl.setting.GameDirectoryManager;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.construct.*;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane.MessageType;
import org.jackhuang.hmcl.ui.decorator.DecoratorAnimatedPage;
import org.jackhuang.hmcl.ui.decorator.DecoratorPage;
import org.jackhuang.hmcl.ui.wizard.Refreshable;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/**
 * One plugin, and the versions of it that can be installed.
 */
@NotNullByDefault
public final class PluginDetailPage extends DecoratorAnimatedPage implements DecoratorPage, Refreshable {
	// The plugin being described
	private final Catalog.Plugin plugin;

	// The instance this page installs into, or null to use the launcher's selected one
	private final @Nullable DshInstance target;

	// The page's state published to the window decorator
	private final ReadOnlyObjectWrapper<State> state;

	private final PluginVersionList versions;

	// Creates the page, about whichever instance is selected.
	public PluginDetailPage(Catalog.Plugin plugin) {
		this(plugin, null);
	}

	// Creates the page for one instance.
	public PluginDetailPage(Catalog.Plugin plugin, @Nullable DshInstance target) {
		this.plugin = plugin;
		this.target = target;
		this.state = new ReadOnlyObjectWrapper<>(State.fromTitle(plugin.name()));
		this.versions = new PluginVersionList(plugin.npm(),
		                                      plugin.version(),
		                                      plugin.npm() == null ? plugin.name() : plugin.npm(),
		                                      this::choose,
		                                      version -> installInto(target(), version));

		// A border pane rather than a box: its centre takes whatever height is left.
		BorderPane layout = new BorderPane();
		layout.setPadding(new Insets(10));
		layout.setTop(buildHeader());
		layout.setCenter(versions);
		BorderPane.setMargin(layout.getCenter(), new Insets(10, 0, 0, 0));

		setCenter(layout);
		refresh();
	}

	// Opens a plugin's own page in the browser.
	private static void openExternal(String url) {
		Schedulers.io().execute(() -> {
			try {
				if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
					Desktop.getDesktop().browse(URI.create(url));
				} else {
					LOG.warning("No desktop integration to open " + url);
				}
			} catch (Exception e) {
				LOG.warning("Failed to open " + url, e);
			}
		});
	}

	// Builds the header: what the plugin is, and where its own page is.
	private Node buildHeader() {
		BorderPane card = new BorderPane();
		card.getStyleClass().add("card");
		card.setPadding(new Insets(10));

		// The picture is the owner's, the same one the market list shows.
		StackPane icon = new StackPane();
		icon.getStyleClass().add("installer-item-image");
		ImageContainer picture = new ImageContainer(32);
		icon.getChildren().add(picture);
		icon.setPadding(new Insets(0, 10, 0, 0));
		card.setLeft(icon);

		String avatar = plugin.ownerAvatarUrl();
		if (avatar != null) {
			Image cached = RemoteIconLoader.cached(avatar);
			if (cached != null) {
				picture.setImage(cached);
			} else {
				RemoteIconLoader.load(avatar, picture::setImage);
			}
		}

		TwoLineListItem content = new TwoLineListItem();
		content.setTitle(plugin.name());
		content.setSubtitle(plugin.localizedDescription() == null ? plugin.owner() : plugin.localizedDescription());
		content.addTags(Stream.of(plugin.category(), plugin.owner())
		                      .filter(tag -> tag != null && !tag.isBlank())
		                      .toList());
		content.addTag(plugin.sourceKind());
		if (plugin.version() != null && !plugin.version().isBlank()) {
			content.addTag(plugin.version());
		}
		card.setCenter(content);

		if (plugin.url() != null && plugin.url().startsWith("http")) {
			JFXButton page = new JFXButton(i18n("download.release_page"));
			// A plugin published as a package has a package page rather than a repository one.
			if (!plugin.hasRepository()) {
				page.setText(i18n("dsh.market.package_page"));
			}
			page.setGraphic(SVG.OPEN_IN_NEW.createIcon(20));
			page.getStyleClass().add("jfx-tool-bar-button");
			page.setOnAction(event -> openExternal(plugin.url()));
			HBox right = new HBox(page);
			right.setAlignment(Pos.CENTER_RIGHT);
			card.setRight(right);
		}

		return card;
	}

	// Reads the versions the registry lists, judged against the harness the target instance runs.
	@Override
	public void refresh() {
		versions.refresh(target(), null, null);
	}

	private @Nullable DshInstance target() {
		return target != null ? target : GameDirectoryManager.selectedInstanceProperty().get();
	}

	// Asks what to do with one version.
	private void choose(String version) {
		DshInstance instance = target();
		String title = i18n("mods.download.title", plugin.name() + " " + version);
		MessageDialogPane.Builder builder = new MessageDialogPane.Builder(
				plugin.localizedDescription() == null ? plugin.name() : plugin.localizedDescription(),
				title,
				MessageType.QUESTION);

		builder.addAction(i18n("mods.install"), () -> installInto(instance, version));
		if (plugin.npm() != null) {
			builder.addAction(i18n("mods.save_as"), () -> saveToFolder(version));
		}
		builder.addCancel(null);
		Controllers.dialog(builder.build());
	}

	/**
	 * Installs one version into an instance.
	 *
	 * @param instance the instance, or null when none is selected
	 * @param version  the version
	 */
	private void installInto(@Nullable DshInstance instance, String version) {
		if (instance == null) {
			Controllers.dialog(i18n("dsh.market.no_instance"), i18n("download.install"), MessageType.ERROR);
			return;
		}

		String spec = plugin.npm() != null
		              ? plugin.npm() + "@" + version
		              : (plugin.installSpec() == null ? null : plugin.installSpec());
		if (spec == null) {
			Controllers.dialog(i18n("dsh.market.not_installable", plugin.name()),
			                   i18n("download.install"),
			                   MessageType.ERROR);
			return;
		}

		String finalSpec = spec;
		PluginInstalls.run(instance,
		                   progress -> DshPluginInstaller.installSpecs(instance, List.of(finalSpec), progress::accept),
		                   () -> fireEvent(new PageCloseEvent()));
	}

	/**
	 * Downloads one version into a folder the user chooses.
	 *
	 * @param version the version
	 */
	private void saveToFolder(String version) {
		if (plugin.npm() == null) {
			return;
		}
		DirectoryChooser chooser = new DirectoryChooser();
		chooser.setTitle(i18n("mods.save_as"));
		File chosen = chooser.showDialog(Controllers.getStage());
		if (chosen == null) {
			return;
		}

		Path folder = chosen.toPath();
		String spec = plugin.npm() + "@" + version;
		ProgressDialog.run(i18n("mods.save_as"), progress -> {
			DshNodeRuntime runtime = DshNodeRuntime.detect().orElse(null);
			if (runtime == null || runtime.npm() == null) {
				throw new DshException("npm was not found on PATH; saving a package requires it");
			}
			progress.accept("Fetching " + spec);
			try {
				DshCommand.run(List.of(runtime.npm().toString(), "pack", spec, "--pack-destination", folder.toString()),
				               null,
				               progress::accept);
			} catch (IOException e) {
				throw new DshException("Failed to run npm", e);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new DshException("Saving the package was interrupted", e);
			}
		}, null);
	}

	@Override
	public ReadOnlyObjectProperty<State> stateProperty() {
		return state.getReadOnlyProperty();
	}
}
