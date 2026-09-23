/*
 * HMCL-DSH
 * Copyright (C) 2026  HMCL-DSH contributors
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
package org.jackhuang.hmcl.dsh;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    private DshProcessManager() {
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

    /// Running processes, keyed by instance id.
    private static final Map<String, DshProcess> RUNNING = new ConcurrentHashMap<>();

    /// The instances the launcher has asked to stop and which have not finished
    /// stopping.
    ///
    /// An instance is not startable again until it is out of this set: DeepSeek
    /// Harness holds its port and its home until it has exited, so starting a
    /// second one in the meantime is what let a stopped instance come back on a
    /// different port, and let two servers race one home.
    private static final java.util.Set<String> STOPPING = ConcurrentHashMap.newKeySet();

    /// Serialises the check-then-start sequence in [#launch].
    ///
    /// Without it, two quick activations of the launch button both observe an
    /// empty registry and start a second server on the same home, which races
    /// the profile files and leaves the extra process untracked.
    private static final Object LAUNCH_LOCK = new Object();

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(DshProcessManager::stopAll, "HMCL-DSH shutdown"));
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
    public static DshProcess launch(DshInstance instance, @Nullable DshAccount account)
            throws DshException {
        synchronized (LAUNCH_LOCK) {
            DshProcess existing = RUNNING.get(instance.id());
            if (existing != null) {
                // An instance that is still draining must not be started again:
                // it holds its port and its home until it is gone, so a second
                // server would come up on another port, or race the first one
                // through the profile files.
                if (STOPPING.contains(instance.id())) {
                    throw new DshException("Instance " + instance.id()
                            + " is still stopping; wait for it to exit before starting it again");
                }
                if (existing.isRunning()) {
                    return existing;
                }
                // It exited without the registry having noticed yet.
                RUNNING.remove(instance.id(), existing);
            }

            // The runtime is resolved inside the launcher, so an instance pinned
            // to a managed Node runtime is honoured here too.
            DshProcess process = DshProcess.start(instance, account);
            RUNNING.put(instance.id(), process);
            process.setStateListener(state -> {
                if (state == DshProcess.State.STOPPED || state == DshProcess.State.FAILED) {
                    RUNNING.remove(instance.id(), process);
                    // The account overlay belonged to this launch. Removing it here means an
                    // instance that was started with a key is an instance with no trace of it once
                    // it has stopped.
                    DshAccountOverlay.remove(process.plan().accountOverlay());
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
        if (process == null || !process.isRunning()) {
            return LaunchState.STOPPED;
        }
        return process.state() == DshProcess.State.STARTING ? LaunchState.STARTING : LaunchState.RUNNING;
    }

    /// Returns the running process for an instance.
    ///
    /// @param instanceId the instance id
    /// @return the process, or empty when the instance is not running
    public static Optional<DshProcess> find(String instanceId) {
        DshProcess process = RUNNING.get(instanceId);
        return process == null || !process.isRunning() ? Optional.empty() : Optional.of(process);
    }

    /// Lists every instance that is currently running.
    ///
    /// @return the running handles, newest first
    public static List<DshProcess> running() {
        List<DshProcess> processes = new ArrayList<>();
        for (DshProcess process : RUNNING.values()) {
            if (process.isRunning()) {
                processes.add(process);
            }
        }
        processes.sort((a, b) -> b.uptime().compareTo(a.uptime()));
        return processes;
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
            return false;
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
}
