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

import com.jfoenix.controls.JFXButton;
import com.jfoenix.controls.JFXColorPicker;
import com.jfoenix.controls.JFXComboBox;
import com.jfoenix.controls.JFXSlider;
import com.jfoenix.controls.JFXTextField;
import javafx.beans.InvalidationListener;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.StringBinding;
import javafx.beans.property.Property;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.paint.Paint;
import org.jackhuang.hmcl.setting.BackgroundType;
import org.jackhuang.hmcl.setting.FontManager;
import org.jackhuang.hmcl.setting.LauncherSettings;
import org.jackhuang.hmcl.setting.SettingsManager;
import org.jackhuang.hmcl.setting.ThemeColorType;
import org.jackhuang.hmcl.theme.BackgroundLoadPolicy;
import org.jackhuang.hmcl.theme.BuiltinBackground;
import org.jackhuang.hmcl.theme.NetworkBackgroundImageCachePolicy;
import org.jackhuang.hmcl.theme.Theme;
import org.jackhuang.hmcl.theme.ThemeColor;
import org.jackhuang.hmcl.theme.ThemePackManager;
import org.jackhuang.hmcl.theme.ThemeReference;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.construct.ComponentList;
import org.jackhuang.hmcl.ui.construct.ComponentSublist;
import org.jackhuang.hmcl.ui.construct.FontComboBox;
import org.jackhuang.hmcl.ui.construct.LineButton;
import org.jackhuang.hmcl.ui.construct.LinePane;
import org.jackhuang.hmcl.ui.construct.LineSelectButton;
import org.jackhuang.hmcl.ui.construct.LineTextPane;
import org.jackhuang.hmcl.ui.construct.LineToggleButton;
import org.jackhuang.hmcl.ui.construct.RadioChoiceList;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import static org.jackhuang.hmcl.setting.SettingsManager.settings;
import static org.jackhuang.hmcl.util.i18n.I18n.i18n;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// The "appearance" tab of the launcher settings page.
///
/// Drives the transplanted theme engine: the theme pack, the brightness mode, the
/// theme colour and its palette, the background and its opacity, the window, the
/// animations and the fonts. The theme engine distinguishes "the theme chose this"
/// from "the user chose this", so every appearance row carries the small mark beside
/// its name that says which of the two is in force and switches between them — that
/// mark is the original's own control, and without it a row that follows the theme
/// gives no way to take it over.
@NotNullByDefault
public final class AppearanceSettingsPage extends ScrollPane {
    /// Brightness mode identifiers accepted by the theme engine.
    private static final List<String> BRIGHTNESS_MODES = List.of("auto", "light", "dark");

    /// The stylesheet class of the original's small override mark.
    private static final String OVERRIDE_BUTTON_STYLE_CLASS = "toggle-icon-tiny";

    /// The pseudo-class the mark wears once the value is the user's rather than the theme's.
    private static final javafx.css.PseudoClass OVERRIDDEN =
            javafx.css.PseudoClass.getPseudoClass("overridden");

    /// The tooltip the mark carries, kept so its text can follow the state.
    private static final String OVERRIDE_TOOLTIP_KEY = "HDSL.themeAppearanceOverrideTooltip";

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
        setFitToHeight(true);

        VBox root = new VBox(10);
        root.setPadding(new Insets(10));
        setContent(root);

        FXUtils.smoothScrolling(this);

        // The sections and their order are the original's: the theme, then how the launcher looks —
        // the colours, the picture and how solid it is, and the window — then how a background is
        // fetched, then the animations, and the fonts last.
        root.getChildren().addAll(
                sectionTitle(i18n("settings.launcher.theme")), buildThemeList(),
                sectionTitle(i18n("settings.launcher.appearance")), buildAppearanceList(),
                sectionTitle(i18n("launcher.background.loading")), buildBackgroundLoadingList(),
                sectionTitle(i18n("settings.launcher.animation")), buildAnimationList(),
                sectionTitle(i18n("settings.launcher.fonts")), buildFontList());
    }

    /// Builds the theme section: the installed theme, and getting one out of the launcher.
    ///
    /// The original's two rows: one that goes on to the theme packs, and one that writes
    /// what the launcher looks like now into a file. Both are navigation or export rather
    /// than a choice, which is why neither is a dropdown: the first opens a page because
    /// browsing theme packs is more than choosing one, and the second has nothing to
    /// choose.
    ///
    /// @return the assembled component list
    private ComponentList buildThemeList() {
        LineButton currentTheme = LineButton.createNavigationButton();
        currentTheme.setTitle(i18n("theme_pack.theme"));
        currentTheme.setSubtitle(selectedThemeTitle());
        FXUtils.onChangeAndOperate(settings().selectedThemeProperty(),
                ignored -> currentTheme.setSubtitle(selectedThemeTitle()));
        currentTheme.setOnAction(event ->
                Controllers.navigate(new ThemePackManagementPage()));

        LineButton export = new LineButton();
        export.setTitle(i18n("theme_pack.export"));
        export.setSubtitle(i18n("theme_pack.export.subtitle"));
        export.setTrailingIcon(SVG.ARCHIVE);
        export.setOnAction(event -> exportCurrentThemePack());

        ComponentList list = new ComponentList();
        list.getContent().addAll(currentTheme, export);
        return list;
    }

    /// Builds the appearance section.
    ///
    /// One card, in the original's order: how bright the interface is, what colour it
    /// takes, how that colour becomes a palette, the picture behind it and how solid it
    /// is, and whether the title bar and the window are transparent. Each is a row that
    /// opens where it stands — except the brightness and the palette, which are choices
    /// with nothing inside them.
    ///
    /// @return the assembled component list
    private ComponentList buildAppearanceList() {
        ComponentList list = new ComponentList();
        list.getContent().add(buildBrightnessRow());
        list.getContent().add(buildThemeColorRow());
        list.getContent().add(buildColorStyleRow());
        list.getContent().add(buildBackgroundRow());
        list.getContent().add(buildOpacityRow());
        list.getContent().addAll(buildWindowList().getContent());
        return list;
    }

    /// Builds the row that chooses the launcher's brightness mode.
    ///
    /// The original keeps this at the head of the appearance section, before the colours,
    /// and gives it the same override mark as the rest: a theme states a brightness and
    /// the user may take it over.
    ///
    /// @return the row
    private LineSelectButton<String> buildBrightnessRow() {
        LineSelectButton<String> brightness = new LineSelectButton<>();
        brightness.setTitle(i18n("settings.launcher.brightness"));
        brightness.setItems(BRIGHTNESS_MODES);
        brightness.setNullSafeConverter(mode -> i18n("dsh.settings.theme.brightness." + mode));
        originalBinding(brightness, LauncherSettings.THEME_APPEARANCE_BRIGHTNESS_MODE,
                settings().themeBrightnessModeProperty(), this::resolvedBrightnessMode);
        return brightness;
    }

    /// Builds the row that chooses the theme colour.
    ///
    /// An expanding row, which is where the original keeps a setting with several forms:
    /// the colour in force is shown beside the name, and the choices are inside.
    ///
    /// @return the row
    private ComponentSublist buildThemeColorRow() {
        ComponentSublist sublist = new ComponentSublist();
        sublist.setTitle(i18n("settings.launcher.theme_color"));
        sublist.setHasSubtitle(true);
        sublist.setTitleRight(createOverrideMark(LauncherSettings.THEME_APPEARANCE_COLOR,
                settings().themeColorTypeProperty(), () -> ThemeColorType.DEFAULT));

        ThemeColor currentCustom = Optional.ofNullable(settings().customThemeColorProperty().get())
                .orElse(ThemeColor.DEFAULT);

        JFXColorPicker picker = new JFXColorPicker();
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

        sublist.descriptionProperty().bind(Bindings.createStringBinding(() -> {
            ThemeColorType type = Objects.requireNonNullElse(
                    choices.selectedValueProperty().get(), ThemeColorType.DEFAULT);
            return i18n("dsh.settings.theme_color." + type.name().toLowerCase(Locale.ROOT));
        }, choices.selectedValueProperty()));

        sublist.getContent().setAll(choices);
        return sublist;
    }

    /// Builds the row that chooses how the theme's colour becomes a palette.
    ///
    /// @return the row
    private LineSelectButton<org.glavo.monetfx.ColorStyle> buildColorStyleRow() {
        LineSelectButton<org.glavo.monetfx.ColorStyle> colorStyle = new LineSelectButton<>();
        colorStyle.setTitle(i18n("settings.launcher.theme_color_style"));
        colorStyle.setConverter(style -> i18n("settings.launcher.theme_color_style."
                + Objects.requireNonNullElse(style, org.glavo.monetfx.ColorStyle.FIDELITY)
                .name().toLowerCase(Locale.ROOT)));
        colorStyle.setDescriptionConverter(style -> i18n("settings.launcher.theme_color_style."
                + Objects.requireNonNullElse(style, org.glavo.monetfx.ColorStyle.FIDELITY)
                .name().toLowerCase(Locale.ROOT) + ".desc"));
        colorStyle.setItems(availableColorStyles());
        originalBinding(colorStyle, LauncherSettings.THEME_APPEARANCE_COLOR_STYLE,
                settings().themeColorStyleProperty(), () -> org.glavo.monetfx.ColorStyle.FIDELITY);
        return colorStyle;
    }

    /// Builds the row that chooses where the background comes from.
    ///
    /// An expanding row holding every source the original offers — the default, a
    /// built-in wallpaper, the theme colour, a picture of your own, a network picture and
    /// a flat colour. The last of those is why the row has to hold them all: a colour has
    /// nowhere to be typed if the row is a dropdown, since the picker belongs beside the
    /// choice it applies to.
    ///
    /// @return the row
    private ComponentSublist buildBackgroundRow() {
        ComponentSublist sublist = new ComponentSublist();
        sublist.setTitle(i18n("launcher.background"));
        sublist.setHasSubtitle(true);
        Node overrideMark = createOverrideMark(LauncherSettings.THEME_APPEARANCE_BACKGROUND,
                settings().backgroundTypeProperty(), () -> BackgroundType.DEFAULT);
        sublist.setTitleRight(overrideMark);

        JFXComboBox<String> builtin = new JFXComboBox<>();
        builtin.getItems().setAll(BuiltinBackground.BUILTIN_BACKGROUND_IDS);
        FXUtils.setLimitWidth(builtin, 160);
        builtin.setValue(Objects.requireNonNullElse(
                settings().builtinBackgroundIdProperty().get(),
                BuiltinBackground.FALLBACK.id()));
        builtin.valueProperty().addListener((observable, was, value) -> {
            if (value != null) {
                settings().builtinBackgroundIdProperty().set(value);
                selectBackground(BackgroundType.BUILTIN);
            }
        });

        RadioChoiceList.FileChoice<BackgroundType> custom = new RadioChoiceList.FileChoice<>(
                i18n("settings.custom"), BackgroundType.CUSTOM)
                .setChooserTitle(i18n("launcher.background.choose"))
                .addExtensionFilter(FXUtils.getImageExtensionFilter());
        custom.setPath(Objects.toString(settings().customBackgroundImagePathProperty().get(), ""));
        custom.pathProperty().addListener((observable, was, value) -> {
            if (value != null && !value.isBlank()) {
                settings().customBackgroundImagePathProperty().set(value);
                selectBackground(BackgroundType.CUSTOM);
            }
        });

        RadioChoiceList.TextChoice<BackgroundType> network = new RadioChoiceList.TextChoice<>(
                i18n("launcher.background.network"), BackgroundType.NETWORK);
        network.setText(Objects.toString(settings().networkBackgroundImageUrlProperty().get(), ""));
        network.textProperty().addListener((observable, was, value) -> {
            if (value != null && !value.isBlank()) {
                settings().networkBackgroundImageUrlProperty().set(value);
                selectBackground(BackgroundType.NETWORK);
            }
        });

        PaintChoice paint = new PaintChoice(i18n("launcher.background.paint"),
                BackgroundType.PAINT, settings().customBackgroundPaintProperty());

        RadioChoiceList<BackgroundType> background = new RadioChoiceList<>();
        background.setFallbackValue(BackgroundType.DEFAULT);
        background.setChoices(
                new RadioChoiceList.Choice<>(i18n("message.default"), BackgroundType.DEFAULT)
                        .setTooltip(i18n("launcher.background.default.tooltip")),
                new RadioChoiceList.Choice<>(i18n("launcher.background.builtin"), BackgroundType.BUILTIN) {
                    @Override
                    protected Node createRightNode() {
                        return builtin;
                    }
                },
                new RadioChoiceList.Choice<>(i18n("launcher.background.theme_color"), BackgroundType.THEME_COLOR),
                custom,
                network,
                paint);
        background.setSelectedValue(Objects.requireNonNullElse(
                settings().backgroundTypeProperty().get(), BackgroundType.DEFAULT));
        background.selectedValueProperty().addListener((observable, was, value) -> {
            if (value != null && value != was) {
                selectBackground(value);
            }
        });

        // The line under the name says what is in force: the picture's address when there is one,
        // the wallpaper's name otherwise — which is what the original shows there.
        sublist.descriptionProperty().bind(Bindings.createStringBinding(() -> {
            BackgroundType type = Objects.requireNonNullElse(
                    background.selectedValueProperty().get(), BackgroundType.DEFAULT);
            return switch (type) {
                case DEFAULT -> i18n("message.default");
                case THEME_COLOR -> i18n("launcher.background.theme_color");
                case BUILTIN -> Objects.requireNonNullElse(
                        settings().builtinBackgroundIdProperty().get(),
                        BuiltinBackground.FALLBACK.id());
                case CUSTOM -> Objects.toString(
                        settings().customBackgroundImagePathProperty().get(), i18n("settings.custom"));
                case NETWORK -> Objects.toString(
                        settings().networkBackgroundImageUrlProperty().get(), i18n("launcher.background.network"));
                case PAINT -> {
                    Paint chosen = settings().customBackgroundPaintProperty().get();
                    yield chosen != null ? chosen.toString() : i18n("launcher.background.paint");
                }
            };
        }, background.selectedValueProperty(),
                settings().builtinBackgroundIdProperty(),
                settings().customBackgroundImagePathProperty(),
                settings().networkBackgroundImageUrlProperty(),
                settings().customBackgroundPaintProperty()));

        sublist.getContent().setAll(background);
        return sublist;
    }

    /// Builds the row with the opacity slider.
    ///
    /// A `LinePane`, not a `LineTextPane` with a trailing node: the original puts a slider
    /// on the row's right edge, while a title trailing sits immediately after the label.
    ///
    /// @return the row
    private LinePane buildOpacityRow() {
        LinePane opacity = new LinePane();
        opacity.setTitle(i18n("settings.launcher.background.settings.opacity"));
        opacity.setRight(buildOpacitySlider());
        return opacity;
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

    /// Builds the rows about how a background is fetched and what is used when it does not arrive.
    ///
    /// The original's three rows: whether a network picture is kept for next time, which
    /// standby is shown while it loads, and whether the launcher waits for the picture at
    /// all. The standby is an expanding row because one of its answers carries a colour
    /// picker.
    ///
    /// @return the assembled component list
    private ComponentList buildBackgroundLoadingList() {
        LineToggleButton cache = new LineToggleButton();
        cache.setTitle(i18n("launcher.background.network.cache"));
        cache.setSelected(cachePolicy() == NetworkBackgroundImageCachePolicy.ENABLED);
        cache.selectedProperty().addListener((observable, was, value) ->
                settings().networkBackgroundImageCachePolicyProperty().set(
                        Boolean.TRUE.equals(value)
                                ? NetworkBackgroundImageCachePolicy.ENABLED
                                : NetworkBackgroundImageCachePolicy.DISABLED));
        settings().networkBackgroundImageCachePolicyProperty().addListener((observable, was, value) ->
                cache.setSelected(cachePolicy() == NetworkBackgroundImageCachePolicy.ENABLED));

        ComponentSublist fallback = new ComponentSublist();
        fallback.setTitle(i18n("launcher.background.fallback"));
        fallback.setHasSubtitle(true);

        RadioChoiceList<BackgroundType> fallbackItem = new RadioChoiceList<>();
        fallbackItem.setFallbackValue(BackgroundType.BUILTIN);
        fallbackItem.setChoices(
                new RadioChoiceList.Choice<>(i18n("launcher.background.fallback.builtin"), BackgroundType.BUILTIN),
                new RadioChoiceList.Choice<>(i18n("launcher.background.fallback.theme_color"), BackgroundType.THEME_COLOR),
                new PaintChoice(i18n("launcher.background.fallback.paint"), BackgroundType.PAINT,
                        settings().backgroundFallbackPaintProperty()));
        fallbackItem.setSelectedValue(Objects.requireNonNullElse(
                settings().backgroundFallbackTypeProperty().get(), BackgroundType.BUILTIN));
        fallbackItem.selectedValueProperty().addListener((observable, was, value) -> {
            if (value != null && value != was) {
                settings().backgroundFallbackTypeProperty().set(value);
            }
        });
        fallback.descriptionProperty().bind(Bindings.createStringBinding(() -> {
            BackgroundType type = Objects.requireNonNullElse(
                    fallbackItem.selectedValueProperty().get(), BackgroundType.BUILTIN);
            return switch (type) {
                case PAINT -> {
                    Paint chosen = settings().backgroundFallbackPaintProperty().get();
                    yield chosen != null ? chosen.toString() : i18n("launcher.background.fallback.paint");
                }
                case THEME_COLOR -> i18n("launcher.background.fallback.theme_color");
                default -> i18n("launcher.background.fallback.builtin");
            };
        }, fallbackItem.selectedValueProperty(), settings().backgroundFallbackPaintProperty()));
        fallback.getContent().setAll(fallbackItem);

        LineSelectButton<BackgroundLoadPolicy> policy = new LineSelectButton<>();
        policy.setTitle(i18n("launcher.background.load_policy"));
        policy.setConverter(choice -> i18n("launcher.background.load_policy."
                + Objects.requireNonNullElse(choice, BackgroundLoadPolicy.WAIT_FOR_BACKGROUND)
                .name().toLowerCase(Locale.ROOT)));
        policy.setItems(List.of(BackgroundLoadPolicy.WAIT_FOR_BACKGROUND,
                BackgroundLoadPolicy.SHOW_FALLBACK_WHILE_LOADING));
        policy.setValue(settings().backgroundLoadPolicyProperty().get());
        policy.valueProperty().addListener((observable, was, value) -> {
            if (value != null && value != was) {
                settings().backgroundLoadPolicyProperty().set(value);
            }
        });

        ComponentList list = new ComponentList();
        list.getContent().addAll(cache, fallback, policy);
        return list;
    }

    /// Builds the window section.
    ///
    /// @return the assembled component list
    private ComponentList buildWindowList() {
        LineToggleButton transparentTitleBar = new LineToggleButton();
        transparentTitleBar.setTitle(i18n("settings.launcher.title_transparent"));
        transparentTitleBar.setSelected(settings().titleBarTransparentProperty().get());
        transparentTitleBar.selectedProperty().addListener((observable, oldValue, newValue) -> {
            settings().getThemeAppearanceOverrides().add(LauncherSettings.THEME_APPEARANCE_TITLE_BAR_TRANSPARENT);
            settings().titleBarTransparentProperty().set(newValue);
        });

        LineToggleButton transparentWindow = new LineToggleButton();
        transparentWindow.setTitle(i18n("settings.launcher.window_transparent"));
        transparentWindow.setSelected(settings().windowTransparentProperty().get());
        transparentWindow.selectedProperty().addListener((observable, oldValue, newValue) -> {
            settings().getThemeAppearanceOverrides().add(LauncherSettings.THEME_APPEARANCE_WINDOW_TRANSPARENT);
            settings().windowTransparentProperty().set(newValue);
        });

        ComponentList list = new ComponentList();
        list.getContent().addAll(transparentTitleBar, transparentWindow);
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
        animations.setTitle(i18n("settings.launcher.turn_off_animations"));
        animations.setSubtitle(i18n("settings.take_effect_after_restart"));
        // The row asks what the original asks — whether to turn the animations off — so the switch
        // reads the same way round as its own name.
        animations.setSelected(settings().isAnimationDisabled());
        animations.selectedProperty().addListener((observable, was, value) ->
                settings().animationDisabledProperty().set(value));

        list.getContent().add(animations);
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

        LineTextPane launcherRow = new LineTextPane();
        launcherRow.setTitle(i18n("settings.launcher.font"));
        // The line under the name is the font's own name, drawn in it: the original
        // shows what the choice looks like rather than a sentence about it.
        launcherRow.setSubtitle(Objects.toString(
                settings().launcherFontFamilyProperty().get(), systemFontFamily()));

        FontComboBox font = new FontComboBox();
        font.setValue(settings().launcherFontFamilyProperty().get());
        FXUtils.onChangeAndOperate(font.valueProperty(), FontManager::setFontFamily);
        FXUtils.onChangeAndOperate(font.valueProperty(), value -> launcherRow.setSubtitle(
                Objects.toString(value, systemFontFamily())));

        JFXButton reset = FXUtils.newToggleButton4(SVG.RESTORE);
        FXUtils.installFastTooltip(reset, i18n("button.reset"));
        reset.setOnAction(event -> font.setValue(null));

        HBox controls = new HBox(8, font, reset);
        controls.setAlignment(Pos.CENTER_RIGHT);
        launcherRow.setRowTrailing(controls);
        list.getContent().add(launcherRow);

        list.getContent().add(buildLogFontRow());

        // The original keeps the anti-aliasing row in the same card, last: it is a choice
        // about how text is drawn, which is what the rest of the card is about.
        LineSelectButton<org.jackhuang.hmcl.setting.FontAntiAliasing> antiAliasing =
                new LineSelectButton<>();
        antiAliasing.setTitle(i18n("settings.launcher.font.anti_aliasing"));
        antiAliasing.setSubtitle(i18n("settings.take_effect_after_restart"));
        antiAliasing.setItems(List.of(org.jackhuang.hmcl.setting.FontAntiAliasing.values()));
        antiAliasing.setNullSafeConverter(choice -> i18n(Objects
                .requireNonNullElse(choice, org.jackhuang.hmcl.setting.FontAntiAliasing.AUTO).i18nKey()));
        antiAliasing.setValue(settings().fontAntiAliasing());
        antiAliasing.valueProperty().addListener((observable, was, value) -> {
            if (value != null && value != was) {
                settings().fontAntiAliasingProperty().set(value);
            }
        });
        list.getContent().add(antiAliasing);
        return list;
    }

    /// Builds the row for the log's own font and size.
    ///
    /// The log has a font and a size of its own, and both settings have existed all along
    /// with nothing on the page to reach them. The size is a spinner rather than a box to
    /// type into, which is what the original uses for a number with bounds.
    ///
    /// @return the row
    private LineTextPane buildLogFontRow() {
        LineTextPane row = new LineTextPane();
        row.setTitle(i18n("dsh.settings.font.log"));

        FontComboBox font = new FontComboBox();
        font.setValue(settings().logFontFamilyProperty().get());
        font.valueProperty().addListener((observable, was, value) ->
                settings().logFontFamilyProperty().set(value == null ? "" : value));

        javafx.scene.control.Spinner<Double> size =
                new javafx.scene.control.Spinner<>(6.0, 72.0, logFontSize(), 1.0);
        size.setEditable(true);
        FXUtils.setLimitWidth(size, 90);
        size.valueProperty().addListener((observable, was, value) -> {
            if (value != null) {
                settings().logFontSizeProperty().set(value);
            }
        });

        JFXButton reset = FXUtils.newToggleButton4(SVG.RESTORE);
        FXUtils.installFastTooltip(reset, i18n("button.reset"));
        reset.setOnAction(event -> {
            font.setValue(null);
            size.getValueFactory().setValue(12.0);
        });

        HBox controls = new HBox(8, font, size, reset);
        controls.setAlignment(Pos.CENTER_RIGHT);
        row.setRowTrailing(controls);
        return row;
    }

    /// Returns the log font size in force.
    ///
    /// @return the size, or the default when none was chosen
    private static double logFontSize() {
        Double stored = settings().logFontSizeProperty().get();
        return stored == null || stored <= 0 ? 12.0 : stored;
    }

    /// Returns the font the interface uses when the user has chosen none.
    ///
    /// @return the family's name
    private static String systemFontFamily() {
        return Objects.toString(FontManager.getFontFamily(), "");
    }

    /// Returns the network-background cache policy in force.
    ///
    /// @return the policy, or the default when none was chosen
    private static NetworkBackgroundImageCachePolicy cachePolicy() {
        return Objects.requireNonNullElse(
                settings().networkBackgroundImageCachePolicyProperty().get(),
                NetworkBackgroundImageCachePolicy.ENABLED);
    }

    /// Returns the brightness mode the theme resolves to when the user has not chosen one.
    ///
    /// @return the mode's identifier
    private String resolvedBrightnessMode() {
        try {
            return Objects.toString(
                    ThemePackManager.resolveCurrentThemeBrightness(ThemePackManager.currentResolveContext()),
                    "auto");
        } catch (IOException | RuntimeException e) {
            return "auto";
        }
    }

    /// Returns the name of the theme in force.
    ///
    /// @return the theme's display name, or a note that the pack is missing
    private static String selectedThemeTitle() {
        ThemeReference reference = settings().getSelectedThemeOrDefault();
        try {
            ThemePackManager.InstalledThemePack pack = ThemePackManager.findInstalled(reference);
            if (pack == null) {
                return i18n("theme_pack.current.missing");
            }
            // The manifest's name is localized text, so it is rendered in the launcher's
            // language rather than shown as whatever the pack happens to list first.
            String packName = Objects.requireNonNullElse(
                    ThemePackManagementPage.text(pack.manifest().name()), pack.manifest().id());
            if (reference.themeId() == null) {
                return packName;
            }
            for (Theme theme : pack.manifest().themes()) {
                if (theme.id().equals(reference.themeId())) {
                    return packName + " / " + Objects.requireNonNullElse(
                            ThemePackManagementPage.text(theme.name()), theme.id());
                }
            }
            return i18n("theme_pack.current.missing");
        } catch (IOException e) {
            LOG.warning("Failed to read the installed theme packs", e);
            return i18n("theme_pack.current.missing");
        }
    }

    /// Returns the colour styles this build of the theme library offers.
    ///
    /// The list is the original's, minus whatever the library here does not have: a name
    /// the library does not know is skipped rather than failing, because a row with fewer
    /// choices is better than a launcher that will not start.
    ///
    /// @return the styles
    private static List<org.glavo.monetfx.ColorStyle> availableColorStyles() {
        List<org.glavo.monetfx.ColorStyle> styles = new ArrayList<>();
        addStyle(styles, "FIDELITY");
        addStyle(styles, "TONAL_SPOT");
        addStyle(styles, "VIBRANT");
        addStyle(styles, "NEUTRAL");
        addStyle(styles, "FRUIT_SALAD");
        addStyle(styles, "RAINBOW");
        return styles;
    }

    /// Adds a colour style by name, when this build has it.
    ///
    /// @param styles where to add it
    /// @param name   the style's name
    private static void addStyle(List<org.glavo.monetfx.ColorStyle> styles, String name) {
        try {
            styles.add(org.glavo.monetfx.ColorStyle.valueOf(name));
        } catch (IllegalArgumentException e) {
            LOG.info("This build has no colour style named " + name);
        }
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

    /// Builds the original's small mark that says whether a row follows the theme.
    ///
    /// The theme engine keeps a set of keys naming the values the user took over, and this
    /// is the control for that set: pressing it takes the value over — starting from what
    /// the theme resolves to, so taking it over changes nothing until the value is changed
    /// — and pressing it again hands the value back. Without it a row that follows the
    /// theme can only be changed by changing it, which silently makes it the user's.
    ///
    /// @param key      the override key this mark stands for
    /// @param property the value the key governs
    /// @param theme    what the theme resolves the value to
    /// @param <T>      the value's type
    /// @return the mark
    private static <T> JFXButton createOverrideMark(
            String key, Property<T> property, Supplier<T> theme) {
        JFXButton mark = new JFXButton();
        mark.getStyleClass().add(OVERRIDE_BUTTON_STYLE_CLASS);
        mark.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        Tooltip tooltip = new Tooltip();
        mark.getProperties().put(OVERRIDE_TOOLTIP_KEY, tooltip);
        FXUtils.installFastTooltip(mark, tooltip);

        Runnable refresh = () -> {
            boolean overridden = settings().getThemeAppearanceOverrides().contains(key);
            mark.setGraphic((overridden ? SVG.EDIT : SVG.STYLE).createIcon(15));
            mark.pseudoClassStateChanged(OVERRIDDEN, overridden);
            tooltip.setText(i18n(overridden
                    ? "theme_pack.appearance.custom.tooltip"
                    : "theme_pack.appearance.follow_theme.tooltip"));
        };

        mark.setOnAction(event -> {
            if (settings().getThemeAppearanceOverrides().contains(key)) {
                settings().getThemeAppearanceOverrides().remove(key);
            } else {
                T resolved = theme.get();
                if (resolved != null) {
                    property.setValue(resolved);
                }
                settings().getThemeAppearanceOverrides().add(key);
            }
            refresh.run();
        });

        // Typed rather than a lambda: the set is observable both ways, and an untyped lambda
        // leaves the compiler choosing between an invalidation and a change listener.
        settings().getThemeAppearanceOverrides()
                .addListener((InvalidationListener) ignored -> refresh.run());
        refresh.run();
        return mark;
    }

    /// Binds a choice to one of the theme-appearance values.
    ///
    /// The row shows the user's value once the value is theirs and the theme's until then,
    /// which is what the mark beside it reports. Choosing an entry is what makes the value
    /// theirs — the original does the same, and it is the only way a dropdown can take a
    /// value over: there is nowhere else to press.
    ///
    /// @param row      the row
    /// @param key      the override key the row governs
    /// @param property the value the key governs
    /// @param theme    what the theme resolves the value to
    /// @param <T>      the value's type
    private static <T> void originalBinding(
            LineSelectButton<T> row, String key, Property<T> property, Supplier<T> theme) {
        JFXButton mark = createOverrideMark(key, property, theme);
        row.setTitleTrailing(mark);

        // A guard: writing the resolved value into the property fires the row's own listener,
        // and without this the refresh would be read as the user having chosen something.
        boolean[] updating = {false};
        InvalidationListener refresh = ignored -> {
            if (updating[0]) {
                return;
            }
            updating[0] = true;
            try {
                boolean overridden = settings().getThemeAppearanceOverrides().contains(key);
                T shown = overridden ? property.getValue() : theme.get();
                if (shown != null) {
                    row.setValue(shown);
                }
            } finally {
                updating[0] = false;
            }
        };

        row.valueProperty().addListener((observable, was, value) -> {
            if (updating[0] || value == null) {
                return;
            }
            updating[0] = true;
            try {
                property.setValue(value);
                settings().getThemeAppearanceOverrides().add(key);
            } finally {
                updating[0] = false;
            }
        });

        property.addListener(refresh);
        settings().getThemeAppearanceOverrides().addListener(refresh);
        settings().selectedThemeProperty().addListener(refresh);
        refresh.invalidated(null);
    }

    /// Saves what the launcher looks like now as a theme-pack file.
    ///
    /// The original asks for the pack's name, its version and the author before asking
    /// where to put the file. This launcher's dialog helper takes one answer at a time, so
    /// the same three are asked in the same order, one after another, each starting at the
    /// default the original would have shown.
    private void exportCurrentThemePack() {
        String defaultPackName = i18n("theme_pack.export.name");
        String defaultAuthor = Objects.toString(System.getProperty("user.name"), "Unknown");

        // What the answers are kept in while the three dialogs run one after another: the
        // helper completes its future with the field's text, so what a later dialog wants to
        // know about an earlier one has to be held here.
        String[] answers = {defaultPackName, ThemePackManager.CURRENT_THEME_PACK_VERSION, defaultAuthor};

        Controllers.prompt(i18n("theme_pack.export.name"), (value, handler) -> {
            answers[0] = value == null || value.isBlank() ? defaultPackName : value.trim();
            handler.resolve();
        }, defaultPackName).thenCompose(ignored ->
                Controllers.prompt(i18n("theme_pack.export.version"), (value, handler) -> {
                    answers[1] = value == null || value.isBlank()
                            ? ThemePackManager.CURRENT_THEME_PACK_VERSION : value.trim();
                    handler.resolve();
                }, ThemePackManager.CURRENT_THEME_PACK_VERSION)
        ).thenCompose(ignored ->
                Controllers.prompt(i18n("theme_pack.export.author"), (value, handler) -> {
                    answers[2] = value == null || value.isBlank() ? defaultAuthor : value.trim();
                    handler.resolve();
                }, defaultAuthor)
        ).thenAccept(ignored -> writeThemePack("hdsl-theme", answers[1], answers[0], answers[2]))
                .exceptionally(failure -> {
                    // Closing a dialog is not a failure to report.
                    LOG.info("Theme-pack export was cancelled");
                    return null;
                });
    }

    /// Asks where to put the theme pack and writes it.
    ///
    /// @param packId  the pack's identifier
    /// @param version the pack's version
    /// @param name    the pack's name
    /// @param author  the pack's author
    private void writeThemePack(String packId, String version, String name, String author) {
        javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
        chooser.setTitle(i18n("theme_pack.export.title"));
        chooser.setInitialFileName(name + org.jackhuang.hmcl.theme.ThemePackExporter.FILE_EXTENSION);
        chooser.getExtensionFilters().setAll(new javafx.stage.FileChooser.ExtensionFilter(
                i18n("theme_pack.file"),
                "*" + org.jackhuang.hmcl.theme.ThemePackExporter.FILE_EXTENSION));

        @Nullable java.nio.file.Path chosen = Controllers.showSaveDialog(chooser);
        if (chosen == null) {
            return;
        }
        java.nio.file.Path output = chosen;
        String fileName = output.getFileName().toString();
        if (!fileName.toLowerCase(Locale.ROOT)
                .endsWith(org.jackhuang.hmcl.theme.ThemePackExporter.FILE_EXTENSION)) {
            output = output.resolveSibling(fileName + org.jackhuang.hmcl.theme.ThemePackExporter.FILE_EXTENSION);
        }
        try {
            ThemePackManager.exportCurrent(output, packId, version, name, author);
            Controllers.dialog(i18n("theme_pack.export.success", output),
                    i18n("message.success"),
                    org.jackhuang.hmcl.ui.construct.MessageDialogPane.MessageType.SUCCESS);
        } catch (IOException | RuntimeException e) {
            LOG.warning("Failed to export the theme pack", e);
            Controllers.dialog(i18n("theme_pack.export.failed") + "\n" + e,
                    i18n("message.error"),
                    org.jackhuang.hmcl.ui.construct.MessageDialogPane.MessageType.ERROR);
        }
    }

    /// A choice whose right-hand side is a colour picker.
    ///
    /// A flat colour cannot be offered as an ordinary entry: the colour is the answer as
    /// well as the choice, so the picker belongs on the entry's own line.
    private static final class PaintChoice extends RadioChoiceList.Choice<BackgroundType> {
        /// The picker owned by this entry.
        private final JFXColorPicker picker = new JFXColorPicker();

        /// Creates the entry.
        ///
        /// @param title    the entry's name
        /// @param value    the background source it selects
        /// @param property the colour it edits
        private PaintChoice(String title, BackgroundType value, Property<Paint> property) {
            super(title, value);
            FXUtils.bindPaint(picker, property);
            // Choosing the colour is choosing this source, which is what somebody who
            // opens the picker means.
            picker.valueProperty().addListener((observable, was, color) -> {
                if (color != null) {
                    settings().getThemeAppearanceOverrides()
                            .add(LauncherSettings.THEME_APPEARANCE_BACKGROUND);
                }
            });
        }

        @Override
        protected Node createRightNode() {
            return picker;
        }
    }
}
