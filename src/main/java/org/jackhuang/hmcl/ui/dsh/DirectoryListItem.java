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
import javafx.css.PseudoClass;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.RadioButton;
import javafx.scene.control.Skin;
import javafx.scene.control.SkinBase;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import org.jackhuang.hmcl.setting.GameDirectory;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.SVGContainer;
import org.jackhuang.hmcl.ui.construct.RipplerContainer;
import org.jackhuang.hmcl.ui.construct.TwoLineListItem;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;

/// One folder in the instance list's sidebar.
///
/// The node structure and style classes are the original's, because its
/// stylesheet is the one this project ships and its rules select this shape
/// exactly:
///
/// ```
/// .game-directory-list-item > .rippler-container > BorderPane { padding: 8 0 8 16 }
/// ... > .two-line-list-item > .first-line > .title { font-size: 13 }
/// ... > .two-line-list-item > HBox > .subtitle { font-size: 10 }
/// ```
///
/// A row built any other way gets none of that — a plain list item carries the
/// box's default sizes, which is a difference of a few pixels in two places and
/// therefore one that looks almost right.
@NotNullByDefault
public final class DirectoryListItem extends RadioButton {
    /// Marks the row whose folder the list is showing.
    private static final PseudoClass SELECTED = PseudoClass.getPseudoClass("selected");

    /// The folder being shown.
    private final GameDirectory directory;

    /// Run when the row is clicked.
    private final Consumer<GameDirectory> onSelect;

    /// Run when the remove button is pressed, or `null` when there is none.
    private final @Nullable Consumer<GameDirectory> onRemove;

    /// Creates a row.
    ///
    /// @param directory the folder to show
    /// @param onSelect  run when the row is clicked
    /// @param onRemove  run when the remove button is pressed, or `null`
    public DirectoryListItem(GameDirectory directory,
                             Consumer<GameDirectory> onSelect,
                             @Nullable Consumer<GameDirectory> onRemove) {
        this.directory = directory;
        this.onSelect = onSelect;
        this.onRemove = onRemove;

        getStyleClass().setAll("game-directory-list-item", "navigation-drawer-item");

        // The row's selection is the launcher's, not the button group's: two
        // rows are never both selected, but which one is comes from the settings
        // rather than from having been clicked.
        selectedProperty().addListener((observable, was, active) -> pseudoClassStateChanged(SELECTED, active));
    }

    /// Returns the folder this row shows.
    ///
    /// @return the folder
    public GameDirectory getDirectory() {
        return directory;
    }

    @Override
    protected Skin<?> createDefaultSkin() {
        return new DirectoryListItemSkin(this);
    }

    /// Lays a row out.
    ///
    /// The layout belongs to a skin rather than to the control: a RadioButton's
    /// own skin renders it, and setting the control's children directly fights
    /// that — the row ends up with no height and nothing appears. The original
    /// splits them the same way.
    private static final class DirectoryListItemSkin extends SkinBase<DirectoryListItem> {
        /// The folder icon, which fills in when the row is selected.
        private final SVGContainer icon;

        /// Creates the skin.
        ///
        /// @param control the row
        DirectoryListItemSkin(DirectoryListItem control) {
            super(control);

            BorderPane root = new BorderPane();
            root.setPickOnBounds(false);

            this.icon = new SVGContainer(SVG.FOLDER, 20);
            icon.setMouseTransparent(true);
            BorderPane.setMargin(icon, new Insets(0, 6, 0, 6));
            BorderPane.setAlignment(icon, Pos.CENTER_LEFT);
            root.setLeft(icon);

            TwoLineListItem content = new TwoLineListItem();
            content.setPickOnBounds(false);
            BorderPane.setAlignment(content, Pos.CENTER);
            content.setTitle(control.getDirectory().displayName());
            content.setSubtitle(control.getDirectory().path());
            root.setCenter(content);

            HBox right = new HBox();
            right.setAlignment(Pos.CENTER_RIGHT);
            if (control.onRemove != null) {
                JFXButton remove = FXUtils.newToggleButton4(SVG.CLOSE, 14);
                remove.setOnAction(event -> control.onRemove.accept(control.getDirectory()));
                BorderPane.setAlignment(remove, Pos.CENTER);
                right.getChildren().add(remove);
            }
            root.setRight(right);

            FXUtils.onClicked(control, () -> control.onSelect.accept(control.getDirectory()));

            FXUtils.onChangeAndOperate(control.selectedProperty(), active -> {
                SVG target = active ? SVG.FOLDER_FILL : SVG.FOLDER;
                if (icon.getIcon() != target) {
                    icon.setIcon(target);
                }
            });

            getChildren().setAll(new RipplerContainer(root));
        }
    }
}
