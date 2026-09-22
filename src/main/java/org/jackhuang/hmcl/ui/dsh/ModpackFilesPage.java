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
package org.jackhuang.hmcl.ui.dsh;

import java.nio.file.Files;

import org.jackhuang.hmcl.dsh.DshPackForge;

import org.jackhuang.hmcl.dsh.DshModpacks;

import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.dsh.DshException;
import org.jackhuang.hmcl.dsh.DshInstance;
import org.jackhuang.hmcl.dsh.DshSession;
import org.jackhuang.hmcl.dsh.DshSessions;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.construct.ComponentList;
import org.jackhuang.hmcl.ui.construct.LinePane;
import org.jackhuang.hmcl.ui.wizard.WizardController;
import org.jackhuang.hmcl.ui.wizard.WizardPage;
import org.jackhuang.hmcl.util.SettingsMap;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.List;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

/// Asks what of the instance goes into the pack.
///
/// The original's step, which offers a tree of what a game instance holds — mods,
/// worlds, settings — and lets each be left out. What an instance of DeepSeek
/// Harness holds is a configuration and a history: the configuration is what makes
/// the pack a pack and always goes, and the history is the conversations, which is
/// the one thing worth asking about. A pack of a hundred conversations is a
/// hundred megabytes and one of none is a kilobyte, and which of those somebody
/// wants depends on whether they are handing over an environment or a workspace.
@NotNullByDefault
public final class ModpackFilesPage extends VBox implements WizardPage {
    /// The wizard's settings.
    private final SettingsMap settings;

    /// Whether the conversations travel with the pack.
    private final CheckBox sessionsBox = new CheckBox();

    /// How many conversations the instance has.
    private int sessionCount;

    /// The wizard this page belongs to.
    private final WizardController controller;

    /// The instance being written out.
    private final DshInstance instance;

    /// Creates the page.
    ///
    /// @param controller the wizard controller
    /// @param instance   the instance being written out
    /// @param settings   the wizard's settings
    public ModpackFilesPage(WizardController controller, DshInstance instance, SettingsMap settings) {
        this.controller = controller;
        this.instance = instance;
        this.settings = settings;

        setSpacing(10);
        setPadding(new Insets(10));

        Label title = new Label(i18n("dsh.modpack.files.title"));
        getChildren().add(title);

        ComponentList list = new ComponentList();
        list.getContent().add(configurationRow());
        list.getContent().add(sessionsRow());
        getChildren().add(list);

        javafx.scene.layout.HBox buttons = new javafx.scene.layout.HBox(8);
        buttons.setAlignment(javafx.geometry.Pos.CENTER_RIGHT);
        com.jfoenix.controls.JFXButton write = new com.jfoenix.controls.JFXButton(i18n("modpack.export"));
        write.getStyleClass().add("jfx-button-raised");
        write.setOnAction(event -> {
            cleanup(settings);
            controller.onFinish();
        });
        buttons.getChildren().add(write);
        getChildren().add(buttons);
    }

    /// Builds the row for what always travels.
    ///
    /// @return the row
    private Node configurationRow() {
        // A line with the box on it, which is how the original draws the entries of
        // its file tree: what it is on the left, whether it goes on the right, and
        // nothing that has to be opened before either can be seen.
        CheckBox box = new CheckBox();
        box.setSelected(true);
        box.setDisable(true);

        LinePane pane = new LinePane();
        pane.setTitle(i18n("dsh.modpack.files.configuration"));
        pane.setRight(box);
        FXUtils.installFastTooltip(pane, i18n("dsh.modpack.files.configuration.detail"));
        return pane;
    }

    /// Builds the row for the conversations.
    ///
    /// @return the row
    private Node sessionsRow() {
        List<DshSession> sessions;
        try {
            sessions = DshSessions.list(instance.homeDirectory());
        } catch (DshException e) {
            // The count is a courtesy; the page still works without it.
            sessions = List.of();
        }
        sessionCount = sessions.size();

        sessionsBox.setDisable(sessionCount == 0);
        sessionsBox.setSelected(Boolean.TRUE.equals(settings.get(ModpackExportWizardProvider.SESSIONS))
                && sessionCount > 0);

        LinePane pane = new LinePane();
        pane.setTitle(i18n("dsh.modpack.files.sessions"));
        pane.setRight(sessionsBox);
        FXUtils.installFastTooltip(pane, sessionCount == 0
                ? i18n("dsh.modpack.files.sessions.none")
                : i18n("dsh.modpack.files.sessions.count", sessionCount));
        return pane;
    }

    @Override
    public void onNavigate(SettingsMap settings) {
        // Nothing to load: what the instance holds was read when the page was built.
    }

    @Override
    public void cleanup(SettingsMap settings) {
        settings.put(ModpackExportWizardProvider.SESSIONS, sessionsBox.isSelected());
    }

    @Override
    public String getTitle() {
        return i18n("dsh.modpack.files");
    }


    /// Writes this launcher's own kind of pack.
    ///
    /// @param instance the instance to write out
    /// @param target   where to write it
    /// @param options  what it should say and carry
    static void run(DshInstance instance, java.nio.file.Path target, DshModpacks.Options options) {
        ProgressDialog.run(i18n("modpack.export"),
                progress -> DshModpacks.export(instance, target, options, progress::accept), null);
    }

    /// Writes a pack the community's tooling reads, and the digest beside it.
    ///
    /// A publisher writes the digest next to the pack, which is what the community's index and its
    /// installers check against; writing it here means an exported pack is publishable without
    /// another step.
    ///
    /// @param instance the instance to write out
    /// @param target   where to write it
    /// @param options  what it should say about itself
    static void runPackForge(DshInstance instance, java.nio.file.Path target,
                             DshPackForge.Options options) {
        ProgressDialog.run(i18n("modpack.export"), progress -> {
            DshPackForge.Result result = DshPackForge.export(instance, target, options, progress::accept);
            try {
                Files.writeString(target.resolveSibling(target.getFileName() + ".sha256"),
                        result.sha256() + "  " + target.getFileName() + "\n");
                progress.accept("Wrote " + target.getFileName() + ".sha256");
            } catch (java.io.IOException e) {
                progress.accept("The pack was written, but its digest was not: " + e.getMessage());
            }
        }, null);
    }
}
