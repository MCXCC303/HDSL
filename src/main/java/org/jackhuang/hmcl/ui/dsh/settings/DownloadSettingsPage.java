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

import javafx.geometry.Pos;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.dsh.NodeSource;
import org.jackhuang.hmcl.setting.SettingsManager;
import org.jackhuang.hmcl.util.i18n.I18n;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.construct.ComponentList;
import org.jackhuang.hmcl.ui.construct.RadioChoiceList;
import org.jackhuang.hmcl.ui.construct.ComponentSublist;
import org.jackhuang.hmcl.ui.construct.LinePane;
import org.jackhuang.hmcl.ui.construct.MultiFileItem;
import org.jackhuang.hmcl.ui.construct.LineSelectButton;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.function.Function;

import static org.jackhuang.hmcl.setting.SettingsManager.settings;
import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

/// Where the launcher fetches what it downloads.
///
/// The original keeps its download sources on a page of their own under the
/// launcher's settings, apart from the settings that describe the game or the
/// runtimes: a source is neither, it is how the launcher reaches the network. The
/// same split is kept here, and the rows inside the section are the original's own:
/// every source the launcher can be pointed at, one row each, under the one heading.
///
/// Two sources exist because two downloads do not come from npm: Node's
/// distribution comes from the Node.js project or a mirror of it, and the plugin
/// catalogue is one document on one host. DeepSeek Harness and its plugins come
/// from npm, which the user points at a registry of their choosing.
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
                ComponentList.createComponentListTitle(i18n("settings.launcher.download")), buildDownloadList(),
                ComponentList.createComponentListTitle(i18n("dsh.settings.proxy")), buildProxyList());
        root.getStyleClass().add("card-list");
        setContent(root);

        // After the content: smooth scrolling binds to it and throws on null.
        FXUtils.smoothScrolling(this);
    }

    /// Builds the download source section.
    ///
    /// One card holding every source the launcher reads, which is the original's
    /// arrangement: its own download-source card holds the version-list source, the
    /// file source and the default add-on source as three rows of one surface.
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
        list.getContent().add(buildCatalogRow());

        return list;
    }

    /// Builds the row that says where the plugin catalogue is read from.
    ///
    /// The address is the one thing a network may need to change: the community catalogue
    /// lives on one host, and a network that cannot reach it can point this at a mirror.
    /// It is a source like the one above it — where something is fetched from — so it sits
    /// in the same card rather than a section of its own.
    ///
    /// @return the row
    private javafx.scene.Node buildCatalogRow() {
        com.jfoenix.controls.JFXTextField field = new com.jfoenix.controls.JFXTextField();
        field.setPromptText(org.jackhuang.hmcl.dsh.DshPluginCatalog.CATALOG_URL);
        field.setMinWidth(320);
        field.textProperty().bindBidirectional(settings().pluginCatalogUrlProperty());

        return proxyRowWithField(i18n("dsh.settings.catalog.url"), null, field);
    }

    /// Builds the rows about downloading itself: where what was fetched is kept, and how many
    /// downloads happen at once.
    ///
    /// @return the list
    private ComponentList buildDownloadList() {
        // The original's rows for the same job: where the cache lives — shown, with a button that
        // empties it and a way to choose another place — and how many downloads happen at once,
        // which is a choice rather than a number to type.
        // The original's cache row opens where it stands and offers the launcher's own folder or one
        // of your own, with a picker for it, and keeps the button that empties it on the row itself.
        ComponentSublist cache = new ComponentSublist(() -> {
            MultiFileItem<Boolean> location = new MultiFileItem<>();
            location.loadChildren(java.util.List.of(
                    new MultiFileItem.Option<>(i18n("dsh.settings.download.cache.default"), true),
                    new MultiFileItem.FileOption<>(i18n("dsh.settings.download.cache.custom"), false)
                            .setChooserTitle(i18n("dsh.settings.download.cache.choose"))
                            .setSelectionMode(org.jackhuang.hmcl.ui.construct.FileSelector.SelectionMode.DIRECTORY)
                            .bindBidirectional(settings().cacheDirectoryProperty())));
            location.selectedDataProperty().bindBidirectional(settings().cacheDirectoryCustomProperty());
            return java.util.List.of(location);
        });
        cache.setTitle(i18n("dsh.settings.download.cache"));
        cache.setHasSubtitle(true);
        cache.descriptionProperty().bind(javafx.beans.binding.Bindings.createStringBinding(
                () -> org.jackhuang.hmcl.dsh.DshPluginCatalog.cacheDirectory().toString(),
                settings().cacheDirectoryCustomProperty(), settings().cacheDirectoryProperty()));

        com.jfoenix.controls.JFXButton clear = new com.jfoenix.controls.JFXButton(
                i18n("dsh.settings.download.cache.clear"));
        clear.getStyleClass().add("jfx-button-border");
        clear.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        clear.setOnAction(event -> {
            // Says what it did, because a button that quietly removes nothing looks broken.
            int removed = org.jackhuang.hmcl.dsh.DshPluginCatalog.clearCache();
            clear.setText(i18n("dsh.settings.download.cache.cleared", removed));
        });
        cache.setHeaderRight(clear);

        // Automatic is the absence of a number, and the row says so rather than showing nothing.
        // The original's row for this: a row that opens where it stands, offering the automatic
        // count or one of your own, with a slider for it. A popup would be a different gesture for
        // the same question, and this is the one the original uses.
        ComponentSublist threads = new ComponentSublist(() -> {
            RadioChoiceList<Boolean> choices = new RadioChoiceList<>();
            choices.setChoices(
                    new RadioChoiceList.Choice<>(i18n("dsh.settings.download.threads.auto"), true),
                    new RadioChoiceList.Choice<>(i18n("dsh.settings.download.threads.custom"), false) {
                        @Override
                        protected javafx.scene.Node createRightNode() {
                            javafx.scene.layout.HBox box = new javafx.scene.layout.HBox(8);
                            box.setAlignment(javafx.geometry.Pos.CENTER);
                            box.disableProperty().bind(settings().autoDownloadThreadsProperty());

                            com.jfoenix.controls.JFXSlider slider = new com.jfoenix.controls.JFXSlider(1, 256, 64);
                            javafx.scene.layout.HBox.setHgrow(slider, javafx.scene.layout.Priority.ALWAYS);
                            com.jfoenix.controls.JFXTextField field = new com.jfoenix.controls.JFXTextField();
                            org.jackhuang.hmcl.ui.FXUtils.setLimitWidth(field, 60);

                            Integer current = settings().downloadConcurrencyProperty().get();
                            int value = current == null ? 64 : current;
                            slider.setValue(value);
                            field.setText(Integer.toString(value));
                            slider.valueProperty().addListener((observable, was, now) -> {
                                settings().downloadConcurrencyProperty().set(now.intValue());
                                field.setText(Integer.toString(now.intValue()));
                            });
                            field.textProperty().addListener((observable, was, text) -> {
                                try {
                                    int typed = Integer.parseInt(text == null ? "" : text.trim());
                                    if (typed > 0) {
                                        settings().downloadConcurrencyProperty().set(typed);
                                        slider.setValue(typed);
                                    }
                                } catch (NumberFormatException e) {
                                    // Half-typed numbers are not settings.
                                }
                            });

                            box.getChildren().setAll(slider, field);
                            return box;
                        }
                    });
            choices.selectedValueProperty().bindBidirectional(settings().autoDownloadThreadsProperty());
            return java.util.List.of(choices);
        });
        threads.setTitle(i18n("dsh.settings.download.threads"));
        threads.setHasSubtitle(true);
        threads.descriptionProperty().bind(javafx.beans.binding.Bindings.createStringBinding(() -> {
            if (settings().autoDownloadThreadsProperty().get()) {
                return i18n("dsh.settings.download.threads.auto");
            }
            Integer count = settings().downloadConcurrencyProperty().get();
            return Integer.toString(count == null ? 64 : count);
        }, settings().autoDownloadThreadsProperty(), settings().downloadConcurrencyProperty()));

        ComponentList list = new ComponentList();
        list.getContent().add(cache);
        list.getContent().add(threads);
        return list;
    }

    /// Builds the rows for the network the launcher downloads through.
    ///
    /// A package manager is configured through the environment it inherits, so these travel with
    /// every child the launcher starts: one proxy covers the version lists and the plugin
    /// installs, and nothing has to be configured twice.
    ///
    /// The original's own arrangement, which is not a list of rows: one card holding the four
    /// modes on a line of their own and, under them, a small form. Which parts of the form mean
    /// anything is decided by the mode — a proxy that is not used has no address — so the whole
    /// form is switched off together rather than field by field, and the name and password are
    /// switched off separately because they are asked for on top of an address rather than
    /// instead of one.
    ///
    /// The fields are laid out in a grid with the names in the first column, which is what the
    /// original uses: a card of `LinePane` rows would draw a separator between the host and the
    /// port, and the original's proxy form has none.
    ///
    /// @return the section
    private javafx.scene.layout.VBox buildProxyList() {
        javafx.scene.layout.VBox proxyList = new javafx.scene.layout.VBox(10);
        proxyList.getStyleClass().add("card-non-transparent");

        javafx.scene.layout.HBox proxyTypePane = new javafx.scene.layout.HBox();
        proxyTypePane.setAlignment(Pos.CENTER_LEFT);
        proxyTypePane.setPadding(new javafx.geometry.Insets(10, 0, 0, 0));

        javafx.scene.control.ToggleGroup group = new javafx.scene.control.ToggleGroup();
        java.util.Map<org.jackhuang.hmcl.dsh.DshProxyMode, com.jfoenix.controls.JFXRadioButton> buttons =
                new java.util.LinkedHashMap<>();
        for (org.jackhuang.hmcl.dsh.DshProxyMode mode : org.jackhuang.hmcl.dsh.DshProxyMode.values()) {
            // The original's own control and its own wording, so the line reads the same.
            com.jfoenix.controls.JFXRadioButton button =
                    new com.jfoenix.controls.JFXRadioButton(i18n(proxyModeKey(mode)));
            button.setToggleGroup(group);
            button.setUserData(mode);
            buttons.put(mode, button);
            proxyTypePane.getChildren().add(button);
        }
        buttons.get(currentProxyMode()).setSelected(true);
        group.selectedToggleProperty().addListener((observable, was, now) -> {
            settings().proxyModeProperty().set(now != null
                    && now.getUserData() instanceof org.jackhuang.hmcl.dsh.DshProxyMode mode
                    ? mode : org.jackhuang.hmcl.dsh.DshProxyMode.SYSTEM);
        });
        proxyList.getChildren().add(proxyTypePane);

        javafx.scene.layout.VBox proxyPane = new javafx.scene.layout.VBox();
        // Nothing in the form means anything while the mode says the proxy is not used.
        proxyPane.disableProperty().bind(javafx.beans.binding.Bindings.createBooleanBinding(
                () -> !currentProxyMode().usesAddress(), settings().proxyModeProperty()));

        com.jfoenix.controls.JFXTextField host = new com.jfoenix.controls.JFXTextField();
        host.textProperty().bindBidirectional(settings().proxyHostProperty());

        com.jfoenix.controls.JFXTextField port = new com.jfoenix.controls.JFXTextField();
        FXUtils.setLimitWidth(port, 200);
        FXUtils.setValidateWhileTextChanged(port, true);
        // The port is stored as text, so the converter is taken as far as the number and the
        // conversion back to text is done here: binding it through the library's own helper
        // would need the property to be an integer one, and the file has always held a string.
        org.jackhuang.hmcl.util.javafx.SafeStringConverter<Integer, Number> portConverter =
                org.jackhuang.hmcl.util.javafx.SafeStringConverter.fromInteger()
                        .restrict(it -> it >= 0 && it <= 0xFFFF)
                        .fallbackTo(0);
        portConverter.asPredicate(org.jackhuang.hmcl.ui.construct.Validator.addTo(port));
        port.setText(portConverter.toString(portConverter.fromString(
                settings().proxyPortProperty().get())));
        port.textProperty().addListener((observable, was, text) -> {
            Integer port0 = portConverter.fromString(text == null ? "" : text);
            if (port0 != null) {
                settings().proxyPortProperty().set(Integer.toString(port0));
            }
        });

        proxyPane.getChildren().add(formGrid(new javafx.scene.control.Label(i18n("settings.launcher.proxy.host")),
                host, new javafx.scene.control.Label(i18n("settings.launcher.proxy.port")), port));

        com.jfoenix.controls.JFXCheckBox authenticated =
                new com.jfoenix.controls.JFXCheckBox(i18n("settings.launcher.proxy.authentication"));
        authenticated.selectedProperty().bindBidirectional(settings().proxyAuthenticatedProperty());
        javafx.scene.layout.VBox authToggle = new javafx.scene.layout.VBox(authenticated);
        authToggle.setPadding(new javafx.geometry.Insets(20, 0, 20, 5));
        proxyPane.getChildren().add(authToggle);

        com.jfoenix.controls.JFXTextField user = new com.jfoenix.controls.JFXTextField();
        user.textProperty().bindBidirectional(settings().proxyUserProperty());
        com.jfoenix.controls.JFXPasswordField password = new com.jfoenix.controls.JFXPasswordField();
        password.textProperty().bindBidirectional(settings().proxyPasswordProperty());

        javafx.scene.layout.GridPane authPane = formGrid(
                new javafx.scene.control.Label(i18n("settings.launcher.proxy.username")), user,
                new javafx.scene.control.Label(i18n("settings.launcher.proxy.password")), password);
        authPane.disableProperty().bind(settings().proxyAuthenticatedProperty().not());
        proxyPane.getChildren().add(authPane);

        proxyList.getChildren().add(proxyPane);
        return proxyList;
    }

    /// Returns the proxy mode in force.
    ///
    /// @return the mode, never `null`
    private static org.jackhuang.hmcl.dsh.DshProxyMode currentProxyMode() {
        return java.util.Objects.requireNonNullElse(
                settings().proxyModeProperty().get(), org.jackhuang.hmcl.dsh.DshProxyMode.SYSTEM);
    }

    /// Returns the string key for one proxy mode.
    ///
    /// The original's keys, because the four choices are its four choices.
    ///
    /// @param mode the mode
    /// @return the key
    private static String proxyModeKey(org.jackhuang.hmcl.dsh.DshProxyMode mode) {
        return switch (mode) {
            case SYSTEM -> "settings.launcher.proxy.default";
            case NONE -> "settings.launcher.proxy.none";
            case HTTP -> "settings.launcher.proxy.http";
            case SOCKS -> "settings.launcher.proxy.socks";
        };
    }

    /// Builds a two-row form: a name and its field, twice.
    ///
    /// The shape the original's proxy form has, down to the grid it uses: the names take their
    /// own width, the fields take what is left, and the whole block is indented so that it reads
    /// as belonging to the line of modes above it.
    ///
    /// @param firstLabel  the first row's name
    /// @param firstField  the first row's field
    /// @param secondLabel the second row's name
    /// @param secondField the second row's field
    /// @return the grid
    private static javafx.scene.layout.GridPane formGrid(
            javafx.scene.Node firstLabel, javafx.scene.Node firstField,
            javafx.scene.Node secondLabel, javafx.scene.Node secondField) {
        javafx.scene.layout.ColumnConstraints grow = new javafx.scene.layout.ColumnConstraints();
        grow.setHgrow(Priority.ALWAYS);

        javafx.scene.layout.GridPane grid = new javafx.scene.layout.GridPane();
        grid.setPadding(new javafx.geometry.Insets(0, 0, 0, 30));
        grid.setHgap(20);
        grid.setVgap(10);
        grid.getColumnConstraints().setAll(new javafx.scene.layout.ColumnConstraints(), grow);
        grid.addRow(0, firstLabel, firstField);
        grid.addRow(1, secondLabel, secondField);
        return grid;
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
        if (hint != null && !hint.isBlank()) {
            pane.setSubtitle(hint);
        }
        pane.setRight(field);
        return pane;
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
