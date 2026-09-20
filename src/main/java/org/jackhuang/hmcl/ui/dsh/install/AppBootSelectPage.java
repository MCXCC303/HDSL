package org.jackhuang.hmcl.ui.dsh.install;

import com.jfoenix.controls.JFXButton;
import com.jfoenix.controls.JFXTextField;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.dsh.DshException;
import org.jackhuang.hmcl.dsh.DshInstanceIcon;
import org.jackhuang.hmcl.dsh.DshVersionManager;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.dsh.VersionFilterBar;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.construct.ComponentList;
import org.jackhuang.hmcl.ui.construct.ImageContainer;
import org.jackhuang.hmcl.ui.construct.LineTextPane;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane.MessageType;
import org.jackhuang.hmcl.ui.construct.SpinnerPane;
import org.jackhuang.hmcl.ui.construct.RipplerContainer;
import org.jackhuang.hmcl.ui.construct.TwoLineListItem;
import org.jackhuang.hmcl.ui.wizard.WizardController;
import org.jackhuang.hmcl.ui.wizard.WizardPage;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Chooses which boot library version an instance will be created with.
///
/// A page rather than a dropdown, which is the original's arrangement: it opens
/// the same list a version is chosen from anywhere else, with a name box, a
/// refresh, and a row per version. A dropdown has no room for a list that grows
/// and no way out of it — this has the window's own back and close.
@NotNullByDefault
public final class AppBootSelectPage extends VBox implements WizardPage {
    /// The wizard this page belongs to.
    private final WizardController controller;

    /// Receives the chosen version, or `null` for the launcher's own.
    private final Consumer<@Nullable String> onChosen;

    /// The version the launcher itself runs, which needs no warning.
    private final @Nullable String launcherVersion;

    /// The name box, type filter and refresh this page shares with the other
    /// version lists.
    private final VersionFilterBar filterBar = new VersionFilterBar(this::render, this::load);

    /// Spins while the versions are being fetched.
    private final SpinnerPane spinner = new SpinnerPane();

    /// Holds the version rows.
    ///
    /// A plain box rather than a [ComponentList]: these rows carry their own
    /// surface, and a component list wraps each child in one more item with
    /// padding of its own, which stacks on the row's margin and makes every row
    /// taller than the original's.
    private final VBox list = new VBox();

    /// Every version the last load returned, before filtering.
    private List<String> versions = List.of();

    /// Creates the page.
    ///
    /// @param controller      the wizard controller
    /// @param launcherVersion the version the launcher runs, or `null`
    /// @param onChosen        receives the chosen version
    public AppBootSelectPage(WizardController controller, @Nullable String launcherVersion,
                             Consumer<@Nullable String> onChosen) {
        this.controller = controller;
        this.launcherVersion = launcherVersion;
        this.onChosen = onChosen;

        setSpacing(10);
        setAlignment(Pos.TOP_LEFT);

        VBox.setMargin(filterBar, new Insets(10, 10, 0, 10));

        // The card surface the rows sit on, as the original's list has.
        StackPane listSurface = new StackPane(list);
        listSurface.getStyleClass().add("card");
        VBox.setMargin(listSurface, new Insets(10));
        listSurface.setAlignment(Pos.TOP_LEFT);

        ScrollPane scroll = new ScrollPane(listSurface);
        scroll.setFitToWidth(true);
        FXUtils.smoothScrolling(scroll);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        // A spinner while the versions are being fetched, as the original shows,
        // rather than a line of text where the list will be.
        spinner.setContent(scroll);
        VBox.setVgrow(spinner, Priority.ALWAYS);

        getChildren().setAll(filterBar, spinner);

        // The warning belongs at the door rather than at the choice: it says
        // what this page is for and what changing it costs, which is worth
        // reading before deciding, not after.
        Controllers.dialog(
                i18n("dsh.install.app_boot.warning", String.valueOf(launcherVersion)),
                i18n("dsh.install.app_boot"), MessageType.WARNING, null);

        load();
    }

    @Override
    public String getTitle() {
        return i18n("dsh.install.app_boot.choose");
    }

    /// Loads the published boot library versions.
    private void load() {
        list.getChildren().clear();
        spinner.setLoading(true);

        java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try {
                return DshVersionManager.fetchPackageVersions(DshVersionManager.APP_BOOT_PACKAGE);
            } catch (DshException e) {
                throw new java.util.concurrent.CompletionException(e);
            }
        }).whenComplete((loaded, throwable) -> FXUtils.runInFX(() -> {
            if (throwable != null) {
                Throwable cause = throwable instanceof java.util.concurrent.CompletionException
                        && throwable.getCause() != null ? throwable.getCause() : throwable;
                LOG.warning("Failed to list boot library versions", cause);
                list.getChildren().clear();
                LineTextPane failure = new LineTextPane();
                failure.setText(i18n("dsh.install.app_boot.unavailable") + ": " + cause.getMessage());
                list.getChildren().add(failure);
                spinner.setLoading(false);
                return;
            }
            versions = loaded;
            render();
            spinner.setLoading(false);
        }));
    }

    /// Rebuilds the list from the loaded versions and the name box.
    private void render() {
        list.getChildren().clear();

        // The launcher's own version leads: it is the safe answer, so it is the
        // one at the top rather than something to go looking for.
        List<String> ordered = new ArrayList<>();
        if (launcherVersion != null) {
            ordered.add(launcherVersion);
        }
        for (String version : versions) {
            if (!ordered.contains(version)) {
                ordered.add(version);
            }
        }

        int shown = 0;
        for (String version : ordered) {
            if (!filterBar.accepts(version)) {
                continue;
            }
            list.getChildren().add(buildRow(version));
            shown++;
        }
        if (shown == 0) {
            LineTextPane empty = new LineTextPane();
            empty.setText(i18n("dsh.versions.remote.empty"));
            list.getChildren().add(empty);
        }
    }

    /// Builds one row.
    ///
    /// The row shape the download page uses: an icon, the version as the title,
    /// a tag saying what the version is, and an arrow. A version that is not the
    /// launcher's own is a departure from the pairing, and the tag says so before
    /// the click rather than after it.
    ///
    /// @param version the version this row offers
    /// @return the row
    private Node buildRow(String version) {
        boolean paired = version.equals(launcherVersion);

        ImageContainer icon = new ImageContainer(32);
        icon.setImage(DshInstanceIcon.DSH_APPLICATION.load());

        TwoLineListItem content = new TwoLineListItem();
        content.setTitle(version);
        content.getTags().clear();
        content.addTag(i18n(paired ? "dsh.install.app_boot.paired" : "dsh.install.app_boot.departure"));
        content.setSubtitle(i18n(paired ? "dsh.install.app_boot.hint" : "dsh.install.app_boot.departure.hint"));
        content.setAlignment(Pos.CENTER);

        JFXButton arrow = new JFXButton();
        arrow.setGraphic(SVG.ARROW_FORWARD.createIcon());
        arrow.getStyleClass().add("toggle-icon4");

        HBox row = new HBox(16, icon, content, arrow);
        row.setAlignment(Pos.CENTER);
        HBox.setHgrow(content, Priority.ALWAYS);
        StackPane.setMargin(row, new Insets(10, 16, 10, 16));

        StackPane cell = new StackPane(row);
        cell.getStyleClass().add("md-list-cell");
        StackPane.setAlignment(row, Pos.CENTER_LEFT);

        RipplerContainer rippler = new RipplerContainer(cell);
        rippler.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 1) {
                choose(version, paired);
                event.consume();
            }
        });
        cell.setCursor(javafx.scene.Cursor.HAND);
        return rippler;
    }

    /// Records a choice and returns to the page that asked.
    ///
    /// The original applies the choice and goes back in one step; there is
    /// nothing left to confirm, because the warning was shown on the way in.
    ///
    /// @param version the chosen version
    /// @param paired  whether it is the version the launcher itself runs
    private void choose(String version, boolean paired) {
        onChosen.accept(paired ? null : version);
        // Closing the page is how a one-page wizard finishes: its work is done
        // when the choice is made, so it has no task to run and nothing to
        // announce. onCancel is the only door out of the wizard that does not
        // put a dialog on the screen on the way.
        controller.onCancel();
    }
}
