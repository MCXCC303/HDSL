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

import javafx.application.Platform;
import org.jackhuang.hmcl.ui.FxToolkit;
import org.jackhuang.hmcl.util.i18n.I18n;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@NotNullByDefault
class DshInstallProgressTest {
	@BeforeAll
	static void startToolkit() {
		FxToolkit.requireRunning();
	}

	@Test
	void theFetchedAndReusedPackagesCountTowardsTheFraction() throws InterruptedException {
		DshInstallProgress progress = new DshInstallProgress();
		progress.accept("Progress: resolved 200, reused 90, downloaded 10, added 0");

		assertEquals(I18n.i18n("dsh.install.progress.fetching", 100, 200), messageOf(progress));
		assertEquals(0.5, fractionOf(progress), 1e-9);
	}

	@Test
	void theWrittenPackagesCountTowardsTheFraction() throws InterruptedException {
		DshInstallProgress progress = new DshInstallProgress();
		progress.accept("Progress: resolved 100, reused 90, downloaded 10, added 30");

		assertEquals(I18n.i18n("dsh.install.progress.pnpm", 30, 100, 10, 90), messageOf(progress));
		assertEquals(0.3, fractionOf(progress), 1e-9);
	}

	@Test
	void theResolvingPhaseReportsNoFraction() throws InterruptedException {
		DshInstallProgress progress = new DshInstallProgress();
		progress.accept("Progress: resolved 100, reused 0, downloaded 0, added 0");

		assertEquals(I18n.i18n("dsh.install.progress.resolving", 100), messageOf(progress));
		assertEquals(-1.0, fractionOf(progress), 1e-9);
	}

	@Test
	void theDownloadPercentageBecomesTheFraction() throws InterruptedException {
		DshInstallProgress progress = new DshInstallProgress();
		progress.accept("Downloading 42%");

		assertEquals(I18n.i18n("dsh.node.download.progress", 42), messageOf(progress));
		assertEquals(0.42, fractionOf(progress), 1e-9);
	}

	@Test
	void anUnrelatedLineKeepsTheMessage() throws InterruptedException {
		DshInstallProgress progress = new DshInstallProgress();
		progress.accept("npm notice");

		assertEquals("", messageOf(progress));
		assertEquals(-1.0, fractionOf(progress), 1e-9);
	}

	private static String messageOf(DshInstallProgress progress) throws InterruptedException {
		awaitInterfaceThread();
		return progress.messageProperty().get();
	}

	private static double fractionOf(DshInstallProgress progress) throws InterruptedException {
		awaitInterfaceThread();
		return progress.fractionProperty().get();
	}

	private static void awaitInterfaceThread() throws InterruptedException {
		CountDownLatch done = new CountDownLatch(1);
		Platform.runLater(done::countDown);
		assertTrue(done.await(15, TimeUnit.SECONDS), "the interface thread did not run the work");
	}
}
