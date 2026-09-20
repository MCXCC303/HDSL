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

import javafx.geometry.Insets;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.dsh.DshException;
import org.jackhuang.hmcl.dsh.DshInstance;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.construct.ComponentList;
import org.jackhuang.hmcl.ui.construct.LineButton;
import org.jackhuang.hmcl.ui.construct.LineTextPane;
import org.jetbrains.annotations.NotNullByDefault;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Lists the directories that belong to an instance and opens them.
///
/// This is the counterpart of HMCL's browse page for an instance: a place to
/// reach the files a launcher manages without hunting for them, and the only
/// route to them for a user who does not know how a DeepSeek Harness home is
/// laid out.
///
/// A directory that does not exist yet is shown disabled rather than hidden, so
/// the layout is learnable before anything has been created in it.
@NotNullByDefault
public final class BrowsePane extends ScrollPane {
    /// Creates the pane for an instance.
    ///
    /// @param instance the instance whose directories are listed
    public BrowsePane(DshInstance instance) {
        setFitToWidth(true);

        ComponentList list = new ComponentList();
        for (Directory directory : directoriesOf(instance)) {
            list.getContent().add(buildRow(directory));
        }

        VBox root = new VBox(ComponentList.createComponentListTitle(i18n("dsh.instance.folders")), list);
        root.setPadding(new Insets(10));
        setContent(root);

        // Must run after the content is installed: smooth scrolling binds to the
        // content node and throws on a null content.
        FXUtils.smoothScrolling(this);
    }

    /// Collects the directories an instance owns.
    ///
    /// @param instance the instance
    /// @return the directories, in the order they are shown
    private static List<Directory> directoriesOf(DshInstance instance) {
        List<Directory> directories = new ArrayList<>();
        directories.add(new Directory(i18n("dsh.instance.open_workspace"),
                instance.workspacePath(), null));
        directories.add(new Directory(i18n("dsh.instance.open_home"),
                safeHome(instance), null));

        Path home = safeHome(instance);
        if (home != null) {
            directories.add(new Directory(i18n("dsh.instance.open.sessions"),
                    home.resolve("sessions"), null));
            directories.add(new Directory(i18n("dsh.instance.open.profiles"),
                    home.resolve("profiles"), null));
            directories.add(new Directory(i18n("dsh.instance.open.storages"),
                    home.resolve("storages"), null));
        }
        return directories;
    }

    /// Resolves an instance's home without failing the whole page.
    ///
    /// @param instance the instance
    /// @return the home, or `null` when it cannot be resolved
    private static Path safeHome(DshInstance instance) {
        try {
            return instance.homeDirectory();
        } catch (DshException e) {
            LOG.warning("The instance home could not be resolved", e);
            return null;
        }
    }

    /// Builds one directory row.
    ///
    /// @param directory the directory
    /// @return the row
    private static LineButton buildRow(Directory directory) {
        LineButton row = new LineButton();
        row.setTitle(directory.title());
        if (directory.path() == null) {
            row.setSubtitle(i18n("dsh.instance.open.unavailable"));
            row.setDisable(true);
            return row;
        }

        row.setSubtitle(directory.path().toString());
        row.setDisable(!Files.isDirectory(directory.path()));
        row.setOnAction(event -> FXUtils.showFileInExplorer(directory.path()));
        return row;
    }

    /// One directory the browse page offers.
    ///
    /// @param title the row label
    /// @param path  the directory, or `null` when it cannot be resolved
    /// @param note  an optional note shown with the path
    private record Directory(String title, Path path, String note) {
    }
}
