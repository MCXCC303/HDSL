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
    /// Whether this row has taken the setting over from the launcher.
    private final BooleanProperty overridden = new SimpleBooleanProperty(this, "overridden", false);

    /// Creates a row that follows the launcher until it is told otherwise.
    public LineInheritableSelectButton() {
        // The launcher's own small icon button, so the globe is drawn in the theme's
        // colour like every other icon in a row: a bare SVG button takes the default
        // fill, which on these rows is a dark shape that looks like a smudge.
        JFXButton inheritButton = FXUtils.newToggleButton4(SVG.PUBLIC);
        FXUtils.installFastTooltip(inheritButton, i18n("dsh.settings.inherit"));
        inheritButton.setOnAction(event -> setOverridden(!isOverridden()));
        addTitleNode(inheritButton);

        overridden.addListener((observable, was, value) -> applyEditable());
        applyEditable();
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
        setDisable(!isOverridden());
    }
}
