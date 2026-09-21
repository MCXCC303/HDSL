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

import javafx.scene.control.ScrollPane;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.dsh.NodeSource;
import org.jackhuang.hmcl.setting.SettingsManager;
import org.jackhuang.hmcl.util.i18n.I18n;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.construct.ComponentList;
import org.jackhuang.hmcl.ui.construct.LineSelectButton;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.function.Function;

import static org.jackhuang.hmcl.setting.SettingsManager.settings;
import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

/// Where the launcher fetches what it downloads.
///
/// The original keeps its download sources on a page of their own under the
/// launcher's settings, apart from the settings that describe the game or the
/// runtimes: a source is neither, it is how the launcher reaches the network.
/// One source is offered here because one download needs one — the Node
/// distribution — and the plugins and DeepSeek Harness itself come from npm,
/// which the user points at a registry of their choosing.
///
/// The row is the original's shape: the chosen option's name is what the row
/// shows, and each option carries a line saying what it is, shown in the list the
/// row opens rather than in the row itself.
@NotNullByDefault
public final class DownloadSettingsPage extends ScrollPane {
    /// Creates the page.
    public DownloadSettingsPage() {
        setFitToWidth(true);
        setFitToHeight(true);

        VBox root = new VBox(
                ComponentList.createComponentListTitle(i18n("settings.launcher.download_source")),
                buildSourceList());
        root.getStyleClass().add("card-list");
        setContent(root);

        // After the content: smooth scrolling binds to it and throws on null.
        FXUtils.smoothScrolling(this);
    }

    /// Builds the download source section.
    ///
    /// @return the assembled component list
    private ComponentList buildSourceList() {
        ComponentList list = new ComponentList();

        LineSelectButton<NodeSource> source = new LineSelectButton<>();
        source.setTitle(i18n("settings.launcher.download_source"));
        source.setNullSafeConverter(sourceName());
        source.setDescriptionConverter(sourceDescription());
        source.setItems(NodeSource.values());
        source.setValue(settings().nodeSourceProperty().get());
        source.valueProperty().addListener((observable, was, value) -> {
            if (value != null && value != was) {
                settings().nodeSourceProperty().set(value);
                SettingsManager.save();
            }
        });
        list.getContent().add(source);

        return list;
    }

    /// Returns the labels the source row shows for its options.
    ///
    /// The original's own wording, because the choice is the same choice: a
    /// mirror is the one to take where the publisher cannot be reached.
    ///
    /// @return the converter
    private static Function<NodeSource, String> sourceName() {
        return source -> switch (source) {
            case OFFICIAL -> i18n("download.provider.official");
            case MIRROR -> i18n("download.provider.mirror");
        };
    }

    /// Returns the line each option carries in the list the row opens.
    ///
    /// @return the converter
    private static Function<NodeSource, String> sourceDescription() {
        return source -> {
            String key = switch (source) {
                case OFFICIAL -> "download.provider.official.desc";
                case MIRROR -> "download.provider.mirror.desc";
            };
            return I18n.hasKey(key) ? i18n(key) : null;
        };
    }
}
