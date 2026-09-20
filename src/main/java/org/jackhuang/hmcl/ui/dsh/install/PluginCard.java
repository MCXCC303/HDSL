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

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.binding.Bindings;
import com.jfoenix.controls.JFXButton;
import javafx.css.PseudoClass;
import javafx.geometry.Insets;
import javafx.scene.control.Control;
import javafx.scene.control.Label;
import javafx.scene.control.Skin;
import javafx.scene.control.SkinBase;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.dsh.DshPreset;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.SVGContainer;
import org.jackhuang.hmcl.ui.construct.RipplerContainer;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

/// One selectable plugin in the quick-install grid.
///
/// The card reuses HMCL's installer-card styling: an icon, a name and a status
/// line in a rippling surface. HMCL uses the same shape for Forge, Fabric and
/// NeoForge; HMCL-DSH uses it for the plugins a new profile can start with.
///
/// Selecting is a plain toggle rather than a sub-page, because unlike a mod
/// loader a plugin has no version to choose at install time: `dsh plugin add`
/// resolves the package spec itself.
@NotNullByDefault
public final class PluginCard extends Control {
    /// Applied while the card is selected.
    private static final PseudoClass SELECTED = PseudoClass.getPseudoClass("selected");

    /// The catalogue entry this card represents.
    private final DshPreset preset;

    /// Whether the plugin will be installed.
    private final BooleanProperty selected = new SimpleBooleanProperty(this, "selected", false);

    /// The action run when the card is activated.
    private final ObjectProperty<@Nullable Runnable> onConfigure = new SimpleObjectProperty<>(this, "onConfigure");

    /// The version shown under the name, or `null` for "do not install".
    private final ObjectProperty<@Nullable String> chosenVersion = new SimpleObjectProperty<>(this, "chosenVersion");

    /// Creates a card for a catalogue entry.
    ///
    /// @param preset   the entry
    /// @param selected whether it starts selected
    public PluginCard(DshPreset preset, boolean selected) {
        this.preset = preset;

        getStyleClass().add("installer-item-card");
        this.selected.set(selected);
        this.selected.addListener((observable, oldValue, newValue) ->
                pseudoClassStateChanged(SELECTED, newValue));
        pseudoClassStateChanged(SELECTED, selected);

        setOnMouseClicked(event -> {
            Runnable action = onConfigure.get();
            if (action != null) {
                action.run();
            }
        });
    }

    /// Sets the action run when the card is activated.
    ///
    /// @param action the action
    public void setOnConfigure(Runnable action) {
        onConfigure.set(action);
    }

    /// Returns the version the card currently shows.
    ///
    /// @return the version, an empty string for the current release, or `null` when not installing
    public @Nullable String chosenVersion() {
        return chosenVersion.get();
    }

    /// Sets the version the card shows.
    ///
    /// @param version the version, or `null` for "do not install"
    public void setChosenVersion(@Nullable String version) {
        chosenVersion.set(version);
        selected.set(version != null);
        pseudoClassStateChanged(SELECTED, version != null);
    }


    /// Returns the catalogue entry.
    ///
    /// @return the preset
    public DshPreset preset() {
        return preset;
    }

    /// Returns whether the plugin is selected for installation.
    ///
    /// @return whether it is selected
    public boolean isSelected() {
        return selected.get();
    }

    /// Returns the selection property.
    ///
    /// @return the selection property
    public BooleanProperty selectedProperty() {
        return selected;
    }

    /// Returns the chosen-version property.
    ///
    /// @return the chosen-version property
    public ObjectProperty<@Nullable String> chosenVersionProperty() {
        return chosenVersion;
    }

    @Override
    protected Skin<?> createDefaultSkin() {
        return new PluginCardSkin(this);
    }

    /// Renders the icon, name and status of one plugin card.
    private static final class PluginCardSkin extends SkinBase<PluginCard> {
        /// Creates the skin.
        ///
        /// @param control the card
        private PluginCardSkin(PluginCard control) {
            super(control);

            VBox pane = new VBox();
            pane.getStyleClass().add("installer-item");
            pane.pseudoClassStateChanged(PseudoClass.getPseudoClass("card"), true);

            SVGContainer icon = new SVGContainer(iconFor(control.preset()), 32);
            icon.setMouseTransparent(true);
            icon.getStyleClass().add("installer-item-image");
            VBox.setMargin(icon, new Insets(8, 0, 8, 0));
            pane.getChildren().add(icon);

            Label name = new Label(control.preset().name());
            name.getStyleClass().add("installer-item-name");
            name.setMouseTransparent(true);
            pane.getChildren().add(name);

            Label status = new Label();
            status.getStyleClass().add("installer-item-status");
            status.setMouseTransparent(true);
            status.textProperty().bind(Bindings.createStringBinding(
                    () -> {
                        String version = control.chosenVersion();
                        if (version == null) {
                            return i18n("dsh.install.plugin.not_installing");
                        }
                        return version.isEmpty() ? i18n("dsh.install.plugin.latest") : version;
                    },
                    control.chosenVersionProperty()));
            pane.getChildren().add(status);

            JFXButton arrow = new JFXButton();
            arrow.setGraphic(SVG.ARROW_FORWARD.createIcon());
            arrow.getStyleClass().add("toggle-icon4");
            // The card itself opens the chooser, so the arrow states that rather
            // than acting on its own.
            arrow.setMouseTransparent(true);
            pane.getChildren().add(arrow);

            StackPane wrapper = new StackPane();
            wrapper.getStyleClass().add("installer-item-wrapper");
            wrapper.pseudoClassStateChanged(PseudoClass.getPseudoClass("card"), true);
            wrapper.getChildren().add(new RipplerContainer(pane));

            getChildren().setAll(wrapper);
        }

        /// Chooses an icon for a catalogue entry.
        ///
        /// @param preset the entry
        /// @return the icon
        private static SVG iconFor(DshPreset preset) {
            return switch (preset.id()) {
                case "dshmarket" -> SVG.EXTENSION;
                case "better-sidebar", "skin-center" -> SVG.STYLE;
                case "context", "cost-meter" -> SVG.TUNE;
                case "git-graph" -> SVG.FOLDER_COPY;
                case "subagent-codex", "subagent-claude-code", "agent-team" -> SVG.ROCKET_LAUNCH;
                default -> SVG.EXTENSION_FILL;
            };
        }
    }
}
