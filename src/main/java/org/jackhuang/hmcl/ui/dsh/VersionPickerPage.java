package org.jackhuang.hmcl.ui.dsh;

import com.jfoenix.controls.JFXButton;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
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
import org.jackhuang.hmcl.dsh.DshInstance;
import org.jackhuang.hmcl.dsh.DshInstanceIcon;
import org.jackhuang.hmcl.dsh.DshRelease;
import org.jackhuang.hmcl.dsh.DshVersionManager;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.construct.ImageContainer;
import org.jackhuang.hmcl.ui.construct.LineTextPane;
import org.jackhuang.hmcl.ui.construct.PageCloseEvent;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane.MessageType;
import org.jackhuang.hmcl.ui.construct.RipplerContainer;
import org.jackhuang.hmcl.ui.construct.SpinnerPane;
import org.jackhuang.hmcl.ui.construct.TwoLineListItem;
import org.jackhuang.hmcl.ui.decorator.DecoratorPage;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.List;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Chooses another DeepSeek Harness version for an instance.
///
/// A page rather than a dropdown, which is how this launcher chooses a version
/// everywhere: the same name box, the same type filter, and a row per version.
@NotNullByDefault
public final class VersionPickerPage extends VBox implements DecoratorPage {
    /// The page's state.
    private final ReadOnlyObjectWrapper<State> state =
            new ReadOnlyObjectWrapper<>(State.fromTitle(i18n("dsh.instance.upgrade.choose")));

    /// The instance being moved.
    private final DshInstance instance;

    /// The name box and type filter, shared with the other version lists.
    private final VersionFilterBar filterBar = new VersionFilterBar(this::render, this::load);

    /// Spins while the versions are being fetched.
    private final SpinnerPane spinner = new SpinnerPane();

    /// The rows, rebuilt when the filter changes.
    private final VBox list = new VBox();

    /// Every published version the last load returned.
    private List<DshRelease> releases = List.of();

    /// The published versions of the package the harness is published with.
    ///
    /// Read once with the release list: a harness version whose twin is missing
    /// cannot be installed, and saying so beforehand is better than a failure after
    /// a download.
    private java.util.Set<String> lockstep = java.util.Set.of();

    /// The oldest version of the lockstep package, or `null` when it is unknown.
    private String oldestLockstep;

    /// Creates the page.
    ///
    /// @param instance the instance to choose a version for
    public VersionPickerPage(DshInstance instance) {
        this.instance = instance;

        setSpacing(10);
        setAlignment(Pos.TOP_LEFT);

        VBox.setMargin(filterBar, new Insets(10, 10, 0, 10));

        StackPane surface = new StackPane(list);
        surface.getStyleClass().add("card");
        surface.setAlignment(Pos.TOP_LEFT);
        VBox.setMargin(surface, new Insets(10));

        ScrollPane scroll = new ScrollPane(surface);
        scroll.setFitToWidth(true);
        FXUtils.smoothScrolling(scroll);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        spinner.setContent(scroll);
        VBox.setVgrow(spinner, Priority.ALWAYS);

        getChildren().setAll(filterBar, spinner);

        load();
    }

    @Override
    public ReadOnlyObjectProperty<State> stateProperty() {
        return state.getReadOnlyProperty();
    }

    /// Loads the published versions.
    private void load() {
        list.getChildren().clear();
        spinner.setLoading(true);

        java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try {
                DshVersionManager.Lockstep twins = DshVersionManager.lockstepVersionsAndOldest();
                lockstep = twins.versions();
                oldestLockstep = twins.oldest();
                return DshVersionManager.fetchReleases();
            } catch (DshException e) {
                throw new java.util.concurrent.CompletionException(e);
            }
        }).whenComplete((loaded, throwable) -> FXUtils.runInFX(() -> {
            spinner.setLoading(false);
            if (throwable != null) {
                Throwable cause = throwable instanceof java.util.concurrent.CompletionException
                        && throwable.getCause() != null ? throwable.getCause() : throwable;
                LOG.warning("Failed to list published versions", cause);
                LineTextPane failure = new LineTextPane();
                failure.setText(i18n("dsh.versions.load_failed") + ": " + cause.getMessage());
                list.getChildren().add(failure);
                return;
            }
            releases = loaded;
            render();
        }));
    }

    /// Rebuilds the rows from the loaded versions and the filters.
    private void render() {
        list.getChildren().clear();

        int shown = 0;
        for (DshRelease release : releases) {
            if (!filterBar.accepts(release.version())) {
                continue;
            }
            list.getChildren().add(buildRow(release));
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
    /// @param release the release this row offers
    /// @return the row
    private Node buildRow(DshRelease release) {
        boolean current = release.version().equals(instance.version());

        ImageContainer icon = new ImageContainer(32);
        icon.setImage(DshInstanceIcon.DSH_APPLICATION.load());

        TwoLineListItem content = new TwoLineListItem();
        content.setTitle(release.version());
        content.getTags().clear();
        content.addTag(i18n(current ? "dsh.instance.upgrade.current" : "download.type." + release.type().id()));

        // A version whose published twin is missing cannot be installed: the
        // registry has nothing to satisfy the dependency it names. Saying so here
        // is the difference between a list of versions and a list of versions that
        // can be used.
        boolean installable = lockstep.isEmpty() || lockstep.contains(release.version())
                || DshVersionManager.predatesLockstep(release.version(), oldestLockstep);
        if (!current && !installable) {
            content.addTag(i18n("dsh.instance.upgrade.missing"));
        }
        content.setSubtitle(current
                ? i18n("dsh.instance.upgrade.current.hint")
                : i18n("dsh.instance.upgrade.hint"));
        content.setAlignment(Pos.CENTER);

        HBox row = new HBox(16, icon, content);
        row.setAlignment(Pos.CENTER);
        HBox.setHgrow(content, Priority.ALWAYS);
        StackPane.setMargin(row, new Insets(10, 16, 10, 16));

        StackPane cell = new StackPane(row);
        cell.getStyleClass().add("md-list-cell");
        StackPane.setAlignment(row, Pos.CENTER_LEFT);

        if (!current) {
            // Part of the row rather than a control of its own: it says where the
            // row leads, and a press on it is a press on the row.
            JFXButton choose = new JFXButton();
            choose.setGraphic(SVG.ARROW_FORWARD.createIcon());
            choose.getStyleClass().add("toggle-icon4");
            choose.setMouseTransparent(true);
            row.getChildren().add(choose);
            cell.setCursor(javafx.scene.Cursor.HAND);
        }

        RipplerContainer rippler = new RipplerContainer(cell);
        if (!current) {
            rippler.setOnMouseClicked(event -> {
                if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 1) {
                    confirm(release.version());
                    event.consume();
                }
            });
        }
        return rippler;
    }

    /// Asks before moving the instance, because the plugins are installed again.
    ///
    /// @param version the version to move to
    private void confirm(String version) {
        Controllers.confirm(
                i18n("dsh.instance.upgrade.confirm", instance.id(), instance.version(), version),
                i18n("dsh.instance.upgrade"),
                () -> DshInstanceUpgradeService.upgrade(instance, version,
                        () -> fireEvent(new PageCloseEvent())),
                null);
    }
}
