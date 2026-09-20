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

import com.google.gson.annotations.SerializedName;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/// One launcher instance: a pinned DeepSeek Harness version plus the state it
/// operates on.
///
/// An instance is deliberately thin. It names an installed [DshVersion], a
/// profile inside that home, the working directory sessions are scoped to, and
/// how the `DSH_HOME` is resolved. Everything else — plugins, sessions,
/// credentials — belongs to the home directory and is therefore owned by
/// DeepSeek Harness rather than mirrored here.
///
/// Instances are immutable; [DshInstanceManager] replaces them on edit.
@NotNullByDefault
public record DshInstance(
        @SerializedName("id") String id,
        @SerializedName("version") String version,
        @SerializedName("profile") String profile,
        @SerializedName("workspace") String workspace,
        @SerializedName("nodeRuntime") @Nullable String nodeRuntime,
        @SerializedName("homeMode") DshHomeMode homeMode,
        @SerializedName("customHome") @Nullable String customHome,
        @SerializedName("arguments") @Unmodifiable List<String> extraArguments,
        @SerializedName("environment") @Unmodifiable Map<String, String> environment,
        @SerializedName("portMode") @Nullable DshPortMode portMode,
        @SerializedName("port") int port,
        @SerializedName("createdAt") long createdAt) {

    /// The profile booted when this instance is launched.
    public static final String DEFAULT_PROFILE = "web";

    /// Returns the port policy, defaulting to automatic.
    ///
    /// @return the port mode, never `null`
    public DshPortMode portModeOrDefault() {
        return portMode == null ? DshPortMode.AUTO : portMode;
    }

    /// Returns the port to pass to DeepSeek Harness.
    ///
    /// For a fixed instance this is the port the user chose. For an automatic
    /// one it is the port the instance was first given, or `0` before that has
    /// happened.
    ///
    /// @return the port, or `0` when none has been settled on yet
    public int portOrDefault() {
        return Math.max(port, 0);
    }

    /// Returns a copy with a different port.
    ///
    /// @param newPort the port
    /// @return the copy
    public DshInstance withPort(int newPort) {
        return new DshInstance(id, version, profile, workspace, nodeRuntime, homeMode, customHome,
                extraArguments, environment, portMode, newPort, createdAt);
    }

    /// Returns a copy with a different port policy.
    ///
    /// @param newMode the policy
    /// @param newPort the fixed port, ignored when the policy is automatic
    /// @return the copy
    public DshInstance withPortPolicy(DshPortMode newMode, int newPort) {
        return new DshInstance(id, version, profile, workspace, nodeRuntime, homeMode, customHome,
                extraArguments, environment, newMode, newPort, createdAt);
    }

    /// Returns the Node runtime this instance runs on.
    ///
    /// `system` means whatever `node` is on `PATH`; anything else names a runtime
    /// installed under `runtimes/<version>/`.
    ///
    /// @return the runtime selection, never `null`
    public String nodeRuntimeOrDefault() {
        String value = nodeRuntime;
        return value == null || value.isBlank() ? DshNodeRuntime.SYSTEM : value;
    }

    /// Returns the working directory sessions are scoped to.
    ///
    /// Paths are stored as strings rather than as [Path] values: the manifest is
    /// a user-visible file, and Gson cannot serialise [Path] inside a record
    /// without a custom adapter.
    ///
    /// @return the absolute workspace path
    public Path workspacePath() {
        return Path.of(workspace).toAbsolutePath().normalize();
    }

    /// Returns the configured custom home.
    ///
    /// @return the absolute custom home, or `null` when none is configured
    public @Nullable Path customHomePath() {
        String value = customHome;
        return value == null ? null : Path.of(value).toAbsolutePath().normalize();
    }

    /// Resolves the `DSH_HOME` this instance must be launched with.
    ///
    /// @return the absolute home directory for this instance
    /// @throws DshException when a custom home was requested but not configured,
    ///                       or when a path segment is unusable
    public Path homeDirectory() throws DshException {
        return switch (homeMode) {
            case ISOLATED -> DshPaths.instanceDirectory(id).resolve("home").toAbsolutePath().normalize();
            case VERSION_SHARED -> DshPaths.versionDirectory(version).resolve(".dsh-home").toAbsolutePath().normalize();
            case CUSTOM -> {
                Path home = customHomePath();
                if (home == null) {
                    throw new DshException("Instance " + id + " is set to use a custom DSH_HOME but none is configured");
                }
                yield home.toAbsolutePath().normalize();
            }
        };
    }

    /// Returns this instance's directory, which holds `instance.json` and the
    /// isolated home when [#homeMode] is [DshHomeMode#ISOLATED].
    ///
    /// @return the instance directory
    /// @throws DshException when the id cannot be used as a directory name
    public Path instanceDirectory() throws DshException {
        return DshPaths.instanceDirectory(id);
    }

    /// Reports whether this instance shares its home with other instances.
    ///
    /// The user interface uses this to show a warning next to the instance.
    ///
    /// @return whether the home is not private to this instance
    public boolean sharesHome() {
        return homeMode != DshHomeMode.ISOLATED;
    }

    /// Creates a new instance with a different profile.
    ///
    /// @param newProfile the profile to boot
    /// @return the updated instance
    public DshInstance withProfile(String newProfile) {
        return new DshInstance(id, version, newProfile, workspace, nodeRuntime, homeMode, customHome,
                extraArguments, environment, DshPortMode.AUTO, 0, createdAt);
    }

    /// Creates a new instance pinned to a different version.
    ///
    /// @param newVersion the version to pin
    /// @return the updated instance
    public DshInstance withVersion(String newVersion) {
        return new DshInstance(id, newVersion, profile, workspace, nodeRuntime, homeMode, customHome,
                extraArguments, environment, DshPortMode.AUTO, 0, createdAt);
    }

    /// Creates a new instance pinned to a different Node runtime.
    ///
    /// @param runtime the runtime selection, or `null` for the system runtime
    /// @return the updated instance
    public DshInstance withNodeRuntime(@Nullable String runtime) {
        return new DshInstance(id, version, profile, workspace, runtime, homeMode, customHome,
                extraArguments, environment, DshPortMode.AUTO, 0, createdAt);
    }

    /// Creates a new instance with a different home policy.
    ///
    /// @param mode the new policy
    /// @param home the custom home, required when `mode` is [DshHomeMode#CUSTOM]
    /// @return the updated instance
    public DshInstance withHome(DshHomeMode mode, @Nullable Path home) {
        return new DshInstance(id, version, profile, workspace, nodeRuntime, mode,
                home == null ? null : home.toAbsolutePath().normalize().toString(),
                extraArguments, environment, DshPortMode.AUTO, 0, createdAt);
    }

    /// Creates a new instance with different launch arguments and environment.
    ///
    /// @param arguments   the extra command-line arguments
    /// @param environment the extra environment variables
    /// @return the updated instance
    public DshInstance withLaunchOptions(@Unmodifiable List<String> arguments,
                                         @Unmodifiable Map<String, String> environment) {
        return new DshInstance(id, version, profile, workspace, nodeRuntime, homeMode, customHome,
                arguments, environment, DshPortMode.AUTO, 0, createdAt);
    }
}
