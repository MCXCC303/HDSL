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

import com.mcxcc303.hdsl.core.DshFileManager;
import com.mcxcc303.hdsl.plugin.DshDependencyPolicy;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.Metadata;
import org.jackhuang.hmcl.setting.SettingsManager;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.construct.ComponentList;
import org.jackhuang.hmcl.ui.construct.LineButton;
import org.jackhuang.hmcl.ui.construct.LineSelectButton;
import org.jackhuang.hmcl.util.i18n.I18n;
import org.jackhuang.hmcl.util.i18n.SupportedLocale;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.Locale;

import static org.jackhuang.hmcl.setting.SettingsManager.settings;
import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

@NotNullByDefault
public final class GeneralSettingsPage extends ScrollPane {
	public GeneralSettingsPage() {
		setFitToWidth(true);
		setFitToHeight(true);

		ComponentList general = new ComponentList();
		general.getContent().add(buildLanguageRow());
		general.getContent().add(buildStorageRow());

		ComponentList packs = new ComponentList();
		packs.getContent().add(buildDependencyPolicyRow());

		VBox root = new VBox(ComponentList.createComponentListTitle(i18n("settings.launcher.general")),
		                     general,
		                     ComponentList.createComponentListTitle(i18n("dsh.settings.pack_install")),
		                     packs);
		root.getStyleClass().add("card-list");
		setContent(root);

		// Smooth scrolling binds to the content node, so it must run after the content is installed.
		FXUtils.smoothScrolling(this);
	}

	private LineSelectButton<DshDependencyPolicy> buildDependencyPolicyRow() {
		LineSelectButton<DshDependencyPolicy> row = new LineSelectButton<>();
		row.setTitle(i18n("dsh.settings.dependency_policy"));
		row.setItems(DshDependencyPolicy.values());
		row.setNullSafeConverter(policy -> i18n(
				"dsh.settings.dependency_policy." + policy.name().toLowerCase(Locale.ROOT)));
		row.setValue(settings().dependencyPolicy());
		row.valueProperty().addListener((observable, was, value) -> {
			if (value != null && value != was) {
				settings().dependencyPolicyProperty().set(value);
				SettingsManager.save();
			}
		});
		return row;
	}

	private LineSelectButton<SupportedLocale> buildLanguageRow() {
		LineSelectButton<SupportedLocale> language = new LineSelectButton<>();
		language.setTitle(i18n("dsh.settings.language"));
		language.setItems(SupportedLocale.getSupportedLocales());
		language.setNullSafeConverter(locale -> locale.getDisplayName(I18n.getLocale()));
		language.setValue(I18n.getLocale());
		language.valueProperty().addListener((observable, oldValue, newValue) -> {
			if (newValue != null) {
				I18n.setLocale(newValue);
				settings().languageProperty().set(newValue);
				SettingsManager.save();
			}
		});
		return language;
	}

	private LineButton buildStorageRow() {
		LineButton home = new LineButton();
		home.setTitle(i18n("dsh.settings.home"));
		home.setSubtitle(Metadata.HDSL_USER_HOME.toString());
		home.setOnAction(event -> DshFileManager.openDirectory(Metadata.HDSL_USER_HOME));
		return home;
	}
}
