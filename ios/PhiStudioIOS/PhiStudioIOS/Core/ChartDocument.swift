import Foundation

public struct ChartDocument: Hashable, Sendable {
    public var name = "Untitled"
    public var composer = ""
    public var charter = ""
    public var level = ""
    public var chartID = ""
    public var song = ""
    public var background = ""
    public var offsetMilliseconds = 0
    public var rpeVersion = 123
    public var bpmChanges: [BPMChange]
    public var judgeLines: [JudgeLine]
    public var rawRoot: [String: JSONValue] = [:]
    public var rawMeta: [String: JSONValue] = [:]
    public private(set) var revision: UInt64 = 0

    public init() {
        bpmChanges = [BPMChange()]
        judgeLines = [JudgeLine()]
    }

    private init(empty _: Bool) {
        bpmChanges = []
        judgeLines = []
    }

    public init(data: Data) throws {
        let root: JSONValue
        do {
            root = try JSONValue.parse(data)
        } catch {
            throw ChartError.invalidJSON(error.localizedDescription)
        }
        try self.init(json: root)
    }

    public init(json: JSONValue) throws {
        guard let root = json.objectValue else {
            throw ChartError.invalidJSON("Chart root must be an object")
        }
        self.init(empty: true)
        rawRoot = root.removing(["META", "BPMList", "judgeLineList"])
        if let meta = root.object("META") {
            rawMeta = meta.removing([
                "RPEVersion", "background", "charter", "composer", "id",
                "level", "name", "offset", "song",
            ])
            name = meta.string("name", default: "Untitled")
            composer = meta.string("composer")
            charter = meta.string("charter")
            level = meta.string("level")
            chartID = meta.string("id")
            song = meta.string("song")
            background = meta.string("background")
            offsetMilliseconds = meta.int("offset")
            rpeVersion = meta.int("RPEVersion", default: 123)
        }
        bpmChanges = try (root.array("BPMList") ?? []).compactMap { value in
            guard value.objectValue != nil else { return nil }
            return try BPMChange(json: value)
        }
        if bpmChanges.isEmpty { bpmChanges = [BPMChange()] }
        bpmChanges.sort { $0.startTime < $1.startTime }

        judgeLines = try (root.array("judgeLineList") ?? []).compactMap { value in
            guard value.objectValue != nil else { return nil }
            return try JudgeLine(json: value)
        }
        if judgeLines.isEmpty { judgeLines = [JudgeLine()] }
    }

    public var json: JSONValue {
        var root = rawRoot
        var meta = rawMeta
        meta["RPEVersion"] = .integer(Int64(rpeVersion))
        meta["background"] = .string(background)
        meta["charter"] = .string(charter)
        meta["composer"] = .string(composer)
        meta["id"] = .string(chartID)
        meta["level"] = .string(level)
        meta["name"] = .string(name)
        meta["offset"] = .integer(Int64(offsetMilliseconds))
        meta["song"] = .string(song)
        root["META"] = .object(meta)
        root["BPMList"] = .array(bpmChanges.sorted { $0.startTime < $1.startTime }.map(\.json))
        root["judgeLineList"] = .array(judgeLines.map(\.json))
        if root["judgeLineGroup"] == nil {
            root["judgeLineGroup"] = .array([.string("Default")])
        }
        return .object(root)
    }

    public func encoded(prettyPrinted: Bool = false) throws -> Data {
        try json.encoded(prettyPrinted: prettyPrinted)
    }

    public mutating func markEdited() {
        revision &+= 1
    }

    public mutating func sort() {
        bpmChanges.sort { $0.startTime < $1.startTime }
        for index in judgeLines.indices {
            judgeLines[index].notes.sort { $0.startTime < $1.startTime }
            for layerIndex in judgeLines[index].eventLayers.indices {
                for type in EventType.allCases {
                    judgeLines[index].eventLayers[layerIndex].events[type]?.sort {
                        $0.startTime < $1.startTime
                    }
                }
            }
        }
    }

    public func bpm(at beat: Double) -> Double {
        var current = 120.0
        for change in bpmChanges {
            if change.startTime.doubleValue > beat { break }
            current = validBPM(change.bpm, fallback: current)
        }
        if let first = bpmChanges.first, first.startTime.doubleValue > beat {
            current = validBPM(first.bpm, fallback: current)
        }
        return current
    }

    /// Converts chart beat to elapsed chart milliseconds, excluding META offset.
    public func milliseconds(atBeat target: Double) -> Int64 {
        let beat = max(0, target.isFinite ? target : 0)
        if beat == 0 { return 0 }
        var milliseconds = 0.0
        var cursorBeat = 0.0
        var currentBPM = bpm(at: 0)
        for change in bpmChanges {
            let changeBeat = max(0, change.startTime.doubleValue)
            if changeBeat <= cursorBeat {
                currentBPM = validBPM(change.bpm, fallback: currentBPM)
                continue
            }
            if changeBeat >= beat { break }
            milliseconds += (changeBeat - cursorBeat) * 60_000 / currentBPM
            cursorBeat = changeBeat
            currentBPM = validBPM(change.bpm, fallback: currentBPM)
        }
        milliseconds += (beat - cursorBeat) * 60_000 / currentBPM
        return Int64(max(0, milliseconds.rounded()))
    }

    /// Converts elapsed chart milliseconds, excluding META offset, into a beat.
    public func beat(atMilliseconds target: Int64) -> Double {
        var remaining = Double(max(0, target))
        var cursorBeat = 0.0
        var currentBPM = bpm(at: 0)
        for change in bpmChanges {
            let changeBeat = max(0, change.startTime.doubleValue)
            if changeBeat <= cursorBeat {
                currentBPM = validBPM(change.bpm, fallback: currentBPM)
                continue
            }
            let segmentMilliseconds = (changeBeat - cursorBeat) * 60_000 / currentBPM
            if remaining <= segmentMilliseconds {
                return cursorBeat + remaining * currentBPM / 60_000
            }
            remaining -= segmentMilliseconds
            cursorBeat = changeBeat
            currentBPM = validBPM(change.bpm, fallback: currentBPM)
        }
        return cursorBeat + remaining * currentBPM / 60_000
    }

    public func audioMilliseconds(atBeat beat: Double, packageOffset: Int64 = 0) -> Int64 {
        let combined = Self.saturatingAdd(Int64(offsetMilliseconds), packageOffset)
        return max(0, Self.saturatingAdd(milliseconds(atBeat: beat), combined))
    }

    public func beat(atAudioMilliseconds milliseconds: Int64, packageOffset: Int64 = 0) -> Double {
        let combined = Self.saturatingAdd(Int64(offsetMilliseconds), packageOffset)
        return beat(atMilliseconds: max(0, Self.saturatingSubtract(milliseconds, combined)))
    }

    public var totalNotes: Int {
        judgeLines.reduce(0) { $0 + $1.notes.count }
    }

    public var totalEvents: Int {
        judgeLines.reduce(0) { $0 + $1.eventCount }
    }

    public var finalBeat: Double {
        var result = 1.0
        for line in judgeLines {
            for note in line.notes {
                result = max(
                    result,
                    max(note.startTime.doubleValue, note.endTime.doubleValue)
                )
            }
            for layer in line.eventLayers {
                for values in layer.events.values {
                    for event in values {
                        result = max(
                            result,
                            max(event.startTime.doubleValue, event.endTime.doubleValue)
                        )
                    }
                }
            }
            for values in line.storyboard.events.values {
                for event in values {
                    result = max(
                        result,
                        max(event.startTime.doubleValue, event.endTime.doubleValue)
                    )
                }
            }
        }
        return result
    }

    private func validBPM(_ value: Double, fallback: Double) -> Double {
        value.isFinite && value > 0 ? value : fallback
    }

    private static func saturatingAdd(_ left: Int64, _ right: Int64) -> Int64 {
        let result = left.addingReportingOverflow(right)
        if !result.overflow { return result.partialValue }
        return right >= 0 ? .max : .min
    }

    private static func saturatingSubtract(_ left: Int64, _ right: Int64) -> Int64 {
        if right == .min { return left >= 0 ? .max : left - right }
        return saturatingAdd(left, -right)
    }
}
