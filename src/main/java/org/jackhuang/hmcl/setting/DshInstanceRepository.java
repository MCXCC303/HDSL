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
package org.jackhuang.hmcl.setting;

import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.ObjectBinding;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import org.jackhuang.hmcl.dsh.DshInstance;
import org.jackhuang.hmcl.dsh.DshInstanceManager;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

import static org.jackhuang.hmcl.setting.SettingsManager.settings;

/// The instances of one folder, as the interface sees them.
///
/// This is the HMCL-DSH counterpart of HMCL's `HMCLGameRepository`, and it is
/// the reason the interface never has to go looking for changes itself: it owns
/// a snapshot that is republished whenever the folder is re-read, and a
/// selection that re-resolves against that snapshot. A page binds to the two and
/// is therefore always showing what is on disk, without a refresh call anywhere.
///
/// Instances are still files, so a read is a directory listing; the snapshot
/// exists to give that listing an identity the interface can observe, not to
/// cache it.
@NotNullByDefault
public final class DshInstanceRepository {
    /// One read of a folder: the instances it held at that moment.
    ///
    /// @param directory the folder the snapshot describes
    /// @param instances the instances inside it, newest first
    public record Snapshot(GameDirectory directory, List<DshInstance> instances) {
        /// Finds an instance by id.
        ///
        /// @param id the instance id
        /// @return the instance, or `null` when the snapshot does not hold it
        public @Nullable DshInstance findInstance(String id) {
            for (DshInstance instance : instances) {
                if (instance.id().equals(id)) {
                    return instance;
                }
            }
            return null;
        }
    }

    /// The folder this repository describes.
    private final GameDirectory directory;

    /// The last read of the folder.
    ///
    /// It starts empty rather than unset: a page may bind to the selection
    /// before the first read has finished, and an unread folder holds nothing.
    private final ReadOnlyObjectWrapper<Snapshot> snapshot =
            new ReadOnlyObjectWrapper<>(this, "snapshot");

    /// The instance selected in this folder, as the settings remember it.
    ///
    /// Resolved from the settings map rather than copied out of it, so a
    /// selection written anywhere — the list, the home page, another window —
    /// reaches every page without either of them knowing about the other.
    private final ObjectBinding<@Nullable String> selectedInstanceId;

    /// The selected instance, resolved against the current snapshot.
    private final ReadOnlyObjectWrapper<@Nullable DshInstance> selectedInstance =
            new ReadOnlyObjectWrapper<>(this, "selectedInstance");

    /// Whether the folder has been read at least once.
    private boolean loaded;

    /// Creates a repository for a folder.
    ///
    /// @param directory the folder to describe
    DshInstanceRepository(GameDirectory directory) {
        this.directory = directory;
        this.snapshot.set(new Snapshot(directory, List.of()));
        this.selectedInstanceId = Bindings.valueAt(settings().getSelectedInstance(), directory.id());
        this.selectedInstance.bind(Bindings.createObjectBinding(
                this::resolveSelectedInstance, selectedInstanceId, snapshot));
    }

    /// Returns the folder this repository describes.
    ///
    /// @return the folder
    public GameDirectory getDirectory() {
        return directory;
    }

    /// Returns the last read of the folder.
    ///
    /// @return the snapshot
    public Snapshot getSnapshot() {
        return snapshot.get();
    }

    /// Returns the snapshot property, which changes on every read.
    ///
    /// @return the snapshot property
    public ReadOnlyObjectProperty<Snapshot> snapshotProperty() {
        return snapshot.getReadOnlyProperty();
    }

    /// Returns the instances the folder holds, newest first.
    ///
    /// @return the instances
    public List<DshInstance> getInstances() {
        return snapshot.get().instances();
    }

    /// Reports whether the folder has been read.
    ///
    /// A listener registered before the first read is called by it, so this is
    /// only asked to decide whether a registration has to be answered
    /// immediately.
    ///
    /// @return whether a read has completed
    public boolean isLoaded() {
        return loaded;
    }

    /// Returns the instance selected in this folder.
    ///
    /// @return the instance, or `null` when the folder holds none
    public @Nullable DshInstance getSelectedInstance() {
        return selectedInstance.get();
    }

    /// Returns the selected-instance property.
    ///
    /// @return the selected-instance property
    public ReadOnlyObjectProperty<@Nullable DshInstance> selectedInstanceProperty() {
        return selectedInstance.getReadOnlyProperty();
    }

    /// Records an instance as this folder's selection.
    ///
    /// The value is only written down; the property follows from it, so passing
    /// an instance of another folder cannot desynchronise the two.
    ///
    /// @param instance the instance to select, or `null` to select nothing
    public void setSelectedInstance(@Nullable DshInstance instance) {
        settings().setSelectedInstance(directory.id(), instance == null ? null : instance.id());
        SettingsManager.save();
    }

    /// Corrects the remembered selection against the current snapshot.
    ///
    /// A selection naming an instance that is gone is no selection: the home
    /// page would otherwise offer to start something that is not there, while
    /// the list says the folder holds nothing. An empty folder clears it, and a
    /// folder that holds something but has nothing chosen chooses its first
    /// instance — which is what makes a newly created instance the one the home
    /// page acts on without anyone having to click it.
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

    /// Re-reads the folder and publishes what it holds.
    ///
    /// Safe to call from any thread: the read happens where it is called, and
    /// the publication is moved onto the interface thread, because the snapshot
    /// is what interface bindings hang off.
    public void refresh() {
        List<DshInstance> instances = DshInstanceManager.listIn(directory.directory());
        publish(new Snapshot(directory, instances));
    }

    /// Publishes a snapshot and re-resolves the selection against it.
    ///
    /// @param next the snapshot to publish
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
            // No interface is running — a command-line run, or a test. Nobody is
            // bound to the property, so publishing it where the change was made
            // is both safe and the only way to publish it at all.
            apply.run();
        }
    }

    /// Resolves the remembered selection against the current snapshot.
    ///
    /// @return the selected instance, or `null` when the folder holds none
    private @Nullable DshInstance resolveSelectedInstance() {
        String instanceId = selectedInstanceId.get();
        return instanceId == null ? null : getSnapshot().findInstance(instanceId);
    }
}
