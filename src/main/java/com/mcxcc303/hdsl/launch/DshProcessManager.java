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
package com.mcxcc303.hdsl.launch;

import com.mcxcc303.hdsl.account.DshAccount;
import com.mcxcc303.hdsl.account.DshInjectedSettings;
import com.mcxcc303.hdsl.core.DshException;
import com.mcxcc303.hdsl.instance.DshInstance;
import com.mcxcc303.hdsl.instance.InstanceListing;
import com.mcxcc303.hdsl.instance.InstanceStore;
import java.util.function.Consumer;
import org.jackhuang.hmcl.util.logging.Logger;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Tracks the DeepSeek Harness processes this launcher started.
///
/// The registry exists so the user interface can show what is currently
/// running, and so the launcher can guarantee it leaves nothing behind: a
/// JVM shutdown hook stops every child, because an abandoned `dsh web` would
/// keep holding its port and its `DSH_HOME`.
@NotNullByDefault
public final class DshProcessManager {
	/// Running processes, keyed by instance id.
	private static final Map<String, DshProcess> RUNNING = new ConcurrentHashMap<>();
	/// The instances the launcher has asked to stop and which have not finished
	/// stopping.
	///
	/// How long a stop waits for a process another launcher started to exit.
	///
	/// The in-process stop waits on the child itself; this one has only a pid to watch, and a
	/// caller that removes the instance next must not remove its home from under a live server.
	private static final Duration EXIT_GRACE = Duration.ofSeconds(10);

	/// An instance is not startable again until it is out of this set: DeepSeek
	/// Harness holds its port and its home until it has exited, so starting a
	/// second one in the meantime is what let a stopped instance come back on a
	/// different port, and let two servers race one home.
	private static final Set<String> STOPPING = ConcurrentHashMap.newKeySet();
	/// Serialises the check-then-start sequence in [#launch].
	///
	/// Without it, two quick activations of the launch button both observe an
	/// empty registry and start a second server on the same home, which races
	/// the profile files and leaves the extra process untracked.
	private static final Object LAUNCH_LOCK = new Object();
	private static volatile @Nullable Consumer<DshProcess> exitListener;

	static {
		Runtime.getRuntime().addShutdownHook(new Thread(DshProcessManager::stopAll, "HDSL shutdown"));
	}

	private DshProcessManager() {
	}

	public static void setExitListener(@Nullable Consumer<DshProcess> listener) {
		exitListener = listener;
	}

	/// Launches an instance, or returns the one already running for it.
	///
	/// One process per instance is enforced here: DeepSeek Harness has no
	/// single-instance lock of its own, and a second `dsh web` on the same home
	/// would race the profile files.
	///
	/// @param instance the instance to launch
	/// @return the running handle
	/// @throws DshException when the instance is still stopping, the runtime is
	///                       missing, or the process cannot start
	public static DshProcess launch(DshInstance instance) throws DshException {
		return launch(instance, null);
	}

	/// Launches an instance with an account.
	///
	/// @param instance the instance
	/// @param account  the account to hand the harness, or `null` for none
	/// @return the process
	/// @throws DshException when it cannot be started
	public static DshProcess launch(DshInstance instance, @Nullable DshAccount account) throws DshException {
		return launch(instance, account, null);
	}

	/// Launches an instance from a plan that has already been built.
	///
	/// Building a plan is not free of consequence: it writes the account's overlay. A caller that
	/// built one — to print what was about to run, which is what the command line interface does —
	/// must be able to hand that same plan over rather than have a second one built, because the
	/// second build writes a second overlay and the first is never removed by anybody.
	///
	/// @param instance the instance
	/// @param account  the account, or `null` for none
	/// @param prepared the plan to use, or `null` to build one
	/// @return the process
	/// @throws DshException when it cannot be started
	public static DshProcess launch(DshInstance instance,
	                                @Nullable DshAccount account,
	                                @Nullable DshLauncher.LaunchPlan prepared) throws DshException {
		synchronized (LAUNCH_LOCK) {
			DshProcess existing = RUNNING.get(instance.id());
			if (existing != null) {
				// An instance that is still draining must not be started again:
				// it holds its port and its home until it is gone, so a second
				// server would come up on another port, or race the first one
				// through the profile files.
				if (STOPPING.contains(instance.id())) {
					throw new DshException("Instance " + instance.id() +
					                       " is still stopping; wait for it to exit before starting it again");
				}
				if (existing.isRunning()) {
					return existing;
				}
				// It exited without the registry having noticed yet.
				RUNNING.remove(instance.id(), existing);
			}

			// Not one of ours, and possibly running all the same: another launcher may be serving
			// this instance right now, and a second harness on one home races the profile files —
			// which is the whole of what the check above is for among our own, and this is that same
			// check reaching across processes.
			DshRunningRecord record = DshRunningRecord.read(instance);
			if (record != null) {
				throw new DshException("Instance " + instance.id() + " is already running (pid " + record.pid() +
				                       "), started by another launcher; stop it before starting it again");
			}

			// The runtime is resolved inside the launcher, so an instance pinned
			// to a managed Node runtime is honoured here too.
			DshProcess process = prepared == null
			                     ? DshProcess.start(instance, account)
			                     : DshProcess.startPrepared(instance, prepared);
			RUNNING.put(instance.id(), process);
			process.setStateListener(state -> {
				if (state == DshProcess.State.STOPPED || state == DshProcess.State.FAILED) {
					RUNNING.remove(instance.id(), process);
					// Whatever this launch put into the home's own settings goes back to what it
					// was, so the next launch — of any kind, with an account or with none — starts
					// on the person's own configuration rather than on this launch's leftovers.
					// The account's route in the profile's patch layer goes back with it: that is
					// what `settle` puts back, and it is why a launch with a key leaves no trace of
					// it once the instance has stopped.
					try {
						DshInjectedSettings.settle(process.plan().instance());
					} catch (DshException e) {
						Logger.LOG.warning("Could not put back what the launch of " + process.plan().instance().id() +
						                   " left in its settings", e);
					}
					// An instance that dies **after** it was up is the case a message box is least
					// able to help with and the crash dialog most: it was working a moment ago, so
					// the question is what it said on the way out, and the answer is in the output
					// the dialog carries. Reported from here rather than from the launch path
					// because this listener is the one place that sees every ending, whenever it
					// happens — a server that fell over an hour after it started never goes through
					// the launch path again.
					// `isCrash` rather than `state == FAILED`, and that distinction is the whole
					// reason this did not work the first time: `DshProcess` maps the exit of a
					// process that had been **ready** to `STOPPED` whatever its exit code, because
					// for its own purposes "it was up and now it is not" is the same thing either
					// way. So a crash after a successful start arrives here as `STOPPED`, and
					// testing for `FAILED` misses exactly the case this dialog was written for.
					Consumer<DshProcess> listener = exitListener;
					if (listener != null) {
						try {
							listener.accept(process);
						} catch (RuntimeException e) {
							LOG.warning("Exit listener failed", e);
						}
					}
				}
			});
			return process;
		}
	}

	/// Reports what an instance is doing.
	///
	/// @param instanceId the instance id
	/// @return the state, never `null`
	public static LaunchState stateOf(String instanceId) {
		if (STOPPING.contains(instanceId)) {
			return LaunchState.STOPPING;
		}
		DshProcess process = RUNNING.get(instanceId);
		if (process != null && process.isRunning()) {
			return process.state() == DshProcess.State.STARTING ? LaunchState.STARTING : LaunchState.RUNNING;
		}
		// Not one of ours — and an instance another launcher is running is not stopped, which is
		// what this answered before there was a record to ask. What it cannot say is whether such
		// an instance is still coming up: that is the other launcher's to know, and it is up by the
		// time anybody can read about it from here.
		DshInstance instance = InstanceStore.find(instanceId);
		if (instance == null) {
			return LaunchState.STOPPED;
		}
		return DshRunningRecord.read(instance) == null ? LaunchState.STOPPED : LaunchState.RUNNING;
	}

	/// Returns a running instance, whether this launcher started it or another one did.
	///
	/// The handle this JVM holds comes first, because it is the process itself, with its output and
	/// its state. What this falls back to is the record the launcher that started it left on disk —
	/// which is the difference between "nothing is running" and "I did not start it".
	///
	/// @param instance the instance
	/// @return the running instance, or empty when nothing is running
	public static Optional<Running> findRunning(DshInstance instance) {
		Optional<DshProcess> process = find(instance.id());
		if (process.isPresent()) {
			return Optional.of(of(process.get()));
		}
		DshRunningRecord record = DshRunningRecord.read(instance);
		return record == null ? Optional.empty() : Optional.of(of(instance.id(), record));
	}

	/// Returns how an instance is running, for a caller that has the record and not the process.
	private static Running of(String instanceId, DshRunningRecord record) {
		return new Running(instanceId,
		                   record.pid(),
		                   record.webUrl(),
		                   record.surface() == null ? "" : record.surface(),
		                   record.uptime());
	}

	/// Returns how an instance is running, for a caller that has the process in hand.
	private static Running of(DshProcess process) {
		return new Running(process.instance().id(),
		                   process.pid(),
		                   process.webUrl(),
		                   process.plan().surface().name(),
		                   process.uptime());
	}

	/// Returns the running process for an instance.
	///
	/// @param instanceId the instance id
	/// @return the process, or empty when the instance is not running
	public static Optional<DshProcess> find(String instanceId) {
		DshProcess process = RUNNING.get(instanceId);
		return process == null || !process.isRunning() ? Optional.empty() : Optional.of(process);
	}

	/// Lists every instance that is running, whoever started it.
	///
	/// @return the running instances, newest first
	public static List<Running> running() {
		List<Running> instances = new ArrayList<>();
		Set<String> ours = new HashSet<>();
		for (DshProcess process : RUNNING.values()) {
			if (process.isRunning()) {
				instances.add(of(process));
				ours.add(process.instance().id());
			}
		}
		// The map above holds only what this JVM started, and that is the whole of what it ever
		// will: the records on disk are how the rest of them are found, and the ids already in the
		// map are what keeps one instance from being listed twice.
		for (DshInstance instance : InstanceListing.list()) {
			if (ours.contains(instance.id())) {
				continue;
			}
			DshRunningRecord record = DshRunningRecord.read(instance);
			if (record != null) {
				instances.add(of(instance.id(), record));
			}
		}
		instances.sort((a, b) -> b.uptime().compareTo(a.uptime()));
		return instances;
	}

	/// Stops the process for an instance, if any.
	///
	/// The instance stays in the registry for as long as it takes the child to
	/// exit, and is only then forgotten. Dropping it first would leave a window
	/// in which the launcher believed nothing was running while a server was
	/// still holding its port and its home — which is exactly the window a quick
	/// stop-then-start used to slip through.
	///
	/// @param instanceId the instance id
	/// @return whether a running process was stopped
	public static boolean stop(String instanceId) {
		DshProcess process = RUNNING.get(instanceId);
		if (process == null) {
			return stopWhatAnotherLauncherRuns(instanceId);
		}
		STOPPING.add(instanceId);
		try {
			process.stop();
		} finally {
			STOPPING.remove(instanceId);
			RUNNING.remove(instanceId, process);
		}
		return true;
	}

	/// Stops an instance another launcher is running, which is all that can be done about it here.
	///
	/// What this has to go on is the record: the pid is the process, and signalling it is what the
	/// other launcher's own stop would have done. The record is deleted once the process is gone, so
	/// that the next reader does not have to work out for itself that it is stale.
	///
	/// @param instanceId the instance id
	/// @return whether a running process was stopped
	private static boolean stopWhatAnotherLauncherRuns(String instanceId) {
		DshInstance instance = InstanceStore.find(instanceId);
		if (instance == null) {
			return false;
		}
		DshRunningRecord record = DshRunningRecord.read(instance);
		if (record == null) {
			return false;
		}
		ProcessHandle handle = ProcessHandle.of(record.pid()).orElse(null);
		if (handle == null || !handle.isAlive()) {
			DshRunningRecord.delete(instance);
			return false;
		}
		LOG.info("Stopping instance " + instanceId + " (pid " + record.pid() + "), started by another launcher");
		handle.destroy();
		Instant deadline = Instant.now().plus(EXIT_GRACE);
		while (handle.isAlive() && Instant.now().isBefore(deadline)) {
			try {
				Thread.sleep(50);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				break;
			}
		}
		DshRunningRecord.delete(instance);
		return true;
	}

	/// Reports whether an instance has been asked to stop and has not finished.
	///
	/// @param instanceId the instance id
	/// @return whether it is stopping
	public static boolean isStopping(String instanceId) {
		return STOPPING.contains(instanceId);
	}

	/// Stops every running process.
	///
	/// Called from the JVM shutdown hook; also safe to call directly.
	public static void stopAll() {
		for (DshProcess process : List.copyOf(RUNNING.values())) {
			try {
				process.stop();
			} catch (RuntimeException e) {
				LOG.warning("Failed to stop " + process.instance().id(), e);
			}
		}
		RUNNING.clear();
	}

	/// Returns the process for an instance without checking liveness.
	///
	/// @param instanceId the instance id
	/// @return the handle, or `null`
	static @Nullable DshProcess raw(String instanceId) {
		return RUNNING.get(instanceId);
	}

	/// A running instance, as any process can see it.
	///
	/// @param instanceId the instance
	/// @param pid        the harness process
	/// @param webUrl     the address it serves, while it has reported one
	/// @param surface    the surface it was booted into
	/// @param uptime     how long it has been running
	public record Running(String instanceId, long pid, Optional<URI> webUrl, String surface, Duration uptime) {
	}

	/// What an instance is doing, as far as starting and stopping it goes.
	///
	/// One answer for every place that offers to start or stop something: the
	/// home page's launch button, the instance list's rocket, an instance's own
	/// page and the instance picker all show what this says, so no two of them
	/// can disagree about whether an instance is running — which is what decides
	/// whether pressing them starts a server or stops the one already there.
	public enum LaunchState {
		/// Nothing is running and nothing is on its way up.
		STOPPED,
		/// Started, still coming up: the browser surface has not reported yet.
		STARTING,
		/// Up and serving.
		RUNNING,
		/// Asked to stop, still draining.
		STOPPING
	}
}
