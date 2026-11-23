package io.github.delokoseni.service.ntfs;

import java.util.List;

public record NtfsBackupBootSectorEvidence(
        long bpbLastAbsoluteDiskLba,
        long extentLastAbsoluteDiskLba,
        boolean bpbLastSectorReadable,
        boolean bpbLastSectorMatchesPrimary,
        List<Long> matchingTrailingAbsoluteDiskLbas,
        List<Long> zeroTrailingAbsoluteDiskLbas,
        List<Long> otherTrailingAbsoluteDiskLbas,
        List<Long> unreadableTrailingAbsoluteDiskLbas,
        boolean trailingInspectionComplete) {
    public NtfsBackupBootSectorEvidence {
        matchingTrailingAbsoluteDiskLbas = List.copyOf(matchingTrailingAbsoluteDiskLbas);
        zeroTrailingAbsoluteDiskLbas = List.copyOf(zeroTrailingAbsoluteDiskLbas);
        otherTrailingAbsoluteDiskLbas = List.copyOf(otherTrailingAbsoluteDiskLbas);
        unreadableTrailingAbsoluteDiskLbas = List.copyOf(unreadableTrailingAbsoluteDiskLbas);
    }

    public boolean matchingExtentTerminal() {
        return matchingTrailingAbsoluteDiskLbas.contains(extentLastAbsoluteDiskLba);
    }
}
