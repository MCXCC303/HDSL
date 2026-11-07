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
package com.mcxcc303.hdsl.pack;

import com.mcxcc303.hdsl.account.DshProfilePatch;
import com.mcxcc303.hdsl.core.DshException;
import com.mcxcc303.hdsl.harness.HarnessInstaller;
import com.mcxcc303.hdsl.instance.DshHomeMode;
import com.mcxcc303.hdsl.instance.DshInstance;
import com.mcxcc303.hdsl.instance.DshInstanceManager;
import com.mcxcc303.hdsl.launch.DshLauncher;
import com.mcxcc303.hdsl.launch.DshProcess;
import com.mcxcc303.hdsl.plugin.DshPluginInstaller;
import org.jackhuang.hmcl.util.logging.Logger;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Unpacks, installs, and verifies {@code .dspack} modpacks into instances.
 */
@NotNullByDefault
public final class DspackLander {
	private static final Duration VERIFY_TIMEOUT = Duration.ofSeconds(90);
	private static final int FAILURE_LINES = 12;

	private DspackLander() {
	}

	/**
	 * Unpacks all parts of a pack archive into their target destinations.
	 *
	 * @param archive     the pack archive
	 * @param destination where overrides land
	 * @param home        where home files land, or null if omitted
	 * @return summary of landed files
	 * @throws DshException when the archive cannot be read, or holds an unsafe member
	 */
	public static Landed land(Path archive, Path destination, @Nullable Path home) throws DshException {
		return land(archive, destination, home, EnumSet.allOf(Part.class));
	}

	/**
	 * Unpacks selected parts of a pack archive into their target destinations.
	 *
	 * @param archive     the pack archive
	 * @param destination where overrides land
	 * @param home        where home files land, or null if omitted
	 * @param parts       which sets to write
	 * @return summary of landed files
	 * @throws DshException when the archive cannot be read, or holds an unsafe member
	 */
	public static Landed land(Path archive, Path destination, @Nullable Path home, Set<Part> parts)
	throws DshException {
		int files = 0;
		int overrides = 0;
		int fromHome = 0;
		int machine = 0;
		try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive), StandardCharsets.UTF_8)) {
			ZipEntry entry;
			while ((entry = zip.getNextEntry()) != null) {
				String name = entry.getName();
				if (entry.isDirectory()) {
					continue;
				}

				Path target;
				if (name.startsWith("overrides/")) {
					if (!parts.contains(Part.OVERRIDES)) {
						continue;
					}
					target = DspackSafety.resolve(destination, name.substring("overrides/".length()));
					overrides++;
				} else if (name.startsWith("home/") && home != null) {
					if (!parts.contains(Part.HOME)) {
						continue;
					}
					target = DspackSafety.resolve(home, name.substring("home/".length()));
					fromHome++;
				} else if (DspackSafety.isMachineFile(name)) {
					if (!parts.contains(Part.MACHINE)) {
						continue;
					}
					target = DspackSafety.resolve(destination, name);
					machine++;
				} else {
					continue;
				}

				DspackSafety.refuseIfUnsafe(name, target);
				Files.createDirectories(target.getParent());
				Files.copy(zip, target, StandardCopyOption.REPLACE_EXISTING);
				files++;
			}
		} catch (IOException e) {
			throw new DshException("Could not unpack " + archive + ": " + e.getMessage(), e);
		}
		return new Landed(files, overrides, fromHome, machine);
	}

	/**
	 * Finishes an installation after build script approval.
	 *
	 * @param archive  the pack archive
	 * @param instance target instance
	 * @param report   receives progress lines, or null
	 * @return the finished instance
	 * @throws DshException when the pack cannot be installed
	 */
	public static DshInstance finish(Path archive, DshInstance instance, @Nullable Consumer<String> report)
	throws DshException {
		try {
			installInto(archive, instance, report);
			return instance;
		} catch (DshException | RuntimeException failed) {
			if (failed instanceof DshPluginInstaller.DshBuildScriptApprovalRequired) {
				throw failed;
			}
			Logger.LOG.warning("Could not finish the pack in " + instance.id() + "; removing it", failed);
			HarnessInstaller.discardPartial(instance);
			try {
				DshInstanceManager.delete(instance.id());
			} catch (DshException | RuntimeException cleanupFailure) {
				Logger.LOG.warning("Could not remove the half-made instance " + instance.id(), cleanupFailure);
			}
			throw failed;
		}
	}

	/**
	 * Puts a recognized pack into an instance that is already at the right version.
	 *
	 * @param archive  the pack archive
	 * @param instance the instance to fill
	 * @param report   receives progress lines, or null
	 * @return summary of landed files
	 * @throws DshException when the archive cannot be read, or the install fails
	 */
	public static Landed installInto(Path archive, DshInstance instance, @Nullable Consumer<String> report)
	throws DshException {
		Path home = instance.homeDirectory();
		Path profiles = home.resolve("profiles").resolve(instance.profile());

		Landed machine = land(archive, profiles, home, EnumSet.of(Part.MACHINE));
		if (machine.machine() > 0) {
			say(report, "Installing the pack's dependencies");
		} else {
			say(report, "Initializing profile " + instance.profile());
		}
		DshPluginInstaller.resolve(instance, report);

		Landed rest = land(archive, profiles, home, EnumSet.of(Part.OVERRIDES, Part.HOME));

		Path patch = profiles.resolve("cordis.patch.yml");
		List<String> keptWhole = DshProfilePatch.dropRedundantInserts(patch,
		                                                              DshPluginInstaller.readBundles(home,
		                                                                                             instance.profile()));
		if (!keptWhole.isEmpty()) {
			say(report,
			    "These are both a bundle and an insert, and carry settings that would be " +
			    "lost if the duplicate were dropped: " + keptWhole);
		}

		say(report, "Checking that the pack starts");
		verifyBoots(instance);

		return new Landed(machine.files() + rest.files(), rest.overrides(), rest.home(), machine.machine());
	}

	/**
	 * Starts the instance and stops it to verify that it boots successfully.
	 *
	 * @param instance the instance to verify
	 * @throws DshException when the instance fails to boot or times out
	 */
	static void verifyBoots(DshInstance instance) throws DshException {
		DshLauncher.LaunchPlan plan = DshLauncher.plan(instance, null);
		StringBuilder output = new StringBuilder();
		DshProcess process = DshProcess.startPrepared(instance, plan);
		process.setLogSink(line -> output.append(line).append('\n'));
		try {
			long deadline = System.currentTimeMillis() + VERIFY_TIMEOUT.toMillis();
			while (System.currentTimeMillis() < deadline && process.state() == DshProcess.State.STARTING) {
				Thread.sleep(200);
			}
			if (process.state() != DshProcess.State.READY) {
				throw new DshException("The installed pack does not start:\n" + failureOf(output.toString()));
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new DshException("Interrupted while starting the installed pack", e);
		} finally {
			process.stop();
			long gone = System.currentTimeMillis() + VERIFY_TIMEOUT.toMillis();
			while (process.isRunning() && System.currentTimeMillis() < gone) {
				try {
					Thread.sleep(100);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					break;
				}
			}
		}
	}

	/**
	 * Extracts leading error lines from startup log output.
	 *
	 * @param output the full log output
	 * @return extracted error summary
	 */
	static String failureOf(String output) {
		List<String> lines = new ArrayList<>();
		boolean started = false;
		for (String line : output.split("\n")) {
			String trimmed = line.strip();
			if (!started) {
				started = trimmed.contains("Error") || trimmed.contains("Cannot find package");
			}
			if (started && !trimmed.isEmpty()) {
				lines.add(trimmed);
			}
			if (lines.size() >= FAILURE_LINES) {
				break;
			}
		}
		return lines.isEmpty() ? output.strip() : String.join("\n", lines);
	}

	static void say(@Nullable Consumer<String> report, String line) {
		if (report != null) {
			report.accept(line);
		}
	}

	/**
	 * Creates an instance and installs a pack into it, cleaning up on failure.
	 *
	 * @param archive the verified pack archive
	 * @param id      instance identifier
	 * @param profile profile name
	 * @param version harness version
	 * @param report  receives progress lines, or null
	 * @return the created and populated instance
	 * @throws DshException when creation or installation fails
	 */
	public static DshInstance installNew(Path archive,
	                                     String id,
	                                     String profile,
	                                     String version,
	                                     @Nullable Consumer<String> report) throws DshException {
		DshInstance instance = DshInstanceManager.create(id,
		                                                 version,
		                                                 profile,
		                                                 Path.of(System.getProperty("user.home")),
		                                                 DshHomeMode.ISOLATED,
		                                                 null,
		                                                 List.of(),
		                                                 Map.of());
		try {
			say(report, "Installing DeepSeek Harness " + version);
			HarnessInstaller.install(instance, version, report);

			say(report,
			    "Writing the pack's files into " +
			    instance.homeDirectory().resolve("profiles").resolve(instance.profile()));
			installInto(archive, instance, report);
			return instance;
		} catch (DshException | RuntimeException failed) {
			if (failed instanceof DshPluginInstaller.DshBuildScriptApprovalRequired) {
				throw failed;
			}
			Logger.LOG.warning("Could not install the pack into " + id + "; removing the instance made for it", failed);
			HarnessInstaller.discardPartial(instance);
			try {
				DshInstanceManager.delete(id);
			} catch (DshException | RuntimeException cleanupFailure) {
				Logger.LOG.warning("Could not remove the half-made instance " + id, cleanupFailure);
			}
			throw failed;
		}
	}

	/**
	 * Sections of a pack archive that can be written independently.
	 */
	public enum Part {
		MACHINE,
		OVERRIDES,
		HOME
	}

	/**
	 * Summary of files extracted from a pack archive.
	 *
	 * @param files     total number of files written
	 * @param overrides number of files written from overrides/
	 * @param home      number of files written from home/
	 * @param machine   number of machine configuration files written
	 */
	public record Landed(int files, int overrides, int home, int machine) {
	}
}
