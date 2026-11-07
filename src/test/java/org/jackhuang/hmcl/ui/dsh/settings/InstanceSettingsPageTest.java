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

import com.jfoenix.controls.JFXTextField;
import com.mcxcc303.hdsl.instance.DshHomeMode;
import com.mcxcc303.hdsl.instance.DshInstance;
import com.mcxcc303.hdsl.instance.DshInstanceManager;
import com.mcxcc303.hdsl.instance.DshInstanceSettings;
import com.mcxcc303.hdsl.instance.DshPortMode;
import com.mcxcc303.hdsl.instance.InstanceStore;
import com.mcxcc303.hdsl.runtime.DshNodeRuntime;
import javafx.application.Platform;
import org.jackhuang.hmcl.ui.FxToolkit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies how the instance settings page presents and stores the fixed port.
 */
class InstanceSettingsPageTest {
	private static final String INSTANCE_ID = "settings-page";
	private @TempDir Path workspace;

	@BeforeAll
	static void startToolkit() {
		FxToolkit.requireRunning();
	}

	@AfterEach
	void removeInstance() {
		try {
			DshInstanceManager.delete(INSTANCE_ID);
		} catch (Exception ignored) {
		}
	}

	/**
	 * An invalid remembered port must not turn into a new random port merely by opening the page.
	 */
	@Test
	void anOutOfRangeStoredPortIsLeftForTheUserInsteadOfBeingReplacedByAReservation() throws Exception {
		DshInstance instance = fixedInstance(80);
		DshInstanceSettings.setAutoPort(instance, 0);

		JFXTextField field = onFxThread(() -> {
			InstanceSettingsPage page = new InstanceSettingsPage(instance, () -> {
			});
			return portFieldOf(page);
		});

		assertEquals("", field.getText(), "opening the page must not reserve and show an unrelated port");
		assertEquals(80, InstanceStore.find(INSTANCE_ID).portOrDefault(), "opening the page must not write");
	}

	/**
	 * An invalid edit is reported and put back to the port the instance already had.
	 */
	@Test
	void anInvalidEditFallsBackToTheStoredPortWithoutWriting() throws Exception {
		DshInstance instance = fixedInstance(4097);

		JFXTextField field = onFxThread(() -> {
			InstanceSettingsPage page = new InstanceSettingsPage(instance, () -> {
			});
			applyPort(page, "80");
			return portFieldOf(page);
		});

		assertEquals("4097", field.getText(), "the field is put back to the stored port");
		assertEquals(4097, InstanceStore.find(INSTANCE_ID).portOrDefault(), "an invalid edit is not written");
	}

	/**
	 * A valid edit is written when the field is committed.
	 */
	@Test
	void aValidEditIsStoredWhenTheFieldIsCommitted() throws Exception {
		DshInstance instance = fixedInstance(4097);

		JFXTextField field = onFxThread(() -> {
			InstanceSettingsPage page = new InstanceSettingsPage(instance, () -> {
			});
			applyPort(page, "4098");
			return portFieldOf(page);
		});

		assertEquals("4098", field.getText());
		assertEquals(4098, InstanceStore.find(INSTANCE_ID).portOrDefault(), "a valid edit is written");
	}

	private DshInstance fixedInstance(int port) throws Exception {
		try {
			DshInstanceManager.delete(INSTANCE_ID);
		} catch (Exception ignored) {
		}
		DshInstance instance = DshInstanceManager.create(INSTANCE_ID,
		                                                 "1.0.0",
		                                                 DshInstance.DEFAULT_PROFILE,
		                                                 workspace,
		                                                 DshNodeRuntime.SYSTEM,
		                                                 DshHomeMode.ISOLATED,
		                                                 null,
		                                                 List.of(),
		                                                 Map.of());
		DshInstanceManager.update(instance.withPortPolicy(DshPortMode.FIXED, port));
		return InstanceStore.find(INSTANCE_ID);
	}

	private static JFXTextField portFieldOf(InstanceSettingsPage page) throws Exception {
		Field field = InstanceSettingsPage.class.getDeclaredField("portField");
		field.setAccessible(true);
		return (JFXTextField) field.get(page);
	}

	private static void applyPort(InstanceSettingsPage page, String text) throws Exception {
		Method method = InstanceSettingsPage.class.getDeclaredMethod("applyPort", String.class);
		method.setAccessible(true);
		method.invoke(page, text);
	}

	private static <T> T onFxThread(ThrowingSupplier<T> work) throws Exception {
		FutureTask<T> task = new FutureTask<>(work::get);
		Platform.runLater(task);
		return task.get(30, TimeUnit.SECONDS);
	}

	private interface ThrowingSupplier<T> {
		T get() throws Exception;
	}
}
