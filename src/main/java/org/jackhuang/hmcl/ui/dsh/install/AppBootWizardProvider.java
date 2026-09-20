package org.jackhuang.hmcl.ui.dsh.install;

import javafx.scene.Node;
import org.jackhuang.hmcl.ui.wizard.WizardController;
import org.jackhuang.hmcl.ui.wizard.WizardProvider;
import org.jackhuang.hmcl.util.SettingsMap;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;

/// A one-page wizard that chooses a boot library version.
///
/// The original chooses a boot library in a step of its own with a list and a
/// name box, rather than in a dropdown inside the page that asked for it. The
/// choice is made here and handed straight back, so this wizard's settings are
/// never read: the page it shows writes into the caller's own.
@NotNullByDefault
public final class AppBootWizardProvider implements WizardProvider {
    /// The version the launcher itself runs, which needs no warning.
    private final @Nullable String launcherVersion;

    /// Receives the chosen version, or `null` for the launcher's own.
    private final Consumer<@Nullable String> onChosen;

    /// Creates the provider.
    ///
    /// @param launcherVersion the version the launcher runs, or `null`
    /// @param onChosen        receives the chosen version
    public AppBootWizardProvider(@Nullable String launcherVersion, Consumer<@Nullable String> onChosen) {
        this.launcherVersion = launcherVersion;
        this.onChosen = onChosen;
    }

    @Override
    public void start(SettingsMap settings) {
        // Nothing to seed: the page reads the versions itself.
    }

    @Override
    public @Nullable Node createPage(WizardController controller, int step, SettingsMap settings) {
        return step == 0 ? new AppBootSelectPage(controller, launcherVersion, onChosen) : null;
    }

    @Override
    public Object finish(SettingsMap settings) {
        // The choice is applied by the page when it is made; there is no work
        // left for the wizard to run.
        return null;
    }

    @Override
    public boolean cancel() {
        return true;
    }
}
