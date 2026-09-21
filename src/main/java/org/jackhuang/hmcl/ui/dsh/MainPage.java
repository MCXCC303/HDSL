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
import javafx.scene.image.ImageView;
import org.jackhuang.hmcl.Metadata;
import org.jackhuang.hmcl.theme.Themes;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import org.jackhuang.hmcl.dsh.DshInstance;
import org.jackhuang.hmcl.dsh.DshInstanceIcon;
import org.jackhuang.hmcl.dsh.DshInstanceIcons;
import org.jackhuang.hmcl.dsh.DshInstanceManager;
import org.jackhuang.hmcl.dsh.DshProcess;
import org.jackhuang.hmcl.dsh.DshProcessManager;
import org.jackhuang.hmcl.setting.DshInstanceRepository;
import org.jackhuang.hmcl.setting.GameDirectoryManager;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.construct.AdvancedListBox;
import org.jackhuang.hmcl.ui.construct.AdvancedListItem;
import org.jackhuang.hmcl.ui.construct.ImageContainer;
import org.jackhuang.hmcl.ui.construct.ComponentList;
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
            new ReadOnlyObjectWrapper<>(State.rootNode(titleNode()));

    /// The instance the launch control targets.
    private final ObjectProperty<@Nullable DshInstance> currentInstance = new SimpleObjectProperty<>();

    /// The launch/stop button's first line.
    private final Label actionLabel = new Label();

    /// The launch/stop button's second line, showing the target instance.
    private final Label actionTarget = new Label();

    /// The button itself, so its state can be refreshed.
    private final JFXButton actionButton = new JFXButton();

    /// Keeps the button in step with the process while one is running.
    private final Timeline ticker;

    /// The sidebar entry that opens the selected instance's management page.
    ///
    /// It carries the instance name as its subtitle, so the home page always
    /// shows which instance the launch button and this entry act on.
    private final AdvancedListItem currentInstanceItem = new AdvancedListItem();

    /// The instance icon shown on the manage entry.
    private final ImageContainer currentInstanceIcon = new ImageContainer(AdvancedListItem.LEFT_GRAPHIC_SIZE);

    /// Lazily created destination pages.
    private @Nullable DownloadPage downloadPage;
    private @Nullable SettingsPage settingsPage;

    /// Creates the home page.
    public MainPage() {
        getStyleClass().remove("gray-background");

        currentInstanceIcon.setMouseTransparent(true);
        AdvancedListItem.setAlignment(currentInstanceIcon, Pos.CENTER);
        currentInstanceItem.setLeftGraphic(currentInstanceIcon);
        currentInstanceItem.setTitle(i18n("dsh.instance.manage"));
        currentInstanceItem.setSubtitle(i18n("dsh.launch.no_instance.hint"));
        currentInstanceItem.setOnAction(event -> openCurrentInstance());

        // The original groups by what a thing is, not by where it sits in the
        // page: the instance you are about to launch leads its own group, and
        // the launcher's own settings sit apart from the game's.
        AdvancedListBox sideBar = new AdvancedListBox()
                .startCategory(i18n("instance").toUpperCase(Locale.ROOT))
                .add(currentInstanceItem)
                .addNavigationDrawerItem(i18n("dsh.instance.list"), SVG.FORMAT_LIST_BULLETED,
                        () -> Controllers.navigate(getInstancesPage()))
                .addNavigationDrawerItem(i18n("download"), SVG.DOWNLOAD,
                        () -> Controllers.navigate(getDownloadPage()))
                .startCategory(i18n("settings.launcher.general").toUpperCase(Locale.ROOT))
                .addNavigationDrawerItem(i18n("settings"), SVG.SETTINGS,
                        () -> Controllers.navigate(getSettingsPage()));
        FXUtils.setLimitWidth(sideBar, 200);
        getLeft().getStyleClass().add("gray-background");
        setLeft(sideBar);

        // The launch control is the only centre content; it is placed inside a
        // StackPane because a Control's children belong to its skin.
        setCenter(new StackPane(buildLaunchPane()));

        // The instance the page acts on is the selected folder's selection, and
        // the folder's contents can change under it — an icon edit, a rename, a
        // new instance made by the wizard. Both are observed rather than asked
        // for, so nothing here has to be refreshed by whoever changed them.
        currentInstance.bind(GameDirectoryManager.selectedInstanceProperty());
        currentInstance.addListener((observable, was, now) -> refresh());
        GameDirectoryManager.registerVersionsListener(this::onRepositoryChanged);

        ticker = new Timeline(new KeyFrame(Duration.seconds(1), event -> refreshActionState()));
        ticker.setCycleCount(Animation.INDEFINITE);
        ticker.play();

        refresh();
    }

    /// Redraws the page for the folder that just published its instances.
    ///
    /// @param repository the selected folder's repository
    private void onRepositoryChanged(DshInstanceRepository repository) {
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
        String raw = name == null ? "" : name.trim();
        if (raw.toLowerCase(Locale.ROOT).startsWith("instance:")) {
            String rest = raw.substring("instance:".length());
            int slash = rest.indexOf('/');
            String id = slash < 0 ? rest : rest.substring(0, slash);
            String tab = slash < 0 ? null : rest.substring(slash + 1);
            org.jackhuang.hmcl.dsh.DshInstance instance = org.jackhuang.hmcl.dsh.DshInstanceManager.find(id);
            if (instance == null) {
                return false;
            }
            Controllers.navigate(new InstancePage(instance, tab));
            return true;
        }

        if (raw.toLowerCase(Locale.ROOT).startsWith("create-version:")) {
            Controllers.getDecorator().startWizard(
                    new org.jackhuang.hmcl.ui.dsh.install.DshInstallWizardProvider(
                            raw.substring("create-version:".length())),
                    i18n("dsh.instance.create"));
            return true;
        }

        String value = raw.toLowerCase(Locale.ROOT);
        if (value.startsWith("settings/")) {
            SettingsPage page = getSettingsPage();
            Controllers.navigate(page);
            return page.openTab(value.substring("settings/".length()));
        }
        switch (value) {
            // The home page is where the application already is, so asking for
            // it is not a navigation. Pushing it would give the root page a back
            // entry and put the back arrow on the one page the original has none.
            case "home", "" -> {
                return true;
            }
            case "instances" -> Controllers.navigate(getInstancesPage());
            case "download" -> Controllers.navigate(getDownloadPage());
            // Deep links used when verifying the wizards; they are how a page
            // inside the decorator can be reached without clicking.
            case "create" -> {
                Controllers.getDecorator().startWizard(
                        new org.jackhuang.hmcl.ui.dsh.install.DshInstallWizardProvider(),
                        i18n("dsh.instance.create"));
                return true;
            }
            case "settings" -> Controllers.navigate(getSettingsPage());
            default -> {
                return false;
            }
        }
        return true;
    }

    /// Builds the title the window shows while the home page is open.
    ///
    /// The original puts the application's name and version here rather than a
    /// page title, with its mark beside them: this is the one page that is not
    /// somewhere you went, so naming it would be naming the place you already
    /// are. The state is the root state, which is what keeps the back arrow off.
    ///
    /// @return the title node
    private static Node titleNode() {
        ImageView icon = new ImageView(FXUtils.newBuiltinImage("/assets/img/icon-title.png"));

        Label label = new Label(Metadata.FULL_TITLE);
        label.getStyleClass().add("jfx-decorator-title");
        label.textFillProperty().bind(Themes.titleFillProperty());

        HBox node = new HBox(8, icon, label);
        node.setPadding(new Insets(0, 0, 0, 2));
        node.setAlignment(Pos.CENTER_LEFT);
        return node;
    }

    /// Returns the download page, creating it on first use.
    ///
    /// @return the download page
    public DownloadPage getDownloadPage() {
        if (downloadPage == null) {
            downloadPage = new DownloadPage();
        }
        return downloadPage;
    }

    /// Returns the instance list page.
    ///
    /// The page belongs to [Controllers], because more than one place opens it
    /// and only one page may exist: two of them would show two different lists.
    ///
    /// @return the instance list page
    public InstancesPage getInstancesPage() {
        return Controllers.getInstancesPage();
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

    /// Redraws the page for the instance it currently acts on.
    ///
    /// The instance is not chosen here: it is the selected folder's selection,
    /// which the folder corrects on its own when what it points at is gone.
    @Override
    public void refresh() {
        DshInstance current = currentInstance.get();

        currentInstanceItem.setSubtitle(current == null
                ? i18n("dsh.launch.no_instance.hint")
                : current.id());
        currentInstanceIcon.setImage(current == null
                ? DshInstanceIcon.DEFAULT.load()
                : DshInstanceIcons.load(current));

        refreshActionState();
    }

    /// Recomputes the button's label and tooltip.
    private void refreshActionState() {
        DshInstance current = currentInstance.get();
        if (current == null) {
            actionLabel.setText(i18n("dsh.launch.no_instance"));
            actionTarget.setText(i18n("dsh.launch.no_instance.hint"));
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

    }

    /// Opens the management page of the instance the launch button targets.
    ///
    /// With no instance chosen there is nothing to manage, so the list is shown
    /// instead of an empty editor.
    private void openCurrentInstance() {
        DshInstance instance = currentInstance.get();
        if (instance == null) {
            Controllers.navigate(getInstancesPage());
            return;
        }
        Controllers.navigate(new InstancePage(instance));
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
        List<DshInstance> instances = GameDirectoryManager.getSelectedRepository().getInstances();
        if (instances.isEmpty()) {
            Controllers.navigate(getInstancesPage());
            return;
        }

        InstancePickerMenu.show(anchor, instances, GameDirectoryManager::setSelectedInstance);
    }

    /// Key under which a menu row remembers the popup that owns it.
    private static final String POPUP_KEY = "hdsl-popup";

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
    /// Builds a section title.
    ///
    /// HMCL's helper rather than a styled row: the original puts the title
    /// between card groups, outside their background.
    ///
    /// @param text the title
    /// @return the title node
    private static Node buildHeader(String text) {
        return ComponentList.createComponentListTitle(text);
    }
}
