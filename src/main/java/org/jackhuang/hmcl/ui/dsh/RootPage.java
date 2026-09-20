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
import org.jackhuang.hmcl.Metadata;
import org.jackhuang.hmcl.ui.decorator.DecoratorAnimatedPage;
import org.jackhuang.hmcl.ui.decorator.DecoratorPage;
import org.jetbrains.annotations.NotNullByDefault;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

/// The launcher shell: a sidebar of destinations on the left and the navigator
/// content area on the right.
///
/// This is the HMCL-DSH counterpart of HMCL's `RootPage`. Account, multiplayer
/// and download-source destinations are gone; the remaining entries describe
/// DeepSeek Harness versions, instances and launcher settings.
@NotNullByDefault
public final class RootPage extends DecoratorAnimatedPage implements DecoratorPage {
    /// The page state published to the window decorator.
    private final ReadOnlyObjectWrapper<State> state = new ReadOnlyObjectWrapper<>();

    /// The lazily created settings page.
    private SettingsPage settingsPage;

    /// The lazily created instance list page.
    private InstancesPage instancesPage;

    /// The lazily created DeepSeek Harness version list page.
    private VersionsPage versionsPage;

    /// The launcher home page, created eagerly because it is the default view.
    private final MainPage mainPage = new MainPage();

    /// Creates the root page and installs its sidebar.
    public RootPage() {
        getStyleClass().remove("gray-background");
        getLeft().getStyleClass().add("gray-background");

        state.set(State.fromTitle(Metadata.FULL_TITLE));

        // Navigation lives on the home page, which is the only page a
        // user sees before choosing a destination.

        setCenter(mainPage);
    }

    /// Returns the settings page, creating it on first use.
    ///
    /// @return the settings page
    public SettingsPage getSettingsPage() {
        if (settingsPage == null) {
            settingsPage = new SettingsPage();
        }
        return settingsPage;
    }

    /// Returns the instance list page, creating it on first use.
    ///
    /// @return the instance list page
    public InstancesPage getInstancesPage() {
        if (instancesPage == null) {
            instancesPage = new InstancesPage();
        }
        return instancesPage;
    }

    /// Returns the version list page, creating it on first use.
    ///
    /// @return the versions page
    public VersionsPage getVersionsPage() {
        if (versionsPage == null) {
            versionsPage = new VersionsPage();
        }
        return versionsPage;
    }

    @Override
    public ReadOnlyObjectProperty<State> stateProperty() {
        return state.getReadOnlyProperty();
    }

    @Override
    public void refresh() {
        // The root page owns no refreshable data of its own.
    }
}
