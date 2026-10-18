/*
 * HDSL
 * Copyright (C) 2026  HDSL contributors
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
package org.jackhuang.hmcl.setting;

import com.google.gson.annotations.SerializedName;
import com.mcxcc303.hdsl.account.DshAccount;
import com.mcxcc303.hdsl.account.DshVendor;
import com.mcxcc303.hdsl.instance.DshHomeMode;
import com.mcxcc303.hdsl.instance.DshIsolationPolicy;
import com.mcxcc303.hdsl.launch.DshLauncherVisibility;
import com.mcxcc303.hdsl.launch.DshProxyMode;
import com.mcxcc303.hdsl.plugin.DshBuildScriptPolicy;
import com.mcxcc303.hdsl.plugin.DshDependencyPolicy;
import com.mcxcc303.hdsl.runtime.NodeSource;
import com.mcxcc303.hdsl.skin.DshSkinChoice;
import org.glavo.monetfx.ColorStyle;
import org.jackhuang.hmcl.Metadata;
import org.jackhuang.hmcl.theme.BackgroundLoadPolicy;
import org.jackhuang.hmcl.theme.NetworkBackgroundImageCachePolicy;
import org.jackhuang.hmcl.theme.ThemeColor;
import org.jackhuang.hmcl.theme.ThemeReference;
import org.jackhuang.hmcl.util.gson.JsonUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/*
 * Owns the single [LauncherSettings] instance and persists it to disk.
 *
 * Persistence is deliberately explicit rather than reflective: the settings
 * object holds JavaFX properties, which serialise poorly, so a plain
 * serialisable snapshot is written instead. This keeps the on-disk format
 * stable and easy to migrate as HDSL grows.
 */
@NotNullByDefault
public final class SettingsManager {
	private SettingsManager() {
	}

	// The settings file inside the per-user HDSL home.
	private static final Path SETTINGS_PATH = Metadata.HMCL_USER_HOME.resolve("launcher-settings.json");

	// The window state file inside the per-user HDSL home.
	private static final Path STATE_PATH = Metadata.HMCL_USER_HOME.resolve("launcher-state.json");

	// The lazily created settings singleton.
	private static @Nullable LauncherSettings launcherSettings;

	// The lazily created window state singleton.
	private static @Nullable LauncherState launcherState;

	/**
	 * Returns the process-wide window state, loading it on first use.
	 *
	 * @return the launcher window state singleton
	 */
	public static synchronized LauncherState state() {
		LauncherState state = launcherState;
		if (state == null) {
			state = new LauncherState();
			launcherState = state;
			loadState(state);
		}
		return state;
	}

	/**
	 * Reads persisted window geometry into `state`, ignoring a missing file.
	 *
	 * @param state the state to populate
	 */
	private static void loadState(LauncherState state) {
		Path path = STATE_PATH;
		if (!Files.isRegularFile(path)) {
			return;
		}
		try {
			StateSnapshot snapshot = JsonUtils.fromJsonFile(path, StateSnapshot.class);
			if (snapshot != null) {
				snapshot.applyTo(state);
			}
		} catch (Exception e) {
			LOG.warning("Failed to load launcher state from " + path, e);
		}
	}

	// Persists the current window geometry.
	public static void saveState() {
		LauncherState state = launcherState;
		if (state == null) {
			return;
		}
		try {
			Path parent = STATE_PATH.getParent();
			if (parent != null) {
				Files.createDirectories(parent);
			}
			JsonUtils.writeToJsonFile(STATE_PATH, StateSnapshot.of(state));
		} catch (IOException e) {
			LOG.warning("Failed to save launcher state to " + STATE_PATH, e);
		}
	}

	/**
	 * Returns the process-wide launcher settings, loading them on first use.
	 *
	 * @return the launcher settings singleton
	 */
	public static LauncherSettings settings() {
		LauncherSettings settings = launcherSettings;
		if (settings == null) {
			settings = load();
			launcherSettings = settings;
		}
		return settings;
	}

	/**
	 * Loads settings from disk, returning defaults when the file is absent or unreadable.
	 * Package-private so that a test can read back what {@link #save()} wrote without disturbing the instance the launcher is holding.
	 *
	 * @return the loaded settings
	 */
	static LauncherSettings load() {
		LauncherSettings settings = new LauncherSettings();
		readSnapshot().applyTo(settings);
		return settings;
	}

	/**
	 * Resolves a stored theme colour.
	 * <p>
	 * Older settings files hold one of the standard colour names; the current
	 * format holds the colour itself as `#RRGGBB`, which is what lets a colour
	 * chosen in the picker survive a restart. Both are accepted.
	 *
	 * @param stored the stored name or hex value
	 * @return the resolved colour, or the default when the value is unusable
	 */
	private static ThemeColor resolveThemeColor(String stored) {
		String value = stored.trim();
		for (ThemeColor standard : ThemeColor.STANDARD_COLORS) {
			if (standard.name().equalsIgnoreCase(value)) {
				return standard;
			}
		}
		try {
			return new ThemeColor(value, javafx.scene.paint.Color.web(value));
		} catch (IllegalArgumentException e) {
			LOG.warning("Unknown theme colour in settings: " + stored);
			return ThemeColor.DEFAULT;
		}
	}

	private static <E extends Enum<E>> E parseEnum(Class<E> type, String value, E fallback) {
		try {
			return Enum.valueOf(type, value.trim().toUpperCase(java.util.Locale.ROOT));
		} catch (IllegalArgumentException e) {
			LOG.warning("Unknown " + type.getSimpleName() + " value in settings: " + value);
			return fallback;
		}
	}

	/**
	 * Renders a background colour for the settings file.
	 * <p>
	 * Only a flat colour is stored: the appearance page's pickers produce one, and a
	 * gradient in a settings file would be a value nothing can edit.
	 *
	 * @param paint the colour, or `null`
	 * @return its hex form, or `null` when there is nothing to store
	 */
	private static @Nullable String paintToString(@Nullable javafx.scene.paint.Paint paint) {
		if (!(paint instanceof javafx.scene.paint.Color color)) {
			return null;
		}
		return String.format("#%02X%02X%02X",
		                     Math.round(color.getRed() * 255),
		                     Math.round(color.getGreen() * 255),
		                     Math.round(color.getBlue() * 255));
	}

	/**
	 * Reads a background colour written by [SettingsManager#paintToString].
	 *
	 * @param value the stored hex string
	 * @return the colour, or `null` when it cannot be read
	 */
	private static javafx.scene.paint.@Nullable Paint paintOf(String value) {
		try {
			return javafx.scene.paint.Color.web(value.trim());
		} catch (IllegalArgumentException e) {
			LOG.warning("Unknown background colour in settings: " + value);
			return null;
		}
	}

	/**
	 * Writes the current launcher settings to disk.
	 * <p>
	 * Failures are logged rather than propagated so that a read-only home
	 * directory cannot make the launcher unusable.
	 */
	public static void save() {
		writeSnapshot(Snapshot.of(settings()));
	}

	/**
	 * Adds a folder to the settings file, and selects it, in one read-modify-write.
	 * <p>
	 * This is how a folder is added from the command line. It is written into the file as that file
	 * is on disk, rather than out of this process's copy of the settings: the copy was read when the
	 * process started and holds every other setting as it was then, so publishing it would put back
	 * whatever the window changed in between. The caller holds the launcher's lock, so nothing else
	 * is writing while this runs.
	 *
	 * @param path the folder to add
	 * @return the entry for it, the one already there or a new one
	 */
	public static GameDirectory addGameDirectory(Path path) {
		Snapshot snapshot = readSnapshot();
		List<GameDirectory> directories = new ArrayList<>(
				snapshot.gameDirectories == null ? List.of() : snapshot.gameDirectories);
		GameDirectory added = GameDirectoryManager.entryFor(directories, path, null);

		snapshot.gameDirectories = List.copyOf(directories);
		snapshot.selectedGameDirectoryId = added.id();
		writeSnapshot(snapshot);
		return added;
	}

	/**
	 * Reads the settings file as it is on disk.
	 *
	 * @return the snapshot, empty when there is no readable file
	 */
	private static Snapshot readSnapshot() {
		if (Files.isRegularFile(SETTINGS_PATH)) {
			try {
				Snapshot snapshot = JsonUtils.fromJsonFile(SETTINGS_PATH, Snapshot.class);
				if (snapshot != null) {
					return snapshot;
				}
			} catch (Exception e) {
				LOG.warning("Failed to read launcher settings from " + SETTINGS_PATH, e);
			}
		}
		return new Snapshot();
	}

	/**
	 * Writes a snapshot to the settings file.
	 * <p>
	 * Through a file beside it and a move, rather than over it. Writing over it means truncating it
	 * first, and a reader that arrives in between — another launcher reading the settings at
	 * start-up, which is the usual case — opens a file that is empty or half written: for this file
	 * that is no accounts, no API keys, nothing. Through a move, what a reader opens is either the
	 * file as it was or the file as it is, whole either way.
	 *
	 * @param snapshot the snapshot to write
	 */
	private static void writeSnapshot(Snapshot snapshot) {
		try {
			Path directory = SETTINGS_PATH.getParent();
			if (directory == null) {
				// A path with no parent is the root of a filesystem, which this file is never in.
				throw new IOException("The settings file has no directory: " + SETTINGS_PATH);
			}
			Files.createDirectories(directory);

			Path written = Files.createTempFile(directory, "launcher-settings", ".json");
			try {
				JsonUtils.writeToJsonFile(written, snapshot);
				restrictPermissions(written);
				move(written, SETTINGS_PATH);
			} finally {
				Files.deleteIfExists(written);
			}
		} catch (IOException e) {
			LOG.warning("Failed to save launcher settings to " + SETTINGS_PATH, e);
		}
	}

	/**
	 * Puts a written file in place of the settings file.
	 *
	 * @param written the file to move
	 * @param target  where it goes
	 * @throws IOException when it cannot be moved
	 */
	private static void move(Path written, Path target) throws IOException {
		try {
			Files.move(written, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			// A filesystem that cannot do it atomically — a network mount, some Windows
			// configurations — still gets the replacement, and with it the part that matters most:
			// the reader opens one file or the other, never a half-written one.
			LOG.info("Moving " + written + " into place atomically is not supported here", e);
			Files.move(written, target, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	/**
	 * Makes the settings file readable only by its owner.
	 * <p>
	 * The file holds the accounts' API keys, and the harness keeps its own credentials in a file
	 * with the same restriction for the same reason. A default `umask` writes it `0644`, which on a
	 * machine with more than one person on it is a key given away — and the key is not a preference
	 * that can be reset, it is a credential that bills somebody.
	 * <p>
	 * A filesystem that cannot express the mode (Windows, some mounts) fails here and is ignored:
	 * the key still works, and refusing to save settings over an unavailable permission bit would
	 * be trading the whole file for a hardening step.
	 *
	 * @param file the file to restrict
	 */
	private static void restrictPermissions(Path file) {
		try {
			java.nio.file.attribute.PosixFileAttributeView view = Files.getFileAttributeView(file,
			                                                                                 java.nio.file.attribute.PosixFileAttributeView.class);
			if (view != null) {
				view.setPermissions(java.util.EnumSet.of(java.nio.file.attribute.PosixFilePermission.OWNER_READ,
				                                         java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
			}
		} catch (IOException | UnsupportedOperationException e) {
			LOG.info("Could not restrict the permissions of " + file, e);
		}
	}

	/**
	 * Applies the selection written before selections were kept per folder.
	 * <p>
	 * A launcher that kept one selection for the whole program was showing one
	 * folder, so the value belongs to the folder it owns. A value already chosen
	 * since is left alone: it is the newer answer.
	 *
	 * @param settings the settings to update
	 * @param legacyId the instance id an older launcher stored, or `null`
	 */
	static void applyLegacySelection(LauncherSettings settings, @Nullable String legacyId) {
		if (legacyId == null || settings.getSelectedInstance(GameDirectory.DEFAULT_ID) != null) {
			return;
		}
		settings.setSelectedInstance(GameDirectory.DEFAULT_ID, legacyId);
	}

	/**
	 * The serialisable view of [LauncherSettings].
	 * <p>
	 * Every field is nullable so that a partially written or older file still
	 * loads; missing values keep the defaults already present in
	 * [LauncherSettings].
	 */
	private static final class Snapshot {
		@SerializedName("selectedThemePackId")
		private @Nullable String selectedThemePackId;

		@SerializedName("selectedThemeId")
		private @Nullable String selectedThemeId;

		@SerializedName("themeAppearanceOverrides")
		private @Nullable List<String> themeAppearanceOverrides;

		@SerializedName("themeBrightnessMode")
		private @Nullable String themeBrightnessMode;

		@SerializedName("themeColor")
		private @Nullable String themeColor;

		@SerializedName("themeColorType")
		private @Nullable String themeColorType;

		@SerializedName("themeColorStyle")
		private @Nullable String themeColorStyle;

		@SerializedName("titleBarTransparent")
		private @Nullable Boolean titleBarTransparent;

		@SerializedName("launcherFontFamily")
		private @Nullable String launcherFontFamily;

		@SerializedName("defaultNodeRuntime")
		private @Nullable String defaultNodeRuntime;

		@SerializedName("defaultHomeMode")
		private @Nullable String defaultHomeMode;

		@SerializedName("gameDirectories")
		private @Nullable java.util.List<GameDirectory> gameDirectories;

		@SerializedName("selectedGameDirectoryId")
		private @Nullable String selectedGameDirectoryId;

		@SerializedName("windowTransparent")
		private @Nullable Boolean windowTransparent;

		@SerializedName("backgroundType")
		private @Nullable String backgroundType;

		@SerializedName("builtinBackgroundId")
		private @Nullable String builtinBackgroundId;

		@SerializedName("customBackgroundImagePath")
		private @Nullable String customBackgroundImagePath;

		@SerializedName("networkBackgroundImageUrl")
		private @Nullable String networkBackgroundImageUrl;

		@SerializedName("backgroundOpacity")
		private @Nullable Double backgroundOpacity;

		@SerializedName("networkBackgroundImageCachePolicy")
		private @Nullable String networkBackgroundImageCachePolicy;

		@SerializedName("backgroundFallbackType")
		private @Nullable String backgroundFallbackType;

		// Both are colours the appearance page's pickers edit, so both are settings the user
		// can change. They are stored as a hex string, which is what a colour picker
		// round-trips and what a settings file written by hand can say.
		@SerializedName("customBackgroundPaint")
		private @Nullable String customBackgroundPaint;

		@SerializedName("backgroundFallbackPaint")
		private @Nullable String backgroundFallbackPaint;

		@SerializedName("backgroundLoadPolicy")
		private @Nullable String backgroundLoadPolicy;

		@SerializedName("logFontFamily")
		private @Nullable String logFontFamily;

		@SerializedName("logFontSize")
		private @Nullable Double logFontSize;

		@SerializedName("fontAntiAliasing")
		private @Nullable String fontAntiAliasing;

		@SerializedName("defaultLaunchArguments")
		private @Nullable String defaultLaunchArguments;

		@SerializedName("accounts")
		private @Nullable java.util.List<AccountSnapshot> accounts;

		// The suppliers added by address, in the order they were added.
		private @Nullable java.util.List<VendorSnapshot> vendors;

		/**
		 * Which account the launcher uses, by its key. Written to the snapshot because a choice with
		 * no field here is a choice that silently reverts on the next start — the trap that lost
		 * seventeen settings once already.
		 */
		@SerializedName("activeAccountKey")
		private @Nullable String activeAccountKey;

		/**
		 * One account as it is stored.
		 *
		 * @param vendorId the vendor's id
		 * @param apiKey   the key
		 * @param baseUrl  the endpoint, for a vendor whose address is per account
		 * @param label    what the person calls it
		 */
		private record AccountSnapshot(@Nullable DshAccount.AccountKind kind, String vendorId, String apiKey,
		                               @Nullable String baseUrl, @Nullable String label, @Nullable String model,
		                               @Nullable String skinType, @Nullable String skinModel, @Nullable String skinPath,
		                               @Nullable String skinCapePath) {}

		/**
		 * A supplier somebody added by address.
		 * <p>
		 * All five fields, because all five are what the harness needs to route it: the id becomes
		 * the route name, the address is where the calls go, the protocol is what the route
		 * declares, and the variable is where the key is read from.
		 */
		private record VendorSnapshot(String id, String displayName, String apiKeyEnv, String api, String baseUrl) {}

		/**
		 * The language the interface speaks, by the name the locale helper uses.
		 * <p>
		 * The name rather than the locale: the helper resolves a name back to the
		 * supported locale it stands for, which a bare language tag cannot do for every
		 * variant it offers.
		 */
		@SerializedName("language")
		private @Nullable String language;

		@SerializedName("animationDisabled")
		private @Nullable Boolean animationDisabled;

		@SerializedName("nodeSource")
		private @Nullable String nodeSource;

		@SerializedName("selectedInstance")
		private @Nullable java.util.Map<String, String> selectedInstance;

		/**
		 * The single selection written by launchers that kept one for the whole
		 * launcher rather than one per folder.
		 * <p>
		 * Only ever read, and only while nothing per folder has been stored: it
		 * belonged to the folder the launcher owns.
		 */
		@SerializedName("selectedInstanceId")
		private @Nullable String legacySelectedInstanceId;

		@SerializedName("openBrowserOnLaunch")
		private @Nullable Boolean openBrowserOnLaunch;

		// Everything below belongs to a control that exists on one of the settings pages,
		// and each one was written to this file by nothing at all: the snapshot is what
		// `save()` writes and the only thing `load()` reads, so a property with no field
		// here is a setting somebody can change, watch take effect, and lose at the next
		// start. The rows are the ones on the general, download-source and global
		// instance-settings tabs.

		// Stored as the lowercase id the enum itself publishes, the way `nodeSource` is:
		// Gson's enum adapter would write `MANUAL`, so a file written by the interface and
		// one written by hand would disagree about the spelling of the same value.
		@SerializedName("buildScriptPolicy")
		private @Nullable String buildScriptPolicy;

		@SerializedName("launcherVisibility")
		private @Nullable String launcherVisibility;

		@SerializedName("isolationPolicy")
		private @Nullable String isolationPolicy;

		// Old releases persisted "modded" even when no policy was explicitly chosen.
		// A separate key lets new choices survive reloads without retaining that old default.
		@SerializedName("newInstanceIsolationPolicy")
		private @Nullable String newInstanceIsolationPolicy;

		@SerializedName("showLogs")
		private @Nullable Boolean showLogs;

		@SerializedName("debugLog")
		private @Nullable Boolean debugLog;

		@SerializedName("checkLeftoverPorts")
		private @Nullable Boolean checkLeftoverPorts;

		@SerializedName("preLaunchCommand")
		private @Nullable String preLaunchCommand;

		@SerializedName("postExitCommand")
		private @Nullable String postExitCommand;

		@SerializedName("globalEnvironment")
		private @Nullable java.util.Map<String, String> globalEnvironment;

		/**
		 * Every place the plugin catalogue is read from, in the order they are tried.
		 * <p>
		 * Absent for a launcher that has never been asked, which reads the two the
		 * ecosystem publishes — the community address and the npm package that
		 * carries the same document.
		 */
		@SerializedName("marketSource")
		private @Nullable String marketSource;

		// The catalogue skills are searched in, by [SkillSource#id], or absent for the community's own index.
		@SerializedName("skillSource")
		private @Nullable String skillSource;

		// Where the modpack market's index is read from, or absent for the published one.
		@SerializedName("packMarketUrl")
		private @Nullable String packMarketUrl;

		// How much of a new instance's dependency tree is held, by the enum's own name.
		@SerializedName("dependencyPolicy")
		private @Nullable String dependencyPolicy;

		@SerializedName("cacheDirectory")
		private @Nullable String cacheDirectory;

		@SerializedName("cacheDirectoryCustom")
		private @Nullable Boolean cacheDirectoryCustom;

		@SerializedName("autoDownloadThreads")
		private @Nullable Boolean autoDownloadThreads;

		@SerializedName("downloadConcurrency")
		private @Nullable Integer downloadConcurrency;

		@SerializedName("proxyMode")
		private @Nullable String proxyMode;

		@SerializedName("proxyHost")
		private @Nullable String proxyHost;

		@SerializedName("proxyPort")
		private @Nullable String proxyPort;

		@SerializedName("proxyAuthenticated")
		private @Nullable Boolean proxyAuthenticated;

		@SerializedName("proxyUser")
		private @Nullable String proxyUser;

		@SerializedName("proxyPassword")
		private @Nullable String proxyPassword;

		/**
		 * Captures the current settings into a serialisable snapshot.
		 *
		 * @param settings the settings to capture
		 * @return the snapshot
		 */
		static Snapshot of(LauncherSettings settings) {
			Snapshot snapshot = new Snapshot();
			ThemeReference theme = settings.getSelectedThemeOrDefault();
			snapshot.selectedThemePackId = theme.packId();
			snapshot.selectedThemeId = theme.themeId();
			snapshot.themeAppearanceOverrides = new ArrayList<>(settings.getThemeAppearanceOverrides());
			snapshot.themeBrightnessMode = settings.themeBrightnessModeProperty().get();
			snapshot.themeColor = ThemeColor.getColorDisplayName(settings.customThemeColorProperty().get().color());
			snapshot.themeColorType = settings.themeColorTypeProperty().get().name();
			snapshot.themeColorStyle = settings.themeColorStyleProperty().get().name();
			snapshot.titleBarTransparent = settings.titleBarTransparentProperty().get();
			snapshot.launcherFontFamily = settings.launcherFontFamilyProperty().get();
			snapshot.defaultNodeRuntime = settings.defaultNodeRuntimeProperty().get();
			snapshot.defaultHomeMode = settings.defaultHomeModeProperty().get().name();
			snapshot.gameDirectories = java.util.List.copyOf(settings.gameDirectoriesProperty().get());
			snapshot.selectedGameDirectoryId = settings.selectedGameDirectoryIdProperty().get();
			snapshot.windowTransparent = settings.windowTransparentProperty().get();
			snapshot.backgroundType = settings.backgroundTypeProperty().get().name();
			snapshot.builtinBackgroundId = settings.builtinBackgroundIdProperty().get();
			snapshot.customBackgroundImagePath = settings.customBackgroundImagePathProperty().get();
			snapshot.networkBackgroundImageUrl = settings.networkBackgroundImageUrlProperty().get();
			snapshot.backgroundOpacity = settings.backgroundOpacityProperty().get();
			snapshot.networkBackgroundImageCachePolicy = settings.networkBackgroundImageCachePolicyProperty()
			                                                     .get()
			                                                     .name();
			snapshot.backgroundFallbackType = settings.backgroundFallbackTypeProperty().get().name();
			snapshot.customBackgroundPaint = paintToString(settings.customBackgroundPaintProperty().get());
			snapshot.backgroundFallbackPaint = paintToString(settings.backgroundFallbackPaintProperty().get());
			snapshot.backgroundLoadPolicy = settings.backgroundLoadPolicyProperty().get().name();
			snapshot.logFontFamily = settings.logFontFamilyProperty().get();
			snapshot.logFontSize = settings.logFontSizeProperty().get();
			snapshot.fontAntiAliasing = settings.fontAntiAliasing().id();
			snapshot.defaultLaunchArguments = settings.defaultLaunchArgumentsProperty().get();
			snapshot.activeAccountKey = settings.activeAccountKey();
			snapshot.accounts = settings.getAccounts()
			                            .stream()
			                            .map(account -> new AccountSnapshot(account.kind(),
			                                                                account.vendorId(),
			                                                                account.apiKey(),
			                                                                account.baseUrl(),
			                                                                account.label(),
			                                                                account.model(),
			                                                                account.skinOrDefault().type().name(),
			                                                                account.skinOrDefault().model().modelName,
			                                                                account.skinOrDefault().localSkinPath(),
			                                                                account.skinOrDefault().localCapePath()))
			                            .toList();
			snapshot.vendors = settings.getCustomVendors()
			                           .stream()
			                           .map(vendor -> new VendorSnapshot(vendor.id(),
			                                                             vendor.displayName(),
			                                                             vendor.apiKeyEnv(),
			                                                             vendor.api(),
			                                                             vendor.baseUrl()))
			                           .toList();
			settings.languageProperty().get();
			snapshot.language = settings.languageProperty().get().getName();
			snapshot.animationDisabled = settings.animationDisabledProperty().get();
			snapshot.nodeSource = settings.nodeSourceProperty().get().id();
			snapshot.selectedInstance = new java.util.LinkedHashMap<>(settings.getSelectedInstance());
			snapshot.openBrowserOnLaunch = settings.openBrowserOnLaunchProperty().get();
			snapshot.buildScriptPolicy = settings.buildScriptPolicy().id();
			snapshot.launcherVisibility = settings.launcherVisibility().id();
			snapshot.isolationPolicy = settings.isolationPolicy().id();
			snapshot.newInstanceIsolationPolicy = settings.isolationPolicyProperty().get() == null
			        ? DshIsolationPolicy.ALWAYS.id()
			        : settings.isolationPolicyProperty().get().id();
			snapshot.showLogs = settings.showLogsProperty().get();
			snapshot.debugLog = settings.debugLogProperty().get();
			snapshot.checkLeftoverPorts = settings.checkLeftoverPortsProperty().get();
			snapshot.preLaunchCommand = settings.preLaunchCommandProperty().get();
			snapshot.postExitCommand = settings.postExitCommandProperty().get();
			snapshot.globalEnvironment = new java.util.LinkedHashMap<>(settings.globalEnvironment());
			snapshot.marketSource = settings.marketSourceProperty().get();
			snapshot.skillSource = settings.skillSourceProperty().get();
			snapshot.packMarketUrl = settings.packMarketUrlProperty().get();
			snapshot.dependencyPolicy = settings.dependencyPolicy().name();
			snapshot.cacheDirectory = settings.cacheDirectoryProperty().get();
			snapshot.cacheDirectoryCustom = settings.cacheDirectoryCustomProperty().get();
			snapshot.autoDownloadThreads = settings.autoDownloadThreadsProperty().get();
			snapshot.downloadConcurrency = settings.downloadConcurrencyProperty().get();
			settings.proxyModeProperty().get();
			snapshot.proxyMode = settings.proxyModeProperty().get().id();
			snapshot.proxyHost = settings.proxyHostProperty().get();
			snapshot.proxyPort = settings.proxyPortProperty().get();
			snapshot.proxyAuthenticated = settings.proxyAuthenticatedProperty().get();
			snapshot.proxyUser = settings.proxyUserProperty().get();
			snapshot.proxyPassword = settings.proxyPasswordProperty().get();
			return snapshot;
		}

		/**
		 * Applies this snapshot onto a settings object, ignoring unset fields.
		 *
		 * @param settings the settings to update
		 */
		void applyTo(LauncherSettings settings) {
			// Each field is applied on its own: a single unreadable value must
			// not silently discard every setting that follows it.
			if (selectedThemePackId != null) {
				settings.selectedThemeProperty().set(new ThemeReference(selectedThemePackId, selectedThemeId));
			}
			if (themeAppearanceOverrides != null) {
				settings.getThemeAppearanceOverrides().clear();
				settings.getThemeAppearanceOverrides().addAll(themeAppearanceOverrides);
			}
			if (themeBrightnessMode != null) {
				settings.themeBrightnessModeProperty().set(themeBrightnessMode);
			}
			if (themeColor != null) {
				settings.customThemeColorProperty().set(resolveThemeColor(themeColor));
			}
			if (themeColorType != null) {
				settings.themeColorTypeProperty()
				        .set(parseEnum(ThemeColorType.class, themeColorType, ThemeColorType.DEFAULT));
			}
			if (themeColorStyle != null) {
				settings.themeColorStyleProperty()
				        .set(parseEnum(ColorStyle.class, themeColorStyle, ColorStyle.FIDELITY));
			}
			if (defaultNodeRuntime != null) {
				settings.defaultNodeRuntimeProperty().set(defaultNodeRuntime);
			}
			if (defaultHomeMode != null) {
				settings.defaultHomeModeProperty()
				        .set(parseEnum(DshHomeMode.class, defaultHomeMode, DshHomeMode.ISOLATED));
			}
			if (gameDirectories != null) {
				settings.gameDirectoriesProperty().set(java.util.List.copyOf(gameDirectories));
			}
			if (selectedGameDirectoryId != null) {
				settings.selectedGameDirectoryIdProperty().set(selectedGameDirectoryId);
			}
			if (launcherFontFamily != null) {
				settings.launcherFontFamilyProperty().set(launcherFontFamily);
			}
			if (titleBarTransparent != null) {
				settings.titleBarTransparentProperty().set(titleBarTransparent);
			}
			if (windowTransparent != null) {
				settings.windowTransparentProperty().set(windowTransparent);
			}
			if (backgroundType != null) {
				settings.backgroundTypeProperty()
				        .set(parseEnum(BackgroundType.class, backgroundType, BackgroundType.DEFAULT));
			}
			if (builtinBackgroundId != null) {
				settings.builtinBackgroundIdProperty().set(builtinBackgroundId);
			}
			if (customBackgroundImagePath != null) {
				settings.customBackgroundImagePathProperty().set(customBackgroundImagePath);
			}
			if (networkBackgroundImageUrl != null) {
				settings.networkBackgroundImageUrlProperty().set(networkBackgroundImageUrl);
			}
			if (backgroundOpacity != null) {
				settings.backgroundOpacityProperty().set(backgroundOpacity);
			}
			if (networkBackgroundImageCachePolicy != null) {
				settings.networkBackgroundImageCachePolicyProperty()
				        .set(parseEnum(NetworkBackgroundImageCachePolicy.class,
				                       networkBackgroundImageCachePolicy,
				                       NetworkBackgroundImageCachePolicy.ENABLED));
			}
			if (backgroundFallbackType != null) {
				settings.backgroundFallbackTypeProperty()
				        .set(parseEnum(BackgroundType.class, backgroundFallbackType, BackgroundType.BUILTIN));
			}
			if (customBackgroundPaint != null) {
				javafx.scene.paint.Paint paint = paintOf(customBackgroundPaint);
				if (paint != null) {
					settings.customBackgroundPaintProperty().set(paint);
				}
			}
			if (backgroundFallbackPaint != null) {
				javafx.scene.paint.Paint paint = paintOf(backgroundFallbackPaint);
				if (paint != null) {
					settings.backgroundFallbackPaintProperty().set(paint);
				}
			}
			if (backgroundLoadPolicy != null) {
				settings.backgroundLoadPolicyProperty()
				        .set(parseEnum(BackgroundLoadPolicy.class,
				                       backgroundLoadPolicy,
				                       BackgroundLoadPolicy.WAIT_FOR_BACKGROUND));
			}
			if (logFontFamily != null) {
				settings.logFontFamilyProperty().set(logFontFamily);
			}
			if (logFontSize != null) {
				settings.logFontSizeProperty().set(logFontSize);
			}
			if (fontAntiAliasing != null) {
				settings.fontAntiAliasingProperty()
				        .set(org.jackhuang.hmcl.setting.FontAntiAliasing.of(fontAntiAliasing));
			}
			if (activeAccountKey != null) {
				settings.activeAccountKeyProperty().set(activeAccountKey);
			}
			if (accounts != null) {
				for (AccountSnapshot account : accounts) {
					settings.getAccounts()
					        .add(new DshAccount(
							        account.kind() == null ? DshAccount.AccountKind.THIRD_PARTY : account.kind(),
							        account.vendorId(),
							        account.apiKey(),
							        account.baseUrl(),
							        account.label(),
							        account.model(),
							        new DshSkinChoice(
									        // Read through the forgiving reader rather than the enum's own:
									        // a name this launcher does not know — a file written by a later
									        // version, or one edited by hand — must not stop the whole
									        // settings file from loading.
									        java.util.Objects.requireNonNullElse(DshSkinChoice.Type.fromStorage(account.skinType()),
									                                             DshSkinChoice.Type.DEFAULT),
									        DshSkinChoice.TextureModel.fromStorage(account.skinModel()),
									        account.skinPath(),
									        account.skinCapePath())));
				}
			}
			if (vendors != null) {
				for (VendorSnapshot vendor : vendors) {
					// A supplier with no address cannot be routed, so a row that has lost one — a
					// hand-edited file, or a version that wrote it differently — is dropped rather
					// than offered as something that cannot work.
					if (vendor.baseUrl() == null || vendor.baseUrl().isBlank()) {
						continue;
					}
					settings.getCustomVendors()
					        .add(new DshVendor(vendor.id(),
					                           vendor.displayName() == null ? vendor.id() : vendor.displayName(),
					                           vendor.apiKeyEnv() == null ? "" : vendor.apiKeyEnv(),
					                           vendor.api() == null ? DshVendor.APIS.get(0) : vendor.api(),
					                           vendor.baseUrl(),
					                           false));
				}
			}
			if (defaultLaunchArguments != null) {
				settings.defaultLaunchArgumentsProperty().set(defaultLaunchArguments);
			}
			if (language != null) {
				settings.languageProperty().set(org.jackhuang.hmcl.util.i18n.SupportedLocale.getLocaleByName(language));
			}
			if (animationDisabled != null) {
				settings.animationDisabledProperty().set(animationDisabled);
			}
			if (nodeSource != null) {
				settings.nodeSourceProperty().set(NodeSource.of(nodeSource));
			}
			if (selectedInstance != null) {
				settings.getSelectedInstance().clear();
				settings.getSelectedInstance().putAll(selectedInstance);
			} else {
				applyLegacySelection(settings, legacySelectedInstanceId);
			}
			if (openBrowserOnLaunch != null) {
				settings.openBrowserOnLaunchProperty().set(openBrowserOnLaunch);
			}
			if (buildScriptPolicy != null) {
				settings.buildScriptPolicyProperty().set(DshBuildScriptPolicy.of(buildScriptPolicy));
			}
			if (launcherVisibility != null) {
				settings.launcherVisibilityProperty().set(DshLauncherVisibility.of(launcherVisibility));
			}
			if (newInstanceIsolationPolicy != null) {
				settings.isolationPolicyProperty().set(DshIsolationPolicy.of(newInstanceIsolationPolicy));
			} else if (isolationPolicy != null) {
				DshIsolationPolicy legacyPolicy = DshIsolationPolicy.of(isolationPolicy);
				// Only the creation default changes. Existing instances still resolve their
				// recorded homeMode, including GLOBAL's unchanged defaultHomeMode setting.
				settings.isolationPolicyProperty().set(legacyPolicy == DshIsolationPolicy.WITH_PLUGINS
				        ? DshIsolationPolicy.ALWAYS : legacyPolicy);
			}
			if (showLogs != null) {
				settings.showLogsProperty().set(showLogs);
			}
			if (debugLog != null) {
				settings.debugLogProperty().set(debugLog);
				// The switch decides whether lines are written, so the logger has to be
				// told as well: without this it stays as the interface left it, which
				// after a restart is off.
				org.jackhuang.hmcl.util.logging.Logger.setDebugEnabled(debugLog);
			}
			if (checkLeftoverPorts != null) {
				settings.checkLeftoverPortsProperty().set(checkLeftoverPorts);
			}
			if (preLaunchCommand != null) {
				settings.preLaunchCommandProperty().set(preLaunchCommand);
			}
			if (postExitCommand != null) {
				settings.postExitCommandProperty().set(postExitCommand);
			}
			if (globalEnvironment != null) {
				settings.globalEnvironmentProperty().set(new java.util.LinkedHashMap<>(globalEnvironment));
			}
			if (packMarketUrl != null) {
				settings.packMarketUrlProperty().set(packMarketUrl);
			}
			if (dependencyPolicy != null) {
				// A name this launcher does not know is left at the default rather than refusing the
				// whole settings file: a policy is a preference, and losing it costs one choice.
				try {
					settings.dependencyPolicyProperty().set(DshDependencyPolicy.valueOf(dependencyPolicy));
				} catch (IllegalArgumentException e) {
					LOG.warning("Unknown dependency policy in the settings: " + dependencyPolicy);
				}
			}
			if (marketSource != null) {
				settings.marketSourceProperty().set(marketSource);
			}
			if (skillSource != null) {
				settings.skillSourceProperty().set(skillSource);
			}
			if (cacheDirectory != null) {
				settings.cacheDirectoryProperty().set(cacheDirectory);
			}
			if (cacheDirectoryCustom != null) {
				settings.cacheDirectoryCustomProperty().set(cacheDirectoryCustom);
			}
			if (autoDownloadThreads != null) {
				settings.autoDownloadThreadsProperty().set(autoDownloadThreads);
			}
			if (downloadConcurrency != null) {
				settings.downloadConcurrencyProperty().set(downloadConcurrency);
			}
			if (proxyMode != null) {
				settings.proxyModeProperty().set(DshProxyMode.of(proxyMode));
			}
			if (proxyHost != null) {
				settings.proxyHostProperty().set(proxyHost);
			}
			if (proxyPort != null) {
				settings.proxyPortProperty().set(proxyPort);
			}
			if (proxyAuthenticated != null) {
				settings.proxyAuthenticatedProperty().set(proxyAuthenticated);
			}
			if (proxyUser != null) {
				settings.proxyUserProperty().set(proxyUser);
			}
			if (proxyPassword != null) {
				settings.proxyPasswordProperty().set(proxyPassword);
			}
		}
	}

	// The serializable view of [LauncherState].
	private static final class StateSnapshot {
		@SerializedName("x")
		private @Nullable Double x;

		@SerializedName("y")
		private @Nullable Double y;

		@SerializedName("width")
		private @Nullable Double width;

		@SerializedName("height")
		private @Nullable Double height;

		/**
		 * Captures window geometry into a serialisable snapshot.
		 *
		 * @param state the state to capture
		 * @return the snapshot
		 */
		static StateSnapshot of(LauncherState state) {
			StateSnapshot snapshot = new StateSnapshot();
			snapshot.x = state.getX();
			snapshot.y = state.getY();
			snapshot.width = state.getWidth();
			snapshot.height = state.getHeight();
			return snapshot;
		}

		/**
		 * Applies this snapshot onto a state object, ignoring unset fields.
		 *
		 * @param state the state to update
		 */
		void applyTo(LauncherState state) {
			if (x != null) {
				state.setX(x);
			}
			if (y != null) {
				state.setY(y);
			}
			if (width != null) {
				state.setWidth(width);
			}
			if (height != null) {
				state.setHeight(height);
			}
		}
	}
}
