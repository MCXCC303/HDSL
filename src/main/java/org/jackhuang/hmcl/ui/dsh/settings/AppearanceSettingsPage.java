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
import org.jackhuang.hmcl.ui.construct.ComponentSublist;
import org.jackhuang.hmcl.ui.construct.LineFileChooserButton;
import org.jackhuang.hmcl.ui.construct.LineSelectButton;
import org.jackhuang.hmcl.ui.construct.LinePane;
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

    /// Builds a section title.
    ///
    /// Uses HMCL's helper rather than a styled row: the original puts the title
    /// between card groups, outside their background, and that placement is what
    /// makes a settings page read as titled groups rather than one long list.
    ///
    /// @param text the title
    /// @return the title node
    private static Node sectionTitle(String text) {
        return ComponentList.createComponentListTitle(text);
    }

    /// Creates the appearance settings tab.
    public AppearanceSettingsPage() {
        setFitToWidth(true);

        VBox root = new VBox(10);
        root.setPadding(new Insets(10));
        setContent(root);

        FXUtils.smoothScrolling(this);

        // Grouped as the original groups them: the theme pack has a section to
        // itself, and the colours, font, animations and window belong to one
        // section called appearance. A section per setting puts the section's
        // name and its only row's name next to each other saying the same word.
        // The sections and their order are the original's: the theme, then how the launcher looks —
        // including the picture and how solid it is — then how a background is fetched, then the
        // animations, and the fonts last.
        root.getChildren().addAll(
                sectionTitle(i18n("dsh.settings.theme")), buildThemeList(),
                sectionTitle(i18n("settings.launcher.appearance")),
                buildBrightnessList(), buildThemeColorList(), buildColorStyleList(), buildBackgroundDetailList(), buildWindowList(),
                sectionTitle(i18n("dsh.settings.background.load.section")), buildBackgroundLoadingList(),
                sectionTitle(i18n("dsh.settings.animations")), buildAnimationList(),
                sectionTitle(i18n("dsh.settings.font")), buildFontList());
    }

    /// Builds the theme colour section.
    ///
    /// The colours are radio choices rather than a dropdown because the custom
    /// entry carries a colour picker beside it, which a dropdown row has no room
    /// for. This mirrors HMCL's `RadioChoiceList` of `ThemeColorType`.
    ///
    /// @return the assembled component list
    /// Adds a colour style by name, when this build has it.
    ///
    /// The type comes from the theme library, so a name that library does not have is skipped
    /// rather than failing: a row with fewer choices is better than a launcher that will not
    /// start.
    ///
    /// @param styles where to add it
    /// @param name   the style's name
    private static void addStyle(java.util.List<org.glavo.monetfx.ColorStyle> styles, String name) {
        try {
            styles.add(org.glavo.monetfx.ColorStyle.valueOf(name));
        } catch (IllegalArgumentException e) {
            org.jackhuang.hmcl.util.logging.Logger.LOG.info(
                    "This build has no colour style named " + name);
        }
    }

    private javafx.scene.Node buildThemeColorList() {
        // The original keeps this as an expanding row: the colour in force is shown beside the name
        // and the choices are inside it, which is where a setting with several forms belongs.
        ComponentSublist sublist = new ComponentSublist();
        sublist.setTitle(i18n("settings.launcher.theme_color"));
        sublist.descriptionProperty().bind(javafx.beans.binding.Bindings.createStringBinding(() -> {
            org.jackhuang.hmcl.setting.ThemeColorType type = settings().themeColorTypeProperty().get();
            return type == null ? ""
                    : i18n("dsh.settings.theme_color." + type.name().toLowerCase(java.util.Locale.ROOT));
        }, settings().themeColorTypeProperty()));
        ComponentList list = new ComponentList();


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
        // The colour style says how the chosen colour becomes a palette, and the theme engine
        // has been applying it with no way of reaching it from here. It belongs beside the
        // colour itself, which is where the original keeps it. Its choices are the ones both
        // bundles name, so the row can never show a key where a word belongs.
        java.util.List<org.glavo.monetfx.ColorStyle> styles = new java.util.ArrayList<>();
        addStyle(styles, "CONTENT");
        addStyle(styles, "EXPRESSIVE");
        addStyle(styles, "FIDELITY");
        addStyle(styles, "FRUIT_SALAD");
        addStyle(styles, "MONOCHROME");
        addStyle(styles, "NEUTRAL");
        addStyle(styles, "RAINBOW");
        addStyle(styles, "TONAL_SPOT");
        addStyle(styles, "VIBRANT");
        if (!styles.isEmpty()) {
            LineSelectButton<org.glavo.monetfx.ColorStyle> colorStyle = new LineSelectButton<>();
            colorStyle.setTitle(i18n("settings.launcher.theme_color_style"));
            colorStyle.setItems(styles);
            colorStyle.setConverter(style -> style == null ? ""
                    : i18n("settings.launcher.theme_color_style." + style.name().toLowerCase(java.util.Locale.ROOT)));
            colorStyle.setValue(settings().themeColorStyleProperty().get());
            colorStyle.valueProperty().addListener((observable, was, value) -> {
                if (value != null) {
                    settings().themeColorStyleProperty().set(value);
                }
            });
        this.colorStyleRow = colorStyle;
        }

        sublist.getContent().add(list);
        // A list is what turns a sublist into the row that opens: it wraps it with the header the
        // original's expanding rows have, which is why the original adds them to a list as well.
        ComponentList wrapper = new ComponentList();
        wrapper.getContent().add(sublist);
        return wrapper;
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


        LineTextPane row = new LineTextPane();
        row.setTitle(i18n("dsh.settings.font.launcher"));
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
        // The log has a font and a size of its own: the original keeps them as a row here, and both
        // settings have existed all along with nothing on the page to reach them.
        LineTextPane logRow = new LineTextPane();
        logRow.setTitle(i18n("dsh.settings.font.log"));

        FontComboBox logFont = new FontComboBox();
        logFont.setValue(settings().logFontFamilyProperty().get());
        logFont.valueProperty().addListener((observable, was, value) ->
                settings().logFontFamilyProperty().set(value == null ? "" : value));

        com.jfoenix.controls.JFXTextField size = new com.jfoenix.controls.JFXTextField();
        org.jackhuang.hmcl.ui.FXUtils.setLimitWidth(size, 60);
        size.setText(Double.toString(settings().logFontSizeProperty().get()));
        size.textProperty().addListener((observable, was, text) -> {
            try {
                double value = Double.parseDouble(text == null ? "" : text.trim());
                if (value > 0) {
                    settings().logFontSizeProperty().set(value);
                }
            } catch (NumberFormatException e) {
                // Half-typed numbers are not settings.
            }
        });

        JFXButton resetLogFont = FXUtils.newToggleButton4(SVG.RESTORE);
        FXUtils.installFastTooltip(resetLogFont, i18n("button.reset"));
        resetLogFont.setOnAction(event -> {
            logFont.setValue(null);
            size.setText("12");
        });

        HBox logControls = new HBox(8, logFont, size, resetLogFont);
        logControls.setAlignment(Pos.CENTER_RIGHT);
        // Attached the way the row above attaches its own controls.
        logRow.setRowTrailing(logControls);
        list.getContent().add(logRow);


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
        animations.setTitle(i18n("dsh.settings.animations.off"));
        animations.setSubtitle(i18n("dsh.settings.animations.desc"));
        // The row asks what the original asks — whether to turn the animations off — so the switch
        // reads the same way round as its own name.
        animations.setSelected(settings().isAnimationDisabled());
        animations.selectedProperty().addListener((observable, was, value) ->
                settings().animationDisabledProperty().set(value));

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
        brightness.setTitle(i18n("settings.launcher.brightness"));
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
        this.brightnessRow = brightness;
        list.getContent().add(theme);
        return list;
    }

    /// The row that chooses the launcher's brightness mode.
    private javafx.scene.Node brightnessRow;

    /// The row that chooses how the theme colour becomes a palette.
    private javafx.scene.Node colorStyleRow;

    /// The row about what happens when the background cannot be loaded.
    private javafx.scene.Node backgroundLoadRow;

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
        this.backgroundLoadRow = loadPolicy;
        list.getContent().add(backgroundType);
        // The original keeps one row about a background that does not arrive, and this is it: the
        // choices about where a background comes from and how solid it is are the appearance
        // section's rows, which is where the original puts them.
        ComponentSublist sublist = new ComponentSublist();
        sublist.setTitle(i18n("dsh.settings.background.fallback"));
        ComponentList wrapper = new ComponentList();
        sublist.getContent().add(list);
        wrapper.getContent().add(sublist);
        return wrapper;
    }

    /// Builds the source-specific controls and the opacity slider.
    ///
    /// @return the assembled component list
    /// Builds the row about a background that does not arrive.
    ///
    /// The original keeps this as a row of its own that opens: the policy inside it is the answer to
    /// the question the row asks, and it belongs to nothing else.
    ///
    /// @return the assembled component list
    /// Builds the rows about how a background is fetched and what is used when it does not arrive.
    ///
    /// @return the assembled component list
    /// Builds the row that chooses how the theme's colour becomes a palette.
    ///
    /// The original keeps this beside the colour row rather than inside it, so the choice is visible
    /// without opening anything.
    ///
    /// @return the assembled component list
    private ComponentList buildColorStyleList() {
        ComponentList list = new ComponentList();
        if (colorStyleRow != null) {
            list.getContent().add(colorStyleRow);
        }
        return list;
    }


    /// Builds the row that chooses the launcher's brightness mode.
    ///
    /// The original keeps this at the head of the appearance section, before the colours.
    ///
    /// @return the assembled component list
    private ComponentList buildBrightnessList() {
        ComponentList list = new ComponentList();
        if (brightnessRow != null) {
            list.getContent().add(brightnessRow);
        }
        return list;
    }


    private ComponentList buildBackgroundLoadingList() {
        // The original's two rows for this: the fallback that opens, and the policy beside it. The
        // fallback builder is what makes the policy row, so it is asked for it first.
        ComponentList fallback = buildBackgroundSourceList();
        ComponentList list = new ComponentList();
        list.getContent().add(fallback);
        if (backgroundLoadRow != null) {
            list.getContent().add(backgroundLoadRow);
        }
        return list;
    }


    private ComponentList buildBackgroundFallbackList() {
        ComponentSublist sublist = new ComponentSublist();
        sublist.setTitle(i18n("dsh.settings.background.fallback"));
        ComponentList wrapper = new ComponentList();
        if (backgroundLoadRow != null) {
            sublist.getContent().add(backgroundLoadRow);
        }
        wrapper.getContent().add(sublist);
        return wrapper;
    }


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

        // A LinePane, not a LineTextPane with a title-trailing node: HMCL puts
        // the slider on the row's right edge with setRight, while a title
        // trailing sits immediately after the label.
        LinePane opacity = new LinePane();
        opacity.setTitle(i18n("dsh.settings.background.opacity"));
        opacity.setRight(buildOpacitySlider());

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
