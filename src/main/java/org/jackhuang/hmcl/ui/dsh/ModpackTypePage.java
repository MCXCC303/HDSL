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
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.construct.TwoLineListItem;
import org.jackhuang.hmcl.ui.wizard.WizardController;
import org.jackhuang.hmcl.ui.wizard.WizardPage;
import org.jackhuang.hmcl.util.SettingsMap;
import org.jetbrains.annotations.NotNullByDefault;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

/// Asks what kind of pack to write.
///
/// The original asks the same question with four answers, because four launchers
/// read four formats. This asks it with one, and says so: a pack this launcher can
/// read back. The others that run DeepSeek Harness do not agree on a format yet, so
/// offering to write one of theirs would be offering to write something that may
/// not be read — and the step exists anyway, because that is where the choice will
/// be made when there is more than one to make.
@NotNullByDefault
public final class ModpackTypePage extends VBox implements WizardPage {
    /// The wizard this page belongs to.
    private final WizardController controller;

    /// Creates the page.
    ///
    /// @param controller the wizard controller
    public ModpackTypePage(WizardController controller) {
        this.controller = controller;

        setPadding(new Insets(10));
        getStyleClass().add("jfx-list-view");
        setMaxSize(400, 150);
        setSpacing(8);

        Label title = new Label(i18n("modpack.export.as"));
        VBox.setMargin(title, new Insets(8, 0, 8, 12));
        getChildren().setAll(title, createButton());
    }

    /// Builds the one option there is.
    ///
    /// The shape the original's three options have: a card, a two-line label, and
    /// the arrow that says it leads somewhere.
    ///
    /// @return the row
    private Node createButton() {
        JFXButton button = new JFXButton();
        button.getStyleClass().add("card");
        button.setOnAction(event -> controller.onNext());
        button.prefWidthProperty().bind(widthProperty());

        BorderPane graphic = new BorderPane();
        graphic.setMouseTransparent(true);
        graphic.setLeft(new TwoLineListItem(i18n("dsh.modpack.type"), i18n("dsh.modpack.type.detail")));

        Node arrow = SVG.ARROW_FORWARD.createIcon();
        BorderPane.setAlignment(arrow, Pos.CENTER);
        graphic.setRight(arrow);
        button.setGraphic(graphic);
        return button;
    }

    @Override
    public void cleanup(SettingsMap settings) {
    }

    @Override
    public String getTitle() {
        return i18n("modpack.wizard.step.3.title");
    }
}
