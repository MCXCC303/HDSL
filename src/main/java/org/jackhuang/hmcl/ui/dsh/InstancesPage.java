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
import com.jfoenix.controls.JFXListView;
import com.jfoenix.controls.JFXPopup;
import com.mcxcc303.hdsl.core.DshException;
import com.mcxcc303.hdsl.core.DshFileManager;
import com.mcxcc303.hdsl.instance.DshInstance;
import com.mcxcc303.hdsl.instance.DshInstanceManager;
import com.mcxcc303.hdsl.launch.DshProcessManager.LaunchState;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.beans.binding.Bindings;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.collections.ListChangeListener;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import org.jackhuang.hmcl.setting.DshInstanceRepository;
import org.jackhuang.hmcl.setting.GameDirectory;
import org.jackhuang.hmcl.setting.GameDirectoryManager;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.ToolbarListPageSkin;
import org.jackhuang.hmcl.ui.construct.*;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane.MessageType;
import org.jackhuang.hmcl.ui.decorator.DecoratorAnimatedPage;
import org.jackhuang.hmcl.ui.decorator.DecoratorPage;
import org.jackhuang.hmcl.ui.wizard.Refreshable;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

/**
 * Lists the instances of the selected folder.
 * <p>
 * The page observes the folder's {@link DshInstanceRepository} and redraws whenever it
 * publishes a snapshot.
 */
@NotNullByDefault
public final class InstancesPage extends DecoratorAnimatedPage implements DecoratorPage, Refreshable {
	private final ReadOnlyObjectWrapper<State> state = new ReadOnlyObjectWrapper<>(State.fromTitle(i18n(
			"dsh.instance.list")));
	private final BooleanProperty searching = new SimpleBooleanProperty(false);
	private final JFXListView<DshInstance> instanceList = buildInstanceList();
	private final VBox directoryBox = new VBox();
	private final ListSearchBar toolbar = new ListSearchBar(this::filterInstances, searching::set);
	private final Timeline ticker;
	private List<DshInstance> instances = List.of();
	private Map<String, LaunchState> shownStates = Map.of();

	public InstancesPage() {
		AdvancedListItem addDirectoryItem = new AdvancedListItem();
		addDirectoryItem.getStyleClass().add("navigation-drawer-item");
		addDirectoryItem.setTitle(i18n("dsh.directory.add"));
		addDirectoryItem.setLeftIcon(SVG.ADD_CIRCLE);
		addDirectoryItem.setOnAction(event -> addDirectory());

		directoryBox.setFillWidth(true);

		VBox directoryContent = new VBox();
		directoryContent.getStyleClass().add("advanced-list-box-content");
		directoryContent.getChildren().setAll(directoryBox, addDirectoryItem);

		ScrollPane directoryPane = new ScrollPane();
		directoryPane.setFitToWidth(true);
		directoryPane.setContent(directoryContent);
		VBox.setVgrow(directoryPane, Priority.ALWAYS);
		FXUtils.smoothScrolling(directoryPane);

		AdvancedListBox actions = new AdvancedListBox().addNavigationDrawerItem(i18n("dsh.instance.install"),
		                                                                        SVG.ADD,
		                                                                        this::createInstance)
		                                               .addNavigationDrawerItem(i18n("install.modpack"),
		                                                                        SVG.PACKAGE2,
		                                                                        () -> Controllers.navigate(new PackInstallPage()))
		                                               .addNavigationDrawerItem(i18n("dsh.settings.global"),
		                                                                        SVG.SETTINGS_FILL,
		                                                                        () -> Controllers.navigate(new SettingsPage()));

		FXUtils.setLimitWidth(directoryPane, 200);
		FXUtils.setLimitHeight(actions, 40 * 3 + 12 * 2);

		// The page paints the translucent plate, so the sidebar must not paint it again.
		setLeft(directoryPane, actions);

		// ComponentList gives the list an opaque surface; a bare VBox lets the wallpaper show through.
		StackPane pane = new StackPane();
		pane.setPadding(new Insets(10));
		pane.getStyleClass().add("notice-pane");

		ComponentList root = new ComponentList();
		root.getStyleClass().add("no-padding");
		root.getContent().add(buildToolbar());
		root.getContent().add(instanceList);
		// ComponentList reads the VGrow off the child and applies it to the wrapper it builds.
		ComponentList.setVgrow(instanceList, Priority.ALWAYS);
		pane.getChildren().setAll(root);

		setCenter(pane);

		GameDirectoryManager.getGameDirectories()
		                    .addListener((ListChangeListener<GameDirectory>) change -> loadDirectories());
		GameDirectoryManager.selectedGameDirectoryProperty().addListener((observable, was, now) -> loadDirectories());
		GameDirectoryManager.selectedInstanceProperty().addListener((observable, was, now) -> instanceList.refresh());
		GameDirectoryManager.registerVersionsListener(this::loadInstances);

		// The state of an instance changes on its own, so the rows are watched for it.
		ticker = new Timeline(new KeyFrame(Duration.seconds(1), event -> refreshLaunchStates()));
		ticker.setCycleCount(Animation.INDEFINITE);
		ticker.play();

		loadDirectories();
	}

	private void refreshLaunchStates() {
		Map<String, LaunchState> states = new HashMap<>();
		boolean changed = false;
		for (DshInstance instance : instanceList.getItems()) {
			LaunchState state = DshLaunchService.state(instance.id());
			states.put(instance.id(), state);
			if (shownStates.get(instance.id()) != state) {
				changed = true;
			}
		}
		shownStates = states;
		if (changed) {
			instanceList.refresh();
		}
	}

	@Override
	public ReadOnlyObjectProperty<State> stateProperty() {
		return state.getReadOnlyProperty();
	}

	// Re-read the selected folder.
	@Override
	public void refresh() {
		GameDirectoryManager.getSelectedRepository().refresh();
	}

	// Redraws the sidebar from the folders.
	private void loadDirectories() {
		GameDirectory directory = GameDirectoryManager.selected();

		directoryBox.getChildren().setAll(GameDirectoryManager.getGameDirectories().stream().map(candidate -> {
			DirectoryListItem item = new DirectoryListItem(candidate,
			                                               chosen -> GameDirectoryManager.select(chosen.id()),
			                                               this::removeDirectory);
			item.setSelected(candidate.id().equals(directory.id()));
			return (Node) item;
		}).toList());
	}

	private void loadInstances(DshInstanceRepository repository) {
		instances = repository.getInstances();
		filterInstances();
	}

	private void filterInstances() {
		List<DshInstance> shown = new ArrayList<>(instances);
		shown.removeIf(instance -> !toolbar.accepts(instance.id()));

		instanceList.getItems().setAll(shown);
		instanceList.refresh();
		shownStates = new HashMap<>();
		refreshLaunchStates();
	}

	private Node buildToolbar() {
		toolbar.setButtons(ToolbarListPageSkin.createToolbarButton2(i18n("button.refresh"),
		                                                            SVG.REFRESH,
		                                                            this::refresh));
		return toolbar;
	}

	/**
	 * Drops a folder from the list.
	 *
	 * @param directory the folder to drop
	 */
	private void removeDirectory(GameDirectory directory) {
		if (directory.isDefault()) {
			Controllers.dialog(i18n("dsh.directory.remove.default"), i18n("dsh.directory.remove"), MessageType.ERROR);
			return;
		}
		GameDirectoryManager.remove(directory.id());
	}

	private void addDirectory() {
		Controllers.navigate(new DirectoryPage());
	}

	private JFXListView<DshInstance> buildInstanceList() {
		JFXListView<DshInstance> list = new JFXListView<>();
		list.setCellFactory(view -> {
			InstanceListCell cell = new InstanceListCell();
			cell.setHandlers(this::select, this::open, this::toggleLaunch, this::showMenu);
			cell.setSelectedInstanceSupplier(GameDirectoryManager::getSelectedInstance);
			cell.setStateCheck(instance -> DshLaunchService.state(instance.id()));
			return cell;
		});
		list.getStyleClass().add("no-padding");
		FXUtils.setLimitHeight(list, Region.USE_COMPUTED_SIZE);

		StackPane placeholder = new StackPane();
		placeholder.getStyleClass().add("notice-pane");
		Label placeholderLabel = new Label();
		placeholderLabel.textProperty()
		                .bind(Bindings.when(searching)
		                              .then(i18n("search.no_results_found"))
		                              .otherwise(i18n("dsh.instance.empty") + "\n" + i18n("dsh.instance.empty.hint")));
		placeholder.getChildren().add(placeholderLabel);
		list.setPlaceholder(placeholder);
		return list;
	}

	private void select(DshInstance instance) {
		GameDirectoryManager.setSelectedInstance(instance);
	}

	private void open(DshInstance instance) {
		Controllers.navigate(new InstancePage(instance));
	}

	private void toggleLaunch(DshInstance instance) {
		DshLaunchService.toggle(instance, this::refresh);
	}

	private void showMenu(DshInstance instance, JFXButton anchor) {
		PopupMenu menu = new PopupMenu();
		JFXPopup popup = new JFXPopup(menu);

		menu.getContent()
		    .setAll(new IconedMenuItem(SVG.CHECK, i18n("dsh.instance.select"), () -> select(instance), popup),
		            new IconedMenuItem(SVG.SETTINGS_FILL,
		                               i18n("dsh.instance.manage"),
		                               () -> Controllers.navigate(new InstancePage(instance)),
		                               popup),
		            new IconedMenuItem(SVG.PUBLIC,
		                               i18n("dsh.instance.open_browser"),
		                               () -> InstanceBrowser.open(instance),
		                               popup),
		            new IconedMenuItem(SVG.FOLDER_OPEN,
		                               i18n("dsh.instance.open_home"),
		                               () -> showInstanceDirectory(instance),
		                               popup),
		            new MenuSeparator(),
		            new IconedMenuItem(SVG.DELETE, i18n("dsh.instance.remove"), () -> removeInstance(instance), popup));

		popup.show(anchor,
		           JFXPopup.PopupVPosition.BOTTOM,
		           JFXPopup.PopupHPosition.RIGHT,
		           -anchor.getBoundsInLocal().getWidth(),
		           0);
	}

	private void showInstanceDirectory(DshInstance instance) {
		try {
			if (!DshFileManager.openDirectory(instance.instanceDirectory())) {
				Controllers.dialog(i18n("dsh.instance.open.unavailable"), i18n("message.error"), MessageType.ERROR);
			}
		} catch (DshException e) {
			Controllers.dialog(e.getMessage(), i18n("message.error"), MessageType.ERROR);
		}
	}

	private void createInstance() {
		Controllers.navigate(Controllers.getDownloadPage());
	}

	/**
	 * Removes an instance after confirmation.
	 */
	private void removeInstance(DshInstance instance) {
		String id = instance.id();
		Controllers.confirm(i18n("dsh.instance.remove.confirm", id), i18n("dsh.instance.remove"), () -> {
			AtomicBoolean failed = new AtomicBoolean();
			ProgressDialog.run(i18n("dsh.instance.remove"), progress -> {
				progress.accept(i18n("dsh.instance.removing", id));
				DshInstanceManager.delete(id);
			}, () -> {
				if (!failed.get()) {
					Controllers.showToast(i18n("dsh.instance.removed", id));
				}
			}, failure -> {
				failed.set(true);
				return false;
			});
		}, null);
	}
}
