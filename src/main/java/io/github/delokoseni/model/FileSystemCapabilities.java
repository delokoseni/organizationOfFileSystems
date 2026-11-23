package io.github.delokoseni.model;

import java.util.ArrayList;
import java.util.List;

public final class FileSystemCapabilities {
    public static final long CASE_SENSITIVE_SEARCH = 0x00000001L;
    public static final long CASE_PRESERVED_NAMES = 0x00000002L;
    public static final long UNICODE_ON_DISK = 0x00000004L;
    public static final long PERSISTENT_ACLS = 0x00000008L;
    public static final long FILE_COMPRESSION = 0x00000010L;
    public static final long VOLUME_QUOTAS = 0x00000020L;
    public static final long SUPPORTS_SPARSE_FILES = 0x00000040L;
    public static final long SUPPORTS_REPARSE_POINTS = 0x00000080L;
    public static final long SUPPORTS_REMOTE_STORAGE = 0x00000100L;
    public static final long VOLUME_IS_COMPRESSED = 0x00008000L;
    public static final long SUPPORTS_OBJECT_IDS = 0x00010000L;
    public static final long SUPPORTS_ENCRYPTION = 0x00020000L;
    public static final long NAMED_STREAMS = 0x00040000L;
    public static final long READ_ONLY_VOLUME = 0x00080000L;
    public static final long SEQUENTIAL_WRITE_ONCE = 0x00100000L;
    public static final long SUPPORTS_TRANSACTIONS = 0x00200000L;
    public static final long SUPPORTS_HARD_LINKS = 0x00400000L;
    public static final long SUPPORTS_EXTENDED_ATTRIBUTES = 0x00800000L;
    public static final long SUPPORTS_OPEN_BY_FILE_ID = 0x01000000L;
    public static final long SUPPORTS_USN_JOURNAL = 0x02000000L;
    public static final long SUPPORTS_INTEGRITY_STREAMS = 0x04000000L;
    public static final long SUPPORTS_BLOCK_REFCOUNTING = 0x08000000L;
    public static final long SUPPORTS_SPARSE_VDL = 0x10000000L;
    public static final long SUPPORTS_GHOSTING = 0x40000000L;

    private static final long[][] FLAGS = {
            {CASE_SENSITIVE_SEARCH, 1}, {CASE_PRESERVED_NAMES, 2}, {UNICODE_ON_DISK, 4},
            {PERSISTENT_ACLS, 8}, {FILE_COMPRESSION, 16}, {VOLUME_QUOTAS, 32},
            {SUPPORTS_SPARSE_FILES, 64}, {SUPPORTS_REPARSE_POINTS, 128},
            {SUPPORTS_REMOTE_STORAGE, 256}, {VOLUME_IS_COMPRESSED, 0x8000},
            {SUPPORTS_OBJECT_IDS, 0x10000}, {SUPPORTS_ENCRYPTION, 0x20000},
            {NAMED_STREAMS, 0x40000}, {READ_ONLY_VOLUME, 0x80000},
            {SEQUENTIAL_WRITE_ONCE, 0x100000}, {SUPPORTS_TRANSACTIONS, 0x200000},
            {SUPPORTS_HARD_LINKS, 0x400000}, {SUPPORTS_EXTENDED_ATTRIBUTES, 0x800000},
            {SUPPORTS_OPEN_BY_FILE_ID, 0x1000000}, {SUPPORTS_USN_JOURNAL, 0x2000000},
            {SUPPORTS_INTEGRITY_STREAMS, 0x4000000}, {SUPPORTS_BLOCK_REFCOUNTING, 0x8000000},
            {SUPPORTS_SPARSE_VDL, 0x10000000}, {SUPPORTS_GHOSTING, 0x40000000}
    };

    private static final String[] NAMES = {
            "Поиск с учётом регистра", "Имена с сохранением регистра", "Unicode на диске",
            "Постоянные ACL", "Сжатие файлов", "Квоты диска",
            "Разреженные файлы", "Точки повторной обработки", "Удалённое хранилище",
            "Сжатый том", "Идентификаторы объектов", "Шифрование файлов",
            "Именованные потоки", "Том только для чтения", "Последовательная однократная запись",
            "Транзакции", "Жёсткие ссылки", "Расширенные атрибуты",
            "Открытие по идентификатору файла", "Журнал USN", "Потоки целостности",
            "Подсчёт ссылок на блоки", "Разрежённая длина действительных данных", "Призрачные файлы"
    };

    private FileSystemCapabilities() {
    }

    public static List<String> decode(long flags) {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < FLAGS.length; i++) {
            if ((flags & FLAGS[i][0]) != 0) {
                names.add(NAMES[i]);
            }
        }
        return List.copyOf(names);
    }
}
