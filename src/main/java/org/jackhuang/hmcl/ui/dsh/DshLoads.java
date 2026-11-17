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

import com.mcxcc303.hdsl.core.DshDeadlines;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.ui.construct.SpinnerPane;
import org.jetbrains.annotations.NotNullByDefault;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.jackhuang.hmcl.ui.FXUtils.runInFX;
import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

@NotNullByDefault
public final class DshLoads {
	private static final DshLoads REGISTRY = new DshLoads(DshDeadlines.REGISTRY);
	private final Duration deadline;

	DshLoads(Duration deadline) {
		this.deadline = deadline;
	}

	public static DshLoads registry() {
		return REGISTRY;
	}

	public <T> void run(SpinnerPane spinner,
			Callable<T> work,
			Consumer<T> onLoaded,
			Consumer<Throwable> onFailed) {
		spinner.setFailedReason(null);
		spinner.setLoading(true);

		CompletableFuture<T> load = CompletableFuture.supplyAsync(() -> {
			try {
				return work.call();
			} catch (Exception e) {
				throw new CompletionException(e);
			}
		}, Schedulers.io());
		load.orTimeout(deadline.toMillis(), TimeUnit.MILLISECONDS);
		load.whenComplete((value, failure) -> runInFX(() -> {
			spinner.setLoading(false);
			if (failure == null) {
				onLoaded.accept(value);
			} else {
				onFailed.accept(cause(failure));
			}
		}));
	}

	public String message(String subject, Throwable failure) {
		String reason = cause(failure).getMessage();
		if (reason == null || reason.isBlank()) {
			reason = subject;
		} else {
			reason = subject + ": " + reason;
		}
		return i18n("dsh.load.failed", reason);
	}

	public String message(Throwable failure) {
		Throwable cause = cause(failure);
		String reason = cause.getMessage();
		if (reason == null || reason.isBlank()) {
			reason = cause.toString();
		}
		return i18n("dsh.load.failed", reason);
	}

	private static Throwable cause(Throwable failure) {
		return failure instanceof CompletionException && failure.getCause() != null
				? failure.getCause()
				: failure;
	}
}