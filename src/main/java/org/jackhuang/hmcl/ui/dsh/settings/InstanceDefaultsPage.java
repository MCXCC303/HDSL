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
package org.jackhuang.hmcl.ui.dsh.settings;

import javafx.scene.control.ScrollPane;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.dsh.DshHomeMode;
import org.jackhuang.hmcl.dsh.DshNodeRuntime;
import org.jackhuang.hmcl.dsh.NodeRuntime;
import org.jackhuang.hmcl.dsh.NodeRuntimeManager;
import org.jackhuang.hmcl.setting.SettingsManager;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.construct.ComponentList;
import org.jackhuang.hmcl.ui.construct.LineSelectButton;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.jackhuang.hmcl.setting.SettingsManager.settings;
import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

/// What a new instance is given.
///
/// The original keeps the defaults an instance starts from apart from the
/// launcher's own settings: Java and memory are not the same kind of thing as
/// the interface language, and they are reached from a different place. The same
/// split is kept here.
@NotNullByDefault
public final class InstanceDefaultsPage extends ScrollPane {
    /// Creates the page.
    public InstanceDefaultsPage() {
        setFitToWidth(true);
        setFitToHeight(true);

        VBox root = new VBox(ComponentList.createComponentListTitle(i18n("dsh.settings.environment")),
                buildEnvironmentList());
        root.getStyleClass().add("card-list");
        setContent(root);

        // After the content: smooth scrolling binds to it and throws on null.
        FXUtils.smoothScrolling(this);
    }

    /// Builds the environment section: what a new instance is given.
    ///
    /// HMCL keeps Java management at the top level of its settings rather than
    /// per instance, and the reasoning carries over: a runtime is a launcher's
    /// business, not something every instance should have its own copy of. An
    /// instance may still pin its own, which is what these are the defaults for.
    ///
    /// @return the assembled component list
    private ComponentList buildEnvironmentList() {
        ComponentList list = new ComponentList();

        LineSelectButton<String> node = new LineSelectButton<>();
        node.setTitle(i18n("dsh.settings.default_node"));
        node.setSubtitle(i18n("dsh.settings.default_node.hint"));

        List<String> runtimes = new ArrayList<>();
        runtimes.add(DshNodeRuntime.SYSTEM);
        for (NodeRuntime runtime : NodeRuntimeManager.listInstalled()) {
            runtimes.add(runtime.version());
        }
        node.setItems(runtimes);
        node.setNullSafeConverter(selection -> DshNodeRuntime.SYSTEM.equals(selection)
                ? i18n("dsh.install.node.system")
                : selection);
        node.setValue(settings().defaultNodeRuntimeProperty().get());
        node.valueProperty().addListener((observable, was, value) -> {
            if (value != null && !value.equals(was)) {
                settings().defaultNodeRuntimeProperty().set(value);
                SettingsManager.save();
            }
        });
        list.getContent().add(node);

        LineSelectButton<DshHomeMode> home = new LineSelectButton<>();
        home.setTitle(i18n("dsh.settings.default_home"));
        home.setSubtitle(i18n("dsh.settings.default_home.hint"));
        // The policy first, then what "not isolated" means — the original's arrangement, and the
        // one that reads correctly: the rule decides, and the mode below says what it falls back to.
        LineSelectButton<org.jackhuang.hmcl.dsh.DshIsolationPolicy> policy = new LineSelectButton<>();
        policy.setTitle(i18n("dsh.settings.isolation"));
        policy.setSubtitle(i18n("dsh.settings.isolation.hint"));
        policy.setItems(org.jackhuang.hmcl.dsh.DshIsolationPolicy.values());
        policy.setConverter(choice -> choice == null ? ""
                : i18n("dsh.settings.isolation." + choice.id()));
        policy.setValue(settings().isolationPolicy());
        policy.valueProperty().addListener((observable, was, value) -> {
            if (value != null) {
                settings().isolationPolicyProperty().set(value);
            }
        });
        home.setItems(DshHomeMode.ISOLATED, DshHomeMode.VERSION_SHARED);
        home.setNullSafeConverter(mode -> i18n("dsh.instance.home." + mode.name().toLowerCase(Locale.ROOT)));
        home.setValue(settings().defaultHomeModeProperty().get());
        home.valueProperty().addListener((observable, was, value) -> {
            if (value != null && value != was) {
                settings().defaultHomeModeProperty().set(value);
                SettingsManager.save();
            }
        });
        list.getContent().add(home);

        return list;
    }

}
