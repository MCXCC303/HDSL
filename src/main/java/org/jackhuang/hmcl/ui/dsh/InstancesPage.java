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
import org.jackhuang.hmcl.ui.ToolbarListPageSkin;
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

    /// Holds one row per folder the launcher knows about.
    private final VBox directoryBox = new VBox();

    /// The field the search toolbar carries.


    /// The toolbar of buttons, shown when not searching.
    /// The page's toolbar, which swaps itself for a search field.
    private final ListSearchBar toolbar = new ListSearchBar(this::refresh);

    /// The search toolbar, shown in place of the buttons.




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
                .addNavigationDrawerItem(i18n("dsh.settings.global"), SVG.SETTINGS_FILL,
                        () -> Controllers.navigate(new SettingsPage()));

        FXUtils.setLimitWidth(directoryPane, 200);
        FXUtils.setLimitHeight(actions, 40 * 2 + 12 * 2);

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

        refresh();
    }

    @Override
    public ReadOnlyObjectProperty<State> stateProperty() {
        return state.getReadOnlyProperty();
    }

    @Override
    public void refresh() {
        GameDirectory directory = GameDirectoryManager.selected();

        directoryBox.getChildren().setAll(GameDirectoryManager.directories().stream()
                .map(candidate -> {
                    // The remove button is on every row, as the original has it.
                    // The folder the launcher owns refuses rather than
                    // disappearing from the row: it is where a new instance goes
                    // when nowhere else is chosen, and a row that silently has no
                    // button reads as a different kind of row.
                    DirectoryListItem item = new DirectoryListItem(candidate,
                            chosen -> {
                                GameDirectoryManager.select(chosen.id());
                                refresh();
                            },
                            this::removeDirectory);
                    item.setSelected(candidate.id().equals(directory.id()));
                    return (Node) item;
                })
                .toList());

        List<DshInstance> instances = new ArrayList<>(DshInstanceManager.listIn(directory.directory()));
        instances.removeIf(instance -> !toolbar.accepts(instance.id()));

        instanceList.getItems().setAll(instances);
        instanceList.refresh();

        // A selection pointing at an instance that is gone is no selection: the
        // launcher would otherwise offer to start something that is not there,
        // while the sidebar says there is nothing at all.
        String selected = settings().selectedInstanceIdProperty().get();
        if (selected != null && instances.stream().noneMatch(instance -> instance.id().equals(selected))) {
            settings().selectedInstanceIdProperty().set(
                    instances.isEmpty() ? null : instances.get(0).id());
        }
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
        // height the original does not have. Mine was eight pixels taller for
        // the four added on each side.
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
        refresh();
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
            cell.setHandlers(this::select, this::open, this::toggleLaunch, this::showMenu);
            cell.setSelectedIdSupplier(() -> settings().selectedInstanceIdProperty().get());
            cell.setRunningCheck(instance -> DshProcessManager.find(instance.id()).isPresent());
            return cell;
        });
        // No fixed cell size: the original does not set one here, and a height
        // chosen here is a number the original never had. The row's height comes
        // from its content and its padding.
        list.getStyleClass().add("no-padding");
        FXUtils.setLimitHeight(list, Region.USE_COMPUTED_SIZE);
        return list;
    }

    /// Chooses the instance the home page acts on.
    ///
    /// @param instance the instance to select
    private void select(DshInstance instance) {
        // The radio button's job, and only that: which instance the launcher
        // starts. Opening one is the row's job.
        settings().selectedInstanceIdProperty().set(instance.id());
        refresh();
    }

    /// Opens an instance's page, choosing it on the way.
    ///
    /// @param instance the instance to open
    private void open(DshInstance instance) {
        select(instance);
        Controllers.navigate(getInstancePage(instance));
    }

    /// Returns the page for an instance, creating it on first use.
    ///
    /// @param instance the instance
    /// @return the page
    private static InstancePage getInstancePage(DshInstance instance) {
        return new InstancePage(instance);
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
        // No check for an installed version: there does not have to be one. The
        // wizard downloads the version the new instance will use as the first
        // thing it does, so a launcher with nothing installed can still make an
        // instance — which is the only way it ever gets anything installed.
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
