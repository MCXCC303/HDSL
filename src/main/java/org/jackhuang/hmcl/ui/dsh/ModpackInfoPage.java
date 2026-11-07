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
import com.jfoenix.controls.JFXTextArea;
import com.jfoenix.controls.JFXTextField;
import com.mcxcc303.hdsl.instance.DshInstance;
import javafx.beans.property.SimpleStringProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.construct.ComponentList;
import org.jackhuang.hmcl.ui.construct.LinePane;
import org.jackhuang.hmcl.ui.construct.LineTextPane;
import org.jackhuang.hmcl.ui.wizard.WizardController;
import org.jackhuang.hmcl.ui.wizard.WizardPage;
import org.jackhuang.hmcl.util.SettingsMap;
import org.jetbrains.annotations.NotNullByDefault;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

/**
 * Wizard page for editing modpack metadata (name, version, author, description, and URLs).
 */
@NotNullByDefault
public final class ModpackInfoPage extends VBox implements WizardPage {
	private final WizardController controller;
	private final DshInstance instance;
	private final SettingsMap settings;
	private final SimpleStringProperty name = new SimpleStringProperty();
	private final SimpleStringProperty packVersion = new SimpleStringProperty();
	private final SimpleStringProperty author = new SimpleStringProperty();
	private final SimpleStringProperty description = new SimpleStringProperty();
	private final SimpleStringProperty url = new SimpleStringProperty();
	private final SimpleStringProperty referenceUrl = new SimpleStringProperty();

	public ModpackInfoPage(WizardController controller, DshInstance instance, SettingsMap settings) {
		this.controller = controller;
		this.instance = instance;
		this.settings = settings;

		name.set(string(ModpackExportWizardProvider.NAME, instance.id()));
		packVersion.set(string(ModpackExportWizardProvider.VERSION, "1.0"));
		author.set(string(ModpackExportWizardProvider.AUTHOR, ""));
		description.set(string(ModpackExportWizardProvider.DESCRIPTION, ""));
		url.set(string(ModpackExportWizardProvider.URL, ""));
		referenceUrl.set(string(ModpackExportWizardProvider.REFERENCE_URL, ""));

		setSpacing(10);
		setPadding(new Insets(10));

		ComponentList list = new ComponentList();
		list.getContent().add(instanceRow());
		list.getContent().add(textRow(i18n("modpack.name"), name));
		list.getContent().add(textRow(i18n("archive.version"), packVersion));
		list.getContent().add(textRow(i18n("archive.author"), author));
		list.getContent().add(textRow(i18n("modpack.export.url"), url));
		list.getContent().add(textRow(i18n("modpack.export.reference_url"), referenceUrl));
		list.getContent().add(descriptionRow());

		list.setMaxHeight(Double.MAX_VALUE);
		ScrollPane scroll = new ScrollPane(list);
		scroll.setFitToWidth(true);
		VBox.setVgrow(scroll, Priority.ALWAYS);
		scroll.setFitToWidth(true);
		getChildren().add(scroll);

		HBox buttons = new HBox(8);
		buttons.setAlignment(Pos.CENTER_RIGHT);
		JFXButton next = new JFXButton(i18n("button.next"));
		next.getStyleClass().add("jfx-button-raised");
		next.setOnAction(event -> {
			cleanup(settings);
			controller.onNext();
		});
		buttons.getChildren().add(next);
		getChildren().add(buttons);
	}

	private Node instanceRow() {
		LineTextPane pane = new LineTextPane();
		pane.setTitle(i18n("modpack.wizard.step.initialization.exported_version"));
		pane.setText(instance.id().equals(instance.version())
		             ? instance.id()
		             : instance.id() + " · " + instance.version());
		return pane;
	}

	private Node textRow(String title, SimpleStringProperty property) {
		LinePane pane = new LinePane();
		pane.setTitle(title);

		JFXTextField field = new JFXTextField();
		field.setMinWidth(420);
		pane.setRight(field);
		field.textProperty().bindBidirectional(property);
		return pane;
	}

	private Node descriptionRow() {
		VBox box = new VBox(8);

		Label label = new Label(i18n("modpack.description"));
		JFXTextArea area = new JFXTextArea();
		area.setPrefRowCount(6);
		FXUtils.setLimitHeight(area, 132);
		area.setWrapText(true);
		area.textProperty().bindBidirectional(description);
		box.getChildren().addAll(label, area);
		box.setMaxHeight(Region.USE_PREF_SIZE);
		return box;
	}

	private String string(String key, String fallback) {
		Object value = settings.get(key);
		return value == null || value.toString().isBlank() ? fallback : value.toString();
	}

	@Override
	public void onNavigate(SettingsMap settings) {
	}

	@Override
	public void cleanup(SettingsMap settings) {
		settings.put(ModpackExportWizardProvider.NAME, name.get());
		settings.put(ModpackExportWizardProvider.VERSION, packVersion.get());
		settings.put(ModpackExportWizardProvider.AUTHOR, author.get());
		settings.put(ModpackExportWizardProvider.DESCRIPTION, description.get());
	}

	@Override
	public String getTitle() {
		return i18n("modpack.wizard.step.1.title");
	}
}
