import FileProvider
import Foundation
import SQLite3
import XCTest

private let testSQLiteTransient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)

final class FakeFSStoreTests: XCTestCase {
    private var temporaryDirectory: URL!
    private var rootURL: URL!
    private var metaURL: URL!
    private var stateURL: URL!
    private var eventsURL: URL!
    private var store: FakeFSStore!

    override func setUpWithError() throws {
        temporaryDirectory = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        rootURL = temporaryDirectory.appendingPathComponent("data", isDirectory: true)
        metaURL = temporaryDirectory.appendingPathComponent("meta.db")
        stateURL = temporaryDirectory.appendingPathComponent("state.db")
        eventsURL = temporaryDirectory.appendingPathComponent("events.db")
        try FileManager.default.createDirectory(at: rootURL, withIntermediateDirectories: true)
        try createFakeFSDatabase()
        store = try FakeFSStore(
            rootURL: rootURL,
            fakeFSDatabaseURL: metaURL,
            stateDatabaseURL: stateURL,
            eventDatabaseURL: eventsURL
        )
    }

    override func tearDownWithError() throws {
        store = nil
        try? FileManager.default.removeItem(at: temporaryDirectory)
    }

    func testCreateAndRenameKeepFakeFSMetadataAndPersistentIdentifier() throws {
        let original = try store.createItem(
            parentIdentifier: .rootContainer,
            filename: ".env",
            isDirectory: false,
            contents: nil
        )
        XCTAssertTrue(FileManager.default.fileExists(atPath: rootURL.appendingPathComponent(".env").path))
        XCTAssertNotNil(try inode(at: "/.env"))

        let result = try store.modifyItem(
            identifier: original.identifier,
            parentIdentifier: .rootContainer,
            filename: ".environment",
            baseVersion: original.version,
            changedFields: [.filename],
            newContents: nil,
            fileSystemFlags: nil,
            contentModificationDate: nil
        )

        XCTAssertFalse(result.shouldFetchContent)
        XCTAssertEqual(result.item.identifier, original.identifier)
        XCTAssertNil(try inode(at: "/.env"))
        XCTAssertEqual(try inode(at: "/.environment"), original.inode)
        XCTAssertTrue(FileManager.default.fileExists(atPath: rootURL.appendingPathComponent(".environment").path))
    }

    func testStaleBaseVersionDoesNotOverwriteAlpineChange() throws {
        let initialContents = temporaryDirectory.appendingPathComponent("initial")
        try Data("initial".utf8).write(to: initialContents)
        let original = try store.createItem(
            parentIdentifier: .rootContainer,
            filename: "notes.txt",
            isDirectory: false,
            contents: initialContents
        )
        _ = try store.children(of: .rootContainer)

        try Data("alpine!".utf8).write(to: rootURL.appendingPathComponent("notes.txt"))
        try appendEvent(path: "/notes.txt", operation: 0)
        let alpineVersion = try store.item(for: original.identifier)
        XCTAssertNotEqual(alpineVersion.contentVersion, original.contentVersion)

        let filesContents = temporaryDirectory.appendingPathComponent("files")
        try Data("files!!".utf8).write(to: filesContents)
        let result = try store.modifyItem(
            identifier: original.identifier,
            parentIdentifier: .rootContainer,
            filename: "notes.txt",
            baseVersion: original.version,
            changedFields: [.contents],
            newContents: filesContents,
            fileSystemFlags: nil,
            contentModificationDate: nil
        )

        XCTAssertTrue(result.shouldFetchContent)
        XCTAssertEqual(try Data(contentsOf: rootURL.appendingPathComponent("notes.txt")), Data("alpine!".utf8))
    }

    func testExternalCreateIsReportedAfterRootWasEnumerated() throws {
        _ = try store.children(of: .rootContainer)
        let anchor = try store.currentAnchor()
        try createExternalFile(path: "/agent.txt", contents: "agent")
        try appendEvent(path: "/agent.txt", operation: 0)

        let changes = try store.changes(from: anchor, suggestedBatchSize: 50)

        XCTAssertEqual(changes.updated.map(\.filename), ["agent.txt"])
        XCTAssertTrue(changes.deleted.isEmpty)
    }

    func testExternalRenameAndDeleteReportThePersistentIdentifier() throws {
        let original = try store.createItem(
            parentIdentifier: .rootContainer,
            filename: "before.txt",
            isDirectory: false,
            contents: nil
        )
        _ = try store.children(of: .rootContainer)
        let initialAnchor = try store.currentAnchor()

        try moveExternalPath(from: "/before.txt", to: "/after.txt")
        try appendEvent(path: "/before.txt", operation: 1)
        try appendEvent(path: "/after.txt", operation: 2)
        let renameChanges = try store.changes(from: initialAnchor, suggestedBatchSize: 50)

        XCTAssertEqual(renameChanges.updated.map(\.identifier), [original.identifier])
        XCTAssertEqual(renameChanges.updated.map(\.filename), ["after.txt"])

        try removeExternalPath("/after.txt")
        try appendEvent(path: "/after.txt", operation: 1)
        let deleteChanges = try store.changes(from: renameChanges.anchor, suggestedBatchSize: 50)

        XCTAssertEqual(deleteChanges.deleted, [original.identifier])
        XCTAssertTrue(deleteChanges.updated.isEmpty)
    }

    func testHiddenItemsAreEnumerated() throws {
        _ = try store.createItem(
            parentIdentifier: .rootContainer,
            filename: ".git",
            isDirectory: true,
            contents: nil
        )
        let children = try store.children(of: .rootContainer)
        XCTAssertEqual(children.map(\.filename), [".git"])
    }

    func testDirectoryPagesDoNotLoadTheWholeDirectory() throws {
        for name in ["c.txt", "a.txt", "b.txt"] {
            _ = try store.createItem(
                parentIdentifier: .rootContainer,
                filename: name,
                isDirectory: false,
                contents: nil
            )
        }

        let first = try store.childrenPage(of: .rootContainer, offset: 0, limit: 2)
        let second = try store.childrenPage(of: .rootContainer, offset: 2, limit: 2)

        XCTAssertEqual(first.items.map(\.filename), ["a.txt", "b.txt"])
        XCTAssertTrue(first.hasMore)
        XCTAssertEqual(second.items.map(\.filename), ["c.txt"])
        XCTAssertFalse(second.hasMore)
    }

    private func createFakeFSDatabase() throws {
        let database = try openDatabase(metaURL)
        defer { sqlite3_close(database) }
        try execute(database, "CREATE TABLE stats(inode INTEGER PRIMARY KEY, stat BLOB)")
        try execute(database, "CREATE TABLE paths(path BLOB PRIMARY KEY, inode INTEGER REFERENCES stats(inode))")
        try execute(database, "CREATE INDEX inode_to_path ON paths(inode, path)")
        let rootStat = FakeFSStat(mode: FakeFSStat.directoryType | 0o755).data
        var statement: OpaquePointer?
        XCTAssertEqual(sqlite3_prepare_v2(database, "INSERT INTO stats(stat) VALUES(?)", -1, &statement, nil), SQLITE_OK)
        _ = rootStat.withUnsafeBytes { sqlite3_bind_blob(statement, 1, $0.baseAddress, Int32($0.count), testSQLiteTransient) }
        XCTAssertEqual(sqlite3_step(statement), SQLITE_DONE)
        sqlite3_finalize(statement)
        try execute(database, "INSERT INTO paths(path, inode) VALUES(x'', 1)")
    }

    private func createExternalFile(path: String, contents: String) throws {
        try Data(contents.utf8).write(to: rootURL.appendingPathComponent(String(path.dropFirst())))
        let database = try openDatabase(metaURL)
        defer { sqlite3_close(database) }
        let fakeStat = FakeFSStat(mode: FakeFSStat.regularType | 0o644).data
        var statement: OpaquePointer?
        XCTAssertEqual(sqlite3_prepare_v2(database, "INSERT INTO stats(stat) VALUES(?)", -1, &statement, nil), SQLITE_OK)
        _ = fakeStat.withUnsafeBytes { sqlite3_bind_blob(statement, 1, $0.baseAddress, Int32($0.count), testSQLiteTransient) }
        XCTAssertEqual(sqlite3_step(statement), SQLITE_DONE)
        sqlite3_finalize(statement)
        let inode = sqlite3_last_insert_rowid(database)
        XCTAssertEqual(sqlite3_prepare_v2(database, "INSERT INTO paths(path, inode) VALUES(?, ?)", -1, &statement, nil), SQLITE_OK)
        _ = path.data(using: .utf8)!.withUnsafeBytes { sqlite3_bind_blob(statement, 1, $0.baseAddress, Int32($0.count), testSQLiteTransient) }
        sqlite3_bind_int64(statement, 2, inode)
        XCTAssertEqual(sqlite3_step(statement), SQLITE_DONE)
        sqlite3_finalize(statement)
    }

    private func appendEvent(path: String, operation: Int32) throws {
        let database = try openDatabase(eventsURL)
        defer { sqlite3_close(database) }
        var statement: OpaquePointer?
        XCTAssertEqual(sqlite3_prepare_v2(database, "INSERT INTO events(path, operation) VALUES(?, ?)", -1, &statement, nil), SQLITE_OK)
        sqlite3_bind_text(statement, 1, path, -1, testSQLiteTransient)
        sqlite3_bind_int(statement, 2, operation)
        XCTAssertEqual(sqlite3_step(statement), SQLITE_DONE)
        sqlite3_finalize(statement)
    }

    private func moveExternalPath(from source: String, to destination: String) throws {
        try FileManager.default.moveItem(
            at: rootURL.appendingPathComponent(String(source.dropFirst())),
            to: rootURL.appendingPathComponent(String(destination.dropFirst()))
        )
        let database = try openDatabase(metaURL)
        defer { sqlite3_close(database) }
        var statement: OpaquePointer?
        XCTAssertEqual(sqlite3_prepare_v2(database, "UPDATE paths SET path = ? WHERE path = ?", -1, &statement, nil), SQLITE_OK)
        _ = destination.data(using: .utf8)!.withUnsafeBytes {
            sqlite3_bind_blob(statement, 1, $0.baseAddress, Int32($0.count), testSQLiteTransient)
        }
        _ = source.data(using: .utf8)!.withUnsafeBytes {
            sqlite3_bind_blob(statement, 2, $0.baseAddress, Int32($0.count), testSQLiteTransient)
        }
        XCTAssertEqual(sqlite3_step(statement), SQLITE_DONE)
        sqlite3_finalize(statement)
    }

    private func removeExternalPath(_ path: String) throws {
        try FileManager.default.removeItem(at: rootURL.appendingPathComponent(String(path.dropFirst())))
        let database = try openDatabase(metaURL)
        defer { sqlite3_close(database) }
        var statement: OpaquePointer?
        XCTAssertEqual(sqlite3_prepare_v2(database, "DELETE FROM paths WHERE path = ?", -1, &statement, nil), SQLITE_OK)
        _ = path.data(using: .utf8)!.withUnsafeBytes {
            sqlite3_bind_blob(statement, 1, $0.baseAddress, Int32($0.count), testSQLiteTransient)
        }
        XCTAssertEqual(sqlite3_step(statement), SQLITE_DONE)
        sqlite3_finalize(statement)
    }

    private func inode(at path: String) throws -> Int64? {
        let database = try openDatabase(metaURL)
        defer { sqlite3_close(database) }
        var statement: OpaquePointer?
        XCTAssertEqual(sqlite3_prepare_v2(database, "SELECT inode FROM paths WHERE path = ?", -1, &statement, nil), SQLITE_OK)
        _ = path.data(using: .utf8)!.withUnsafeBytes { sqlite3_bind_blob(statement, 1, $0.baseAddress, Int32($0.count), testSQLiteTransient) }
        defer { sqlite3_finalize(statement) }
        return sqlite3_step(statement) == SQLITE_ROW ? sqlite3_column_int64(statement, 0) : nil
    }

    private func openDatabase(_ url: URL) throws -> OpaquePointer {
        var database: OpaquePointer?
        guard sqlite3_open(url.path, &database) == SQLITE_OK, let database else {
            throw CocoaError(.fileWriteUnknown)
        }
        sqlite3_busy_timeout(database, 5_000)
        return database
    }

    private func execute(_ database: OpaquePointer, _ sql: String) throws {
        guard sqlite3_exec(database, sql, nil, nil, nil) == SQLITE_OK else {
            throw CocoaError(.fileWriteUnknown)
        }
    }
}
