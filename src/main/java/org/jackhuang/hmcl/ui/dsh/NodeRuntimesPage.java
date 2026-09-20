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

import com.jfoenix.controls.JFXButton;
import com.jfoenix.controls.JFXListView;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.Skin;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import org.jackhuang.hmcl.dsh.DshException;
import org.jackhuang.hmcl.dsh.DshNodeRuntime;
import org.jackhuang.hmcl.dsh.NodeRuntime;
import org.jackhuang.hmcl.dsh.NodeRuntimeManager;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.ListPageBase;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.ToolbarListPageSkin;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane.MessageType;
import org.jackhuang.hmcl.ui.construct.TwoLineListItem;
import org.jackhuang.hmcl.ui.wizard.Refreshable;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.jackhuang.hmcl.ui.FXUtils.runInFX;
import static org.jackhuang.hmcl.util.i18n.I18n.i18n;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Manages the Node.js runtimes HMCL-DSH can pin an instance to.
///
/// The page is built on HMCL's own list-page stack — [ListPageBase] with a
/// [ToolbarListPageSkin] and cells derived from the same shape its Java
/// management uses — because that is what the original's page is. A toolbar
/// above a list, and a row of version badge, name, tags and path, are the
/// original's answers to "show the runtimes installed on this machine", and the
/// counterparts here are close enough that a reader of HMCL can find their way
/// around it.
@NotNullByDefault
public final class NodeRuntimesPage extends ListPageBase<NodeRuntimesPage.NodeRow> implements Refreshable {
    /// Whether a load is already running.
    private boolean busy;

    /// The runtimes detected on `PATH`, refreshed with the rest.
    private DshNodeRuntime system = DshNodeRuntime.detect().orElse(null);

    /// Creates the page.
    public NodeRuntimesPage() {
        refresh();
    }

    @Override
    protected Skin<?> createDefaultSkin() {
        return new NodeRuntimesPageSkin(this);
    }

    @Override
    public void refresh() {
        if (busy) {
            return;
        }
        busy = true;
        setLoading(true);

        CompletableFuture.supplyAsync(() -> {
            system = DshNodeRuntime.detect().orElse(null);
            return NodeRuntimeManager.listInstalled();
        }, Schedulers.io()).whenComplete((installed, throwable) -> runInFX(() -> {
            busy = false;
            setLoading(false);
            if (throwable != null) {
                LOG.warning("Failed to load Node runtimes", throwable);
                setFailedReason(i18n("dsh.versions.load_failed"));
                return;
            }

            List<NodeRow> rows = new ArrayList<>();
            for (NodeRuntime runtime : installed) {
                rows.add(NodeRow.installed(runtime));
            }
            DshNodeRuntime detected = system;
            rows.add(detected == null ? NodeRow.systemMissing() : NodeRow.system(detected));

            // Mutate the list rather than replacing it: the skin binds the list
            // view's content to this very list, and HMCL fills its page the same
            // way.
            getItems().setAll(rows);
        }));
    }

    /// Installs a published version.
    ///
    /// @param version the version to install
    private void install(String version) {
        setLoading(true);
        CompletableFuture.runAsync(() -> {
            try {
                NodeRuntimeManager.install(version, line -> LOG.info("[node] " + line));
            } catch (DshException e) {
                throw new java.util.concurrent.CompletionException(e);
            }
        }, Schedulers.io()).whenComplete((ignored, throwable) -> runInFX(() -> {
            setLoading(false);
            if (throwable != null) {
                Throwable cause = throwable instanceof java.util.concurrent.CompletionException
                        && throwable.getCause() != null ? throwable.getCause() : throwable;
                LOG.warning("Failed to install Node.js", cause);
                Controllers.dialog(cause.getMessage(), i18n("dsh.node.install_failed"), MessageType.ERROR);
            }
            refresh();
        }));
    }

    /// Adopts a Node.js installation the user already has.
    ///
    /// The chosen directory is copied into the managed runtimes so the instance
    /// pinning it does not depend on where it happened to live, which is what
    /// HMCL's add-Java does with a chosen home.
    private void addLocal() {
        javafx.stage.DirectoryChooser chooser = new javafx.stage.DirectoryChooser();
        chooser.setTitle(i18n("dsh.node.add"));
        Path chosen = Controllers.showDialog(chooser);
        if (chosen == null) {
            return;
        }

        String version = NodeRuntimeManager.versionOfDirectory(chosen);
        if (version == null) {
            Controllers.dialog(i18n("dsh.node.add.not_node"), i18n("dsh.node.add"), MessageType.ERROR);
            return;
        }

        setLoading(true);
        CompletableFuture.runAsync(() -> {
            try {
                NodeRuntimeManager.adopt(chosen, version);
            } catch (DshException e) {
                throw new java.util.concurrent.CompletionException(e);
            }
        }, Schedulers.io()).whenComplete((ignored, throwable) -> runInFX(() -> {
            setLoading(false);
            if (throwable != null) {
                Throwable cause = throwable instanceof java.util.concurrent.CompletionException
                        && throwable.getCause() != null ? throwable.getCause() : throwable;
                Controllers.dialog(cause.getMessage(), i18n("dsh.node.add"), MessageType.ERROR);
            }
            refresh();
        }));
    }

    /// Removes an installed runtime after confirmation.
    ///
    /// @param version the version to remove
    private void uninstall(String version) {
        Controllers.confirm(i18n("dsh.node.uninstall.confirm", version),
                i18n("dsh.node.uninstall"),
                () -> {
                    try {
                        NodeRuntimeManager.uninstall(version);
                    } catch (DshException e) {
                        Controllers.dialog(e.getMessage(), i18n("message.error"), MessageType.ERROR);
                    }
                    refresh();
                },
                null);
    }

    /// One row of the runtime list.
    ///
    /// @param title      the primary line
    /// @param subtitle   the secondary line
    /// @param badge      the number shown in the version chip
    /// @param tags       the chips shown after the title
    /// @param directory  the runtime directory, or `null` when there is none
    /// @param removable  the version to remove, or `null` when it cannot be
    record NodeRow(String title,
                   String subtitle,
                   String badge,
                   @org.jetbrains.annotations.Unmodifiable List<String> tags,
                   @Nullable Path directory,
                   @Nullable String removable) {

        /// Builds the row for an installed runtime.
        ///
        /// @param runtime the runtime
        /// @return the row
        static NodeRow installed(NodeRuntime runtime) {
            String major = runtime.version().split("\\.")[0];
            return new NodeRow("Node.js " + runtime.version(),
                    runtime.node().toString(),
                    major,
                    List.of(i18n("dsh.node.tag.managed")),
                    runtime.directory(),
                    runtime.version());
        }

        /// Builds the row for the runtime on `PATH`.
        ///
        /// @param runtime the detected runtime
        /// @return the row
        static NodeRow system(DshNodeRuntime runtime) {
            String version = runtime.nodeVersion();
            return new NodeRow("Node.js " + version,
                    runtime.node().toString(),
                    version.split("\\.")[0],
                    List.of(i18n("dsh.node.tag.system"),
                            runtime.isNodeSupported() ? i18n("dsh.node.tag.supported")
                                    : i18n("dsh.node.tag.unsupported")),
                    runtime.binDirectory(),
                    null);
        }

        /// Builds the row shown when no runtime is on `PATH`.
        ///
        /// @return the row
        static NodeRow systemMissing() {
            return new NodeRow(i18n("dsh.node.system.missing.title"),
                    DshNodeRuntime.requirement(),
                    "-",
                    List.of(i18n("dsh.node.tag.missing")),
                    null,
                    null);
        }
    }

    /// The page's skin: a toolbar above the runtime list.
    private static final class NodeRuntimesPageSkin extends ToolbarListPageSkin<NodeRow, NodeRuntimesPage> {
        /// Creates the skin.
        ///
        /// @param control the page
        NodeRuntimesPageSkin(NodeRuntimesPage control) {
            super(control);
        }

        @Override
        protected List<Node> initializeToolbar(NodeRuntimesPage page) {
            List<Node> toolbar = new ArrayList<>();
            toolbar.add(createToolbarButton2(i18n("button.refresh"), SVG.REFRESH, page::refresh));
            toolbar.add(createToolbarButton2(i18n("dsh.node.download"), SVG.DOWNLOAD,
                    () -> Controllers.dialog(new NodeDownloadDialog(page::refresh))));
            toolbar.add(createToolbarButton2(i18n("dsh.node.add"), SVG.ADD, page::addLocal));
            return toolbar;
        }

        @Override
        protected ListCell<NodeRow> createListCell(JFXListView<NodeRow> listView) {
            return new NodeItemCell(listView, getSkinnable());
        }
    }

    /// One row of the runtime list.
    ///
    /// The shape follows HMCL's Java rows: a version chip, the two-line label,
    /// and the per-row actions on the trailing edge.
    private static final class NodeItemCell extends ListCell<NodeRow> {
        /// The version chip.
        private final Label badge = new Label();

        /// The name, path and tags.
        private final TwoLineListItem content = new TwoLineListItem();

        /// The button that reveals the runtime directory.
        private final JFXButton reveal = FXUtils.newToggleButton4(SVG.FOLDER_OPEN);

        /// The button that installs or removes.
        private final JFXButton action = FXUtils.newToggleButton4(SVG.DOWNLOAD);

        /// The page whose actions the row's buttons run.
        private final NodeRuntimesPage page;

        /// The row's graphic, built once and re-installed by [updateItem].
        ///
        /// A `ListCell` is recycled by the virtual flow, so the graphic has to
        /// be set again for every non-empty item: clearing it for an empty cell
        /// otherwise leaves every later row blank.
        private final org.jackhuang.hmcl.ui.construct.RipplerContainer graphic;

        /// Creates the cell.
        ///
        /// @param listView the owning list, used for cell width
        /// @param page     the page the row's actions belong to
        NodeItemCell(JFXListView<NodeRow> listView, NodeRuntimesPage page) {
            this.page = page;
            BorderPane root = new BorderPane();
            root.getStyleClass().add("md-list-cell");
            root.setPadding(new javafx.geometry.Insets(8));

            badge.setAlignment(javafx.geometry.Pos.CENTER);
            FXUtils.setLimitWidth(badge, 32);
            FXUtils.setLimitHeight(badge, 32);
            badge.setStyle("-fx-background-color: -monet-secondary-container;"
                    + " -fx-background-radius: 2; -fx-padding: 2;"
                    + " -fx-font-weight: normal; -fx-font-size: 16px;");

            HBox center = new HBox(8);
            center.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
            center.setMouseTransparent(true);
            HBox.setHgrow(content, Priority.ALWAYS);
            center.getChildren().setAll(badge, content);
            root.setCenter(center);

            HBox right = new HBox();
            right.setAlignment(javafx.geometry.Pos.CENTER_RIGHT);
            FXUtils.installFastTooltip(reveal, i18n("reveal.in_file_manager"));
            reveal.setOnAction(event -> {
                NodeRow row = getItem();
                if (row != null && row.directory() != null) {
                    FXUtils.showFileInExplorer(row.directory());
                }
            });
            right.getChildren().setAll(reveal, action);
            root.setRight(right);

            this.graphic = new org.jackhuang.hmcl.ui.construct.RipplerContainer(root);
            setGraphic(graphic);
            FXUtils.limitCellWidth(listView, this);
        }

        @Override
        protected void updateItem(@Nullable NodeRow row, boolean empty) {
            super.updateItem(row, empty);

            if (empty || row == null) {
                setGraphic(null);
                return;
            }

            setGraphic(graphic);
            badge.setText(row.badge());
            content.setTitle(row.title());
            content.setSubtitle(row.subtitle());
            content.getTags().clear();
            content.addTags(row.tags());

            boolean removable = row.removable() != null;

            reveal.setDisable(row.directory() == null);
            reveal.setVisible(row.directory() != null);
            reveal.setManaged(row.directory() != null);

            action.setVisible(removable);
            action.setManaged(removable);
            // Twenty-four in a pane of the same size, as the original's remove
            // button is; the icon was a fifth smaller than its counterpart's.
            javafx.scene.layout.StackPane removeIconPane = new javafx.scene.layout.StackPane();
            removeIconPane.setAlignment(javafx.geometry.Pos.CENTER);
            FXUtils.setLimitWidth(removeIconPane, 24);
            FXUtils.setLimitHeight(removeIconPane, 24);
            removeIconPane.getChildren().setAll(SVG.DELETE.createIcon(24));
            action.setGraphic(removeIconPane);
            FXUtils.installFastTooltip(action, i18n("dsh.node.uninstall"));

            action.setOnAction(event -> {
                if (removable) {
                    page.uninstall(row.removable());
                }
            });
        }
    }
}
