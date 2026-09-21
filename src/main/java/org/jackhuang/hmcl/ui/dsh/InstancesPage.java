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
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.ListChangeListener;
import javafx.geometry.Insets;
import javafx.scene.Node;
import com.jfoenix.controls.JFXListView;
import javafx.beans.binding.Bindings;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import org.jackhuang.hmcl.dsh.DshException;
import org.jackhuang.hmcl.dsh.DshInstance;
import org.jackhuang.hmcl.dsh.DshInstanceManager;
import org.jackhuang.hmcl.dsh.DshProcessManager.LaunchState;
import org.jackhuang.hmcl.setting.DshInstanceRepository;
import org.jackhuang.hmcl.setting.GameDirectory;
import org.jackhuang.hmcl.setting.GameDirectoryManager;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.ToolbarListPageSkin;
import org.jackhuang.hmcl.ui.construct.AdvancedListBox;
import org.jackhuang.hmcl.ui.construct.AdvancedListItem;
import org.jackhuang.hmcl.ui.construct.ComponentList;
import org.jackhuang.hmcl.ui.construct.LineButton;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane.MessageType;
import org.jackhuang.hmcl.ui.decorator.DecoratorAnimatedPage;
import org.jackhuang.hmcl.ui.decorator.DecoratorPage;
import org.jackhuang.hmcl.ui.wizard.Refreshable;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

/// Lists the DeepSeek Harness instances managed by HMCL-DSH.
///
/// An instance pins one installed `dsh` version together with the profile,
/// working directory and `DSH_HOME` it runs against. Creating one is
/// intentionally cheap: the default is a private, isolated home, which is the
/// only shape that stays safe while upstream churns through releases.
///
/// The page does not go looking for instances: it is handed the selected
/// folder's [DshInstanceRepository] and redraws whenever that folder publishes a
/// snapshot, which happens on every write and on every switch of folder. That is
/// how an instance created by the wizard appears here without anyone refreshing
/// anything.
@NotNullByDefault
public final class InstancesPage extends DecoratorAnimatedPage implements DecoratorPage, Refreshable {
    /// The page state published to the window decorator.
    private final ReadOnlyObjectWrapper<State> state =
            new ReadOnlyObjectWrapper<>(State.fromTitle(i18n("dsh.instance.list")));

    /// Whether the search field is the reason the list is showing what it shows.
    ///
    /// Declared before the list and the toolbar, because both are built from it
    /// while the page is being constructed.
    private final BooleanProperty searching = new SimpleBooleanProperty(false);

    /// The card listing the instances.
    private final JFXListView<DshInstance> instanceList = buildInstanceList();

    /// Holds one row per folder the launcher knows about.
    private final VBox directoryBox = new VBox();

    /// The page's toolbar, which swaps itself for a search field.
    private final ListSearchBar toolbar = new ListSearchBar(this::filterInstances, searching::set);

    /// The instances the selected folder last published.
    private List<DshInstance> instances = List.of();

    /// What each row was last drawn for.
    ///
    /// A row says whether its instance is starting, up or stopping, and that
    /// changes on its own; the ticker below watches for the change rather than
    /// redrawing the list every second for nothing.
    private Map<String, LaunchState> shownStates = Map.of();

    /// Watches the instances' states while any of them is not simply idle.
    private final Timeline ticker;

    /// Creates the instance list page.
    public InstancesPage() {
        // The original lists every folder as a row rather than one row that
        // opens a menu, and puts the whole list in a scroll pane so a collection
        // of them stays reachable. The add item sits under the list, inside the
        // same scrollable content.
        AdvancedListItem addDirectoryItem = new AdvancedListItem();
        addDirectoryItem.getStyleClass().add("navigation-drawer-item");
        addDirectoryItem.setTitle(i18n("dsh.directory.add"));
        addDirectoryItem.setLeftIcon(SVG.ADD_CIRCLE);
        addDirectoryItem.setOnAction(event -> addDirectory());

        directoryBox.setFillWidth(true);

        VBox directoryContent = new VBox();
        directoryContent.getStyleClass().add("advanced-list-box-content");
        directoryContent.getChildren().setAll(directoryBox, addDirectoryItem);

        ScrollPane directoryPane = new ScrollPane();
        directoryPane.setFitToWidth(true);
        directoryPane.setContent(directoryContent);
        VBox.setVgrow(directoryPane, Priority.ALWAYS);
        FXUtils.smoothScrolling(directoryPane);

        AdvancedListBox actions = new AdvancedListBox()
                .addNavigationDrawerItem(i18n("dsh.instance.install"), SVG.ADD, this::createInstance)
                // The original puts both of these at the foot of its game list, and
                // this is the same pair: build an instance, or build one from a pack
                // somebody made.
                .addNavigationDrawerItem(i18n("install.modpack"), SVG.PACKAGE2, this::installModpack)
                .addNavigationDrawerItem(i18n("dsh.settings.global"), SVG.SETTINGS_FILL,
                        () -> Controllers.navigate(new SettingsPage()));

        FXUtils.setLimitWidth(directoryPane, 200);
        FXUtils.setLimitHeight(actions, 40 * 3 + 12 * 2);

        // The page itself already paints the translucent plate, so the sidebar
        // must not paint it again: two layers of a half-transparent colour is
        // visibly darker, and the original's sidebar and content match. Only the
        // home page plates its sidebar separately, because it clears the page's.
        setLeft(directoryPane, actions);

        // The original wraps the page in a ComponentList, and that is what gives
        // the list its surface: ComponentList wraps each child in a node wearing
        // `options-list-item`, whose rule carries `-monet-surface` — an opaque
        // background. A bare VBox has none, so the wallpaper shows through the
        // list and the rows, which makes every colour in the interface depend on
        // what is behind the window.
        StackPane pane = new StackPane();
        pane.setPadding(new Insets(10));
        pane.getStyleClass().add("notice-pane");

        ComponentList root = new ComponentList();
        root.getStyleClass().add("no-padding");
        root.getContent().add(buildToolbar());
        root.getContent().add(instanceList);
        // ComponentList wraps its children, so a VGrow set on the child lands on a
        // node the box does not lay out and does nothing. The box reads this
        // property off the child and applies it to the wrapper it builds.
        ComponentList.setVgrow(instanceList, Priority.ALWAYS);
        pane.getChildren().setAll(root);

        setCenter(pane);

        // The folders and the instances are both observed rather than fetched:
        // adding a folder rebuilds the sidebar, and any change to the selected
        // folder's instances redraws the list.
        GameDirectoryManager.getGameDirectories().addListener(
                (ListChangeListener<GameDirectory>) change -> loadDirectories());
        GameDirectoryManager.selectedGameDirectoryProperty().addListener(
                (observable, was, now) -> loadDirectories());
        // The chosen instance belongs to the folder, so the row that shows the
        // choice is redrawn when it moves — including when it is moved from
        // somewhere else, which the home page's menu can do.
        GameDirectoryManager.selectedInstanceProperty().addListener(
                (observable, was, now) -> instanceList.refresh());
        GameDirectoryManager.registerVersionsListener(this::loadInstances);

        // The state of an instance changes without anyone here asking: a launch
        // becomes ready, a stop finishes. Watching for that is what keeps the
        // rows honest about which of them can be started and which can only be
        // stopped — the same reason the home page's button has a ticker.
        ticker = new Timeline(new KeyFrame(Duration.seconds(1), event -> refreshLaunchStates()));
        ticker.setCycleCount(Animation.INDEFINITE);
        ticker.play();

        loadDirectories();
    }

    /// Redraws the rows whose instance changed what it is doing.
    private void refreshLaunchStates() {
        Map<String, LaunchState> states = new HashMap<>();
        boolean changed = false;
        for (DshInstance instance : instanceList.getItems()) {
            LaunchState state = DshLaunchService.state(instance.id());
            states.put(instance.id(), state);
            if (shownStates.get(instance.id()) != state) {
                changed = true;
            }
        }
        shownStates = states;
        if (changed) {
            instanceList.refresh();
        }
    }

    @Override
    public ReadOnlyObjectProperty<State> stateProperty() {
        return state.getReadOnlyProperty();
    }

    /// Re-reads the selected folder.
    ///
    /// The instances themselves arrive through the repository, so this only has
    /// to ask for a fresh read; what comes back is published to this page the
    /// same way a write made anywhere else is.
    @Override
    public void refresh() {
        GameDirectoryManager.getSelectedRepository().refresh();
    }

    /// Redraws the sidebar from the folders the launcher knows about.
    private void loadDirectories() {
        GameDirectory directory = GameDirectoryManager.selected();

        directoryBox.getChildren().setAll(GameDirectoryManager.getGameDirectories().stream()
                .map(candidate -> {
                    // The remove button is on every row, as the original has it.
                    // The folder the launcher owns refuses rather than
                    // disappearing from the row: it is where a new instance goes
                    // when nowhere else is chosen, and a row that silently has no
                    // button reads as a different kind of row.
                    DirectoryListItem item = new DirectoryListItem(candidate,
                            chosen -> GameDirectoryManager.select(chosen.id()),
                            this::removeDirectory);
                    item.setSelected(candidate.id().equals(directory.id()));
                    return (Node) item;
                })
                .toList());
    }

    /// Shows the instances of the folder that just published them.
    ///
    /// @param repository the selected folder's repository
    private void loadInstances(DshInstanceRepository repository) {
        instances = repository.getInstances();
        filterInstances();
    }

    /// Redraws the list from the last published instances and the search text.
    ///
    /// A selection is not touched here: it belongs to the folder, and it is the
    /// folder that decides what it points at.
    private void filterInstances() {
        List<DshInstance> shown = new ArrayList<>(instances);
        shown.removeIf(instance -> !toolbar.accepts(instance.id()));

        instanceList.getItems().setAll(shown);
        instanceList.refresh();
        shownStates = new HashMap<>();
        refreshLaunchStates();
    }

    /// Builds the toolbar above the list.
    ///
    /// The original's toolbar swaps rather than carries both: search is a
    /// button, and pressing it replaces the row with a field and a close
    /// button. A permanently visible field takes the width the buttons need
    /// and offers a control nobody asked for yet.
    ///
    /// @return the toolbar container
    private Node buildToolbar() {
        // No padding: the buttons are 37 pixels tall by their own stylesheet and
        // the original's toolbar is exactly that, so anything added here is a
        // height the original does not have.
        // The page's own buttons; the shared bar adds the search button and the
        // field it opens, so every list page opens its search the same way.
        toolbar.setButtons(
                ToolbarListPageSkin.createToolbarButton2(i18n("button.refresh"), SVG.REFRESH, this::refresh));
        return toolbar;
    }

    /// Drops a folder from the list.
    ///
    /// Nothing is deleted: the instances inside keep their files and stop being
    /// listed.
    ///
    /// @param directory the folder to drop
    private void removeDirectory(GameDirectory directory) {
        if (directory.isDefault()) {
            Controllers.dialog(i18n("dsh.directory.remove.default"),
                    i18n("dsh.directory.remove"), MessageType.ERROR);
            return;
        }
        GameDirectoryManager.remove(directory.id());
    }

    /// Asks for a folder to look for instances in.
    ///
    /// Nothing is copied into it: adding a folder makes the launcher look
    /// inside, and the instances it finds are the ones already there.
    private void addDirectory() {
        // The original opens a page rather than a folder chooser: a folder is
        // only one of the three things a directory is, and a chooser has nowhere
        // to ask for its name or whether to record it relative to the launcher.
        Controllers.navigate(new DirectoryPage());
    }

    /// Builds the instance list.
    ///
    /// A `ListView` with a custom cell is used rather than a component list,
    /// because that is what HMCL's instance list is: the cell supplies the
    /// radio button, the icon and the two-line label that the original
    /// typography depends on.
    ///
    /// @return the list
    private JFXListView<DshInstance> buildInstanceList() {
        JFXListView<DshInstance> list = new JFXListView<>();
        list.setCellFactory(view -> {
            InstanceListCell cell = new InstanceListCell();
            cell.setHandlers(this::select, this::open, this::toggleLaunch, this::showMenu);
            cell.setSelectedInstanceSupplier(GameDirectoryManager::getSelectedInstance);
            cell.setStateCheck(instance -> DshLaunchService.state(instance.id()));
            return cell;
        });
        // No fixed cell size: the original does not set one here, and a height
        // chosen here is a number the original never had. The row's height comes
        // from its content and its padding.
        list.getStyleClass().add("no-padding");
        FXUtils.setLimitHeight(list, Region.USE_COMPUTED_SIZE);

        // What the list says when it holds nothing, which is what the original's
        // game list says in the same case: the reason there is nothing to show,
        // and nothing about search results unless a search is what emptied it.
        StackPane placeholder = new StackPane();
        placeholder.getStyleClass().add("notice-pane");
        Label placeholderLabel = new Label();
        placeholderLabel.textProperty().bind(Bindings.when(searching)
                .then(i18n("search.no_results_found"))
                .otherwise(i18n("dsh.instance.empty") + "\n" + i18n("dsh.instance.empty.hint")));
        placeholder.getChildren().add(placeholderLabel);
        list.setPlaceholder(placeholder);
        return list;
    }

    /// Chooses the instance the home page acts on.
    ///
    /// @param instance the instance to select
    private void select(DshInstance instance) {
        // The radio button's job, and only that: which instance the launcher
        // starts. Opening one is the row's job.
        GameDirectoryManager.setSelectedInstance(instance);
    }

    /// Opens an instance's page.
    ///
    /// Opening one is not choosing it: the radio button chooses, and the row
    /// opens. The original keeps the two apart for the same reason — the pages
    /// that act on an instance are reached by looking at it, not by making it the
    /// one the launch button starts.
    ///
    /// @param instance the instance to open
    private void open(DshInstance instance) {
        Controllers.navigate(new InstancePage(instance));
    }

    /// Starts or stops an instance.
    ///
    /// @param instance the instance
    private void toggleLaunch(DshInstance instance) {
        DshLaunchService.toggle(instance, this::refresh);
    }

    /// Shows the per-instance menu.
    ///
    /// @param instance the instance
    /// @param anchor   the button the popup is anchored to
    private void showMenu(DshInstance instance, JFXButton anchor) {
        AdvancedListBox menu = new AdvancedListBox();
        JFXPopup[] popupRef = new JFXPopup[1];
        Runnable close = () -> {
            if (popupRef[0] != null) {
                popupRef[0].hide();
            }
        };

        menu.add(buildMenuRow(i18n("dsh.instance.select"), SVG.CHECK, () -> {
            select(instance);
            close.run();
        }));
        menu.add(buildMenuRow(i18n("dsh.instance.manage"), SVG.SETTINGS_FILL, () -> {
            close.run();
            Controllers.navigate(new InstancePage(instance));
        }));
        menu.add(buildMenuRow(i18n("dsh.instance.open_home"), SVG.FOLDER_OPEN, () -> {
            close.run();
            try {
                FXUtils.showFileInExplorer(instance.instanceDirectory());
            } catch (DshException e) {
                Controllers.dialog(e.getMessage(), i18n("message.error"), MessageType.ERROR);
            }
        }));
        menu.add(buildMenuRow(i18n("dsh.instance.remove"), SVG.DELETE, () -> {
            close.run();
            removeInstance(instance);
        }));

        popupRef[0] = new JFXPopup(menu);
        popupRef[0].show(anchor, JFXPopup.PopupVPosition.BOTTOM, JFXPopup.PopupHPosition.RIGHT,
                -anchor.getBoundsInLocal().getWidth(), 0);
    }

    /// Builds one row for the per-instance menu.
    ///
    /// @param title  the row label
    /// @param icon   the leading icon
    /// @param action the action to run
    /// @return the row
    private LineButton buildMenuRow(String title, SVG icon, Runnable action) {
        LineButton row = new LineButton();
        row.setTitle(title);
        row.setLeading(icon, 16);
        row.setOnAction(event -> action.run());
        return row;
    }

    /// Opens the version list an instance is created from.
    ///
    /// The original routes this through its download page rather than through a
    /// step of its own: the version list is where a version is chosen, and it is
    /// the same list whether the user came here to create an instance or simply
    /// to look at what has been published. Choosing a row there starts the
    /// wizard, which has nothing left to ask about the version.
    private void createInstance() {
        Controllers.navigate(Controllers.getDownloadPage());
    }

    /// Builds an instance from a pack the user chooses.
    ///
    /// The pack carries the harness version it pins, the boot library it was paired
    /// with and the profile's plugins; nothing installed travels with it, so the
    /// instance is built by installing the version and resolving the plugin list.
    /// An instance with the pack's own id has to be dealt with first: writing over
    /// one would be replacing somebody's instance with somebody else's.
    private void installModpack() {
        javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
        chooser.setTitle(i18n("install.modpack"));
        chooser.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter(
                i18n("dsh.modpack.filter"), "*.zip"));
        java.io.File chosen = chooser.showOpenDialog(Controllers.getStage());
        if (chosen == null) {
            return;
        }

        java.nio.file.Path pack = chosen.toPath();
        org.jackhuang.hmcl.dsh.DshModpacks.Manifest manifest;
        try {
            manifest = org.jackhuang.hmcl.dsh.DshModpacks.readManifest(pack);
        } catch (org.jackhuang.hmcl.dsh.DshException e) {
            Controllers.dialog(e.getMessage(), i18n("install.modpack"), MessageType.ERROR);
            return;
        }

        String id = manifest.instanceId();
        if (org.jackhuang.hmcl.dsh.DshInstanceManager.find(id) != null) {
            Controllers.dialog(i18n("dsh.modpack.exists", id), i18n("install.modpack"), MessageType.ERROR);
            return;
        }

        ProgressDialog.run(i18n("install.modpack"), progress -> org.jackhuang.hmcl.dsh.DshModpacks.install(
                pack, id, java.nio.file.Path.of(System.getProperty("user.home")), progress::accept),
                this::refresh);
    }

    /// Removes an instance after confirmation.
    ///
    /// @param instance the instance to remove
    private void removeInstance(DshInstance instance) {
        Controllers.confirm(i18n("dsh.instance.remove.confirm", instance.id()),
                i18n("dsh.instance.remove"),
                () -> {
                    try {
                        DshInstanceManager.delete(instance.id());
                        Controllers.showToast(i18n("dsh.instance.removed", instance.id()));
                    } catch (DshException e) {
                        Controllers.dialog(e.getMessage(), i18n("dsh.instance.remove_failed"), MessageType.ERROR);
                    }
                },
                null);
    }
}
