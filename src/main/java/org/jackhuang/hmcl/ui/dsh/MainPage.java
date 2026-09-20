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
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
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
import org.jackhuang.hmcl.ui.decorator.DecoratorAnimatedPage;
import org.jackhuang.hmcl.ui.decorator.DecoratorPage;
import org.jackhuang.hmcl.ui.wizard.Refreshable;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;

import static org.jackhuang.hmcl.setting.SettingsManager.settings;
import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

/// The launcher home.
///
/// The page is deliberately spare: a sidebar and one large launch control
/// anchored to the bottom-right corner. Instance management lives behind that
/// control rather than on the page, because the launcher's primary action is
/// starting and stopping a single chosen instance.
///
/// Because DeepSeek Harness has no single-instance lock of its own, the button
/// reflects the selected instance's state: it launches a stopped instance and
/// stops a running one, so a second server can never be started by accident.
@NotNullByDefault
public final class MainPage extends DecoratorAnimatedPage implements DecoratorPage, Refreshable {
    /// The page state published to the window decorator.
    private final ReadOnlyObjectWrapper<State> state =
            new ReadOnlyObjectWrapper<>(State.fromTitle(i18n("dsh.home")));

    /// The instance the launch control targets.
    private final ObjectProperty<@Nullable DshInstance> currentInstance = new SimpleObjectProperty<>();

    /// The launch/stop button's first line.
    private final Label actionLabel = new Label();

    /// The launch/stop button's second line, showing the target instance.
    private final Label actionTarget = new Label();

    /// The button itself, so its state can be refreshed.
    private final JFXButton actionButton = new JFXButton();

    /// The status line above the button.
    private final Label status = new Label();

    /// Keeps the button in step with the process while one is running.
    private final Timeline ticker;

    /// Lazily created destination pages.
    private @Nullable InstancesPage instancesPage;
    private @Nullable VersionsPage versionsPage;
    private @Nullable SettingsPage settingsPage;
    private @Nullable NodeRuntimesPage nodeRuntimesPage;

    /// Creates the home page.
    public MainPage() {
        getStyleClass().remove("gray-background");

        AdvancedListBox sideBar = new AdvancedListBox()
                .startCategory(i18n("dsh.home").toUpperCase(Locale.ROOT))
                .addNavigationDrawerItem(i18n("dsh.home"), SVG.HOME, () -> Controllers.navigate(this))
                .addNavigationDrawerItem(i18n("instance.manage"), SVG.FORMAT_LIST_BULLETED,
                        () -> Controllers.navigate(getInstancesPage()))
                .addNavigationDrawerItem(i18n("dsh.versions.title"), SVG.DOWNLOAD,
                        () -> Controllers.navigate(getVersionsPage()))
                .addNavigationDrawerItem(i18n("dsh.node.title"), SVG.STADIA_CONTROLLER,
                        () -> Controllers.navigate(getNodeRuntimesPage()))
                .addNavigationDrawerItem(i18n("settings"), SVG.SETTINGS,
                        () -> Controllers.navigate(getSettingsPage()));
        FXUtils.setLimitWidth(sideBar, 200);
        setLeft(sideBar);

        // The launch control is the only centre content; it is placed inside a
        // StackPane because a Control's children belong to its skin.
        StackPane centre = new StackPane(buildLaunchPane());
        StackPane.setAlignment(status, Pos.TOP_LEFT);
        centre.getChildren().add(status);
        setCenter(centre);

        ticker = new Timeline(new KeyFrame(Duration.seconds(1), event -> refreshActionState()));
        ticker.setCycleCount(Animation.INDEFINITE);
        ticker.play();

        refresh();
    }

    /// Builds the bottom-right launch control.
    ///
    /// @return the launch pane
    private Node buildLaunchPane() {
        actionButton.getStyleClass().add("launch-button");
        actionButton.setDefaultButton(true);

        actionLabel.setStyle("-fx-font-size: 16px;");
        actionTarget.setStyle("-fx-font-size: 12px;");
        VBox graphic = new VBox(actionLabel, actionTarget);
        graphic.setAlignment(Pos.CENTER);
        actionButton.setGraphic(graphic);
        actionButton.setOnAction(event -> onActionButton());

        JFXButton menuButton = new JFXButton();
        menuButton.getStyleClass().add("menu-button");
        menuButton.setGraphic(SVG.ARROW_DROP_UP.createIcon(30));
        FXUtils.installFastTooltip(menuButton, i18n("dsh.launch.select"));
        menuButton.setOnAction(event -> showInstanceMenu(menuButton));

        HBox pane = new HBox(actionButton, menuButton);
        pane.getStyleClass().add("launch-pane");
        pane.setAlignment(Pos.BOTTOM_RIGHT);
        pane.setMaxWidth(Region.USE_PREF_SIZE);
        pane.setMaxHeight(Region.USE_PREF_SIZE);
        pane.setPickOnBounds(false);
        StackPane.setAlignment(pane, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(pane, new Insets(0, 20, 20, 0));
        return pane;
    }

    /// Opens a page by name, used by the `--page` start-up option.
    ///
    /// @param name the page name: `home`, `instances`, `versions` or `settings`
    /// @return whether a page was opened
    public boolean openPage(String name) {
        String value = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
        if (value.startsWith("settings/")) {
            SettingsPage page = getSettingsPage();
            Controllers.navigate(page);
            return page.openTab(value.substring("settings/".length()));
        }
        switch (value) {
            case "home", "" -> Controllers.navigate(this);
            case "instances" -> Controllers.navigate(getInstancesPage());
            case "versions" -> Controllers.navigate(getVersionsPage());
            case "node", "runtimes" -> Controllers.navigate(getNodeRuntimesPage());
            case "settings" -> Controllers.navigate(getSettingsPage());
            default -> {
                return false;
            }
        }
        return true;
    }

    /// Returns the instance list page, creating it on first use.
    ///
    /// @return the instance list page
    public InstancesPage getInstancesPage() {
        if (instancesPage == null) {
            instancesPage = new InstancesPage();
        }
        return instancesPage;
    }

    /// Returns the version list page, creating it on first use.
    ///
    /// @return the versions page
    public VersionsPage getVersionsPage() {
        if (versionsPage == null) {
            versionsPage = new VersionsPage();
        }
        return versionsPage;
    }

    /// Returns the Node runtime page, creating it on first use.
    ///
    /// @return the Node runtime page
    public NodeRuntimesPage getNodeRuntimesPage() {
        if (nodeRuntimesPage == null) {
            nodeRuntimesPage = new NodeRuntimesPage();
        }
        return nodeRuntimesPage;
    }

    /// Returns the settings page, creating it on first use.
    ///
    /// @return the settings page
    public SettingsPage getSettingsPage() {
        if (settingsPage == null) {
            settingsPage = new SettingsPage();
        }
        return settingsPage;
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

        refreshActionState();
    }

    /// Recomputes the button's label, tooltip and status line.
    private void refreshActionState() {
        DshInstance current = currentInstance.get();
        if (current == null) {
            actionLabel.setText(i18n("dsh.launch.no_instance"));
            actionTarget.setText(i18n("dsh.launch.no_instance.hint"));
            status.setText(i18n("dsh.running.none"));
            return;
        }

        boolean launching = DshLaunchService.isLaunching(current.id());
        DshProcess running = DshProcessManager.find(current.id()).orElse(null);

        if (launching) {
            actionLabel.setText(i18n("dsh.launch.launching"));
        } else if (running != null) {
            actionLabel.setText(i18n("dsh.stop"));
        } else {
            actionLabel.setText(i18n("dsh.launch"));
        }
        actionTarget.setText(current.id());

        int count = DshProcessManager.running().size();
        status.setText(count == 0 ? i18n("dsh.running.none") : i18n("dsh.running.count", count));
    }

    /// Launches or stops the selected instance.
    private void onActionButton() {
        DshInstance instance = currentInstance.get();
        if (instance == null) {
            Controllers.navigate(getInstancesPage());
            return;
        }
        if (DshLaunchService.isLaunching(instance.id())) {
            return;
        }

        DshProcess running = DshProcessManager.find(instance.id()).orElse(null);
        if (running != null) {
            DshLaunchService.stop(instance.id(), this::refresh);
            return;
        }
        DshLaunchService.launch(instance, ignored -> refresh());
    }

    /// Shows the instance picker next to the launch button.
    ///
    /// Selecting a running instance leaves the button showing Stop, which is
    /// what keeps a second server from being started on the same home.
    ///
    /// @param anchor the button the popup is anchored to
    private void showInstanceMenu(Node anchor) {
        List<DshInstance> instances = DshInstanceManager.list();
        if (instances.isEmpty()) {
            Controllers.navigate(getInstancesPage());
            return;
        }

        ComponentList list = new ComponentList();
        list.getContent().add(buildHeader(i18n("dsh.launch.select")));

        for (DshInstance instance : instances) {
            DshProcess running = DshProcessManager.find(instance.id()).orElse(null);
            LineButton row = new LineButton();
            row.setTitle(instance.id());
            row.setSubtitle(running != null
                    ? i18n("dsh.instance.running.since", running.uptime().toSeconds())
                    : i18n("dsh.instance.summary", instance.version(), instance.profile(),
                            i18n("dsh.instance.home." + instance.homeMode().name().toLowerCase(Locale.ROOT))));
            JFXButton indicator = FXUtils.newToggleButton4(running != null ? SVG.CANCEL : SVG.ROCKET_LAUNCH, 18);
            indicator.setMouseTransparent(true);
            row.setTitleTrailing(indicator);
            row.setOnAction(event -> {
                settings().selectedInstanceIdProperty().set(instance.id());
                currentInstance.set(instance);
                refreshActionState();
                hidePopup(row);
            });
            list.getContent().add(row);
        }

        VBox box = new VBox(list);
        box.setPadding(new Insets(8));
        box.setMaxWidth(420);

        JFXPopup popup = new JFXPopup(box);
        for (Node child : list.getContent()) {
            child.getProperties().put(POPUP_KEY, popup);
        }
        popup.show(anchor, JFXPopup.PopupVPosition.BOTTOM, JFXPopup.PopupHPosition.RIGHT,
                0, -anchor.getBoundsInLocal().getHeight());
    }

    /// Key under which a menu row remembers the popup that owns it.
    private static final String POPUP_KEY = "hmcl-dsh-popup";

    /// Hides the popup a row belongs to.
    ///
    /// @param row the row that was activated
    private static void hidePopup(Node row) {
        Object popup = row.getProperties().get(POPUP_KEY);
        if (popup instanceof JFXPopup jfxPopup) {
            jfxPopup.hide();
        }
    }

    /// Builds a bold heading for a menu card.
    ///
    /// @param text the heading text
    /// @return the heading row
    private Node buildHeader(String text) {
        LineTextPane header = new LineTextPane();
        header.setTitle(text);
        header.getStyleClass().add("section-header");
        return header;
    }
}
