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
package org.jackhuang.hmcl.ui.construct;

import com.jfoenix.controls.JFXButton;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.SVG;
import org.jetbrains.annotations.NotNullByDefault;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

/// A row that chooses a value, or follows the launcher's choice of it.
///
/// The original's shape for a setting an instance may take over: the value is shown either
/// way, and the globe beside its name says whether this instance chose it or is following
/// the launcher. A row that follows is not editable — the value shown is the launcher's, so
/// changing it here would be changing nothing — and clicking the globe takes it over, which
/// is what makes it editable.
///
/// The globe is the same one the original uses, at the same size, and it is put beside the
/// title because that is where a row says what it is: the value belongs at the row's end,
/// and who chose it belongs next to the name.
///
/// @param <T> the type of value the row chooses
@NotNullByDefault
public class LineInheritableSelectButton<T extends @org.jetbrains.annotations.UnknownNullability Object>
        extends LineSelectButton<T> {
    /// The size of the mark beside a row's name.
    private static final int INHERIT_ICON_SIZE = 12;

    /// The mark shown once a row has taken the setting over, when the icon set has one.
    private static final SVG MANUAL_ICON = SVG.EDIT;

    /// How faint the mark is while the launcher is the one deciding.
    private static final double INHERIT_FAINT = 0.45;

    /// Whether this row has taken the setting over from the launcher.
    private final BooleanProperty overridden = new SimpleBooleanProperty(this, "overridden", false);

    /// Creates a row that follows the launcher until it is told otherwise.
    public LineInheritableSelectButton() {
        // The launcher's own small icon button, so the globe is drawn in the theme's
        // colour like every other icon in a row: a bare SVG button takes the default
        // fill, which on these rows is a dark shape that looks like a smudge.
        // A class of this control's own, so the stylesheet can key on it without knowing what
        // the classes it inherits call themselves.
        getStyleClass().add("line-inheritable-value");

        JFXButton inheritButton = FXUtils.newToggleButton4(SVG.PUBLIC);
        // The helper's style is what colours it; its size is the row's, and the original's mark is
        // small — thirty pixels of globe beside a name is a control, not a mark.
        inheritButton.setGraphic(SVG.PUBLIC.createIcon(INHERIT_ICON_SIZE));
        inheritButton.setPrefSize(22, 22);
        inheritButton.setMaxSize(22, 22);
        inheritButton.setMinSize(javafx.scene.layout.Region.USE_PREF_SIZE,
                javafx.scene.layout.Region.USE_PREF_SIZE);
        FXUtils.installFastTooltip(inheritButton, i18n("dsh.settings.inherit"));
        inheritButton.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_CLICKED, event -> {
            setOverridden(!isOverridden());
            event.consume();
        });
        setTitleTrailing(inheritButton);
        // The row's own skeleton marks parts of itself mouse-transparent so that a press goes to the
        // row rather than to its content; the globe is inside that content, and has to be reachable
        // for the setting to have a way back.
        makeClickable(inheritButton);

        // Following the launcher is not a choice of this row's, so the value is not editable while
        // it does — but the row itself stays enabled, because the globe on it is the way in, and a
        // disabled row disables what is on it.
        Runnable showState = () -> {
            boolean overridden = isOverridden();
            inheritButton.setOpacity(overridden ? INHERIT_FAINT : 1.0);
            inheritButton.setGraphic((overridden ? MANUAL_ICON : SVG.PUBLIC).createIcon(INHERIT_ICON_SIZE));
            FXUtils.installFastTooltip(inheritButton,
                    i18n(overridden ? "dsh.settings.override.tooltip" : "dsh.settings.inherit.tooltip"));
        };
        overridden.addListener((observable, was, value) -> {
            showState.run();
            applyEditable();
        });
        showState.run();
        applyEditable();
    }

    /// Clears mouse-transparency from a node up to this row.
    ///
    /// @param node the node to make reachable
    private void makeClickable(javafx.scene.Node node) {
        javafx.scene.Node current = node;
        while (current != null && current != this) {
            current.setMouseTransparent(false);
            current = current.getParent();
        }
    }

    @Override
    public void fire() {
        // Pressing the row is taking the setting over — the original's rows behave this way, and
        // it means there is no state in which a press writes a value without saying whose it is.
        setOverridden(true);
        super.fire();
    }

    /// Returns whether this row has taken the setting over.
    ///
    /// @return the property
    public BooleanProperty overriddenProperty() {
        return overridden;
    }

    /// Returns whether this row has taken the setting over.
    ///
    /// @return whether it has
    public boolean isOverridden() {
        return overridden.get();
    }

    /// Sets whether this row has taken the setting over.
    ///
    /// @param value whether it has
    public void setOverridden(boolean value) {
        overridden.set(value);
    }

    /// Makes the row editable only when it is the one choosing.
    ///
    /// Following the launcher is not a choice of this row's, so the row does not offer to
    /// change it: the globe is the way in, and the value is read-only until it is pressed.
    private void applyEditable() {
        // Both, deliberately: the stylesheet rule says it for a row that a theme styles the way
        // this one expects, and this says it whatever the stylesheet does — because a setting that
        // is following the launcher has to look like one.
        javafx.application.Platform.runLater(() -> {
            javafx.scene.Node value = lookup(".trailing-label");
            if (value != null) {
                value.setOpacity(isOverridden() ? 1.0 : 0.55);
            }
        });
        // What says the value is the launcher's is the globe, not a greyed-out row: the row stays
        // usable so the globe can be pressed, and its action is consumed while it follows.
        pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("inherited"), !isOverridden());
    }
}
