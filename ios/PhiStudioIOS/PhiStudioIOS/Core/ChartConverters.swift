import Foundation

public enum ChartJSONFormat: String, Sendable {
    case rpe
    case officialPhigros
    case unknown

    public static func detect(_ root: JSONValue) -> ChartJSONFormat {
        guard let object = root.objectValue else { return .unknown }
        if object.object("META") != nil,
           object.array("BPMList") != nil,
           object.array("judgeLineList") != nil
        {
            return .rpe
        }
        if object["formatVersion"] != nil,
           object.array("judgeLineList") != nil,
           object.array("BPMList") == nil
        {
            return .officialPhigros
        }
        return .unknown
    }
}

public enum ChartConverter {
    private static let beatPrecision = 1_000_000
    private static let minimumDuration = 1.0 / Double(beatPrecision)

    public static func decode(data: Data, suggestedName: String = "") throws -> ChartDocument {
        if let root = try? JSONValue.parse(data) {
            switch ChartJSONFormat.detect(root) {
            case .rpe:
                return try ChartDocument(json: root)
            case .officialPhigros:
                return try convertOfficialPhigros(root)
            case .unknown:
                break
            }
        }
        guard let text = String(data: data, encoding: .utf8) else {
            throw ChartError.unsupportedFormat("\(suggestedName) is neither UTF-8 PEC nor chart JSON")
        }
        return try convertPEC(text)
    }

    public static func convertOfficialPhigros(_ root: JSONValue) throws -> ChartDocument {
        guard let object = root.objectValue else {
            throw ChartError.unsupportedFormat("Official Phigros root must be an object")
        }
        let version = object.int("formatVersion", default: -1)
        guard version == 1 || version == 3 else {
            throw ChartError.unsupportedFormat(
                "Official Phigros formatVersion \(version) is not supported; expected 1 or 3"
            )
        }
        guard let sourceLines = object.array("judgeLineList"), !sourceLines.isEmpty,
              let firstLine = sourceLines[0].objectValue
        else {
            throw ChartError.unsupportedFormat("Official Phigros chart has no judge lines")
        }
        let baseBPM = try positiveFinite(firstLine.double("bpm"), label: "line BPM")
        var chart = ChartDocument()
        chart.offsetMilliseconds = try milliseconds(object.double("offset"))
        chart.bpmChanges = [BPMChange(bpm: baseBPM)]
        chart.judgeLines = []
        for (index, value) in sourceLines.enumerated() {
            guard let source = value.objectValue else {
                throw ChartError.unsupportedFormat("Official judge line \(index) is malformed")
            }
            let lineBPM = try positiveFinite(source.double("bpm"), label: "line BPM")
            let beatScale = baseBPM / lineBPM / 32
            chart.judgeLines.append(
                try convertOfficialLine(source, index: index, version: version, beatScale: beatScale)
            )
        }
        return chart
    }

    public static func convertPEC(_ source: String) throws -> ChartDocument {
        let lines = source.components(separatedBy: .newlines)
        guard let offsetIndex = lines.firstIndex(where: {
            !stripBOM($0.trimmingCharacters(in: .whitespacesAndNewlines)).isEmpty
        }) else {
            throw pecError(0, "source is empty")
        }
        let offsetText = stripBOM(lines[offsetIndex].trimmingCharacters(in: .whitespaces))
        var chart = ChartDocument()
        chart.offsetMilliseconds = try pecOffset(
            number(offsetText, line: offsetIndex + 1, label: "offset")
        )
        chart.bpmChanges = []
        chart.judgeLines = []
        var builders: [PECLineBuilder] = []
        var lastNote: (line: Int, index: Int)?

        func builder(_ id: Int) -> PECLineBuilder {
            while builders.count <= id {
                builders.append(PECLineBuilder(id: builders.count))
            }
            return builders[id]
        }

        for index in (offsetIndex + 1) ..< lines.count {
            let text = lines[index].trimmingCharacters(in: .whitespacesAndNewlines)
            if text.isEmpty || text.hasPrefix("//") { continue }
            let values = text.split(whereSeparator: \.isWhitespace).map(String.init)
            guard let first = values.first else { continue }
            let command = first.lowercased()
            let lineNumber = index + 1
            switch command {
            case "bp":
                try require(values, exactly: 3, line: lineNumber)
                chart.bpmChanges.append(
                    BPMChange(
                        bpm: try positive(
                            number(values[2], line: lineNumber, label: "BPM"),
                            line: lineNumber,
                            label: "BPM"
                        ),
                        startTime: beat(
                            try number(values[1], line: lineNumber, label: "BPM beat")
                        )
                    )
                )
            case "n1", "n2", "n3", "n4":
                try require(
                    values,
                    atLeast: command == "n2" ? 7 : 6,
                    line: lineNumber
                )
                let lineID = try nonNegativeInt(values[1], line: lineNumber, label: "line ID")
                let target = builder(lineID)
                var note = Note()
                note.type = pecNoteType(command)
                let start = try number(values[2], line: lineNumber, label: "note beat")
                var cursor = 3
                var end = start
                if command == "n2" {
                    end = try number(values[cursor], line: lineNumber, label: "hold end beat")
                    cursor += 1
                }
                note.startTime = beat(start)
                note.endTime = beat(
                    note.type == .hold ? max(start + minimumDuration, end) : start
                )
                note.positionX = try (
                    number(values[cursor], line: lineNumber, label: "note X") * 675 / 1024
                )
                cursor += 1
                note.above = try nonNegativeInt(
                    values[cursor],
                    line: lineNumber,
                    label: "above"
                ) == 1 ? 1 : 0
                cursor += 1
                note.isFake = try binary(values[cursor], line: lineNumber, label: "fake") == 1
                cursor += 1
                while cursor < values.count {
                    let marker = values[cursor]
                    cursor += 1
                    guard cursor < values.count else {
                        throw pecError(lineNumber, "Missing value after \(marker)")
                    }
                    if marker == "#" {
                        note.speed = try number(
                            values[cursor],
                            line: lineNumber,
                            label: "note speed"
                        )
                    } else if marker == "&" {
                        note.size = try number(values[cursor], line: lineNumber, label: "note size")
                    } else {
                        throw pecError(lineNumber, "Unexpected note suffix \(marker)")
                    }
                    cursor += 1
                }
                target.line.notes.append(note)
                lastNote = (lineID, target.line.notes.count - 1)
            case "#", "&":
                try require(values, exactly: 2, line: lineNumber)
                guard let lastNote else { throw pecError(lineNumber, "No note before \(command)") }
                let value = try number(
                    values[1],
                    line: lineNumber,
                    label: command == "#" ? "note speed" : "note size"
                )
                if command == "#" {
                    builders[lastNote.line].line.notes[lastNote.index].speed = value
                } else {
                    builders[lastNote.line].line.notes[lastNote.index].size = value
                }
            case "cv":
                try require(values, exactly: 4, line: lineNumber)
                builder(try nonNegativeInt(values[1], line: lineNumber, label: "line ID"))
                    .speed.append(
                        PECSpeedPoint(
                            beat: try number(values[2], line: lineNumber, label: "speed beat"),
                            value: try number(values[3], line: lineNumber, label: "speed") * 9 / 14
                        )
                    )
            case "cp", "cm":
                let transition = command == "cm"
                try require(values, exactly: transition ? 7 : 5, line: lineNumber)
                let target = builder(
                    try nonNegativeInt(values[1], line: lineNumber, label: "line ID")
                )
                let start = try number(values[2], line: lineNumber, label: "move start beat")
                var cursor = 3
                let end = transition
                    ? try number(values[cursor], line: lineNumber, label: "move end beat")
                    : start
                if transition { cursor += 1 }
                let x = try (
                    (number(values[cursor], line: lineNumber, label: "move X") - 1024)
                        * 675 / 1024
                )
                cursor += 1
                let y = try (
                    (number(values[cursor], line: lineNumber, label: "move Y") - 700)
                        * 450 / 700
                )
                cursor += 1
                let easing = transition
                    ? try pecEasing(values[cursor], line: lineNumber)
                    : 1
                target.add(.moveX, start: start, end: end, target: x, easing: easing, transition: transition)
                target.add(.moveY, start: start, end: end, target: y, easing: easing, transition: transition)
            case "cd", "cr":
                let transition = command == "cr"
                try require(values, exactly: transition ? 6 : 4, line: lineNumber)
                let target = builder(
                    try nonNegativeInt(values[1], line: lineNumber, label: "line ID")
                )
                let start = try number(values[2], line: lineNumber, label: "rotation start beat")
                var cursor = 3
                let end = transition
                    ? try number(values[cursor], line: lineNumber, label: "rotation end beat")
                    : start
                if transition { cursor += 1 }
                let value = try number(values[cursor], line: lineNumber, label: "rotation")
                cursor += 1
                let easing = transition
                    ? try pecEasing(values[cursor], line: lineNumber)
                    : 1
                target.add(
                    .rotate,
                    start: start,
                    end: end,
                    target: value,
                    easing: easing,
                    transition: transition
                )
            case "ca", "cf":
                let transition = command == "cf"
                try require(values, exactly: transition ? 5 : 4, line: lineNumber)
                let target = builder(
                    try nonNegativeInt(values[1], line: lineNumber, label: "line ID")
                )
                let start = try number(values[2], line: lineNumber, label: "alpha start beat")
                var cursor = 3
                let end = transition
                    ? try number(values[cursor], line: lineNumber, label: "alpha end beat")
                    : start
                if transition { cursor += 1 }
                target.add(
                    .alpha,
                    start: start,
                    end: end,
                    target: try number(values[cursor], line: lineNumber, label: "alpha"),
                    easing: 1,
                    transition: transition
                )
            default:
                throw pecError(lineNumber, "Unsupported command \(first)")
            }
        }

        if chart.bpmChanges.isEmpty { chart.bpmChanges = [BPMChange()] }
        chart.bpmChanges.sort { $0.startTime < $1.startTime }
        if builders.isEmpty { _ = builder(0) }
        chart.judgeLines = builders.map { $0.finishedLine() }
        return chart
    }

    private static func convertOfficialLine(
        _ source: [String: JSONValue],
        index: Int,
        version: Int,
        beatScale: Double
    ) throws -> JudgeLine {
        var line = JudgeLine()
        line.name = "Line \(index)"
        line.notes = []
        line.eventLayers = [EventLayer()]

        func appendNotes(_ values: [JSONValue]?, above: Int) throws {
            for value in values ?? [] {
                guard let object = value.objectValue else { continue }
                let officialType = object.int("type", default: -1)
                var note = Note()
                switch officialType {
                case 1: note.type = .tap
                case 2: note.type = .drag
                case 3: note.type = .hold
                case 4: note.type = .flick
                default:
                    throw ChartError.unsupportedFormat(
                        "Unknown official Phigros note type \(officialType)"
                    )
                }
                note.above = above
                let start = try finite(object.double("time"), label: "note time") * beatScale
                let hold = officialType == 3
                    ? try finite(object.double("holdTime"), label: "hold time") * beatScale
                    : 0
                if start < 0 { continue }
                note.startTime = beat(start)
                note.endTime = beat(
                    officialType == 3 ? max(start + minimumDuration, start + hold) : start
                )
                note.positionX = try finite(object.double("positionX"), label: "note X") * 75
                note.speed = officialType == 3
                    ? 1
                    : try finite(object.double("speed", default: 1), label: "note speed")
                line.notes.append(note)
            }
        }
        try appendNotes(source.array("notesAbove"), above: 1)
        try appendNotes(source.array("notesBelow"), above: 0)
        line.notes.sort { $0.startTime < $1.startTime }

        for value in source.array("judgeLineMoveEvents") ?? [] {
            guard let event = value.objectValue else { continue }
            let startX: Double
            let endX: Double
            let startY: Double
            let endY: Double
            if version == 1 {
                let packedStart = try finite(event.double("start"), label: "move X")
                let packedEnd = try finite(event.double("end"), label: "move X")
                startX = (round(packedStart / 1000) / 880 - 0.5) * 1350
                endX = (round(packedEnd / 1000) / 880 - 0.5) * 1350
                startY = (positiveRemainder(packedStart, 1000) / 530 - 0.5) * 900
                endY = (positiveRemainder(packedEnd, 1000) / 530 - 0.5) * 900
            } else {
                startX = (try finite(event.double("start"), label: "move X") - 0.5) * 1350
                endX = (try finite(event.double("end"), label: "move X") - 0.5) * 1350
                startY = (try finite(event.double("start2", default: 0.5), label: "move Y") - 0.5) * 900
                endY = (try finite(event.double("end2", default: 0.5), label: "move Y") - 0.5) * 900
            }
            addOfficialEvent(
                to: &line.eventLayers[0],
                type: .moveX,
                source: event,
                beatScale: beatScale,
                startValue: startX,
                endValue: endX
            )
            addOfficialEvent(
                to: &line.eventLayers[0],
                type: .moveY,
                source: event,
                beatScale: beatScale,
                startValue: startY,
                endValue: endY
            )
        }
        try convertOfficialNumericEvents(
            source.array("judgeLineRotateEvents"),
            into: &line.eventLayers[0],
            type: .rotate,
            beatScale: beatScale,
            multiplier: -1
        )
        try convertOfficialNumericEvents(
            source.array("judgeLineDisappearEvents"),
            into: &line.eventLayers[0],
            type: .alpha,
            beatScale: beatScale,
            multiplier: 255
        )
        for value in source.array("speedEvents") ?? [] {
            guard let event = value.objectValue else { continue }
            let speed = try finite(event.double("value"), label: "speed") * 4.5
            addOfficialEvent(
                to: &line.eventLayers[0],
                type: .speed,
                source: event,
                beatScale: beatScale,
                startValue: speed,
                endValue: speed
            )
        }
        return line
    }

    private static func convertOfficialNumericEvents(
        _ source: [JSONValue]?,
        into layer: inout EventLayer,
        type: EventType,
        beatScale: Double,
        multiplier: Double
    ) throws {
        for value in source ?? [] {
            guard let event = value.objectValue else { continue }
            addOfficialEvent(
                to: &layer,
                type: type,
                source: event,
                beatScale: beatScale,
                startValue: try finite(event.double("start"), label: type.title) * multiplier,
                endValue: try finite(event.double("end"), label: type.title) * multiplier
            )
        }
    }

    private static func addOfficialEvent(
        to layer: inout EventLayer,
        type: EventType,
        source: [String: JSONValue],
        beatScale: Double,
        startValue originalStartValue: Double,
        endValue: Double
    ) {
        var originalStart = source.double("startTime") * beatScale
        let originalEnd = source.double("endTime") * beatScale
        if originalEnd < 0 { return }
        var startValue = originalStartValue
        if originalStart < 0, originalEnd > originalStart {
            let progress = -originalStart / (originalEnd - originalStart)
            startValue += (endValue - startValue) * progress
            originalStart = 0
        }
        let start = max(0, originalStart)
        let end = max(start + minimumDuration, originalEnd)
        var event = LineEvent(type: type)
        event.startTime = beat(start)
        event.endTime = beat(end)
        event.start = startValue
        event.end = endValue
        var values = layer[type]
        merge(event, into: &values)
        layer[type] = values
    }

    private static func merge(_ event: LineEvent, into target: inout [LineEvent]) {
        if var previous = target.last {
            let previousDuration = previous.endTime.doubleValue - previous.startTime.doubleValue
            let eventDuration = event.endTime.doubleValue - event.startTime.doubleValue
            let previousSlope = previousDuration <= 0 ? 0 : (previous.end - previous.start) / previousDuration
            let eventSlope = eventDuration <= 0 ? 0 : (event.end - event.start) / eventDuration
            if close(previous.endTime.doubleValue, event.startTime.doubleValue),
               close(previous.end, event.start),
               close(previousSlope, eventSlope)
            {
                previous.endTime = event.endTime
                previous.end = event.end
                target[target.count - 1] = previous
                return
            }
        }
        target.append(event)
    }

    private static func beat(_ value: Double) -> BeatTime {
        .fromDouble(value, division: beatPrecision)
    }

    private static func finite(_ value: Double, label: String) throws -> Double {
        guard value.isFinite else {
            throw ChartError.unsupportedFormat("\(label) must be finite")
        }
        return value
    }

    private static func positiveFinite(_ value: Double, label: String) throws -> Double {
        let result = try finite(value, label: label)
        guard result > 0 else {
            throw ChartError.unsupportedFormat("\(label) must be positive")
        }
        return result
    }

    private static func positiveRemainder(_ value: Double, _ divisor: Double) -> Double {
        let remainder = value.truncatingRemainder(dividingBy: divisor)
        return remainder < 0 ? remainder + divisor : remainder
    }

    private static func milliseconds(_ seconds: Double) throws -> Int {
        let value = try finite(seconds, label: "chart offset") * 1000
        guard value >= Double(Int.min), value <= Double(Int.max) else {
            throw ChartError.unsupportedFormat("Official chart offset is out of range")
        }
        return Int(value.rounded())
    }

    private static func number(_ value: String, line: Int, label: String) throws -> Double {
        guard let parsed = Double(value), parsed.isFinite else {
            throw pecError(line, "\(label) must be numeric")
        }
        return parsed
    }

    private static func positive(_ value: Double, line: Int, label: String) throws -> Double {
        guard value > 0 else { throw pecError(line, "\(label) must be positive") }
        return value
    }

    private static func nonNegativeInt(_ value: String, line: Int, label: String) throws -> Int {
        guard let parsed = Int(value), parsed >= 0 else {
            throw pecError(line, "\(label) must be a non-negative integer")
        }
        return parsed
    }

    private static func binary(_ value: String, line: Int, label: String) throws -> Int {
        let result = try nonNegativeInt(value, line: line, label: label)
        guard result == 0 || result == 1 else {
            throw pecError(line, "\(label) must be 0 or 1")
        }
        return result
    }

    private static func pecEasing(_ value: String, line: Int) throws -> Int {
        let result = try nonNegativeInt(value, line: line, label: "easing")
        return result > 29 ? 1 : max(1, result)
    }

    private static func require(_ values: [String], exactly count: Int, line: Int) throws {
        guard values.count == count else {
            throw pecError(line, "Expected \(count) fields, got \(values.count)")
        }
    }

    private static func require(_ values: [String], atLeast count: Int, line: Int) throws {
        guard values.count >= count else {
            throw pecError(line, "Expected at least \(count) fields, got \(values.count)")
        }
    }

    private static func pecOffset(_ value: Double) throws -> Int {
        let adjusted = (value - 150).rounded()
        guard adjusted >= Double(Int.min), adjusted <= Double(Int.max) else {
            throw pecError(1, "offset is out of range")
        }
        return Int(adjusted)
    }

    private static func pecNoteType(_ command: String) -> NoteType {
        switch command {
        case "n2": .hold
        case "n3": .flick
        case "n4": .drag
        default: .tap
        }
    }

    private static func stripBOM(_ value: String) -> String {
        value.hasPrefix("\u{FEFF}") ? String(value.dropFirst()) : value
    }

    private static func pecError(_ line: Int, _ message: String) -> ChartError {
        .unsupportedFormat(line > 0 ? "PEC line \(line): \(message)" : "PEC: \(message)")
    }

    fileprivate static func close(_ left: Double, _ right: Double) -> Bool {
        let scale = max(1, max(abs(left), abs(right)))
        return abs(left - right) <= 1.0e-8 * scale
    }
}

private struct PECCommand {
    var start: Double
    var end: Double
    var target: Double
    var easing: Int
    var transition: Bool
    var sequence: Int
}

private struct PECSpeedPoint {
    var beat: Double
    var value: Double
}

private final class PECLineBuilder {
    var line = JudgeLine()
    var commands: [EventType: [PECCommand]] = [:]
    var speed: [PECSpeedPoint] = []
    private var sequence = 0

    init(id: Int) {
        line.name = "Line \(id)"
        line.notes = []
        line.eventLayers = [EventLayer()]
        for type in EventType.allCases { commands[type] = [] }
    }

    func add(
        _ type: EventType,
        start: Double,
        end: Double,
        target: Double,
        easing: Int,
        transition: Bool
    ) {
        commands[type, default: []].append(
            PECCommand(
                start: start,
                end: end,
                target: target,
                easing: easing,
                transition: transition,
                sequence: sequence
            )
        )
        sequence += 1
    }

    func finishedLine() -> JudgeLine {
        line.notes.sort { $0.startTime < $1.startTime }
        for type in [EventType.moveX, .moveY, .rotate, .alpha] {
            line.eventLayers[0][type] = buildCommands(commands[type] ?? [], type: type)
        }
        line.eventLayers[0][.speed] = buildSpeed()
        return line
    }

    private func buildCommands(_ source: [PECCommand], type: EventType) -> [LineEvent] {
        let sorted = source.sorted {
            $0.start == $1.start ? $0.sequence < $1.sequence : $0.start < $1.start
        }
        var target: [LineEvent] = []
        var state = type.defaultValue
        var occupiedUntil = -Double.infinity
        for (index, command) in sorted.enumerated() {
            var start = max(0, command.start)
            if !command.transition {
                state = command.target
                let followedAtSameBeat = index + 1 < sorted.count
                    && ChartConverter.close(sorted[index + 1].start, command.start)
                if !followedAtSameBeat {
                    var point = LineEvent(type: type)
                    point.startTime = .fromDouble(start, division: 1_000_000)
                    point.endTime = .fromDouble(start + 0.000_001, division: 1_000_000)
                    point.start = state
                    point.end = state
                    mergePEC(point, into: &target)
                    occupiedUntil = max(occupiedUntil, point.endTime.doubleValue)
                }
                continue
            }
            start = max(start, occupiedUntil)
            let end = max(start + 0.000_001, command.end)
            var event = LineEvent(type: type)
            event.startTime = .fromDouble(start, division: 1_000_000)
            event.endTime = .fromDouble(end, division: 1_000_000)
            event.start = state
            event.end = command.target
            event.easingType = command.easing
            mergePEC(event, into: &target)
            state = command.target
            occupiedUntil = end
        }
        return target
    }

    private func buildSpeed() -> [LineEvent] {
        let sorted = speed.sorted { $0.beat < $1.beat }
        var unique: [PECSpeedPoint] = []
        for point in sorted {
            let normalized = PECSpeedPoint(beat: max(0, point.beat), value: point.value)
            if let last = unique.last, ChartConverter.close(last.beat, normalized.beat) {
                unique[unique.count - 1] = normalized
            } else {
                unique.append(normalized)
            }
        }
        if unique.isEmpty || unique[0].beat > 0 {
            unique.insert(PECSpeedPoint(beat: 0, value: 0), at: 0)
        }
        var result: [LineEvent] = []
        for index in unique.indices {
            let point = unique[index]
            let proposedEnd = index + 1 < unique.count ? unique[index + 1].beat : point.beat + 0.000_001
            var event = LineEvent(type: .speed)
            event.startTime = .fromDouble(point.beat, division: 1_000_000)
            event.endTime = .fromDouble(
                max(point.beat + 0.000_001, proposedEnd),
                division: 1_000_000
            )
            event.start = point.value
            event.end = point.value
            mergePEC(event, into: &result)
        }
        return result
    }

    private func mergePEC(_ event: LineEvent, into target: inout [LineEvent]) {
        if var previous = target.last {
            let previousDuration = previous.endTime.doubleValue - previous.startTime.doubleValue
            let eventDuration = event.endTime.doubleValue - event.startTime.doubleValue
            let previousSlope = previousDuration <= 0 ? 0 : (previous.end - previous.start) / previousDuration
            let eventSlope = eventDuration <= 0 ? 0 : (event.end - event.start) / eventDuration
            if previous.easingType == event.easingType,
               ChartConverter.close(previous.endTime.doubleValue, event.startTime.doubleValue),
               ChartConverter.close(previous.end, event.start),
               previous.easingType == 1,
               ChartConverter.close(previousSlope, eventSlope)
            {
                previous.endTime = event.endTime
                previous.end = event.end
                target[target.count - 1] = previous
                return
            }
        }
        target.append(event)
    }
}

public struct PackageManifest: Hashable, Sendable {
    public enum Kind: String, Hashable, Sendable {
        case yaml
        case text
    }

    public var path: String
    public var kind: Kind
    public var sourceText: String
    public var fields: [String: String]

    public init(path: String, kind: Kind, sourceText: String) {
        self.path = path
        self.kind = kind
        self.sourceText = sourceText
        var text = sourceText
        if text.hasPrefix("\u{FEFF}") { text.removeFirst() }
        var values: [String: String] = [:]
        for line in text.components(separatedBy: .newlines) {
            let trimmed = line.trimmingCharacters(in: .whitespaces)
            if trimmed.isEmpty || trimmed.hasPrefix("#") { continue }
            guard let separator = trimmed.firstIndex(of: ":"), separator > trimmed.startIndex else {
                continue
            }
            let key = trimmed[..<separator].trimmingCharacters(in: .whitespaces).lowercased()
            if key.isEmpty || values[key] != nil { continue }
            let value = trimmed[trimmed.index(after: separator)...]
                .trimmingCharacters(in: .whitespaces)
            if value == "null" || value == "~" { continue }
            values[key] = Self.decodeScalar(value)
        }
        fields = values
    }

    public subscript(key: String) -> String? {
        fields[key.lowercased()]
    }

    private static func decodeScalar(_ value: String) -> String {
        guard value.count >= 2 else { return value }
        if value.first == "\"", value.last == "\"" {
            return String(value.dropFirst().dropLast())
                .replacingOccurrences(of: "\\\"", with: "\"")
                .replacingOccurrences(of: "\\n", with: "\n")
                .replacingOccurrences(of: "\\r", with: "\r")
                .replacingOccurrences(of: "\\t", with: "\t")
                .replacingOccurrences(of: "\\\\", with: "\\")
        }
        if value.first == "'", value.last == "'" {
            return String(value.dropFirst().dropLast()).replacingOccurrences(of: "''", with: "'")
        }
        return value
    }
}

public struct PackageLimits: Hashable, Sendable {
    public var maximumEntries = 1_024
    public var maximumEntryBytes: Int64 = 128 * 1_024 * 1_024
    public var maximumTotalBytes: Int64 = 512 * 1_024 * 1_024
    public var maximumCompressedEntryBytes: Int64 = 128 * 1_024 * 1_024
    public var maximumArchiveBytes: Int64 = 256 * 1_024 * 1_024

    public init() {}
}
