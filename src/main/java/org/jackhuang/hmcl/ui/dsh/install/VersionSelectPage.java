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
package org.jackhuang.hmcl.ui.dsh.install;

import com.jfoenix.controls.JFXButton;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.dsh.DshInstance;
import org.jackhuang.hmcl.dsh.DshVersion;
import org.jackhuang.hmcl.dsh.DshVersionManager;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.construct.ComponentList;
import org.jackhuang.hmcl.ui.construct.LineButton;
import org.jackhuang.hmcl.ui.construct.LineTextPane;
import org.jackhuang.hmcl.ui.wizard.WizardController;
import org.jackhuang.hmcl.ui.wizard.WizardPage;
import org.jackhuang.hmcl.util.SettingsMap;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.List;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

/// The first step of the install wizard: choose which DeepSeek Harness version
/// the new instance pins.
///
/// Mirrors HMCL's version-selection step. Unlike HMCL, the chosen version must
/// already be installed: downloading a version is the Versions page's job, and
/// keeping the two apart means the wizard never has to explain two different
/// progress bars.
@NotNullByDefault
public final class VersionSelectPage extends VBox implements WizardPage {
    /// The wizard controller used to advance.
    private final WizardController controller;

    /// Creates the version-selection page.
    ///
    /// @param controller the wizard controller
    public VersionSelectPage(WizardController controller) {
        this.controller = controller;

        setSpacing(10);
        setPadding(new Insets(20));
        setAlignment(Pos.TOP_LEFT);

        Label title = new Label(i18n("dsh.install.step.version"));
        title.setStyle("-fx-font-size: 15px; -fx-font-weight: bold;");

        ComponentList list = new ComponentList();
        List<DshVersion> installed = DshVersionManager.listInstalled();

        if (installed.isEmpty()) {
            list.getContent().add(buildNote(i18n("dsh.install.step.version.empty")));
        } else {
            for (DshVersion version : installed) {
                list.getContent().add(buildVersionRow(version));
            }
        }

        getChildren().addAll(title, list, buildFooter());
        VBox.setVgrow(list, Priority.ALWAYS);
    }

    /// Builds one selectable version row.
    ///
    /// @param version the installed version
    /// @return the row
    private LineButton buildVersionRow(DshVersion version) {
        LineButton row = new LineButton();
        row.setTitle(version.version());
        row.setSubtitle(version.directory().toString());
        row.setOnAction(event -> {
            controller.getSettings().put(DshInstallWizardProvider.VERSION, version.version());
            controller.onNext();
        });
        return row;
    }

    /// Builds the page footer.
    ///
    /// @return the footer
    private HBox buildFooter() {
        JFXButton cancel = new JFXButton(i18n("button.cancel"));
        cancel.setOnAction(event -> controller.onCancel());

        JFXButton next = new JFXButton(i18n("button.next"));
        next.getStyleClass().add("dialog-accept");
        String selected = controller.getSettings().get(DshInstallWizardProvider.VERSION);
        next.setDisable(selected == null || selected.isBlank());
        next.setOnAction(event -> controller.onNext());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox footer = new HBox(8, cancel, spacer, next);
        footer.setAlignment(Pos.CENTER_RIGHT);
        return footer;
    }

    /// Builds a non-interactive note row.
    ///
    /// @param text the text to show
    /// @return the row
    private javafx.scene.Node buildNote(String text) {
        LineTextPane note = new LineTextPane();
        note.setText(text);
        return note;
    }

    @Override
    public void onNavigate(SettingsMap settings) {
        // Rebuild the footer so the next button reflects a selection made after
        // the page was first created.
        getChildren().set(getChildren().size() - 1, buildFooter());
    }

    @Override
    public String getTitle() {
        return i18n("dsh.install.step.version");
    }
}
