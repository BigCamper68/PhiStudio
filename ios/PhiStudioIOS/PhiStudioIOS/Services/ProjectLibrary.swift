import Foundation

public struct ProjectRecord: Codable, Identifiable, Hashable, Sendable {
    public var id: UUID
    public var name: String
    public var sourceDisplayName: String
    public var workspaceName: String
    public var createdAt: Date
    public var modifiedAt: Date
    public var chartPath: String
    public var audioPath: String?
    public var illustrationPath: String?
    public var noteCount: Int
    public var eventCount: Int

    public init(
        id: UUID = UUID(),
        name: String,
        sourceDisplayName: String,
        workspaceName: String,
        createdAt: Date = .now,
        modifiedAt: Date = .now,
        chartPath: String,
        audioPath: String?,
        illustrationPath: String?,
        noteCount: Int,
        eventCount: Int
    ) {
        self.id = id
        self.name = name
        self.sourceDisplayName = sourceDisplayName
        self.workspaceName = workspaceName
        self.createdAt = createdAt
        self.modifiedAt = modifiedAt
        self.chartPath = chartPath
        self.audioPath = audioPath
        self.illustrationPath = illustrationPath
        self.noteCount = noteCount
        self.eventCount = eventCount
    }
}

public struct LoadedProject: Hashable, Sendable {
    public var record: ProjectRecord
    public var package: ChartPackage
}

public actor ProjectLibrary {
    private struct Index: Codable {
        var version = 1
        var projects: [ProjectRecord] = []
    }

    public static let shared = ProjectLibrary()

    private let manager = FileManager.default
    private let rootURL: URL
    private let legacyRootURL: URL?
    private let projectsURL: URL
    private let indexURL: URL
    private var index = Index()
    private var isLoaded = false

    public init(rootURL: URL? = nil, legacyRootURL: URL? = nil) {
        if let rootURL {
            self.rootURL = rootURL.appendingPathComponent("PhiStudio", isDirectory: true)
            self.legacyRootURL = legacyRootURL?.appendingPathComponent(
                "PhiStudio",
                isDirectory: true
            )
        } else {
            let applicationSupport = FileManager.default.urls(
                for: .applicationSupportDirectory,
                in: .userDomainMask
            ).first!
            let documents = FileManager.default.urls(
                for: .documentDirectory,
                in: .userDomainMask
            ).first!
            self.rootURL = applicationSupport.appendingPathComponent(
                "PhiStudio",
                isDirectory: true
            )
            self.legacyRootURL = (legacyRootURL ?? documents).appendingPathComponent(
                "PhiStudio",
                isDirectory: true
            )
        }
        projectsURL = self.rootURL.appendingPathComponent("Projects", isDirectory: true)
        indexURL = self.rootURL.appendingPathComponent("projects.json")
    }

    public func list() throws -> [ProjectRecord] {
        try loadIfNeeded()
        return index.projects.sorted { $0.modifiedAt > $1.modifiedAt }
    }

    public func open(_ id: UUID) throws -> LoadedProject {
        try loadIfNeeded()
        guard let record = index.projects.first(where: { $0.id == id }) else {
            throw ChartError.fileSystem("Project no longer exists")
        }
        let package = try PackageService.loadWorkspace(
            workspaceURL(for: record),
            sourceDisplayName: record.sourceDisplayName
        )
        return LoadedProject(record: record, package: package)
    }

    public func importFile(_ sourceURL: URL) throws -> LoadedProject {
        try loadIfNeeded()
        let id = UUID()
        let workspaceName = id.uuidString
        let workspace = projectsURL.appendingPathComponent(workspaceName, isDirectory: true)
        let ext = sourceURL.pathExtension.lowercased()
        let package: ChartPackage
        if ext == "zip" || ext == "pez" {
            package = try PackageService.importPackage(from: sourceURL, to: workspace)
        } else {
            try manager.createDirectory(at: workspace, withIntermediateDirectories: true)
            do {
                var name = sourceURL.lastPathComponent
                if name.isEmpty { name = ext == "pec" ? "chart.pec" : "chart.json" }
                let destination = workspace.appendingPathComponent(name)
                try manager.copyItem(at: sourceURL, to: destination)
                package = try PackageService.loadWorkspace(
                    workspace,
                    sourceDisplayName: sourceURL.lastPathComponent
                )
            } catch {
                try? manager.removeItem(at: workspace)
                throw error
            }
        }
        let record = makeRecord(
            id: id,
            workspaceName: workspaceName,
            package: package,
            createdAt: .now
        )
        index.projects.append(record)
        try saveIndex()
        return LoadedProject(record: record, package: package)
    }

    public func createProject(
        name: String,
        composer: String,
        charter: String,
        level: String,
        bpm: Double,
        audioSource: URL?,
        illustrationSource: URL?
    ) throws -> LoadedProject {
        try loadIfNeeded()
        let id = UUID()
        let workspaceName = id.uuidString
        let package = try PackageService.createProject(
            at: projectsURL.appendingPathComponent(workspaceName, isDirectory: true),
            name: name,
            composer: composer,
            charter: charter,
            level: level,
            bpm: bpm,
            audioSource: audioSource,
            illustrationSource: illustrationSource
        )
        let record = makeRecord(
            id: id,
            workspaceName: workspaceName,
            package: package,
            createdAt: .now
        )
        index.projects.append(record)
        try saveIndex()
        return LoadedProject(record: record, package: package)
    }

    public func save(
        projectID: UUID,
        package: ChartPackage,
        chart: ChartDocument
    ) throws -> ProjectRecord {
        try loadIfNeeded()
        guard let indexPosition = index.projects.firstIndex(where: { $0.id == projectID }) else {
            throw ChartError.fileSystem("Project no longer exists")
        }
        try PackageService.save(package, chart: chart)
        var record = index.projects[indexPosition]
        record.name = chart.name.isEmpty ? package.projectName : chart.name
        record.modifiedAt = .now
        record.chartPath = package.chartPath
        record.audioPath = chart.song.isEmpty ? package.audioPath : chart.song
        record.illustrationPath = chart.background.isEmpty
            ? package.illustrationPath
            : chart.background
        record.noteCount = chart.totalNotes
        record.eventCount = chart.totalEvents
        index.projects[indexPosition] = record
        try saveIndex()
        return record
    }

    public func duplicate(_ id: UUID) throws -> LoadedProject {
        let source = try open(id)
        try loadIfNeeded()
        let newID = UUID()
        let workspaceName = newID.uuidString
        let destination = projectsURL.appendingPathComponent(workspaceName, isDirectory: true)
        try manager.copyItem(at: source.package.workspaceURL, to: destination)
        do {
            var package = try PackageService.loadWorkspace(
                destination,
                sourceDisplayName: source.package.sourceDisplayName
            )
            package.chart.name = "\(package.chart.name) Copy"
            try PackageService.save(package, chart: package.chart)
            let record = makeRecord(
                id: newID,
                workspaceName: workspaceName,
                package: package,
                createdAt: .now
            )
            index.projects.append(record)
            try saveIndex()
            return LoadedProject(record: record, package: package)
        } catch {
            try? manager.removeItem(at: destination)
            throw error
        }
    }

    public func remove(_ id: UUID) throws {
        try loadIfNeeded()
        guard let position = index.projects.firstIndex(where: { $0.id == id }) else { return }
        let record = index.projects[position]
        let workspace = workspaceURL(for: record)
        if manager.fileExists(atPath: workspace.path) {
            try manager.removeItem(at: workspace)
        }
        index.projects.remove(at: position)
        try saveIndex()
    }

    public func export(
        _ id: UUID,
        chart: ChartDocument? = nil,
        to destinationURL: URL
    ) throws {
        let loaded = try open(id)
        try PackageService.exportPackage(
            loaded.package,
            chart: chart ?? loaded.package.chart,
            to: destinationURL
        )
    }

    public func replaceAsset(
        projectID: UUID,
        sourceURL: URL,
        kind: AssetKind,
        chart sourceChart: ChartDocument
    ) throws -> LoadedProject {
        var loaded = try open(projectID)
        var chart = sourceChart
        let extensionName = sourceURL.pathExtension.lowercased()
        let stem = kind == .audio ? "music" : "illustration"
        let suffix = extensionName.isEmpty ? "" : ".\(extensionName)"
        let filename = "\(stem)-\(UUID().uuidString.prefix(8))\(suffix)"
        let destination = loaded.package.workspaceURL.appendingPathComponent(filename)
        try manager.copyItem(at: sourceURL, to: destination)
        switch kind {
        case .audio: chart.song = filename
        case .illustration: chart.background = filename
        }
        try PackageService.save(loaded.package, chart: chart)
        loaded.package = try PackageService.loadWorkspace(
            loaded.package.workspaceURL,
            sourceDisplayName: loaded.package.sourceDisplayName
        )
        loaded.package.chart = chart
        loaded.record = try save(
            projectID: projectID,
            package: loaded.package,
            chart: chart
        )
        return loaded
    }

    public enum AssetKind: Sendable, Equatable {
        case audio
        case illustration
    }

    private func loadIfNeeded() throws {
        if isLoaded { return }
        try migrateLegacyLibraryIfNeeded()
        try manager.createDirectory(at: projectsURL, withIntermediateDirectories: true)
        if manager.fileExists(atPath: indexURL.path) {
            do {
                index = try JSONDecoder().decode(Index.self, from: Data(contentsOf: indexURL))
            } catch {
                let backup = rootURL.appendingPathComponent(
                    "projects-corrupt-\(Int(Date().timeIntervalSince1970)).json"
                )
                try? manager.copyItem(at: indexURL, to: backup)
                index = try rebuildIndex()
                try saveIndex()
            }
        } else {
            index = try rebuildIndex()
            try saveIndex()
        }
        let discovered = try rebuildIndex()
        let knownIDs = Set(index.projects.map(\.id))
        let knownWorkspaces = Set(index.projects.map(\.workspaceName))
        let recovered = discovered.projects.filter {
            !knownIDs.contains($0.id) && !knownWorkspaces.contains($0.workspaceName)
        }
        if !recovered.isEmpty {
            index.projects.append(contentsOf: recovered)
            try saveIndex()
        }
        isLoaded = true
    }

    /// Versions through 1.0.3 kept private workspaces under Documents. Those folders can be
    /// moved or partially removed by Files/iCloud while the index still points at them. Keep
    /// project internals in Application Support and migrate the old library before any scan.
    private func migrateLegacyLibraryIfNeeded() throws {
        guard let legacyRootURL,
              legacyRootURL.standardizedFileURL != rootURL.standardizedFileURL,
              manager.fileExists(atPath: legacyRootURL.path)
        else {
            return
        }

        try manager.createDirectory(
            at: rootURL.deletingLastPathComponent(),
            withIntermediateDirectories: true
        )

        if !manager.fileExists(atPath: rootURL.path) {
            do {
                try manager.moveItem(at: legacyRootURL, to: rootURL)
            } catch {
                // Cross-volume and coordinated Files moves can fail even when a read-only copy
                // is possible. Preserve the legacy folder until the copied library is verified.
                guard !manager.fileExists(atPath: rootURL.path) else { throw error }
                try manager.copyItem(at: legacyRootURL, to: rootURL)
            }
            return
        }

        let legacyProjects = legacyRootURL.appendingPathComponent("Projects", isDirectory: true)
        try manager.createDirectory(at: projectsURL, withIntermediateDirectories: true)
        if manager.fileExists(atPath: legacyProjects.path) {
            let workspaces = try manager.contentsOfDirectory(
                at: legacyProjects,
                includingPropertiesForKeys: [.isDirectoryKey],
                options: [.skipsHiddenFiles]
            )
            for workspace in workspaces {
                guard (try? workspace.resourceValues(
                    forKeys: [.isDirectoryKey]
                ).isDirectory) == true else {
                    continue
                }
                let destination = projectsURL.appendingPathComponent(
                    workspace.lastPathComponent,
                    isDirectory: true
                )
                guard !manager.fileExists(atPath: destination.path) else { continue }
                try manager.copyItem(at: workspace, to: destination)
            }
        }

        let legacyIndexURL = legacyRootURL.appendingPathComponent("projects.json")
        let primary = decodeIndex(at: indexURL) ?? Index()
        let legacy = decodeIndex(at: legacyIndexURL) ?? Index()
        var merged = primary
        let existingIDs = Set(primary.projects.map(\.id))
        let existingWorkspaces = Set(primary.projects.map(\.workspaceName))
        merged.projects.append(contentsOf: legacy.projects.filter {
            !existingIDs.contains($0.id) && !existingWorkspaces.contains($0.workspaceName)
        })
        index = merged
        try saveIndex()
    }

    private func decodeIndex(at url: URL) -> Index? {
        guard manager.fileExists(atPath: url.path),
              let data = try? Data(contentsOf: url)
        else {
            return nil
        }
        return try? JSONDecoder().decode(Index.self, from: data)
    }

    private func rebuildIndex() throws -> Index {
        let urls = try manager.contentsOfDirectory(
            at: projectsURL,
            includingPropertiesForKeys: [.isDirectoryKey],
            options: [.skipsHiddenFiles]
        )
        var records: [ProjectRecord] = []
        for url in urls {
            guard (try? url.resourceValues(forKeys: [.isDirectoryKey]).isDirectory) == true else {
                continue
            }
            guard let id = UUID(uuidString: url.lastPathComponent),
                  let package = try? PackageService.loadWorkspace(
                      url,
                      sourceDisplayName: "\(url.lastPathComponent).pez"
                  )
            else {
                continue
            }
            records.append(
                makeRecord(
                    id: id,
                    workspaceName: url.lastPathComponent,
                    package: package,
                    createdAt: .now
                )
            )
        }
        return Index(projects: records)
    }

    private func saveIndex() throws {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        try encoder.encode(index).write(to: indexURL, options: .atomic)
    }

    private func makeRecord(
        id: UUID,
        workspaceName: String,
        package: ChartPackage,
        createdAt: Date
    ) -> ProjectRecord {
        ProjectRecord(
            id: id,
            name: package.chart.name.isEmpty ? package.projectName : package.chart.name,
            sourceDisplayName: package.sourceDisplayName,
            workspaceName: workspaceName,
            createdAt: createdAt,
            modifiedAt: .now,
            chartPath: package.chartPath,
            audioPath: package.audioPath,
            illustrationPath: package.illustrationPath,
            noteCount: package.chart.totalNotes,
            eventCount: package.chart.totalEvents
        )
    }

    private func workspaceURL(for record: ProjectRecord) -> URL {
        let canonical = projectsURL.appendingPathComponent(
            record.id.uuidString,
            isDirectory: true
        )
        let safeWorkspaceName = !record.workspaceName.isEmpty
            && !record.workspaceName.contains("/")
            && !record.workspaceName.contains("\\")
            && record.workspaceName != "."
            && record.workspaceName != ".."
        if safeWorkspaceName {
            let recorded = projectsURL.appendingPathComponent(
                record.workspaceName,
                isDirectory: true
            )
            if manager.fileExists(atPath: recorded.path) { return recorded }
        }
        return canonical
    }
}
