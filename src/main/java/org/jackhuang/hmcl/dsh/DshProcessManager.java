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

    /// Running processes, keyed by instance id.
    private static final Map<String, DshProcess> RUNNING = new ConcurrentHashMap<>();

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
    /// @throws DshException when the runtime is missing or the process cannot start
    public static DshProcess launch(DshInstance instance) throws DshException {
        DshProcess existing = RUNNING.get(instance.id());
        if (existing != null && existing.isRunning()) {
            return existing;
        }

        DshNodeRuntime runtime = DshNodeRuntime.detect()
                .orElseThrow(() -> new DshException("Node.js was not found on PATH; " + DshNodeRuntime.requirement()));

        DshProcess process = DshProcess.start(instance, runtime);
        RUNNING.put(instance.id(), process);
        process.setStateListener(state -> {
            if (state == DshProcess.State.STOPPED || state == DshProcess.State.FAILED) {
                RUNNING.remove(instance.id(), process);
            }
        });
        return process;
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
    /// @param instanceId the instance id
    /// @return whether a running process was stopped
    public static boolean stop(String instanceId) {
        DshProcess process = RUNNING.remove(instanceId);
        if (process == null) {
            return false;
        }
        process.stop();
        return true;
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
