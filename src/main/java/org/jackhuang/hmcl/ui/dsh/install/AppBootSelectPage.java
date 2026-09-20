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
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.construct.ComponentList;
import org.jackhuang.hmcl.ui.construct.ImageContainer;
import org.jackhuang.hmcl.ui.construct.LineTextPane;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane.MessageType;
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

    /// The name box above the list.
    private final JFXTextField nameField = new JFXTextField();

    /// Holds the version rows.
    private final ComponentList list = new ComponentList();

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

        nameField.setPromptText(i18n("download.name.prompt"));
        nameField.textProperty().addListener((observable, was, value) -> render());

        JFXButton refresh = FXUtils.newRaisedButton(i18n("button.refresh"));
        refresh.setOnAction(event -> load());

        HBox toolbar = new HBox(16, new Label(i18n("download.name")), nameField, refresh);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.getStyleClass().add("card");
        HBox.setHgrow(nameField, Priority.ALWAYS);
        VBox.setMargin(toolbar, new Insets(10, 10, 0, 10));

        ScrollPane scroll = new ScrollPane(list);
        scroll.setFitToWidth(true);
        FXUtils.smoothScrolling(scroll);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        getChildren().setAll(toolbar, scroll);

        load();
    }

    @Override
    public String getTitle() {
        return i18n("dsh.install.app_boot.choose");
    }

    /// Loads the published boot library versions.
    private void load() {
        list.getContent().clear();
        LineTextPane loading = new LineTextPane();
        loading.setText(i18n("dsh.versions.loading"));
        list.getContent().add(loading);

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
                list.getContent().clear();
                LineTextPane failure = new LineTextPane();
                failure.setText(i18n("dsh.install.app_boot.unavailable") + ": " + cause.getMessage());
                list.getContent().add(failure);
                return;
            }
            versions = loaded;
            render();
        }));
    }

    /// Rebuilds the list from the loaded versions and the name box.
    private void render() {
        list.getContent().clear();

        String needle = nameField.getText() == null
                ? "" : nameField.getText().trim().toLowerCase(Locale.ROOT);

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
            if (!needle.isEmpty() && !version.toLowerCase(Locale.ROOT).contains(needle)) {
                continue;
            }
            list.getContent().add(buildRow(version));
            shown++;
        }
        if (shown == 0) {
            LineTextPane empty = new LineTextPane();
            empty.setText(i18n("dsh.versions.remote.empty"));
            list.getContent().add(empty);
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

    /// Records a choice, warning first when it departs from the pairing.
    ///
    /// @param version the chosen version
    /// @param paired  whether it is the version the launcher itself runs
    private void choose(String version, boolean paired) {
        if (paired) {
            onChosen.accept(null);
            controller.onFinish();
            return;
        }

        // The warning says what the choice costs: the pairing is not a
        // preference, and it belongs to the installed version rather than to this
        // instance, so every instance running that version is affected.
        Controllers.dialog(
                i18n("dsh.install.app_boot.warning", version, String.valueOf(launcherVersion)),
                i18n("dsh.install.app_boot"), MessageType.WARNING, () -> {
                    onChosen.accept(version);
                    controller.onFinish();
                });
    }
}
