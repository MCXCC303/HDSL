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

import com.jfoenix.controls.JFXButton;
import com.jfoenix.controls.JFXColorPicker;
import com.jfoenix.controls.JFXComboBox;
import com.jfoenix.controls.JFXSlider;
import javafx.beans.InvalidationListener;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.StringBinding;
import javafx.beans.property.Property;
import javafx.css.PseudoClass;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Paint;
import javafx.stage.FileChooser;
import org.glavo.monetfx.ColorStyle;
import org.jackhuang.hmcl.setting.*;
import org.jackhuang.hmcl.theme.*;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.construct.*;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Supplier;

import static org.jackhuang.hmcl.setting.SettingsManager.settings;
import static org.jackhuang.hmcl.util.i18n.I18n.i18n;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/**
 * The "appearance" tab of the launcher settings page.
 * <p>
 * Controls themes, color palettes, window appearance, background images, animations, and fonts.
 */
@NotNullByDefault
public final class AppearanceSettingsPage extends ScrollPane {
	private static final List<String> BRIGHTNESS_MODES = List.of("auto", "light", "dark");
	private static final String OVERRIDE_BUTTON_STYLE_CLASS = "toggle-icon-tiny";
	private static final PseudoClass OVERRIDDEN = PseudoClass.getPseudoClass("overridden");
	private static final String OVERRIDE_TOOLTIP_KEY = "HDSL.themeAppearanceOverrideTooltip";

	/**
	 * Creates the appearance settings tab.
	 */
	public AppearanceSettingsPage() {
		setFitToWidth(true);
		setFitToHeight(true);

		VBox root = new VBox(10);
		root.setPadding(new Insets(10));
		setContent(root);

		FXUtils.smoothScrolling(this);

		root.getChildren()
		    .addAll(sectionTitle(i18n("settings.launcher.theme")),
		            buildThemeList(),
		            sectionTitle(i18n("settings.launcher.appearance")),
		            buildAppearanceList(),
		            sectionTitle(i18n("launcher.background.loading")),
		            buildBackgroundLoadingList(),
		            sectionTitle(i18n("settings.launcher.animation")),
		            buildAnimationList(),
		            sectionTitle(i18n("settings.launcher.fonts")),
		            buildFontList());
	}

	private static Node sectionTitle(String text) {
		return ComponentList.createComponentListTitle(text);
	}

	private static double logFontSize() {
		Double stored = settings().logFontSizeProperty().get();
		return stored == null || stored <= 0 ? 12.0 : stored;
	}

	private static String systemFontFamily() {
		return Objects.toString(FontManager.getFontFamily(), "");
	}

	private static NetworkBackgroundImageCachePolicy cachePolicy() {
		return Objects.requireNonNullElse(settings().networkBackgroundImageCachePolicyProperty().get(),
		                                  NetworkBackgroundImageCachePolicy.ENABLED);
	}

	private static String selectedThemeTitle() {
		ThemeReference reference = settings().getSelectedThemeOrDefault();
		try {
			ThemePackManager.InstalledThemePack pack = ThemePackManager.findInstalled(reference);
			if (pack == null) {
				return i18n("theme_pack.current.missing");
			}
			String packName = Objects.requireNonNullElse(ThemePackManagementPage.text(pack.manifest().name()),
			                                             pack.manifest().id());
			if (reference.themeId() == null) {
				return packName;
			}
			for (Theme theme : pack.manifest().themes()) {
				if (theme.id().equals(reference.themeId())) {
					return packName + " / " +
					       Objects.requireNonNullElse(ThemePackManagementPage.text(theme.name()), theme.id());
				}
			}
			return i18n("theme_pack.current.missing");
		} catch (IOException e) {
			LOG.warning("Failed to read the installed theme packs", e);
			return i18n("theme_pack.current.missing");
		}
	}

	private static List<ColorStyle> availableColorStyles() {
		List<ColorStyle> styles = new ArrayList<>();
		addStyle(styles, "FIDELITY");
		addStyle(styles, "TONAL_SPOT");
		addStyle(styles, "VIBRANT");
		addStyle(styles, "NEUTRAL");
		addStyle(styles, "FRUIT_SALAD");
		addStyle(styles, "RAINBOW");
		return styles;
	}

	private static void addStyle(List<ColorStyle> styles, String name) {
		try {
			styles.add(ColorStyle.valueOf(name));
		} catch (IllegalArgumentException e) {
			LOG.info("This build has no colour style named " + name);
		}
	}

	private static void selectBackground(BackgroundType type) {
		settings().getThemeAppearanceOverrides().add(LauncherSettings.THEME_APPEARANCE_BACKGROUND);
		settings().backgroundTypeProperty().set(type);
	}

	/**
	 * Builds an override button that toggles between theme defaults and user overrides.
	 *
	 * @param key      the override settings key
	 * @param property the governed property
	 * @param theme    supplier for the theme's resolved value
	 * @param <T>      the value type
	 * @return the override button node
	 */
	private static <T> JFXButton createOverrideMark(String key, Property<T> property, Supplier<T> theme) {
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

		settings().getThemeAppearanceOverrides().addListener((InvalidationListener) ignored -> refresh.run());
		refresh.run();
		return mark;
	}

	/**
	 * Binds a selection control to a theme appearance property with user override tracking.
	 *
	 * @param row      the selector row
	 * @param key      the override settings key
	 * @param property the governed property
	 * @param theme    supplier for the theme's resolved value
	 * @param <T>      the value type
	 */
	private static <T> void originalBinding(LineSelectButton<T> row,
	                                        String key,
	                                        Property<T> property,
	                                        Supplier<T> theme) {
		JFXButton mark = createOverrideMark(key, property, theme);
		row.setTitleTrailing(mark);

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

	private ComponentList buildThemeList() {
		LineButton currentTheme = LineButton.createNavigationButton();
		currentTheme.setTitle(i18n("theme_pack.theme"));
		currentTheme.setSubtitle(selectedThemeTitle());
		FXUtils.onChangeAndOperate(settings().selectedThemeProperty(),
		                           ignored -> currentTheme.setSubtitle(selectedThemeTitle()));
		currentTheme.setOnAction(event -> Controllers.navigate(new ThemePackManagementPage()));

		LineButton export = new LineButton();
		export.setTitle(i18n("theme_pack.export"));
		export.setSubtitle(i18n("theme_pack.export.subtitle"));
		export.setTrailingIcon(SVG.ARCHIVE);
		export.setOnAction(event -> exportCurrentThemePack());

		ComponentList list = new ComponentList();
		list.getContent().addAll(currentTheme, export);
		return list;
	}

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

	private LineSelectButton<String> buildBrightnessRow() {
		LineSelectButton<String> brightness = new LineSelectButton<>();
		brightness.setTitle(i18n("settings.launcher.brightness"));
		brightness.setItems(BRIGHTNESS_MODES);
		brightness.setNullSafeConverter(mode -> i18n("dsh.settings.theme.brightness." + mode));
		originalBinding(brightness,
		                LauncherSettings.THEME_APPEARANCE_BRIGHTNESS_MODE,
		                settings().themeBrightnessModeProperty(),
		                this::resolvedBrightnessMode);
		return brightness;
	}

	private ComponentSublist buildThemeColorRow() {
		ComponentSublist sublist = new ComponentSublist();
		sublist.setTitle(i18n("settings.launcher.theme_color"));
		sublist.setHasSubtitle(true);
		sublist.setTitleRight(createOverrideMark(LauncherSettings.THEME_APPEARANCE_COLOR,
		                                         settings().themeColorTypeProperty(),
		                                         () -> ThemeColorType.DEFAULT));

		ThemeColor currentCustom = Optional.ofNullable(settings().customThemeColorProperty().get())
		                                   .orElse(ThemeColor.DEFAULT);

		JFXColorPicker picker = new JFXColorPicker();
		picker.setValue(currentCustom.color());
		picker.valueProperty().addListener((observable, was, color) -> {
			if (color != null) {
				settings().customThemeColorProperty().set(new ThemeColor(currentCustom.name(), color));
				settings().getThemeAppearanceOverrides().add(LauncherSettings.THEME_APPEARANCE_COLOR);
				settings().themeColorTypeProperty().set(ThemeColorType.CUSTOM);
			}
		});

		RadioChoiceList.Choice<ThemeColorType> custom = new RadioChoiceList.Choice<>(i18n(
				"dsh.settings.theme_color.custom"), ThemeColorType.CUSTOM) {
			@Override
			protected Node createRightNode() {
				return picker;
			}
		};

		RadioChoiceList<ThemeColorType> choices = new RadioChoiceList<>();
		choices.setFallbackValue(ThemeColorType.DEFAULT);
		choices.setChoices(new RadioChoiceList.Choice<>(i18n("dsh.settings.theme_color.default"),
		                                                ThemeColorType.DEFAULT),
		                   new RadioChoiceList.Choice<>(i18n("dsh.settings.theme_color.system"), ThemeColorType.SYSTEM),
		                   custom,
		                   new RadioChoiceList.Choice<>(i18n("dsh.settings.theme_color.background"),
		                                                ThemeColorType.BACKGROUND));
		choices.setSelectedValue(Objects.requireNonNullElse(settings().themeColorTypeProperty().get(),
		                                                    ThemeColorType.DEFAULT));
		choices.selectedValueProperty().addListener((observable, was, value) -> {
			if (value != null && value != was) {
				settings().getThemeAppearanceOverrides().add(LauncherSettings.THEME_APPEARANCE_COLOR);
				settings().themeColorTypeProperty().set(value);
				SettingsManager.save();
			}
		});

		sublist.descriptionProperty().bind(Bindings.createStringBinding(() -> {
			ThemeColorType type = Objects.requireNonNullElse(choices.selectedValueProperty().get(),
			                                                 ThemeColorType.DEFAULT);
			return i18n("dsh.settings.theme_color." + type.name().toLowerCase(Locale.ROOT));
		}, choices.selectedValueProperty()));

		sublist.getContent().setAll(choices);
		return sublist;
	}

	private LineSelectButton<ColorStyle> buildColorStyleRow() {
		LineSelectButton<ColorStyle> colorStyle = new LineSelectButton<>();
		colorStyle.setTitle(i18n("settings.launcher.theme_color_style"));
		colorStyle.setConverter(style -> i18n("settings.launcher.theme_color_style." +
		                                      Objects.requireNonNullElse(style, ColorStyle.FIDELITY)
		                                             .name()
		                                             .toLowerCase(Locale.ROOT)));
		colorStyle.setDescriptionConverter(style -> i18n("settings.launcher.theme_color_style." +
		                                                 Objects.requireNonNullElse(style, ColorStyle.FIDELITY)
		                                                        .name()
		                                                        .toLowerCase(Locale.ROOT) + ".desc"));
		colorStyle.setItems(availableColorStyles());
		originalBinding(colorStyle,
		                LauncherSettings.THEME_APPEARANCE_COLOR_STYLE,
		                settings().themeColorStyleProperty(),
		                () -> ColorStyle.FIDELITY);
		return colorStyle;
	}

	private ComponentSublist buildBackgroundRow() {
		ComponentSublist sublist = new ComponentSublist();
		sublist.setTitle(i18n("launcher.background"));
		sublist.setHasSubtitle(true);
		Node overrideMark = createOverrideMark(LauncherSettings.THEME_APPEARANCE_BACKGROUND,
		                                       settings().backgroundTypeProperty(),
		                                       () -> BackgroundType.DEFAULT);
		sublist.setTitleRight(overrideMark);

		JFXComboBox<String> builtin = new JFXComboBox<>();
		builtin.getItems().setAll(BuiltinBackground.BUILTIN_BACKGROUND_IDS);
		FXUtils.setLimitWidth(builtin, 160);
		builtin.setValue(Objects.requireNonNullElse(settings().builtinBackgroundIdProperty().get(),
		                                            BuiltinBackground.FALLBACK.id()));
		builtin.valueProperty().addListener((observable, was, value) -> {
			if (value != null) {
				settings().builtinBackgroundIdProperty().set(value);
				selectBackground(BackgroundType.BUILTIN);
			}
		});

		RadioChoiceList.FileChoice<BackgroundType> custom = new RadioChoiceList.FileChoice<>(i18n("settings.custom"),
		                                                                                     BackgroundType.CUSTOM).setChooserTitle(
				i18n("launcher.background.choose")).addExtensionFilter(FXUtils.getImageExtensionFilter());
		custom.setPath(Objects.toString(settings().customBackgroundImagePathProperty().get(), ""));
		custom.pathProperty().addListener((observable, was, value) -> {
			if (value != null && !value.isBlank()) {
				settings().customBackgroundImagePathProperty().set(value);
				selectBackground(BackgroundType.CUSTOM);
			}
		});

		RadioChoiceList.TextChoice<BackgroundType> network = new RadioChoiceList.TextChoice<>(i18n(
				"launcher.background.network"), BackgroundType.NETWORK);
		network.setText(Objects.toString(settings().networkBackgroundImageUrlProperty().get(), ""));
		network.textProperty().addListener((observable, was, value) -> {
			if (value != null && !value.isBlank()) {
				settings().networkBackgroundImageUrlProperty().set(value);
				selectBackground(BackgroundType.NETWORK);
			}
		});

		PaintChoice paint = new PaintChoice(i18n("launcher.background.paint"),
		                                    BackgroundType.PAINT,
		                                    settings().customBackgroundPaintProperty());

		RadioChoiceList<BackgroundType> background = new RadioChoiceList<>();
		background.setFallbackValue(BackgroundType.DEFAULT);
		background.setChoices(new RadioChoiceList.Choice<>(i18n("message.default"), BackgroundType.DEFAULT).setTooltip(
				                      i18n("launcher.background.default.tooltip")),
		                      new RadioChoiceList.Choice<>(i18n("launcher.background.builtin"),
		                                                   BackgroundType.BUILTIN) {
			                      @Override
			                      protected Node createRightNode() {
				                      return builtin;
			                      }
		                      },
		                      new RadioChoiceList.Choice<>(i18n("launcher.background.theme_color"),
		                                                   BackgroundType.THEME_COLOR),
		                      custom,
		                      network,
		                      paint);
		background.setSelectedValue(Objects.requireNonNullElse(settings().backgroundTypeProperty().get(),
		                                                       BackgroundType.DEFAULT));
		background.selectedValueProperty().addListener((observable, was, value) -> {
			if (value != null && value != was) {
				selectBackground(value);
			}
		});

		sublist.descriptionProperty().bind(Bindings.createStringBinding(() -> {
			                                                                BackgroundType type = Objects.requireNonNullElse(background.selectedValueProperty().get(),
			                                                                                                                 BackgroundType.DEFAULT);
			                                                                return switch (type) {
				                                                                case DEFAULT -> i18n("message.default");
				                                                                case THEME_COLOR -> i18n("launcher.background.theme_color");
				                                                                case BUILTIN -> Objects.requireNonNullElse(settings().builtinBackgroundIdProperty().get(),
				                                                                                                           BuiltinBackground.FALLBACK.id());
				                                                                case CUSTOM ->
						                                                                Objects.toString(settings().customBackgroundImagePathProperty().get(), i18n("settings.custom"));
				                                                                case NETWORK -> Objects.toString(settings().networkBackgroundImageUrlProperty().get(),
				                                                                                                 i18n("launcher.background.network"));
				                                                                case PAINT -> {
					                                                                Paint chosen = settings().customBackgroundPaintProperty().get();
					                                                                yield chosen != null ? chosen.toString() : i18n("launcher.background.paint");
				                                                                }
			                                                                };
		                                                                },
		                                                                background.selectedValueProperty(),
		                                                                settings().builtinBackgroundIdProperty(),
		                                                                settings().customBackgroundImagePathProperty(),
		                                                                settings().networkBackgroundImageUrlProperty(),
		                                                                settings().customBackgroundPaintProperty()));

		sublist.getContent().setAll(background);
		return sublist;
	}

	private LinePane buildOpacityRow() {
		LinePane opacity = new LinePane();
		opacity.setTitle(i18n("settings.launcher.background.settings.opacity"));
		opacity.setRight(buildOpacitySlider());
		return opacity;
	}

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
		StringBinding text = Bindings.createStringBinding(() -> ((int) slider.getValue()) + "%",
		                                                  slider.valueProperty());
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

	private ComponentList buildBackgroundLoadingList() {
		LineSwitch cache = new LineSwitch();
		cache.setTitle(i18n("launcher.background.network.cache"));
		cache.setSelected(cachePolicy() == NetworkBackgroundImageCachePolicy.ENABLED);
		cache.selectedProperty()
		     .addListener((observable, was, value) -> settings().networkBackgroundImageCachePolicyProperty()
		                                                        .set(Boolean.TRUE.equals(value)
		                                                             ? NetworkBackgroundImageCachePolicy.ENABLED
		                                                             : NetworkBackgroundImageCachePolicy.DISABLED));
		settings().networkBackgroundImageCachePolicyProperty()
		          .addListener((observable, was, value) -> cache.setSelected(
				          cachePolicy() == NetworkBackgroundImageCachePolicy.ENABLED));

		ComponentSublist fallback = new ComponentSublist();
		fallback.setTitle(i18n("launcher.background.fallback"));
		fallback.setHasSubtitle(true);

		RadioChoiceList<BackgroundType> fallbackItem = new RadioChoiceList<>();
		fallbackItem.setFallbackValue(BackgroundType.BUILTIN);
		fallbackItem.setChoices(new RadioChoiceList.Choice<>(i18n("launcher.background.fallback.builtin"),
		                                                     BackgroundType.BUILTIN),
		                        new RadioChoiceList.Choice<>(i18n("launcher.background.fallback.theme_color"),
		                                                     BackgroundType.THEME_COLOR),
		                        new PaintChoice(i18n("launcher.background.fallback.paint"),
		                                        BackgroundType.PAINT,
		                                        settings().backgroundFallbackPaintProperty()));
		fallbackItem.setSelectedValue(Objects.requireNonNullElse(settings().backgroundFallbackTypeProperty().get(),
		                                                         BackgroundType.BUILTIN));
		fallbackItem.selectedValueProperty().addListener((observable, was, value) -> {
			if (value != null && value != was) {
				settings().backgroundFallbackTypeProperty().set(value);
			}
		});
		fallback.descriptionProperty().bind(Bindings.createStringBinding(() -> {
			BackgroundType type = Objects.requireNonNullElse(fallbackItem.selectedValueProperty().get(),
			                                                 BackgroundType.BUILTIN);
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
		policy.setConverter(choice -> i18n("launcher.background.load_policy." +
		                                   Objects.requireNonNullElse(choice, BackgroundLoadPolicy.WAIT_FOR_BACKGROUND)
		                                          .name()
		                                          .toLowerCase(Locale.ROOT)));
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

	private ComponentList buildWindowList() {
		LineSwitch transparentTitleBar = new LineSwitch();
		transparentTitleBar.setTitle(i18n("settings.launcher.title_transparent"));
		transparentTitleBar.setSelected(settings().titleBarTransparentProperty().get());
		transparentTitleBar.selectedProperty().addListener((observable, oldValue, newValue) -> {
			settings().getThemeAppearanceOverrides().add(LauncherSettings.THEME_APPEARANCE_TITLE_BAR_TRANSPARENT);
			settings().titleBarTransparentProperty().set(newValue);
		});

		LineSwitch transparentWindow = new LineSwitch();
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

	private ComponentList buildAnimationList() {
		ComponentList list = new ComponentList();

		LineSwitch animations = new LineSwitch();
		animations.setTitle(i18n("settings.launcher.turn_off_animations"));
		animations.setSubtitle(i18n("settings.take_effect_after_restart"));
		animations.setSelected(settings().isAnimationDisabled());
		animations.selectedProperty()
		          .addListener((observable, was, value) -> settings().animationDisabledProperty().set(value));

		list.getContent().add(animations);
		return list;
	}

	private ComponentList buildFontList() {
		ComponentList list = new ComponentList();

		LineTextPane launcherRow = new LineTextPane();
		launcherRow.setTitle(i18n("settings.launcher.font"));
		launcherRow.setSubtitle(Objects.toString(settings().launcherFontFamilyProperty().get(), systemFontFamily()));

		FontComboBox font = new FontComboBox();
		font.setValue(settings().launcherFontFamilyProperty().get());
		FXUtils.onChangeAndOperate(font.valueProperty(), FontManager::setFontFamily);
		FXUtils.onChangeAndOperate(font.valueProperty(),
		                           value -> launcherRow.setSubtitle(Objects.toString(value, systemFontFamily())));

		JFXButton reset = FXUtils.newToggleButton4(SVG.RESTORE);
		FXUtils.installFastTooltip(reset, i18n("button.reset"));
		reset.setOnAction(event -> font.setValue(null));

		HBox controls = new HBox(8, font, reset);
		controls.setAlignment(Pos.CENTER_RIGHT);
		launcherRow.setRowTrailing(controls);
		list.getContent().add(launcherRow);

		list.getContent().add(buildLogFontRow());

		LineSelectButton<FontAntiAliasing> antiAliasing = new LineSelectButton<>();
		antiAliasing.setTitle(i18n("settings.launcher.font.anti_aliasing"));
		antiAliasing.setSubtitle(i18n("settings.take_effect_after_restart"));
		antiAliasing.setItems(List.of(FontAntiAliasing.values()));
		antiAliasing.setNullSafeConverter(choice -> i18n(Objects.requireNonNullElse(choice, FontAntiAliasing.AUTO)
		                                                        .i18nKey()));
		antiAliasing.setValue(settings().fontAntiAliasing());
		antiAliasing.valueProperty().addListener((observable, was, value) -> {
			if (value != null && value != was) {
				settings().fontAntiAliasingProperty().set(value);
			}
		});
		list.getContent().add(antiAliasing);
		return list;
	}

	private LineTextPane buildLogFontRow() {
		LineTextPane row = new LineTextPane();
		row.setTitle(i18n("dsh.settings.font.log"));

		FontComboBox font = new FontComboBox();
		font.setValue(settings().logFontFamilyProperty().get());
		font.valueProperty()
		    .addListener((observable, was, value) -> settings().logFontFamilyProperty()
		                                                       .set(value == null ? "" : value));

		Spinner<Double> size = new Spinner<>(6.0, 72.0, logFontSize(), 1.0);
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

	private String resolvedBrightnessMode() {
		try {
			return Objects.toString(ThemePackManager.resolveCurrentThemeBrightness(ThemePackManager.currentResolveContext()),
			                        "auto");
		} catch (IOException | RuntimeException e) {
			return "auto";
		}
	}

	private void exportCurrentThemePack() {
		String defaultPackName = i18n("theme_pack.export.name");
		String defaultAuthor = Objects.toString(System.getProperty("user.name"), "Unknown");

		String[] answers = {defaultPackName, ThemePackManager.CURRENT_THEME_PACK_VERSION, defaultAuthor};

		Controllers.prompt(i18n("theme_pack.export.name"), (value, handler) -> {
			           answers[0] = value == null || value.isBlank() ? defaultPackName : value.trim();
			           handler.resolve();
		           }, defaultPackName)
		           .thenCompose(ignored -> Controllers.prompt(i18n("theme_pack.export.version"), (value, handler) -> {
			           answers[1] = value == null || value.isBlank()
			                        ? ThemePackManager.CURRENT_THEME_PACK_VERSION
			                        : value.trim();
			           handler.resolve();
		           }, ThemePackManager.CURRENT_THEME_PACK_VERSION))
		           .thenCompose(ignored -> Controllers.prompt(i18n("theme_pack.export.author"), (value, handler) -> {
			           answers[2] = value == null || value.isBlank() ? defaultAuthor : value.trim();
			           handler.resolve();
		           }, defaultAuthor))
		           .thenAccept(ignored -> writeThemePack("hdsl-theme", answers[1], answers[0], answers[2]))
		           .exceptionally(failure -> {
			           LOG.info("Theme-pack export was cancelled");
			           return null;
		           });
	}

	private void writeThemePack(String packId, String version, String name, String author) {
		FileChooser chooser = new FileChooser();
		chooser.setTitle(i18n("theme_pack.export.title"));
		chooser.setInitialFileName(name + ThemePackExporter.FILE_EXTENSION);
		chooser.getExtensionFilters()
		       .setAll(new FileChooser.ExtensionFilter(i18n("theme_pack.file"),
		                                               "*" + ThemePackExporter.FILE_EXTENSION));

		@Nullable Path chosen = Controllers.showSaveDialog(chooser);
		if (chosen == null) {
			return;
		}
		Path output = chosen;
		String fileName = output.getFileName().toString();
		if (!fileName.toLowerCase(Locale.ROOT).endsWith(ThemePackExporter.FILE_EXTENSION)) {
			output = output.resolveSibling(fileName + ThemePackExporter.FILE_EXTENSION);
		}
		try {
			ThemePackManager.exportCurrent(output, packId, version, name, author);
			Controllers.dialog(i18n("theme_pack.export.success", output),
			                   i18n("message.success"),
			                   MessageDialogPane.MessageType.SUCCESS);
		} catch (IOException | RuntimeException e) {
			LOG.warning("Failed to export the theme pack", e);
			Controllers.dialog(i18n("theme_pack.export.failed") + "\n" + e,
			                   i18n("message.error"),
			                   MessageDialogPane.MessageType.ERROR);
		}
	}

	/**
	 * Choice item presenting a color picker on the right.
	 */
	private static final class PaintChoice extends RadioChoiceList.Choice<BackgroundType> {
		private final JFXColorPicker picker = new JFXColorPicker();

		private PaintChoice(String title, BackgroundType value, Property<Paint> property) {
			super(title, value);
			FXUtils.bindPaint(picker, property);
			picker.valueProperty().addListener((observable, was, color) -> {
				if (color != null) {
					settings().getThemeAppearanceOverrides().add(LauncherSettings.THEME_APPEARANCE_BACKGROUND);
				}
			});
		}

		@Override
		protected Node createRightNode() {
			return picker;
		}
	}
}
