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
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.dsh.DshException;
import org.jackhuang.hmcl.dsh.DshInstance;
import org.jackhuang.hmcl.dsh.DshInstanceManager;
import org.jackhuang.hmcl.dsh.DshPackInstaller;
import org.jackhuang.hmcl.dsh.DshPackMarket;
import org.jackhuang.hmcl.dsh.DshVersionManager;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.construct.TwoLineListItem;
import org.jackhuang.hmcl.ui.decorator.DecoratorAnimatedPage;
import org.jackhuang.hmcl.ui.decorator.DecoratorPage;
import org.jackhuang.hmcl.ui.wizard.Refreshable;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

/// Installs a pack somebody already has, or knows the address of.
///
/// The original's own chooser, card for card (`ModpackSelectionPage`): a heading, then one wide
/// pressable card per way of getting a pack — a file on this machine, an address on the internet, or
/// the catalogue — each with what it needs on one line and an arrow saying it leads somewhere. The
/// whole page accepts a dropped file as well, because the commonest way to have a pack in front of
/// you is to have just downloaded it, and making somebody find it in a file chooser after that is a
/// step that buys nothing.
///
/// The three differ in where the archive comes from and in nothing else: once there is a file, the
/// same three steps run — check the container, make an instance at the version it pins, land its
/// files. So this page decides **where from** and hands the rest to the same code the market's
/// detail page uses.
@NotNullByDefault
public final class PackInstallPage extends DecoratorAnimatedPage implements DecoratorPage, Refreshable {
    /// The page's state for the title bar.
    private final ReadOnlyObjectWrapper<State> state =
            new ReadOnlyObjectWrapper<>(State.fromTitle(i18n("install.modpack")));

    /// Creates the page.
    public PackInstallPage() {
        VBox content = new VBox(8);
        content.setMaxWidth(400);
        // Its height as well as its width: a box left free to grow is stretched to the whole page by
        // the stack pane, and its children then sit at the top of a box that reaches the bottom —
        // which is why centring the box changed nothing at all.
        content.setMaxHeight(Region.USE_PREF_SIZE);
        content.setSpacing(8);
        content.setPadding(new Insets(20));

        Label title = new Label(i18n("install.modpack"));
        title.setPadding(new Insets(8));

        content.getChildren().addAll(
                title,
                card("modpack.choose.local", this::chooseLocalFile),
                card("modpack.choose.remote", this::chooseRemoteFile),
                card("modpack.choose.repository", this::openCatalogue));

        // Whole-page drop, which is the original's own gesture: the card says the file can be
        // dropped here, so it has to be true of the page and not only of the card.
        FXUtils.applyDragListener(content,
                path -> isPack(path),
                files -> {
                    if (!files.isEmpty()) {
                        install(files.get(0));
                    }
                });

        // Centred rather than pinned to the top, which is the original's own arrangement: three cards
        // in the middle of an empty page read as a question being asked, and the same three against
        // the top edge read as a page that failed to load the rest of itself.
        StackPane pane = new StackPane(content);
        pane.setAlignment(Pos.CENTER);
        pane.setPadding(new Insets(10));
        setCenter(pane);
    }

    /// Builds one way of getting a pack.
    ///
    /// The original's own card, node for node: a button wearing the card surface, a two-line list as
    /// its graphic, and an arrow on the right. The arrow is what says the card leads somewhere rather
    /// than doing something, and the whole card is pressable because it is a button and not a panel
    /// with a link in it.
    ///
    /// @param key    the pair of strings' shared prefix
    /// @param action what pressing it does
    /// @return the card
    private Node card(String key, Runnable action) {
        JFXButton button = new JFXButton();
        button.getStyleClass().add("card");
        button.setStyle("-fx-cursor: HAND;");
        button.setMaxWidth(Double.MAX_VALUE);
        button.setOnAction(event -> action.run());

        BorderPane graphic = new BorderPane();
        graphic.setMouseTransparent(true);
        graphic.setLeft(new TwoLineListItem(i18n(key), i18n(key + ".detail")));

        javafx.scene.Node arrow = SVG.ARROW_FORWARD.createIcon();
        BorderPane.setAlignment(arrow, Pos.CENTER);
        graphic.setRight(arrow);

        button.setGraphic(graphic);
        javafx.scene.layout.GridPane.setHgrow(button, Priority.ALWAYS);
        com.jfoenix.effects.JFXDepthManager.setDepth(button, 1);
        return button;
    }

    /// Asks for a file on this machine.
    private void chooseLocalFile() {
        javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
        chooser.setTitle(i18n("modpack.choose"));
        chooser.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter(
                i18n("modpack"), "*.dspack", "*.hdslp", "*.zip"));
        Path chosen = Controllers.showOpenDialog(chooser);
        if (chosen != null) {
            install(chosen);
        }
    }

    /// Asks for an address.
    ///
    /// The original prompts for a link the same way. What is downloaded has no index entry behind it,
    /// so the two pointers the market provides are not available — the container is checked instead,
    /// which is the part that says whether this launcher can install it at all.
    private void chooseRemoteFile() {
        Controllers.prompt(i18n("modpack.choose.remote.tooltip"), (url, handler) -> {
            if (url == null || url.isBlank()) {
                handler.reject(i18n("dsh.pack.url.empty"));
                return;
            }
            Path target = DshPackInstaller.cacheDirectory()
                    .resolve("downloaded-" + System.currentTimeMillis() + ".dspack");
            try {
                Files.createDirectories(target.getParent());
                java.net.http.HttpRequest request = java.net.http.HttpRequest
                        .newBuilder(java.net.URI.create(url.trim()))
                        .timeout(java.time.Duration.ofMinutes(10))
                        .GET()
                        .build();
                try (java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                        .connectTimeout(java.time.Duration.ofSeconds(20))
                        .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
                        .build()) {
                    java.net.http.HttpResponse<Path> response = client.send(request,
                            java.net.http.HttpResponse.BodyHandlers.ofFile(target));
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        handler.reject(i18n("dsh.pack.url.failed", response.statusCode()));
                        return;
                    }
                }
                handler.resolve();
                install(target);
            } catch (java.io.IOException | InterruptedException | RuntimeException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                handler.reject(String.valueOf(e.getMessage()));
            }
        });
    }

    /// Opens the catalogue, which is where the launcher's own market lives.
    private void openCatalogue() {
        Controllers.navigate(new PackMarketPage());
    }

    /// Reports whether a dropped file looks like a pack.
    ///
    /// @param file the file
    /// @return whether it is worth opening
    private static boolean isPack(Path file) {
        String name = file.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        return name.endsWith(".dspack") || name.endsWith(".hdslp") || name.endsWith(".zip");
    }

    /// Installs a pack from a file that is already here.
    ///
    /// The three steps, and the order is what makes each safe: the container is recognised **before**
    /// an instance is made, so a file that is not a pack leaves nothing behind.
    ///
    /// @param archive where the pack is
    private void install(Path archive) {
        ProgressDialog.run(i18n("dsh.pack.installing", archive.getFileName().toString()), report -> {
            DshPackInstaller.Container container = DshPackInstaller.identify(archive);
            report.accept("Container version " + container.containerVersion()
                    + ", manifest version " + container.manifestVersion());

            String version = container.text("dshVersion");
            if (version == null || version.isBlank()) {
                throw new DshException("这个整合包没有写明需要哪个 DeepSeek Harness 版本，"
                        + "而必须先装好一个才能安装它");
            }
            String id = uniqueId(container.text("name"));
            String profile = container.profileName("pack");

            report.accept("Creating instance " + id + " at " + version);
            // Isolated, because that is what installing a pack means: it brings its own plugins, and
            // putting them into a home something else is already using is how one pack's dependencies
            // end up in another pack's profile.
            DshInstance instance = DshInstanceManager.create(id, version, profile,
                    Path.of(System.getProperty("user.home")),
                    org.jackhuang.hmcl.dsh.DshHomeMode.ISOLATED, null, List.of(), Map.of());

            report.accept("Installing DeepSeek Harness " + version);
            DshVersionManager.install(instance, version, report::accept);

            report.accept("Writing the pack's files into "
                    + instance.homeDirectory().resolve("profiles").resolve(instance.profile()));
            DshPackInstaller.Landed landed = DshPackInstaller.installInto(archive, instance,
                    report::accept);
            report.accept("Wrote " + landed.files() + " files (" + landed.overrides()
                    + " overrides, " + landed.machine() + " machine files)");
        }, () -> Controllers.navigate(MainPage.instance().getInstancesPage()));
    }

    /// Returns an instance id that is not taken.
    ///
    /// From the pack's own name, because that is what somebody will look for in the list, and made
    /// unique by adding a number rather than by refusing — installing a pack twice wants a second
    /// instance, not an error.
    ///
    /// @param name what the pack calls itself, or `null`
    /// @return the id
    private static String uniqueId(@Nullable String name) {
        String base = name == null || name.isBlank() ? "pack" : name;
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
