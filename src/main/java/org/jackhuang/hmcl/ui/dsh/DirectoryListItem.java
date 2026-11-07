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

/**
 * Sidebar navigation item representing an instance directory.
 */
@NotNullByDefault
public final class DirectoryListItem extends RadioButton {
	private static final PseudoClass SELECTED = PseudoClass.getPseudoClass("selected");

	private final GameDirectory directory;
	private final Consumer<GameDirectory> onSelect;
	private final @Nullable Consumer<GameDirectory> onRemove;

	/**
	 * Creates a directory list item.
	 *
	 * @param directory directory to display
	 * @param onSelect  selection callback
	 * @param onRemove  optional removal callback
	 */
	public DirectoryListItem(GameDirectory directory,
	                         Consumer<GameDirectory> onSelect,
	                         @Nullable Consumer<GameDirectory> onRemove) {
		this.directory = directory;
		this.onSelect = onSelect;
		this.onRemove = onRemove;

		getStyleClass().setAll("game-directory-list-item", "navigation-drawer-item");
		selectedProperty().addListener((observable, was, active) -> pseudoClassStateChanged(SELECTED, active));
	}

	public GameDirectory getDirectory() {
		return directory;
	}

	@Override
	protected Skin<?> createDefaultSkin() {
		return new DirectoryListItemSkin(this);
	}

	private static final class DirectoryListItemSkin extends SkinBase<DirectoryListItem> {
		private final SVGContainer icon;

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
