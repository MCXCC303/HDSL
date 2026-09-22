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

import javafx.beans.property.SimpleStringProperty;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.dsh.DshInstance;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.construct.ComponentList;
import org.jackhuang.hmcl.ui.construct.ComponentSublist;
import org.jackhuang.hmcl.ui.construct.LineTextPane;
import org.jackhuang.hmcl.ui.wizard.WizardController;
import org.jackhuang.hmcl.ui.wizard.WizardPage;
import org.jackhuang.hmcl.util.SettingsMap;
import org.jetbrains.annotations.NotNullByDefault;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

/// Asks what the pack says about itself.
///
/// The original's step, with the fields that mean something here. Its page also
/// carries launch arguments, memory and a server address, because a pack of that
/// launcher's can carry them; a pack of this one carries a version of DeepSeek
/// Harness, the boot library it is paired with and a plugin list, and those are
/// read from the instance rather than typed. What is typed is the part a person
/// writes: what the pack is called, its own version, who made it, and what it is
/// for — which is what somebody receiving it sees before installing anything.
@NotNullByDefault
public final class ModpackInfoPage extends VBox implements WizardPage {
    /// The wizard this page belongs to.
    private final WizardController controller;

    /// The instance being exported.
    private final DshInstance instance;

    /// The wizard's settings.
    private final SettingsMap settings;

    /// What the pack is called.
    private final SimpleStringProperty name = new SimpleStringProperty();

    /// The pack's own version.
    private final SimpleStringProperty packVersion = new SimpleStringProperty();

    /// Who made it.
    private final SimpleStringProperty author = new SimpleStringProperty();

    /// What it is for.
    private final SimpleStringProperty description = new SimpleStringProperty();

    /// Creates the page.
    ///
    /// @param controller the wizard controller
    /// @param instance   the instance being exported
    /// @param settings   the wizard's settings
    public ModpackInfoPage(WizardController controller, DshInstance instance, SettingsMap settings) {
        this.controller = controller;
        this.instance = instance;
        this.settings = settings;

        name.set(string(ModpackExportWizardProvider.NAME, instance.id()));
        packVersion.set(string(ModpackExportWizardProvider.VERSION, "1.0"));
        author.set(string(ModpackExportWizardProvider.AUTHOR, ""));
        description.set(string(ModpackExportWizardProvider.DESCRIPTION, ""));

        setSpacing(10);
        setPadding(new Insets(10));

        ComponentList list = new ComponentList();
        list.getContent().add(instanceRow());
        list.getContent().add(textRow(i18n("modpack.name"), name));
        list.getContent().add(textRow(i18n("archive.version"), packVersion));
        list.getContent().add(textRow(i18n("archive.author"), author));
        list.getContent().add(descriptionRow());

        ScrollPane scroll = new ScrollPane(list);
        scroll.setFitToWidth(true);
        VBox.setVgrow(scroll, javafx.scene.layout.Priority.ALWAYS);
        getChildren().add(scroll);
    }

    /// Builds the row that says which instance is being written out.
    ///
    /// @return the row
    private javafx.scene.Node instanceRow() {
        LineTextPane pane = new LineTextPane();
        pane.setTitle(i18n("modpack.wizard.step.initialization.exported_version"));
        pane.setText(instance.id() + " · " + instance.version());
        return pane;
    }

    /// Builds a row holding one editable line.
    ///
    /// @param title    the row's label
    /// @param property what is typed into it
    /// @return the row
    private javafx.scene.Node textRow(String title, SimpleStringProperty property) {
        ComponentSublist sublist = new ComponentSublist();
        sublist.setTitle(title);

        com.jfoenix.controls.JFXTextField field = new com.jfoenix.controls.JFXTextField();
        FXUtils.bindString(field, property);
        sublist.getContent().add(field);
        return sublist;
    }

    /// Builds the row for what the pack is for.
    ///
    /// @return the row
    private javafx.scene.Node descriptionRow() {
        ComponentSublist sublist = new ComponentSublist();
        sublist.setTitle(i18n("modpack.description"));

        javafx.scene.control.TextArea area = new javafx.scene.control.TextArea();
        area.setPrefRowCount(6);
        area.setWrapText(true);
        area.textProperty().bindBidirectional(description);
        sublist.getContent().add(area);
        return sublist;
    }

    /// Reads a setting.
    ///
    /// @param key      the key
    /// @param fallback what to use when it holds nothing
    /// @return the value
    private String string(String key, String fallback) {
        Object value = settings.get(key);
        return value == null || value.toString().isBlank() ? fallback : value.toString();
    }

    @Override
    public void onNavigate(SettingsMap settings) {
        // The page seeded its fields when it was built, and the wizard keeps what
        // was typed into them, so returning to this step shows what was left there.
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
