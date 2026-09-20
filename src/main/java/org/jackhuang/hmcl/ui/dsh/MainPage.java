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
import com.jfoenix.controls.JFXPopup;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.SimpleObjectProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import org.jackhuang.hmcl.dsh.DshInstance;
import org.jackhuang.hmcl.dsh.DshInstanceManager;
import org.jackhuang.hmcl.dsh.DshProcess;
import org.jackhuang.hmcl.dsh.DshProcessManager;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.construct.AdvancedListBox;
import org.jackhuang.hmcl.ui.construct.ComponentList;
import org.jackhuang.hmcl.ui.construct.LineButton;
import org.jackhuang.hmcl.ui.construct.LineTextPane;
import org.jackhuang.hmcl.ui.construct.TwoLineListItem;
import org.jackhuang.hmcl.ui.decorator.DecoratorAnimatedPage;
import org.jackhuang.hmcl.ui.decorator.DecoratorPage;
import org.jackhuang.hmcl.ui.wizard.Refreshable;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.List;

import static org.jackhuang.hmcl.setting.SettingsManager.settings;
import static org.jackhuang.hmcl.util.i18n.I18n.i18n;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// The launcher home: what is running now, and the launch button.
///
/// The layout deliberately mirrors HMCL's home page: a content area with cards
/// and a large launch control anchored to the bottom-right corner, with a
/// dropdown next to it for switching instance. What differs is what the button
/// does — DeepSeek Harness runs as a background server, so a launch starts a
/// child process and opens its browser interface rather than spawning a game.
@NotNullByDefault
public final class MainPage extends DecoratorAnimatedPage implements DecoratorPage, Refreshable {
    /// The page state published to the window decorator.
    private final ReadOnlyObjectWrapper<State> state =
            new ReadOnlyObjectWrapper<>(State.fromTitle(i18n("dsh.home")));

    /// The instance the launch button targets.
    private final ObjectProperty<@Nullable DshInstance> currentInstance = new SimpleObjectProperty<>();

    /// The card listing what is running right now.
    private final ComponentList runningList = new ComponentList();

    /// The card shown when nothing is running.
    private final ComponentList placeholderList = new ComponentList();

    /// The label above the cards.
    private final Label status = new Label();

    /// The launch button's first line.
    private final Label launchLabel = new Label();

    /// The launch button's second line, showing the target instance.
    private final Label launchTarget = new Label();

    /// Refreshes uptimes while anything is running.
    private final Timeline uptimeTicker;

    /// Creates the home page.
    public MainPage() {
        getStyleClass().remove("gray-background");

        setLeft(new AdvancedListBox()
                .startCategory(i18n("dsh.home").toUpperCase(java.util.Locale.ROOT))
                .addNavigationDrawerItem(i18n("instance.manage"), SVG.FORMAT_LIST_BULLETED,
                        () -> Controllers.navigate(new InstancesPage()))
                .addNavigationDrawerItem(i18n("dsh.versions.title"), SVG.DOWNLOAD,
                        () -> Controllers.navigate(new VersionsPage()))
                .addNavigationDrawerItem(i18n("settings"), SVG.SETTINGS,
                        () -> Controllers.navigate(new SettingsPage())));

        VBox content = new VBox(10);
        content.setPadding(new Insets(10));
        content.getChildren().addAll(status, runningList, placeholderList);

        // The launch pane lives inside the centre node: a Control's children are
        // owned by its skin, so adding to getChildren() directly would be wiped
        // the moment the skin is created.
        StackPane centre = new StackPane(content, buildLaunchPane());
        StackPane.setAlignment(content, Pos.TOP_LEFT);
        setCenter(centre);

        uptimeTicker = new Timeline(new KeyFrame(Duration.seconds(2), event -> refreshRunning()));
        uptimeTicker.setCycleCount(Animation.INDEFINITE);
        uptimeTicker.play();

        refresh();
    }

    /// Builds the bottom-right launch control.
    ///
    /// @return the launch pane
    private Node buildLaunchPane() {
        JFXButton launchButton = new JFXButton();
        launchButton.getStyleClass().add("launch-button");
        launchButton.setDefaultButton(true);

        launchLabel.setStyle("-fx-font-size: 16px;");
        launchTarget.setStyle("-fx-font-size: 12px;");
        VBox graphic = new VBox(launchLabel, launchTarget);
        graphic.setAlignment(Pos.CENTER);
        launchButton.setGraphic(graphic);
        launchButton.setOnAction(event -> onLaunch());

        JFXButton menuButton = new JFXButton();
        menuButton.getStyleClass().add("menu-button");
        menuButton.setGraphic(SVG.ARROW_DROP_UP.createIcon(30));
        FXUtils.installFastTooltip(menuButton, i18n("dsh.launch.select"));
        menuButton.setOnAction(event -> showInstanceMenu(menuButton));

        HBox pane = new HBox(launchButton, menuButton);
        pane.getStyleClass().add("launch-pane");
        pane.setAlignment(Pos.BOTTOM_RIGHT);
        pane.setMaxWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        pane.setMaxHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        pane.setPickOnBounds(false);
        StackPane.setAlignment(pane, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(pane, new Insets(0, 20, 20, 0));
        return pane;
    }

    @Override
    public ReadOnlyObjectProperty<State> stateProperty() {
        return state.getReadOnlyProperty();
    }

    @Override
    public void refresh() {
        List<DshInstance> instances = DshInstanceManager.list();

        String selectedId = settings().selectedInstanceIdProperty().get();
        DshInstance current = null;
        for (DshInstance instance : instances) {
            if (instance.id().equals(selectedId)) {
                current = instance;
                break;
            }
        }
        if (current == null && !instances.isEmpty()) {
            current = instances.get(0);
        }
        currentInstance.set(current);
        if (current != null && !current.id().equals(selectedId)) {
            settings().selectedInstanceIdProperty().set(current.id());
        }

        if (current == null) {
            launchLabel.setText(i18n("dsh.launch.no_instance"));
            launchTarget.setText(i18n("dsh.launch.no_instance.hint"));
        } else {
            boolean launching = DshLaunchService.isLaunching(current.id());
            boolean running = DshProcessManager.find(current.id()).isPresent();
            launchLabel.setText(launching
                    ? i18n("dsh.launch.launching")
                    : running ? i18n("dsh.launch.running") : i18n("dsh.launch"));
            launchTarget.setText(current.id());
        }

        refreshRunning();
    }

    /// Rebuilds the running-instances card.
    private void refreshRunning() {
        List<DshProcess> running = DshProcessManager.running();

        runningList.getContent().clear();
        placeholderList.getContent().clear();

        if (running.isEmpty()) {
            status.setText(i18n("dsh.running.none"));
            placeholderList.getContent().add(buildSectionHeader(i18n("dsh.home")));
            Label hint = new Label(i18n("dsh.home.hint"));
            hint.setWrapText(true);
            VBox hintBox = new VBox(hint);
            hintBox.setPadding(new Insets(10));
            placeholderList.getContent().add(hintBox);
            return;
        }

        status.setText(i18n("dsh.running.count", running.size()));
        runningList.getContent().add(buildSectionHeader(i18n("dsh.running.title")));
        for (DshProcess process : running) {
            runningList.getContent().add(buildRunningRow(process));
        }
    }

    /// Builds one row for a running instance.
    ///
    /// @param process the running process
    /// @return the row
    private LineButton buildRunningRow(DshProcess process) {
        JFXButton stop = FXUtils.newToggleButton4(SVG.CANCEL, 18);
        stop.setOnAction(event -> DshLaunchService.stop(process.instance().id(), this::refresh));

        LineButton row = new LineButton();
        row.setTitle(process.instance().id());
        String url = process.webUrl().map(Object::toString).orElse(null);
        row.setSubtitle(url != null
                ? i18n("dsh.running.summary.url", process.plan().surface().profileName(),
                        process.uptime().toSeconds(), url)
                : i18n("dsh.running.summary", process.plan().surface().profileName(),
                        process.uptime().toSeconds()));
        row.setTitleTrailing(stop);
        row.setOnAction(event -> process.webUrl().ifPresent(uri -> FXUtils.openLink(uri.toString())));
        return row;
    }

    /// Handles the launch button.
    private void onLaunch() {
        DshInstance instance = currentInstance.get();
        if (instance == null) {
            Controllers.navigate(new InstancesPage());
            return;
        }
        if (DshProcessManager.find(instance.id()).isPresent()) {
            DshProcess running = DshProcessManager.find(instance.id()).orElseThrow();
            running.webUrl().ifPresentOrElse(
                    uri -> FXUtils.openLink(uri.toString()),
                    () -> Controllers.showToast(i18n("dsh.launch.already_running", instance.id())));
            return;
        }
        DshLaunchService.launch(instance, ignored -> refresh());
    }

    /// Shows the instance picker next to the launch button.
    ///
    /// @param anchor the button the popup is anchored to
    private void showInstanceMenu(Node anchor) {
        List<DshInstance> instances = DshInstanceManager.list();
        if (instances.isEmpty()) {
            Controllers.navigate(new InstancesPage());
            return;
        }

        VBox box = new VBox();
        box.setPadding(new Insets(8));
        box.setSpacing(4);
        for (DshInstance instance : instances) {
            TwoLineListItem item = new TwoLineListItem();
            item.setTitle(instance.id());
            item.setSubtitle(i18n("dsh.instance.summary",
                    instance.version(), instance.profile(),
                    i18n("dsh.instance.home." + instance.homeMode().name().toLowerCase(java.util.Locale.ROOT))));
            item.setMouseTransparent(false);
            JFXButton button = new JFXButton();
            button.setGraphic(item);
            button.getStyleClass().add("menu-item");
            button.setOnAction(event -> {
                settings().selectedInstanceIdProperty().set(instance.id());
                refresh();
                JFXPopup popup = (JFXPopup) button.getProperties().get("hmcl-dsh-popup");
                if (popup != null) {
                    popup.hide();
                }
            });
            box.getChildren().add(button);
        }

        JFXPopup popup = new JFXPopup(box);
        for (Node child : box.getChildren()) {
            child.getProperties().put("hmcl-dsh-popup", popup);
        }
        popup.show(anchor, JFXPopup.PopupVPosition.BOTTOM, JFXPopup.PopupHPosition.RIGHT, 0, -anchor.getBoundsInLocal().getHeight());
    }

    /// Builds a bold section heading rendered as the first row of a card.
    ///
    /// @param text the heading text
    /// @return the heading row
    private Node buildSectionHeader(String text) {
        LineTextPane header = new LineTextPane();
        header.setTitle(text);
        header.getStyleClass().add("section-header");
        return header;
    }
}
