import FileProvider
import Foundation
import UniformTypeIdentifiers

final class FileProviderExtension: NSObject, NSFileProviderReplicatedExtension {
    private let manager: NSFileProviderManager
    private let queue = DispatchQueue(label: "com.baimoqilin.aether.file-provider", qos: .utility)
    private var store: FakeFSStore?
    private var startupError: Error?

    required init(domain: NSFileProviderDomain) {
        guard let manager = NSFileProviderManager(for: domain) else {
            fatalError("Aether File Provider could not create its domain manager.")
        }
        self.manager = manager
        do {
            store = try FakeFSStore()
        } catch {
            startupError = error
        }
        super.init()
    }

    func invalidate() {}

    func item(
        for identifier: NSFileProviderItemIdentifier,
        request: NSFileProviderRequest,
        completionHandler: @escaping (NSFileProviderItem?, Error?) -> Void
    ) -> Progress {
        perform(completionHandler: completionHandler) { store in
            FileProviderItem(record: try store.item(for: identifier), store: store)
        }
    }

    func fetchContents(
        for itemIdentifier: NSFileProviderItemIdentifier,
        version requestedVersion: NSFileProviderItemVersion?,
        request: NSFileProviderRequest,
        completionHandler: @escaping (URL?, NSFileProviderItem?, Error?) -> Void
    ) -> Progress {
        let progress = Progress(totalUnitCount: 1)
        queue.async { [weak self] in
            guard let self else { return }
            do {
                let store = try requireStore()
                let temporaryDirectory = try manager.temporaryDirectoryURL()
                let (url, record) = try store.fetchContents(
                    for: itemIdentifier,
                    requestedVersion: requestedVersion,
                    temporaryDirectory: temporaryDirectory
                )
                progress.completedUnitCount = 1
                completionHandler(url, FileProviderItem(record: record, store: store), nil)
            } catch {
                completionHandler(nil, nil, providerError(error))
            }
        }
        return progress
    }

    func createItem(
        basedOn itemTemplate: NSFileProviderItem,
        fields: NSFileProviderItemFields,
        contents url: URL?,
        options: NSFileProviderCreateItemOptions = [],
        request: NSFileProviderRequest,
        completionHandler: @escaping (NSFileProviderItem?, NSFileProviderItemFields, Bool, Error?) -> Void
    ) -> Progress {
        let progress = Progress(totalUnitCount: 1)
        queue.async { [weak self] in
            guard let self else { return }
            do {
                let store = try requireStore()
                let record = try store.createItem(
                    parentIdentifier: itemTemplate.parentItemIdentifier,
                    filename: itemTemplate.filename,
                    isDirectory: itemTemplate.contentType?.conforms(to: .folder) == true,
                    contents: url
                )
                progress.completedUnitCount = 1
                completionHandler(FileProviderItem(record: record, store: store), [], false, nil)
            } catch {
                completionHandler(nil, fields, false, providerError(error))
            }
        }
        return progress
    }

    func modifyItem(
        _ item: NSFileProviderItem,
        baseVersion version: NSFileProviderItemVersion,
        changedFields: NSFileProviderItemFields,
        contents newContents: URL?,
        options: NSFileProviderModifyItemOptions = [],
        request: NSFileProviderRequest,
        completionHandler: @escaping (NSFileProviderItem?, NSFileProviderItemFields, Bool, Error?) -> Void
    ) -> Progress {
        let progress = Progress(totalUnitCount: 1)
        queue.async { [weak self] in
            guard let self else { return }
            do {
                let store = try requireStore()
                let result = try store.modifyItem(
                    identifier: item.itemIdentifier,
                    parentIdentifier: item.parentItemIdentifier,
                    filename: item.filename,
                    baseVersion: version,
                    changedFields: changedFields,
                    newContents: newContents,
                    fileSystemFlags: item.fileSystemFlags,
                    contentModificationDate: item.contentModificationDate ?? nil
                )
                progress.completedUnitCount = 1
                completionHandler(
                    FileProviderItem(record: result.item, store: store),
                    [],
                    result.shouldFetchContent,
                    nil
                )
            } catch {
                completionHandler(nil, changedFields, false, providerError(error))
            }
        }
        return progress
    }

    func deleteItem(
        identifier: NSFileProviderItemIdentifier,
        baseVersion version: NSFileProviderItemVersion,
        options: NSFileProviderDeleteItemOptions = [],
        request: NSFileProviderRequest,
        completionHandler: @escaping (Error?) -> Void
    ) -> Progress {
        let progress = Progress(totalUnitCount: 1)
        queue.async { [weak self] in
            guard let self else { return }
            do {
                try requireStore().deleteItem(
                    identifier: identifier,
                    baseVersion: version,
                    recursive: options.contains(.recursive)
                )
                progress.completedUnitCount = 1
                completionHandler(nil)
            } catch {
                completionHandler(providerError(error))
            }
        }
        return progress
    }

    func enumerator(
        for containerItemIdentifier: NSFileProviderItemIdentifier,
        request: NSFileProviderRequest
    ) throws -> NSFileProviderEnumerator {
        FileProviderEnumerator(
            identifier: containerItemIdentifier,
            queue: queue,
            store: try requireStore()
        )
    }

    private func requireStore() throws -> FakeFSStore {
        if let store { return store }
        throw startupError ?? CocoaError(.fileNoSuchFile)
    }

    private func perform<T>(
        completionHandler: @escaping (T?, Error?) -> Void,
        operation: @escaping (FakeFSStore) throws -> T
    ) -> Progress {
        let progress = Progress(totalUnitCount: 1)
        queue.async { [weak self] in
            guard let self else { return }
            do {
                let result = try operation(try requireStore())
                progress.completedUnitCount = 1
                completionHandler(result, nil)
            } catch {
                completionHandler(nil, providerError(error))
            }
        }
        return progress
    }
}

final class FileProviderEnumerator: NSObject, NSFileProviderEnumerator {
    private let identifier: NSFileProviderItemIdentifier
    private let queue: DispatchQueue
    private let store: FakeFSStore

    init(identifier: NSFileProviderItemIdentifier, queue: DispatchQueue, store: FakeFSStore) {
        self.identifier = identifier
        self.queue = queue
        self.store = store
        super.init()
    }

    func enumerateItems(
        for observer: NSFileProviderEnumerationObserver,
        startingAt page: NSFileProviderPage
    ) {
        queue.async { [weak self] in
            guard let self else { return }
            do {
                let offset = Self.offset(from: page)
                let suggestedPageSize = observer.suggestedPageSize ?? 200
                let pageSize = max(1, min(suggestedPageSize > 0 ? suggestedPageSize : 200, 2_000))
                let result: (items: [FakeFSItemRecord], hasMore: Bool)
                if identifier == .trashContainer {
                    result = ([], false)
                } else if identifier == .workingSet {
                    result = try store.workingSetPage(offset: offset, limit: pageSize)
                } else {
                    result = try store.childrenPage(of: identifier, offset: offset, limit: pageSize)
                }
                if !result.items.isEmpty {
                    observer.didEnumerate(result.items.map { FileProviderItem(record: $0, store: self.store) })
                }
                let nextOffset = offset + pageSize
                let nextPage = result.hasMore ? NSFileProviderPage(Data(String(nextOffset).utf8)) : nil
                observer.finishEnumerating(upTo: nextPage)
            } catch {
                observer.finishEnumeratingWithError(providerError(error))
            }
        }
    }

    func enumerateChanges(
        for observer: NSFileProviderChangeObserver,
        from anchor: NSFileProviderSyncAnchor
    ) {
        queue.async { [store] in
            do {
                let batch = try store.changes(from: anchor, suggestedBatchSize: observer.suggestedBatchSize ?? 200)
                if !batch.updated.isEmpty {
                    observer.didUpdate(batch.updated.map { FileProviderItem(record: $0, store: store) })
                }
                if !batch.deleted.isEmpty {
                    observer.didDeleteItems(withIdentifiers: batch.deleted)
                }
                observer.finishEnumeratingChanges(upTo: batch.anchor, moreComing: batch.moreComing)
            } catch {
                observer.finishEnumeratingWithError(providerError(error))
            }
        }
    }

    func currentSyncAnchor(completionHandler: @escaping (NSFileProviderSyncAnchor?) -> Void) {
        queue.async { [store] in
            completionHandler(try? store.currentAnchor())
        }
    }

    func invalidate() {}

    private static func offset(from page: NSFileProviderPage) -> Int {
        Int(String(data: page.rawValue, encoding: .utf8) ?? "") ?? 0
    }
}

final class FileProviderItem: NSObject, NSFileProviderItem {
    private let record: FakeFSItemRecord
    private let parentIdentifierValue: NSFileProviderItemIdentifier

    init(record: FakeFSItemRecord, store: FakeFSStore) {
        self.record = record
        if record.path.isEmpty || record.parentPath.isEmpty {
            parentIdentifierValue = .rootContainer
        } else {
            parentIdentifierValue = (try? store.itemIdentifier(forLinuxPath: record.parentPath)) ?? .rootContainer
        }
        super.init()
    }

    var itemIdentifier: NSFileProviderItemIdentifier { record.identifier }
    var parentItemIdentifier: NSFileProviderItemIdentifier { parentIdentifierValue }
    var filename: String { record.filename }

    var contentType: UTType {
        if record.isDirectory { return .folder }
        if record.stat.isSymbolicLink { return .symbolicLink }
        return UTType(filenameExtension: (record.filename as NSString).pathExtension) ?? .data
    }

    var capabilities: NSFileProviderItemCapabilities {
        let writable = record.stat.mode & 0o200 != 0
        if itemIdentifier == .rootContainer {
            return writable ? [.allowsContentEnumerating, .allowsAddingSubItems] : [.allowsContentEnumerating]
        }
        var result: NSFileProviderItemCapabilities = [.allowsReading, .allowsEvicting]
        if writable { result.insert(.allowsWriting) }
        result.formUnion([.allowsRenaming, .allowsReparenting, .allowsDeleting])
        return result
    }

    var contentPolicy: NSFileProviderContentPolicy { .downloadLazilyAndEvictOnRemoteUpdate }
    var itemVersion: NSFileProviderItemVersion { record.version }

    var fileSystemFlags: NSFileProviderFileSystemFlags {
        var flags: NSFileProviderFileSystemFlags = []
        if record.stat.mode & 0o400 != 0 { flags.insert(.userReadable) }
        if record.stat.mode & 0o200 != 0 { flags.insert(.userWritable) }
        if record.stat.mode & 0o100 != 0 { flags.insert(.userExecutable) }
        return flags
    }

    var documentSize: NSNumber? { record.isDirectory ? nil : NSNumber(value: record.hostStat.st_size) }
    var creationDate: Date? {
        Date(timeIntervalSince1970: TimeInterval(record.hostStat.st_birthtimespec.tv_sec) + TimeInterval(record.hostStat.st_birthtimespec.tv_nsec) / 1_000_000_000)
    }
    var contentModificationDate: Date? {
        Date(timeIntervalSince1970: TimeInterval(record.hostStat.st_mtimespec.tv_sec) + TimeInterval(record.hostStat.st_mtimespec.tv_nsec) / 1_000_000_000)
    }
}

private func providerError(_ error: Error) -> Error {
    if let storeError = error as? FakeFSStoreError {
        switch storeError {
        case .invalidIdentifier, .missingItem:
            return NSFileProviderError(.noSuchItem)
        case .filenameCollision:
            return NSFileProviderError(.filenameCollision)
        case .directoryNotEmpty:
            return NSFileProviderError(.directoryNotEmpty)
        case .invalidFilename:
            return CocoaError(.fileWriteInvalidFileName)
        case .database(let message):
            return CocoaError(.fileReadUnknown, userInfo: [NSLocalizedDescriptionKey: message])
        }
    }
    let nsError = error as NSError
    if nsError.domain == NSFileProviderErrorDomain || nsError.domain == NSCocoaErrorDomain {
        return error
    }
    return CocoaError(.fileReadUnknown, userInfo: [NSUnderlyingErrorKey: error])
}
