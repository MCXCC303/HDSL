/*
 * HMCL-DSH
 * Copyright (C) 2026  HMCL-DSH contributors
 *
 * Adapted from HMCL's packaging logic (CreateDeb), which is licensed under the
 * GNU General Public License version 3. See the NOTICE file for the full
 * statement of changes.
 */
package org.jackhuang.hmcl.gradle.pack;

import kala.compress.archivers.ar.ArArchiveEntry;
import kala.compress.archivers.ar.ArArchiveOutputStream;
import kala.compress.archivers.tar.TarArchiveEntry;
import kala.compress.archivers.tar.TarArchiveOutputStream;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.GZIPOutputStream;

/// Creates a Debian package for the current HMCL-DSH channel.
///
/// ## Package layout
///
/// The generated `data.tar.gz` contains four installed artifacts:
///
/// - the self-executing application script under `/usr/share/java/hmcl-dsh/`
/// - a channel-specific command under `/usr/bin/`
/// - a desktop entry under `/usr/share/applications/`
/// - the icon under `/usr/share/icons/hicolor/256x256/apps/`
///
/// ## Channel commands and aliases
///
/// Every package installs a channel-specific executable such as `hmcl-dsh` or
/// `hmcl-dsh-beta`. The generic command is not shipped as a plain file;
/// maintainer scripts register the channel command into a shared `hmcl-dsh`
/// alternatives group so several channel packages can coexist without file
/// conflicts.
public abstract class CreateDeb extends DefaultTask {
    /// Logger used for the progress messages Gradle prints.
    public static final Logger LOGGER = Logging.getLogger(CreateDeb.class);

    private static final int DIRECTORY_MODE = 0755;
    private static final int EXECUTABLE_MODE = 0755;
    private static final int REGULAR_FILE_MODE = 0644;

    /// Debian version written into the `control` file.
    @Input
    public abstract Property<String> getVersion();

    /// Release type metadata controlling package name, launcher name and alias priority.
    @Input
    public abstract Property<ReleaseType> getReleaseType();

    /// Launcher class name used for the desktop `StartupWMClass` property.
    @Input
    public abstract Property<String> getLauncherClassName();

    /// Self-executing `.sh` artifact produced by the executable-stub task.
    @InputFile
    public abstract RegularFileProperty getAppShFile();

    /// Desktop icon installed into the hicolor icon theme.
    @InputFile
    public abstract RegularFileProperty getIconFile();

    /// Final `.deb` archive written by this task.
    @OutputFile
    public abstract RegularFileProperty getOutputFile();

    /// Returns the current release type.
    ///
    /// @return the release type
    private ReleaseType currentType() {
        return getReleaseType().get();
    }

    /// Returns the channel suffix.
    ///
    /// @return the channel name
    private String currentTypeName() {
        return currentType().getName();
    }

    /// Returns the channel-specific command installed under `/usr/bin`.
    ///
    /// @return the absolute command path
    private String launcherPath() {
        return "/usr/bin/hmcl-dsh-" + currentTypeName();
    }

    /// Returns the installed location of the application script.
    ///
    /// @return the absolute path
    private String targetPath() {
        return "/usr/share/java/hmcl-dsh/" + getAppShFile().getAsFile().get().getName();
    }

    /// Returns the desktop entry path.
    ///
    /// @return the absolute path
    private String desktopFilePath() {
        return "/usr/share/applications/hmcl-dsh-%s.desktop".formatted(currentTypeName());
    }

    /// Returns the installed icon path.
    ///
    /// @return the absolute path
    private String iconTargetPath() {
        return "/usr/share/icons/hicolor/256x256/apps/hmcl-dsh-%s.png".formatted(currentTypeName());
    }

    /// Ensures parent directories exist in the tar stream before child entries.
    ///
    /// @param directories the set of directories already written
    /// @param output      the tar stream
    /// @param dirName     the directory to create
    /// @throws IOException when the stream cannot be written
    private static void makeDirectories(Set<String> directories, TarArchiveOutputStream output, String dirName)
            throws IOException {
        if (dirName.isEmpty() || ".".equals(dirName) || directories.contains(dirName)) {
            return;
        }

        int idx = dirName.lastIndexOf('/');
        if (idx > 0) {
            makeDirectories(directories, output, dirName.substring(0, idx));
        }

        TarArchiveEntry entry = new TarArchiveEntry(dirName + "/", true);
        entry.setMode(DIRECTORY_MODE);
        output.putArchiveEntry(entry);
        output.closeArchiveEntry();
        directories.add(dirName);
    }

    /// Writes binary content as a tar entry, creating parent directories first.
    ///
    /// @param directories the set of directories already written
    /// @param output      the tar stream
    /// @param name        the entry name
    /// @param content     the entry content
    /// @param mode        the file mode
    /// @throws IOException when the stream cannot be written
    private static void putEntry(Set<String> directories, TarArchiveOutputStream output, String name,
                                 byte[] content, int mode) throws IOException {
        int idx = name.lastIndexOf('/');
        if (idx > 0) {
            makeDirectories(directories, output, name.substring(0, idx));
        }

        TarArchiveEntry entry = new TarArchiveEntry(name);
        entry.setMode(mode);
        entry.setSize(content.length);
        output.putArchiveEntry(entry);
        output.write(content);
        output.closeArchiveEntry();
    }

    /// Writes UTF-8 text content as a tar entry.
    ///
    /// @param directories the set of directories already written
    /// @param output      the tar stream
    /// @param name        the entry name
    /// @param content     the entry content
    /// @param mode        the file mode
    /// @throws IOException when the stream cannot be written
    private static void putEntry(Set<String> directories, TarArchiveOutputStream output, String name,
                                 String content, int mode) throws IOException {
        putEntry(directories, output, name, content.getBytes(StandardCharsets.UTF_8), mode);
    }

    /// Builds a valid `.deb` archive with `debian-binary`, `control.tar.gz` and `data.tar.gz`.
    ///
    /// @throws IOException when the inputs are missing or the archive cannot be written
    @TaskAction
    public void run() throws IOException {
        Path appShFile = getAppShFile().getAsFile().get().toPath();
        if (!Files.isRegularFile(appShFile)) {
            throw new IOException("Invalid app script file: " + appShFile);
        }

        Path iconFile = getIconFile().getAsFile().get().toPath();
        if (!Files.isRegularFile(iconFile)) {
            throw new IOException("Invalid icon file: " + iconFile);
        }

        byte[] appShBytes = Files.readAllBytes(appShFile);
        if (appShBytes.length == 0) {
            throw new IOException("Empty app script file: " + appShFile);
        }

        byte[] iconBytes = Files.readAllBytes(iconFile);
        if (iconBytes.length == 0) {
            throw new IOException("Empty icon file: " + iconFile);
        }

        byte[] launcherScriptBytes = launcherScript().getBytes(StandardCharsets.UTF_8);
        byte[] desktopInfoBytes = desktopInfo().getBytes(StandardCharsets.UTF_8);

        LOGGER.lifecycle("Creating control.tar.gz");
        var controlData = new ByteArrayOutputStream();
        try (var output = new TarArchiveOutputStream(new GZIPOutputStream(controlData))) {
            output.setLongFileMode(TarArchiveOutputStream.LONGFILE_GNU);

            Set<String> directories = new HashSet<>();
            putEntry(directories, output, "./control",
                    control(appShBytes.length, launcherScriptBytes.length, desktopInfoBytes.length, iconBytes.length),
                    REGULAR_FILE_MODE);
            putEntry(directories, output, "./postinst", postinst(), EXECUTABLE_MODE);
            putEntry(directories, output, "./prerm", prerm(), EXECUTABLE_MODE);
        }

        Path outputFile = getOutputFile().get().getAsFile().toPath();
        Files.createDirectories(outputFile.getParent());

        LOGGER.lifecycle("Creating data.tar.gz");
        var dataTarBuffer = new ByteArrayOutputStream(16 * 1024 * 1024);
        try (var output = new TarArchiveOutputStream(new GZIPOutputStream(dataTarBuffer))) {
            output.setLongFileMode(TarArchiveOutputStream.LONGFILE_GNU);

            Set<String> directories = new HashSet<>();
            putEntry(directories, output, "." + targetPath(), appShBytes, EXECUTABLE_MODE);
            putEntry(directories, output, "." + launcherPath(), launcherScriptBytes, EXECUTABLE_MODE);
            putEntry(directories, output, "." + desktopFilePath(), desktopInfoBytes, REGULAR_FILE_MODE);
            putEntry(directories, output, "." + iconTargetPath(), iconBytes, REGULAR_FILE_MODE);
        }

        LOGGER.lifecycle("Creating deb file");
        try (var output = new ArArchiveOutputStream(Files.newOutputStream(outputFile,
                StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.CREATE))) {
            putArEntry(output, "debian-binary", "2.0\n".getBytes(StandardCharsets.UTF_8));
            putArEntry(output, "control.tar.gz", controlData.toByteArray());
            putArEntry(output, "data.tar.gz", dataTarBuffer.toByteArray());
        }
    }

    /// Adds one member to the outer ar container used by Debian packages.
    ///
    /// @param output  the ar stream
    /// @param name    the member name
    /// @param content the member content
    /// @throws IOException when the stream cannot be written
    private static void putArEntry(ArArchiveOutputStream output, String name, byte[] content) throws IOException {
        ArArchiveEntry entry = new ArArchiveEntry(name, content.length);
        output.putArchiveEntry(entry);
        output.write(content);
        output.closeArchiveEntry();
    }

    /// Generates the package metadata file.
    ///
    /// `Depends` names a Java runtime but nothing else: Node.js is located at
    /// runtime and can be supplied by the launcher itself, so requiring it here
    /// would be wrong.
    ///
    /// @param appSize           the application script size
    /// @param launcherScriptSize the command wrapper size
    /// @param desktopInfoSize   the desktop entry size
    /// @param iconSize          the icon size
    /// @return the control file content
    private String control(long appSize, long launcherScriptSize, long desktopInfoSize, long iconSize) {
        long installedSize = (appSize + launcherScriptSize + desktopInfoSize + iconSize + 1023) / 1024;

        return """
                Package: %s
                Version: %s
                Section: utils
                Priority: optional
                Architecture: all
                Installed-Size: %d
                Depends: default-jre-headless | java21-runtime-headless | java21-runtime
                Description: DeepSeek Harness launcher
                 HMCL-DSH installs, isolates and launches DeepSeek Harness versions
                 and profiles. It keeps the look of Hello Minecraft! Launcher.
                Homepage: https://github.com/
                """.formatted(currentType().getPackageName(), getVersion().get(), Math.max(installedSize, 1)) + "\n";
    }

    /// Returns the generic command path shared by the channel packages.
    ///
    /// @return the alternatives link path
    private static String commonLauncherPath() {
        return "/usr/bin/hmcl-dsh";
    }

    /// Registers the channel command into the shared alternatives group.
    ///
    /// @return the postinst script
    private String postinst() {
        return """
                #!/bin/sh
                set -e

                if [ "$1" = configure ]; then
                    update-alternatives --install %s hmcl-dsh %s %d
                fi
                """.formatted(commonLauncherPath(), launcherPath(), currentType().getAlternativesPriority());
    }

    /// Removes the channel command from the shared alternatives group.
    ///
    /// @return the prerm script
    private String prerm() {
        return """
                #!/bin/sh
                set -e

                if [ "$1" = remove ] || [ "$1" = deconfigure ]; then
                    update-alternatives --remove hmcl-dsh %s
                fi
                """.formatted(launcherPath());
    }

    /// Creates the wrapper that runs the bundled script from the user's home.
    ///
    /// Running from `$HOME` matters: DeepSeek Harness scopes sessions to the
    /// process working directory, and starting inside `/usr/share` would make
    /// the first session belong to a system directory.
    ///
    /// @return the wrapper script
    private String launcherScript() {
        return """
                #!/usr/bin/env bash
                cd "$HOME"
                exec %s "$@"
                """.formatted(targetPath());
    }

    /// Generates the desktop entry pointing at the channel-specific command.
    ///
    /// @return the desktop entry content
    private String desktopInfo() {
        return """
                [Desktop Entry]
                Type=Application
                Name=%s
                Comment=DeepSeek Harness launcher
                Exec=%s
                Icon=%s
                Terminal=false
                StartupNotify=false
                Categories=Development;Utility;
                Keywords=deepseek;harness;dsh;ai;
                StartupWMClass=%s
                """.formatted(currentType().getDisplayName(), launcherPath(), iconTargetPath(),
                getLauncherClassName().get());
    }
}
