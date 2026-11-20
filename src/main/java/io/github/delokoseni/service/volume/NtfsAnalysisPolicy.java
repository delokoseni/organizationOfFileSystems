package io.github.delokoseni.service.volume;

final class NtfsAnalysisPolicy {
    private NtfsAnalysisPolicy() {
    }

    static boolean shouldQuery(String fileSystemName, boolean userConfirmed) {
        return userConfirmed && FileSystemType.fromWindowsName(fileSystemName) == FileSystemType.NTFS;
    }
}
