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
import org.jackhuang.hmcl.util.logging.Logger;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/**
 * Tracks the DeepSeek Harness processes this launcher started.
 */
@NotNullByDefault
public final class DshProcessManager {
	private static final Map<String, DshProcess> RUNNING = new ConcurrentHashMap<>();
	private static final Duration EXIT_GRACE = Duration.ofSeconds(10);
	private static final Set<String> STOPPING = ConcurrentHashMap.newKeySet();
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

	/**
	 * Launches an instance, or returns the one already running for it.
	 *
	 * @param instance the instance to launch
	 * @return the running process handle
	 * @throws DshException when the process cannot start or the instance is stopping
	 */
	public static DshProcess launch(DshInstance instance) throws DshException {
		return launch(instance, null);
	}

	/**
	 * Launches an instance with an account.
	 *
	 * @param instance the instance
	 * @param account  the account to hand the harness, or {@code null} for none
	 * @return the running process handle
	 * @throws DshException when the process cannot start
	 */
	public static DshProcess launch(DshInstance instance, @Nullable DshAccount account) throws DshException {
		return launch(instance, account, null);
	}

	/**
	 * Launches an instance from a plan that has already been built.
	 *
	 * @param instance the instance
	 * @param account  the account, or {@code null} for none
	 * @param prepared the plan to use, or {@code null} to build one
	 * @return the running process handle
	 * @throws DshException when the process cannot start
	 */
	public static DshProcess launch(DshInstance instance,
	                                @Nullable DshAccount account,
	                                @Nullable DshLauncher.LaunchPlan prepared) throws DshException {
		synchronized (LAUNCH_LOCK) {
			DshProcess existing = RUNNING.get(instance.id());
			if (existing != null) {
				if (STOPPING.contains(instance.id())) {
					throw new DshException("Instance " + instance.id() +
					                       " is still stopping; wait for it to exit before starting it again");
				}
				if (existing.isRunning()) {
					return existing;
				}
				RUNNING.remove(instance.id(), existing);
			}

			DshRunningRecord record = DshRunningRecord.read(instance);
			if (record != null) {
				throw new DshException("Instance " + instance.id() + " is already running (pid " + record.pid() +
				                       "), started by another launcher; stop it before starting it again");
			}

			DshProcess process = prepared == null
			                     ? DshProcess.start(instance, account)
			                     : DshProcess.startPrepared(instance, prepared);
			RUNNING.put(instance.id(), process);
			process.setStateListener(state -> {
				if (state == DshProcess.State.STOPPED || state == DshProcess.State.FAILED) {
					RUNNING.remove(instance.id(), process);
					try {
						DshInjectedSettings.settle(process.plan().instance());
					} catch (DshException e) {
						Logger.LOG.warning("Could not restore settings after launch of " +
						                   process.plan().instance().id(), e);
					}
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

	/**
	 * Reports what an instance is doing.
	 *
	 * @param instanceId the instance id
	 * @return the launch state
	 */
	public static LaunchState stateOf(String instanceId) {
		if (STOPPING.contains(instanceId)) {
			return LaunchState.STOPPING;
		}
		DshProcess process = RUNNING.get(instanceId);
		if (process != null && process.isRunning()) {
			return process.state() == DshProcess.State.STARTING ? LaunchState.STARTING : LaunchState.RUNNING;
		}
		DshInstance instance = InstanceStore.find(instanceId);
		if (instance == null) {
			return LaunchState.STOPPED;
		}
		return DshRunningRecord.read(instance) == null ? LaunchState.STOPPED : LaunchState.RUNNING;
	}

	/**
	 * Returns a running instance, whether this launcher started it or another one did.
	 *
	 * @param instance the instance
	 * @return the running instance descriptor, or empty when nothing is running
	 */
	public static Optional<Running> findRunning(DshInstance instance) {
		Optional<DshProcess> process = find(instance.id());
		if (process.isPresent()) {
			return Optional.of(of(process.get()));
		}
		DshRunningRecord record = DshRunningRecord.read(instance);
		return record == null ? Optional.empty() : Optional.of(of(instance.id(), record));
	}

	private static Running of(String instanceId, DshRunningRecord record) {
		return new Running(instanceId,
		                   record.pid(),
		                   record.webUrl(),
		                   record.surface() == null ? "" : record.surface(),
		                   record.uptime());
	}

	private static Running of(DshProcess process) {
		return new Running(process.instance().id(),
		                   process.pid(),
		                   process.webUrl(),
		                   process.plan().surface().name(),
		                   process.uptime());
	}

	/**
	 * Returns the running process for an instance.
	 *
	 * @param instanceId the instance id
	 * @return the process, or empty when not running
	 */
	public static Optional<DshProcess> find(String instanceId) {
		DshProcess process = RUNNING.get(instanceId);
		return process == null || !process.isRunning() ? Optional.empty() : Optional.of(process);
	}

	/**
	 * Lists every instance that is currently running.
	 *
	 * @return running instances, newest first
	 */
	public static List<Running> running() {
		List<Running> instances = new ArrayList<>();
		Set<String> ours = new HashSet<>();
		for (DshProcess process : RUNNING.values()) {
			if (process.isRunning()) {
				instances.add(of(process));
				ours.add(process.instance().id());
			}
		}
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

	/**
	 * Stops the process for an instance, if any.
	 *
	 * @param instanceId the instance id
	 * @return whether a running process was stopped
	 */
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

	public static boolean isStopping(String instanceId) {
		return STOPPING.contains(instanceId);
	}

	/**
	 * Stops every running process.
	 */
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

	static @Nullable DshProcess raw(String instanceId) {
		return RUNNING.get(instanceId);
	}

	public record Running(String instanceId, long pid, Optional<URI> webUrl, String surface, Duration uptime) {
	}

	public enum LaunchState {
		STOPPED,
		STARTING,
		RUNNING,
		STOPPING
	}
}
