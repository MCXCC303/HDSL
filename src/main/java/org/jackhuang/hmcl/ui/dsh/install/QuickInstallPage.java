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
package org.jackhuang.hmcl.ui.dsh.install;

import com.jfoenix.controls.JFXButton;
import com.jfoenix.controls.JFXTextField;
import com.mcxcc303.hdsl.core.DshException;
import com.mcxcc303.hdsl.core.DshPaths;
import com.mcxcc303.hdsl.harness.AppBootPin;
import com.mcxcc303.hdsl.harness.DshPreset;
import com.mcxcc303.hdsl.harness.DshPresetCatalog;
import com.mcxcc303.hdsl.harness.ReleaseQueries;
import com.mcxcc303.hdsl.instance.DshInstanceIcon;
import com.mcxcc303.hdsl.instance.DshInstanceManager;
import javafx.beans.binding.Bindings;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import org.jackhuang.hmcl.theme.Themes;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.construct.ComponentList;
import org.jackhuang.hmcl.ui.construct.LineTextPane;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane;
import org.jackhuang.hmcl.ui.wizard.WizardController;
import org.jackhuang.hmcl.ui.wizard.WizardPage;
import org.jackhuang.hmcl.util.SettingsMap;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/**
 * The quick-install step of the install wizard.
 * <p>
 * Allows naming the instance and choosing plugins to install into it.
 */
@NotNullByDefault
public final class QuickInstallPage extends BorderPane implements WizardPage {
	private final WizardController controller;
	private final JFXTextField nameField = new JFXTextField();
	private final List<PluginCard> presetCards = new ArrayList<>();
	private final StringProperty appBootStatus = new SimpleStringProperty();
	private boolean nameWrittenByHand;

	/**
	 * Creates the quick-install page.
	 *
	 * @param controller the wizard controller
	 */
	public QuickInstallPage(WizardController controller) {
		this.controller = controller;

		setPadding(new Insets(16));

		seedDefaultChoices();

		VBox content = new VBox(10, buildInstanceList(), buildPresetList());

		ScrollPane scroll = new ScrollPane(content);
		scroll.setFitToWidth(true);
		FXUtils.smoothScrolling(scroll);

		setCenter(scroll);
		setBottom(buildFooter());

		applyDefaults();
	}

	private static Image appBootMark() {
		return (Themes.darkModeProperty().get() ? DshInstanceIcon.DSH_WHITE : DshInstanceIcon.DSH_BLACK).load();
	}

	private void seedDefaultChoices() {
		// Nothing to seed.
	}

	private ComponentList buildInstanceList() {
		nameField.setPromptText(i18n("dsh.instance.name"));
		nameField.textProperty().addListener((observable, was, now) -> nameWrittenByHand = true);

		JFXButton clear = new JFXButton();
		clear.setGraphic(SVG.CLOSE.createIcon(20));
		clear.getStyleClass().add("toggle-icon4");
		clear.setOnAction(event -> nameField.setText(""));

		JFXButton reset = new JFXButton();
		reset.setGraphic(SVG.REFRESH.createIcon(20));
		reset.getStyleClass().add("toggle-icon4");
		reset.setOnAction(event -> {
			nameWrittenByHand = false;
			refreshSuggestedName();
		});

		HBox trailing = new HBox(8, nameField, clear, reset);
		trailing.setAlignment(Pos.CENTER_LEFT);
		HBox.setHgrow(nameField, Priority.ALWAYS);

		LineTextPane nameRow = new LineTextPane();
		nameRow.setTitle(i18n("dsh.instance.name"));
		nameRow.setTitleTrailing(trailing);

		ComponentList list = new ComponentList();
		list.getContent().add(nameRow);
		return list;
	}

	private String currentVersion() {
		String version = controller.getSettings().get(DshInstallWizardProvider.VERSION);
		return version == null || version.isBlank() ? null : version;
	}

	private Node buildAppBootCard() {
		appBootStatus.set(i18n("dsh.install.app_boot.matched", currentAppBoot()));

		ImageView mark = new ImageView();
		mark.imageProperty()
		    .bind(Bindings.createObjectBinding(QuickInstallPage::appBootMark, Themes.darkModeProperty()));

		InstallerCard card = new InstallerCard(mark,
		                                       i18n("dsh.install.app_boot"),
		                                       appBootStatus.get(),
		                                       () -> chooseAppBoot());
		card.statusProperty().bind(appBootStatus);
		FXUtils.installFastTooltip(card, i18n("dsh.install.app_boot.hint"));
		return card;
	}

	private String currentAppBoot() {
		String chosen = controller.getSettings().get(DshInstallWizardProvider.APP_BOOT);
		return chosen == null || chosen.isBlank() ? currentVersion() : chosen;
	}

	private void chooseAppBoot() {
		Controllers.getDecorator().startWizard(new AppBootWizardProvider(currentVersion(), chosen -> {
			                                       if (chosen == null) {
				                                       controller.getSettings().remove(DshInstallWizardProvider.APP_BOOT);
				                                       appBootStatus.set(i18n("dsh.install.app_boot.matched", currentAppBoot()));
			                                       } else {
				                                       controller.getSettings().put(DshInstallWizardProvider.APP_BOOT, chosen);
				                                       appBootStatus.set(i18n("dsh.install.app_boot.chosen", chosen));
			                                       }
			                                       refreshSuggestedName();
		                                       }),
		                                       i18n("dsh.instance.create"));
	}

	private List<String> availableAppBootVersions() {
		try {
			List<String> versions = new ArrayList<>();
			String launcherVersion = currentVersion();
			if (launcherVersion != null) {
				versions.add(launcherVersion);
			}
			for (String version : ReleaseQueries.fetchPackageVersions(AppBootPin.APP_BOOT_PACKAGE)) {
				if (!versions.contains(version)) {
					versions.add(version);
				}
			}
			return versions;
		} catch (DshException e) {
			LOG.warning("Failed to list application boot versions", e);
			return List.of();
		}
	}

	private Node buildVersionCard() {
		return new InstallerCard(DshInstanceIcon.DSH_APPLICATION.load(),
		                         i18n("dsh.install.version.name"),
		                         currentVersion() == null ? i18n("dsh.install.version.none") : currentVersion(),
		                         null);
	}

	private VBox buildPresetList() {
		Node header = ComponentList.createComponentListTitle(i18n("dsh.install.presets"));

		FlowPane grid = new FlowPane();
		grid.setHgap(12);
		grid.setVgap(12);

		Map<String, String> choices = choices();
		grid.getChildren().add(buildVersionCard());
		grid.getChildren().add(buildAppBootCard());

		for (DshPreset preset : DshPresetCatalog.builtin()) {
			PluginCard card = new PluginCard(preset, preset.recommended());
			card.setChosenVersion(choices.get(preset.id()));
			card.setOnConfigure(() -> controller.onNext(new PresetChoicePage(controller, preset)));
			card.setOnClear(() -> {
				choices.remove(preset.id());
				card.setChosenVersion(null);
			});
			FXUtils.installFastTooltip(card, preset.description());
			presetCards.add(card);
			grid.getChildren().add(card);
		}

		return new VBox(4, header, grid);
	}

	private HBox buildFooter() {
		JFXButton back = new JFXButton(i18n("button.previous"));
		back.setOnAction(event -> controller.onPrev(false));

		JFXButton finish = FXUtils.newRaisedButton(i18n("dsh.install.start"));
		finish.setPrefWidth(100);
		finish.setPrefHeight(40);
		finish.setOnAction(event -> {
			SettingsMap settings = controller.getSettings();
			settings.put(DshInstallWizardProvider.NAME, nameField.getText());
			if (!validate(settings)) {
				return;
			}
			controller.onFinish();
		});

		Region spacer = new Region();
		HBox.setHgrow(spacer, Priority.ALWAYS);

		HBox footer = new HBox(8, back, spacer, finish);
		footer.setAlignment(Pos.CENTER_RIGHT);
		footer.setPadding(new Insets(0, 10, 10, 10));
		return footer;
	}

	private void applyDefaults() {
		seedDefaultChoices();
		nameWrittenByHand = false;
		refreshSuggestedName();
	}

	private String suggestName() {
		StringBuilder name = new StringBuilder(currentVersion());

		String appBoot = controller.getSettings().get(DshInstallWizardProvider.APP_BOOT);
		if (appBoot != null && !appBoot.isBlank() && !appBoot.equals(currentVersion())) {
			name.append("-boot").append(appBoot);
		}

		return name.toString();
	}

	private void refreshSuggestedName() {
		if (nameWrittenByHand) {
			return;
		}
		nameField.setText(suggestName());
		nameWrittenByHand = false;
	}

	/**
	 * Validates collected settings before finishing the wizard.
	 *
	 * @param settings the collected wizard settings
	 * @return {@code true} if valid, {@code false} otherwise
	 */
	private boolean validate(SettingsMap settings) {
		String name = settings.get(DshInstallWizardProvider.NAME);
		if (name == null || name.isBlank()) {
			Controllers.dialog(i18n("dsh.instance.name.empty"),
			                   i18n("dsh.install.step.quick"),
			                   MessageDialogPane.MessageType.WARNING);
			return false;
		}
		if (!DshPaths.isUsableSegment(name)) {
			Controllers.dialog(i18n("dsh.instance.name.malformed", name.trim()),
			                   i18n("dsh.install.step.quick"),
			                   MessageDialogPane.MessageType.WARNING);
			return false;
		}
		if (DshInstanceManager.exists(name.trim())) {
			Controllers.dialog(i18n("dsh.install.name.taken", name.trim()),
			                   i18n("dsh.install.step.quick"),
			                   MessageDialogPane.MessageType.WARNING);
			return false;
		}
		return true;
	}

	@Override
	public void onNavigate(SettingsMap settings) {
		Map<String, String> choices = choices();
		for (PluginCard card : presetCards) {
			card.setChosenVersion(choices.get(card.preset().id()));
		}
	}

	private Map<String, String> choices() {
		return DshInstallWizardProvider.presetChoices(controller.getSettings());
	}

	@Override
	public String getTitle() {
		return i18n("dsh.install.step.quick");
	}
}
