/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2020  huangyuhui <huanghongxun2008@126.com> and contributors
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
package org.jackhuang.hmcl.ui.decorator;

import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.scene.Node;
import org.jackhuang.hmcl.ui.construct.Navigator;
import org.jackhuang.hmcl.ui.wizard.Refreshable;

public interface DecoratorPage extends Refreshable {
    ReadOnlyObjectProperty<State> stateProperty();

    default boolean isPageCloseable() {
        return false;
    }

    default boolean back() {
        return true;
    }

    @Override
    default void refresh() {
    }

    default void closePage() {
    }

    default void onDecoratorPageNavigating(Navigator.NavigationEvent event) {
        ((Node) this).getStyleClass().add("content-background");
    }

    record State(String title, Node titleNode, boolean backable, boolean refreshable, boolean animate, double leftPaneWidth) {
        public State(String title, Node titleNode, boolean backable, boolean refreshable, boolean animate) {
            this(title, titleNode, backable, refreshable, animate, 0);
        }

        public static State fromTitle(String title) {
            return new State(title, null, true, false, true);
        }

        public static State fromTitle(String title, double leftPaneWidth) {
            return new State(title, null, true, false, true, leftPaneWidth);
        }

        /// Returns the state of the page the application starts on.
        ///
        /// It is the one page with nowhere to go back to, and it says so: the
        /// default is to be backable, so a root page that used it would show a
        /// back arrow on start-up that does nothing. The original marks its own
        /// root the same way.
        ///
        /// @param title the title to show
        /// @return the state
        public static State root(String title) {
            return new State(title, null, false, false, true);
        }

        /// Returns the state of the page the application starts on, titled by a
        /// node rather than a string.
        ///
        /// @param titleNode the node to show as the title
        /// @return the state
        public static State rootNode(Node titleNode) {
            return new State(null, titleNode, false, false, true);
        }

        public static State fromTitleNode(Node titleNode) {
            return new State(null, titleNode, true, false, true);
        }
    }
}
