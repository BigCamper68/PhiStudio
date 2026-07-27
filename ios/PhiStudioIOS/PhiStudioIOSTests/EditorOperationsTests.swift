import XCTest
@testable import PhiStudioIOS

final class EditorOperationsTests: XCTestCase {
    func testCurveNotesAreGeneratedBetweenEndpoints() throws {
        var line = JudgeLine()
        var first = Note()
        first.startTime = BeatTime(0)
        first.positionX = -300
        var last = Note()
        last.startTime = BeatTime(4)
        last.positionX = 300
        line.notes = [first, last]

        let ids = try EditorOperations.generateCurveNotes(
            in: &line,
            from: first.id,
            to: last.id,
            density: 1,
            subdivision: 1,
            noteType: .drag,
            easingType: 1
        )

        XCTAssertEqual(ids.count, 3)
        XCTAssertEqual(line.notes.count, 5)
        XCTAssertEqual(line.notes[2].positionX, 0, accuracy: 1.0e-9)
    }

    func testComplexMoveGeneratesPairedEventSegments() throws {
        var spec = ComplexMoveSpec()
        spec.startTime = BeatTime(0)
        spec.endTime = BeatTime(2)
        spec.density = 2
        spec.xExpression = "100*cos(pi*t)"
        spec.yExpression = "100*sin(pi*t)"

        let result = try EditorOperations.generateComplexMove(spec)
        XCTAssertEqual(result.segmentCount, 4)
        XCTAssertEqual(result.moveXEvents.count, 4)
        XCTAssertEqual(result.moveYEvents.count, 4)
        XCTAssertEqual(try XCTUnwrap(result.path.first).x, 100, accuracy: 1.0e-9)
    }

    func testBatchValuesAreDeterministicForSameSeed() throws {
        var profile = BatchValueProfile()
        profile.lowerBound = -10
        profile.upperBound = 10
        profile.disturbance = 2
        profile.randomSeed = 42

        XCTAssertEqual(try profile.values(count: 12), try profile.values(count: 12))
    }

    func testSplitThenGlueKeepsAdjacentValuesContinuous() throws {
        var event = LineEvent(type: .moveX)
        event.startTime = BeatTime(0)
        event.endTime = BeatTime(4)
        event.start = 0
        event.end = 400
        var events = [event]

        let rightID = try EditorOperations.splitEvent(
            &events,
            eventID: event.id,
            at: BeatTime(2)
        )
        XCTAssertEqual(events.count, 2)
        XCTAssertEqual(events[0].end, events[1].start, accuracy: 1.0e-9)

        try EditorOperations.glueEvent(&events, eventID: rightID)
        XCTAssertEqual(events.count, 2)
        let right = try XCTUnwrap(events.first { $0.id == rightID })
        let left = try XCTUnwrap(events.first { $0.id == event.id })
        XCTAssertEqual(right.start, left.end, accuracy: 1.0e-9)
    }

    func testDiagnosticsFindInvalidHoldAndOverlap() {
        var chart = ChartDocument()
        var hold = Note()
        hold.type = .hold
        hold.startTime = BeatTime(2)
        hold.endTime = BeatTime(1)
        chart.judgeLines[0].notes = [hold]

        var first = LineEvent(type: .moveX)
        first.startTime = BeatTime(0)
        first.endTime = BeatTime(2)
        var second = LineEvent(type: .moveX)
        second.startTime = BeatTime(1)
        second.endTime = BeatTime(3)
        chart.judgeLines[0].eventLayers[0].events[.moveX] = [first, second]

        let report = ChartDiagnostics.diagnose(chart)
        XCTAssertTrue(report.items.contains { $0.code == .holdIntervalInvalid })
        XCTAssertTrue(report.items.contains { $0.code == .eventOverlap })
    }
}
