import XCTest
@testable import PhiStudioIOS

final class CoreModelTests: XCTestCase {
    func testBeatTimeNormalizesAndParsesExactly() throws {
        XCTAssertEqual(BeatTime(1, 2, 4), BeatTime(1, 1, 2))
        XCTAssertEqual(try BeatTime.parseFlexible("12.125"), BeatTime(12, 1, 8))
        XCTAssertEqual(try BeatTime.parse("-1:3/4"), BeatTime(-1, 3, 4))
        XCTAssertEqual(
            BeatTime(1, 1, 2).adding(BeatTime(2, 2, 3)),
            BeatTime(4, 1, 6)
        )
    }

    func testUnknownJSONFieldsSurviveRoundTrip() throws {
        let source = """
        {
          "META": {
            "RPEVersion": 123,
            "name": "Round Trip",
            "song": "",
            "background": "",
            "customMeta": {"enabled": true}
          },
          "BPMList": [{"bpm": 120, "startTime": [0, 0, 1]}],
          "judgeLineList": [{
            "Name": "Line",
            "eventLayers": [{
              "moveXEvents": [],
              "moveYEvents": [],
              "rotateEvents": [],
              "alphaEvents": [],
              "speedEvents": []
            }],
            "notes": [{
              "type": 1,
              "startTime": [0, 0, 1],
              "endTime": [0, 0, 1],
              "positionX": 0,
              "customNote": 9007199254740991
            }],
            "customLine": "preserve"
          }],
          "customRoot": [1, 2, 3]
        }
        """
        let chart = try ChartDocument(data: Data(source.utf8))
        let roundTrip = try JSONValue.parse(chart.encoded())
        let root = try XCTUnwrap(roundTrip.objectValue)
        XCTAssertEqual(root["customRoot"], .array([.integer(1), .integer(2), .integer(3)]))
        XCTAssertEqual(root.object("META")?["customMeta"], .object(["enabled": .bool(true)]))

        let line = try XCTUnwrap(root.array("judgeLineList")?.first?.objectValue)
        XCTAssertEqual(line["customLine"], .string("preserve"))
        let note = try XCTUnwrap(line.array("notes")?.first?.objectValue)
        XCTAssertEqual(note["customNote"], .integer(9_007_199_254_740_991))
    }

    func testEveryEasingKeepsEndpoints() {
        for type in Easing.minimumType ... Easing.maximumType {
            XCTAssertEqual(Easing.apply(type, 0), 0, accuracy: 1.0e-9, "type \(type)")
            XCTAssertEqual(Easing.apply(type, 1), 1, accuracy: 1.0e-9, "type \(type)")
        }
    }

    func testEasingNamesMatchPhiStudioIdentifierOrder() {
        let expected = [
            "01 · Linear", "02 · Out Sine", "03 · In Sine", "04 · Out Quad",
            "05 · In Quad", "06 · In Out Sine", "07 · In Out Quad", "08 · Out Cubic",
            "09 · In Cubic", "10 · Out Quart", "11 · In Quart", "12 · In Out Cubic",
            "13 · In Out Quart", "14 · Out Quint", "15 · In Quint", "16 · Out Expo",
            "17 · In Expo", "18 · Out Circ", "19 · In Circ", "20 · Out Back",
            "21 · In Back", "22 · In Out Circ", "23 · In Out Back", "24 · Out Elastic",
            "25 · In Elastic", "26 · Out Bounce", "27 · In Bounce",
            "28 · In Out Bounce", "29 · In Out Elastic",
        ]
        XCTAssertEqual(
            (Easing.minimumType ... Easing.maximumType).map { Easing.title(for: $0) },
            expected
        )
    }

    func testCustomGIFStoryboardProducesControlledAndAutoplayStates() throws {
        var chart = ChartDocument()
        chart.judgeLines[0].texture = "objects/card.gif"
        var event = StoryboardEvent(type: .gif)
        event.startTime = .zero
        event.endTime = BeatTime(2)
        event.value = .numeric(start: 0, end: 1)
        chart.judgeLines[0].storyboard[.gif] = [event]
        chart.markEdited()

        let controlled = try XCTUnwrap(ChartEvaluator.evaluate(chart, at: 1).lines.first)
        XCTAssertTrue(controlled.gifEnabled)
        XCTAssertTrue(controlled.gifControlled)
        XCTAssertEqual(controlled.gifProgress, 0.5, accuracy: 1.0e-9)

        let autoplay = try XCTUnwrap(ChartEvaluator.evaluate(chart, at: 3).lines.first)
        XCTAssertTrue(autoplay.gifEnabled)
        XCTAssertFalse(autoplay.gifControlled)
        XCTAssertEqual(autoplay.gifProgress, 1, accuracy: 1.0e-9)
        XCTAssertEqual(
            autoplay.gifAnchorTimeMilliseconds,
            chart.milliseconds(atBeat: 2)
        )
    }

    func testOfficialAndPECConverters() throws {
        let official = """
        {
          "formatVersion": 3,
          "offset": 0.125,
          "judgeLineList": [{
            "bpm": 120,
            "notesAbove": [],
            "notesBelow": [],
            "speedEvents": [],
            "judgeLineMoveEvents": [],
            "judgeLineRotateEvents": [],
            "judgeLineDisappearEvents": []
          }]
        }
        """
        let convertedOfficial = try ChartConverter.decode(
            data: Data(official.utf8),
            suggestedName: "official.json"
        )
        XCTAssertEqual(convertedOfficial.judgeLines.count, 1)
        XCTAssertEqual(convertedOfficial.offsetMilliseconds, 125)

        let pec = """
        0
        bp 0 120
        n1 0 0 1024 1 0
        cv 0 0 7
        """
        let convertedPEC = try ChartConverter.decode(
            data: Data(pec.utf8),
            suggestedName: "chart.pec"
        )
        XCTAssertEqual(convertedPEC.totalNotes, 1)
        XCTAssertEqual(convertedPEC.judgeLines[0].notes[0].positionX, 675, accuracy: 1.0e-9)
    }

    func testEvaluatorCreatesVisibleNoteAndHUD() {
        var chart = ChartDocument()
        chart.name = "Preview"
        chart.level = "IN 15"
        var note = Note()
        note.startTime = BeatTime(2)
        note.endTime = note.startTime
        note.positionX = 100
        chart.judgeLines[0].notes = [note]

        let scene = ChartEvaluator.evaluate(chart, at: 0)
        XCTAssertEqual(scene.lines.count, 1)
        XCTAssertEqual(scene.lines[0].notes.count, 1)
        XCTAssertEqual(scene.lines[0].notes[0].x, 100, accuracy: 1.0e-9)
        XCTAssertEqual(scene.hud.name, "Preview")
    }

    func testPreparedEvaluatorMatchesReferenceAndInvalidatesAfterEdit() {
        var chart = ChartDocument()
        chart.bpmChanges = [
            BPMChange(bpm: 180, startTime: .zero),
            BPMChange(bpm: 120, startTime: BeatTime(8)),
        ]
        var tap = Note()
        tap.startTime = BeatTime(4)
        tap.endTime = tap.startTime
        tap.positionX = -120
        var hold = Note()
        hold.type = .hold
        hold.startTime = BeatTime(6)
        hold.endTime = BeatTime(10)
        hold.positionX = 220
        chart.judgeLines[0].notes = [tap, hold]

        var move = LineEvent(type: .moveX)
        move.startTime = .zero
        move.endTime = BeatTime(8)
        move.start = -100
        move.end = 100
        chart.judgeLines[0].eventLayers[0][.moveX] = [move]
        chart.markEdited()

        var cache = ChartEvaluationCache()
        for beat in [0.0, 3.5, 6.0, 8.5, 11.0] {
            let reference = ChartEvaluator.evaluate(chart, at: beat)
            let prepared = ChartEvaluator.evaluate(chart, at: beat, cache: &cache)
            XCTAssertEqual(prepared, reference, "beat \(beat)")
        }

        chart.judgeLines[0].notes[0].positionX = 333
        chart.markEdited()
        let changed = ChartEvaluator.evaluate(chart, at: 3.5, cache: &cache)
        XCTAssertEqual(changed.lines[0].notes.first?.x, 333)
    }

    func testPreparedEvaluatorMatchesAndroidLayerAndBaseBPMSemantics() {
        var chart = ChartDocument()
        chart.bpmChanges = [
            BPMChange(bpm: 180, startTime: BeatTime(4)),
        ]
        var activeAlpha = LineEvent(type: .alpha)
        activeAlpha.startTime = .zero
        activeAlpha.endTime = BeatTime(2)
        activeAlpha.start = 100
        activeAlpha.end = 100
        var futureAlpha = LineEvent(type: .alpha)
        futureAlpha.startTime = BeatTime(10)
        futureAlpha.endTime = BeatTime(12)
        futureAlpha.start = 20
        futureAlpha.end = 20
        chart.judgeLines[0].eventLayers = [
            EventLayer(),
            EventLayer(),
        ]
        chart.judgeLines[0].eventLayers[0][.alpha] = [activeAlpha]
        chart.judgeLines[0].eventLayers[1][.alpha] = [futureAlpha]
        chart.markEdited()

        var cache = ChartEvaluationCache()
        let scene = ChartEvaluator.evaluate(chart, at: 1, cache: &cache)
        XCTAssertEqual(scene.chartTimeMilliseconds, chart.milliseconds(atBeat: 1))
        XCTAssertEqual(scene.lines[0].alpha, 100)
    }

    func testEventCloneMatchesAndroidLineAndTimeSequence() throws {
        var chart = ChartDocument()
        chart.judgeLines.append(JudgeLine())
        chart.judgeLines[1].eventLayers[0][.moveX] = []
        var source = LineEvent(type: .moveX)
        source.startTime = BeatTime(1)
        source.endTime = BeatTime(2)
        source.start = 10
        source.end = 20
        chart.judgeLines[0].eventLayers[0][.moveX] = [source]

        let result = try EditorOperations.cloneEvents(
            in: &chart,
            sourceLineIndex: 0,
            layerIndex: 0,
            selectedIDs: [source.id],
            spec: EventCloneSpec(
                lineSequence: [0, 1],
                timeIncrement: BeatTime(1)
            )
        )

        XCTAssertEqual(result.count, 2)
        XCTAssertEqual(chart.judgeLines[0].eventLayers[0][.moveX].count, 1)
        XCTAssertEqual(chart.judgeLines[1].eventLayers[0][.moveX].count, 1)
        XCTAssertEqual(
            chart.judgeLines[0].eventLayers[0][.moveX][0].startTime,
            BeatTime(1)
        )
        XCTAssertEqual(
            chart.judgeLines[1].eventLayers[0][.moveX][0].startTime,
            BeatTime(2)
        )
        XCTAssertNotEqual(chart.judgeLines[0].eventLayers[0][.moveX][0].id, source.id)
    }

    func testHitEffectKeepsTheLineTransformAtTheHitTime() throws {
        var chart = ChartDocument()
        var note = Note()
        note.startTime = BeatTime(1)
        note.endTime = note.startTime
        chart.judgeLines[0].notes = [note]

        var move = LineEvent(type: .moveX)
        move.startTime = .zero
        move.endTime = BeatTime(2)
        move.start = 0
        move.end = 100
        chart.judgeLines[0].eventLayers[0][.moveX] = [move]
        chart.markEdited()

        let scene = ChartEvaluator.evaluate(chart, at: 1.2)
        let effect = try XCTUnwrap(scene.lines[0].hitEffects.first)
        XCTAssertEqual(effect.x, 50, accuracy: 1.0e-6)
        XCTAssertEqual(effect.seed, 1_073_647_552)
    }

    func testEditorSettingsUseAndroidGridSequence() {
        var settings = EditorSettings()
        XCTAssertEqual(settings.verticalGridLines, 11)
        settings.verticalGridLines = 16
        settings.normalize()
        XCTAssertEqual(settings.verticalGridLines, 17)
        XCTAssertEqual(
            EditorSettings.verticalGridOptions,
            [3, 5, 7, 9, 11, 13, 17, 21, 25, 33]
        )
    }

    func testPackagePathNormalizationRejectsTraversal() throws {
        XCTAssertEqual(try PackageService.normalizePath("folder/chart.json"), "folder/chart.json")
        XCTAssertThrowsError(try PackageService.normalizePath("../chart.json"))
        XCTAssertThrowsError(try PackageService.normalizePath("/absolute/chart.json"))
        XCTAssertThrowsError(try PackageService.normalizePath("C:\\chart.json"))
    }

    func testPackageExportUpdatesManifestAndPreservesUnknownFiles() throws {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent("PhiStudioTests-\(UUID().uuidString)", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let sourceWorkspace = root.appendingPathComponent("Source", isDirectory: true)
        let importedWorkspace = root.appendingPathComponent("Imported", isDirectory: true)
        let archive = root.appendingPathComponent("RoundTrip.pez")
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)

        let package = try PackageService.createProject(
            at: sourceWorkspace,
            name: "Before",
            composer: "Composer",
            charter: "Charter",
            level: "IN 10",
            bpm: 120,
            audioSource: nil,
            illustrationSource: nil
        )
        try Data([0xCA, 0xFE]).write(
            to: sourceWorkspace.appendingPathComponent("unknown-resource.bin")
        )
        var changed = package.chart
        changed.name = "After"
        changed.level = "AT 16"

        try PackageService.exportPackage(package, chart: changed, to: archive)
        let imported = try PackageService.importPackage(from: archive, to: importedWorkspace)

        XCTAssertEqual(imported.chart.name, "After")
        XCTAssertEqual(imported.chart.level, "AT 16")
        XCTAssertTrue(
            FileManager.default.fileExists(
                atPath: importedWorkspace.appendingPathComponent("unknown-resource.bin").path
            )
        )
    }
}
