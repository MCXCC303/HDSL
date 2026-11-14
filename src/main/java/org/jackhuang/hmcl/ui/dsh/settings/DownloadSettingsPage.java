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

import com.jfoenix.controls.*;
import com.mcxcc303.hdsl.launch.DshProxyMode;
import com.mcxcc303.hdsl.plugin.CatalogClient;
import com.mcxcc303.hdsl.plugin.CatalogSources;
import com.mcxcc303.hdsl.plugin.MarketSource;
import com.mcxcc303.hdsl.runtime.node.NodeSource;
import com.mcxcc303.hdsl.skill.DshSkillSource;
import com.mcxcc303.hdsl.skill.DshSkillSources;
import javafx.beans.binding.Bindings;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.*;
import org.jackhuang.hmcl.setting.SettingsManager;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.construct.*;
import org.jackhuang.hmcl.util.i18n.I18n;
import org.jackhuang.hmcl.util.javafx.SafeStringConverter;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

import static org.jackhuang.hmcl.setting.SettingsManager.settings;
import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

// Where the launcher fetches what it downloads
@NotNullByDefault
public final class DownloadSettingsPage extends ScrollPane {
	// Creates the page.
	public DownloadSettingsPage() {
		setFitToWidth(true);
		setFitToHeight(true);

		VBox root = new VBox(ComponentList.createComponentListTitle(i18n("settings.launcher.download_source")),
		                     buildSourceList(),
		                     ComponentList.createComponentListTitle(i18n("settings.launcher.download")),
		                     buildDownloadList(),
		                     ComponentList.createComponentListTitle(i18n("dsh.settings.proxy")),
		                     buildProxyList());
		root.getStyleClass().add("card-list");
		setContent(root);

		// After the content: smooth scrolling binds to it and throws on null.
		FXUtils.smoothScrolling(this);
	}

	/**
	 * Returns the proxy mode in force.
	 *
	 * @return proxy mode
	 */
	private static DshProxyMode currentProxyMode() {
		return Objects.requireNonNullElse(settings().proxyModeProperty().get(), DshProxyMode.SYSTEM);
	}

	/**
	 * Returns the string key for one proxy mode.
	 *
	 * @param mode the mode
	 * @return the key
	 */
	private static String proxyModeKey(DshProxyMode mode) {
		return switch (mode) {
			case SYSTEM -> "settings.launcher.proxy.default";
			case NONE -> "settings.launcher.proxy.none";
			case HTTP -> "settings.launcher.proxy.http";
			case SOCKS -> "settings.launcher.proxy.socks";
		};
	}

	/**
	 * Builds a two-row form
	 *
	 * @param firstLabel  the first row's name
	 * @param firstField  the first row's field
	 * @param secondLabel the second row's name
	 * @param secondField the second row's field
	 * @return the grid
	 */
	private static GridPane formGrid(Node firstLabel, Node firstField, Node secondLabel, Node secondField) {
		ColumnConstraints grow = new ColumnConstraints();
		grow.setHgrow(Priority.ALWAYS);

		GridPane grid = new GridPane();
		grid.setPadding(new Insets(0, 0, 0, 30));
		grid.setHgap(20);
		grid.setVgap(10);
		grid.getColumnConstraints().setAll(new ColumnConstraints(), grow);
		grid.addRow(0, firstLabel, firstField);
		grid.addRow(1, secondLabel, secondField);
		return grid;
	}

	/**
	 * Returns the labels the source row shows for its options.
	 *
	 * @return the converter
	 */
	private static Function<NodeSource, String> sourceName() {
		return source -> switch (source) {
			case OFFICIAL -> i18n("download.provider.official");
			case MIRROR -> i18n("download.provider.mirror");
		};
	}

	/**
	 * Returns the line each option carries in the list the row opens.
	 */
	private static Function<NodeSource, String> sourceDescription() {
		return source -> {
			String key = switch (source) {
				case OFFICIAL -> "download.provider.official.desc";
				case MIRROR -> "download.provider.mirror.desc";
			};
			return I18n.hasKey(key) ? i18n(key) : null;
		};
	}

	private static Function<DshSkillSources, String> skillSourceDescription() {
		return source -> {
			String key = switch (source) {
				case SKILLS_SH -> "dsh.skills.source.skills_sh.desc";
				case SKILLS_MP -> "dsh.skills.source.skills_mp.desc";
			};
			return I18n.hasKey(key) ? i18n(key) : null;
		};
	}

	/**
	 * Download sources, including NPM/Skill/Plugin indexes
	 *
	 * @return the assembled component list
	 */
	private ComponentList buildSourceList() {
		ComponentList list = new ComponentList();

		LineSelectButton<NodeSource> source = new LineSelectButton<>();
		source.setTitle(i18n("dsh.settings.npm_source"));
		source.setNullSafeConverter(sourceName());
		source.setDescriptionConverter(sourceDescription());
		source.setItems(NodeSource.values());
		source.setValue(settings().nodeSourceProperty().get());
		source.valueProperty().addListener((observable, was, value) -> {
			if (value != was) {
				settings().nodeSourceProperty().set(value);
				SettingsManager.save();
			}
		});
		list.getContent().add(source);
		list.getContent().add(buildMarketRow());
		list.getContent().add(buildSkillRow());

		return list;
	}

	/**
	 * @return the market choose row
	 */
	private Node buildMarketRow() {
		LineSelectButton<MarketSource> source = new LineSelectButton<>();
		source.setTitle(i18n("dsh.settings.market_source"));
		source.setNullSafeConverter(MarketSource::label);
		source.setItems(List.of(MarketSource.values()));
		source.setValue(CatalogSources.marketSource());
		source.valueProperty().addListener((observable, was, value) -> {
			if (value != was) {
				CatalogSources.setMarketSource(value);
			}
		});
		return source;
	}

	private Node buildSkillRow() {
		LineSelectButton<DshSkillSources> source = new LineSelectButton<>();
		source.setTitle(i18n("dsh.settings.skill_source"));
		source.setNullSafeConverter(DshSkillSources::label);
		source.setDescriptionConverter(skillSourceDescription());
		source.setItems(List.of(DshSkillSources.values()));
		source.setValue(DshSkillSource.selectedSource());
		source.valueProperty().addListener((observable, was, value) -> {
			if (value != was) {
				DshSkillSource.setSelectedSource(value);
			}
		});
		return source;
	}

	private ComponentList buildDownloadList() {
		ComponentSublist cache = new ComponentSublist(() -> {
			MultiFileItem<Boolean> location = new MultiFileItem<>();
			location.loadChildren(List.of(new MultiFileItem.Option<>(i18n("dsh.settings.download.cache.default"), true),
			                              new MultiFileItem.FileOption<>(i18n("dsh.settings.download.cache.custom"),
			                                                             false).setChooserTitle(i18n(
					                                                                   "dsh.settings.download.cache.choose"))
			                                                                   .setSelectionMode(FileSelector.SelectionMode.DIRECTORY)
			                                                                   .bindBidirectional(settings().cacheDirectoryProperty())));
			location.selectedDataProperty().bindBidirectional(settings().cacheDirectoryCustomProperty());
			return List.of(location);
		});
		cache.setTitle(i18n("dsh.settings.download.cache"));
		cache.setHasSubtitle(true);
		cache.descriptionProperty()
		     .bind(Bindings.createStringBinding(() -> CatalogClient.cacheDirectory().toString(),
		                                        settings().cacheDirectoryCustomProperty(),
		                                        settings().cacheDirectoryProperty()));

		JFXButton clear = new JFXButton(i18n("dsh.settings.download.cache.clear"));
		clear.getStyleClass().add("jfx-button-border");
		clear.setMinWidth(Region.USE_PREF_SIZE);
		clear.setOnAction(event -> {
			int removed = CatalogClient.clearCache();
			clear.setText(i18n("dsh.settings.download.cache.cleared", removed));
		});
		cache.setHeaderRight(clear);

		ComponentSublist threads = new ComponentSublist(() -> {
			RadioChoiceList<Boolean> choices = new RadioChoiceList<>();
			choices.setChoices(
					new RadioChoiceList.Choice<>(i18n("dsh.settings.download.threads.auto"), true),
					new RadioChoiceList.Choice<>(i18n("dsh.settings.download.threads.custom"), false) {
						@Override
						protected Node createRightNode() {
							HBox box = new HBox(8);
							box.setAlignment(Pos.CENTER);
							box.disableProperty().bind(settings().autoDownloadThreadsProperty());

							JFXSlider slider = new JFXSlider(1, 256, 64);
							HBox.setHgrow(slider, Priority.ALWAYS);
							JFXTextField field = new JFXTextField();
							FXUtils.setLimitWidth(field, 60);

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
			return List.of(choices);
		});
		threads.setTitle(i18n("dsh.settings.download.threads"));
		threads.setHasSubtitle(true);
		threads.descriptionProperty().bind(Bindings.createStringBinding(() -> {
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

	/**
	 * Builds the rows for the network the launcher downloads through, similar to HMCL pages
	 *
	 * @return the section
	 */
	private VBox buildProxyList() {
		VBox proxyList = new VBox(10);
		proxyList.getStyleClass().add("card-non-transparent");

		HBox proxyTypePane = new HBox();
		proxyTypePane.setAlignment(Pos.CENTER_LEFT);
		proxyTypePane.setPadding(new Insets(10, 0, 0, 0));

		ToggleGroup group = new ToggleGroup();
		Map<DshProxyMode, JFXRadioButton> buttons = new LinkedHashMap<>();
		for (DshProxyMode mode : DshProxyMode.values()) {
			JFXRadioButton button = new JFXRadioButton(i18n(proxyModeKey(mode)));
			button.setToggleGroup(group);
			button.setUserData(mode);
			buttons.put(mode, button);
			proxyTypePane.getChildren().add(button);
		}
		buttons.get(currentProxyMode()).setSelected(true);
		group.selectedToggleProperty().addListener(
				(observable, was, now) ->
						settings().proxyModeProperty()
						          .set(now != null && now.getUserData() instanceof DshProxyMode mode
						               ? mode
						               : DshProxyMode.SYSTEM));
		proxyList.getChildren().add(proxyTypePane);

		VBox proxyPane = new VBox();
		proxyPane.disableProperty()
		         .bind(Bindings.createBooleanBinding(() -> !currentProxyMode().usesAddress(),
		                                             settings().proxyModeProperty()));

		JFXTextField host = new JFXTextField();
		host.textProperty().bindBidirectional(settings().proxyHostProperty());

		JFXTextField port = new JFXTextField();
		FXUtils.setLimitWidth(port, 200);
		FXUtils.setValidateWhileTextChanged(port, true);
		SafeStringConverter<Integer, Number> portConverter = SafeStringConverter.fromInteger()
		                                                                        .restrict(it -> it >= 0 && it <= 0xFFFF)
		                                                                        .fallbackTo(0);
		portConverter.asPredicate(Validator.addTo(port));
		port.setText(portConverter.toString(portConverter.fromString(settings().proxyPortProperty().get())));
		port.textProperty().addListener((observable, was, text) -> {
			Integer port0 = portConverter.fromString(text == null ? "" : text);
			settings().proxyPortProperty().set(Integer.toString(port0));
		});

		proxyPane.getChildren()
		         .add(formGrid(new Label(i18n("settings.launcher.proxy.host")),
		                       host,
		                       new Label(i18n("settings.launcher.proxy.port")),
		                       port));

		JFXCheckBox authenticated = new JFXCheckBox(i18n("settings.launcher.proxy.authentication"));
		authenticated.selectedProperty().bindBidirectional(settings().proxyAuthenticatedProperty());
		VBox authToggle = new VBox(authenticated);
		authToggle.setPadding(new Insets(20, 0, 20, 5));
		proxyPane.getChildren().add(authToggle);

		JFXTextField user = new JFXTextField();
		user.textProperty().bindBidirectional(settings().proxyUserProperty());
		JFXPasswordField password = new JFXPasswordField();
		password.textProperty().bindBidirectional(settings().proxyPasswordProperty());

		GridPane authPane = formGrid(new Label(i18n("settings.launcher.proxy.username")),
		                             user,
		                             new Label(i18n("settings.launcher.proxy.password")),
		                             password);
		authPane.disableProperty().bind(settings().proxyAuthenticatedProperty().not());
		proxyPane.getChildren().add(authPane);

		proxyList.getChildren().add(proxyPane);
		return proxyList;
	}

	/**
	 * @param title the row's name
	 * @param hint  what it is for
	 * @param field what is typed into it
	 * @return the row
	 */
	@Deprecated
	private Node proxyRowWithField(String title, String hint, Node field) {
		LinePane pane = new LinePane();
		pane.setTitle(title);
		if (!hint.isBlank()) pane.setSubtitle(hint);

		pane.setRight(field);
		return pane;
	}
}
