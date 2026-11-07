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
package org.jackhuang.hmcl.ui.construct;

import javafx.application.Platform;
import org.jackhuang.hmcl.ui.FxToolkit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests verifying that inheritable controls update their indicator icon based on override state.
 */
class InheritMarkTest {

	@BeforeAll
	static void startToolkit() {
		FxToolkit.requireRunning();
	}

	@Test
	void theTextFieldMarkFollowsTheOverrideState() throws Exception {
		onFxThread(() -> {
			LineInheritableTextField row = new LineInheritableTextField("Command before an instance starts");

			row.setOverridden(false);
			Object inherited = markOf(row);
			assertNotNull(inherited, "the row has no mark beside its name");

			row.setOverridden(true);
			Object overridden = markOf(row);
			assertNotSame(inherited, overridden, "the mark does not change when the row takes the setting over");

			row.setOverridden(false);
			assertNotSame(overridden, markOf(row), "the mark does not change back when the row follows again");
		});
	}

	@Test
	void aToggleRowFlipsTheValueItIsShowing() throws Exception {
		onFxThread(() -> {
			LineInheritableSwitch row = new LineInheritableSwitch();

			row.setOverridden(false);
			row.setRawValue(false);

			row.fire();
			assertTrue(row.isOverridden(), "pressing the row takes the setting over");
			assertTrue(row.getRawValue(), "pressing a row that shows off turns it on");

			row.fire();
			assertFalse(row.getRawValue(), "pressing it again turns it off");

			row.fire();
			assertTrue(row.getRawValue(), "and it keeps flipping");
		});
	}

	private static void onFxThread(Runnable work) throws Exception {
		FutureTask<Void> task = new FutureTask<>(work, null);
		Platform.runLater(task);
		task.get(30, TimeUnit.SECONDS);
	}

	private static Object markOf(LineComponent row) {
		return row.getTitleTrailing() instanceof com.jfoenix.controls.JFXButton button
				? button.getGraphic() : null;
	}
}
