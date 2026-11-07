/*
 * HDSL
 * Copyright (C) 2026  HDSL contributors
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
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane.MessageType;
import org.jackhuang.hmcl.ui.construct.TwoLineListItem;
import org.jackhuang.hmcl.ui.wizard.WizardController;
import org.jackhuang.hmcl.ui.wizard.WizardPage;
import org.jackhuang.hmcl.util.SettingsMap;
import org.jetbrains.annotations.NotNullByDefault;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

/**
 * Wizard page for selecting the modpack export format.
 */
@NotNullByDefault
public final class ModpackTypePage extends VBox implements WizardPage {
	private final WizardController controller;

	public ModpackTypePage(WizardController controller) {
		this.controller = controller;

		setPadding(new Insets(10));
		getStyleClass().add("jfx-list-view");
		setMaxSize(400, 150);
		setSpacing(8);

		Label title = new Label(i18n("modpack.export.as"));
		VBox.setMargin(title, new Insets(8, 0, 8, 12));
		getChildren().setAll(title, createButton(true), createButton(false));

		Platform.runLater(() -> warnAboutLocalPlugins(controller));
	}

	private static void warnAboutLocalPlugins(WizardController controller) {
		SettingsMap settings = controller.getSettings();
		if (Boolean.TRUE.equals(settings.get(ModpackExportWizardProvider.LOCAL_WARNED)) ||
		    ModpackExportWizardProvider.localPluginsOf(settings).isEmpty()) {
			return;
		}
		settings.put(ModpackExportWizardProvider.LOCAL_WARNED, Boolean.TRUE);
		Controllers.dialog(i18n("dsh.modpack.development.enter"),
		                   i18n("dsh.modpack.development.title"),
		                   MessageType.WARNING);
	}

	private Node createButton(boolean hdsl) {
		JFXButton button = new JFXButton();
		button.getStyleClass().add("card");
		button.setOnAction(event -> {
			controller.getSettings()
			          .put(ModpackExportWizardProvider.FORMAT,
			               hdsl
			               ? ModpackExportWizardProvider.FORMAT_HDSL
			               : ModpackExportWizardProvider.FORMAT_PACKFORGE);
			controller.onNext();
		});
		button.prefWidthProperty().bind(widthProperty());

		BorderPane graphic = new BorderPane();
		graphic.setMouseTransparent(true);
		graphic.setLeft(new TwoLineListItem(i18n(hdsl ? "dsh.modpack.type" : "dsh.packforge.type"),
		                                    i18n(hdsl ? "dsh.modpack.type.detail" : "dsh.packforge.type.detail")));

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
