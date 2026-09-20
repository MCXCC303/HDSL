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

import com.jfoenix.controls.JFXSlider;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.StringBinding;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.setting.BackgroundType;
import org.jackhuang.hmcl.setting.LauncherSettings;
import org.jackhuang.hmcl.setting.SettingsManager;
import java.util.Optional;
import org.jackhuang.hmcl.ui.construct.RadioChoiceList;
import org.jackhuang.hmcl.theme.ThemeColor;
import org.jackhuang.hmcl.setting.ThemeColorType;
import com.jfoenix.controls.JFXButton;
import org.jackhuang.hmcl.ui.construct.FontComboBox;
import org.jackhuang.hmcl.setting.FontManager;
import javafx.scene.Node;
import javafx.scene.control.ColorPicker;
import com.jfoenix.controls.JFXColorPicker;
import org.jackhuang.hmcl.theme.BackgroundLoadPolicy;
import org.jackhuang.hmcl.theme.BuiltinBackground;
import org.jackhuang.hmcl.theme.Theme;
import org.jackhuang.hmcl.theme.ThemePackManager;
import org.jackhuang.hmcl.theme.ThemeReference;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.construct.ComponentList;
import org.jackhuang.hmcl.ui.construct.LineFileChooserButton;
import org.jackhuang.hmcl.ui.construct.LineSelectButton;
import org.jackhuang.hmcl.ui.construct.LineTextPane;
import org.jackhuang.hmcl.ui.construct.LineToggleButton;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.jackhuang.hmcl.setting.SettingsManager.settings;
import static org.jackhuang.hmcl.util.i18n.I18n.i18n;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// The "appearance" tab of the launcher settings page.
///
/// Drives the transplanted theme engine: theme pack selection, brightness mode,
/// the background source and its opacity, and window transparency. Every row
/// records the corresponding appearance-override key, because the theme engine
/// distinguishes "the theme chose this" from "the user chose this".
@NotNullByDefault
public final class AppearanceSettingsPage extends ScrollPane {
    /// Brightness mode identifiers accepted by the theme engine.
    private static final List<String> BRIGHTNESS_MODES = List.of("auto", "light", "dark");

    /// Creates the appearance settings tab.
    public AppearanceSettingsPage() {
        setFitToWidth(true);

        VBox root = new VBox(10);
        root.setPadding(new Insets(10));
        setContent(root);

        FXUtils.smoothScrolling(this);

        root.getChildren().addAll(
                buildThemeList(),
                buildThemeColorList(),
                buildFontList(),
                buildAnimationList(),
                buildBackgroundSourceList(),
                buildBackgroundDetailList(),
                buildWindowList());
    }

    /// Builds the theme colour section.
    ///
    /// The colours are radio choices rather than a dropdown because the custom
    /// entry carries a colour picker beside it, which a dropdown row has no room
    /// for. This mirrors HMCL's `RadioChoiceList` of `ThemeColorType`.
    ///
    /// @return the assembled component list
    private ComponentList buildThemeColorList() {
        ComponentList list = new ComponentList();

        LineTextPane header = new LineTextPane();
        header.setTitle(i18n("dsh.settings.theme_color"));
        header.getStyleClass().add("section-header");
        list.getContent().add(header);

        ThemeColor currentCustom = Optional.ofNullable(settings().customThemeColorProperty().get())
                .orElse(ThemeColor.DEFAULT);

        ColorPicker picker = new JFXColorPicker();
        picker.setValue(currentCustom.color());
        picker.valueProperty().addListener((observable, was, color) -> {
            if (color != null) {
                settings().customThemeColorProperty().set(new ThemeColor(currentCustom.name(), color));
                settings().getThemeAppearanceOverrides().add(LauncherSettings.THEME_APPEARANCE_COLOR);
                // Picking a colour is only meaningful if it is also the mode in
                // use, so choosing one selects the custom entry.
                settings().themeColorTypeProperty().set(ThemeColorType.CUSTOM);
            }
        });

        RadioChoiceList.Choice<ThemeColorType> custom = new RadioChoiceList.Choice<>(
                i18n("dsh.settings.theme_color.custom"), ThemeColorType.CUSTOM) {
            @Override
            protected Node createRightNode() {
                return picker;
            }
        };

        RadioChoiceList<ThemeColorType> choices = new RadioChoiceList<>();
        choices.setFallbackValue(ThemeColorType.DEFAULT);
        choices.setChoices(
                new RadioChoiceList.Choice<>(i18n("dsh.settings.theme_color.default"), ThemeColorType.DEFAULT),
                new RadioChoiceList.Choice<>(i18n("dsh.settings.theme_color.system"), ThemeColorType.SYSTEM),
                custom,
                new RadioChoiceList.Choice<>(i18n("dsh.settings.theme_color.background"),
                        ThemeColorType.BACKGROUND));
        choices.setSelectedValue(Objects.requireNonNullElse(
                settings().themeColorTypeProperty().get(), ThemeColorType.DEFAULT));
        choices.selectedValueProperty().addListener((observable, was, value) -> {
            if (value != null && value != was) {
                // The colour is only consulted when it is recorded as an
                // override; without this the theme pack's own colour wins and
                // the choice appears to do nothing.
                settings().getThemeAppearanceOverrides().add(LauncherSettings.THEME_APPEARANCE_COLOR);
                settings().themeColorTypeProperty().set(value);
                SettingsManager.save();
            }
        });

        list.getContent().add(choices);
        return list;
    }

    /// Builds the font section.
    ///
    /// HMCL's font control is a `FontComboBox` rather than a generic selector,
    /// and that is not cosmetic: this system reports thousands of font
    /// families, and the generic selector builds a node per item the moment it
    /// is opened. The combo box loads its list on first use and virtualises it,
    /// so the count does not matter, and each row previews its own family.
    ///
    /// @return the assembled component list
    private ComponentList buildFontList() {
        ComponentList list = new ComponentList();

        LineTextPane header = new LineTextPane();
        header.setTitle(i18n("dsh.settings.font"));
        header.getStyleClass().add("section-header");
        list.getContent().add(header);

        LineTextPane row = new LineTextPane();
        row.setTitle(i18n("dsh.settings.font"));
        row.setSubtitle(i18n("dsh.settings.font.hint"));

        FontComboBox font = new FontComboBox();
        font.setValue(settings().launcherFontFamilyProperty().get());
        FXUtils.onChangeAndOperate(font.valueProperty(), FontManager::setFontFamily);

        JFXButton reset = FXUtils.newToggleButton4(SVG.RESTORE);
        FXUtils.installFastTooltip(reset, i18n("button.reset"));
        reset.setOnAction(event -> font.setValue(null));

        HBox controls = new HBox(8, font, reset);
        controls.setAlignment(Pos.CENTER_RIGHT);
        row.setRowTrailing(controls);

        list.getContent().add(row);
        return list;
    }

    /// Builds the animation section.
    ///
    /// The toggle is inverted because the setting stores the disabling rather
    /// than the enabling, which is how the animation helpers read it.
    ///
    /// @return the assembled component list
    private ComponentList buildAnimationList() {
        ComponentList list = new ComponentList();

        LineToggleButton animations = new LineToggleButton();
        animations.setTitle(i18n("dsh.settings.animations"));
        animations.setSubtitle(i18n("dsh.settings.animations.desc"));
        animations.setSelected(!settings().isAnimationDisabled());
        animations.selectedProperty().addListener((observable, was, value) ->
                settings().animationDisabledProperty().set(!value));

        list.getContent().add(animations);
        return list;
    }

    /// Builds the theme section: theme pack and brightness mode.
    ///
    /// @return the assembled component list
    private ComponentList buildThemeList() {
        LineSelectButton<ThemeReference> theme = new LineSelectButton<>();
        theme.setTitle(i18n("dsh.settings.theme"));
        theme.setItems(installedThemeReferences());
        theme.setNullSafeConverter(AppearanceSettingsPage::displayNameOf);
        theme.setValue(settings().getSelectedThemeOrDefault());
        theme.valueProperty().addListener((observable, oldValue, newValue) -> {
            if (newValue != null && !Objects.equals(oldValue, newValue)) {
                settings().getThemeAppearanceOverrides().add(LauncherSettings.THEME_APPEARANCE_COLOR);
                settings().selectedThemeProperty().set(newValue);
            }
        });

        LineSelectButton<String> brightness = new LineSelectButton<>();
        brightness.setTitle(i18n("dsh.settings.theme.brightness"));
        brightness.setItems(BRIGHTNESS_MODES);
        brightness.setNullSafeConverter(mode -> i18n("dsh.settings.theme.brightness." + mode));
        brightness.setValue(settings().themeBrightnessModeProperty().get());
        brightness.valueProperty().addListener((observable, oldValue, newValue) -> {
            if (newValue != null) {
                settings().getThemeAppearanceOverrides().add(LauncherSettings.THEME_APPEARANCE_BRIGHTNESS_MODE);
                settings().themeBrightnessModeProperty().set(newValue);
            }
        });

        ComponentList list = new ComponentList();
        list.getContent().addAll(theme, brightness);
        return list;
    }

    /// Builds the background-source selector.
    ///
    /// @return the assembled component list
    private ComponentList buildBackgroundSourceList() {
        LineSelectButton<BackgroundType> backgroundType = new LineSelectButton<>();
        backgroundType.setTitle(i18n("dsh.settings.background"));
        backgroundType.setItems(List.of(
                BackgroundType.DEFAULT,
                BackgroundType.THEME_COLOR,
                BackgroundType.BUILTIN,
                BackgroundType.CUSTOM,
                BackgroundType.NETWORK));
        backgroundType.setNullSafeConverter(type -> i18n("dsh.settings.background." + type.name().toLowerCase(java.util.Locale.ROOT)));
        backgroundType.setValue(settings().backgroundTypeProperty().get());
        backgroundType.valueProperty().addListener((observable, oldValue, newValue) -> {
            if (newValue != null) {
                selectBackground(newValue);
            }
        });

        LineSelectButton<BackgroundLoadPolicy> loadPolicy = new LineSelectButton<>();
        loadPolicy.setTitle(i18n("dsh.settings.background.load"));
        loadPolicy.setItems(List.of(BackgroundLoadPolicy.WAIT_FOR_BACKGROUND,
                BackgroundLoadPolicy.SHOW_FALLBACK_WHILE_LOADING));
        loadPolicy.setNullSafeConverter(policy -> i18n("dsh.settings.background.load."
                + policy.name().toLowerCase(java.util.Locale.ROOT)));
        loadPolicy.setValue(settings().backgroundLoadPolicyProperty().get());
        loadPolicy.valueProperty().addListener((observable, oldValue, newValue) -> {
            if (newValue != null) {
                settings().backgroundLoadPolicyProperty().set(newValue);
            }
        });

        ComponentList list = new ComponentList();
        list.getContent().addAll(backgroundType, loadPolicy);
        return list;
    }

    /// Builds the source-specific controls and the opacity slider.
    ///
    /// @return the assembled component list
    private ComponentList buildBackgroundDetailList() {
        LineFileChooserButton image = new LineFileChooserButton();
        image.setTitle(i18n("dsh.settings.background.image"));
        image.setType(LineFileChooserButton.Type.OPEN_FILE);
        image.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter(
                i18n("dsh.settings.background.image.filter"), "*.png", "*.jpg", "*.jpeg", "*.webp", "*.bmp", "*.gif"));
        image.setLocation(settings().customBackgroundImagePathProperty().get());
        image.locationProperty().addListener((observable, oldValue, newValue) -> {
            if (newValue != null && !newValue.isBlank()) {
                settings().customBackgroundImagePathProperty().set(newValue);
                selectBackground(BackgroundType.CUSTOM);
            }
        });

        LineTextPane network = new LineTextPane();
        network.setTitle(i18n("dsh.settings.background.network"));
        network.setText(settings().networkBackgroundImageUrlProperty().get());
        network.setOnMouseClicked(event -> Controllers.prompt(
                i18n("dsh.settings.background.network.prompt"),
                (value, handler) -> {
                    String url = value == null ? "" : value.trim();
                    if (url.isEmpty()) {
                        handler.reject(i18n("dsh.settings.background.network.empty"));
                        return;
                    }
                    settings().networkBackgroundImageUrlProperty().set(url);
                    selectBackground(BackgroundType.NETWORK);
                    handler.resolve();
                },
                settings().networkBackgroundImageUrlProperty().get()));

        LineSelectButton<String> builtin = new LineSelectButton<>();
        builtin.setTitle(i18n("dsh.settings.background.builtin"));
        builtin.setItems(BuiltinBackground.BUILTIN_BACKGROUND_IDS);
        builtin.setNullSafeConverter(AppearanceSettingsPage::builtinName);
        builtin.setValue(settings().builtinBackgroundIdProperty().get());
        builtin.valueProperty().addListener((observable, oldValue, newValue) -> {
            if (newValue != null) {
                settings().builtinBackgroundIdProperty().set(newValue);
                selectBackground(BackgroundType.BUILTIN);
            }
        });

        LineTextPane opacity = new LineTextPane();
        opacity.setTitle(i18n("dsh.settings.background.opacity"));
        opacity.setTitleTrailing(buildOpacitySlider());

        ComponentList list = new ComponentList();
        list.getContent().addAll(image, network, builtin, opacity);
        return list;
    }

    /// Builds the opacity slider shown on the right of its row.
    ///
    /// @return the slider and its percentage label
    private HBox buildOpacitySlider() {
        JFXSlider slider = new JFXSlider(0, 100, settings().backgroundOpacityProperty().get() * 100);
        slider.setPrefWidth(220);
        slider.setShowTickMarks(true);
        slider.setMajorTickUnit(10);
        slider.setMinorTickCount(1);
        slider.setBlockIncrement(5);
        slider.setSnapToTicks(true);
        HBox.setHgrow(slider, Priority.ALWAYS);

        Label percentage = new Label();
        FXUtils.setLimitWidth(percentage, 50);
        percentage.setAlignment(Pos.CENTER);
        StringBinding text = Bindings.createStringBinding(
                () -> ((int) slider.getValue()) + "%", slider.valueProperty());
        percentage.textProperty().bind(text);
        slider.setValueFactory(ignored -> text);

        slider.valueProperty().addListener((observable, oldValue, newValue) -> {
            double opacity = Math.max(0, Math.min(1, Math.round(newValue.doubleValue()) / 100.0));
            settings().getThemeAppearanceOverrides().add(LauncherSettings.THEME_APPEARANCE_BACKGROUND_OPACITY);
            if (Double.compare(settings().backgroundOpacityProperty().get(), opacity) != 0) {
                settings().backgroundOpacityProperty().set(opacity);
            }
        });

        HBox box = new HBox(8, slider, percentage);
        box.setAlignment(Pos.CENTER);
        return box;
    }

    /// Builds the window section.
    ///
    /// @return the assembled component list
    private ComponentList buildWindowList() {
        LineToggleButton transparentTitleBar = new LineToggleButton();
        transparentTitleBar.setTitle(i18n("dsh.settings.title_bar_transparent"));
        transparentTitleBar.setSelected(settings().titleBarTransparentProperty().get());
        transparentTitleBar.selectedProperty().addListener((observable, oldValue, newValue) -> {
            settings().getThemeAppearanceOverrides().add(LauncherSettings.THEME_APPEARANCE_TITLE_BAR_TRANSPARENT);
            settings().titleBarTransparentProperty().set(newValue);
        });

        LineToggleButton transparentWindow = new LineToggleButton();
        transparentWindow.setTitle(i18n("dsh.settings.window_transparent"));
        transparentWindow.setSelected(settings().windowTransparentProperty().get());
        transparentWindow.selectedProperty().addListener((observable, oldValue, newValue) -> {
            settings().getThemeAppearanceOverrides().add(LauncherSettings.THEME_APPEARANCE_WINDOW_TRANSPARENT);
            settings().windowTransparentProperty().set(newValue);
        });

        ComponentList list = new ComponentList();
        list.getContent().addAll(transparentTitleBar, transparentWindow);
        return list;
    }

    /// Switches the background source and records the user's override.
    ///
    /// Without the override key the theme engine would keep treating the
    /// background as "chosen by the theme" and overwrite the user's selection.
    ///
    /// @param type the newly selected source
    private static void selectBackground(BackgroundType type) {
        settings().getThemeAppearanceOverrides().add(LauncherSettings.THEME_APPEARANCE_BACKGROUND);
        settings().backgroundTypeProperty().set(type);
    }

    /// Enumerates every theme the installed theme packs expose.
    ///
    /// @return the selectable theme references
    private static List<ThemeReference> installedThemeReferences() {
        List<ThemeReference> references = new ArrayList<>();
        try {
            for (ThemePackManager.InstalledThemePack pack : ThemePackManager.listInstalled()) {
                List<Theme> themes = pack.manifest().themes();
                if (themes.isEmpty()) {
                    references.add(new ThemeReference(pack.manifest().id(), null));
                } else {
                    for (Theme candidate : themes) {
                        references.add(new ThemeReference(pack.manifest().id(), candidate.id()));
                    }
                }
            }
        } catch (IOException e) {
            LOG.warning("Failed to enumerate installed theme packs", e);
        }
        if (references.isEmpty()) {
            references.add(LauncherSettings.DEFAULT_THEME_REFERENCE);
        }
        return references;
    }

    /// Renders a theme reference for the selector.
    ///
    /// @param reference the reference to render
    /// @return a human-readable label
    private static String displayNameOf(ThemeReference reference) {
        return reference.themeId() == null
                ? reference.packId()
                : reference.packId() + " / " + reference.themeId();
    }

    /// Renders a built-in wallpaper id, falling back to the raw id.
    ///
    /// @param id the wallpaper id
    /// @return a human-readable label
    private static String builtinName(String id) {
        BuiltinBackground background = BuiltinBackground.fromId(id);
        return background == null ? id : background.id();
    }
}
