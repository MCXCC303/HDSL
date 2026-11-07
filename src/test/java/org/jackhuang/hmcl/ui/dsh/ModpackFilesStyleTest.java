package org.jackhuang.hmcl.ui.dsh;

import com.jfoenix.controls.JFXCheckTreeCell;
import com.jfoenix.controls.JFXTreeView;
import javafx.application.Platform;
import javafx.scene.control.CheckBoxTreeItem;
import javafx.scene.control.TreeCell;
import com.mcxcc303.hdsl.instance.DshHomeMode;
import com.mcxcc303.hdsl.instance.DshInstance;
import com.mcxcc303.hdsl.core.DshPaths;
import org.jackhuang.hmcl.ui.FxToolkit;
import org.jackhuang.hmcl.ui.construct.NoneMultipleSelectionModel;
import org.jackhuang.hmcl.util.SettingsMap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests verifying selection and rendering behavior of the modpack file export tree.
 */
class ModpackFilesStyleTest {

	@BeforeAll
	static void startToolkit() {
		FxToolkit.requireRunning();
	}

	private static void onFxThread(Runnable work) throws Exception {
		FutureTask<Void> task = new FutureTask<>(work, null);
		Platform.runLater(task);
		task.get(30, TimeUnit.SECONDS);
	}

	@SuppressWarnings("unchecked")
	private static JFXTreeView<String> treeOf(ModpackFilesPage page) {
		return (JFXTreeView<String>) page.getChildren().stream()
				.filter(JFXTreeView.class::isInstance)
				.findFirst()
				.orElseThrow(() -> new AssertionError("the page draws no JFXTreeView"));
	}

	private static DshInstance instanceWith(String id) throws Exception {
		Path home = DshPaths.instanceDirectory(id).resolve("home");
		Files.createDirectories(home.resolve("profiles/web"));
		Files.writeString(home.resolve("profiles/web/package.json"), """
				{"dsh":{"profile":{"bundles":["@deepseek-ai/dsh-plugin-manager","@deepseek-ai/dsh-better-sidebar"]}}}
				""");
		return new DshInstance(id, "v0.1.7", "web", "/tmp", null, DshHomeMode.ISOLATED, null,
				List.of(), Map.of(), null, null, null, 0, 0L);
	}

	private static void delete(String id) throws Exception {
		Path directory = DshPaths.instanceDirectory(id);
		if (Files.exists(directory)) {
			try (var walk = Files.walk(directory)) {
				walk.sorted(Comparator.reverseOrder()).forEach(path -> {
					try {
						Files.deleteIfExists(path);
					} catch (java.io.IOException ignored) {
					}
				});
			}
		}
	}

	@Test
	void theRowsAreMaterialCheckboxesThatCannotBeSelected() throws Exception {
		String id = "style-cells";
		DshInstance instance = instanceWith(id);
		try {
			onFxThread(() -> {
				ModpackFilesPage page = new ModpackFilesPage(null, instance, new SettingsMap());

				JFXTreeView<String> tree = treeOf(page);
				assertInstanceOf(NoneMultipleSelectionModel.class, tree.getSelectionModel(),
						"a row of the original's tree is not selectable");

				TreeCell<String> cell = tree.getCellFactory().call(tree);
				assertInstanceOf(JFXCheckTreeCell.class, cell,
						"the row draws the platform's checkbox rather than the material one");
			});
		} finally {
			delete(id);
		}
	}

	@Test
	void whatTheBoxesSayFollowsWhatIsUnderThem() throws Exception {
		String id = "style-marks";
		DshInstance instance = instanceWith(id);
		try {
			onFxThread(() -> {
				ModpackFilesPage page = new ModpackFilesPage(null, instance, new SettingsMap());
				CheckBoxTreeItem<String> root = (CheckBoxTreeItem<String>) treeOf(page).getRoot();

				CheckBoxTreeItem<String> plugins = (CheckBoxTreeItem<String>) root.getChildren().get(0);
				CheckBoxTreeItem<String> sessions = (CheckBoxTreeItem<String>) root.getChildren().get(2);
				assertTrue(plugins.isSelected(), "both bundles are ticked, so the branch is");
				assertFalse(plugins.isIndeterminate());
				assertFalse(sessions.isSelected(), "no conversation was asked for, so none travels");

				assertTrue(root.isIndeterminate(), "one branch travels and one does not: partly, not fully");
				assertFalse(root.isSelected(), "a partly travelling instance is not a travelling one");
			});
		} finally {
			delete(id);
		}
	}
}
