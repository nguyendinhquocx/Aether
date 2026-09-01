import CryptoKit
import Darwin
import FileProvider
import Foundation
import SQLite3
import UniformTypeIdentifiers

private let sqliteTransient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)

enum FakeFSStoreError: Error {
    case invalidIdentifier
    case invalidFilename
    case missingItem
    case filenameCollision
    case directoryNotEmpty
    case database(String)
}

struct FakeFSStat: Equatable {
    static let directoryType: UInt32 = 0o040000
    static let regularType: UInt32 = 0o100000
    static let symbolicLinkType: UInt32 = 0o120000
    static let typeMask: UInt32 = 0o170000

    var mode: UInt32
    var uid: UInt32
    var gid: UInt32
    var device: UInt32

    var isDirectory: Bool { mode & Self.typeMask == Self.directoryType }
    var isSymbolicLink: Bool { mode & Self.typeMask == Self.symbolicLinkType }

    var data: Data {
        let words = [mode.littleEndian, uid.littleEndian, gid.littleEndian, device.littleEndian]
        return words.withUnsafeBytes { Data($0) }
    }

    init(mode: UInt32, uid: UInt32 = 0, gid: UInt32 = 0, device: UInt32 = 0) {
        self.mode = mode
        self.uid = uid
        self.gid = gid
        self.device = device
    }

    init?(data: Data) {
        guard data.count >= 16 else { return nil }
        let words = data.withUnsafeBytes { raw -> [UInt32] in
            (0..<4).map { offset in
                raw.loadUnaligned(fromByteOffset: offset * 4, as: UInt32.self).littleEndian
            }
        }
        self.init(mode: words[0], uid: words[1], gid: words[2], device: words[3])
    }
}

struct FakeFSItemRecord {
    let inode: Int64
    let path: String
    let stat: FakeFSStat
    let hostStat: stat
    let contentGeneration: Int64

    var identifier: NSFileProviderItemIdentifier {
        path.isEmpty ? .rootContainer : NSFileProviderItemIdentifier(String(format: "inode:%016llx", inode))
    }

    var parentPath: String {
        guard !path.isEmpty, let slash = path.lastIndex(of: "/") else { return "" }
        return String(path[..<slash])
    }

    var filename: String {
        path.isEmpty ? "Aether" : String(path[(path.lastIndex(of: "/") ?? path.startIndex)...].dropFirst())
    }

    var isDirectory: Bool { path.isEmpty || stat.isDirectory }

    var contentVersion: Data {
        let value: String
        if isDirectory {
            value = "directory:\(inode):\(contentGeneration)"
        } else {
            value = [
                "file", String(inode), String(hostStat.st_ino), String(hostStat.st_size),
                String(hostStat.st_mtimespec.tv_sec), String(hostStat.st_mtimespec.tv_nsec),
                String(contentGeneration),
            ].joined(separator: ":")
        }
        return Data(SHA256.hash(data: Data(value.utf8)))
    }

    var metadataVersion: Data {
        let value = [
            path, String(inode), String(stat.mode), String(stat.uid), String(stat.gid), String(stat.device),
            String(hostStat.st_ctimespec.tv_sec), String(hostStat.st_ctimespec.tv_nsec),
            String(hostStat.st_birthtimespec.tv_sec), String(hostStat.st_birthtimespec.tv_nsec),
        ].joined(separator: "\u{0}")
        return Data(SHA256.hash(data: Data(value.utf8)))
    }

    var version: NSFileProviderItemVersion {
        NSFileProviderItemVersion(contentVersion: contentVersion, metadataVersion: metadataVersion)
    }
}

struct FakeFSChangeBatch {
    let updated: [FakeFSItemRecord]
    let deleted: [NSFileProviderItemIdentifier]
    let anchor: NSFileProviderSyncAnchor
    let moreComing: Bool
}

final class FakeFSStore {
    static let appGroupIdentifier = "group.com.baimoqilin.aether"
    static let eventDatabaseName = "file-provider-events.sqlite"
    static let stateDatabaseName = "file-provider-state.sqlite"

    private static let eventBatchSize = 4_000
    private static let defaultChangeBatchSize = 200

    let rootURL: URL
    let fakeFSDatabaseURL: URL
    let stateDatabaseURL: URL
    let eventDatabaseURL: URL

    private let meta: SQLiteDatabase
    private let state: SQLiteDatabase
    private let events: SQLiteDatabase
    private let databaseIdentifier: String

    convenience init() throws {
        guard let container = FileManager.default.containerURL(
            forSecurityApplicationGroupIdentifier: Self.appGroupIdentifier
        ) else {
            throw CocoaError(.fileNoSuchFile)
        }
        let alpine = container.appendingPathComponent("AetherAlpine", isDirectory: true)
        try self.init(
            rootURL: alpine.appendingPathComponent("data", isDirectory: true),
            fakeFSDatabaseURL: alpine.appendingPathComponent("meta.db"),
            stateDatabaseURL: alpine.appendingPathComponent(Self.stateDatabaseName),
            eventDatabaseURL: alpine.appendingPathComponent(Self.eventDatabaseName)
        )
    }

    init(
        rootURL: URL,
        fakeFSDatabaseURL: URL,
        stateDatabaseURL: URL,
        eventDatabaseURL: URL
    ) throws {
        self.rootURL = rootURL.standardizedFileURL
        self.fakeFSDatabaseURL = fakeFSDatabaseURL
        self.stateDatabaseURL = stateDatabaseURL
        self.eventDatabaseURL = eventDatabaseURL
        meta = try SQLiteDatabase(url: fakeFSDatabaseURL, create: false)
        state = try SQLiteDatabase(url: stateDatabaseURL, create: true)
        events = try SQLiteDatabase(url: eventDatabaseURL, create: true)
        try Self.createStateSchema(in: state)
        try Self.createEventSchema(in: events)
        if let existing = try state.scalarText("SELECT value FROM settings WHERE key = 'database_identifier'") {
            databaseIdentifier = existing
        } else {
            databaseIdentifier = UUID().uuidString.lowercased()
            try state.execute(
                "INSERT INTO settings(key, value) VALUES('database_identifier', ?)",
                [.text(databaseIdentifier)]
            )
        }
    }

    func item(for identifier: NSFileProviderItemIdentifier) throws -> FakeFSItemRecord {
        try processExternalEvents()
        guard let item = try currentItem(for: identifier) else { throw FakeFSStoreError.missingItem }
        try remember(items: [item])
        return item
    }

    func itemIdentifier(forLinuxPath path: String) throws -> NSFileProviderItemIdentifier {
        guard let item = try currentItem(path: path) else { throw FakeFSStoreError.missingItem }
        return item.identifier
    }

    func children(of identifier: NSFileProviderItemIdentifier) throws -> [FakeFSItemRecord] {
        try childrenPage(of: identifier, offset: 0, limit: 1_000_000).items
    }

    func childrenPage(
        of identifier: NSFileProviderItemIdentifier,
        offset: Int,
        limit: Int
    ) throws -> (items: [FakeFSItemRecord], hasMore: Bool) {
        try processExternalEvents()
        guard let container = try currentItem(for: normalizedContainer(identifier)), container.isDirectory else {
            throw FakeFSStoreError.missingItem
        }
        let prefix = container.path + "/"
        let rows = try meta.query(
            """
            SELECT p.path, p.inode, s.stat
            FROM paths p JOIN stats s ON s.inode = p.inode
            WHERE substr(CAST(p.path AS TEXT), 1, length(?)) = ?
              AND instr(substr(CAST(p.path AS TEXT), length(?) + 1), '/') = 0
            ORDER BY CAST(p.path AS TEXT) COLLATE NOCASE, p.path
            LIMIT ? OFFSET ?
            """,
            [
                .text(prefix), .text(prefix), .text(prefix),
                .int64(Int64(limit + 1)), .int64(Int64(offset)),
            ]
        )
        var seen = Set<Int64>()
        let pageRows = Array(rows.prefix(limit))
        let children = try pageRows.compactMap { row -> FakeFSItemRecord? in
            guard
                let path = row.text(0),
                path != container.path,
                parentPath(of: path) == container.path,
                seen.insert(row.int64(1)).inserted,
                let fakeStat = row.data(2).flatMap(FakeFSStat.init(data:))
            else { return nil }
            return try makeRecord(inode: row.int64(1), path: path, fakeStat: fakeStat)
        }
        try state.transaction {
            try markEnumerated(container.identifier)
            try remember(items: children)
        }
        return (children, rows.count > limit)
    }

    func workingSet() throws -> [FakeFSItemRecord] {
        try workingSetPage(offset: 0, limit: 1_000_000).items
    }

    func workingSetPage(offset: Int, limit: Int) throws -> (items: [FakeFSItemRecord], hasMore: Bool) {
        try processExternalEvents()
        let rows = try state.query(
            "SELECT item_id FROM items ORDER BY path COLLATE NOCASE, path LIMIT ? OFFSET ?",
            [.int64(Int64(limit + 1)), .int64(Int64(offset))]
        )
        let identifiers = rows.prefix(limit).compactMap { $0.text(0) }
        let items = try identifiers.compactMap { raw in
            try currentItem(for: NSFileProviderItemIdentifier(raw))
        }
        return (items, rows.count > limit)
    }

    func fetchContents(
        for identifier: NSFileProviderItemIdentifier,
        requestedVersion: NSFileProviderItemVersion?,
        temporaryDirectory: URL
    ) throws -> (URL, FakeFSItemRecord) {
        try processExternalEvents()
        try FileManager.default.createDirectory(at: temporaryDirectory, withIntermediateDirectories: true)
        let output = temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: false)
        var item: FakeFSItemRecord?
        try meta.transaction(.immediate) {
            guard let lockedItem = try currentItem(for: identifier), !lockedItem.isDirectory else {
                throw FakeFSStoreError.missingItem
            }
            if let requestedVersion, !sameVersion(requestedVersion, lockedItem.version) {
                throw CocoaError(
                    .fileReadUnknown,
                    userInfo: [NSLocalizedDescriptionKey: "The requested file version is no longer available."]
                )
            }
            try FileManager.default.copyItem(at: hostURL(for: lockedItem.path), to: output)
            item = lockedItem
        }
        guard let item else { throw FakeFSStoreError.missingItem }
        try remember(items: [item])
        return (output, item)
    }

    func createItem(
        parentIdentifier: NSFileProviderItemIdentifier,
        filename: String,
        isDirectory: Bool,
        contents: URL?
    ) throws -> FakeFSItemRecord {
        try validate(filename: filename)
        guard let parent = try currentItem(for: normalizedContainer(parentIdentifier)), parent.isDirectory else {
            throw FakeFSStoreError.missingItem
        }
        var path = childPath(parent: parent.path, filename: filename)
        var destination = hostURL(for: path)
        let fakeStat = FakeFSStat(mode: (isDirectory ? FakeFSStat.directoryType | 0o755 : FakeFSStat.regularType | 0o644))
        var inode: Int64 = 0
        try meta.transaction(.immediate) {
            guard let lockedParent = try currentItem(for: normalizedContainer(parentIdentifier)),
                  lockedParent.inode == parent.inode,
                  lockedParent.isDirectory
            else { throw FakeFSStoreError.missingItem }
            path = childPath(parent: lockedParent.path, filename: filename)
            destination = hostURL(for: path)
            guard try meta.scalarInt64("SELECT inode FROM paths WHERE path = ?", [.blob(Data(path.utf8))]) == nil,
                  !FileManager.default.fileExists(atPath: destination.path)
            else { throw FakeFSStoreError.filenameCollision }
            if isDirectory {
                try FileManager.default.createDirectory(at: destination, withIntermediateDirectories: false)
            } else {
                let staged = destination.deletingLastPathComponent()
                    .appendingPathComponent(".aether-file-provider-\(UUID().uuidString)")
                do {
                    if let contents {
                        try FileManager.default.copyItem(at: contents, to: staged)
                    } else {
                        guard FileManager.default.createFile(atPath: staged.path, contents: Data()) else {
                            throw CocoaError(.fileWriteUnknown)
                        }
                    }
                    try FileManager.default.moveItem(at: staged, to: destination)
                } catch {
                    try? FileManager.default.removeItem(at: staged)
                    throw error
                }
            }
            do {
                try meta.execute("INSERT INTO stats(stat) VALUES(?)", [.blob(fakeStat.data)])
                inode = meta.lastInsertRowID
                try meta.execute("INSERT INTO paths(path, inode) VALUES(?, ?)", [.blob(Data(path.utf8)), .int64(inode)])
            } catch {
                try? FileManager.default.removeItem(at: destination)
                throw error
            }
        }
        guard let item = try currentItem(inode: inode) else { throw FakeFSStoreError.missingItem }
        try remember(items: [item])
        return item
    }

    func modifyItem(
        identifier: NSFileProviderItemIdentifier,
        parentIdentifier: NSFileProviderItemIdentifier,
        filename: String,
        baseVersion: NSFileProviderItemVersion,
        changedFields: NSFileProviderItemFields,
        newContents: URL?,
        fileSystemFlags: NSFileProviderFileSystemFlags?,
        contentModificationDate: Date?
    ) throws -> (item: FakeFSItemRecord, shouldFetchContent: Bool) {
        try processExternalEvents()
        guard let original = try currentItem(for: identifier) else { throw FakeFSStoreError.missingItem }
        var currentPath = original.path
        var conflictedItem: FakeFSItemRecord?
        var currentInode = original.inode
        try meta.transaction(.immediate) {
            guard let lockedItem = try currentItem(for: identifier) else { throw FakeFSStoreError.missingItem }
            currentPath = lockedItem.path
            currentInode = lockedItem.inode
            if !versionMatches(baseVersion, current: lockedItem.version, changedFields: changedFields) {
                conflictedItem = lockedItem
                return
            }
            if changedFields.contains(.parentItemIdentifier) || changedFields.contains(.filename) {
                try validate(filename: filename)
                guard let parent = try currentItem(for: normalizedContainer(parentIdentifier)), parent.isDirectory else {
                    throw FakeFSStoreError.missingItem
                }
                let destinationPath = childPath(parent: parent.path, filename: filename)
                if destinationPath != lockedItem.path {
                    guard try meta.scalarInt64("SELECT inode FROM paths WHERE path = ?", [.blob(Data(destinationPath.utf8))]) == nil,
                          !FileManager.default.fileExists(atPath: hostURL(for: destinationPath).path)
                    else { throw FakeFSStoreError.filenameCollision }
                    try renameMetadata(from: lockedItem.path, to: destinationPath)
                    do {
                        try FileManager.default.moveItem(at: hostURL(for: lockedItem.path), to: hostURL(for: destinationPath))
                    } catch {
                        throw error
                    }
                    currentPath = destinationPath
                }
            }

            if changedFields.contains(.contents), let newContents, !lockedItem.isDirectory {
                let destination = hostURL(for: currentPath)
                let staged = destination.deletingLastPathComponent()
                    .appendingPathComponent(".aether-file-provider-\(UUID().uuidString)")
                try FileManager.default.copyItem(at: newContents, to: staged)
                do {
                    _ = try FileManager.default.replaceItemAt(destination, withItemAt: staged)
                } catch {
                    try? FileManager.default.removeItem(at: staged)
                    throw error
                }
            }

            if changedFields.contains(.fileSystemFlags), let fileSystemFlags {
                var updated = lockedItem.stat
                updated.mode &= ~UInt32(0o700)
                if fileSystemFlags.contains(.userReadable) { updated.mode |= 0o400 }
                if fileSystemFlags.contains(.userWritable) { updated.mode |= 0o200 }
                if fileSystemFlags.contains(.userExecutable) { updated.mode |= 0o100 }
                try meta.execute("UPDATE stats SET stat = ? WHERE inode = ?", [.blob(updated.data), .int64(lockedItem.inode)])
            }
            if changedFields.contains(.contentModificationDate), let contentModificationDate {
                try FileManager.default.setAttributes(
                    [.modificationDate: contentModificationDate],
                    ofItemAtPath: hostURL(for: currentPath).path
                )
            }
        }
        if let conflictedItem {
            return (conflictedItem, baseVersion.contentVersion != conflictedItem.contentVersion)
        }
        guard let item = try currentItem(inode: currentInode) else { throw FakeFSStoreError.missingItem }
        try remember(items: [item])
        return (item, false)
    }

    func deleteItem(
        identifier: NSFileProviderItemIdentifier,
        baseVersion: NSFileProviderItemVersion,
        recursive: Bool
    ) throws {
        try processExternalEvents()
        guard identifier != .rootContainer else { throw CocoaError(.fileWriteNoPermission) }
        var deletedItem: FakeFSItemRecord?
        try meta.transaction(.immediate) {
            guard let lockedItem = try currentItem(for: identifier) else { return }
            guard sameVersion(baseVersion, lockedItem.version) else {
                throw NSFileProviderError(.deletionRejected)
            }
            if lockedItem.isDirectory, !recursive, try hasChildren(lockedItem.path) {
                throw FakeFSStoreError.directoryNotEmpty
            }
            try deleteMetadata(path: lockedItem.path, recursive: recursive)
            try FileManager.default.removeItem(at: hostURL(for: lockedItem.path))
            deletedItem = lockedItem
        }
        guard let item = deletedItem else { return }
        let prefix = item.path + "/"
        try state.execute("DELETE FROM items WHERE item_id = ? OR path = ? OR substr(path, 1, length(?)) = ?", [
            .text(identifier.rawValue), .text(item.path), .text(prefix), .text(prefix),
        ])
    }

    func currentAnchor() throws -> NSFileProviderSyncAnchor {
        try processExternalEvents()
        return try anchor(sequence: latestRevision())
    }

    func changes(
        from anchor: NSFileProviderSyncAnchor,
        suggestedBatchSize: Int
    ) throws -> FakeFSChangeBatch {
        let startingSequence = try sequence(from: anchor)
        let hasMoreEvents = try processExternalEvents()
        let limit = max(1, min(suggestedBatchSize > 0 ? suggestedBatchSize : Self.defaultChangeBatchSize, 2_000))
        let rows = try state.query(
            "SELECT revision, item_id, deleted FROM changes WHERE revision > ? ORDER BY revision, item_id LIMIT ?",
            [.int64(startingSequence), .int64(Int64(limit + 1))]
        )
        let selected = Array(rows.prefix(limit))
        var endSequence = startingSequence
        var updates: [FakeFSItemRecord] = []
        var deletions: [NSFileProviderItemIdentifier] = []
        var seen = Set<String>()
        for row in selected.reversed() {
            let itemID = row.text(1) ?? ""
            guard seen.insert(itemID).inserted else { continue }
            endSequence = max(endSequence, row.int64(0))
            let identifier = NSFileProviderItemIdentifier(itemID)
            if row.int64(2) != 0 {
                deletions.append(identifier)
            } else if let item = try currentItem(for: identifier) {
                updates.append(item)
            } else {
                deletions.append(identifier)
            }
        }
        if selected.isEmpty {
            endSequence = try latestRevision()
        }
        let latest = try latestRevision()
        return FakeFSChangeBatch(
            updated: Array(updates.reversed()),
            deleted: Array(deletions.reversed()),
            anchor: try self.anchor(sequence: endSequence),
            moreComing: hasMoreEvents || rows.count > limit || endSequence < latest
        )
    }

    private func currentItem(for identifier: NSFileProviderItemIdentifier) throws -> FakeFSItemRecord? {
        if identifier == .rootContainer || identifier == .workingSet { return try currentItem(path: "") }
        guard let inode = inode(from: identifier) else { throw FakeFSStoreError.invalidIdentifier }
        return try currentItem(inode: inode)
    }

    private func currentItem(inode: Int64) throws -> FakeFSItemRecord? {
        let rows = try meta.query(
            "SELECT p.path, s.stat FROM paths p JOIN stats s ON s.inode = p.inode WHERE p.inode = ? ORDER BY p.path LIMIT 1",
            [.int64(inode)]
        )
        guard let row = rows.first, let path = row.text(0), let data = row.data(1), let fakeStat = FakeFSStat(data: data) else {
            return nil
        }
        return try makeRecord(inode: inode, path: path, fakeStat: fakeStat)
    }

    private func currentItem(path: String) throws -> FakeFSItemRecord? {
        let rows = try meta.query(
            "SELECT p.inode, s.stat FROM paths p JOIN stats s ON s.inode = p.inode WHERE p.path = ? LIMIT 1",
            [.blob(Data(path.utf8))]
        )
        guard let row = rows.first, let data = row.data(1), let fakeStat = FakeFSStat(data: data) else { return nil }
        return try makeRecord(inode: row.int64(0), path: path, fakeStat: fakeStat)
    }

    private func makeRecord(inode: Int64, path: String, fakeStat: FakeFSStat) throws -> FakeFSItemRecord? {
        var host = stat()
        guard lstat(hostURL(for: path).path, &host) == 0 else { return nil }
        let generation = try state.scalarInt64(
            "SELECT content_generation FROM items WHERE item_id = ?",
            [.text(NSFileProviderItemIdentifier(String(format: "inode:%016llx", inode)).rawValue)]
        ) ?? 0
        return FakeFSItemRecord(inode: inode, path: path, stat: fakeStat, hostStat: host, contentGeneration: generation)
    }

    @discardableResult
    private func processExternalEvents() throws -> Bool {
        let lastEvent = Int64(
            try state.scalarText("SELECT value FROM settings WHERE key = 'last_external_event'") ?? "0"
        ) ?? 0
        let eventRows = try events.query(
            "SELECT sequence, path, operation FROM events WHERE sequence > ? ORDER BY sequence LIMIT ?",
            [.int64(lastEvent), .int64(Int64(Self.eventBatchSize + 1))]
        )
        guard !eventRows.isEmpty else { return false }
        let batch = Array(eventRows.prefix(Self.eventBatchSize))
        let hasMore = eventRows.count > Self.eventBatchSize
        var candidateIdentifiers = Set<String>()
        var forcedContentGeneration: [String: Int64] = [:]

        for row in batch {
            let sequence = row.int64(0)
            guard let path = normalizeLinuxPath(row.text(1) ?? "") else { continue }
            let operation = row.int64(2)
            if operation == 99 {
                let allKnown = try state.query("SELECT item_id FROM items", [])
                allKnown.compactMap { $0.text(0) }.forEach { candidateIdentifiers.insert($0) }
                let allPaths = try meta.query(
                    "SELECT p.path, p.inode, s.stat FROM paths p JOIN stats s ON s.inode = p.inode",
                    []
                )
                for pathRow in allPaths {
                    guard
                        let currentPath = pathRow.text(0),
                        let fakeStatData = pathRow.data(2),
                        let fakeStat = FakeFSStat(data: fakeStatData),
                        let current = try makeRecord(inode: pathRow.int64(1), path: currentPath, fakeStat: fakeStat)
                    else { continue }
                    let parentID = try parentIdentifier(for: current.path).rawValue
                    if try state.scalarInt64(
                        "SELECT 1 FROM enumerated_containers WHERE item_id = ?",
                        [.text(parentID)]
                    ) != nil {
                        candidateIdentifiers.insert(current.identifier.rawValue)
                    }
                }
                continue
            }
            let prefix = path + "/"
            let known = try state.query(
                "SELECT item_id FROM items WHERE path = ? OR substr(path, 1, length(?)) = ?",
                [.text(path), .text(prefix), .text(prefix)]
            )
            known.compactMap { $0.text(0) }.forEach { candidateIdentifiers.insert($0) }
            if let current = try currentItem(path: path) {
                let parentID = try parentIdentifier(for: current.path).rawValue
                let parentKnown = try state.scalarInt64(
                    "SELECT 1 FROM enumerated_containers WHERE item_id = ?",
                    [.text(parentID)]
                ) != nil
                if candidateIdentifiers.contains(current.identifier.rawValue) || parentKnown {
                    candidateIdentifiers.insert(current.identifier.rawValue)
                    if operation == 0 || operation == 3 {
                        forcedContentGeneration[current.identifier.rawValue] = sequence
                    }
                }
            }
        }

        let maxEvent = batch.last?.int64(0) ?? lastEvent
        try state.transaction {
            var createdRevision = false
            for itemID in candidateIdentifiers.sorted() {
                let identifier = NSFileProviderItemIdentifier(itemID)
                if var current = try currentItem(for: identifier) {
                    if let generation = forcedContentGeneration[itemID] {
                        current = FakeFSItemRecord(
                            inode: current.inode,
                            path: current.path,
                            stat: current.stat,
                            hostStat: current.hostStat,
                            contentGeneration: generation
                        )
                    }
                    let changed = try itemChanged(current)
                    try remember(items: [current])
                    if changed {
                        let revision = try createRevision(externalSequence: maxEvent)
                        try appendChange(revision: revision, identifier: identifier, deleted: false)
                        createdRevision = true
                    }
                } else if try state.scalarInt64("SELECT 1 FROM items WHERE item_id = ?", [.text(itemID)]) != nil {
                    try state.execute("DELETE FROM items WHERE item_id = ?", [.text(itemID)])
                    let revision = try createRevision(externalSequence: maxEvent)
                    try appendChange(revision: revision, identifier: identifier, deleted: true)
                    createdRevision = true
                }
            }
            if !createdRevision {
                _ = try createRevision(externalSequence: maxEvent)
            }
            try setSetting("last_external_event", value: String(maxEvent))
        }
        try events.execute("DELETE FROM events WHERE sequence <= ?", [.int64(maxEvent)])
        return hasMore
    }

    private func remember(items: [FakeFSItemRecord]) throws {
        for item in items {
            let parentID = try parentIdentifier(for: item.path).rawValue
            try state.execute(
                """
                INSERT INTO items(item_id, inode, path, parent_id, content_version, metadata_version, content_generation)
                VALUES(?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(item_id) DO UPDATE SET
                    inode=excluded.inode, path=excluded.path, parent_id=excluded.parent_id,
                    content_version=excluded.content_version, metadata_version=excluded.metadata_version,
                    content_generation=excluded.content_generation
                """,
                [
                    .text(item.identifier.rawValue), .int64(item.inode), .text(item.path), .text(parentID),
                    .blob(item.contentVersion), .blob(item.metadataVersion), .int64(item.contentGeneration),
                ]
            )
        }
    }

    private func itemChanged(_ item: FakeFSItemRecord) throws -> Bool {
        let rows = try state.query(
            "SELECT path, content_version, metadata_version FROM items WHERE item_id = ?",
            [.text(item.identifier.rawValue)]
        )
        guard let row = rows.first else { return true }
        return row.text(0) != item.path || row.data(1) != item.contentVersion || row.data(2) != item.metadataVersion
    }

    private func parentIdentifier(for path: String) throws -> NSFileProviderItemIdentifier {
        let parent = parentPath(of: path)
        if parent.isEmpty { return .rootContainer }
        guard let item = try currentItem(path: parent) else { throw FakeFSStoreError.missingItem }
        return item.identifier
    }

    private func normalizedContainer(_ identifier: NSFileProviderItemIdentifier) -> NSFileProviderItemIdentifier {
        identifier == .workingSet ? .rootContainer : identifier
    }

    private func inode(from identifier: NSFileProviderItemIdentifier) -> Int64? {
        guard identifier.rawValue.hasPrefix("inode:"), identifier.rawValue.count == 22 else { return nil }
        return Int64(identifier.rawValue.dropFirst(6), radix: 16)
    }

    private func hostURL(for linuxPath: String) -> URL {
        guard !linuxPath.isEmpty else { return rootURL }
        return rootURL.appendingPathComponent(String(linuxPath.dropFirst()), isDirectory: false).standardizedFileURL
    }

    private func childPath(parent: String, filename: String) -> String {
        parent.isEmpty ? "/\(filename)" : "\(parent)/\(filename)"
    }

    private func parentPath(of path: String) -> String {
        guard !path.isEmpty, let slash = path.lastIndex(of: "/"), slash != path.startIndex else { return "" }
        return String(path[..<slash])
    }

    private func normalizeLinuxPath(_ path: String) -> String? {
        guard path.isEmpty || path.hasPrefix("/") else { return "/" + path }
        guard !path.split(separator: "/").contains("..") else { return nil }
        return path == "/" ? "" : path
    }

    private func validate(filename: String) throws {
        guard !filename.isEmpty, filename != ".", filename != "..", !filename.contains("/"), !filename.contains("\u{0}") else {
            throw FakeFSStoreError.invalidFilename
        }
    }

    private func hasChildren(_ path: String) throws -> Bool {
        try meta.scalarInt64(
            "SELECT 1 FROM paths WHERE substr(CAST(path AS TEXT), 1, length(?)) = ? LIMIT 1",
            [.text(path + "/"), .text(path + "/")]
        ) != nil
    }

    private func renameMetadata(from source: String, to destination: String) throws {
        let rows = try meta.query(
            "SELECT path, inode FROM paths WHERE path = ? OR substr(CAST(path AS TEXT), 1, length(?)) = ? ORDER BY length(path)",
            [.blob(Data(source.utf8)), .text(source + "/"), .text(source + "/")]
        )
        for row in rows {
            guard let oldPath = row.text(0) else { continue }
            let suffix = oldPath.dropFirst(source.count)
            try meta.execute(
                "UPDATE paths SET path = ? WHERE path = ? AND inode = ?",
                [.blob(Data((destination + suffix).utf8)), .blob(Data(oldPath.utf8)), .int64(row.int64(1))]
            )
        }
    }

    private func deleteMetadata(path: String, recursive: Bool) throws {
        if recursive {
            try meta.execute(
                "DELETE FROM paths WHERE path = ? OR substr(CAST(path AS TEXT), 1, length(?)) = ?",
                [.blob(Data(path.utf8)), .text(path + "/"), .text(path + "/")]
            )
        } else {
            try meta.execute("DELETE FROM paths WHERE path = ?", [.blob(Data(path.utf8))])
        }
        try meta.execute("DELETE FROM stats WHERE NOT EXISTS (SELECT 1 FROM paths WHERE paths.inode = stats.inode)")
    }

    private func markEnumerated(_ identifier: NSFileProviderItemIdentifier) throws {
        try state.execute(
            "INSERT OR IGNORE INTO enumerated_containers(item_id) VALUES(?)",
            [.text(identifier.rawValue)]
        )
    }

    private func createRevision(externalSequence: Int64) throws -> Int64 {
        try state.execute("INSERT INTO revisions(external_sequence) VALUES(?)", [.int64(externalSequence)])
        return state.lastInsertRowID
    }

    private func appendChange(revision: Int64, identifier: NSFileProviderItemIdentifier, deleted: Bool) throws {
        try state.execute(
            "INSERT OR REPLACE INTO changes(revision, item_id, deleted) VALUES(?, ?, ?)",
            [.int64(revision), .text(identifier.rawValue), .int64(deleted ? 1 : 0)]
        )
    }

    private func latestRevision() throws -> Int64 {
        try state.scalarInt64("SELECT max(sequence) FROM revisions") ?? 0
    }

    private func setSetting(_ key: String, value: String) throws {
        try state.execute(
            "INSERT INTO settings(key, value) VALUES(?, ?) ON CONFLICT(key) DO UPDATE SET value=excluded.value",
            [.text(key), .text(value)]
        )
    }

    private func anchor(sequence: Int64) throws -> NSFileProviderSyncAnchor {
        NSFileProviderSyncAnchor(Data("\(databaseIdentifier):\(sequence)".utf8))
    }

    private func sequence(from anchor: NSFileProviderSyncAnchor) throws -> Int64 {
        guard
            let value = String(data: anchor.rawValue, encoding: .utf8),
            let separator = value.lastIndex(of: ":"),
            String(value[..<separator]) == databaseIdentifier,
            let sequence = Int64(value[value.index(after: separator)...])
        else { throw NSFileProviderError(.syncAnchorExpired) }
        return sequence
    }

    private func sameVersion(_ lhs: NSFileProviderItemVersion, _ rhs: NSFileProviderItemVersion) -> Bool {
        lhs.contentVersion == rhs.contentVersion && lhs.metadataVersion == rhs.metadataVersion
    }

    private func versionMatches(
        _ base: NSFileProviderItemVersion,
        current: NSFileProviderItemVersion,
        changedFields: NSFileProviderItemFields
    ) -> Bool {
        let beforeFirst = NSFileProviderItemVersion.beforeFirstSyncComponent
        if changedFields.contains(.contents),
           base.contentVersion != beforeFirst,
           base.contentVersion != current.contentVersion { return false }
        let metadataFields = changedFields.subtracting(.contents)
        if !metadataFields.isEmpty,
           base.metadataVersion != beforeFirst,
           base.metadataVersion != current.metadataVersion { return false }
        return true
    }

    private static func createStateSchema(in database: SQLiteDatabase) throws {
        try database.execute("PRAGMA journal_mode=WAL")
        try database.execute("""
            CREATE TABLE IF NOT EXISTS settings(key TEXT PRIMARY KEY, value TEXT NOT NULL);
            """)
        try database.execute("""
            CREATE TABLE IF NOT EXISTS items(
                item_id TEXT PRIMARY KEY,
                inode INTEGER NOT NULL,
                path TEXT NOT NULL,
                parent_id TEXT NOT NULL,
                content_version BLOB NOT NULL,
                metadata_version BLOB NOT NULL,
                content_generation INTEGER NOT NULL DEFAULT 0
            );
            """)
        try database.execute("CREATE INDEX IF NOT EXISTS items_path ON items(path)")
        try database.execute("CREATE TABLE IF NOT EXISTS enumerated_containers(item_id TEXT PRIMARY KEY)")
        try database.execute("""
            CREATE TABLE IF NOT EXISTS revisions(
                sequence INTEGER PRIMARY KEY AUTOINCREMENT,
                external_sequence INTEGER NOT NULL
            );
            """)
        try database.execute("""
            CREATE TABLE IF NOT EXISTS changes(
                revision INTEGER NOT NULL,
                item_id TEXT NOT NULL,
                deleted INTEGER NOT NULL,
                PRIMARY KEY(revision, item_id)
            );
            """)
    }

    private static func createEventSchema(in database: SQLiteDatabase) throws {
        try database.execute("PRAGMA journal_mode=WAL")
        try database.execute("""
            CREATE TABLE IF NOT EXISTS events(
                sequence INTEGER PRIMARY KEY AUTOINCREMENT,
                path TEXT NOT NULL,
                operation INTEGER NOT NULL
            );
            """)
    }
}

private enum SQLiteValue {
    case int64(Int64)
    case text(String)
    case blob(Data)
}

private struct SQLiteRow {
    let values: [SQLiteColumn]

    func int64(_ index: Int) -> Int64 { values[index].int64Value ?? 0 }
    func text(_ index: Int) -> String? { values[index].textValue }
    func data(_ index: Int) -> Data? { values[index].dataValue }
}

private enum SQLiteColumn {
    case null
    case int64(Int64)
    case text(String)
    case blob(Data)

    var int64Value: Int64? { if case .int64(let value) = self { return value }; return nil }
    var textValue: String? {
        switch self {
        case .text(let value): return value
        case .blob(let data): return String(data: data, encoding: .utf8)
        default: return nil
        }
    }
    var dataValue: Data? {
        switch self {
        case .blob(let value): return value
        case .text(let value): return Data(value.utf8)
        default: return nil
        }
    }
}

private final class SQLiteDatabase {
    enum TransactionKind { case deferred, immediate }

    private var handle: OpaquePointer?
    var lastInsertRowID: Int64 { sqlite3_last_insert_rowid(handle) }

    init(url: URL, create: Bool) throws {
        let flags = SQLITE_OPEN_READWRITE | SQLITE_OPEN_FULLMUTEX | (create ? SQLITE_OPEN_CREATE : 0)
        guard sqlite3_open_v2(url.path, &handle, flags, nil) == SQLITE_OK else {
            let message = handle.map { String(cString: sqlite3_errmsg($0)) } ?? "Unable to open SQLite database"
            if let handle { sqlite3_close(handle) }
            throw FakeFSStoreError.database(message)
        }
        sqlite3_busy_timeout(handle, 5_000)
    }

    deinit { if let handle { sqlite3_close(handle) } }

    func execute(_ sql: String, _ values: [SQLiteValue] = []) throws {
        let statement = try prepare(sql)
        defer { sqlite3_finalize(statement) }
        try bind(values, to: statement)
        while true {
            let result = sqlite3_step(statement)
            if result == SQLITE_DONE { return }
            guard result == SQLITE_ROW else { throw error() }
        }
    }

    func query(_ sql: String, _ values: [SQLiteValue] = []) throws -> [SQLiteRow] {
        let statement = try prepare(sql)
        defer { sqlite3_finalize(statement) }
        try bind(values, to: statement)
        var rows: [SQLiteRow] = []
        while true {
            let result = sqlite3_step(statement)
            if result == SQLITE_DONE { return rows }
            guard result == SQLITE_ROW else { throw error() }
            var columns: [SQLiteColumn] = []
            for index in 0..<sqlite3_column_count(statement) {
                switch sqlite3_column_type(statement, index) {
                case SQLITE_INTEGER:
                    columns.append(.int64(sqlite3_column_int64(statement, index)))
                case SQLITE_TEXT:
                    columns.append(.text(String(cString: sqlite3_column_text(statement, index))))
                case SQLITE_BLOB:
                    let count = Int(sqlite3_column_bytes(statement, index))
                    if count == 0 { columns.append(.blob(Data())) }
                    else { columns.append(.blob(Data(bytes: sqlite3_column_blob(statement, index), count: count))) }
                default:
                    columns.append(.null)
                }
            }
            rows.append(SQLiteRow(values: columns))
        }
    }

    func scalarInt64(_ sql: String, _ values: [SQLiteValue] = []) throws -> Int64? {
        try query(sql, values).first?.values.first?.int64Value
    }

    func scalarText(_ sql: String, _ values: [SQLiteValue] = []) throws -> String? {
        try query(sql, values).first?.values.first?.textValue
    }

    func transaction<T>(_ kind: TransactionKind = .deferred, _ body: () throws -> T) throws -> T {
        try execute(kind == .immediate ? "BEGIN IMMEDIATE" : "BEGIN")
        do {
            let result = try body()
            try execute("COMMIT")
            return result
        } catch {
            try? execute("ROLLBACK")
            throw error
        }
    }

    private func prepare(_ sql: String) throws -> OpaquePointer {
        var statement: OpaquePointer?
        guard sqlite3_prepare_v2(handle, sql, -1, &statement, nil) == SQLITE_OK, let statement else {
            throw error()
        }
        return statement
    }

    private func bind(_ values: [SQLiteValue], to statement: OpaquePointer) throws {
        for (offset, value) in values.enumerated() {
            let index = Int32(offset + 1)
            let result: Int32
            switch value {
            case .int64(let value):
                result = sqlite3_bind_int64(statement, index, value)
            case .text(let value):
                result = sqlite3_bind_text(statement, index, value, -1, sqliteTransient)
            case .blob(let data):
                result = data.withUnsafeBytes { raw in
                    sqlite3_bind_blob(statement, index, raw.baseAddress, Int32(raw.count), sqliteTransient)
                }
            }
            guard result == SQLITE_OK else { throw error() }
        }
    }

    private func error() -> FakeFSStoreError {
        FakeFSStoreError.database(String(cString: sqlite3_errmsg(handle)))
    }
}
