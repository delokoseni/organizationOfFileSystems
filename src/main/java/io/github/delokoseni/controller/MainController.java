package io.github.delokoseni.controller;

import io.github.delokoseni.model.DiskInfo;
import io.github.delokoseni.model.NtfsVolumeData;
import io.github.delokoseni.model.VolumeInspection;
import io.github.delokoseni.service.diagnostics.DiagnosticFinding;
import io.github.delokoseni.service.diagnostics.DiagnosticCopyService;
import io.github.delokoseni.service.diagnostics.DiagnosticSelectionService;
import io.github.delokoseni.service.diagnostics.DiagnosticService;
import io.github.delokoseni.service.diagnostics.DiagnosticTextFormatter;
import io.github.delokoseni.service.disk.DiskAnalysis;
import io.github.delokoseni.service.disk.DiskInventory;
import io.github.delokoseni.service.disk.DiskInventoryService;
import io.github.delokoseni.service.disk.RawDiskAccessStatus;
import io.github.delokoseni.service.ntfs.NtfsFileRecordSegmentSize;
import io.github.delokoseni.service.parser.PartitionRecord;
import io.github.delokoseni.service.volume.VolumeAnalysis;
import io.github.delokoseni.service.volume.VolumeAnalysisCandidate;
import io.github.delokoseni.service.volume.NtfsAnalysisReadiness;
import io.github.delokoseni.service.volume.WindowsVolumeService;
import io.github.delokoseni.util.CapacityFormatter;
import io.github.delokoseni.util.UiMessages;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
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
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.stage.Screen;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public final class MainController {
    private final DiskInventoryService inventoryService = new DiskInventoryService();
    private final WindowsVolumeService volumeService = new WindowsVolumeService();
    private final DiagnosticTextFormatter diagnosticTextFormatter = new DiagnosticTextFormatter();
    private final DiagnosticSelectionService diagnosticSelectionService = new DiagnosticSelectionService();
    private DiskInventory inventory = new DiskInventory(List.of(), List.of(), List.of());
    private final Map<String, List<DiagnosticFinding>> volumeDiagnostics = new HashMap<>();
    private Label diskInformation;
    private Label inventoryStatus;
    private ListView<DiskAnalysis> disks;
    private TableView<PartitionRow> partitions;
    private TextArea selectedPartitionInformation;
    private ListView<VolumeInspection> volumes;
    private TextArea volumeInformation;
    private TableView<DiagnosticFinding> diagnosticTable;
    private Button refreshButton;
    private Button analyzeSelectedVolumeButton;
    private Button copyAllDiagnosticsButton;
    private Label ntfsAvailability;
    private ProgressIndicator progress;
    private TextArea ntfsInformation;
    private final Set<Integer> diskInspectionsInProgress = new HashSet<>();
    private VolumeAnalysisCandidate confirmedCandidate;
    private boolean ntfsOperationInProgress;
    private String ntfsFailureForSelection = "";
    private String ntfsFailedVolumeGuid = "";

    public Parent createView() {
        BorderPane root = new BorderPane();
        root.setPadding(new Insets(16));
        root.setStyle("-fx-background-color: #f3f6fa; -fx-font-family: 'Segoe UI';");

        Label title = new Label(UiMessages.text("application.title"));
        title.setStyle("-fx-font-size: 23px; -fx-font-weight: bold; -fx-text-fill: #14213d;");
        Label readOnly = new Label(UiMessages.text("application.readOnly"));
        readOnly.setStyle("-fx-background-color: #e6f4ea; -fx-text-fill: #176b37; "
                + "-fx-font-weight: bold; -fx-padding: 7 12; -fx-background-radius: 14;");
        refreshButton = new Button(UiMessages.text("application.refresh"));
        refreshButton.setOnAction(event -> refreshInventory());
        progress = new ProgressIndicator();
        progress.setMaxSize(24, 24);
        progress.setVisible(false);
        HBox header = new HBox(14, title, readOnly, refreshButton, progress);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(0, 0, 14, 0));
        root.setTop(header);

        disks = new ListView<>();
        disks.setPlaceholder(new Label(UiMessages.text("empty.disks")));
        disks.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(DiskAnalysis item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else {
                    DiskInfo disk = item.disk();
                    String model = disk.model().isBlank() ? "Модель недоступна" : disk.model();
                    String category = disk.removable() ? "Съёмный носитель" : "Физический диск";
                    setText("Диск " + disk.diskNumber() + "  |  " + model + "\n"
                            + capacityText(disk.capacityBytes()) + "  |  "
                            + disk.partitionStyle().displayName() + "  |  " + category + "\n"
                            + item.accessStatus().displayName());
                    if (disk.removable()) {
                        setStyle("-fx-background-color: #e6f4ea; -fx-font-weight: bold;");
                    } else {
                        setStyle("");
                    }
                }
            }
        });
        VBox diskPanel = new VBox(10, sectionTitle(UiMessages.text("section.physicalDisks")), disks);
        diskPanel.setPadding(new Insets(12));
        diskPanel.setPrefWidth(260);
        diskPanel.setStyle(panelStyle());
        VBox.setVgrow(disks, Priority.ALWAYS);
        root.setLeft(diskPanel);

        inventoryStatus = new Label("Обновите список, чтобы отдельно обнаружить физические диски и тома.");
        inventoryStatus.setWrapText(true);
        inventoryStatus.setStyle("-fx-text-fill: #475467; -fx-background-color: white; "
                + "-fx-padding: 10; -fx-background-radius: 6;");
        diskInformation = new Label(UiMessages.text("status.noDisk"));
        diskInformation.setWrapText(true);
        diskInformation.setStyle("-fx-font-family: 'Consolas'; -fx-background-color: white; "
                + "-fx-padding: 10; -fx-background-radius: 6;");

        partitions = createPartitionTable();
        selectedPartitionInformation = new TextArea(UiMessages.text("status.noPartition"));
        selectedPartitionInformation.setEditable(false);
        selectedPartitionInformation.setWrapText(true);
        selectedPartitionInformation.setStyle("-fx-font-family: 'Consolas';");
        volumes = createVolumeList();
        volumeInformation = new TextArea(UiMessages.text("status.noVolume"));
        volumeInformation.setEditable(false);
        volumeInformation.setWrapText(true);
        volumeInformation.setStyle("-fx-font-family: 'Consolas';");
        diagnosticTable = createDiagnosticTable();
        analyzeSelectedVolumeButton = new Button(UiMessages.text("application.selectVolume"));
        analyzeSelectedVolumeButton.setDisable(true);
        analyzeSelectedVolumeButton.setTooltip(new Tooltip("Сначала выберите том в списке."));
        analyzeSelectedVolumeButton.setOnAction(event -> verifyThenAnalyze(analyzeSelectedVolumeButton));
        VBox diskTab = new VBox(10, diskInformation, sectionTitle(UiMessages.text("section.partitionTable")), partitions);
        diskTab.setPadding(new Insets(12));
        VBox.setVgrow(partitions, Priority.ALWAYS);
        VBox selectedTab = new VBox(8, selectedPartitionInformation);
        selectedTab.setPadding(new Insets(12));
        VBox.setVgrow(selectedPartitionInformation, Priority.ALWAYS);
        VBox fileSystemTab = new VBox(8, volumes, volumeInformation);
        fileSystemTab.setPadding(new Insets(12));
        VBox.setVgrow(volumes, Priority.ALWAYS);
        VBox.setVgrow(volumeInformation, Priority.ALWAYS);
        copyAllDiagnosticsButton = new Button("Копировать все");
        copyAllDiagnosticsButton.setOnAction(event -> copyAllDiagnostics());
        VBox diagnosticsTab = new VBox(8,
                new Label("Диагностика не изменяет данные; функции исправления отсутствуют."),
                copyAllDiagnosticsButton, diagnosticTable);
        diagnosticsTab.setPadding(new Insets(12));
        VBox.setVgrow(diagnosticTable, Priority.ALWAYS);
        ntfsInformation = new TextArea("Выберите том и нажмите «Анализировать выбранный том». "
                + "NTFS-данные запрашиваются только после явного подтверждения.");
        ntfsInformation.setEditable(false);
        ntfsInformation.setWrapText(true);
        ntfsInformation.setStyle("-fx-font-family: 'Consolas';");
        ntfsAvailability = new Label("Выберите доступный том NTFS.");
        ntfsAvailability.setWrapText(true);
        VBox ntfsTab = new VBox(8, analyzeSelectedVolumeButton, ntfsAvailability, ntfsInformation);
        ntfsTab.setPadding(new Insets(12));
        VBox.setVgrow(ntfsInformation, Priority.ALWAYS);

        TabPane tabs = new TabPane(
                tab(UiMessages.text("tab.disks"), diskTab),
                tab(UiMessages.text("tab.partition"), selectedTab),
                tab(UiMessages.text("tab.filesystem"), fileSystemTab),
                tab(UiMessages.text("tab.ntfs"), ntfsTab),
                tab(UiMessages.text("tab.diagnostics"), diagnosticsTab));
        VBox content = new VBox(10, inventoryStatus, tabs);
        content.setPadding(new Insets(0, 0, 0, 14));
        VBox.setVgrow(tabs, Priority.ALWAYS);
        root.setCenter(content);

        disks.getSelectionModel().selectedItemProperty().addListener((observable, oldValue, selected) ->
                showDisk(selected));
        partitions.getSelectionModel().selectedItemProperty().addListener((observable, oldValue, selected) -> {
            selectedPartitionInformation.setText(selected == null
                    ? UiMessages.text("status.noPartition") : selected.details());
        });
        volumes.getSelectionModel().selectedItemProperty().addListener((observable, oldValue, selected) -> {
            volumeInformation.setText(selected == null ? UiMessages.text("status.noVolume") : describeVolume(selected));
            confirmedCandidate = null;
            ntfsFailureForSelection = "";
            ntfsFailedVolumeGuid = "";
            updateNtfsAction(selected);
            refreshDiagnosticTable();
        });
        return root;
    }

    private void refreshInventory() {
        refreshButton.setDisable(true);
        analyzeSelectedVolumeButton.setDisable(true);
        confirmedCandidate = null;
        ntfsFailureForSelection = "";
        ntfsFailedVolumeGuid = "";
        volumeDiagnostics.clear();
        inventory = new DiskInventory(List.of(), List.of(), List.of());
        disks.getItems().clear();
        volumes.getItems().clear();
        diagnosticTable.getItems().clear();
        ntfsAvailability.setText("Обновление инвентаризации; анализ тома временно недоступен.");
        disks.setDisable(true);
        volumes.setDisable(true);
        volumes.getSelectionModel().clearSelection();
        disks.getSelectionModel().clearSelection();
        progress.setVisible(true);
        inventoryStatus.setText("Обнаружение томов и проверка связанных дисков...");
        Task<DiskInventory> task = new Task<>() {
            @Override
            protected DiskInventory call() throws Exception {
                return inventoryService.inspect();
            }
        };
        task.setOnSucceeded(event -> {
            inventory = task.getValue();
            confirmedCandidate = null;
            ntfsFailureForSelection = "";
            ntfsFailedVolumeGuid = "";
            disks.getSelectionModel().clearSelection();
            disks.getItems().setAll(inventory.disks());
            volumes.getSelectionModel().clearSelection();
            volumes.getItems().setAll(inventory.volumes());
            refreshDiagnosticTable();
            updateNtfsAction(null);
            disks.setDisable(false);
            volumes.setDisable(false);
            analyzeSelectedVolumeButton.setTooltip(new Tooltip("Выберите том для анализа."));
            inventoryStatus.setText("Обнаружено физических дисков: " + inventory.disks().size()
                    + "; томов: " + inventory.volumes().size()
                    + ". Анализ диска выполняется только после его выбора; недоступные устройства остаются в списке.");
            refreshButton.setDisable(false);
            progress.setVisible(false);
            if (!disks.getItems().isEmpty()) {
                disks.getSelectionModel().clearSelection();
                diskInformation.setText("Выберите физический диск для анализа разделов только для чтения. "
                        + "Автоматический выбор не выполняется.");
            } else {
                showDisk(null);
            }
        });
        task.setOnFailed(event -> {
            inventoryStatus.setText("Не удалось обновить список устройств: " + errorText(task.getException()));
            updateNtfsAction(volumes.getSelectionModel().getSelectedItem());
            ntfsAvailability.setText("Анализ недоступен: не удалось обновить список томов. "
                    + errorText(task.getException()));
            analyzeSelectedVolumeButton.setTooltip(new Tooltip(ntfsAvailability.getText()));
            disks.setDisable(false);
            volumes.setDisable(false);
            refreshButton.setDisable(false);
            progress.setVisible(false);
        });
        startWorker(task, "disk-inventory");
    }

    private void showDisk(DiskAnalysis analysis) {
        if (analysis == null) {
            diskInformation.setText(UiMessages.text("status.noDisk"));
            partitions.getItems().clear();
            selectedPartitionInformation.setText(UiMessages.text("status.noPartition"));
            return;
        }
        selectedPartitionInformation.setText(UiMessages.text("status.noPartition"));
        DiskInfo disk = analysis.disk();
        diskInformation.setText("Физический диск " + disk.diskNumber() + "  |  "
                + (disk.model().isBlank() ? "Модель недоступна" : disk.model())
                + "\nУстройство: " + disk.devicePath()
                + "\nПроизводитель: " + fallback(disk.vendor())
                + "   Серийный номер: " + fallback(disk.serialNumber())
                + "\nЁмкость: " + capacityText(disk.capacityBytes())
                + "\nЛогический сектор: " + (disk.logicalSectorSize() > 0
                ? disk.logicalSectorSize() + " байт" : "недоступно")
                + "   Физический сектор: "
                + (disk.physicalSectorSize() > 0 ? disk.physicalSectorSize() + " байт" : "недоступно")
                + "\nТип таблицы разделов: " + disk.partitionStyle().displayName()
                + "\nДоступ на чтение: " + analysis.accessStatus().displayName()
                + (analysis.accessMessage().isBlank() ? "" : "\n" + analysis.accessMessage())
                + "\nСвязанные тома: " + (analysis.volumes().isEmpty() ? "не сопоставлены"
                : analysis.volumes().stream().map(v -> v.volume().volumeGuidPath()
                + (v.volume().mountPoints().isEmpty() ? "" : " (" + String.join(", ", v.volume().mountPoints()) + ")"))
                .reduce((first, second) -> first + ", " + second).orElse("не сопоставлены")));
        partitions.getItems().setAll(partitionRows(analysis));
        if (analysis.accessStatus() == RawDiskAccessStatus.DISCOVERED
                && diskInspectionsInProgress.add(disk.diskNumber())) {
            inspectSelectedDisk(disk.diskNumber());
        }
    }

    private void inspectSelectedDisk(int diskNumber) {
        inventoryStatus.setText("Чтение выбранного физического диска " + diskNumber
                + " только для чтения. Остальные диски остаются доступными.");
        progress.setVisible(true);
        refreshButton.setDisable(true);
        disks.setDisable(true);
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
            inventory = new DiskInventory(updated, inventory.volumes(), inventory.diagnostics());
            disks.getItems().setAll(updated);
            DiskAnalysis current = updated.stream()
                    .filter(disk -> disk.disk().diskNumber() == diskNumber).findFirst().orElseThrow();
            disks.getSelectionModel().select(current);
            refreshDiagnosticTable();
            progress.setVisible(false);
            refreshButton.setDisable(false);
            disks.setDisable(false);
            updateNtfsAction(volumes.getSelectionModel().getSelectedItem());
            inventoryStatus.setText("Выбранный диск " + diskNumber + ": "
                    + current.accessStatus().displayName()
                    + (current.accessMessage().isBlank() ? "" : " — " + current.accessMessage()));
        });
        task.setOnFailed(event -> {
            diskInspectionsInProgress.remove(diskNumber);
            progress.setVisible(false);
            refreshButton.setDisable(false);
            disks.setDisable(false);
            updateNtfsAction(volumes.getSelectionModel().getSelectedItem());
            inventoryStatus.setText("Не удалось проверить выбранный диск: " + errorText(task.getException()));
        });
        startWorker(task, "physical-disk-" + diskNumber + "-inspection");
    }

    private void refreshDiagnosticTable() {
        VolumeInspection selected = volumes.getSelectionModel().getSelectedItem();
        List<DiagnosticFinding> allVolumeFindings = volumeDiagnostics.values().stream()
                .flatMap(List::stream).toList();
        List<DiagnosticFinding> selectedFindings = selected == null ? List.of()
                : volumeDiagnostics.getOrDefault(diagnosticKey(selected), List.of());
        List<DiagnosticFinding> visible = diagnosticSelectionService.visibleFindings(
                inventory, selected, selectedFindings, allVolumeFindings);
        if (visible.isEmpty()) {
            visible = List.of(new DiagnosticFinding(
                    io.github.delokoseni.service.diagnostics.DiagnosticSeverity.INFORMATION,
                    selected == null ? "Диагностика" : selected.volume().volumeGuidPath(),
                    selected == null ? "Диагностические сообщения отсутствуют."
                            : "Для выбранного тома и связанного физического диска диагностические сообщения отсутствуют."));
        }
        diagnosticTable.getItems().setAll(visible);
    }

    private void storeVolumeDiagnostics(VolumeInspection volume, List<DiagnosticFinding> findings) {
        List<DiagnosticFinding> conciseFindings = findings.stream()
                .map(finding -> finding.message().contains("Количество секторов NTFS отличается")
                        ? new DiagnosticFinding(finding.severity(), finding.scope(),
                        "Расхождение границы NTFS сохранено; подробный анализ доступен в основном выводе анализа.")
                        : finding)
                .toList();
        volumeDiagnostics.put(diagnosticKey(volume), List.copyOf(conciseFindings));
        refreshDiagnosticTable();
    }

    private static String diagnosticKey(VolumeInspection volume) {
        return volume.volume().volumeGuidPath().toLowerCase(java.util.Locale.ROOT);
    }

    private void updateNtfsAction(VolumeInspection selected) {
        NtfsAnalysisReadiness.Readiness readiness =
                NtfsAnalysisReadiness.evaluate(selected, inventory.volumes());
        String selectionKey = selected == null ? "" : selected.volume().volumeGuidPath();
        boolean failedForSelection = !ntfsFailedVolumeGuid.isBlank()
                && ntfsFailedVolumeGuid.equalsIgnoreCase(selectionKey);
        ntfsAvailability.setText(failedForSelection
                ? "Анализ недоступен: " + ntfsFailureForSelection
                : readiness.explanation());
        analyzeSelectedVolumeButton.setTooltip(new Tooltip(ntfsAvailability.getText()));
        analyzeSelectedVolumeButton.setDisable(ntfsOperationInProgress
                || !readiness.available() || failedForSelection);
    }

    private void copyAllDiagnostics() {
        if (diagnosticTable.getItems().isEmpty()) {
            inventoryStatus.setText("Нет диагностических сообщений для копирования.");
            return;
        }
        VolumeInspection selected = volumes.getSelectionModel().getSelectedItem();
        copyDiagnostics(clipboard -> new DiagnosticCopyService().copyAll(
                diagnosticTable.getItems(), inventory, selected, clipboard));
    }

    private void copyOneDiagnostic(DiagnosticFinding finding) {
        VolumeInspection selected = volumes.getSelectionModel().getSelectedItem();
        copyDiagnostics(clipboard -> new DiagnosticCopyService().copyOne(
                finding, inventory, selected, clipboard));
    }

    private void copyDiagnostics(java.util.function.Function<java.util.function.Consumer<String>, Boolean> copy) {
        try {
            boolean copied = copy.apply(text -> {
                ClipboardContent content = new ClipboardContent();
                content.putString(text);
                if (!Clipboard.getSystemClipboard().setContent(content)) {
                    throw new IllegalStateException("Системный буфер обмена отклонил текст.");
                }
            });
            inventoryStatus.setText(copied ? "Диагностика скопирована." : "Нет диагностических сообщений для копирования.");
        } catch (RuntimeException e) {
            inventoryStatus.setText("Не удалось скопировать диагностику: " + errorText(e));
        }
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
        return "Источник: " + p.source()
                + "\nТип раздела: " + partitionType(p)
                + "\nGUID раздела: " + fallback(p.typeGuid())
                + "\nУникальный GUID раздела: " + fallback(p.uniqueGuid())
                + "\nАтрибуты GPT: 0x" + Long.toUnsignedString(p.attributes(), 16)
                + "\nТип раздела MBR: " + String.format("0x%02X", p.mbrType())
                + "\nПризнак загрузки: " + (p.typeGuid().isBlank()
                ? (p.bootable() ? "загрузочный" : "незагрузочный") : "не применяется (GPT)")
                + "\nНачальный LBA: " + p.firstLba()
                + "\nКонечный LBA (включительно): " + p.lastLbaInclusive()
                + "\nНачальное смещение в байтах: " + p.startingOffsetBytes()
                + "\nРазмер раздела: " + p.lengthBytes() + " байт"
                + "\nИмя: " + fallback(p.name());
    }

    private void verifyThenAnalyze(Button action) {
        VolumeInspection selected = volumes.getSelectionModel().getSelectedItem();
        if (selected == null) {
            inventoryStatus.setText(UiMessages.text("status.chooseVolume"));
            return;
        }
        ntfsOperationInProgress = true;
        updateNtfsAction(selected);
        volumes.setDisable(true);
        progress.setVisible(true);
        inventoryStatus.setText("Повторная проверка выбранного тома, его физического диска и разделов...");
        Task<VolumeAnalysisCandidate> verification = new Task<>() {
            @Override
            protected VolumeAnalysisCandidate call() throws Exception {
                return volumeService.verifySelectedVolume(selected);
            }
        };
        verification.setOnSucceeded(event -> {
            VolumeAnalysisCandidate candidate = verification.getValue();
            progress.setVisible(false);
            volumes.setDisable(false);
            VolumeInspection currentSelection = volumes.getSelectionModel().getSelectedItem();
            if (currentSelection == null || !currentSelection.volume().volumeGuidPath()
                    .equalsIgnoreCase(candidate.volumeInspection().volume().volumeGuidPath())) {
                confirmedCandidate = null;
                ntfsOperationInProgress = false;
                inventoryStatus.setText("Выбор тома изменился во время проверки; анализ не запущен.");
                updateNtfsAction(currentSelection);
                return;
            }
            if (confirmedCandidate != null && sameConfirmedCandidate(confirmedCandidate, candidate)) {
                inspectConfirmedCandidate(candidate, action);
                return;
            }
            confirmedCandidate = null;
            ntfsOperationInProgress = false;
            Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION);
            confirmation.setTitle("Подтверждение анализа только для чтения");
            confirmation.setHeaderText("Проверен том на физическом диске " + candidate.physicalDiskNumber());
            confirmation.setContentText(describeCandidate(candidate)
                    + "\n\nПродолжить анализ файловой системы и параметров NTFS только для чтения?");
            confirmation.getButtonTypes().setAll(new ButtonType("Продолжить"), new ButtonType("Отмена"));
            ButtonType continueButton = confirmation.getButtonTypes().getFirst();
            boolean confirmed = confirmation.showAndWait().orElse(ButtonType.CANCEL) == continueButton;
            if (confirmed) {
                confirmedCandidate = candidate;
                inspectConfirmedCandidate(candidate, action);
            } else {
                inventoryStatus.setText("Анализ отменён; запрос NTFS не выполнялся.");
                confirmedCandidate = null;
                storeVolumeDiagnostics(currentSelection, List.of(new DiagnosticFinding(
                        io.github.delokoseni.service.diagnostics.DiagnosticSeverity.INFORMATION,
                        currentSelection.volume().volumeGuidPath(),
                        "Подтверждение не получено; запрос NTFS не выполнялся.")));
                updateNtfsAction(currentSelection);
            }
        });
        verification.setOnFailed(event -> {
            confirmedCandidate = null;
            ntfsOperationInProgress = false;
            volumes.setDisable(false);
            String reason = errorText(verification.getException());
            ntfsFailureForSelection = reason;
            ntfsFailedVolumeGuid = selected.volume().volumeGuidPath();
            inventoryStatus.setText("Не удалось проверить выбранный том; анализ не запущен: " + reason);
            storeVolumeDiagnostics(selected, List.of(new DiagnosticFinding(
                    io.github.delokoseni.service.diagnostics.DiagnosticSeverity.ERROR,
                    selected.volume().volumeGuidPath() + " — проверка",
                    errorText(verification.getException()))));
            updateNtfsAction(volumes.getSelectionModel().getSelectedItem());
            progress.setVisible(false);
        });
        startWorker(verification, "selected-volume-verification");
    }

    private void inspectConfirmedCandidate(VolumeAnalysisCandidate candidate, Button action) {
        ntfsOperationInProgress = true;
        updateNtfsAction(volumes.getSelectionModel().getSelectedItem());
        progress.setVisible(true);
        inventoryStatus.setText("Повторная проверка тома и чтение подтверждённых параметров файловой системы...");
        volumes.setDisable(true);
        Task<VolumeAnalysis> analysis = new Task<>() {
            @Override
            protected VolumeAnalysis call() throws Exception {
                return volumeService.inspectVerifiedVolume(candidate, true);
            }
        };
        analysis.setOnSucceeded(event -> {
            VolumeAnalysis result = analysis.getValue();
            inventoryStatus.setText("Анализ только для чтения завершён для тома "
                    + result.candidate().volumeInspection().volume().volumeGuidPath() + ".");
            ntfsOperationInProgress = false;
            ntfsFailureForSelection = "";
            ntfsFailedVolumeGuid = "";
            updateNtfsAction(volumes.getSelectionModel().getSelectedItem());
            volumes.setDisable(false);
            progress.setVisible(false);
            storeVolumeDiagnostics(result.candidate().volumeInspection(),
                    new DiagnosticService().classify(
                            result.candidate().volumeInspection().volume().volumeGuidPath(),
                            result.diagnostics()));
            showAnalysisResult(result);
        });
        analysis.setOnFailed(event -> {
            volumes.setDisable(false);
            ntfsOperationInProgress = false;
            String reason = errorText(analysis.getException());
            ntfsFailureForSelection = reason;
            ntfsFailedVolumeGuid = candidate.volumeInspection().volume().volumeGuidPath();
            inventoryStatus.setText("Анализ безопасно остановлен: " + reason);
            DiagnosticFinding finding = new DiagnosticService().classify(
                    candidate.volumeInspection().volume().volumeGuidPath(), List.of(errorText(analysis.getException())))
                    .getFirst();
            storeVolumeDiagnostics(candidate.volumeInspection(), List.of(finding));
            updateNtfsAction(volumes.getSelectionModel().getSelectedItem());
            progress.setVisible(false);
        });
        startWorker(analysis, "selected-volume-analysis");
    }


    private void showAnalysisResult(VolumeAnalysis result) {
        Alert report = new Alert(Alert.AlertType.INFORMATION);

        report.setTitle("Анализ только для чтения");
        report.setHeaderText(
                "Проверен том на физическом диске "
                        + result.candidate().physicalDiskNumber()
        );

        String text = describeCandidate(result.candidate());
        String fileSystem = result.candidate()
                .volumeInspection().volume().fileSystem();

        if (result.ntfsData().isEmpty()) {
            text += "\n\nДанные NTFS недоступны, поскольку файловая система выбранного тома — "
                    + (fileSystem.isBlank() ? "неизвестная" : fileSystem) + ".";
        }

        if (result.ntfsData().isPresent()) {
            NtfsVolumeData ntfs = result.ntfsData().orElseThrow();
            var metrics = result.ntfsMetrics().orElseThrow();

            text += "\n\nСерийный номер тома NTFS: "
                    + Long.toUnsignedString(ntfs.volumeSerialNumber())
                    + "\nКоличество секторов: " + ntfs.numberOfSectors()
                    + "\nВсего кластеров: " + ntfs.totalClusters()
                    + "\nСвободных кластеров: " + ntfs.freeClusters()
                    + "\nЗарезервировано кластеров: " + ntfs.reservedClusters()
                    + "\nБайт в секторе / кластере: "
                    + ntfs.bytesPerSector() + " / " + ntfs.bytesPerCluster()
                    + "\nСекторов в кластере: " + metrics.sectorsPerCluster()
                    + "\nЁмкость кластеров: "
                    + CapacityFormatter.format(metrics.totalClusterCapacityBytes())
                    + "\nИспользовано кластеров (включая резерв): "
                    + metrics.usedClusters()
                    + "\nЁмкость свободных кластеров: "
                    + CapacityFormatter.format(metrics.freeClusterCapacityBytes())
                    + "\nНачало MFT (LCN / смещение в томе): "
                    + ntfs.mftStartLcn() + " / "
                    + metrics.mftStartOffsetWithinVolume() + " байт"
                    + "\nЗеркало MFT (LCN / смещение в томе): "
                    + ntfs.mftMirrorStartLcn() + " / "
                    + metrics.mftMirrorStartOffsetWithinVolume() + " байт"
                    + "\nДлина действительных данных MFT: "
                    + ntfs.mftValidDataLength()
                    + "\nРазмер записи MFT: "
                    + metrics.mftRecordSizeBytes()
                    + " байт (" + NtfsFileRecordSegmentSize.describeClustersPerRecord(
                    metrics.mftRecordSizeBytes(), ntfs.bytesPerCluster()) + ")"
                    + "; Windows ClustersPerFileRecordSegment: "
                    + ntfs.clustersPerFileRecordSegment()
                    + (ntfs.clustersPerFileRecordSegment() == 0
                    && metrics.mftRecordSizeBytes() < ntfs.bytesPerCluster()
                    ? " (запись меньше кластера; значение DWORD равно нулю)" : "")
                    + (result.ntfsBootSector().isPresent()
                    ? "; знаковое кодирование размера записи в BPB: "
                    + result.ntfsBootSector().orElseThrow().fileRecordSizeEncoding() : "")
                    + "\nДиапазон зоны MFT по LCN: ["
                    + ntfs.mftZoneStartLcn() + ", "
                    + ntfs.mftZoneEndLcn() + "), размер: "
                    + CapacityFormatter.format(metrics.mftZoneSizeBytes())
                    + "\nВерсия NTFS: FSCTL_GET_NTFS_VOLUME_DATA её не предоставляет";
        }

        if (!result.diagnostics().isEmpty()) {
            text += "\n\nДиагностика:\n"
                    + String.join("\n", result.diagnostics());
        }

        ntfsInformation.setText(text);

        // Создаём прокручиваемое поле с результатами
        TextArea textArea = new TextArea(text);
        textArea.setEditable(false);
        textArea.setWrapText(true);

        // Определяем доступную область экрана
        Rectangle2D bounds = Screen.getPrimary().getVisualBounds();

        double width = Math.min(900, bounds.getWidth() * 0.85);
        double height = Math.min(700, bounds.getHeight() * 0.85);

        textArea.setPrefWidth(width - 40);
        textArea.setPrefHeight(height - 140);

        report.getDialogPane().setContent(textArea);
        report.getDialogPane().setPrefWidth(width);
        report.getDialogPane().setPrefHeight(height);

        report.showAndWait();
    }


    private static String describeCandidate(VolumeAnalysisCandidate candidate) {
        VolumeInspection inspection = candidate.volumeInspection();
        var fileSystemInfo = inspection.fileSystem();
        StringBuilder text = new StringBuilder()
                .append("Точки подключения: ").append(inspection.volume().mountPoints().isEmpty()
                        ? "отсутствуют" : String.join(", ", inspection.volume().mountPoints()))
                .append("\nGUID тома: ").append(inspection.volume().volumeGuidPath())
                .append("\nФайловая система: ").append(inspection.volume().fileSystem())
                .append("\nМетка тома: ").append(fallback(inspection.volume().label()))
                .append("\nСерийный номер тома: ").append(inspection.fileSystem().isPresent()
                        ? Long.toUnsignedString(inspection.fileSystem().orElseThrow().volumeSerialNumber())
                        : "недоступно")
                .append("\nЁмкость тома: ").append(fileSystemInfo.isPresent()
                        ? CapacityFormatter.format(fileSystemInfo.orElseThrow().totalBytes()) : "недоступно")
                .append("\nСвободно: ").append(fileSystemInfo.isPresent()
                        ? CapacityFormatter.format(fileSystemInfo.orElseThrow().availableBytes()) : "недоступно")
                .append("\nФизический диск: ").append(candidate.physicalDiskNumber())
                .append("\nМодель устройства: ").append(candidate.model().isBlank()
                        ? "недоступна" : candidate.model())
                .append("\nСерийный номер устройства: ").append(candidate.serialNumber().isBlank()
                        ? "недоступен" : candidate.serialNumber())
                .append("\nЁмкость диска: ").append(CapacityFormatter.format(candidate.diskCapacityBytes()))
                .append("\nЛогический сектор: ").append(candidate.logicalSectorSize()).append(" байт")
                .append("\nФизический сектор: ")
                .append(candidate.physicalSectorSize().isPresent()
                        ? candidate.physicalSectorSize().getAsInt() + " байт" : "недоступен");
        for (var partition : candidate.partitions()) {
            text.append("\nРаздел ").append(partition.partitionNumber())
                    .append(": смещение ").append(partition.startingOffsetBytes())
                    .append(" байт, размер ").append(partition.lengthBytes()).append(" байт");
        }
        if (!candidate.diagnostics().isEmpty()) {
            text.append("\n\nПримечания:\n").append(String.join("\n", candidate.diagnostics()));
        }
        return text.toString();
    }

    private static boolean sameConfirmedCandidate(
            VolumeAnalysisCandidate first, VolumeAnalysisCandidate second) {
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
                .append("GUID тома: ").append(inspection.volume().volumeGuidPath())
                .append("\nТочки подключения: ").append(inspection.volume().mountPoints().isEmpty()
                        ? "отсутствуют" : String.join(", ", inspection.volume().mountPoints()))
                .append("\nФайловая система: ").append(inspection.volume().fileSystem())
                .append("\nМетка тома: ").append(inspection.volume().label());
        if (inspection.fileSystem().isPresent()) {
            var info = inspection.fileSystem().orElseThrow();
            text.append("\nСерийный номер тома: ").append(Long.toUnsignedString(info.volumeSerialNumber()))
                    .append("\nМаксимальная длина имени компонента: ").append(info.maximumComponentNameLength())
                    .append("\nВозможности: ").append(String.join(", ", info.capabilityNames()))
                    .append("\nВсего / свободно / доступно: ").append(CapacityFormatter.format(info.totalBytes()))
                    .append(" / ").append(CapacityFormatter.format(info.freeBytes()))
                    .append(" / ").append(CapacityFormatter.format(info.availableBytes()))
                    .append("\nРазмер сектора файловой системы: ").append(info.bytesPerSector()).append(" байт")
                    .append("\nСекторов в кластере: ").append(info.sectorsPerCluster())
                    .append("\nРазмер кластера файловой системы: ")
                    .append(CapacityFormatter.format(info.clusterSizeBytes()));
        }
        for (var extent : inspection.diskExtents()) {
            text.append("\nОбласть: физический диск ").append(extent.diskNumber())
                    .append(", смещение ").append(extent.startingOffsetBytes())
                    .append(", размер ").append(extent.extentLengthBytes()).append(" байт");
        }
        if (!inspection.diagnostics().isEmpty()) {
            text.append("\n\nДиагностика:\n").append(String.join("\n", inspection.diagnostics()));
        }
        text.append("\n\nФлаги возможностей обозначают поддержку файловой системы, а не использование каждой функции файлами.");
        return text.toString();
    }

    private static TableView<PartitionRow> createPartitionTable() {
        TableView<PartitionRow> table = new TableView<>();
        table.setPlaceholder(new Label(UiMessages.text("empty.partition")));
        addPartitionColumn(table, "Номер", PartitionRow::number, 65);
        addPartitionColumn(table, "Том / точка подключения", PartitionRow::mount, 150);
        addPartitionColumn(table, "Файловая система", PartitionRow::fileSystem, 120);
        addPartitionColumn(table, "Тип", PartitionRow::type, 180);
        addPartitionColumn(table, "Смещение (байт)", PartitionRow::startOffset, 140);
        addPartitionColumn(table, "Размер", PartitionRow::size, 100);
        addPartitionColumn(table, "Начальный LBA", PartitionRow::firstLba, 100);
        addPartitionColumn(table, "Конечный LBA", PartitionRow::lastLba, 100);
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
        list.setPlaceholder(new Label(UiMessages.text("empty.volumes")));
        list.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(VolumeInspection item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else {
                    String mounts = item.volume().mountPoints().isEmpty()
                            ? "(без точки подключения)" : String.join(", ", item.volume().mountPoints());
                    setText(mounts + "  |  " + item.volume().fileSystem() + "  |  "
                            + item.volume().volumeGuidPath());
                }
            }
        });
        return list;
    }

    private TableView<DiagnosticFinding> createDiagnosticTable() {
        TableView<DiagnosticFinding> table = new TableView<>();
        table.setPlaceholder(new Label(UiMessages.text("empty.diagnostics")));
        TableColumn<DiagnosticFinding, String> severity = diagnosticColumn(
                "Важность", finding -> severityName(finding.severity()), 110);
        TableColumn<DiagnosticFinding, String> scope = diagnosticColumn(
                "Устройство / том", finding -> diagnosticTextFormatter.scopeLabel(
                        finding, inventory, volumes.getSelectionModel().getSelectedItem()), 320);
        TableColumn<DiagnosticFinding, String> message = diagnosticColumn(
                "Результат", DiagnosticFinding::message, 650);
        TableColumn<DiagnosticFinding, Void> copy = new TableColumn<>("Копирование");
        copy.setPrefWidth(115);
        copy.setCellFactory(column -> new javafx.scene.control.TableCell<>() {
            private final Button button = new Button("Копировать");
            {
                button.setOnAction(event -> {
                    DiagnosticFinding finding = getTableView().getItems().get(getIndex());
                    copyOneDiagnostic(finding);
                });
            }

            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                setGraphic(empty ? null : button);
            }
        });
        table.getColumns().add(severity);
        table.getColumns().add(scope);
        table.getColumns().add(message);
        table.getColumns().add(copy);
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
                        case UNAVAILABLE -> "-fx-background-color: #e8eef5;";
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
        return value == null || value.isBlank() ? "недоступно" : value;
    }

    private static String capacityText(long capacity) {
        return capacity > 0 ? CapacityFormatter.format(capacity) : "недоступно";
    }

    private static String errorText(Throwable error) {
        return error == null || error.getMessage() == null ? "неизвестная ошибка" : error.getMessage();
    }

    private static String severityName(io.github.delokoseni.service.diagnostics.DiagnosticSeverity severity) {
        return switch (severity) {
            case ERROR -> "ОШИБКА";
            case WARNING -> "ПРЕДУПРЕЖДЕНИЕ";
            case INFORMATION -> "ИНФОРМАЦИЯ";
            case UNAVAILABLE -> "НЕДОСТУПНО";
        };
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
