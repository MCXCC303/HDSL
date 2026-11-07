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

import com.google.gson.JsonObject;
import com.jfoenix.controls.JFXButton;
import com.mcxcc303.hdsl.core.DshException;
import com.mcxcc303.hdsl.instance.InstanceStore;
import com.mcxcc303.hdsl.pack.DshPackMarket;
import com.mcxcc303.hdsl.pack.DspackContainer;
import com.mcxcc303.hdsl.pack.DspackDownload;
import com.mcxcc303.hdsl.pack.DspackLander;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.*;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.construct.TwoLineListItem;
import org.jackhuang.hmcl.ui.decorator.DecoratorAnimatedPage;
import org.jackhuang.hmcl.ui.decorator.DecoratorPage;
import org.jackhuang.hmcl.ui.wizard.Refreshable;
import org.jackhuang.hmcl.util.gson.JsonUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

// Displays details for a community pack and allows installing it.
@NotNullByDefault
public final class PackDetailPage extends DecoratorAnimatedPage implements DecoratorPage, Refreshable {
	private final DshPackMarket.Entry entry;
	private final ReadOnlyObjectWrapper<State> state;
	private final Label manifestLine = new Label(i18n("dsh.pack.loading"));
	private final Label readme = new Label();
	private final JFXButton install = new JFXButton(i18n("download.install"));
	private final BooleanProperty refreshable = new SimpleBooleanProperty(this, "refreshable", true);
	private @Nullable DspackContainer.Container container;

	public PackDetailPage(DshPackMarket.Entry entry) {
		this.entry = entry;
		this.state = new ReadOnlyObjectWrapper<>(new State(entry.title(), null, true, true, true));

		BorderPane layout = new BorderPane();
		layout.setPadding(new Insets(10));
		layout.setTop(buildHeader());
		layout.setCenter(buildBody());
		BorderPane.setMargin(layout.getCenter(), new Insets(10, 0, 0, 0));

		setCenter(layout);
		refresh();
	}

	private static int fact(GridPane grid, int row, String name, String value) {
		Label label = new Label(value);
		label.setWrapText(true);
		grid.add(new Label(name), 0, row);
		grid.add(label, 1, row);
		return row + 1;
	}

	private static @Nullable String get(String url) {
		return DshPackMarket.document(url);
	}

	private Node buildHeader() {
		BorderPane card = new BorderPane();
		card.getStyleClass().add("card");
		card.setPadding(new Insets(10));

		TwoLineListItem content = new TwoLineListItem();
		content.setTitle(entry.title());
		content.setSubtitle(entry.subtitle());
		content.addTags(List.of(entry.id(), PackMarketPage.sizeOf(entry.size())));
		if (entry.type() != null) {
			content.addTag(entry.type());
		}
		if (entry.bundleCount() != null) {
			content.addTag(i18n("dsh.pack.bundles", entry.bundleCount()));
		}
		BorderPane.setAlignment(content, Pos.CENTER_LEFT);
		card.setCenter(content);

		install.getStyleClass().add("dialog-accept");
		install.setOnAction(event -> install());

		HBox actions = new HBox(8, install);
		actions.setAlignment(Pos.CENTER_RIGHT);
		card.setRight(actions);
		return card;
	}

	private Node buildBody() {
		VBox box = new VBox(10);

		GridPane facts = new GridPane();
		facts.getStyleClass().add("card");
		facts.setPadding(new Insets(10));
		facts.setHgap(15);
		facts.setVgap(8);

		ColumnConstraints names = new ColumnConstraints();
		names.setMinWidth(Region.USE_PREF_SIZE);
		facts.getColumnConstraints().addAll(names, new ColumnConstraints());

		int row = 0;
		row = fact(facts, row, i18n("dsh.pack.field.id"), entry.id());
		row = fact(facts, row, i18n("dsh.pack.field.version"), entry.version());
		if (entry.author() != null) {
			row = fact(facts, row, i18n("archive.author"), entry.author());
		}
		if (entry.dshVersion() != null) {
			row = fact(facts, row, i18n("dsh.pack.field.dsh"), entry.dshVersion());
		}
		if (entry.profileName() != null) {
			row = fact(facts, row, i18n("dsh.pack.field.profile"), entry.profileName());
		}
		if (entry.category() != null) {
			row = fact(facts, row, i18n("addon.category"), entry.category());
		}
		if (entry.updatedAt() != null) {
			row = fact(facts, row, i18n("dsh.pack.field.updated"), entry.updatedAt());
		}
		row = fact(facts, row, i18n("dsh.pack.field.size"), PackMarketPage.sizeOf(entry.size()));
		Label digest = new Label(entry.sha256());
		digest.setWrapText(true);
		digest.getStyleClass().add("desc");
		facts.add(new Label(i18n("dsh.pack.field.sha256")), 0, row);
		facts.add(digest, 1, row);
		row++;
		facts.add(new Label(i18n("dsh.pack.field.overrides")), 0, row);
		facts.add(manifestLine, 1, row);

		box.getChildren().add(facts);

		readme.setWrapText(true);
		readme.setPadding(new Insets(10));
		readme.getStyleClass().add("card");
		readme.setMaxWidth(Double.MAX_VALUE);
		ScrollPane scroll = new ScrollPane(readme);
		scroll.setFitToWidth(true);
		scroll.setPrefHeight(400);
		VBox.setVgrow(scroll, Priority.ALWAYS);
		box.getChildren().add(scroll);
		return box;
	}

	@Override
	public void refresh() {
		manifestLine.setText(i18n("dsh.pack.loading"));

		CompletableFuture.supplyAsync(() -> get(DshPackMarket.manifestUrl(entry)), Schedulers.io())
		                 .whenComplete((manifest, failure) -> Platform.runLater(() -> {
			                 if (getScene() == null) {
				                 return;
			                 }
			                 if (failure != null) {
				                 manifestLine.setText(i18n("dsh.pack.manifest.unreadable"));
			                 } else {
				                 readManifest(manifest);
			                 }
		                 }));

		CompletableFuture.supplyAsync(() -> get(DshPackMarket.readmeUrl(entry)), Schedulers.io())
		                 .whenComplete((text, failure) -> Platform.runLater(() -> {
			                 if (getScene() == null || failure != null) {
				                 return;
			                 }
			                 showReadme(text);
		                 }));
	}

	private void readManifest(@Nullable String body) {
		if (body == null || body.isBlank()) {
			manifestLine.setText(i18n("dsh.pack.manifest.missing"));
			return;
		}
		try {
			var manifest = JsonUtils.fromJson(body, JsonObject.class);
			if (manifest == null) {
				manifestLine.setText(i18n("dsh.pack.manifest.malformed"));
				return;
			}
			int bundles = manifest.has("bundles") && manifest.get("bundles").isJsonArray()
			              ? manifest.getAsJsonArray("bundles").size()
			              : 0;
			int dependencies = manifest.has("dependencies") && manifest.get("dependencies").isJsonObject()
			                   ? manifest.getAsJsonObject("dependencies").size()
			                   : 0;
			manifestLine.setText(i18n("dsh.pack.manifest.read", bundles, dependencies));
		} catch (RuntimeException e) {
			manifestLine.setText(i18n("dsh.pack.manifest.malformed"));
		}
	}

	private void showReadme(@Nullable String body) {
		if (body == null || body.isBlank()) {
			return;
		}
		readme.setText(body);
	}

	private void install() {
		Path archive = DspackDownload.archiveFile(entry);
		String id = suggestedId();

		ProgressDialog.run(i18n("dsh.pack.installing", entry.title()), report -> {
			report.accept("Downloading " + entry.downloadUrl());
			DspackDownload.download(entry, report::accept);
			report.accept("The pack matches the market's size and SHA-256");

			DspackContainer.Container container0 = DspackContainer.identify(archive);
			report.accept("Container version " + container0.containerVersion() + ", manifest version " +
			              container0.manifestVersion());
			container = container0;

			String profile = container0.profileName(entry.profileName() == null ? "pack" : entry.profileName());
			String version = container0.text("dshVersion");
			if (version == null || version.isBlank()) {
				version = entry.dshVersion();
			}
			if (version == null || version.isBlank()) {
				throw new DshException("The pack does not say which DeepSeek Harness version it " +
				                       "needs, and one must be installed before it can be", null);
			}

			DspackLander.installNew(archive, id, profile, version, report::accept);
		}, () -> {
			Controllers.navigate(MainPage.instance().getInstancesPage());
		});
	}

	private String suggestedId() {
		String base = entry.name() == null || entry.name().isBlank() ? entry.id() : entry.name();
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

	@Override
	public ReadOnlyObjectWrapper<State> stateProperty() {
		return state;
	}

	@Override
	public BooleanProperty refreshableProperty() {
		return refreshable;
	}
}
