/*
 * HMCL-DSH
 * Copyright (C) 2026  HMCL-DSH contributors
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
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.dsh.DshException;
import org.jackhuang.hmcl.dsh.DshInstance;
import org.jackhuang.hmcl.dsh.DshInstanceManager;
import org.jackhuang.hmcl.dsh.DshPackInstaller;
import org.jackhuang.hmcl.dsh.DshPackMarket;
import org.jackhuang.hmcl.setting.SettingsManager;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane.MessageType;
import org.jackhuang.hmcl.ui.construct.TwoLineListItem;
import org.jackhuang.hmcl.ui.decorator.DecoratorAnimatedPage;
import org.jackhuang.hmcl.ui.decorator.DecoratorPage;
import org.jackhuang.hmcl.ui.wizard.Refreshable;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

/// One pack from the market, described and installable.
///
/// The detail is **lazy**, and that is the market's whole design rather than an optimisation: the
/// index carries only what a list needs, and a pack's own manifest and README are fetched when
/// somebody opens it. So this page starts with what the index said and fills in the rest as it
/// arrives — and every piece of the rest is allowed to be missing. A pack with no README is a pack
/// with no README, not a page that failed; a manifest that will not parse is worth saying so about,
/// but not worth hiding the download behind.
///
/// Installing has three steps and the page keeps them apart on purpose:
///
/// 1. **fetch** the archive into the launcher's own cache, checking it against the index's `size` and
///    `sha256` as it goes. A pack that fails is discarded, not kept and reported.
/// 2. **read** the container, which is what says whether this launcher can install it at all.
/// 3. **land** its files: a new instance at the version the pack pins, and the pack's `overrides/`
///    written into that instance's profile.
@NotNullByDefault
public final class PackDetailPage extends DecoratorAnimatedPage implements DecoratorPage, Refreshable {
    /// The pack being described.
    private final DshPackMarket.Entry entry;

    /// The page's state for the title bar.
    private final ReadOnlyObjectWrapper<State> state;

    /// What the manifest turned out to be, once it has been read.
    private final Label manifestLine = new Label(i18n("dsh.pack.loading"));

    /// The pack's own README, once it has been read.
    ///
    /// Shown as text rather than as rendered Markdown. The launcher does turn Markdown into HTML
    /// (`StringUtils.convertToHtml`), but this build ships no view that renders HTML — `javafx.scene.web`
    /// is not on the module path — so the choice is between the author's words with their asterisks
    /// and hashes, and the author's words replaced by markup nobody can read. It is the words that
    /// matter, and a Markdown renderer written here would be a third of a library getting somebody
    /// else's document subtly wrong.
    private final Label readme = new Label();

    /// What was read out of the manifest, for the install to use.
    private @Nullable DshPackInstaller.Container container;

    /// The button that installs, kept so its state can follow what has been read.
    private final JFXButton install = new JFXButton(i18n("download.install"));

    /// Creates the page.
    ///
    /// @param entry the pack
    public PackDetailPage(DshPackMarket.Entry entry) {
        this.entry = entry;
        this.state = new ReadOnlyObjectWrapper<>(State.fromTitle(entry.title()));

        BorderPane layout = new BorderPane();
        layout.setPadding(new Insets(10));
        layout.setTop(buildHeader());
        layout.setCenter(buildBody());
        BorderPane.setMargin(layout.getCenter(), new Insets(10, 0, 0, 0));

        setCenter(layout);
        refresh();
    }

    /// Builds the header: what the pack is, and what to do with it.
    ///
    /// @return the header
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
        install.setDisable(true);

        // No back button: the title bar draws one for every page, and a second one inside the card
        // would be the same control twice. The original's detail pages have none either.
        HBox actions = new HBox(8, install);
        actions.setAlignment(Pos.CENTER_RIGHT);
        card.setRight(actions);
        return card;
    }

    /// Builds the body: what the index said, the manifest's verdict, and the README.
    ///
    /// @return the body
    private Node buildBody() {
        VBox box = new VBox(10);

        javafx.scene.layout.GridPane facts = new javafx.scene.layout.GridPane();
        facts.getStyleClass().add("card");
        facts.setPadding(new Insets(10));
        facts.setHgap(15);
        facts.setVgap(8);

        javafx.scene.layout.ColumnConstraints names = new javafx.scene.layout.ColumnConstraints();
        names.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        facts.getColumnConstraints().addAll(names, new javafx.scene.layout.ColumnConstraints());

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
        // The digest is shown in full rather than shortened: it is what the download is checked
        // against, and the one place a person can compare it with what the author published.
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
        // A Label's maximum width is its *preferred* width: LabeledSkinBase caps it there, so a
        // wrapped Label left to itself stops at the width of its longest line. Inside a ScrollPane
        // that fills its width, that is exactly the bug — the README card ends in the middle of the
        // page and the rest is background. Allowing the full width is what lets the ScrollPane's
        // fitToWidth give the Label the viewport, so it wraps there instead.
        readme.setMaxWidth(Double.MAX_VALUE);
        javafx.scene.control.ScrollPane scroll = new javafx.scene.control.ScrollPane(readme);
        scroll.setFitToWidth(true);
        scroll.setPrefHeight(400);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        box.getChildren().add(scroll);
        return box;
    }

    /// Adds one row of facts.
    ///
    /// @param grid  the grid
    /// @param row   which row
    /// @param name  the field's name
    /// @param value the value
    /// @return the next free row
    private static int fact(javafx.scene.layout.GridPane grid, int row, String name, String value) {
        Label label = new Label(value);
        label.setWrapText(true);
        grid.add(new Label(name), 0, row);
        grid.add(label, 1, row);
        return row + 1;
    }

    /// Reads the pack's own documents.
    ///
    /// Two requests, each allowed to fail on its own. They are started together because neither
    /// depends on the other, and a page that waited for the README before showing whether the pack
    /// can be installed would be slower for no reason.
    @Override
    public void refresh() {
        manifestLine.setText(i18n("dsh.pack.loading"));

        CompletableFuture.supplyAsync(() -> {
            String manifest = get(DshPackMarket.manifestUrl(entry));
            String text = get(DshPackMarket.readmeUrl(entry));
            return new String[]{manifest, text};
        }, Schedulers.io()).whenComplete((documents, failure) -> Platform.runLater(() -> {
            if (getScene() == null) {
                return;
            }
            if (failure != null) {
                manifestLine.setText(i18n("dsh.pack.manifest.unreadable"));
                return;
            }
            readManifest(documents[0]);
            showReadme(documents[1]);
        }));
    }

    /// Works out what the pack's manifest says, and whether this launcher can install it.
    ///
    /// @param body the manifest, or `null` when there is none
    private void readManifest(@Nullable String body) {
        if (body == null || body.isBlank()) {
            manifestLine.setText(i18n("dsh.pack.manifest.missing"));
            return;
        }
        try {
            var manifest = org.jackhuang.hmcl.util.gson.JsonUtils.fromJson(
                    body, com.google.gson.JsonObject.class);
            if (manifest == null) {
                manifestLine.setText(i18n("dsh.pack.manifest.malformed"));
                return;
            }
            int bundles = manifest.has("bundles") && manifest.get("bundles").isJsonArray()
                    ? manifest.getAsJsonArray("bundles").size() : 0;
            int dependencies = manifest.has("dependencies") && manifest.get("dependencies").isJsonObject()
                    ? manifest.getAsJsonObject("dependencies").size() : 0;
            manifestLine.setText(i18n("dsh.pack.manifest.read", bundles, dependencies));
            // Nothing is enabled here that the container check would refuse; the download is the
            // step that finds out, and this only says the manifest was read.
            install.setDisable(false);
        } catch (RuntimeException e) {
            manifestLine.setText(i18n("dsh.pack.manifest.malformed"));
        }
    }

    /// Shows the pack's README.
    ///
    /// Left empty when there is none: a pack is not required to have one, and a notice for a
    /// document nobody promised would be worse than the space it takes.
    ///
    /// @param body the README, or `null`
    private void showReadme(@Nullable String body) {
        if (body == null || body.isBlank()) {
            return;
        }
        readme.setText(body);
    }

    /// Reads a document from the market, or answers `null`.
    ///
    /// @param url the address
    /// @return the body, or `null` when it is not there
    private static @Nullable String get(String url) {
        // Through the market's own reader, which checks the status: the launcher's plain reader does
        // not, and a host that answers a missing page with a 200 and an HTML apology would have its
        // error page displayed as the pack's README — which is what happened before this.
        return DshPackMarket.document(url);
    }

    /// Fetches the pack and installs it.
    ///
    /// The three steps in order, and each one is what makes the next safe: the archive is verified
    /// before it is opened, its container is recognised before anything is created, and only then is
    /// an instance made. A failure at any step leaves nothing behind but a file in the cache.
    private void install() {
        Path archive = DshPackInstaller.archiveFile(entry);
        String id = suggestedId();

        ProgressDialog.run(i18n("dsh.pack.installing", entry.title()), report -> {
            report.accept("Downloading " + entry.downloadUrl());
            // The digest and the length are both checked inside, as the bytes arrive and before the
            // file is moved into place, so what comes back is what the market described.
            DshPackInstaller.download(entry, report::accept);
            report.accept("The pack matches the market's size and SHA-256");

            DshPackInstaller.Container container0 = DshPackInstaller.identify(archive);
            report.accept("Container version " + container0.containerVersion()
                    + ", manifest version " + container0.manifestVersion());
            container = container0;

            String profile = container0.profileName(entry.profileName() == null ? "pack" : entry.profileName());
            String version = container0.text("dshVersion");
            if (version == null || version.isBlank()) {
                version = entry.dshVersion();
            }
            if (version == null || version.isBlank()) {
                throw new DshException("The pack does not say which DeepSeek Harness version it "
                        + "needs, and one must be installed before it can be", null);
            }

            DshPackInstaller.installNew(archive, id, profile, version, report::accept);
        }, () -> {
            // Land on the instance list, which is where the thing that was just made is. The
            // original does the same after installing a modpack: the installation is over, and what
            // a person wants next is the instance rather than the page they started from.
            Controllers.navigate(org.jackhuang.hmcl.ui.dsh.MainPage.instance().getInstancesPage());
        });
    }

    /// Suggests an instance id for this pack.
    ///
    /// From the pack's own name, because that is what a person will look for in the list. Made unique
    /// by adding a number rather than by refusing: somebody installing a pack twice wants a second
    /// instance, not an error.
    ///
    /// @return the id
    private String suggestedId() {
        String base = entry.name() == null || entry.name().isBlank() ? entry.id() : entry.name();
        base = base.replaceAll("[^A-Za-z0-9._-]", "-");
        if (base.isEmpty() || !Character.isLetterOrDigit(base.charAt(0))) {
            base = "pack-" + base;
        }
        if (DshInstanceManager.find(base) == null) {
            return base;
        }
        for (int i = 2; i < 1000; i++) {
            if (DshInstanceManager.find(base + "-" + i) == null) {
                return base + "-" + i;
            }
        }
        return base + "-" + System.currentTimeMillis();
    }

    /// Returns the page's state for the title bar.
    ///
    /// @return the state
    @Override
    public ReadOnlyObjectWrapper<State> stateProperty() {
        return state;
    }
}
