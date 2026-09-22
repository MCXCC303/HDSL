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
import org.jackhuang.hmcl.ui.construct.LinePane;
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
                buildSourceList(),
                ComponentList.createComponentListTitle(i18n("dsh.settings.catalog")),
                buildCatalogList(),
                ComponentList.createComponentListTitle(i18n("dsh.settings.proxy")), buildProxyList(),
                ComponentList.createComponentListTitle(i18n("dsh.settings.env_vars")), buildGlobalEnvironmentList());
        root.getStyleClass().add("card-list");
        setContent(root);

        // After the content: smooth scrolling binds to it and throws on null.
        FXUtils.smoothScrolling(this);
    }

    /// Builds the download source section.
    ///
    /// @return the assembled component list
    /// Builds the row that says where the plugin catalogue is read from.
    ///
    /// The address is the one thing a network may need to change: the community catalogue
    /// lives on one host, and a network that cannot reach it can point this at a mirror.
    ///
    /// @return the list
    /// Builds the rows for the network the launcher downloads through.
    ///
    /// A package manager is configured through the environment it inherits, so these travel with
    /// every child the launcher starts: one proxy covers the version lists and the plugin
    /// installs, and nothing has to be configured twice.
    ///
    /// @return the list
    /// Builds the editor for the variables every instance runs with.
    ///
    /// One per line as `NAME=VALUE`, which is what people paste into a box. A launcher-wide set is
    /// for the things that are the same everywhere, and an instance adds its own on top rather than
    /// replacing them — which is what an API key per instance needs.
    ///
    /// @return the list
    private ComponentList buildGlobalEnvironmentList() {
        javafx.scene.control.TextArea area = new javafx.scene.control.TextArea();
        area.setPrefRowCount(4);
        area.setPrefColumnCount(28);
        area.setText(org.jackhuang.hmcl.dsh.DshEnvironment.format(settings().globalEnvironment()));
        area.textProperty().addListener((observable, was, text) ->
                settings().globalEnvironmentProperty().set(org.jackhuang.hmcl.dsh.DshEnvironment.parse(text)));

        // The same row as everything else on the page: the name and what it is for on the left, the
        // box beside them. A line of explanation floating above a bare box is not a row, and the
        // reference has no such thing.
        ComponentList list = new ComponentList();
        list.getContent().add(proxyRowWithField(i18n("dsh.settings.env_vars"),
                i18n("dsh.settings.env_vars.global.hint"), area));
        return list;
    }

    private ComponentList buildProxyList() {
        ComponentList list = new ComponentList();
        list.getContent().add(proxyRow(i18n("dsh.settings.proxy.http"),
                i18n("dsh.settings.proxy.hint"), settings().httpProxyProperty()));
        list.getContent().add(proxyRow(i18n("dsh.settings.proxy.https"),
                i18n("dsh.settings.proxy.hint"), settings().httpsProxyProperty()));
        list.getContent().add(proxyRow(i18n("dsh.settings.proxy.none"),
                i18n("dsh.settings.proxy.none.hint"), settings().noProxyProperty()));

        com.jfoenix.controls.JFXTextField concurrency = new com.jfoenix.controls.JFXTextField();
        concurrency.setPromptText(i18n("dsh.settings.proxy.concurrency.hint"));
        concurrency.setText(settings().downloadConcurrencyProperty().get() == null ? ""
                : settings().downloadConcurrencyProperty().get().toString());
        concurrency.textProperty().addListener((observable, was, text) -> {
            String value = text == null ? "" : text.trim();
            if (value.isEmpty()) {
                settings().downloadConcurrencyProperty().set(null);
                return;
            }
            try {
                settings().downloadConcurrencyProperty().set(Integer.valueOf(value));
            } catch (NumberFormatException e) {
                // Half-typed numbers are not settings: the field keeps what was typed and the
                // setting keeps what it had.
            }
        });
        list.getContent().add(proxyRowWithField(i18n("dsh.settings.proxy.concurrency"),
                i18n("dsh.settings.proxy.concurrency.hint"), concurrency));
        return list;
    }

    /// Builds one proxy row.
    ///
    /// @param title    the row's name
    /// @param hint     what it is for
    /// @param property what is typed into it
    /// @return the row
    private javafx.scene.Node proxyRow(String title, String hint,
                                       javafx.beans.property.StringProperty property) {
        com.jfoenix.controls.JFXTextField field = new com.jfoenix.controls.JFXTextField();
        field.textProperty().bindBidirectional(property);
        return proxyRowWithField(title, hint, field);
    }

    /// Builds one row of a name, what it is for, and the field beside them.
    ///
    /// The shape the reference shows for a row with something to type: the name on the left with a
    /// line under it saying what it is for, and the box beside them. A name, a paragraph and a box
    /// stacked underneath is three lines doing what one row does everywhere else.
    ///
    /// @param title the row's name
    /// @param hint  what it is for
    /// @param field what is typed into it
    /// @return the row
    private javafx.scene.Node proxyRowWithField(String title, String hint, javafx.scene.Node field) {
        LinePane pane = new LinePane();
        pane.setTitle(title);
        pane.setSubtitle(hint);
        pane.setRight(field);
        return pane;
    }
    private ComponentList buildCatalogList() {
        com.jfoenix.controls.JFXTextField field = new com.jfoenix.controls.JFXTextField();
        field.setPromptText(org.jackhuang.hmcl.dsh.DshPluginCatalog.CATALOG_URL);
        field.setMinWidth(320);
        field.textProperty().bindBidirectional(settings().pluginCatalogUrlProperty());

        ComponentList list = new ComponentList();
        list.getContent().add(proxyRowWithField(i18n("dsh.settings.catalog.url"),
                i18n("dsh.settings.catalog.url.hint"), field));
        return list;
    }

    /// Builds the source row.
    ///
    /// @return the list
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
