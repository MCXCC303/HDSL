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

    /// The folder icon, which fills in when the row is selected.
    private final SVGContainer icon;

    /// The name and path.
    private final TwoLineListItem content = new TwoLineListItem();

    /// Creates a row.
    ///
    /// @param directory the folder to show
    /// @param onSelect  run when the row is clicked
    /// @param onRemove  run when the remove button is pressed
    public DirectoryListItem(GameDirectory directory,
                             Consumer<GameDirectory> onSelect,
                             @Nullable Consumer<GameDirectory> onRemove) {
        getStyleClass().setAll("game-directory-list-item", "navigation-drawer-item");

        BorderPane root = new BorderPane();
        root.setPickOnBounds(false);

        SVGContainer left = new SVGContainer(SVG.FOLDER, 20);
        left.setMouseTransparent(true);
        BorderPane.setMargin(left, new Insets(0, 6, 0, 6));
        BorderPane.setAlignment(left, Pos.CENTER_LEFT);
        root.setLeft(left);
        this.icon = left;

        content.setPickOnBounds(false);
        BorderPane.setAlignment(content, Pos.CENTER);
        content.setTitle(directory.displayName());
        content.setSubtitle(directory.path());
        root.setCenter(content);

        HBox right = new HBox();
        right.setAlignment(Pos.CENTER_RIGHT);
        if (onRemove != null) {
            JFXButton remove = FXUtils.newToggleButton4(SVG.CLOSE, 14);
            remove.setOnAction(event -> onRemove.accept(directory));
            BorderPane.setAlignment(remove, Pos.CENTER);
            right.getChildren().add(remove);
        }
        root.setRight(right);

        FXUtils.onClicked(this, () -> onSelect.accept(directory));

        FXUtils.onChangeAndOperate(selectedProperty(), active -> {
            pseudoClassStateChanged(SELECTED, active);
            SVG target = active ? SVG.FOLDER_FILL : SVG.FOLDER;
            if (icon.getIcon() != target) {
                icon.setIcon(target);
            }
        });

        getChildren().setAll(new RipplerContainer(root));
    }
}
