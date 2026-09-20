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
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import com.jfoenix.controls.JFXListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.dsh.DshException;
import org.jackhuang.hmcl.dsh.DshHomeMode;
import org.jackhuang.hmcl.dsh.DshInstance;
import org.jackhuang.hmcl.dsh.DshInstanceManager;
import org.jackhuang.hmcl.dsh.DshVersion;
import org.jackhuang.hmcl.dsh.DshProcessManager;
import org.jackhuang.hmcl.dsh.DshVersionManager;
import org.jackhuang.hmcl.ui.dsh.install.DshInstallWizardProvider;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.construct.AdvancedListBox;
import javafx.stage.DirectoryChooser;
import com.jfoenix.controls.JFXTextField;
import java.nio.file.Path;
import java.util.ArrayList;
import org.jackhuang.hmcl.setting.GameDirectory;
import org.jackhuang.hmcl.setting.GameDirectoryManager;
import org.jackhuang.hmcl.ui.construct.AdvancedListItem;
import org.jetbrains.annotations.Nullable;
import org.jackhuang.hmcl.ui.construct.ComponentList;
import org.jackhuang.hmcl.ui.construct.LineButton;
import org.jackhuang.hmcl.ui.construct.LineTextPane;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane.MessageType;
import org.jackhuang.hmcl.ui.decorator.DecoratorAnimatedPage;
import org.jackhuang.hmcl.ui.decorator.DecoratorPage;
import org.jackhuang.hmcl.ui.wizard.Refreshable;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.List;
import java.util.Locale;

import static org.jackhuang.hmcl.setting.SettingsManager.settings;
import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

/// Lists the DeepSeek Harness instances managed by HMCL-DSH.
///
/// An instance pins one installed `dsh` version together with the profile,
/// working directory and `DSH_HOME` it runs against. Creating one is
/// intentionally cheap: the default is a private, isolated home, which is the
/// only shape that stays safe while upstream churns through releases.
@NotNullByDefault
public final class InstancesPage extends DecoratorAnimatedPage implements DecoratorPage, Refreshable {
    /// The page state published to the window decorator.
    private final ReadOnlyObjectWrapper<State> state =
            new ReadOnlyObjectWrapper<>(State.fromTitle(i18n("dsh.instance.list")));

    /// The card listing the instances.
    private final JFXListView<DshInstance> instanceList = buildInstanceList();

    /// The filter typed into the search box, or `null` when nothing is typed.
    private @Nullable String filter;

    /// The sidebar entry naming the directory whose instances are listed.
    private final AdvancedListItem currentDirectoryItem = new AdvancedListItem();

    /// The field the search toolbar carries.
    private final JFXTextField searchField = new JFXTextField();

    /// The toolbar of buttons, shown when not searching.
    private final HBox normalBar = new HBox(4);

    /// The search toolbar, shown in place of the buttons.
    private final HBox searchBar = new HBox(4);

    /// Holds whichever toolbar is current.
    private final StackPane toolbarHost = new StackPane();

    /// Creates the instance list page.
    public InstancesPage() {

        // The directory leads and the actions sit at the bottom, which is how
        // HMCL's instance list is arranged: what you are looking at, then what
        // you can do.
        currentDirectoryItem.setLeftIcon(SVG.FOLDER_OPEN);
        currentDirectoryItem.setOnAction(event -> showDirectoryMenu());
        // The original pairs the entry with a close button that drops the folder
        // from the list; the entry itself opens the chooser.
        currentDirectoryItem.setRightAction(SVG.CLOSE, this::removeCurrentDirectory);
        AdvancedListBox sideBar = new AdvancedListBox()
                .add(currentDirectoryItem)
                .addNavigationDrawerItem(i18n("dsh.directory.add"), SVG.ADD, this::addDirectory);

        AdvancedListBox actions = new AdvancedListBox()
                .addNavigationDrawerItem(i18n("dsh.instance.install"), SVG.ADD, this::createInstance)
                .addNavigationDrawerItem(i18n("dsh.settings.global"), SVG.SETTINGS_FILL,
                        () -> Controllers.navigate(new SettingsPage()));

        FXUtils.setLimitWidth(sideBar, 200);
        FXUtils.setLimitHeight(actions, 40 * 2 + 12 * 2);
        sideBar.setMaxHeight(Double.MAX_VALUE);
        VBox.setVgrow(sideBar, Priority.ALWAYS);

        // The page itself already paints the translucent plate, so the sidebar
        // must not paint it again: two layers of a half-transparent colour is
        // visibly darker, and the original's sidebar and content match. Only the
        // home page plates its sidebar separately, because it clears the page's.
        setLeft(sideBar, actions);

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
        VBox.setVgrow(instanceList, Priority.ALWAYS);
        pane.getChildren().setAll(root);

        setCenter(pane);

        refresh();
    }

    @Override
    public ReadOnlyObjectProperty<State> stateProperty() {
        return state.getReadOnlyProperty();
    }

    @Override
    public void refresh() {
        GameDirectory directory = GameDirectoryManager.selected();
        currentDirectoryItem.setTitle(directory.displayName());
        currentDirectoryItem.setSubtitle(directory.path());

        List<DshInstance> instances = new ArrayList<>(DshInstanceManager.listIn(directory.directory()));
        if (filter != null && !filter.isBlank()) {
            String needle = filter.trim().toLowerCase(Locale.ROOT);
            instances.removeIf(instance -> !instance.id().toLowerCase(Locale.ROOT).contains(needle));
        }

        instanceList.getItems().setAll(instances);
        instanceList.refresh();
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
        normalBar.setAlignment(Pos.CENTER_LEFT);
        normalBar.setPadding(new Insets(4));
        // Two buttons, as the running original has. Its source carries four —
        // adding an instance and importing a modpack among them — but the
        // release in use keeps both in the sidebar, and the release is what this
        // is being matched against.
        normalBar.getChildren().setAll(
                toolbarButton(i18n("button.refresh"), SVG.REFRESH, this::refresh),
                toolbarButton(i18n("search"), SVG.SEARCH, this::showSearch));

        searchField.setPromptText(i18n("search"));
        HBox.setHgrow(searchField, Priority.ALWAYS);
        searchField.textProperty().addListener((observable, was, value) -> {
            filter = value;
            refresh();
        });

        JFXButton close = FXUtils.newToggleButton4(SVG.CLOSE);
        FXUtils.installFastTooltip(close, i18n("button.cancel"));
        close.setOnAction(event -> hideSearch());
        FXUtils.onEscPressed(searchField, close::fire);

        searchBar.setAlignment(Pos.CENTER_LEFT);
        searchBar.setPadding(new Insets(4));
        searchBar.getChildren().setAll(searchField, close);

        toolbarHost.getChildren().setAll(normalBar);
        return toolbarHost;
    }

    /// Builds a toolbar button.
    ///
    /// @param text   the label
    /// @param icon   the leading icon
    /// @param action the action
    /// @return the button
    private static JFXButton toolbarButton(String text, SVG icon, Runnable action) {
        JFXButton button = new JFXButton(text);
        button.setGraphic(icon.createIcon(18));
        button.getStyleClass().add("jfx-tool-bar-button");
        button.setOnAction(event -> action.run());
        return button;
    }

    /// Replaces the toolbar with the search field.
    private void showSearch() {
        toolbarHost.getChildren().setAll(searchBar);
        searchField.requestFocus();
    }

    /// Restores the toolbar and clears the filter.
    private void hideSearch() {
        searchField.clear();
        filter = null;
        toolbarHost.getChildren().setAll(normalBar);
        refresh();
    }

    /// Drops the folder being shown from the list.
    ///
    /// Nothing is deleted: the instances inside keep their files and stop being
    /// listed. The default folder cannot be dropped, because it is where a new
    /// instance goes when nowhere else is chosen.
    private void removeCurrentDirectory() {
        GameDirectory directory = GameDirectoryManager.selected();
        if (directory.isDefault()) {
            Controllers.dialog(i18n("dsh.directory.remove.default"),
                    i18n("dsh.directory.remove"), MessageType.ERROR);
            return;
        }
        GameDirectoryManager.remove(directory.id());
        refresh();
    }

    /// Offers the folders the launcher knows about.
    ///
    /// The entry names the folder being shown; this is how another is chosen or
    /// one is dropped from the list. Dropping removes nothing but the entry —
    /// the instances inside keep their files.
    private void showDirectoryMenu() {
        List<GameDirectory> directories = GameDirectoryManager.directories();
        GameDirectory selected = GameDirectoryManager.selected();

        AdvancedListBox menu = new AdvancedListBox();
        List<Node> rows = new ArrayList<>();
        for (GameDirectory directory : directories) {
            LineButton row = new LineButton();
            row.setTitle(directory.displayName());
            row.setSubtitle(directory.path() + "  ·  "
                    + i18n("dsh.directory.count", GameDirectoryManager.countInstances(directory)));
            row.setLeading(directory.id().equals(selected.id()) ? SVG.CHECK : SVG.FOLDER_OPEN, 16);
            row.setOnAction(event -> {
                hidePopup(rows);
                GameDirectoryManager.select(directory.id());
                refresh();
            });
            rows.add(row);
        }

        for (Node row : rows) {
            menu.add(row);
        }
        if (!selected.isDefault()) {
            LineButton remove = new LineButton();
            remove.setTitle(i18n("dsh.directory.remove"));
            remove.setSubtitle(i18n("dsh.directory.remove.hint"));
            remove.setLeading(SVG.DELETE, 16);
            remove.setOnAction(event -> {
                hidePopup(rows);
                GameDirectoryManager.remove(selected.id());
                refresh();
            });
            menu.add(remove);
            rows.add(remove);
        }

        JFXPopup popup = new JFXPopup(menu);
        for (Node row : rows) {
            row.getProperties().put(DIRECTORY_POPUP, popup);
        }
        popup.show(currentDirectoryItem, JFXPopup.PopupVPosition.BOTTOM,
                JFXPopup.PopupHPosition.LEFT, currentDirectoryItem.getWidth(), 0);
    }

    /// Key under which a directory menu row remembers the popup that owns it.
    private static final String DIRECTORY_POPUP = "hmcl-dsh-directory-popup";

    /// Hides the popup a directory menu row belongs to.
    ///
    /// @param rows the rows of the menu
    private static void hidePopup(List<Node> rows) {
        for (Node row : rows) {
            if (row.getProperties().get(DIRECTORY_POPUP) instanceof JFXPopup popup) {
                popup.hide();
                return;
            }
        }
    }

    /// Asks for a folder to look for instances in.
    ///
    /// Nothing is copied into it: adding a folder makes the launcher look
    /// inside, and the instances it finds are the ones already there.
    private void addDirectory() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle(i18n("dsh.directory.add"));
        Path chosen = Controllers.showDialog(chooser);
        if (chosen == null) {
            return;
        }
        try {
            GameDirectory added = GameDirectoryManager.add(chosen);
            GameDirectoryManager.select(added.id());
            refresh();
        } catch (IllegalArgumentException e) {
            Controllers.dialog(e.getMessage(), i18n("message.error"), MessageType.ERROR);
        }
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
            cell.setHandlers(this::select, this::toggleLaunch, this::showMenu);
            cell.setSelectedIdSupplier(() -> settings().selectedInstanceIdProperty().get());
            cell.setRunningCheck(instance -> DshProcessManager.find(instance.id()).isPresent());
            return cell;
        });
        list.setFixedCellSize(66);
        list.getStyleClass().addAll("edge-to-edge", "no-padding");
        FXUtils.setLimitHeight(list, Region.USE_COMPUTED_SIZE);
        return list;
    }

    /// Chooses the instance the home page acts on.
    ///
    /// @param instance the instance to select
    private void select(DshInstance instance) {
        settings().selectedInstanceIdProperty().set(instance.id());
        refresh();
    }

    /// Starts or stops an instance.
    ///
    /// @param instance the instance
    private void toggleLaunch(DshInstance instance) {
        if (DshProcessManager.find(instance.id()).isPresent()) {
            DshLaunchService.stop(instance.id(), this::refresh);
        } else {
            DshLaunchService.launch(instance, ignored -> refresh());
        }
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

    /// Opens the create-an-instance wizard.
    ///
    /// The wizard mirrors HMCL's install flow: choose a version, then fill in
    /// the quick-install page and pick the plugins to add.
    private void createInstance() {
        List<DshVersion> installed = DshVersionManager.listInstalled();
        if (installed.isEmpty()) {
            Controllers.dialog(i18n("dsh.instance.need_version"),
                    i18n("dsh.instance.create"), MessageType.WARNING);
            return;
        }
        Controllers.getDecorator().startWizard(new DshInstallWizardProvider(), i18n("dsh.instance.create"));
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
                        refresh();
                        Controllers.showToast(i18n("dsh.instance.removed", instance.id()));
                    } catch (DshException e) {
                        Controllers.dialog(e.getMessage(), i18n("dsh.instance.remove_failed"), MessageType.ERROR);
                    }
                },
                null);
    }

    /// Builds a bold section heading rendered as the first row of a card.
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
    private static Node buildSectionHeader(String text) {
        return ComponentList.createComponentListTitle(text);
    }

    /// Builds a non-interactive note row.
    ///
    /// @param text the text to show
    /// @return the row
    private Node buildNote(String text) {
        LineTextPane note = new LineTextPane();
        note.setText(text);
        return note;
    }
}
