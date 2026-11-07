package org.jackhuang.hmcl.ui.construct;

import com.jfoenix.controls.JFXCheckBox;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import org.jackhuang.hmcl.ui.FxToolkit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Tests verifying checkbox rendering states for checked, unchecked, and indeterminate conditions.
 */
class CheckBoxMarksTest {

	@BeforeAll
	static void startToolkit() {
		FxToolkit.requireRunning();
	}

	private static void onFxThread(Runnable work) throws Exception {
		FutureTask<Void> task = new FutureTask<>(work, null);
		Platform.runLater(task);
		task.get(30, TimeUnit.SECONDS);
	}

	private record Marks(boolean tick, boolean bar) {
	}

	private static Marks marksOf(JFXCheckBox box) {
		StackPane root = new StackPane(box);
		new Scene(root, 200, 60);
		root.applyCss();
		root.layout();
		assertInstanceOf(com.jfoenix.skins.JFXCheckBoxSkin.class, box.getSkin());
		((com.jfoenix.skins.JFXCheckBoxSkin) box.getSkin()).finishSelectionAnimation();

		Node tick = box.lookup(".jfx-check-mark");
		Node bar = box.lookup(".jfx-indeterminate-mark");
		assertNotNull(tick, "the skin draws no check mark");
		assertNotNull(bar, "the skin draws no partly-ticked bar");
		return new Marks(tick.isVisible(), bar.isVisible());
	}

	@Test
	void aBoxThatIsNeitherTickedNorPartlyTickedShowsNeitherMark() throws Exception {
		onFxThread(() -> {
			JFXCheckBox box = new JFXCheckBox();
			Marks marks = marksOf(box);
			assertFalse(marks.tick());
			assertFalse(marks.bar());
		});
	}

	@Test
	void aTickedBoxShowsATickAndNoBar() throws Exception {
		onFxThread(() -> {
			JFXCheckBox box = new JFXCheckBox();
			box.setSelected(true);
			Marks marks = marksOf(box);
			assertTrue(marks.tick());
			assertFalse(marks.bar());
		});
	}

	@Test
	void aPartlyTickedBoxShowsABarRatherThanATick() throws Exception {
		onFxThread(() -> {
			JFXCheckBox box = new JFXCheckBox();
			box.setAllowIndeterminate(false);
			box.setIndeterminate(true);
			Marks marks = marksOf(box);
			assertTrue(marks.bar(),
					"a branch whose children disagree is neither ticked nor empty, and has to look it");
			assertFalse(marks.tick(), "the tick gives way to the bar");
		});
	}
}
