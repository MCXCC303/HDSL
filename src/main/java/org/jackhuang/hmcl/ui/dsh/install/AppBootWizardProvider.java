package org.jackhuang.hmcl.ui.dsh.install;

import javafx.scene.Node;
import org.jackhuang.hmcl.ui.wizard.WizardController;
import org.jackhuang.hmcl.ui.wizard.WizardProvider;
import org.jackhuang.hmcl.util.SettingsMap;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;

/**
 * Wizard provider for selecting an application boot library version.
 */
@NotNullByDefault
public final class AppBootWizardProvider implements WizardProvider {
	private final @Nullable String launcherVersion;
	private final Consumer<@Nullable String> onChosen;

	/**
	 * Creates the wizard provider.
	 *
	 * @param launcherVersion the active launcher version, or {@code null}
	 * @param onChosen        callback receiving the selected version
	 */
	public AppBootWizardProvider(@Nullable String launcherVersion, Consumer<@Nullable String> onChosen) {
		this.launcherVersion = launcherVersion;
		this.onChosen = onChosen;
	}

	@Override
	public void start(SettingsMap settings) {
	}

	@Override
	public @Nullable Node createPage(WizardController controller, int step, SettingsMap settings) {
		return step == 0 ? new AppBootSelectPage(controller, launcherVersion, onChosen) : null;
	}

	@Override
	public Object finish(SettingsMap settings) {
		return null;
	}

	@Override
	public boolean cancel() {
		return true;
	}
}
