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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Adds and removes profile plugins by driving `dsh plugin`.
///
/// The launcher deliberately does not talk to npm itself. Upstream already
/// ships a forwarder — `dsh plugin --profile <name> <pnpm args>` runs `pnpm`
/// inside the profile directory and then reconciles `dsh.profile.bundles` — so
/// shelling out to it keeps the lock handling, the bundle reconciliation and
/// the failure modes exactly as upstream defines them.
///
/// All calls run against the instance's own `DSH_HOME` and its pinned `dsh`
/// installation, so they cannot disturb another instance or the user's global
/// `dsh`.
@NotNullByDefault
public final class DshPluginInstaller {
    private DshPluginInstaller() {
    }

    /// Installs every given preset into an instance's profile.
    ///
    /// Presets are installed one at a time so that a failure names the package
    /// that caused it, and so partial progress is visible in the log.
    ///
    /// @param instance the instance whose profile is modified
    /// @param presets  the presets to install
    /// @param onLine   receives every output line, or `null`
    /// @throws DshException when the runtime or version is unavailable, `pnpm`
    ///                       is missing, or an install fails
    public static void install(DshInstance instance, List<DshPreset> presets,
                               @Nullable Consumer<String> onLine) throws DshException {
        if (presets.isEmpty()) {
            return;
        }

        DshNodeRuntime runtime = DshLauncher.resolveRuntime(instance);
        if (!runtime.canManagePlugins()) {
            throw new DshException("pnpm was not found on PATH; installing plugins requires it");
        }

        Path home = instance.homeDirectory();
        for (DshPreset preset : presets) {
            report(onLine, "Installing " + preset.spec() + " ...");
            runPluginCommand(instance, runtime, home, List.of("add", preset.spec()), onLine);
            report(onLine, "Installed " + preset.spec());
        }
    }

    /// Removes a package from an instance's profile.
    ///
    /// @param instance the instance whose profile is modified
    /// @param spec     the package spec to remove
    /// @param onLine   receives every output line, or `null`
    /// @throws DshException when the removal fails
    public static void remove(DshInstance instance, String spec, @Nullable Consumer<String> onLine)
            throws DshException {
        DshNodeRuntime runtime = DshLauncher.resolveRuntime(instance);
        if (!runtime.canManagePlugins()) {
            throw new DshException("pnpm was not found on PATH; removing plugins requires it");
        }
        runPluginCommand(instance, runtime, instance.homeDirectory(), List.of("remove", spec), onLine);
    }

    /// Reads the bundle list a profile declares.
    ///
    /// @param home    the `DSH_HOME` holding the profile
    /// @param profile the profile name
    /// @return the declared bundle package names, in load order
    public static List<String> readBundles(Path home, String profile) {
        Path manifest = home.resolve("profiles").resolve(profile).resolve("package.json");
        if (!Files.isRegularFile(manifest)) {
            return List.of();
        }
        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(manifest));
            if (!parsed.isJsonObject()) {
                return List.of();
            }
            JsonObject dsh = parsed.getAsJsonObject().getAsJsonObject("dsh");
            if (dsh == null) {
                return List.of();
            }
            JsonObject profileObject = dsh.getAsJsonObject("profile");
            if (profileObject == null) {
                return List.of();
            }
            JsonArray bundles = profileObject.getAsJsonArray("bundles");
            if (bundles == null) {
                return List.of();
            }
            List<String> names = new ArrayList<>(bundles.size());
            for (JsonElement element : bundles) {
                if (element.isJsonPrimitive()) {
                    names.add(element.getAsString());
                }
            }
            return names;
        } catch (IOException | RuntimeException e) {
            LOG.warning("Failed to read the profile manifest " + manifest, e);
            return List.of();
        }
    }

    /// Runs one `dsh plugin` invocation against an instance.
    ///
    /// @param instance the instance
    /// @param runtime  the resolved Node runtime
    /// @param home     the `DSH_HOME` to operate on
    /// @param args     the pnpm arguments to forward
    /// @param onLine   receives every output line, or `null`
    /// @throws DshException when the command cannot be run or exits non-zero
    private static void runPluginCommand(DshInstance instance,
                                         DshNodeRuntime runtime,
                                         Path home,
                                         List<String> args,
                                         @Nullable Consumer<String> onLine) throws DshException {
        DshVersion version = DshVersionManager.findInstalled(instance.version());
        if (version == null) {
            throw new DshException("DeepSeek Harness " + instance.version() + " is not installed");
        }
        Path script = version.binScript();
        if (!Files.isRegularFile(script)) {
            throw new DshException("The installed version is incomplete: " + script + " is missing");
        }

        List<String> command = new ArrayList<>();
        command.add(runtime.node().toString());
        command.add(script.toString());
        command.add("plugin");
        command.add("--profile");
        command.add(instance.profile());
        command.addAll(args);

        // DSH_* cannot come from a .env file — upstream rejects those names there
        // — so DSH_HOME must travel in the child's environment. Getting this
        // wrong would silently operate on the user's real ~/.dsh.
        Map<String, String> environment = new java.util.LinkedHashMap<>();
        environment.put("DSH_HOME", home.toString());
        environment.putAll(instance.environment());

        try {
            DshCommand.Result result = DshCommand.run(command, instance.workspacePath(), environment,
                    line -> report(onLine, line));
            int exitCode = result.exitCode();
            if (exitCode != 0) {
                throw new DshException("`dsh plugin " + String.join(" ", args)
                        + "` exited with code " + exitCode + ":\n" + tail(result.output()));
            }
        } catch (IOException e) {
            throw new DshException("Failed to run `dsh plugin " + String.join(" ", args) + "`", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DshException("`dsh plugin " + String.join(" ", args) + "` was interrupted", e);
        }
    }

    /// Returns the last few output lines, for an error message.
    ///
    /// @param lines the captured output
    /// @return the trailing lines joined by newlines
    private static String tail(List<String> lines) {
        int from = Math.max(0, lines.size() - 12);
        return String.join("\n", lines.subList(from, lines.size()));
    }

    /// Forwards a progress message when a consumer is attached.
    ///
    /// @param onLine  the consumer, or `null`
    /// @param message the message
    private static void report(@Nullable Consumer<String> onLine, String message) {
        if (onLine != null) {
            onLine.accept(message);
        }
    }
}
