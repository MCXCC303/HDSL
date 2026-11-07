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
import com.jfoenix.controls.JFXPopup;
import com.mcxcc303.hdsl.account.DshAccount;
import com.mcxcc303.hdsl.instance.DshInstance;
import com.mcxcc303.hdsl.instance.DshInstanceIcon;
import com.mcxcc303.hdsl.instance.DshInstanceIcons;
import com.mcxcc303.hdsl.instance.InstanceStore;
import com.mcxcc303.hdsl.launch.DshProcessManager;
import com.mcxcc303.hdsl.skin.DshSkin;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.ListChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.Label;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import org.jackhuang.hmcl.Metadata;
import org.jackhuang.hmcl.setting.DshInstanceRepository;
import org.jackhuang.hmcl.setting.GameDirectoryManager;
import org.jackhuang.hmcl.setting.SettingsManager;
import org.jackhuang.hmcl.theme.Themes;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.construct.*;
import org.jackhuang.hmcl.ui.decorator.DecoratorAnimatedPage;
import org.jackhuang.hmcl.ui.decorator.DecoratorPage;
import org.jackhuang.hmcl.ui.dsh.install.DshInstallWizardProvider;
import org.jackhuang.hmcl.ui.dsh.settings.SkinDialog;
import org.jackhuang.hmcl.ui.wizard.Refreshable;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

// The launcher home page.
@NotNullByDefault
public final class MainPage extends DecoratorAnimatedPage implements DecoratorPage, Refreshable {
	private static final String POPUP_KEY = "hdsl-popup";
	private static @Nullable MainPage instance;
	private final ReadOnlyObjectWrapper<State> state = new ReadOnlyObjectWrapper<>(State.rootNode(titleNode()));
	private final ObjectProperty<@Nullable DshInstance> currentInstance = new SimpleObjectProperty<>();
	private final Label actionLabel = new Label();
	private final Label actionTarget = new Label();
	private final JFXButton actionButton = new JFXButton();
	private final Timeline ticker;
	private final AdvancedListItem currentInstanceItem = new AdvancedListItem();
	private final ImageContainer currentInstanceIcon = new ImageContainer(AdvancedListItem.LEFT_GRAPHIC_SIZE);
	private final AdvancedListItem accountItem = new AdvancedListItem();
	private final Canvas accountAvatar = new Canvas(32, 32);
	private DshProcessManager.@Nullable LaunchState shownActionState;
	private @Nullable DownloadPage downloadPage;
	private @Nullable SettingsPage settingsPage;

	public MainPage() {
		getStyleClass().remove("gray-background");

		currentInstanceIcon.setMouseTransparent(true);
		AdvancedListItem.setAlignment(currentInstanceIcon, Pos.CENTER);
		currentInstanceItem.setLeftGraphic(currentInstanceIcon);
		currentInstanceItem.setTitle(i18n("dsh.instance.manage"));
		currentInstanceItem.setSubtitle(i18n("dsh.launch.no_instance.hint"));
		currentInstanceItem.setOnAction(event -> openCurrentInstance());

		accountAvatar.setMouseTransparent(true);
		accountItem.setLeftGraphic(accountAvatar);
		accountItem.setOnAction(event -> Controllers.navigate(new AccountListPage()));

		AdvancedListBox sideBar = new AdvancedListBox().startCategory(i18n("dsh.account.list").toUpperCase(Locale.ROOT))
		                                               .add(accountItem)
		                                               .startCategory(i18n("instance").toUpperCase(Locale.ROOT))
		                                               .add(currentInstanceItem)
		                                               .addNavigationDrawerItem(i18n("dsh.instance.list"),
		                                                                        SVG.FORMAT_LIST_BULLETED,
		                                                                        () -> Controllers.navigate(
				                                                                        getInstancesPage()))
		                                               .addNavigationDrawerItem(i18n("download"),
		                                                                        SVG.DOWNLOAD,
		                                                                        () -> Controllers.navigate(
				                                                                        getDownloadPage()))
		                                               .startCategory(i18n("settings.launcher.general").toUpperCase(
				                                               Locale.ROOT))
		                                               .addNavigationDrawerItem(i18n("settings"),
		                                                                        SVG.SETTINGS,
		                                                                        () -> Controllers.navigate(
				                                                                        getSettingsPage()));
		FXUtils.setLimitWidth(sideBar, 200);
		getLeft().getStyleClass().add("gray-background");
		setLeft(sideBar);

		setCenter(new StackPane(buildLaunchPane()));

		currentInstance.bind(GameDirectoryManager.selectedInstanceProperty());
		currentInstance.addListener((observable, was, now) -> refresh());
		GameDirectoryManager.registerVersionsListener(this::onRepositoryChanged);

		SettingsManager.settings()
		               .activeAccountKeyProperty()
		               .addListener((observable, was, now) -> refreshAccountItem());
		SettingsManager.settings()
		               .getAccounts()
		               .addListener((ListChangeListener<DshAccount>) change -> Platform.runLater(this::refreshAccountItem));

		ticker = new Timeline(new KeyFrame(Duration.seconds(1), event -> refreshActionState()));
		ticker.setCycleCount(Animation.INDEFINITE);
		ticker.play();

		refresh();
		instance = this;
	}

	private static Node titleNode() {
		ImageView icon = new ImageView(FXUtils.newBuiltinImage("/assets/img/icon-title.png"));

		Label label = new Label(Metadata.FULL_TITLE);
		label.getStyleClass().add("jfx-decorator-title");
		label.textFillProperty().bind(Themes.titleFillProperty());

		HBox node = new HBox(8, icon, label);
		node.setPadding(new Insets(0, 0, 0, 2));
		node.setAlignment(Pos.CENTER_LEFT);
		return node;
	}

	/**
	 * Returns the main page instance created at startup.
	 *
	 * @return the main page instance
	 */
	public static MainPage instance() {
		MainPage page = instance;
		if (page == null) {
			throw new IllegalStateException("The main page has not been made yet");
		}
		return page;
	}

	private static void hidePopup(Node row) {
		Object popup = row.getProperties().get(POPUP_KEY);
		if (popup instanceof JFXPopup jfxPopup) {
			jfxPopup.hide();
		}
	}

	private static Node buildHeader(String text) {
		return ComponentList.createComponentListTitle(text);
	}

	private void onRepositoryChanged(DshInstanceRepository repository) {
		refresh();
	}

	private Node buildLaunchPane() {
		actionButton.getStyleClass().add("launch-button");
		actionButton.setDefaultButton(true);

		actionLabel.setStyle("-fx-font-size: 16px;");
		actionTarget.setStyle("-fx-font-size: 12px;");
		VBox graphic = new VBox(actionLabel, actionTarget);
		graphic.setAlignment(Pos.CENTER);
		actionButton.setGraphic(graphic);
		actionButton.setOnAction(event -> onActionButton());

		JFXButton menuButton = new JFXButton();
		menuButton.getStyleClass().add("menu-button");
		menuButton.setGraphic(SVG.ARROW_DROP_UP.createIcon(30));
		FXUtils.installFastTooltip(menuButton, i18n("dsh.launch.select"));
		menuButton.setOnAction(event -> showInstanceMenu(menuButton));

		HBox pane = new HBox(actionButton, menuButton);
		pane.getStyleClass().add("launch-pane");
		pane.setAlignment(Pos.BOTTOM_RIGHT);
		pane.setMaxWidth(Region.USE_PREF_SIZE);
		pane.setMaxHeight(Region.USE_PREF_SIZE);
		pane.setPickOnBounds(false);
		StackPane.setAlignment(pane, Pos.BOTTOM_RIGHT);
		StackPane.setMargin(pane, new Insets(0, 20, 20, 0));
		return pane;
	}

	/**
	 * Opens a page by name, used by the {@code --page} startup option.
	 *
	 * @param name the page name
	 * @return whether a page was opened
	 */
	public boolean openPage(String name) {
		String raw = name == null ? "" : name.trim();
		if (raw.toLowerCase(Locale.ROOT).startsWith("instance:")) {
			String rest = raw.substring("instance:".length());
			int slash = rest.indexOf('/');
			String id = slash < 0 ? rest : rest.substring(0, slash);
			String tab = slash < 0 ? null : rest.substring(slash + 1);
			DshInstance instance = InstanceStore.find(id);
			if (instance == null) {
				return false;
			}
			Controllers.navigate(new InstancePage(instance, tab));
			return true;
		}

		if (raw.toLowerCase(Locale.ROOT).startsWith("create-version:")) {
			Controllers.getDecorator()
			           .startWizard(new DshInstallWizardProvider(raw.substring("create-version:".length())),
			                        i18n("dsh.instance.create"));
			return true;
		}

		if (raw.toLowerCase(Locale.ROOT).startsWith("export-modpack:")) {
			DshInstance instance = InstanceStore.find(raw.substring("export-modpack:".length()));
			if (instance == null) {
				return false;
			}
			Controllers.getDecorator().startWizard(new ModpackExportWizardProvider(instance), i18n("modpack.wizard"));
			return true;
		}

		String value = raw.toLowerCase(Locale.ROOT);
		if (value.startsWith("download/")) {
			DownloadPage page = getDownloadPage();
			Controllers.navigate(page);
			return page.openTab(value.substring("download/".length()));
		}
		if (value.startsWith("settings/")) {
			SettingsPage page = getSettingsPage();
			Controllers.navigate(page);
			return page.openTab(value.substring("settings/".length()));
		}
		switch (value) {
			case "home", "" -> {
				return true;
			}
			case "instances" -> Controllers.navigate(getInstancesPage());
			case "download" -> Controllers.navigate(getDownloadPage());
			case "create" -> Controllers.navigate(getDownloadPage());
			case "accounts" -> Controllers.navigate(new AccountListPage());
			case "skin" -> {
				DshAccount chosen = SettingsManager.settings().activeAccount();
				if (chosen == null) {
					Controllers.dialog(i18n("dsh.launch.needs_account"),
					                   i18n("dsh.account.list"),
					                   MessageDialogPane.MessageType.WARNING);
				} else {
					Controllers.dialog(new SkinDialog(chosen, this::refreshAccountItem));
				}
			}
			case "settings" -> Controllers.navigate(getSettingsPage());
			default -> {
				return false;
			}
		}
		return true;
	}

	public DownloadPage getDownloadPage() {
		return Controllers.getDownloadPage();
	}

	public InstancesPage getInstancesPage() {
		return Controllers.getInstancesPage();
	}

	public SettingsPage getSettingsPage() {
		if (settingsPage == null) {
			settingsPage = new SettingsPage();
		}
		return settingsPage;
	}

	@Override
	public ReadOnlyObjectProperty<State> stateProperty() {
		return state.getReadOnlyProperty();
	}

	@Override
	public void refresh() {
		DshInstance current = currentInstance.get();

		currentInstanceItem.setTitle(current == null ? i18n("dsh.instance.empty") : i18n("dsh.instance.manage"));
		currentInstanceItem.setSubtitle(current == null ? i18n("dsh.instance.install") : current.id());
		currentInstanceIcon.setImage(current == null ? DshInstanceIcon.DEFAULT.load() : DshInstanceIcons.load(current));

		refreshAccountItem();
		refreshActionState();
	}

	private void refreshActionState() {
		DshInstance current = currentInstance.get();
		if (current == null) {
			actionLabel.setText(i18n("dsh.launch.no_instance"));
			actionTarget.setText(i18n("dsh.launch.no_instance.hint"));
			return;
		}

		DshProcessManager.LaunchState state = DshLaunchService.state(current.id());
		actionLabel.setText(DshLaunchService.actionLabel(state));
		actionTarget.setText(current.id());
		if (state != shownActionState) {
			shownActionState = state;
			FXUtils.installFastTooltip(actionButton, DshLaunchService.actionHint(state));
		}
	}

	private void refreshAccountItem() {
		DshAccount account = SettingsManager.settings().activeAccount();
		if (account == null) {
			accountItem.setTitle(i18n("dsh.account.none.short"));
			accountItem.setSubtitle(i18n("dsh.account.none.hint"));
		} else {
			accountItem.setTitle(account.displayName());
			accountItem.setSubtitle(account.vendorId());
		}
		drawAccountAvatar();
	}

	private void drawAccountAvatar() {
		DshAccount active = SettingsManager.settings().activeAccount();
		accountAvatar.getGraphicsContext2D().clearRect(0, 0, 32, 32);

		if (active == null) {
			return;
		}

		AccountAvatar.draw(accountAvatar, DshSkin.headImage(active.key()));
	}

	private void openCurrentInstance() {
		DshInstance instance = currentInstance.get();
		if (instance == null) {
			Controllers.navigate(getInstancesPage());
			return;
		}
		Controllers.navigate(new InstancePage(instance));
	}

	private void onActionButton() {
		DshInstance instance = currentInstance.get();
		if (instance == null) {
			Controllers.navigate(getInstancesPage());
			return;
		}
		DshLaunchService.toggle(instance, this::refresh);
	}

	private void showInstanceMenu(Node anchor) {
		List<DshInstance> instances = GameDirectoryManager.getSelectedRepository().getInstances();
		if (instances.isEmpty()) {
			Controllers.navigate(getInstancesPage());
			return;
		}

		InstancePickerMenu.show(anchor, instances, GameDirectoryManager::setSelectedInstance);
	}
}
