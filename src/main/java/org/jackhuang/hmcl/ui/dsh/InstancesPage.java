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

import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.ui.construct.AdvancedListBox;
import org.jackhuang.hmcl.ui.construct.TwoLineListItem;
import org.jackhuang.hmcl.ui.decorator.DecoratorAnimatedPage;
import org.jackhuang.hmcl.ui.decorator.DecoratorPage;
import org.jetbrains.annotations.NotNullByDefault;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

/// Lists the DeepSeek Harness instances managed by HMCL-DSH.
///
/// Phase 0 renders the empty state only; the instance model, its actions and
/// the launch controls arrive in Phase 1 and Phase 3 (see `PLAN.md`).
@NotNullByDefault
public final class InstancesPage extends DecoratorAnimatedPage implements DecoratorPage {
    /// The page state published to the window decorator.
    private final ReadOnlyObjectWrapper<State> state =
            new ReadOnlyObjectWrapper<>(State.fromTitle(i18n("instance.manage")));

    /// Creates the instance list page.
    public InstancesPage() {
        getStyleClass().remove("gray-background");

        TwoLineListItem placeholder = new TwoLineListItem();
        placeholder.setTitle(i18n("dsh.instance.empty"));
        placeholder.setSubtitle(i18n("dsh.instance.empty.hint"));

        placeholder.setMaxWidth(420);
        placeholder.setPrefWidth(420);

        VBox box = new VBox(8, placeholder);
        box.setAlignment(Pos.CENTER);
        box.setPadding(new Insets(40));
        box.setMaxWidth(420);

        StackPane center = new StackPane(box);
        center.setAlignment(Pos.CENTER);

        setLeft(new AdvancedListBox().startCategory(i18n("instance.manage")));
        setCenter(center);
    }

    @Override
    public ReadOnlyObjectProperty<State> stateProperty() {
        return state.getReadOnlyProperty();
    }

    @Override
    public void refresh() {
        // Refreshed once the instance model exists.
    }
}
