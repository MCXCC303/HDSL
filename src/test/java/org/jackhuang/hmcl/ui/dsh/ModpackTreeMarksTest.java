package org.jackhuang.hmcl.ui.dsh;

import javafx.application.Platform;
import javafx.scene.control.CheckBoxTreeItem;
import org.jackhuang.hmcl.ui.FxToolkit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests verifying checkbox tree branch selection and indeterminate state propagation.
 */
class ModpackTreeMarksTest {

	@BeforeAll
	static void startToolkit() {
		FxToolkit.requireRunning();
	}

	private static void onFxThread(Runnable work) throws Exception {
		FutureTask<Void> task = new FutureTask<>(work, null);
		Platform.runLater(task);
		task.get(30, TimeUnit.SECONDS);
	}

	private static void children(CheckBoxTreeItem<String> parent, boolean ticked, int count) {
		for (int i = 0; i < count; i++) {
			CheckBoxTreeItem<String> child = new CheckBoxTreeItem<>("child-" + i);
			if (ticked) {
				child.setSelected(true);
			}
			parent.getChildren().add(child);
		}
	}

	@Test
	void aBranchWithEverythingTickedIsTicked() throws Exception {
		onFxThread(() -> {
			CheckBoxTreeItem<String> root = new CheckBoxTreeItem<>("instance");
			CheckBoxTreeItem<String> plugins = new CheckBoxTreeItem<>("plugins");
			children(plugins, true, 3);
			root.getChildren().add(plugins);

			ModpackFilesPage.follow(plugins);
			ModpackFilesPage.refreshBranch(root);

			assertTrue(plugins.isSelected(), "a branch with every child ticked is ticked");
			assertFalse(plugins.isIndeterminate(), "and is not partially ticked");
			assertTrue(root.isSelected(), "an instance whose only branch travels, travels");
		});
	}

	@Test
	void aBranchWithNothingTickedIsNot() throws Exception {
		onFxThread(() -> {
			CheckBoxTreeItem<String> root = new CheckBoxTreeItem<>("instance");
			CheckBoxTreeItem<String> sessions = new CheckBoxTreeItem<>("sessions");
			children(sessions, false, 1);
			root.getChildren().add(sessions);

			ModpackFilesPage.follow(sessions);
			ModpackFilesPage.refreshBranch(root);

			assertFalse(sessions.isSelected(), "nothing ticked under it, nothing ticked on it");
			assertFalse(sessions.isIndeterminate());
			assertFalse(root.isSelected(), "and the instance says so too");
		});
	}

	@Test
	void aBranchWithSomethingTickedIsPartiallyTickedAndSoIsTheOneAboveIt() throws Exception {
		onFxThread(() -> {
			CheckBoxTreeItem<String> root = new CheckBoxTreeItem<>("instance");
			CheckBoxTreeItem<String> plugins = new CheckBoxTreeItem<>("plugins");
			children(plugins, true, 3);
			root.getChildren().add(plugins);

			CheckBoxTreeItem<String> sessions = new CheckBoxTreeItem<>("sessions");
			children(sessions, false, 1);
			root.getChildren().add(sessions);

			ModpackFilesPage.follow(plugins);
			ModpackFilesPage.follow(sessions);
			ModpackFilesPage.refreshBranch(root);

			assertTrue(plugins.isSelected());
			assertTrue(root.isIndeterminate(), "one branch travels and one does not: partly, not fully");
			assertFalse(root.isSelected(), "a partly travelling instance is not a travelling one");
		});
	}

	@Test
	void untickingAChildIsSeenByEveryBranchAboveIt() throws Exception {
		onFxThread(() -> {
			CheckBoxTreeItem<String> root = new CheckBoxTreeItem<>("instance");
			CheckBoxTreeItem<String> plugins = new CheckBoxTreeItem<>("plugins");
			children(plugins, true, 3);
			root.getChildren().add(plugins);
			ModpackFilesPage.follow(plugins);
			ModpackFilesPage.refreshBranch(root);
			assertTrue(root.isSelected(), "before: everything travels");

			((CheckBoxTreeItem<String>) plugins.getChildren().get(0)).setSelected(false);

			assertTrue(plugins.isIndeterminate(), "the branch follows its children");
			assertTrue(root.isIndeterminate(), "and so does the instance");
		});
	}
}
