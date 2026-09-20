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
package org.jackhuang.hmcl.ui.dsh.settings;

import javafx.geometry.Insets;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.Metadata;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.construct.ComponentList;
import org.jackhuang.hmcl.ui.construct.LineButton;
import org.jackhuang.hmcl.ui.construct.TwoLineListItem;
import org.jetbrains.annotations.NotNullByDefault;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

/// The "about" tab of the launcher settings page.
///
/// Reports the product identity, the running Java runtime and where HMCL-DSH
/// keeps its data. Attribution for the upstream HMCL project lives in the
/// repository `NOTICE` file rather than in the UI.
@NotNullByDefault
public final class AboutPage extends ScrollPane {
    /// Creates the about tab.
    public AboutPage() {
        setFitToWidth(true);

        VBox root = new VBox(10);
        root.setPadding(new Insets(10));
        setContent(root);

        // Must run after the content is installed: smooth scrolling binds to
        // the content node and fails on a null content.
        FXUtils.smoothScrolling(this);

        TwoLineListItem identity = new TwoLineListItem();
        identity.setTitle(Metadata.FULL_NAME);
        identity.setSubtitle(Metadata.VERSION);

        ComponentList aboutList = new ComponentList();
        aboutList.getContent().add(identity);
        aboutList.getContent().add(buildInfoRow(i18n("dsh.about.runtime"),
                System.getProperty("java.vm.name") + " " + System.getProperty("java.version")));

        root.getChildren().addAll(aboutList, buildPathsList());
    }

    /// Builds a read-only information row.
    ///
    /// @param title    the row label
    /// @param subtitle the value to display
    /// @return the row
    private static TwoLineListItem buildInfoRow(String title, String subtitle) {
        TwoLineListItem item = new TwoLineListItem();
        item.setTitle(title);
        item.setSubtitle(subtitle);
        return item;
    }

    /// Builds the list of directories HMCL-DSH owns.
    ///
    /// @return the assembled component list
    private static ComponentList buildPathsList() {
        LineButton userHome = new LineButton();
        userHome.setTitle(i18n("dsh.settings.home"));
        userHome.setSubtitle(Metadata.HMCL_USER_HOME.toString());
        userHome.setOnAction(event -> FXUtils.showFileInExplorer(Metadata.HMCL_USER_HOME));

        ComponentList list = new ComponentList();
        list.getContent().add(userHome);
        return list;
    }
}
