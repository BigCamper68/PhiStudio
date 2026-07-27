import Foundation

enum ImportedFileKind: Sendable {
    case chart
    case audio
    case illustration

    fileprivate var allowedExtensions: Set<String> {
        switch self {
        case .chart:
            ["json", "pec", "pez", "zip"]
        case .audio:
            ["aac", "flac", "m4a", "mp3", "ogg", "wav"]
        case .illustration:
            ["bmp", "gif", "jpeg", "jpg", "png", "webp"]
        }
    }

    fileprivate var displayName: String {
        switch self {
        case .chart: "chart or package"
        case .audio: "audio"
        case .illustration: "illustration"
        }
    }

    func accepts(_ url: URL) -> Bool {
        allowedExtensions.contains(url.pathExtension.lowercased())
    }
}

struct StagedImportFile: Hashable, Sendable {
    let url: URL
    private let containerURL: URL

    fileprivate init(url: URL, containerURL: URL) {
        self.url = url
        self.containerURL = containerURL
    }

    func remove() {
        try? FileManager.default.removeItem(at: containerURL)
    }
}

enum ImportedFileStager {
    static func stage(
        _ sourceURL: URL,
        as kind: ImportedFileKind
    ) async throws -> StagedImportFile {
        guard sourceURL.isFileURL else {
            throw ImportedFileError.notAFile
        }
        guard kind.accepts(sourceURL) else {
            throw ImportedFileError.unsupportedType(
                expected: kind.displayName,
                extensionName: sourceURL.pathExtension
            )
        }

        let scoped = sourceURL.startAccessingSecurityScopedResource()
        defer {
            if scoped {
                sourceURL.stopAccessingSecurityScopedResource()
            }
        }

        return try await Task.detached(priority: .userInitiated) {
            let manager = FileManager.default
            let root = manager.temporaryDirectory.appendingPathComponent(
                "PhiStudioSelections",
                isDirectory: true
            )
            let container = root.appendingPathComponent(
                UUID().uuidString,
                isDirectory: true
            )
            try manager.createDirectory(at: container, withIntermediateDirectories: true)

            let filename = sourceURL.lastPathComponent.isEmpty
                ? "selection.\(sourceURL.pathExtension.lowercased())"
                : sourceURL.lastPathComponent
            let destination = container.appendingPathComponent(filename)
            do {
                try copyCoordinated(from: sourceURL, to: destination)
                return StagedImportFile(url: destination, containerURL: container)
            } catch {
                try? manager.removeItem(at: container)
                throw error
            }
        }.value
    }

    private static func copyCoordinated(from sourceURL: URL, to destination: URL) throws {
        let coordinator = NSFileCoordinator(filePresenter: nil)
        var coordinationError: NSError?
        var copyResult: Result<Void, Error>?
        coordinator.coordinate(
            readingItemAt: sourceURL,
            options: [.withoutChanges],
            error: &coordinationError
        ) { coordinatedURL in
            copyResult = Result {
                try FileManager.default.copyItem(at: coordinatedURL, to: destination)
            }
        }
        if let coordinationError {
            throw coordinationError
        }
        guard let copyResult else {
            throw ImportedFileError.notAFile
        }
        try copyResult.get()
    }
}

private enum ImportedFileError: LocalizedError, Sendable {
    case notAFile
    case unsupportedType(expected: String, extensionName: String)

    var errorDescription: String? {
        switch self {
        case .notAFile:
            "The selected item is not a file."
        case let .unsupportedType(expected, extensionName):
            extensionName.isEmpty
                ? "Choose a supported \(expected) file."
                : "The .\(extensionName.lowercased()) file is not a supported \(expected)."
        }
    }
}
