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

import com.mcxcc303.hdsl.instance.InstanceStore;

import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.ObjectBinding;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import com.mcxcc303.hdsl.instance.DshInstance;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

import static org.jackhuang.hmcl.setting.SettingsManager.settings;

/**
 * Repository observing instances within a game directory.
 * <p>
 * Publishes snapshot updates on directory rescans and maintains selection state.
 */
@NotNullByDefault
public final class DshInstanceRepository {

	/**
	 * Immutable snapshot of instances in a directory at a specific revision.
	 *
	 * @param directory directory described by this snapshot
	 * @param instances instances contained, newest first
	 * @param revision  sequential read counter
	 */
	public record Snapshot(GameDirectory directory, List<DshInstance> instances, long revision) {

		/**
		 * Finds an instance by identifier.
		 *
		 * @param id the instance identifier
		 * @return matching instance, or {@code null} if not found
		 */
		public @Nullable DshInstance findInstance(String id) {
			for (DshInstance instance : instances) {
				if (instance.id().equals(id)) {
					return instance;
				}
			}
			return null;
		}
	}

	private final GameDirectory directory;
	private final ReadOnlyObjectWrapper<Snapshot> snapshot =
			new ReadOnlyObjectWrapper<>(this, "snapshot");
	private final ObjectBinding<@Nullable String> selectedInstanceId;
	private final ReadOnlyObjectWrapper<@Nullable DshInstance> selectedInstance =
			new ReadOnlyObjectWrapper<>(this, "selectedInstance");
	private boolean loaded;
	private final java.util.concurrent.atomic.AtomicLong revisions = new java.util.concurrent.atomic.AtomicLong();

	DshInstanceRepository(GameDirectory directory) {
		this.directory = directory;
		this.snapshot.set(new Snapshot(directory, List.of(), 0));
		this.selectedInstanceId = Bindings.valueAt(settings().getSelectedInstance(), directory.id());
		this.selectedInstance.bind(Bindings.createObjectBinding(
				this::resolveSelectedInstance, selectedInstanceId, snapshot));
	}

	/**
	 * Returns the directory described by this repository.
	 *
	 * @return the directory
	 */
	public GameDirectory getDirectory() {
		return directory;
	}

	/**
	 * Returns the latest directory snapshot.
	 *
	 * @return current snapshot
	 */
	public Snapshot getSnapshot() {
		return snapshot.get();
	}

	/**
	 * Returns the snapshot property, updated on each rescan.
	 *
	 * @return snapshot property
	 */
	public ReadOnlyObjectProperty<Snapshot> snapshotProperty() {
		return snapshot.getReadOnlyProperty();
	}

	/**
	 * Returns instances in the current snapshot, newest first.
	 *
	 * @return list of instances
	 */
	public List<DshInstance> getInstances() {
		return snapshot.get().instances();
	}

	/**
	 * Returns whether the directory has been loaded at least once.
	 *
	 * @return {@code true} if loaded
	 */
	public boolean isLoaded() {
		return loaded;
	}

	/**
	 * Returns the selected instance in this directory.
	 *
	 * @return selected instance, or {@code null} if none
	 */
	public @Nullable DshInstance getSelectedInstance() {
		return selectedInstance.get();
	}

	/**
	 * Returns the selected-instance property.
	 *
	 * @return selected instance property
	 */
	public ReadOnlyObjectProperty<@Nullable DshInstance> selectedInstanceProperty() {
		return selectedInstance.getReadOnlyProperty();
	}

	/**
	 * Sets the selected instance for this directory and persists settings.
	 *
	 * @param instance instance to select, or {@code null} to clear
	 */
	public void setSelectedInstance(@Nullable DshInstance instance) {
		settings().setSelectedInstance(directory.id(), instance == null ? null : instance.id());
		SettingsManager.save();
	}

	/**
	 * Reconciles the persisted selection against current instances.
	 */
	public void refreshSelectedInstance() {
		@Nullable String persistedId = selectedInstanceId.get();
		@Nullable DshInstance refreshed = persistedId != null ? getSnapshot().findInstance(persistedId) : null;
		if (refreshed == null) {
			refreshed = getInstances().stream().findFirst().orElse(null);
		}

		@Nullable String refreshedId = refreshed == null ? null : refreshed.id();
		if (!Objects.equals(persistedId, refreshedId)) {
			settings().setSelectedInstance(directory.id(), refreshedId);
			SettingsManager.save();
		}
	}

	/**
	 * Rescans the directory and publishes a new snapshot.
	 */
	public void refresh() {
		List<DshInstance> instances = InstanceStore.listIn(directory.directory());
		publish(new Snapshot(directory, instances, revisions.incrementAndGet()));
	}

	private void publish(Snapshot next) {
		Runnable apply = () -> {
			loaded = true;
			snapshot.set(next);
			refreshSelectedInstance();
		};
		if (Platform.isFxApplicationThread()) {
			apply.run();
			return;
		}
		try {
			Platform.runLater(apply);
		} catch (IllegalStateException e) {
			// Fallback when JavaFX platform is not initialized (e.g. CLI or tests).
			apply.run();
		}
	}

	private @Nullable DshInstance resolveSelectedInstance() {
		String instanceId = selectedInstanceId.get();
		return instanceId == null ? null : getSnapshot().findInstance(instanceId);
	}
}
