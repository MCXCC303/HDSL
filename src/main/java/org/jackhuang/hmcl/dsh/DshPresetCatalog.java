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
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;

/// The curated quick-install catalogue.
///
/// The entries are ordinary npm packages that install into a profile through
/// `dsh plugin --profile <name> add <spec>`. DeepSeek Harness appends any
/// package that ships a `dsh.bundle.patch` to the profile's bundle list, so an
/// entry becomes active after the profile is restarted.
///
/// Three tiers exist upstream and the catalogue mirrors that, but only the
/// installable tier is offered here: shipped-but-disabled rows are already part
/// of the dependency closure and belong to a profile patch, not to a download.
@NotNullByDefault
public final class DshPresetCatalog {
    private DshPresetCatalog() {
    }

    /// The built-in catalogue.
    ///
    /// Ordered so the marketplace — the entry that makes every other plugin
    /// discoverable — comes first and is preselected.
    private static final @Unmodifiable List<DshPreset> BUILTIN = List.of(
            DshPreset.recommended("dshmarket", "dsh-market",
                    "dshmarket",
                    "Plugin marketplace: browse, install and share DeepSeek Harness plugins."),
            DshPreset.optional("better-sidebar", "Better Sidebar",
                    "dsh-better-sidebar",
                    "A richer conversation sidebar."),
            DshPreset.optional("context", "Context",
                    "dsh-context",
                    "Inspect and compact the model context of a session."),
            DshPreset.optional("cost-meter", "Cost Meter",
                    "dsh-cost-meter",
                    "Token and cost accounting per session."),
            DshPreset.optional("skills-manager", "Skills Manager",
                    "@michengai/dsh-skills-manager",
                    "Install and manage agent skills."),
            DshPreset.optional("remote-web-ui", "Remote Web UI",
                    "@linxin666/dsh-remote-web-ui",
                    "Reach the web interface from another device."),
            DshPreset.optional("git-graph", "Git Graph",
                    "@linxin666/dsh-client-ui-git-graph",
                    "Visualise repository history inside a session."),
            DshPreset.optional("skin-center", "Skin Center",
                    "@linxin666/dsh-client-ui-skin-center",
                    "Browse and apply interface skins."),
            DshPreset.optional("whale-widget", "Whale Widget",
                    "dsh-whale-widget",
                    "A small decorative widget."),
            DshPreset.optional("subagent-codex", "Codex subagent",
                    "@deepseek-ai/dsh-subagent-codex",
                    "Delegate work to a Codex subagent."),
            DshPreset.optional("subagent-claude-code", "Claude Code subagent",
                    "@deepseek-ai/dsh-subagent-claude-code",
                    "Delegate work to a Claude Code subagent."),
            DshPreset.optional("agent-team", "Agent team",
                    "@deepseek-ai/dsh-experimental-agent-team-profile",
                    "Experimental: run a team of agents."),
            DshPreset.optional("auto-review", "Auto review",
                    "@deepseek-ai/dsh-experimental-auto-review",
                    "Experimental: review changes automatically."));

    /// Returns the built-in presets.
    ///
    /// @return the catalogue, in display order
    public static @Unmodifiable List<DshPreset> builtin() {
        return BUILTIN;
    }

    /// Returns the presets that should be selected by default.
    ///
    /// @return the recommended presets
    public static @Unmodifiable List<DshPreset> recommended() {
        return BUILTIN.stream().filter(DshPreset::recommended).toList();
    }
}
