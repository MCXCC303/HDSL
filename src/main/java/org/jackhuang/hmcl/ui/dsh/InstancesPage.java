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
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.dsh.DshException;
import org.jackhuang.hmcl.dsh.DshHomeMode;
import org.jackhuang.hmcl.dsh.DshInstance;
import org.jackhuang.hmcl.dsh.DshInstanceManager;
import org.jackhuang.hmcl.dsh.DshVersion;
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
            new ReadOnlyObjectWrapper<>(State.fromTitle(i18n("instance.manage")));

    /// The card listing the instances.
    private final ComponentList instanceList = new ComponentList();

    /// The status line above the list.
    private final Label status = new Label();

    /// Creates the instance list page.
    public InstancesPage() {
        getStyleClass().remove("gray-background");

        AdvancedListBox sideBar = new AdvancedListBox()
                .startCategory(i18n("instance.manage").toUpperCase(Locale.ROOT))
                .addNavigationDrawerItem(i18n("dsh.instance.create"), SVG.ADD, this::createInstance);
        FXUtils.setLimitWidth(sideBar, 200);
        getLeft().getStyleClass().add("gray-background");
        getLeft().getStyleClass().add("gray-background");
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

    /// Builds one row for an instance, with a remove action.
    ///
    /// @param instance the instance to render
    /// @return the row
    private LineButton buildRow(DshInstance instance) {
        JFXButton remove = FXUtils.newToggleButton4(SVG.DELETE, 18);
        remove.setOnAction(event -> removeInstance(instance));

        LineButton row = new LineButton();
        row.setTitle(instance.id());
        row.setSubtitle(i18n("dsh.instance.summary",
                instance.version(),
                instance.profile(),
                i18n("dsh.instance.home." + instance.homeMode().name().toLowerCase(Locale.ROOT))));
        row.setTitleTrailing(remove);
        row.setOnAction(event -> {
            try {
                FXUtils.showFileInExplorer(instance.instanceDirectory());
            } catch (DshException e) {
                Controllers.dialog(e.getMessage(), i18n("message.error"), MessageType.ERROR);
            }
        });
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
