import Foundation
import XCTest
import UniformTypeIdentifiers
@testable import PhiStudioIOS

final class AppBehaviorTests: XCTestCase {
    @MainActor
    func testSettingsUpdatesAreAtomicNormalizedAndPersisted() throws {
        let suiteName = "PhiStudio.SettingsTests.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
        defer { defaults.removePersistentDomain(forName: suiteName) }

        let store = SettingsStore(defaults: defaults)
        for expected in [false, true, false, true] {
            store.update(\.showHorizontalGrid, to: expected)
            XCTAssertEqual(store.value.showHorizontalGrid, expected)
        }
        store.update(\.autosaveDelaySeconds, to: -100)
        XCTAssertEqual(store.value.autosaveDelaySeconds, 0.5)

        let restored = SettingsStore(defaults: defaults)
        XCTAssertTrue(restored.value.showHorizontalGrid)
        XCTAssertEqual(restored.value.autosaveDelaySeconds, 0.5)
    }

    func testFileSelectionPolicyAcceptsEverySupportedExtension() {
        XCTAssertTrue(UTType.phiPackage.conforms(to: .zip))
        XCTAssertTrue(UTType.pecChart.conforms(to: .plainText))

        for ext in ["json", "pec", "pez", "zip"] {
            XCTAssertTrue(
                ImportedFileKind.chart.accepts(URL(fileURLWithPath: "/tmp/chart.\(ext)"))
            )
        }
        for ext in ["aac", "flac", "m4a", "mp3", "ogg", "wav"] {
            XCTAssertTrue(
                ImportedFileKind.audio.accepts(URL(fileURLWithPath: "/tmp/music.\(ext)"))
            )
        }
        for ext in ["bmp", "gif", "jpeg", "jpg", "png", "webp"] {
            XCTAssertTrue(
                ImportedFileKind.illustration.accepts(
                    URL(fileURLWithPath: "/tmp/image.\(ext)")
                )
            )
        }
        XCTAssertFalse(
            ImportedFileKind.chart.accepts(URL(fileURLWithPath: "/tmp/unsupported.txt"))
        )
    }

    func testCustomLineTexturePathNormalizationRejectsWorkspaceEscapes() {
        XCTAssertEqual(
            PreviewLineTextureLoader.normalizedName("./objects/card.png"),
            "objects/card.png"
        )
        XCTAssertNil(PreviewLineTextureLoader.normalizedName("line.png"))
        XCTAssertNil(PreviewLineTextureLoader.normalizedName("../secret.png"))
        XCTAssertNil(PreviewLineTextureLoader.normalizedName("/tmp/secret.png"))
        XCTAssertNil(PreviewLineTextureLoader.normalizedName("C:\\secret.png"))
    }

    func testAppAdvertisesGenericInboundFilesWithoutClaimingJSONOrZIP() throws {
        let documentTypes = try XCTUnwrap(
            Bundle.main.object(forInfoDictionaryKey: "CFBundleDocumentTypes")
                as? [[String: Any]]
        )
        let advertisedTypes = documentTypes.flatMap {
            $0["LSItemContentTypes"] as? [String] ?? []
        }
        XCTAssertTrue(advertisedTypes.contains("public.data"))
        XCTAssertTrue(advertisedTypes.contains("public.json"))
        XCTAssertTrue(advertisedTypes.contains("public.zip-archive"))

        let declarations = try XCTUnwrap(
            Bundle.main.object(forInfoDictionaryKey: "UTExportedTypeDeclarations")
                as? [[String: Any]]
        )
        let identifiers = declarations.compactMap { $0["UTTypeIdentifier"] as? String }
        XCTAssertFalse(identifiers.contains("com.bigcamper68.phistudio.rpe-chart"))
        let package = try XCTUnwrap(
            declarations.first {
                $0["UTTypeIdentifier"] as? String == "com.bigcamper68.phistudio.package"
            }
        )
        let tags = try XCTUnwrap(package["UTTypeTagSpecification"] as? [String: Any])
        XCTAssertEqual(tags["public.filename-extension"] as? String, "pez")
    }

    func testSelectedFileIsCopiedIntoAppOwnedStaging() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(
            "PhiStudioSelectionTests-\(UUID().uuidString)",
            isDirectory: true
        )
        defer { try? FileManager.default.removeItem(at: root) }
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let source = root.appendingPathComponent("chart.json")
        let expected = Data(#"{"META":{"name":"Picked"}}"#.utf8)
        try expected.write(to: source)

        let staged = try await ImportedFileStager.stage(source, as: .chart)
        XCTAssertNotEqual(staged.url, source)
        XCTAssertEqual(staged.url.lastPathComponent, source.lastPathComponent)
        XCTAssertEqual(try Data(contentsOf: staged.url), expected)

        let stagedURL = staged.url
        staged.remove()
        XCTAssertFalse(FileManager.default.fileExists(atPath: stagedURL.path))
    }

    func testSavedProjectReopensAndExportsAfterLibraryRecreation() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(
            "PhiStudioLibraryTests-\(UUID().uuidString)",
            isDirectory: true
        )
        defer { try? FileManager.default.removeItem(at: root) }
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)

        let projectID: UUID
        do {
            let library = ProjectLibrary(rootURL: root)
            var loaded = try await library.createProject(
                name: "Persistent chart",
                composer: "Composer",
                charter: "Charter",
                level: "IN 12",
                bpm: 180,
                audioSource: nil,
                illustrationSource: nil
            )
            let customTexture = loaded.package.workspaceURL
                .appendingPathComponent("storyboard-object.png")
            try Data([0x89, 0x50, 0x4E, 0x47]).write(to: customTexture)
            loaded.package.chart.judgeLines[0].texture = "storyboard-object.png"
            _ = try await library.save(
                projectID: loaded.record.id,
                package: loaded.package,
                chart: loaded.package.chart
            )
            projectID = loaded.record.id
        }

        let restoredLibrary = ProjectLibrary(rootURL: root)
        let reopened = try await restoredLibrary.open(projectID)
        XCTAssertEqual(reopened.package.chart.name, "Persistent chart")
        XCTAssertEqual(
            reopened.package.chart.judgeLines[0].texture,
            "storyboard-object.png"
        )
        XCTAssertTrue(
            FileManager.default.fileExists(
                atPath: reopened.package.workspaceURL
                    .appendingPathComponent("storyboard-object.png").path
            )
        )

        let archive = root.appendingPathComponent("Persistent chart.pez")
        try await restoredLibrary.export(projectID, to: archive)
        XCTAssertTrue(FileManager.default.fileExists(atPath: archive.path))
    }

    func testLegacyDocumentsLibraryMigratesBeforeOpening() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(
            "PhiStudioMigrationTests-\(UUID().uuidString)",
            isDirectory: true
        )
        defer { try? FileManager.default.removeItem(at: root) }
        let legacyBase = root.appendingPathComponent("Documents", isDirectory: true)
        let primaryBase = root.appendingPathComponent("Application Support", isDirectory: true)
        try FileManager.default.createDirectory(
            at: legacyBase,
            withIntermediateDirectories: true
        )

        let legacyLibrary = ProjectLibrary(rootURL: legacyBase)
        let created = try await legacyLibrary.createProject(
            name: "Legacy chart",
            composer: "",
            charter: "",
            level: "",
            bpm: 120,
            audioSource: nil,
            illustrationSource: nil
        )

        let migratedLibrary = ProjectLibrary(
            rootURL: primaryBase,
            legacyRootURL: legacyBase
        )
        let reopened = try await migratedLibrary.open(created.record.id)
        XCTAssertEqual(reopened.package.chart.name, "Legacy chart")
        XCTAssertTrue(
            reopened.package.workspaceURL.standardizedFileURL.path.hasPrefix(
                primaryBase.standardizedFileURL.path
            )
        )
    }

    @MainActor
    func testPlaybackRateIsClampedWithoutRecursiveAssignment() {
        let audio = AudioController()
        audio.setRate(-10)
        XCTAssertEqual(audio.rate, 0.25)
        audio.setRate(10)
        XCTAssertEqual(audio.rate, 2)
        audio.setRate(0.75)
        XCTAssertEqual(audio.rate, 0.75)
        audio.setRate(.nan)
        XCTAssertEqual(audio.rate, 1)
        audio.shutdown()
    }
}
