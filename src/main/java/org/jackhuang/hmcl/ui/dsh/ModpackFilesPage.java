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

import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.dsh.DshException;
import org.jackhuang.hmcl.dsh.DshInstance;
import org.jackhuang.hmcl.dsh.DshSession;
import org.jackhuang.hmcl.dsh.DshSessions;
import org.jackhuang.hmcl.ui.construct.ComponentList;
import org.jackhuang.hmcl.ui.construct.ComponentSublist;
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

    /// The instance being written out.
    private final DshInstance instance;

    /// Creates the page.
    ///
    /// @param controller the wizard controller
    /// @param instance   the instance being written out
    /// @param settings   the wizard's settings
    @SuppressWarnings("unused")
    public ModpackFilesPage(WizardController controller, DshInstance instance, SettingsMap settings) {
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
    }

    /// Builds the row for what always travels.
    ///
    /// @return the row
    private Node configurationRow() {
        ComponentSublist sublist = new ComponentSublist();
        sublist.setTitle(i18n("dsh.modpack.files.configuration"));

        CheckBox box = new CheckBox();
        box.setSelected(true);
        box.setDisable(true);
        box.setText(i18n("dsh.modpack.files.configuration.detail"));
        sublist.getContent().add(box);
        return sublist;
    }

    /// Builds the row for the conversations.
    ///
    /// @return the row
    private Node sessionsRow() {
        ComponentSublist sublist = new ComponentSublist();
        sublist.setTitle(i18n("dsh.modpack.files.sessions"));

        List<DshSession> sessions;
        try {
            sessions = DshSessions.list(instance.homeDirectory());
        } catch (DshException e) {
            // The count is a courtesy; the page still works without it.
            sessions = List.of();
        }
        sessionCount = sessions.size();

        sessionsBox.setText(sessionCount == 0
                ? i18n("dsh.modpack.files.sessions.none")
                : i18n("dsh.modpack.files.sessions.count", sessionCount));
        sessionsBox.setDisable(sessionCount == 0);
        sessionsBox.setSelected(Boolean.TRUE.equals(settings.get(ModpackExportWizardProvider.SESSIONS))
                && sessionCount > 0);
        sublist.getContent().add(sessionsBox);
        return sublist;
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

}
