package io.github.delokoseni.controller;

import io.github.delokoseni.model.DeviceIdentification;
import io.github.delokoseni.model.DiskInfo;
import io.github.delokoseni.model.NtfsVolumeData;
import io.github.delokoseni.model.VolumeInspection;
import io.github.delokoseni.service.diagnostics.DiagnosticFinding;
import io.github.delokoseni.service.diagnostics.DiagnosticService;
import io.github.delokoseni.service.disk.DiskAnalysis;
import io.github.delokoseni.service.disk.DiskInventory;
import io.github.delokoseni.service.disk.DiskInventoryService;
import io.github.delokoseni.service.disk.RawDiskAccessStatus;
import io.github.delokoseni.service.parser.PartitionRecord;
import io.github.delokoseni.service.volume.ExternalVolumeAnalysis;
import io.github.delokoseni.service.volume.ExternalVolumeCandidate;
import io.github.delokoseni.service.volume.WindowsVolumeService;
import io.github.delokoseni.util.CapacityFormatter;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.beans.property.ReadOnlyStringWrapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.HashSet;
import java.util.Set;

public final class MainController {
    private final DiskInventoryService inventoryService = new DiskInventoryService();
    private final WindowsVolumeService volumeService = new WindowsVolumeService();
    private DiskInventory inventory = new DiskInventory(
            List.of(), List.of(), List.of(), java.util.Optional.empty(), "",
            new DeviceIdentification(DeviceIdentification.Status.TARGET_VOLUME_NOT_FOUND,
                    java.util.Optional.empty(), "D: has not been identified."));
    private Label diskInformation;
    private Label inventoryStatus;
    private ListView<DiskAnalysis> disks;
    private TableView<PartitionRow> partitions;
    private TextArea selectedPartitionInformation;
    private ListView<VolumeInspection> volumes;
    private TextArea volumeInformation;
    private TableView<DiagnosticFinding> diagnosticTable;
    private Button refreshButton;
    private Button analyzeExternalButton;
    private ProgressIndicator progress;
    private TextArea ntfsInformation;
    private final Set<Integer> diskInspectionsInProgress = new HashSet<>();
    private ExternalVolumeCandidate confirmedCandidate;

    public Parent createView() {
        BorderPane root = new BorderPane();
        root.setPadding(new Insets(16));
        root.setStyle("-fx-background-color: #f3f6fa; -fx-font-family: 'Segoe UI';");

        Label title = new Label("File System Organization");
        title.setStyle("-fx-font-size: 23px; -fx-font-weight: bold; -fx-text-fill: #14213d;");
        Label readOnly = new Label("READ-ONLY");
        readOnly.setStyle("-fx-background-color: #e6f4ea; -fx-text-fill: #176b37; "
                + "-fx-font-weight: bold; -fx-padding: 7 12; -fx-background-radius: 14;");
        refreshButton = new Button("Refresh disks and volumes");
        refreshButton.setOnAction(event -> refreshInventory());
        progress = new ProgressIndicator();
        progress.setMaxSize(24, 24);
        progress.setVisible(false);
        HBox header = new HBox(14, title, readOnly, refreshButton, progress);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(0, 0, 14, 0));
        root.setTop(header);

        disks = new ListView<>();
        disks.setPlaceholder(new Label("Refresh to enumerate disks"));
        disks.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(DiskAnalysis item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else {
                    DiskInfo disk = item.disk();
                    String model = disk.model().isBlank() ? "Model unavailable" : disk.model();
                    boolean preferredTestDevice = inventory.adataCandidate().filter(candidate ->
                            candidate.physicalDiskNumber() == disk.diskNumber()).isPresent()
                            || inventory.adataIdentification().disk()
                            .filter(identified -> identified.diskNumber() == disk.diskNumber()).isPresent();
                    String category = item.volumes().stream().anyMatch(MainController::isSystemVolume)
                            ? "Windows system disk"
                            : preferredTestDevice
                            ? "ADATA SE760 test device"
                            : disk.removable() ? "Removable media" : "Physical disk";
                    setText("Disk " + disk.diskNumber() + "  |  " + model + "\n"
                            + capacityText(disk.capacityBytes()) + "  |  "
                            + disk.partitionStyle() + "  |  " + category + "\n"
                            + item.accessStatus().displayName());
                    if ("ADATA SE760 test device".equals(category)) {
                        setStyle("-fx-background-color: #e6f4ea; -fx-font-weight: bold;");
                    } else {
                        setStyle("");
                    }
                }
            }
        });
        VBox diskPanel = new VBox(10, sectionTitle("Physical disks"), disks);
        diskPanel.setPadding(new Insets(12));
        diskPanel.setPrefWidth(260);
        diskPanel.setStyle(panelStyle());
        VBox.setVgrow(disks, Priority.ALWAYS);
        root.setLeft(diskPanel);

        inventoryStatus = new Label("Refresh to enumerate physical disk interfaces and volumes independently.");
        inventoryStatus.setWrapText(true);
        inventoryStatus.setStyle("-fx-text-fill: #475467; -fx-background-color: white; "
                + "-fx-padding: 10; -fx-background-radius: 6;");
        diskInformation = new Label("No disk selected.");
        diskInformation.setWrapText(true);
        diskInformation.setStyle("-fx-font-family: 'Consolas'; -fx-background-color: white; "
                + "-fx-padding: 10; -fx-background-radius: 6;");

        partitions = createPartitionTable();
        selectedPartitionInformation = new TextArea("Select a partition to view its structure.");
        selectedPartitionInformation.setEditable(false);
        selectedPartitionInformation.setWrapText(true);
        selectedPartitionInformation.setStyle("-fx-font-family: 'Consolas';");
        volumes = createVolumeList();
        volumeInformation = new TextArea("No volume selected.");
        volumeInformation.setEditable(false);
        volumeInformation.setWrapText(true);
        volumeInformation.setStyle("-fx-font-family: 'Consolas';");
        diagnosticTable = createDiagnosticTable();
        diagnosticTable.getItems().add(new DiagnosticFinding(
                io.github.delokoseni.service.diagnostics.DiagnosticSeverity.INFORMATION,
                "ADATA SE760 (D:)",
                "NTFS analysis has not been confirmed or run. Use the read-only analysis action "
                        + "to verify D: and explicitly confirm before any NTFS-specific query."));

        analyzeExternalButton = new Button("Analyze ADATA SE760 (D:)");
        analyzeExternalButton.setDisable(true);
        analyzeExternalButton.setTooltip(new Tooltip("Refresh disks and volumes to verify D: before enabling."));
        analyzeExternalButton.setOnAction(event -> verifyThenAnalyze(analyzeExternalButton));
        VBox diskTab = new VBox(10, diskInformation, sectionTitle("Partition table"), partitions);
        diskTab.setPadding(new Insets(12));
        VBox.setVgrow(partitions, Priority.ALWAYS);
        VBox selectedTab = new VBox(8, selectedPartitionInformation);
        selectedTab.setPadding(new Insets(12));
        VBox.setVgrow(selectedPartitionInformation, Priority.ALWAYS);
        VBox fileSystemTab = new VBox(8, volumes, volumeInformation);
        fileSystemTab.setPadding(new Insets(12));
        VBox.setVgrow(volumes, Priority.ALWAYS);
        VBox.setVgrow(volumeInformation, Priority.ALWAYS);
        VBox diagnosticsTab = new VBox(8, new Label("Findings are diagnostic only; no repair or fix actions exist."),
                diagnosticTable);
        diagnosticsTab.setPadding(new Insets(12));
        VBox.setVgrow(diagnosticTable, Priority.ALWAYS);
        ntfsInformation = new TextArea("NTFS analysis has not been run. Select "
                + "\"Analyze ADATA SE760 (D:)\" above to re-verify the device and volume; "
                + "Windows will be queried for NTFS-only data only after you confirm.");
        ntfsInformation.setEditable(false);
        ntfsInformation.setWrapText(true);
        ntfsInformation.setStyle("-fx-font-family: 'Consolas';");
        VBox ntfsTab = new VBox(8, analyzeExternalButton, ntfsInformation);
        ntfsTab.setPadding(new Insets(12));
        VBox.setVgrow(ntfsInformation, Priority.ALWAYS);

        TabPane tabs = new TabPane(
                tab("Disk and partition table", diskTab),
                tab("Selected partition", selectedTab),
                tab("Filesystem", fileSystemTab),
                tab("NTFS and MFT", ntfsTab),
                tab("Validation and diagnostics", diagnosticsTab));
        VBox content = new VBox(10, inventoryStatus, tabs);
        content.setPadding(new Insets(0, 0, 0, 14));
        VBox.setVgrow(tabs, Priority.ALWAYS);
        root.setCenter(content);

        disks.getSelectionModel().selectedItemProperty().addListener((observable, oldValue, selected) ->
                showDisk(selected));
        partitions.getSelectionModel().selectedItemProperty().addListener((observable, oldValue, selected) -> {
            selectedPartitionInformation.setText(selected == null
                    ? "Select a partition to view its structure." : selected.details());
        });
        volumes.getSelectionModel().selectedItemProperty().addListener((observable, oldValue, selected) -> {
            volumeInformation.setText(selected == null ? "No volume selected." : describeVolume(selected));
        });
        return root;
    }

    private void refreshInventory() {
        refreshButton.setDisable(true);
        analyzeExternalButton.setDisable(true);
        progress.setVisible(true);
        inventoryStatus.setText("Enumerating volumes and inspecting mapped disks in a background task...");
        Task<DiskInventory> task = new Task<>() {
            @Override
            protected DiskInventory call() throws Exception {
                return inventoryService.inspect();
            }
        };
        task.setOnSucceeded(event -> {
            inventory = task.getValue();
            confirmedCandidate = null;
            disks.getSelectionModel().clearSelection();
            disks.getItems().setAll(inventory.disks());
            volumes.getItems().setAll(inventory.volumes());
            diagnosticTable.getItems().setAll(combineDiagnostics(inventory));
            analyzeExternalButton.setDisable(inventory.adataCandidate().isEmpty());
            analyzeExternalButton.setTooltip(new Tooltip(inventory.adataCandidate().isPresent()
                    ? "D: is mapped to " + inventory.adataCandidate().orElseThrow().model()
                    + " on physical disk " + inventory.adataCandidate().orElseThrow().physicalDiskNumber()
                    + ". The workflow performs fresh checks and still requires confirmation."
                    : inventory.adataEligibilityMessage()));
            inventoryStatus.setText("Discovered " + inventory.disks().size() + " physical disks and "
                    + inventory.volumes().size() + " volumes independently. Raw analysis runs only for a "
                    + "selected disk; access failures do not remove it. "
                    + (inventory.adataCandidate().isPresent()
                    ? "ADATA D: is verified; explicit confirmation is still required."
                    : inventory.adataIdentification().status() == DeviceIdentification.Status.IDENTIFIED
                    ? "ADATA SE760 was identified from its PnP model and D: volume mapping, but raw read "
                    + "verification is unavailable: " + inventory.adataEligibilityMessage()
                    : "ADATA action disabled: " + inventory.adataEligibilityMessage()));
            refreshButton.setDisable(false);
            progress.setVisible(false);
            if (!disks.getItems().isEmpty()) {
                disks.getSelectionModel().clearSelection();
                diskInformation.setText("Select any discovered physical disk to request its read-only "
                        + "partition analysis. No disk is selected automatically.");
            } else {
                showDisk(null);
            }
        });
        task.setOnFailed(event -> {
            inventoryStatus.setText("Inventory refresh failed: " + errorText(task.getException()));
            analyzeExternalButton.setDisable(true);
            refreshButton.setDisable(false);
            progress.setVisible(false);
        });
        startWorker(task, "disk-inventory");
    }

    private void showDisk(DiskAnalysis analysis) {
        if (analysis == null) {
            diskInformation.setText("No disk selected.");
            partitions.getItems().clear();
            selectedPartitionInformation.setText("Select a partition to view its structure.");
            return;
        }
        selectedPartitionInformation.setText("Select a partition to view its structure.");
        DiskInfo disk = analysis.disk();
        diskInformation.setText("Physical disk " + disk.diskNumber() + "  |  "
                + (disk.model().isBlank() ? "Model unavailable" : disk.model())
                + "\nDevice: " + disk.devicePath()
                + "\nVendor: " + fallback(disk.vendor()) + "   Serial: " + fallback(disk.serialNumber())
                + "\nCapacity: " + capacityText(disk.capacityBytes())
                + "\nLogical sector: " + (disk.logicalSectorSize() > 0
                ? disk.logicalSectorSize() + " bytes" : "unavailable")
                + "   Physical sector: "
                + (disk.physicalSectorSize() > 0 ? disk.physicalSectorSize() + " bytes" : "unavailable")
                + "\nPartition style: " + disk.partitionStyle()
                + "\nRead access: " + analysis.accessStatus().displayName()
                + (analysis.accessMessage().isBlank() ? "" : "\n" + analysis.accessMessage())
                + "\nAssociated volumes: " + (analysis.volumes().isEmpty() ? "none mapped"
                : analysis.volumes().stream().map(v -> v.volume().volumeGuidPath()
                + (v.volume().mountPoints().isEmpty() ? "" : " (" + String.join(", ", v.volume().mountPoints()) + ")"))
                .reduce((first, second) -> first + ", " + second).orElse("none mapped")));
        partitions.getItems().setAll(partitionRows(analysis));
        if (analysis.accessStatus() == RawDiskAccessStatus.DISCOVERED
                && diskInspectionsInProgress.add(disk.diskNumber())) {
            inspectSelectedDisk(disk.diskNumber());
        }
    }

    private void inspectSelectedDisk(int diskNumber) {
        inventoryStatus.setText("Reading selected physical disk " + diskNumber
                + " with read-only access. Other discovered disks remain available.");
        progress.setVisible(true);
        refreshButton.setDisable(true);
        disks.setDisable(true);
        analyzeExternalButton.setDisable(true);
        Task<DiskAnalysis> task = new Task<>() {
            @Override
            protected DiskAnalysis call() {
                return inventoryService.inspectDisk(diskNumber, inventory);
            }
        };
        task.setOnSucceeded(event -> {
            diskInspectionsInProgress.remove(diskNumber);
            DiskAnalysis result = task.getValue();
            List<DiskAnalysis> updated = inventory.disks().stream()
                    .map(disk -> disk.disk().diskNumber() == diskNumber ? result : disk)
                    .toList();
            inventory = new DiskInventory(updated, inventory.volumes(), inventory.diagnostics(),
                    inventory.adataCandidate(), inventory.adataEligibilityMessage(),
                    inventory.adataIdentification());
            disks.getItems().setAll(updated);
            DiskAnalysis current = updated.stream()
                    .filter(disk -> disk.disk().diskNumber() == diskNumber).findFirst().orElseThrow();
            disks.getSelectionModel().select(current);
            diagnosticTable.getItems().setAll(combineDiagnostics(inventory));
            progress.setVisible(false);
            refreshButton.setDisable(false);
            disks.setDisable(false);
            analyzeExternalButton.setDisable(inventory.adataCandidate().isEmpty());
            inventoryStatus.setText("Selected disk " + diskNumber + ": "
                    + current.accessStatus().displayName()
                    + (current.accessMessage().isBlank() ? "" : " — " + current.accessMessage()));
        });
        task.setOnFailed(event -> {
            diskInspectionsInProgress.remove(diskNumber);
            progress.setVisible(false);
            refreshButton.setDisable(false);
            disks.setDisable(false);
            analyzeExternalButton.setDisable(inventory.adataCandidate().isEmpty());
            inventoryStatus.setText("Selected disk inspection failed: " + errorText(task.getException()));
        });
        startWorker(task, "physical-disk-" + diskNumber + "-inspection");
    }

    private List<DiagnosticFinding> combineDiagnostics(DiskInventory inventory) {
        List<DiagnosticFinding> findings = new ArrayList<>(inventory.diagnostics());
        inventory.disks().forEach(disk -> findings.addAll(disk.diagnostics()));
        if (confirmedCandidate == null) {
            findings.add(new DiagnosticFinding(
                    io.github.delokoseni.service.diagnostics.DiagnosticSeverity.INFORMATION,
                    "ADATA SE760 (D:)",
                    "NTFS analysis has not been confirmed or run. Use the read-only analysis action "
                            + "to verify D: and explicitly confirm before any NTFS-specific query."));
        }
        return findings;
    }

    private List<PartitionRow> partitionRows(DiskAnalysis analysis) {
        if (analysis.partitionTable().isEmpty()) {
            return List.of();
        }
        List<PartitionRecord> records = analysis.partitionTable().orElseThrow().partitions().stream()
                .sorted(Comparator.comparingLong(PartitionRecord::startingOffsetBytes)).toList();
        List<PartitionRow> rows = new ArrayList<>();
        for (int i = 0; i < records.size(); i++) {
            PartitionRecord record = records.get(i);
            String mount = volumeMountsFor(analysis, record);
            rows.add(new PartitionRow(partitionNumber(record, i + 1), mount,
                    volumeFileSystemFor(analysis, record), partitionType(record),
                    Long.toString(record.startingOffsetBytes()),
                    CapacityFormatter.format(record.lengthBytes()),
                    Long.toString(record.firstLba()), Long.toString(record.lastLbaInclusive()),
                    partitionDetails(record)));
        }
        return rows;
    }

    private static String volumeMountsFor(DiskAnalysis analysis, PartitionRecord partition) {
        List<VolumeInspection> matchingVolumes = matchingVolumes(analysis, partition);
        List<String> mounts = matchingVolumes.stream()
                .flatMap(volume -> volume.volume().mountPoints().stream())
                .distinct()
                .toList();
        return mounts.isEmpty() ? "" : String.join(", ", mounts);
    }

    private static List<VolumeInspection> matchingVolumes(DiskAnalysis analysis, PartitionRecord partition) {
        long start = partition.startingOffsetBytes();
        long end;
        try {
            end = Math.addExact(start, partition.lengthBytes());
        } catch (ArithmeticException e) {
            return List.of();
        }
        List<VolumeInspection> matches = new ArrayList<>();
        for (VolumeInspection volume : analysis.volumes()) {
            boolean contained = volume.diskExtents().stream().anyMatch(extent -> {
                if (extent.diskNumber() != analysis.disk().diskNumber()) {
                    return false;
                }
                try {
                    long extentEnd = Math.addExact(extent.startingOffsetBytes(), extent.extentLengthBytes());
                    return extent.startingOffsetBytes() >= start && extentEnd <= end;
                } catch (ArithmeticException ignored) {
                    return false;
                }
            });
            if (contained) {
                matches.add(volume);
            }
        }
        return List.copyOf(matches);
    }

    private static String volumeFileSystemFor(DiskAnalysis analysis, PartitionRecord partition) {
        return matchingVolumes(analysis, partition).stream()
                .map(volume -> volume.volume().fileSystem())
                .filter(value -> !value.isBlank())
                .distinct()
                .reduce((first, second) -> first + ", " + second)
                .orElse("");
    }

    private static String partitionNumber(PartitionRecord partition, int fallback) {
        String source = partition.source();
        String digits = source.replaceAll("\\D+", "");
        return digits.isEmpty() ? Integer.toString(fallback) : digits;
    }

    private static String partitionType(PartitionRecord partition) {
        return !partition.typeGuid().isBlank()
                ? partition.typeGuid() : String.format("MBR 0x%02X", partition.mbrType());
    }

    private static String partitionDetails(PartitionRecord p) {
        return "Source: " + p.source()
                + "\nPartition type: " + partitionType(p)
                + "\nPartition GUID: " + fallback(p.typeGuid())
                + "\nUnique partition GUID: " + fallback(p.uniqueGuid())
                + "\nGPT attributes: 0x" + Long.toUnsignedString(p.attributes(), 16)
                + "\nMBR partition type: " + String.format("0x%02X", p.mbrType())
                + "\nBoot indicator: " + (p.typeGuid().isBlank()
                ? (p.bootable() ? "bootable" : "not bootable") : "not applicable (GPT)")
                + "\nStarting LBA: " + p.firstLba()
                + "\nEnding LBA (inclusive): " + p.lastLbaInclusive()
                + "\nStarting byte offset: " + p.startingOffsetBytes()
                + "\nPartition size: " + p.lengthBytes()
                + "\nName: " + fallback(p.name());
    }

    private void verifyThenAnalyze(Button action) {
        action.setDisable(true);
        progress.setVisible(true);
        inventoryStatus.setText("Verifying D: volume mapping, disk model, capacity, and partition boundaries...");
        Task<ExternalVolumeCandidate> verification = new Task<>() {
            @Override
            protected ExternalVolumeCandidate call() throws Exception {
                return volumeService.verifyExternalAdataD();
            }
        };
        verification.setOnSucceeded(event -> {
            ExternalVolumeCandidate candidate = verification.getValue();
            progress.setVisible(false);
            if (confirmedCandidate != null && sameConfirmedCandidate(confirmedCandidate, candidate)) {
                inspectConfirmedCandidate(candidate, action);
                return;
            }
            confirmedCandidate = null;
            Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION);
            confirmation.setTitle("Confirm read-only ADATA inspection");
            confirmation.setHeaderText("Verified " + candidate.model()
                    + " on physical disk " + candidate.physicalDiskNumber());
            confirmation.setContentText(describeCandidate(candidate)
                    + "\n\nContinue with read-only filesystem/NTFS parameter inspection?");
            confirmation.getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);
            boolean confirmed = confirmation.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK;
            if (confirmed) {
                confirmedCandidate = candidate;
                diagnosticTable.getItems().removeIf(finding ->
                        finding.scope().equals("ADATA SE760 (D:)")
                                && finding.severity()
                                == io.github.delokoseni.service.diagnostics.DiagnosticSeverity.INFORMATION);
                inspectConfirmedCandidate(candidate, action);
            } else {
                inventoryStatus.setText("ADATA inspection cancelled; no NTFS query was issued.");
                confirmedCandidate = null;
                diagnosticTable.getItems().removeIf(finding ->
                        finding.scope().equals("ADATA SE760 (D:)")
                                && finding.severity()
                                == io.github.delokoseni.service.diagnostics.DiagnosticSeverity.INFORMATION);
                diagnosticTable.getItems().add(new DiagnosticFinding(
                        io.github.delokoseni.service.diagnostics.DiagnosticSeverity.INFORMATION,
                        "ADATA SE760 (D:)", "Analysis was not confirmed; no NTFS query was issued."));
                action.setDisable(false);
            }
        });
        verification.setOnFailed(event -> {
            confirmedCandidate = null;
            inventoryStatus.setText("ADATA verification failed; no volume analysis was started: "
                    + errorText(verification.getException()));
            diagnosticTable.getItems().removeIf(finding ->
                    finding.scope().equals("ADATA SE760 (D:)")
                            && finding.severity()
                            == io.github.delokoseni.service.diagnostics.DiagnosticSeverity.INFORMATION);
            diagnosticTable.getItems().add(new DiagnosticFinding(
                    io.github.delokoseni.service.diagnostics.DiagnosticSeverity.ERROR,
                    "ADATA SE760 (D:) verification", errorText(verification.getException())));
            diagnosticTable.getItems().add(new DiagnosticFinding(
                    io.github.delokoseni.service.diagnostics.DiagnosticSeverity.INFORMATION,
                    "ADATA SE760 (D:)",
                    "Analysis has not been confirmed or run. Reconnect/verify D: before trying again."));
            action.setDisable(inventory.adataCandidate().isEmpty());
            progress.setVisible(false);
        });
        startWorker(verification, "adata-device-verification");
    }

    private void inspectConfirmedCandidate(ExternalVolumeCandidate candidate, Button action) {
        progress.setVisible(true);
        inventoryStatus.setText("Revalidating D: and reading confirmed filesystem parameters...");
        Task<ExternalVolumeAnalysis> analysis = new Task<>() {
            @Override
            protected ExternalVolumeAnalysis call() throws Exception {
                return volumeService.inspectVerifiedAdataD(candidate, true);
            }
        };
        analysis.setOnSucceeded(event -> {
            ExternalVolumeAnalysis result = analysis.getValue();
            inventoryStatus.setText("Read-only analysis completed for " + result.candidate().model()
                    + " on physical disk " + result.candidate().physicalDiskNumber() + ".");
            action.setDisable(false);
            progress.setVisible(false);
            diagnosticTable.getItems().addAll(new DiagnosticService().classify(
                    "ADATA SE760 (D:)", result.diagnostics()));
            showAnalysisResult(result);
        });
        analysis.setOnFailed(event -> {
            inventoryStatus.setText("ADATA analysis stopped safely: " + errorText(analysis.getException()));
            DiagnosticFinding finding = new DiagnosticService().classify(
                    "ADATA SE760 (D:) analysis", List.of(errorText(analysis.getException()))).getFirst();
            diagnosticTable.getItems().add(finding);
            action.setDisable(inventory.adataCandidate().isEmpty());
            progress.setVisible(false);
        });
        startWorker(analysis, "adata-volume-analysis");
    }

    private void showAnalysisResult(ExternalVolumeAnalysis result) {
        Alert report = new Alert(Alert.AlertType.INFORMATION);
        report.setTitle("Read-only ADATA analysis");
        report.setHeaderText("Verified " + result.candidate().model()
                + " - disk " + result.candidate().physicalDiskNumber());
        String text = describeCandidate(result.candidate());
        String fileSystem = result.candidate().volumeInspection().volume().fileSystem();
        if (result.ntfsData().isEmpty()) {
            text += "\n\nNTFS-specific information is unavailable because the verified volume "
                    + "filesystem is " + (fileSystem.isBlank() ? "unknown" : fileSystem) + ".";
        }
        if (result.ntfsData().isPresent()) {
            NtfsVolumeData ntfs = result.ntfsData().orElseThrow();
            var metrics = result.ntfsMetrics().orElseThrow();
            text += "\n\nNTFS volume serial: " + Long.toUnsignedString(ntfs.volumeSerialNumber())
                    + "\nNumber of sectors: " + ntfs.numberOfSectors()
                    + "\nTotal clusters: " + ntfs.totalClusters()
                    + "\nFree clusters: " + ntfs.freeClusters()
                    + "\nReserved clusters: " + ntfs.reservedClusters()
                    + "\nBytes per sector / cluster: " + ntfs.bytesPerSector() + " / " + ntfs.bytesPerCluster()
                    + "\nSectors per cluster: " + metrics.sectorsPerCluster()
                    + "\nCluster capacity: " + CapacityFormatter.format(metrics.totalClusterCapacityBytes())
                    + "\nUsed clusters (includes reserved): " + metrics.usedClusters()
                    + "\nFree cluster capacity: " + CapacityFormatter.format(metrics.freeClusterCapacityBytes())
                    + "\nMFT start LCN / volume offset: " + ntfs.mftStartLcn() + " / "
                    + metrics.mftStartOffsetWithinVolume() + " bytes"
                    + "\nMFT mirror LCN / volume offset: " + ntfs.mftMirrorStartLcn() + " / "
                    + metrics.mftMirrorStartOffsetWithinVolume() + " bytes"
                    + "\nMFT valid data length: " + ntfs.mftValidDataLength()
                    + "\nMFT record size: " + metrics.mftRecordSizeBytes()
                    + " bytes; clusters per record: " + ntfs.clustersPerFileRecordSegment()
                    + "\nMFT zone LCN range: [" + ntfs.mftZoneStartLcn() + ", "
                    + ntfs.mftZoneEndLcn() + "), size: " + CapacityFormatter.format(metrics.mftZoneSizeBytes())
                    + "\nNTFS version: not provided by FSCTL_GET_NTFS_VOLUME_DATA";
        }
        if (!result.diagnostics().isEmpty()) {
            text += "\n\nDiagnostics:\n" + String.join("\n", result.diagnostics());
        }
        ntfsInformation.setText(text);
        report.setContentText(text);
        report.showAndWait();
    }

    private static String describeCandidate(ExternalVolumeCandidate candidate) {
        VolumeInspection inspection = candidate.volumeInspection();
        var fileSystemInfo = inspection.fileSystem();
        StringBuilder text = new StringBuilder()
                .append("Drive letter: D:")
                .append("\nVolume GUID: ").append(inspection.volume().volumeGuidPath())
                .append("\nFilesystem: ").append(inspection.volume().fileSystem())
                .append("\nVolume label: ").append(fallback(inspection.volume().label()))
                .append("\nVolume serial: ").append(inspection.fileSystem().isPresent()
                        ? Long.toUnsignedString(inspection.fileSystem().orElseThrow().volumeSerialNumber())
                        : "unavailable")
                .append("\nVolume capacity: ").append(fileSystemInfo.isPresent()
                        ? CapacityFormatter.format(fileSystemInfo.orElseThrow().totalBytes()) : "unavailable")
                .append("\nAvailable free space: ").append(fileSystemInfo.isPresent()
                        ? CapacityFormatter.format(fileSystemInfo.orElseThrow().availableBytes()) : "unavailable")
                .append("\nPhysical disk: ").append(candidate.physicalDiskNumber())
                .append("\nDevice serial: ").append(candidate.serialNumber().isBlank()
                        ? "unavailable" : candidate.serialNumber())
                .append("\nCapacity: ").append(CapacityFormatter.format(candidate.diskCapacityBytes()))
                .append("\nLogical disk sector: ").append(candidate.logicalSectorSize()).append(" bytes")
                .append("\nPhysical disk sector: ")
                .append(candidate.physicalSectorSize().isPresent()
                        ? candidate.physicalSectorSize().getAsInt() + " bytes" : "unavailable");
        for (var partition : candidate.partitions()) {
            text.append("\nPartition ").append(partition.partitionNumber())
                    .append(": offset ").append(partition.startingOffsetBytes())
                    .append(" bytes, length ").append(partition.lengthBytes()).append(" bytes");
        }
        return text.toString();
    }

    private static boolean sameConfirmedCandidate(
            ExternalVolumeCandidate first, ExternalVolumeCandidate second) {
        return first.physicalDiskNumber() == second.physicalDiskNumber()
                && first.diskCapacityBytes() == second.diskCapacityBytes()
                && first.logicalSectorSize() == second.logicalSectorSize()
                && first.physicalSectorSize().equals(second.physicalSectorSize())
                && first.model().equalsIgnoreCase(second.model())
                && first.serialNumber().equalsIgnoreCase(second.serialNumber())
                && first.volumeInspection().volume().volumeGuidPath()
                .equalsIgnoreCase(second.volumeInspection().volume().volumeGuidPath())
                && first.volumeInspection().volume().mountPoints()
                .equals(second.volumeInspection().volume().mountPoints())
                && first.volumeInspection().volume().fileSystem()
                .equalsIgnoreCase(second.volumeInspection().volume().fileSystem())
                && first.volumeInspection().fileSystem().map(info -> info.volumeSerialNumber())
                .equals(second.volumeInspection().fileSystem()
                        .map(info -> info.volumeSerialNumber()))
                && first.volumeInspection().diskExtents().equals(second.volumeInspection().diskExtents())
                && first.partitions().equals(second.partitions());
    }

    private static String describeVolume(VolumeInspection inspection) {
        StringBuilder text = new StringBuilder()
                .append("Volume GUID: ").append(inspection.volume().volumeGuidPath())
                .append("\nMount points: ").append(String.join(", ", inspection.volume().mountPoints()))
                .append("\nFilesystem: ").append(inspection.volume().fileSystem())
                .append("\nLabel: ").append(inspection.volume().label());
        if (inspection.fileSystem().isPresent()) {
            var info = inspection.fileSystem().orElseThrow();
            text.append("\nVolume serial: ").append(Long.toUnsignedString(info.volumeSerialNumber()))
                    .append("\nMaximum component name length: ").append(info.maximumComponentNameLength())
                    .append("\nCapabilities: ").append(String.join(", ", info.capabilityNames()))
                    .append("\nTotal / free / available: ").append(CapacityFormatter.format(info.totalBytes()))
                    .append(" / ").append(CapacityFormatter.format(info.freeBytes()))
                    .append(" / ").append(CapacityFormatter.format(info.availableBytes()))
                    .append("\nFilesystem sector size: ").append(info.bytesPerSector()).append(" bytes")
                    .append("\nSectors per cluster: ").append(info.sectorsPerCluster())
                    .append("\nFilesystem cluster size: ").append(CapacityFormatter.format(info.clusterSizeBytes()));
        }
        for (var extent : inspection.diskExtents()) {
            text.append("\nExtent: physical disk ").append(extent.diskNumber())
                    .append(", offset ").append(extent.startingOffsetBytes())
                    .append(", length ").append(extent.extentLengthBytes()).append(" bytes");
        }
        if (!inspection.diagnostics().isEmpty()) {
            text.append("\n\nDiagnostics:\n").append(String.join("\n", inspection.diagnostics()));
        }
        text.append("\n\nCapability flags indicate filesystem support, not that every file uses a feature.");
        return text.toString();
    }

    private static TableView<PartitionRow> createPartitionTable() {
        TableView<PartitionRow> table = new TableView<>();
        table.setPlaceholder(new Label("No partition data available for this disk"));
        addPartitionColumn(table, "Number", PartitionRow::number, 65);
        addPartitionColumn(table, "Volume / mount", PartitionRow::mount, 120);
        addPartitionColumn(table, "Filesystem", PartitionRow::fileSystem, 100);
        addPartitionColumn(table, "Type", PartitionRow::type, 180);
        addPartitionColumn(table, "Start offset (bytes)", PartitionRow::startOffset, 140);
        addPartitionColumn(table, "Size", PartitionRow::size, 100);
        addPartitionColumn(table, "Starting LBA", PartitionRow::firstLba, 100);
        addPartitionColumn(table, "Ending LBA", PartitionRow::lastLba, 100);
        table.getSelectionModel().selectFirst();
        return table;
    }

    private static void addPartitionColumn(
            TableView<PartitionRow> table,
            String title,
            java.util.function.Function<PartitionRow, String> value,
            double width) {
        TableColumn<PartitionRow, String> column = new TableColumn<>(title);
        column.setCellValueFactory(cell -> new ReadOnlyStringWrapper(value.apply(cell.getValue())));
        column.setPrefWidth(width);
        table.getColumns().add(column);
    }

    private ListView<VolumeInspection> createVolumeList() {
        ListView<VolumeInspection> list = new ListView<>();
        list.setPlaceholder(new Label("Refresh to enumerate volumes"));
        list.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(VolumeInspection item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else {
                    String mounts = item.volume().mountPoints().isEmpty()
                            ? "(no mount point)" : String.join(", ", item.volume().mountPoints());
                    setText(mounts + "  |  " + item.volume().fileSystem() + "  |  "
                            + item.volume().volumeGuidPath());
                }
            }
        });
        return list;
    }

    private static TableView<DiagnosticFinding> createDiagnosticTable() {
        TableView<DiagnosticFinding> table = new TableView<>();
        table.setPlaceholder(new Label("Refresh to run validation diagnostics"));
        TableColumn<DiagnosticFinding, String> severity = diagnosticColumn(
                "Severity", finding -> finding.severity().name(), 110);
        TableColumn<DiagnosticFinding, String> scope = diagnosticColumn(
                "Scope", DiagnosticFinding::scope, 220);
        TableColumn<DiagnosticFinding, String> message = diagnosticColumn(
                "Finding", DiagnosticFinding::message, 650);
        table.getColumns().add(severity);
        table.getColumns().add(scope);
        table.getColumns().add(message);
        table.setRowFactory(view -> new javafx.scene.control.TableRow<>() {
            @Override
            protected void updateItem(DiagnosticFinding item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setStyle("");
                } else {
                    setStyle(switch (item.severity()) {
                        case ERROR -> "-fx-background-color: #fde2e2;";
                        case WARNING -> "-fx-background-color: #fff3cd;";
                        case INFORMATION -> "";
                    });
                }
            }
        });
        return table;
    }

    private static TableColumn<DiagnosticFinding, String> diagnosticColumn(
            String title, java.util.function.Function<DiagnosticFinding, String> value, double width) {
        TableColumn<DiagnosticFinding, String> column = new TableColumn<>(title);
        column.setCellValueFactory(cell -> new ReadOnlyStringWrapper(value.apply(cell.getValue())));
        column.setPrefWidth(width);
        return column;
    }

    private static Tab tab(String title, Node content) {
        Tab tab = new Tab(title, content);
        tab.setClosable(false);
        return tab;
    }

    private static VBox placeholder(String heading, String message) {
        Label title = sectionTitle(heading);
        Label text = new Label(message);
        text.setWrapText(true);
        text.setStyle("-fx-text-fill: #667085;");
        VBox box = new VBox(8, title, text);
        box.setPadding(new Insets(14));
        box.setStyle(panelStyle());
        return box;
    }

    private static Label sectionTitle(String text) {
        Label label = new Label(text);
        label.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #14213d;");
        return label;
    }

    private static String fallback(String value) {
        return value == null || value.isBlank() ? "unavailable" : value;
    }

    private static String capacityText(long capacity) {
        return capacity > 0 ? CapacityFormatter.format(capacity) : "unavailable";
    }

    private static boolean isSystemVolume(VolumeInspection volume) {
        return volume.volume().mountPoints().stream()
                .anyMatch(mount -> mount.equalsIgnoreCase("C:\\") || mount.equalsIgnoreCase("C:"));
    }

    private static String errorText(Throwable error) {
        return error == null || error.getMessage() == null ? "unknown error" : error.getMessage();
    }

    private static String panelStyle() {
        return "-fx-background-color: white; -fx-background-radius: 8;";
    }

    private static void startWorker(Task<?> task, String name) {
        Thread worker = new Thread(task, name);
        worker.setDaemon(true);
        worker.start();
    }

    private record PartitionRow(
            String number,
            String mount,
            String fileSystem,
            String type,
            String startOffset,
            String size,
            String firstLba,
            String lastLba,
            String details) {
    }
}
