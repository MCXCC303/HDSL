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
import com.jfoenix.controls.JFXTextField;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.StringProperty;
import javafx.css.PseudoClass;
import javafx.scene.input.MouseEvent;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.SVG;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

/// A row that holds text, or holds the launcher's text.
///
/// The same shape as the inheritable choice rows, for values that are typed rather than picked: a
/// command that runs before an instance starts is either the launcher's or the instance's own, and
/// the globe beside the name is which. While it follows, the field shows what the launcher says and
/// does not accept typing — the value on screen is not this instance's to change — and taking the
/// setting over is what makes it editable.
///
/// The globe is put in the slot a row keeps beside its title, which is the one place on a row that
/// takes a press; the title line itself is mouse-transparent, so anything put there could never be
/// clicked.
///
/// The mark changes with the state, as it does on the choice rows: a globe while the launcher is
/// deciding, and a small pencil once this instance is. A mark that looked the same either way would
/// leave the row unable to report the press — while the row follows, the field shows the launcher's
/// text, so taking the setting over changes nothing on screen except the mark.
@NotNullByDefault
public class LineInheritableTextField extends LinePane {
    /// The size of the mark beside a row's name.
    private static final int INHERIT_ICON_SIZE = 12;

    /// How faint the mark is while this row is the one deciding.

    private static final double INHERIT_FAINT = 0.45;

    /// Whether this row has taken the setting over from the launcher.
    private final BooleanProperty overridden = new SimpleBooleanProperty(this, "overridden", false);

    /// The field the text is typed into.
    private final JFXTextField field = new JFXTextField();

    /// The mark beside the row's name, kept so its icon can follow the state.
    private final JFXButton inheritButton;

    /// Creates a row that follows the launcher until it is told otherwise.
    ///
    /// @param title the row's name
    public LineInheritableTextField(String title) {
        setTitle(title);
        field.setMinWidth(420);
        setRight(field);

        inheritButton = FXUtils.newToggleButton4(SVG.PUBLIC);
        // The helper's style is what colours it; its size is the row's, and the original's mark is
        // small — thirty pixels of globe beside a name is a control, not a mark.
        inheritButton.setGraphic(SVG.PUBLIC.createIcon(INHERIT_ICON_SIZE));
        inheritButton.setPrefSize(22, 22);
        inheritButton.setMaxSize(22, 22);
        inheritButton.setMinSize(javafx.scene.layout.Region.USE_PREF_SIZE,
                javafx.scene.layout.Region.USE_PREF_SIZE);
        inheritButton.addEventFilter(MouseEvent.MOUSE_CLICKED, event -> {
            setOverridden(!isOverridden());
            event.consume();
        });
        setTitleTrailing(inheritButton);

        overridden.addListener((observable, was, value) -> applyState());
        applyState();
    }

    /// Returns the property holding whether this row has taken the setting over.
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

    /// Returns the text this row holds.
    ///
    /// @return the property
    public StringProperty textProperty() {
        return field.textProperty();
    }

    /// Returns the text this row holds.
    ///
    /// @return the text
    public String getText() {
        return field.getText();
    }

    /// Sets the text this row holds.
    ///
    /// @param text the text
    public void setText(@Nullable String text) {
        field.setText(text == null ? "" : text);
    }

    /// Makes typing possible only when this row is the one choosing.
    private void applyState() {
        boolean overridden = isOverridden();
        field.setDisable(!overridden);
        // The dimming is what says the value belongs to the launcher: the mark says who decides,
        // and the field has to look like one showing somebody else's answer.
        field.setOpacity(overridden ? 1.0 : INHERIT_FAINT);
        // The mark says who is deciding, which is the one thing the field cannot say while the row
        // follows: it is showing the launcher's text either way.
        inheritButton.setGraphic((overridden ? SVG.EDIT : SVG.PUBLIC).createIcon(INHERIT_ICON_SIZE));
        FXUtils.installFastTooltip(inheritButton,
                i18n(overridden ? "dsh.settings.override.tooltip" : "dsh.settings.inherit.tooltip"));
        // The globe says the setting is the launcher's; the dimmed text says the same thing about
        // the value, which is what the original shows.
        pseudoClassStateChanged(PseudoClass.getPseudoClass("inherited"), !overridden);
    }
}
