# File System Organization — универсальный анализатор дисков (только чтение)

This Java 21 / JavaFX application uses a layered design: JavaFX application and
controller, immutable models, disk/volume services, independent binary parsers,
and a narrow Windows-native adapter. Maven provides JavaFX, JNA, and JUnit 5.

## Implemented

- MBR and EBR parsing from a `DiskReader`, including four primary entries, logical
  partitions, bounded EBR chains, cyclic-link detection, LBA and capacity checks,
  boot-indicator validation, and overlap diagnostics.
- GPT parsing from header-provided array locations and sizes, including protective
  MBR, primary/backup headers and arrays, header and array CRC32 checks, partition
  GUIDs, attributes, names, usable ranges, and primary/backup comparisons.
- A `DiskReader` interface with no write operation. The Windows implementation
  requests `GENERIC_READ` only, validates every read range, performs `ReadFile`
  reads, and rejects incomplete reads and native errors.
- An explicit native-query allowlist: `IOCTL_DISK_GET_DRIVE_GEOMETRY_EX`,
  `IOCTL_DISK_GET_DRIVE_LAYOUT_EX`, and read-only
  `IOCTL_STORAGE_QUERY_PROPERTY` queries for the device descriptor and sector
  alignment.
- Decoding of Windows drive-layout results and a pure comparison service that
  reports style, count, partition-number, offset, length, MBR-type, and GPT-ID
  differences without correcting either result.
- Synthetic in-memory parser fixtures and tests only; no test opens a physical
  disk or volume.
- A Russian-language JavaFX interface with a persistent read-only indicator,
  asynchronous disk and volume refresh, independent disk selection, and
  **Анализировать выбранный том** gated by fresh volume-to-disk mapping,
  partition-boundary checks, and explicit confirmation.
- An integrated disk/volume inventory view with partition tables, selected
  partition details, filesystem/volume metadata, NTFS/MFT results, and severity-
  classified validation findings filtered to the selected volume and its
  associated physical disk; with no volume selected, device diagnostics remain
  visible with their disk/volume scope. Refresh clears cached per-volume
  findings. A volume can be selected by its volume GUID,
  including when it has no drive letter. The selected volume is resolved again
  before confirmation and before any NTFS query; a missing selection is never
  replaced by another volume.
- Physical disks are discovered independently through the Windows SetupAPI disk
  interface and PnP device properties. When a PnP object name has no disk number,
  `IOCTL_STORAGE_GET_DEVICE_NUMBER` is queried through a handle requesting zero
  desired access. Volume extents supplement that inventory; neither raw-read
  permission nor successful volume mapping is required for a discovered disk
  to remain visible. If SetupAPI enumeration fails, its error is retained in
  diagnostics while any disk numbers reported by volume extents are still shown.
  Raw partition analysis runs only after a disk is selected.
- Read-only volume enumeration and metadata queries using
  `FindFirstVolumeW`/`FindNextVolumeW`, `GetVolumePathNamesForVolumeNameW`,
  `GetVolumeInformationW`, `GetDiskFreeSpaceExW`, and `GetDiskFreeSpaceW`.
  Results retain volume serial, label, filesystem, component limit, capability
  flags, total/free/available bytes, filesystem bytes-per-sector, sectors per
  cluster, and cluster size. Access and metadata errors are retained as
  per-volume diagnostics.
- Volume extent reads using `IOCTL_VOLUME_GET_VOLUME_DISK_EXTENTS`, with
  explicit physical disk numbers and checked ranges. Extent-to-partition mapping
  supports multiple extents and reports absent or ambiguous partition matches.
- Read-only `FSCTL_GET_NTFS_VOLUME_DATA` queries only after the selected volume
  has passed fresh identity checks and the user confirms, plus checked
  calculations for cluster capacities, used/free counts, MFT/MFT mirror
  volume-relative offsets, record size, and MFT zone size. Direct MFT records
  are not read. The displayed used-cluster count is total clusters minus free
  clusters, so it includes clusters that NTFS reports as reserved.
- Filesystem capability flag decoding and diagnostic consistency checks between
  partitions, extents, Windows filesystem capacities, sector geometry, and NTFS
  cluster data. Capacity differences are reported, not “fixed.”
- In-memory CRC32 and SHA-256 helpers. GPT CRC32 validation and these helpers
  operate only on byte arrays already in application memory; no disk images,
  checksum files, or reports are created.

## Partition formats

### MBR

The first logical sector contains the boot code/reserved area, four 16-byte
partition entries beginning at byte 446, and the `0x55AA` signature at offsets
510–511. Each entry supplies a boot indicator, type, starting LBA, and sector
count. Offsets and lengths are calculated using the reported logical sector
size, with overflow and disk-boundary checks. Overlapping and invalid entries
are reported; the parser never changes the sector.

### EBR

An extended MBR partition describes a bounded container. Its first EBR is at the
container base. The first entry in each EBR describes a logical partition with
its start relative to that EBR; the second entry links to the next EBR with its
start relative to the original extended-partition base. The parser bounds every
record and logical partition by both the container and disk, tracks visited
LBAs, and caps the chain at 4096 records. Invalid links stop the chain.

### GPT and CRC32

GPT parsing reads the protective MBR, primary header at LBA 1, and backup header
at the final device LBA. Each valid header supplies its own entry-array LBA,
entry count, and entry size; allocations are capped (32 MiB/16,384 entries) and
checked before reads. Logical sectors above 1 MiB and individual reads above
64 MiB are rejected.
Partition entries are decoded only after boundary validation. Header CRC32 is
computed over `HeaderSize` bytes after zeroing the four-byte CRC field at offset
16. The entry-array CRC32 covers exactly `NumberOfPartitionEntries *
SizeOfPartitionEntry` bytes. Primary/backup GUIDs, usable ranges, entry
dimensions, and entries are compared diagnostically. GPT metadata is not
considered an active partition table based on signatures alone: its CRCs and
locations are validated, and verified MBR partitions are retained when GPT
remnants are invalid. Hybrid or ambiguous layouts are reported without exposing
unverified GPT entries as partitions. A verified MBR does not require a
protective `0xEE` entry; invalid GPT remnants are reported separately as
warnings. For an active GPT layout, a present but incorrectly sized protective
MBR entry is distinguished from a missing entry. No repair, copy, or write path
exists.

## Windows and volume comparison

`IOCTL_DISK_GET_DRIVE_GEOMETRY_EX` supplies device capacity and logical sector
size. `IOCTL_STORAGE_QUERY_PROPERTY` retrieves model/vendor strings and physical
sector alignment. `IOCTL_DISK_GET_DRIVE_LAYOUT_EX` is decoded separately from
the independent sector parser. The comparison service reports discrepancies;
it never chooses a Windows result to overwrite parsed data or attempts a
correction. Zero-filled unused entries in the Windows layout buffer are
excluded from the actual partition list; the API's slot count is not assumed to
be the number of populated partitions. The volume tab initiates read-only
volume enumeration in a background task. Filesystem sector and cluster sizes
are separate values and are not assumed to equal physical or logical disk
sector sizes.

An LBA is a logical sector index relative to a physical disk. An LCN is a logical
cluster index relative to a volume. Multiplying an LCN by the NTFS cluster size
produces only a volume-relative byte offset. It is not a disk LBA or a physical
disk offset. This implementation does not calculate an absolute disk offset
from an LCN. It also does not infer a Windows filesystem/volume version from
`FSCTL_GET_NTFS_VOLUME_DATA`, because that structure does not provide a version.

Filesystem capability flags describe supported filesystem features; they do not
mean each file uses the feature. Filesystem capacity can differ from partition
length because of metadata, reserved regions, filesystem policy, and capacity
reporting semantics. Differences are diagnostic only. When the NTFS BPB and
FSCTL sector counts disagree with the mapped extent, the analyzer reads the
BPB-indicated terminal sector and a bounded trailing range read-only, recording
matching boot-sector copies, zero-filled sectors, other data, and unreadable
sectors. Boundary messages distinguish volume-relative LBAs from absolute
physical-disk LBAs and consolidate the counts, extent difference, and observed
boot-sector evidence into one warning. A matching copy at the partition end
alone never validates the NTFS boundary; unresolved discrepancies remain
warnings.

## Safety and limitations

The general volume refresh reads volume metadata and extents but does not issue
the NTFS FSCTL. The analysis action uses only the currently selected volume GUID,
requires its extents to map to one physical disk, opens that disk with
`GENERIC_READ`, and maps all extents to Windows partition boundaries. Volumes
spanning multiple physical disks are reported as unsupported by this workflow;
the application never selects one of their disks as a substitute. The action
presents the current device, volume, filesystem, and partition information for
explicit confirmation. After confirmation it repeats identity and mapping checks
before opening that volume for the NTFS query. Any missing, changed, or
ambiguous information stops the operation; no default volume is used.

The volume extent response is decoded only in a 64-bit Windows process; other
process architectures fail closed for disk-to-partition mapping. If Windows does
not return a complete storage descriptor, the selected disk's model is shown as
unavailable; analysis never depends on a particular model name.

Physical disks are enumerated using `SetupDiGetClassDevsW`,
`SetupDiEnumDeviceInterfaces`, `SetupDiGetDeviceInterfaceDetailW`, and
`SetupDiGetDeviceRegistryPropertyW` for the disk interface class and present
device properties. Physical disk numbers are taken from the PnP physical-device
object name or, when absent, from `IOCTL_STORAGE_GET_DEVICE_NUMBER` on the disk
interface. The latter uses a handle with zero desired access. The application
does not probe guessed or sequential device numbers. If raw `CreateFileW` access
is denied, the discovered disk stays visible with its available PnP model name
and an access-denied status; capacity, sector geometry, partition style, and
partition table remain unavailable unless Windows allowed those reads.

Raw physical-disk handles use `GENERIC_READ | FILE_SHARE_READ |
FILE_SHARE_WRITE`, `OPEN_EXISTING`, and zero flags. `ERROR_ACCESS_DENIED` is
reported with the exact path and CreateFile parameters. Raw physical-disk reads
commonly require an elevated administrator token, but access is governed by the
effective Windows token, device ACLs, policy, and drivers. The application
detects administrator-group membership and explains when an explicitly
administrator-launched restart may be needed; it never elevates itself.
Volume handles used only for `IOCTL_VOLUME_GET_VOLUME_DISK_EXTENTS` request
zero desired access with read/write/delete sharing and `OPEN_EXISTING`.
The confirmed NTFS query separately opens its volume with `GENERIC_READ`.
`ERROR_ACCESS_DENIED` does not identify whether the cause is a non-elevated
token, an ACL, policy, or a driver restriction. The UI displays the native error
and access parameters rather than treating administrator status as a guarantee
of access.

Findings are classified as **Information**, **Warning**, or **Error** and shown
without repair actions. Classification is a diagnostic display aid; it does not
change parsing or trigger any operation on the device.

Raw physical-disk data handles and the confirmed NTFS-query volume handle
request `GENERIC_READ`. Disk-interface number lookups and volume extent metadata
handles request zero desired access. Storage device descriptor results also
provide the removable-media flag when Windows returns it. Sharing flags do not
grant the application write access. The explicit device-control allowlist is
`IOCTL_STORAGE_GET_DEVICE_NUMBER` (zero desired access),
`IOCTL_DISK_GET_DRIVE_GEOMETRY_EX`, `IOCTL_DISK_GET_DRIVE_LAYOUT_EX`,
`IOCTL_STORAGE_QUERY_PROPERTY` (device descriptor and storage-alignment
properties), `IOCTL_VOLUME_GET_VOLUME_DISK_EXTENTS`, and (only after the
confirmed identity gate) the read-only `FSCTL_GET_NTFS_VOLUME_DATA`. Other
disk/volume controls and all write APIs are absent. There are no repair
operations, formatting, restoration, or automatic fixes. If access or parsing
fails, the operation fails closed.

The application itself issues no operation intended to modify an inspected
device. This does not guarantee that Windows, storage drivers, or device firmware
will never independently modify storage.

## Build and run

Use a JDK 21 installation:

```text
mvn test
mvn javafx:run
```
