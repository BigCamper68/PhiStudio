import Foundation
import ZIPFoundation

public struct PackageEntry: Hashable, Sendable {
    public var path: String
    public var isDirectory: Bool
    public var uncompressedSize: Int64
}

public struct ChartPackage: Hashable, Sendable {
    public var workspaceURL: URL
    public var sourceDisplayName: String
    public var projectName: String
    public var chartPath: String
    public var audioPath: String?
    public var illustrationPath: String?
    public var manifestOffsetMilliseconds: Int64
    public var useRPE170Speed: Bool
    public var chart: ChartDocument
    public var entries: [PackageEntry]
    public var manifests: [PackageManifest]

    public var chartURL: URL { workspaceURL.appendingPathComponent(chartPath) }
    public var audioURL: URL? {
        audioPath.map { workspaceURL.appendingPathComponent($0) }
    }
    public var illustrationURL: URL? {
        illustrationPath.map { workspaceURL.appendingPathComponent($0) }
    }
}

public enum PackageService {
    private static let maximumManifestBytes: Int64 = 1_024 * 1_024
    private static let audioExtensions = ["ogg", "mp3", "wav", "flac", "m4a", "aac"]
    private static let imageExtensions = ["png", "jpg", "jpeg", "webp", "bmp", "gif"]

    public static func importPackage(
        from archiveURL: URL,
        to workspaceURL: URL,
        limits: PackageLimits = .init()
    ) throws -> ChartPackage {
        let fileSize = try archiveURL.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
        guard Int64(fileSize) <= limits.maximumArchiveBytes else {
            throw ChartError.archiveRejected("Archive exceeds \(limits.maximumArchiveBytes) bytes")
        }
        let manager = FileManager.default
        guard !manager.fileExists(atPath: workspaceURL.path) else {
            throw ChartError.fileSystem("Import workspace already exists")
        }
        try manager.createDirectory(at: workspaceURL, withIntermediateDirectories: true)
        do {
            let entries = try extract(
                archiveURL: archiveURL,
                workspaceURL: workspaceURL,
                limits: limits
            )
            return try analyze(
                workspaceURL: workspaceURL,
                sourceDisplayName: archiveURL.lastPathComponent,
                entries: entries
            )
        } catch {
            try? manager.removeItem(at: workspaceURL)
            throw error
        }
    }

    public static func loadWorkspace(
        _ workspaceURL: URL,
        sourceDisplayName: String
    ) throws -> ChartPackage {
        let entries = try scanWorkspace(workspaceURL)
        return try analyze(
            workspaceURL: workspaceURL,
            sourceDisplayName: sourceDisplayName,
            entries: entries
        )
    }

    public static func createProject(
        at workspaceURL: URL,
        name: String,
        composer: String,
        charter: String,
        level: String,
        bpm: Double,
        audioSource: URL?,
        illustrationSource: URL?
    ) throws -> ChartPackage {
        guard bpm.isFinite, bpm > 0 else {
            throw ChartError.invalidValue("BPM must be positive")
        }
        let manager = FileManager.default
        guard !manager.fileExists(atPath: workspaceURL.path) else {
            throw ChartError.fileSystem("Project workspace already exists")
        }
        try manager.createDirectory(at: workspaceURL, withIntermediateDirectories: true)
        do {
            var chart = ChartDocument()
            chart.name = name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                ? "Untitled"
                : name.trimmingCharacters(in: .whitespacesAndNewlines)
            chart.composer = composer
            chart.charter = charter
            chart.level = level
            chart.bpmChanges = [BPMChange(bpm: bpm)]
            chart.judgeLines = [JudgeLine()]

            if let audioSource {
                let filename = safeAssetName(audioSource.lastPathComponent, fallback: "music")
                try manager.copyItem(at: audioSource, to: workspaceURL.appendingPathComponent(filename))
                chart.song = filename
            }
            if let illustrationSource {
                let filename = safeAssetName(
                    illustrationSource.lastPathComponent,
                    fallback: "illustration"
                )
                try manager.copyItem(
                    at: illustrationSource,
                    to: workspaceURL.appendingPathComponent(filename)
                )
                chart.background = filename
            }
            try writeChart(chart, to: workspaceURL.appendingPathComponent("chart.json"))
            let manifest = """
            name: "\(escapeYAML(chart.name))"
            chart: chart.json
            music: "\(escapeYAML(chart.song))"
            illustration: "\(escapeYAML(chart.background))"
            composer: "\(escapeYAML(chart.composer))"
            charter: "\(escapeYAML(chart.charter))"
            level: "\(escapeYAML(chart.level))"
            """
            guard let manifestData = manifest.data(using: .utf8) else {
                throw ChartError.invalidValue("Could not encode package manifest")
            }
            try manifestData.write(
                to: workspaceURL.appendingPathComponent("info.yml"),
                options: .atomic
            )
            return try loadWorkspace(workspaceURL, sourceDisplayName: "\(chart.name).pez")
        } catch {
            try? manager.removeItem(at: workspaceURL)
            throw error
        }
    }

    public static func save(_ package: ChartPackage, chart: ChartDocument) throws {
        try writeChart(chart, to: package.chartURL)
        try updateManifests(package, chart: chart)
    }

    public static func exportPackage(
        _ package: ChartPackage,
        chart: ChartDocument,
        to destinationURL: URL
    ) throws {
        let manager = FileManager.default
        let temporaryRoot = manager.temporaryDirectory
            .appendingPathComponent("PhiStudioExport-\(UUID().uuidString)", isDirectory: true)
        let stagedWorkspace = temporaryRoot.appendingPathComponent("Package", isDirectory: true)
        try manager.createDirectory(at: temporaryRoot, withIntermediateDirectories: true)
        defer { try? manager.removeItem(at: temporaryRoot) }
        try manager.copyItem(at: package.workspaceURL, to: stagedWorkspace)
        var stagedPackage = package
        stagedPackage.workspaceURL = stagedWorkspace
        try writeChart(chart, to: stagedPackage.chartURL)
        try updateManifests(stagedPackage, chart: chart)
        if manager.fileExists(atPath: destinationURL.path) {
            try manager.removeItem(at: destinationURL)
        }
        try manager.zipItem(
            at: stagedWorkspace,
            to: destinationURL,
            shouldKeepParent: false,
            compressionMethod: .deflate
        )
    }

    public static func normalizePath(_ raw: String) throws -> String {
        guard !raw.isEmpty, !raw.contains("\0") else {
            throw ChartError.archiveRejected("Archive contains an empty or invalid path")
        }
        let value = raw.replacingOccurrences(of: "\\", with: "/")
        let drivePattern = try? NSRegularExpression(pattern: "^[A-Za-z]:")
        let driveRange = NSRange(value.startIndex ..< value.endIndex, in: value)
        guard !value.hasPrefix("/"),
              drivePattern?.firstMatch(in: value, range: driveRange) == nil
        else {
            throw ChartError.archiveRejected("Absolute archive path is not allowed: \(raw)")
        }
        var parts: [String] = []
        for part in value.split(separator: "/", omittingEmptySubsequences: true) {
            if part == "." { continue }
            if part == ".." {
                throw ChartError.archiveRejected("Archive path traversal is not allowed: \(raw)")
            }
            parts.append(String(part))
        }
        guard !parts.isEmpty else {
            throw ChartError.archiveRejected("Archive path normalizes to an empty value")
        }
        return parts.joined(separator: "/")
    }

    private static func extract(
        archiveURL: URL,
        workspaceURL: URL,
        limits: PackageLimits
    ) throws -> [PackageEntry] {
        let archive = try Archive(url: archiveURL, accessMode: .read)
        let manager = FileManager.default
        var known: [String: Bool] = [:]
        var entries: [PackageEntry] = []
        var total: Int64 = 0
        for entry in archive {
            guard entries.count < limits.maximumEntries else {
                throw ChartError.archiveRejected("Archive contains too many entries")
            }
            guard entry.type != .symlink else {
                throw ChartError.archiveRejected("Symbolic links are not accepted")
            }
            let path = try normalizePath(entry.path)
            let isDirectory = entry.type == .directory
            try register(path: path, isDirectory: isDirectory, known: &known)
            let uncompressed = Int64(clamping: entry.uncompressedSize)
            let compressed = Int64(clamping: entry.compressedSize)
            guard uncompressed <= limits.maximumEntryBytes,
                  compressed <= limits.maximumCompressedEntryBytes
            else {
                throw ChartError.archiveRejected("Archive entry is too large: \(path)")
            }
            let nextTotal = total.addingReportingOverflow(uncompressed)
            guard !nextTotal.overflow, nextTotal.partialValue <= limits.maximumTotalBytes else {
                throw ChartError.archiveRejected("Archive expands beyond the allowed total size")
            }
            total = nextTotal.partialValue
            let destination = workspaceURL.appendingPathComponent(path, isDirectory: isDirectory)
            if isDirectory {
                try manager.createDirectory(at: destination, withIntermediateDirectories: true)
            } else {
                try manager.createDirectory(
                    at: destination.deletingLastPathComponent(),
                    withIntermediateDirectories: true
                )
                try archive.extract(entry, to: destination)
                let size = try destination.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
                guard Int64(size) <= limits.maximumEntryBytes else {
                    throw ChartError.archiveRejected("Extracted entry is too large: \(path)")
                }
            }
            entries.append(
                PackageEntry(
                    path: path,
                    isDirectory: isDirectory,
                    uncompressedSize: uncompressed
                )
            )
        }
        return entries
    }

    private static func analyze(
        workspaceURL: URL,
        sourceDisplayName: String,
        entries: [PackageEntry]
    ) throws -> ChartPackage {
        let manifests = try readManifests(workspaceURL: workspaceURL, entries: entries)
        var rpe: [Candidate] = []
        var official: [Candidate] = []
        var pec: [Candidate] = []
        let fileEntries = entries.filter { !$0.isDirectory }
        for entry in fileEntries {
            let url = workspaceURL.appendingPathComponent(entry.path)
            if try looksLikePEC(url) {
                pec.append(Candidate(path: entry.path, format: .pec, json: nil, data: nil))
                continue
            }
            guard try looksLikeJSONObject(url) else { continue }
            let data = try Data(contentsOf: url)
            guard let root = try? JSONValue.parse(stripBOM(data)) else { continue }
            switch ChartJSONFormat.detect(root) {
            case .rpe:
                rpe.append(Candidate(path: entry.path, format: .rpe, json: root, data: data))
            case .officialPhigros:
                official.append(
                    Candidate(path: entry.path, format: .official, json: root, data: data)
                )
            case .unknown:
                break
            }
        }
        let selected = try selectCandidate(
            manifests: manifests,
            rpe: rpe,
            official: official,
            pec: pec
        )
        let chartURL = workspaceURL.appendingPathComponent(selected.path)
        let sourceData: Data
        if let selectedData = selected.data {
            sourceData = selectedData
        } else {
            sourceData = try Data(contentsOf: chartURL)
        }
        var chart: ChartDocument
        var converted = false
        switch selected.format {
        case .rpe:
            chart = try ChartDocument(json: selected.json ?? JSONValue.parse(sourceData))
        case .official:
            chart = try ChartConverter.convertOfficialPhigros(
                selected.json ?? JSONValue.parse(sourceData)
            )
            converted = true
        case .pec:
            guard let source = String(data: sourceData, encoding: .utf8) else {
                throw ChartError.unsupportedFormat("PEC chart is not UTF-8")
            }
            chart = try ChartConverter.convertPEC(source)
            converted = true
        }

        let audioPath = try selectResource(
            manifests: manifests,
            entries: fileEntries,
            keys: ["music", "song"],
            extensions: audioExtensions
        )
        let illustrationPath = try selectResource(
            manifests: manifests,
            entries: fileEntries,
            keys: ["illustration", "picture", "background"],
            extensions: imageExtensions
        )
        let projectName = selectProjectName(
            manifests: manifests,
            chart: chart,
            sourceDisplayName: sourceDisplayName
        )
        if converted {
            chart.name = manifestValue(manifests, key: "name") ?? projectName
            chart.composer = manifestValue(manifests, key: "composer") ?? ""
            chart.charter = manifestValue(manifests, key: "charter") ?? ""
            chart.level = manifestValue(manifests, key: "level") ?? ""
            chart.chartID = manifestValue(manifests, key: "id") ?? ""
            chart.song = audioPath ?? ""
            chart.background = illustrationPath ?? ""
            try writeChart(chart, to: chartURL)
        }
        return ChartPackage(
            workspaceURL: workspaceURL,
            sourceDisplayName: sourceDisplayName,
            projectName: projectName,
            chartPath: selected.path,
            audioPath: audioPath,
            illustrationPath: illustrationPath,
            manifestOffsetMilliseconds: manifestOffset(manifests),
            useRPE170Speed: manifestRPE170Speed(manifests),
            chart: chart,
            entries: entries,
            manifests: manifests
        )
    }

    private enum CandidateFormat {
        case rpe
        case official
        case pec
    }

    private struct Candidate {
        var path: String
        var format: CandidateFormat
        var json: JSONValue?
        var data: Data?
    }

    private static func selectCandidate(
        manifests: [PackageManifest],
        rpe: [Candidate],
        official: [Candidate],
        pec: [Candidate]
    ) throws -> Candidate {
        var referenced: [Candidate] = []
        let all = rpe + official + pec
        for manifest in manifests {
            guard let hint = manifest["chart"], !hint.trimmingCharacters(in: .whitespaces).isEmpty
            else {
                continue
            }
            let resolved = try resolveManifestPath(manifest.path, hint)
            referenced.append(contentsOf: all.filter { $0.path == resolved })
        }
        let uniqueReferenced = Dictionary(grouping: referenced, by: \.path).compactMap {
            $0.value.first
        }
        if uniqueReferenced.count == 1 { return uniqueReferenced[0] }
        if uniqueReferenced.count > 1 {
            throw ChartError.unsupportedFormat("Manifests reference more than one chart")
        }
        if rpe.count == 1 { return rpe[0] }
        if rpe.count > 1 {
            throw ChartError.unsupportedFormat("Package has multiple RPE charts without a unique hint")
        }
        let convertible = official + pec
        if convertible.count == 1 { return convertible[0] }
        if convertible.count > 1 {
            throw ChartError.unsupportedFormat(
                "Package has multiple convertible charts without a unique hint"
            )
        }
        throw ChartError.missingResource("No RPE, official Phigros, or PEC chart was found")
    }

    private static func readManifests(
        workspaceURL: URL,
        entries: [PackageEntry]
    ) throws -> [PackageManifest] {
        try entries.compactMap { entry in
            guard !entry.isDirectory,
                  !entry.path.contains("/"),
                  entry.uncompressedSize <= maximumManifestBytes
            else {
                return nil
            }
            let lower = entry.path.lowercased()
            let kind: PackageManifest.Kind
            if lower == "info.yml" || lower == "info.yaml" {
                kind = .yaml
            } else if lower == "info.txt" {
                kind = .text
            } else {
                return nil
            }
            let source = try String(
                contentsOf: workspaceURL.appendingPathComponent(entry.path),
                encoding: .utf8
            )
            return PackageManifest(path: entry.path, kind: kind, sourceText: source)
        }.sorted {
            $0.kind.rawValue == $1.kind.rawValue
                ? $0.path < $1.path
                : $0.kind.rawValue < $1.kind.rawValue
        }
    }

    private static func selectResource(
        manifests: [PackageManifest],
        entries: [PackageEntry],
        keys: [String],
        extensions: [String]
    ) throws -> String? {
        let paths = Set(entries.map(\.path))
        for manifest in manifests {
            for key in keys {
                guard let value = manifest[key], !value.trimmingCharacters(in: .whitespaces).isEmpty
                else {
                    continue
                }
                let path = try resolveManifestPath(manifest.path, value)
                if paths.contains(path) { return path }
            }
        }
        let matches = entries.map(\.path).filter {
            extensions.contains(URL(fileURLWithPath: $0).pathExtension.lowercased())
        }.sorted()
        return matches.count == 1 ? matches[0] : nil
    }

    private static func selectProjectName(
        manifests: [PackageManifest],
        chart: ChartDocument,
        sourceDisplayName: String
    ) -> String {
        if let name = manifestValue(manifests, key: "name") { return name }
        if !chart.name.isEmpty, chart.name != "Untitled" { return chart.name }
        var value = sourceDisplayName.trimmingCharacters(in: .whitespacesAndNewlines)
        if ["zip", "pez"].contains(URL(fileURLWithPath: value).pathExtension.lowercased()) {
            value = (value as NSString).deletingPathExtension
        }
        return value.isEmpty ? "Imported package" : value
    }

    private static func manifestValue(_ manifests: [PackageManifest], key: String) -> String? {
        manifests.lazy.compactMap { manifest in
            manifest[key]?.trimmingCharacters(in: .whitespacesAndNewlines)
        }.first { !$0.isEmpty }
    }

    private static func manifestOffset(_ manifests: [PackageManifest]) -> Int64 {
        for manifest in manifests where manifest.kind == .yaml {
            guard let value = manifest["offset"], let seconds = Decimal(string: value) else {
                continue
            }
            var source = seconds * 1_000
            var rounded = Decimal()
            NSDecimalRound(&rounded, &source, 0, .plain)
            return NSDecimalNumber(decimal: rounded).int64Value
        }
        return 0
    }

    private static func manifestRPE170Speed(_ manifests: [PackageManifest]) -> Bool {
        for manifest in manifests where manifest.kind == .yaml {
            guard let value = manifest["useRpe170Speed"] else { continue }
            return value.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() == "true"
        }
        return false
    }

    private static func resolveManifestPath(_ manifestPath: String, _ value: String) throws -> String {
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        let parent = (manifestPath as NSString).deletingLastPathComponent
        return try normalizePath(parent.isEmpty ? trimmed : "\(parent)/\(trimmed)")
    }

    private static func scanWorkspace(_ workspaceURL: URL) throws -> [PackageEntry] {
        let manager = FileManager.default
        let baseURL = workspaceURL.standardizedFileURL
        var isDirectory: ObjCBool = false
        guard manager.fileExists(atPath: baseURL.path, isDirectory: &isDirectory),
              isDirectory.boolValue
        else {
            throw ChartError.fileSystem(
                "The saved project workspace is missing. Re-import its PEZ backup if it was "
                    + "removed from Files or iCloud."
            )
        }
        guard let enumerator = manager.enumerator(
            at: baseURL,
            includingPropertiesForKeys: [.isRegularFileKey, .isDirectoryKey, .isSymbolicLinkKey, .fileSizeKey],
            options: [.skipsHiddenFiles]
        ) else {
            throw ChartError.fileSystem("Unable to scan project workspace")
        }
        var result: [PackageEntry] = []
        for case let url as URL in enumerator {
            let values = try url.resourceValues(
                forKeys: [.isRegularFileKey, .isDirectoryKey, .isSymbolicLinkKey, .fileSizeKey]
            )
            guard values.isSymbolicLink != true else {
                throw ChartError.archiveRejected("Project workspaces may not contain symbolic links")
            }
            let standardizedURL = url.standardizedFileURL
            let prefix = baseURL.path.hasSuffix("/") ? baseURL.path : "\(baseURL.path)/"
            guard standardizedURL.path.hasPrefix(prefix) else {
                throw ChartError.archiveRejected("Project workspace contains an invalid path")
            }
            let path = try normalizePath(String(standardizedURL.path.dropFirst(prefix.count)))
            if values.isDirectory == true {
                result.append(PackageEntry(path: path, isDirectory: true, uncompressedSize: 0))
            } else if values.isRegularFile == true {
                result.append(
                    PackageEntry(
                        path: path,
                        isDirectory: false,
                        uncompressedSize: Int64(values.fileSize ?? 0)
                    )
                )
            }
        }
        return result.sorted { $0.path < $1.path }
    }

    private static func register(
        path: String,
        isDirectory: Bool,
        known: inout [String: Bool]
    ) throws {
        let key = path.precomposedStringWithCanonicalMapping.lowercased()
        if known[key] != nil {
            throw ChartError.archiveRejected("Duplicate normalized path: \(path)")
        }
        let components = key.split(separator: "/")
        if components.count > 1 {
            for end in 1 ..< components.count {
                let parent = components.prefix(end).joined(separator: "/")
                if known[parent] == false {
                    throw ChartError.archiveRejected("Path conflicts with a file: \(path)")
                }
            }
        }
        if !isDirectory, known.keys.contains(where: { $0.hasPrefix("\(key)/") }) {
            throw ChartError.archiveRejected("File conflicts with a directory: \(path)")
        }
        known[key] = isDirectory
    }

    private static func looksLikeJSONObject(_ url: URL) throws -> Bool {
        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }
        let prefix = try handle.read(upToCount: 4_096) ?? Data()
        let stripped = stripBOM(prefix)
        guard let text = String(data: stripped, encoding: .utf8) else { return false }
        return text.first(where: { !$0.isWhitespace }) == "{"
    }

    private static func looksLikePEC(_ url: URL) throws -> Bool {
        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }
        let data = try handle.read(upToCount: 64 * 1_024) ?? Data()
        guard var source = String(data: data, encoding: .utf8) else { return false }
        if source.hasPrefix("\u{FEFF}") { source.removeFirst() }
        let lines = source.components(separatedBy: .newlines)
        guard lines.count >= 2,
              let offset = Double(lines[0].trimmingCharacters(in: .whitespaces)),
              offset.isFinite
        else {
            return false
        }
        var hasBPM = false
        var hasCommand = false
        let commandPattern = try NSRegularExpression(
            pattern: "^(?:n[1-4]|cp|cm|cd|ca|cv|cr|cf)(?:\\s+.*)?$",
            options: [.caseInsensitive]
        )
        for line in lines.dropFirst().prefix(256) {
            let trimmed = line.trimmingCharacters(in: .whitespaces)
            if trimmed.hasPrefix("bp ") { hasBPM = true }
            let range = NSRange(trimmed.startIndex ..< trimmed.endIndex, in: trimmed)
            if commandPattern.firstMatch(in: trimmed, range: range) != nil { hasCommand = true }
            if hasBPM, hasCommand { return true }
        }
        return false
    }

    private static func stripBOM(_ data: Data) -> Data {
        data.starts(with: [0xEF, 0xBB, 0xBF]) ? Data(data.dropFirst(3)) : data
    }

    private static func writeChart(_ chart: ChartDocument, to url: URL) throws {
        try FileManager.default.createDirectory(
            at: url.deletingLastPathComponent(),
            withIntermediateDirectories: true
        )
        try chart.encoded().write(to: url, options: .atomic)
    }

    private static func updateManifests(
        _ package: ChartPackage,
        chart: ChartDocument
    ) throws {
        if package.manifests.isEmpty {
            let source = canonicalManifest(package: package, chart: chart)
            guard let data = source.data(using: .utf8) else {
                throw ChartError.invalidValue("Could not encode package manifest")
            }
            try data.write(
                to: package.workspaceURL.appendingPathComponent("info.yml"),
                options: .atomic
            )
            return
        }

        for manifest in package.manifests {
            var lines = manifest.sourceText.components(separatedBy: .newlines)
            var sawName = false
            var sawChart = false
            var sawMusic = false
            var sawIllustration = false
            var sawComposer = false
            var sawCharter = false
            var sawLevel = false

            for index in lines.indices {
                let original = lines[index]
                let trimmed = original.trimmingCharacters(in: .whitespaces)
                guard !trimmed.isEmpty,
                      !trimmed.hasPrefix("#"),
                      let separator = trimmed.firstIndex(of: ":")
                else {
                    continue
                }
                let originalKey = String(trimmed[..<separator])
                let key = originalKey.trimmingCharacters(in: .whitespaces).lowercased()
                let leadingCount = original.distance(
                    from: original.startIndex,
                    to: original.firstIndex(where: { !$0.isWhitespace }) ?? original.endIndex
                )
                let leading = String(original.prefix(leadingCount))
                let replacement: String?
                switch key {
                case "name":
                    sawName = true
                    replacement = chart.name
                case "chart":
                    sawChart = true
                    replacement = package.chartPath
                case "music", "song":
                    sawMusic = true
                    replacement = chart.song
                case "illustration", "picture", "background":
                    sawIllustration = true
                    replacement = chart.background
                case "composer":
                    sawComposer = true
                    replacement = chart.composer
                case "charter":
                    sawCharter = true
                    replacement = chart.charter
                case "level":
                    sawLevel = true
                    replacement = chart.level
                default:
                    replacement = nil
                }
                if let replacement {
                    lines[index] = "\(leading)\(originalKey): \"\(escapeYAML(replacement))\""
                }
            }

            if !sawName { lines.append("name: \"\(escapeYAML(chart.name))\"") }
            if !sawChart { lines.append("chart: \"\(escapeYAML(package.chartPath))\"") }
            if !sawMusic { lines.append("music: \"\(escapeYAML(chart.song))\"") }
            if !sawIllustration {
                lines.append("illustration: \"\(escapeYAML(chart.background))\"")
            }
            if !sawComposer { lines.append("composer: \"\(escapeYAML(chart.composer))\"") }
            if !sawCharter { lines.append("charter: \"\(escapeYAML(chart.charter))\"") }
            if !sawLevel { lines.append("level: \"\(escapeYAML(chart.level))\"") }

            guard let data = lines.joined(separator: "\n").data(using: .utf8) else {
                throw ChartError.invalidValue("Could not encode package manifest")
            }
            try data.write(
                to: package.workspaceURL.appendingPathComponent(manifest.path),
                options: .atomic
            )
        }
    }

    private static func canonicalManifest(
        package: ChartPackage,
        chart: ChartDocument
    ) -> String {
        """
        name: "\(escapeYAML(chart.name))"
        chart: "\(escapeYAML(package.chartPath))"
        music: "\(escapeYAML(chart.song))"
        illustration: "\(escapeYAML(chart.background))"
        composer: "\(escapeYAML(chart.composer))"
        charter: "\(escapeYAML(chart.charter))"
        level: "\(escapeYAML(chart.level))"
        """
    }

    private static func safeAssetName(_ source: String, fallback: String) -> String {
        let ext = URL(fileURLWithPath: source).pathExtension.lowercased()
        let stem = (source as NSString).deletingPathExtension
            .replacingOccurrences(
                of: "[^A-Za-z0-9._ -]",
                with: "_",
                options: .regularExpression
            )
            .trimmingCharacters(in: .whitespacesAndNewlines)
        return "\(stem.isEmpty ? fallback : stem).\(ext.isEmpty ? "bin" : ext)"
    }

    private static func escapeYAML(_ value: String) -> String {
        value.replacingOccurrences(of: "\\", with: "\\\\")
            .replacingOccurrences(of: "\"", with: "\\\"")
            .replacingOccurrences(of: "\n", with: "\\n")
    }
}
