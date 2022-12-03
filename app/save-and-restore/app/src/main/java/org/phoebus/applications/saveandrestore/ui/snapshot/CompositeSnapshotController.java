/*
 * Copyright (C) 2020 European Spallation Source ERIC.
 *
 *  This program is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU General Public License
 *  as published by the Free Software Foundation; either version 2
 *  of the License, or (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program; if not, write to the Free Software
 *  Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 *
 */

package org.phoebus.applications.saveandrestore.ui.snapshot;

import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.image.ImageView;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.BorderPane;
import javafx.util.Callback;
import org.phoebus.applications.saveandrestore.Messages;
import org.phoebus.applications.saveandrestore.SaveAndRestoreApplication;
import org.phoebus.applications.saveandrestore.model.CompositeSnapshot;
import org.phoebus.applications.saveandrestore.model.CompositeSnapshotData;
import org.phoebus.applications.saveandrestore.model.Node;
import org.phoebus.applications.saveandrestore.model.NodeType;
import org.phoebus.applications.saveandrestore.model.SnapshotData;
import org.phoebus.applications.saveandrestore.model.SnapshotItem;
import org.phoebus.applications.saveandrestore.model.Tag;
import org.phoebus.applications.saveandrestore.ui.NodeChangedListener;
import org.phoebus.applications.saveandrestore.ui.SaveAndRestoreController;
import org.phoebus.applications.saveandrestore.ui.SaveAndRestoreService;
import org.phoebus.framework.jobs.JobManager;
import org.phoebus.ui.dialog.ExceptionDetailsErrorDialog;
import org.phoebus.ui.javafx.ImageCache;
import org.phoebus.util.time.TimestampFormats;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class CompositeSnapshotController implements NodeChangedListener {

    @FXML
    private BorderPane root;

    @FXML
    private TableColumn<Node, Node> snapshotNameColumn;

    @FXML
    private TableColumn<Node, Date> snapshotDateColumn;

    @FXML
    private TableView<Node> snapshotTable;

    @FXML
    private TextArea descriptionTextArea;

    @FXML
    private Button saveButton;

    @FXML
    private TextField compositeSnapshotNameField;
    @FXML
    private Label compositeSnapshotCreatedDateField;

    @FXML
    private Label compositeSnapshotLastModifiedDateField;
    @FXML
    private Label createdByField;

    private SaveAndRestoreService saveAndRestoreService;

    private static final Executor UI_EXECUTOR = Platform::runLater;

    private final SimpleBooleanProperty dirty = new SimpleBooleanProperty(false);

    private final ObservableList<Node> snapshotEntries = FXCollections.observableArrayList();

    private final SimpleBooleanProperty selectionEmpty = new SimpleBooleanProperty(false);
    private final SimpleBooleanProperty singelSelection = new SimpleBooleanProperty(false);
    private final SimpleStringProperty compositeSnapshotDescriptionProperty = new SimpleStringProperty();
    private final SimpleStringProperty compositeSnapshotNameProperty = new SimpleStringProperty();
    private Node parentFolder;

    private final SimpleObjectProperty<Node> compositeSnapshotNode = new SimpleObjectProperty<>();

    private final CompositeSnapshotTab compositeSnapshotTab;

    private final Logger logger = Logger.getLogger(CompositeSnapshotController.class.getName());

    public CompositeSnapshotController(CompositeSnapshotTab compositeSnapshotTab) {
        this.compositeSnapshotTab = compositeSnapshotTab;
    }

    @FXML
    public void initialize() {

        saveAndRestoreService = SaveAndRestoreService.getInstance();

        snapshotTable.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        snapshotTable.getSelectionModel().selectedItemProperty().addListener((obs, ov, nv) -> selectionEmpty.set(nv == null));

        snapshotTable.getSelectionModel().getSelectedItems().addListener((ListChangeListener<Node>) c -> singelSelection.set(c.getList().size() == 1));

        ContextMenu pvNameContextMenu = new ContextMenu();

        MenuItem deleteMenuItem = new MenuItem(Messages.menuItemDeleteSelectedPVs,
                new ImageView(ImageCache.getImage(SaveAndRestoreController.class, "/icons/delete.png")));
        deleteMenuItem.setOnAction(ae -> {
            snapshotEntries.removeAll(snapshotTable.getSelectionModel().getSelectedItems());
            snapshotTable.refresh();
        });

        deleteMenuItem.disableProperty().bind(Bindings.createBooleanBinding(() -> snapshotTable.getSelectionModel().getSelectedItems().isEmpty(),
                snapshotTable.getSelectionModel().getSelectedItems()));

        snapshotDateColumn.setCellFactory(new Callback<>() {
            @Override
            public TableCell<Node, Date> call(TableColumn param) {
                final TableCell<Node, Date> cell = new TableCell<>() {
                    @Override
                    public void updateItem(Date item, boolean empty) {
                        super.updateItem(item, empty);
                        if (empty) {
                            setText("");
                        } else {
                            setText(TimestampFormats.SECONDS_FORMAT.format(getItem().toInstant()));
                        }
                    }
                };
                return cell;
            }
        });

        ContextMenu contextMenu = new ContextMenu();
        MenuItem removeMenuItem = new MenuItem("Remove Selected");
        removeMenuItem.setOnAction(event -> {
            List<Node> selected = snapshotTable.getSelectionModel().getSelectedItems();
            snapshotEntries.removeAll(selected);
        });
        contextMenu.getItems().add(removeMenuItem);

        snapshotTable.setContextMenu(contextMenu);
        snapshotTable.setOnContextMenuRequested(event -> {
            if (snapshotTable.getSelectionModel().getSelectedItems().size() == 0) {
                contextMenu.hide();
                event.consume();
            }
        });

        snapshotNameColumn.setCellValueFactory(cell -> new SimpleObjectProperty<>(cell.getValue()));
        snapshotNameColumn.setCellFactory(new Callback<>() {
            @Override
            public TableCell<Node, Node> call(TableColumn param) {
                final TableCell<Node, Node> cell = new TableCell<>() {
                    @Override
                    public void updateItem(Node item, boolean empty) {
                        super.updateItem(item, empty);
                        selectionEmpty.set(empty);
                        if (empty) {
                            setText(null);
                            setGraphic(null);
                        } else {
                            setText(getItem().getName());
                            NodeType nodeType = getItem().getNodeType();
                            boolean golden = getItem().getTags() != null &&
                                    getItem().getTags().stream().filter(t -> t.getName().equals(Tag.GOLDEN)).findFirst().isPresent();
                            if (nodeType.equals(NodeType.SNAPSHOT)) {
                                setGraphic(new ImageView(golden ?
                                        ImageCache.getImage(SnapshotTab.class, "/icons/save-and-restore/snapshot-golden.png") :
                                        ImageCache.getImage(SnapshotTab.class, "/icons/save-and-restore/snapshot.png")));
                            } else {
                                setGraphic(new ImageView(ImageCache.getImage(SnapshotTab.class, "/icons/save-and-restore/composite-snapshot.png")));
                            }
                        }
                    }
                };

                return cell;
            }
        });

        compositeSnapshotNameField.textProperty().bindBidirectional(compositeSnapshotNameProperty);
        descriptionTextArea.textProperty().bindBidirectional(compositeSnapshotDescriptionProperty);

        snapshotEntries.addListener((ListChangeListener<Node>) change -> {
            while (change.next()) {
                if (change.wasAdded() || change.wasRemoved()) {
                    FXCollections.sort(snapshotEntries);
                    dirty.set(true);
                }
            }
        });

        compositeSnapshotNameProperty.addListener((observableValue, oldValue, newValue) -> dirty.set(!newValue.equals(compositeSnapshotNode.getName())));
        compositeSnapshotDescriptionProperty.addListener((observable, oldValue, newValue) -> dirty.set(!newValue.equals(compositeSnapshotNode.get().getDescription())));

        saveButton.disableProperty().bind(Bindings.createBooleanBinding(() -> dirty.not().get() ||
                        compositeSnapshotDescriptionProperty.isEmpty().get() ||
                        compositeSnapshotNameProperty.isEmpty().get(),
                dirty, compositeSnapshotDescriptionProperty, compositeSnapshotNameProperty));

        compositeSnapshotNode.addListener((observable, oldValue, newValue) -> {
            if (newValue != null) {
                compositeSnapshotNameProperty.set(newValue.getName());
                compositeSnapshotCreatedDateField.textProperty().set(newValue.getCreated() != null ?
                        TimestampFormats.SECONDS_FORMAT.format(Instant.ofEpochMilli(newValue.getCreated().getTime())) : null);
                compositeSnapshotLastModifiedDateField.textProperty().set(newValue.getLastModified() != null ?
                        TimestampFormats.SECONDS_FORMAT.format(Instant.ofEpochMilli(newValue.getLastModified().getTime())) : null);
                createdByField.textProperty().set(newValue.getUserName());
                compositeSnapshotDescriptionProperty.set(compositeSnapshotNode.get().getDescription());
            }
        });

        snapshotTable.setOnDragOver(event -> {
            Dragboard db = event.getDragboard();
            if (event.getDragboard().hasContent(SaveAndRestoreApplication.NODE_SELECTION_FORMAT)) {
                event.acceptTransferModes(TransferMode.COPY_OR_MOVE);
            }
            event.consume();
        });

        snapshotTable.setOnDragDropped(event -> {
            List<Node> sourceNodes = (List<Node>) event.getDragboard().getContent(SaveAndRestoreApplication.NODE_SELECTION_FORMAT);
            if (mayDrop(sourceNodes)) {
                snapshotEntries.addAll(sourceNodes);
            }
        });

        SaveAndRestoreService.getInstance().addNodeChangeListener(this);
    }

    @FXML
    public void save() {
        UI_EXECUTOR.execute(() -> {
            try {
                compositeSnapshotNode.get().setName(compositeSnapshotNameProperty.get());
                compositeSnapshotNode.get().setDescription(compositeSnapshotDescriptionProperty.get());
                CompositeSnapshot compositeSnapshot = new CompositeSnapshot();
                compositeSnapshot.setCompositeSnapshotNode(compositeSnapshotNode.get());
                CompositeSnapshotData compositeSnapshotData = new CompositeSnapshotData();
                compositeSnapshotData
                        .setReferencedSnapshotNodes(snapshotEntries.stream().map(e -> e.getUniqueId()).collect(Collectors.toList()));
                compositeSnapshot.setCompositeSnapshotData(compositeSnapshotData);

                if (compositeSnapshotNode.get().getUniqueId() == null) { // New composite snapshot
                    compositeSnapshot = saveAndRestoreService.saveCompositeSnapshot(parentFolder,
                            compositeSnapshot);
                    compositeSnapshotTab.setId(compositeSnapshot.getCompositeSnapshotNode().getUniqueId());
                    compositeSnapshotTab.updateTabTitle(compositeSnapshot.getCompositeSnapshotNode().getName());
                } else {
                    compositeSnapshot = saveAndRestoreService.updateCompositeSnapshot(compositeSnapshot);
                }
                //snapshotEntries.clear();
                //snapshotEntries.addAll(compositeSnapshot.getSnapshotNodes());
                // TODO: load data, probably calling loadCompositeSnapshot(Node)
                dirty.set(false);
                loadCompositeSnapshot(compositeSnapshot.getCompositeSnapshotNode());
            } catch (Exception e1) {
                ExceptionDetailsErrorDialog.openError(snapshotTable,
                        Messages.errorActionFailed,
                        Messages.errorCreateConfigurationFailed,
                        e1);
            }
        });
    }


    /**
     * Loads an existing composite snapshot {@link Node}.
     *
     * @param node An existing {@link Node} of type {@link NodeType#COMPOSITE_SNAPSHOT}.
     */
    public void loadCompositeSnapshot(final Node node) {
        try {
            snapshotEntries.clear();
            List<Node> referencedNodes = saveAndRestoreService.getCompositeSnapshotNodes(node.getUniqueId());
            snapshotEntries.addAll(referencedNodes);
        } catch (Exception e) {
            ExceptionDetailsErrorDialog.openError(root, "Error", "Unable to retrieve configuration data", e);
            return;
        }
        // Create a cloned Node object to avoid changes in the Node object contained in the tree view.
        compositeSnapshotNode.set(Node.builder().uniqueId(node.getUniqueId())
                .name(node.getName())
                .nodeType(NodeType.COMPOSITE_SNAPSHOT)
                .description(node.getDescription())
                .userName(node.getUserName())
                .created(node.getCreated())
                .lastModified(node.getLastModified())
                .build());
        loadCompositeSnapshotData();
    }

    private void loadCompositeSnapshotData() {
        UI_EXECUTOR.execute(() -> {
            try {
                Collections.sort(snapshotEntries);
                snapshotTable.setItems(snapshotEntries);
                dirty.set(false);
            } catch (Exception e) {
                logger.log(Level.WARNING, "Unable to load existing configuration");
            }
        });
    }

    public boolean handleCompositeSnapshotTabClosed() {
        if (dirty.get()) {
            Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
            alert.setTitle(Messages.closeTabPrompt);
            alert.setContentText(Messages.closeCompositeSnapshotWarning);
            Optional<ButtonType> result = alert.showAndWait();
            return result.isPresent() && result.get().equals(ButtonType.OK);
        }
        return true;
    }

    @Override
    public void nodeChanged(Node node) {
        if (node.getUniqueId().equals(compositeSnapshotNode.get().getUniqueId())) {
            compositeSnapshotNode.setValue(Node.builder().uniqueId(node.getUniqueId())
                    .name(node.getName())
                    .nodeType(NodeType.CONFIGURATION)
                    .userName(node.getUserName())
                    .description(node.getDescription())
                    .created(node.getCreated())
                    .lastModified(node.getLastModified())
                    .build());
        }
    }

    /**
     * Configures the controller to create a new composite snapshot.
     *
     * @param parentNode The parent {@link Node} for the new composite, i.e. must be a
     *                   {@link Node} of type {@link NodeType#FOLDER}.
     */
    public void newCompositeSnapshot(Node parentNode) {
        parentFolder = parentNode;
        compositeSnapshotNode.set(Node.builder().nodeType(NodeType.COMPOSITE_SNAPSHOT).build());
        snapshotEntries.clear();
        snapshotEntries.addAll(new ArrayList<>());
        snapshotTable.setItems(snapshotEntries);
        UI_EXECUTOR.execute(() -> compositeSnapshotNameField.requestFocus());
        dirty.set(false);
    }

    private boolean mayDrop(List<Node> sourceNodes) {
        if (sourceNodes.stream().filter(n -> !n.getNodeType().equals(NodeType.SNAPSHOT) &&
                !n.getNodeType().equals(NodeType.COMPOSITE_SNAPSHOT)).findFirst().isPresent()) {
            return false;
        }
        return true;
    }

    private void checkForDuplicatePVs(List<Node> addedSnapshots, Consumer<Node> completion){
        JobManager.schedule("Check snapshot PV duplicates", monitor -> {
            // First get snapshot data for the snapshots already
            List<SnapshotItem> candidateItems = new ArrayList<>();
            for(Node node : addedSnapshots){
                SnapshotData snapshotData = saveAndRestoreService.getSnapshot(node.getUniqueId());

            }


            completion.accept(null);
        });
    }


}