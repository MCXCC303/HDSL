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
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
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
    private final ComponentList instanceList = new ComponentList();

    /// The status line above the list.
    private final Label status = new Label();

    /// Creates the instance list page.
    public InstancesPage() {

        AdvancedListBox sideBar = new AdvancedListBox()
                .startCategory(i18n("dsh.instance.list").toUpperCase(Locale.ROOT))
                .addNavigationDrawerItem(i18n("dsh.instance.create"), SVG.ADD, this::createInstance);
        FXUtils.setLimitWidth(sideBar, 200);
        setLeft(sideBar);

        VBox content = new VBox(10);
        content.setPadding(new Insets(10));
        content.getChildren().addAll(status, instanceList);

        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("edge-to-edge");
        FXUtils.smoothScrolling(scroll);

        setCenter(scroll);

        refresh();
    }

    @Override
    public ReadOnlyObjectProperty<State> stateProperty() {
        return state.getReadOnlyProperty();
    }

    @Override
    public void refresh() {
        List<DshInstance> instances = DshInstanceManager.list();

        instanceList.getContent().clear();
        instanceList.getContent().add(buildSectionHeader(i18n("dsh.instance.section")));

        if (instances.isEmpty()) {
            instanceList.getContent().add(buildNote(i18n("dsh.instance.empty.hint")));
        } else {
            for (DshInstance instance : instances) {
                instanceList.getContent().add(buildRow(instance));
            }
        }

        status.setText(instances.isEmpty()
                ? i18n("dsh.instance.none")
                : i18n("dsh.instance.count", instances.size()));
    }

    /// Builds one row for an instance.
    ///
    /// The row carries the three affordances HMCL's instance list has: a dot to
    /// choose the instance the home page acts on, a rocket to run or stop it,
    /// and a menu — or the row itself — to open its management page.
    ///
    /// @param instance the instance to render
    /// @return the row
    private LineButton buildRow(DshInstance instance) {
        boolean selected = instance.id().equals(settings().selectedInstanceIdProperty().get());
        boolean running = DshProcessManager.find(instance.id()).isPresent();

        JFXButton selector = FXUtils.newToggleButton4(
                selected ? SVG.CHECK_CIRCLE : SVG.ALPHA_CIRCLE, 14);
        FXUtils.installFastTooltip(selector, i18n("dsh.instance.select"));
        selector.setOnAction(event -> select(instance));

        JFXButton launch = FXUtils.newToggleButton4(running ? SVG.CANCEL : SVG.ROCKET_LAUNCH, 18);
        FXUtils.installFastTooltip(launch, running ? i18n("dsh.stop") : i18n("dsh.launch"));
        launch.setOnAction(event -> toggleLaunch(instance, running));

        JFXButton menu = FXUtils.newToggleButton4(SVG.MORE_VERT, 18);
        FXUtils.installFastTooltip(menu, i18n("dsh.instance.menu"));
        menu.setOnAction(event -> showMenu(menu, instance));

        HBox actions = new HBox(4, launch, menu);
        actions.setAlignment(Pos.CENTER);

        LineButton row = new LineButton();
        row.setLeading(selector);
        row.setTitle(instance.id());
        row.setSubtitle(i18n("dsh.instance.summary",
                instance.version(),
                instance.profile(),
                i18n("dsh.instance.home." + instance.homeMode().name().toLowerCase(Locale.ROOT))));
        row.setRowTrailing(actions);
        row.setOnAction(event -> Controllers.navigate(new InstancePage(instance)));
        return row;
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
    /// @param running  whether it is currently running
    private void toggleLaunch(DshInstance instance, boolean running) {
        if (running) {
            DshLaunchService.stop(instance.id(), this::refresh);
        } else {
            DshLaunchService.launch(instance, ignored -> refresh());
        }
    }

    /// Shows the per-instance menu.
    ///
    /// @param anchor   the button the popup is anchored to
    /// @param instance the instance
    private void showMenu(Node anchor, DshInstance instance) {
        JFXPopup[] popupRef = new JFXPopup[1];
        Runnable close = () -> {
            if (popupRef[0] != null) {
                popupRef[0].hide();
            }
        };

        AdvancedListBox menu = new AdvancedListBox();
        menu.addNavigationDrawerItem(i18n("dsh.instance.select"), SVG.CHECK, () -> {
            select(instance);
            close.run();
        });
        menu.addNavigationDrawerItem(i18n("dsh.instance.manage"), SVG.SETTINGS_FILL, () -> {
            close.run();
            Controllers.navigate(new InstancePage(instance));
        });
        menu.addNavigationDrawerItem(i18n("dsh.instance.open_home"), SVG.FOLDER_OPEN, () -> {
            close.run();
            try {
                FXUtils.showFileInExplorer(instance.instanceDirectory());
            } catch (DshException e) {
                Controllers.dialog(e.getMessage(), i18n("message.error"), MessageType.ERROR);
            }
        });
        menu.addNavigationDrawerItem(i18n("dsh.instance.remove"), SVG.DELETE, () -> {
            close.run();
            removeInstance(instance);
        });

        popupRef[0] = new JFXPopup(menu);
        popupRef[0].show(anchor, JFXPopup.PopupVPosition.BOTTOM, JFXPopup.PopupHPosition.RIGHT,
                -anchor.getBoundsInLocal().getWidth(), 0);
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
    private Node buildSectionHeader(String text) {
        LineTextPane header = new LineTextPane();
        header.setTitle(text);
        header.getStyleClass().add("section-header");
        return header;
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
