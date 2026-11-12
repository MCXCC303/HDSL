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
package org.jackhuang.hmcl.ui.dsh.settings;

import com.mcxcc303.hdsl.core.DshFileManager;
import javafx.geometry.Insets;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.Image;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.Metadata;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.construct.ComponentList;
import org.jackhuang.hmcl.ui.construct.LineButton;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

@NotNullByDefault
public final class AboutPage extends ScrollPane {
	public AboutPage() {
		setFitToWidth(true);

		VBox root = new VBox(10);
		root.setPadding(new Insets(10));
		setContent(root);

		// Smooth scrolling binds to the content node, so it must run after the content is installed.
		FXUtils.smoothScrolling(this);

		ComponentList aboutList = new ComponentList();
		LineButton product = buildInfoRow(Metadata.FULL_NAME, Metadata.VERSION, "/assets/img/icon.png");
		product.setOnAction(event -> FXUtils.openLink(Metadata.HOMEPAGE_URL));
		aboutList.getContent().add(product);
		aboutList.getContent()
		         .add(buildInfoRow(i18n("dsh.about.runtime"),
		                           System.getProperty("java.vm.name") + " " + System.getProperty("java.version"),
		                           null));

		aboutList.getContent().addAll(buildPathsList().getContent());

		root.getChildren().addAll(ComponentList.createComponentListTitle(i18n("about")), aboutList);
	}

	private static LineButton buildInfoRow(String title, String subtitle, @Nullable String iconPath) {
		LineButton item = new LineButton();
		item.setLargeTitle(true);
		item.setTitle(title);
		item.setSubtitle(subtitle);
		if (iconPath != null) {
			item.setLeading(new Image(Objects.requireNonNull(AboutPage.class.getResource(iconPath)).toExternalForm()),
			                32);
		}
		return item;
	}

	private static ComponentList buildPathsList() {
		LineButton userHome = new LineButton();
		userHome.setLargeTitle(true);
		userHome.setLeading(SVG.FOLDER_OPEN, 24);
		userHome.setTitle(i18n("dsh.settings.home"));
		userHome.setSubtitle(Metadata.HDSL_USER_HOME.toString());
		userHome.setOnAction(event -> DshFileManager.openDirectory(Metadata.HDSL_USER_HOME));

		ComponentList list = new ComponentList();
		list.getContent().add(userHome);
		return list;
	}
}
