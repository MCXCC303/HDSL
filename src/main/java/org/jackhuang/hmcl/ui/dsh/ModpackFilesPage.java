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
package org.jackhuang.hmcl.ui.dsh;

import com.jfoenix.controls.JFXButton;
import com.jfoenix.controls.JFXCheckTreeCell;
import com.jfoenix.controls.JFXTreeView;
import com.mcxcc303.hdsl.core.DshException;
import com.mcxcc303.hdsl.instance.DshInstance;
import com.mcxcc303.hdsl.launch.DshSession;
import com.mcxcc303.hdsl.launch.DshSessions;
import com.mcxcc303.hdsl.pack.DshPackForge;
import com.mcxcc303.hdsl.pack.PackExporter;
import com.mcxcc303.hdsl.pack.PackManifest;
import com.mcxcc303.hdsl.plugin.DshPluginInstaller;
import com.mcxcc303.hdsl.plugin.DshPluginSettings;
import com.mcxcc303.hdsl.skill.DshSkillManager;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.CheckBoxTreeItem;
import javafx.scene.control.Label;
import javafx.scene.control.TreeItem;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane.MessageType;
import org.jackhuang.hmcl.ui.construct.NoneMultipleSelectionModel;
import org.jackhuang.hmcl.ui.wizard.WizardController;
import org.jackhuang.hmcl.ui.wizard.WizardPage;
import org.jackhuang.hmcl.util.SettingsMap;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

/**
 * Wizard page for selecting which instance files, bundles, settings, and skills to include in the exported pack.
 */
@NotNullByDefault
public final class ModpackFilesPage extends VBox implements WizardPage {
	private final SettingsMap settings;
	private final List<CheckBoxTreeItem<String>> bundleItems = new ArrayList<>();
	private final List<ModpackFileTreeItem> settingsItems = new ArrayList<>();
	private final List<ModpackFileTreeItem> skillsItems = new ArrayList<>();
	private final WizardController controller;
	private final DshInstance instance;
	private CheckBoxTreeItem<String> sessionsItem;
	private int sessionCount;

	public ModpackFilesPage(WizardController controller, DshInstance instance, SettingsMap settings) {
		this.controller = controller;
		this.instance = instance;
		this.settings = settings;

		setSpacing(10);
		setPadding(new Insets(10));

		Label title = new Label(i18n("dsh.modpack.files.title"));
		getChildren().add(title);

		JFXTreeView<String> tree = new JFXTreeView<>(buildTree());
		tree.setCellFactory(view -> new ModpackFileTreeCell());
		tree.setSelectionModel(new NoneMultipleSelectionModel<>());
		tree.setShowRoot(true);
		VBox.setVgrow(tree, Priority.ALWAYS);
		getChildren().add(tree);

		HBox buttons = new HBox(8);
		buttons.setAlignment(Pos.CENTER_RIGHT);
		JFXButton write = FXUtils.newRaisedButton(i18n("modpack.export"));
		write.setPrefSize(100, 40);
		write.setOnAction(event -> {
			cleanup(settings);
			List<String> development = selectedDevelopmentPlugins();
			if (development.isEmpty()) {
				controller.onFinish();
				return;
			}
			Controllers.confirm(i18n("dsh.modpack.development.selected", String.join("\n", development)),
			                    i18n("dsh.modpack.development.title"),
			                    MessageType.WARNING,
			                    controller::onFinish,
			                    null);
		});
		buttons.getChildren().add(write);
		getChildren().add(buttons);
	}

	/**
	 * Configures tree item listeners so parent checkboxes reflect children states.
	 *
	 * @param branch the branch item
	 */
	static void follow(CheckBoxTreeItem<String> branch) {
		for (TreeItem<String> child : branch.getChildren()) {
			if (child instanceof CheckBoxTreeItem<String> box) {
				box.selectedProperty().addListener((observable, was, now) -> refreshFrom(box));
				follow(box);
			}
		}
		refreshBranch(branch);
	}

	private static void refreshFrom(CheckBoxTreeItem<String> box) {
		if (box.getParent() instanceof CheckBoxTreeItem<String> parent) {
			refreshBranch(parent);
			refreshFrom(parent);
		}
	}

	/**
	 * Updates a branch item's checkbox state based on its children.
	 *
	 * @param branch the branch item
	 */
	static void refreshBranch(CheckBoxTreeItem<String> branch) {
		int travelling = 0;
		int some = 0;
		int boxes = 0;
		for (TreeItem<String> child : branch.getChildren()) {
			if (child instanceof CheckBoxTreeItem<String> box) {
				boxes++;
				boolean ticked = box.isSelected() || box.isIndeterminate();
				if (ticked) {
					some++;
				}
				if (box.isSelected() && !box.isIndeterminate()) {
					travelling++;
				}
			}
		}
		if (boxes == 0) {
			return;
		}
		branch.setIndeterminate(some > 0 && travelling < boxes);
		if (travelling == boxes) {
			branch.setSelected(true);
		} else if (some == 0) {
			branch.setSelected(false);
		}
	}

	/**
	 * Writes a pack, reporting progress.
	 *
	 * @param instance the instance to write out
	 * @param target   the target file path
	 * @param options  the pack options
	 * @param report   receives progress lines
	 * @throws DshException when the pack cannot be written
	 */
	static void write(DshInstance instance, Path target, PackManifest.Options options, Consumer<String> report)
	throws DshException {
		PackExporter.export(instance, target, options, report);
	}

	/**
	 * Writes a pack that community tooling reads, and the digest beside it.
	 *
	 * @param instance the instance to write out
	 * @param target   the target file path
	 * @param options  the pack options
	 * @param report   receives progress lines
	 * @throws DshException when the pack cannot be written
	 */
	static void writePackForge(DshInstance instance, Path target, DshPackForge.Options options, Consumer<String> report)
	throws DshException {
		DshPackForge.Result result = DshPackForge.export(instance, target, options, report);
		try {
			Files.writeString(target.resolveSibling(target.getFileName() + ".sha256"),
			                  result.sha256() + "  " + target.getFileName() + "\n");
			report.accept("Wrote " + target.getFileName() + ".sha256");
		} catch (IOException e) {
			report.accept("The pack was written, but its digest was not: " + e.getMessage());
		}
	}

	private ModpackFileTreeItem buildTree() {
		ModpackFileTreeItem root = new ModpackFileTreeItem(instance.id());
		root.setExpanded(true);

		List<String> bundles = List.of();
		try {
			bundles = DshPluginInstaller.readBundles(instance.homeDirectory(), instance.profile());
		} catch (DshException | RuntimeException e) {
			// Ignore if bundles cannot be read
		}
		ModpackFileTreeItem plugins = new ModpackFileTreeItem(i18n("dsh.modpack.files.plugins", bundles.size()));
		plugins.setExpanded(true);
		for (String bundle : bundles) {
			ModpackFileTreeItem item = new ModpackFileTreeItem(bundle);
			item.setSelected(true);
			plugins.getChildren().add(item);
		}
		bundleItems.clear();
		for (TreeItem<String> child : plugins.getChildren()) {
			bundleItems.add((CheckBoxTreeItem<String>) child);
		}
		root.getChildren().add(plugins);

		ModpackFileTreeItem configuration = new ModpackFileTreeItem(i18n("dsh.modpack.files.configuration"));
		for (String name : List.of("package.json", "cordis.patch.yml")) {
			ModpackFileTreeItem item = new ModpackFileTreeItem(name);
			item.setSelected(true);
			configuration.getChildren().add(item);
		}
		root.getChildren().add(configuration);

		List<String> sections = List.of();
		try {
			sections = DshPluginSettings.sectionsOf(instance.homeDirectory());
		} catch (DshException e) {
			// Ignore if settings sections cannot be read
		}
		if (!sections.isEmpty()) {
			Set<String> wanted = ModpackExportWizardProvider.settingsOf(settings);
			ModpackFileTreeItem settingsItem = new ModpackFileTreeItem(i18n("dsh.modpack.files.settings",
			                                                                sections.size()));
			settingsItem.setExpanded(true);
			for (String section : sections) {
				ModpackFileTreeItem item = new ModpackFileTreeItem(section);
				item.setSelected(wanted.isEmpty() || wanted.contains(section));
				settingsItem.getChildren().add(item);
				settingsItems.add(item);
			}
			root.getChildren().add(settingsItem);
			follow(settingsItem);
		}

		List<String> skills = List.of();
		try {
			skills = DshSkillManager.packableNames(instance.homeDirectory());
		} catch (DshException e) {
			// Ignore if skills cannot be read
		}
		if (!skills.isEmpty()) {
			Set<String> wanted = ModpackExportWizardProvider.skillsOf(settings);
			ModpackFileTreeItem skillsItem = new ModpackFileTreeItem(i18n("dsh.modpack.files.skills", skills.size()));
			skillsItem.setExpanded(true);
			for (String skill : skills) {
				ModpackFileTreeItem item = new ModpackFileTreeItem(skill);
				item.setSelected(wanted.isEmpty() || wanted.contains(skill));
				skillsItem.getChildren().add(item);
				skillsItems.add(item);
			}
			root.getChildren().add(skillsItem);
			follow(skillsItem);
		}

		List<DshSession> sessions;
		try {
			sessions = DshSessions.list(instance.homeDirectory());
		} catch (DshException e) {
			sessions = List.of();
		}
		sessionCount = sessions.size();

		sessionsItem = new ModpackFileTreeItem(i18n("dsh.modpack.files.sessions"));
		sessionsItem.setSelected(
				Boolean.TRUE.equals(settings.get(ModpackExportWizardProvider.SESSIONS)) && sessionCount > 0);
		sessionsItem.setIndependent(sessionCount == 0);
		CheckBoxTreeItem<String> attachments = new ModpackFileTreeItem(i18n("dsh.modpack.files.attachments"));
		attachments.setSelected(sessionsItem.isSelected());
		sessionsItem.getChildren().add(attachments);
		root.getChildren().add(sessionsItem);

		follow(plugins);
		follow(configuration);
		follow(sessionsItem);
		refreshBranch(root);

		return root;
	}

	@Override
	public void onNavigate(SettingsMap settings) {
	}

	@Override
	public void cleanup(SettingsMap settings) {
		settings.put(ModpackExportWizardProvider.SESSIONS, sessionsItem != null && sessionsItem.isSelected());
		Set<String> excluded = new LinkedHashSet<>();
		for (CheckBoxTreeItem<String> item : bundleItems) {
			if (!item.isSelected()) {
				excluded.add(item.getValue());
			}
		}
		settings.put(ModpackExportWizardProvider.EXCLUDED_BUNDLES, excluded);
		Set<String> sections = new LinkedHashSet<>();
		for (ModpackFileTreeItem item : settingsItems) {
			if (item.isSelected()) {
				sections.add(item.getValue());
			}
		}
		settings.put(ModpackExportWizardProvider.SETTINGS, sections);
		Set<String> skills = new LinkedHashSet<>();
		for (ModpackFileTreeItem item : skillsItems) {
			if (item.isSelected()) {
				skills.add(item.getValue());
			}
		}
		settings.put(ModpackExportWizardProvider.SKILLS, skills);
	}

	private List<String> selectedDevelopmentPlugins() {
		Set<String> development = new LinkedHashSet<>();
		for (PackExporter.LocalPlugin plugin : ModpackExportWizardProvider.localPluginsOf(settings)) {
			if (plugin.development()) {
				development.add(plugin.name());
			}
		}
		return bundleItems.stream()
		                  .filter(CheckBoxTreeItem::isSelected)
		                  .map(CheckBoxTreeItem::getValue)
		                  .filter(development::contains)
		                  .toList();
	}

	@Override
	public String getTitle() {
		return i18n("dsh.modpack.files");
	}

	private static final class ModpackFileTreeItem extends CheckBoxTreeItem<String> {
		ModpackFileTreeItem(String name) {
			super(name);
		}
	}

	@NotNullByDefault
	private static final class ModpackFileTreeCell extends JFXCheckTreeCell<String> {
		private final HBox content = new HBox(3);
		private final Label name = new Label();

		private ModpackFileTreeCell() {
			name.setMouseTransparent(true);
			content.setAlignment(Pos.CENTER_LEFT);
			content.setPickOnBounds(false);
		}

		@Override
		protected void updateDisplay(@Nullable String item, boolean empty) {
			content.getChildren().clear();
			super.updateDisplay(item, empty);

			name.setText(null);
			if (empty || item == null) {
				return;
			}

			setText(null);
			@Nullable Node graphic = getGraphic();
			if (graphic != null) {
				content.getChildren().add(graphic);
			}
			name.setText(item);
			content.getChildren().add(name);
			setGraphic(content);
		}
	}
}
