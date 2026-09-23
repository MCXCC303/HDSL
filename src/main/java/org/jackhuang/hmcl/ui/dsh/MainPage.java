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

    /// The state the launch button was last drawn for.
    ///
    /// The button is redrawn once a second while an instance is starting or
    /// stopping, and a tooltip installed again every second would be a new
    /// tooltip every second for no gain.
    private DshProcessManager.@Nullable LaunchState shownActionState;

    /// The sidebar entry that opens the selected instance's management page.
    ///
    /// It carries the instance name as its subtitle, so the home page always
    /// shows which instance the launch button and this entry act on.
    private final AdvancedListItem currentInstanceItem = new AdvancedListItem();

    /// The instance icon shown on the manage entry.
    private final ImageContainer currentInstanceIcon = new ImageContainer(AdvancedListItem.LEFT_GRAPHIC_SIZE);

    /// The account entry at the head of the sidebar.
    ///
    /// The original's home page leads with the account, and shows **which** one: its icon is the
    /// account's skin and its second line names the kind of account it is. A launcher whose first
    /// question is "who am I doing this for" has to be able to answer it without being asked, and an
    /// entry that only said "Accounts" would leave the person opening a page to find out what they
    /// could have been told.
    private final AdvancedListItem accountItem = new AdvancedListItem();

    /// The account's picture, drawn from the skin when one has been chosen.
    private final javafx.scene.canvas.Canvas accountAvatar = new javafx.scene.canvas.Canvas(32, 32);

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

        accountAvatar.setMouseTransparent(true);
        accountItem.setLeftGraphic(accountAvatar);
        accountItem.setOnAction(event ->
                Controllers.navigate(new org.jackhuang.hmcl.ui.dsh.AccountListPage()));

        // The original's order, which is an order of importance rather than of implementation: the
        // account leads, because without one nothing can be launched; then the game; then the
        // launcher's own settings. An account is its own group rather than a row under the settings,
        // because it is not a setting — it is the thing everything else is done on behalf of.
        AdvancedListBox sideBar = new AdvancedListBox()
                .startCategory(i18n("dsh.account.list").toUpperCase(Locale.ROOT))
                .add(accountItem)
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

        if (raw.toLowerCase(Locale.ROOT).startsWith("export-modpack:")) {
            org.jackhuang.hmcl.dsh.DshInstance instance = org.jackhuang.hmcl.dsh.DshInstanceManager
                    .find(raw.substring("export-modpack:".length()));
            if (instance == null) {
                return false;
            }
            Controllers.getDecorator().startWizard(new ModpackExportWizardProvider(instance),
                    i18n("modpack.wizard"));
            return true;
        }

        String value = raw.toLowerCase(Locale.ROOT);
        if (value.startsWith("download/")) {
            DownloadPage page = getDownloadPage();
            Controllers.navigate(page);
            return page.openTab(value.substring("download/".length()));
        }
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
            // Creating an instance starts at the version list, which is what the
            // original does: the version is chosen there, and the wizard that
            // follows only fills in what that choice left open.
            case "create" -> Controllers.navigate(getDownloadPage());
            case "accounts" -> Controllers.navigate(new org.jackhuang.hmcl.ui.dsh.AccountListPage());
            case "skin" -> {
                // A skin belongs to an account, so the deep link opens the chooser for whichever
                // account is in force — which is what a person would reach it through.
                org.jackhuang.hmcl.dsh.DshAccount chosen =
                        org.jackhuang.hmcl.setting.SettingsManager.settings().activeAccount();
                if (chosen == null) {
                    Controllers.dialog(i18n("dsh.launch.needs_account"),
                            i18n("dsh.account.list"), org.jackhuang.hmcl.ui.construct.MessageDialogPane.MessageType.WARNING);
                } else {
                    Controllers.dialog(new org.jackhuang.hmcl.ui.dsh.settings.SkinDialog(
                            chosen, this::refreshAccountItem));
                }
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

    /// Returns the download page.
    ///
    /// The page belongs to [Controllers] for the same reason the instance list
    /// does: creating an instance opens it too.
    ///
    /// @return the download page
    public DownloadPage getDownloadPage() {
        return Controllers.getDownloadPage();
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

        // With nothing chosen the entry stops naming an instance and says what
        // there is instead, which is what the original's own home entry does:
        // the title names the state and the subtitle names the way out of it.
        currentInstanceItem.setTitle(current == null
                ? i18n("dsh.instance.empty")
                : i18n("dsh.instance.manage"));
        currentInstanceItem.setSubtitle(current == null
                ? i18n("dsh.instance.install")
                : current.id());
        currentInstanceIcon.setImage(current == null
                ? DshInstanceIcon.DEFAULT.load()
                : DshInstanceIcons.load(current));

        refreshAccountItem();
        refreshActionState();
    }

    /// Recomputes the button's label and tooltip.
    ///
    /// The label is the instance's state and nothing else, so the button offers
    /// Stop whenever anything is up or on its way up — which is what keeps a
    /// second server from being started from here.
    private void refreshActionState() {
        DshInstance current = currentInstance.get();
        if (current == null) {
            actionLabel.setText(i18n("dsh.launch.no_instance"));
            actionTarget.setText(i18n("dsh.launch.no_instance.hint"));
            return;
        }

        DshProcessManager.LaunchState state = DshLaunchService.state(current.id());
        actionLabel.setText(DshLaunchService.actionLabel(state));
        actionTarget.setText(current.id());
        if (state != shownActionState) {
            shownActionState = state;
            FXUtils.installFastTooltip(actionButton, DshLaunchService.actionHint(state));
        }
    }

    /// Opens the management page of the instance the launch button targets.
    ///
    /// With no instance chosen there is nothing to manage, so the list is shown
    /// instead of an empty editor.
    /// Redraws the account entry: which account is in force, and whose face it is.
    ///
    /// The account is **asked for by name**, not taken from the top of the list: the entry has to
    /// follow the choice, and the choice is no longer encoded in the list's order.
    private void refreshAccountItem() {
        org.jackhuang.hmcl.dsh.DshAccount account =
                org.jackhuang.hmcl.setting.SettingsManager.settings().activeAccount();
        if (account == null) {
            accountItem.setTitle(i18n("dsh.account.none.short"));
            accountItem.setSubtitle(i18n("dsh.account.none.hint"));
        } else {
            accountItem.setTitle(account.displayName());
            accountItem.setSubtitle(account.vendorId());
        }
        drawAccountAvatar();
    }

    /// Draws the skin's head on the account entry, or a monogram when there is none to draw.
    ///
    /// The skin belongs to the **launcher**, not to an account: it is chosen once and drawn beside
    /// whatever account is in force. So when there is no account, there is nothing for it to be
    /// beside — and drawing it anyway is how a deleted account's face stayed on the home page. The
    /// skin was never the account's to begin with, but it reads as the account's when it sits where
    /// the account's face was.
    private void drawAccountAvatar() {
        org.jackhuang.hmcl.dsh.DshAccount active =
                org.jackhuang.hmcl.setting.SettingsManager.settings().activeAccount();
        javafx.scene.canvas.GraphicsContext gc = accountAvatar.getGraphicsContext2D();
        gc.clearRect(0, 0, 32, 32);

        // Nothing to show when there is nobody to show it for.
        if (active == null) {
            return;
        }

        javafx.scene.image.Image skin = org.jackhuang.hmcl.dsh.skin.DshSkin.image(active.key());
        if (skin != null) {
            gc.setImageSmoothing(false);
            double unit = 4.0;
            gc.drawImage(skin, 8, 8, 8, 8, 0, 0, unit * 8, unit * 8);
            gc.drawImage(skin, 40, 8, 8, 8, 0, 0, unit * 8, unit * 8);
            return;
        }
        String initial = active.displayName().substring(0, 1).toUpperCase(Locale.ROOT);
        gc.setFill(javafx.scene.paint.Color.web("#8d8d8d"));
        gc.fillRoundRect(0, 0, 32, 32, 8, 8);
        gc.setFill(javafx.scene.paint.Color.WHITE);
        gc.setFont(javafx.scene.text.Font.font(16));
        gc.fillText(initial, 10, 23);
    }

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
        DshLaunchService.toggle(instance, this::refresh);
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
