import Foundation

public enum NoteType: Int, CaseIterable, Identifiable, Sendable {
    case tap = 1
    case hold = 2
    case flick = 3
    case drag = 4

    public var id: Int { rawValue }

    public var title: String {
        switch self {
        case .tap: "Tap"
        case .hold: "Hold"
        case .flick: "Flick"
        case .drag: "Drag"
        }
    }
}

public enum EventType: String, CaseIterable, Identifiable, Sendable {
    case moveX = "moveXEvents"
    case moveY = "moveYEvents"
    case rotate = "rotateEvents"
    case alpha = "alphaEvents"
    case speed = "speedEvents"

    public var id: String { rawValue }

    public var title: String {
        switch self {
        case .moveX: "Move X"
        case .moveY: "Move Y"
        case .rotate: "Rotate"
        case .alpha: "Alpha"
        case .speed: "Speed"
        }
    }

    public var displayRange: ClosedRange<Double> {
        switch self {
        case .moveX: -675 ... 675
        case .moveY: -450 ... 450
        case .rotate: -180 ... 180
        case .alpha: 0 ... 255
        case .speed: -20 ... 20
        }
    }

    public var defaultValue: Double {
        switch self {
        case .alpha: 255
        case .speed: 10
        default: 0
        }
    }
}

public enum AttachedUIElement: String, CaseIterable, Identifiable, Sendable {
    case pause
    case comboNumber
    case combo
    case score
    case bar
    case name
    case level

    public var id: String { rawValue }

    public init?(jsonValue: JSONValue?) {
        guard let source = jsonValue?.stringValue else { return nil }
        let normalized = source.lowercased()
            .replacingOccurrences(of: "_", with: "")
            .replacingOccurrences(of: "-", with: "")
            .replacingOccurrences(of: " ", with: "")
        switch normalized {
        case "pause": self = .pause
        case "combonumber": self = .comboNumber
        case "combo": self = .combo
        case "score": self = .score
        case "bar", "progress", "progressbar": self = .bar
        case "name": self = .name
        case "level": self = .level
        default: return nil
        }
    }
}

public struct BPMChange: Identifiable, Hashable, Sendable {
    public var id = UUID()
    public var bpm = 120.0
    public var startTime = BeatTime.zero
    public var raw: [String: JSONValue] = [:]

    public init(
        id: UUID = UUID(),
        bpm: Double = 120,
        startTime: BeatTime = .zero,
        raw: [String: JSONValue] = [:]
    ) {
        self.id = id
        self.bpm = bpm
        self.startTime = startTime
        self.raw = raw
    }

    public init(json: JSONValue) throws {
        guard let object = json.objectValue else {
            throw ChartError.invalidJSON("BPM item must be an object")
        }
        bpm = object.double("bpm", default: 120)
        startTime = BeatTime(json: object["startTime"])
        raw = object.removing(["bpm", "startTime"])
    }

    public var json: JSONValue {
        var object = raw
        object["bpm"] = .number(bpm)
        object["startTime"] = startTime.json
        return .object(object)
    }
}

public struct Note: Identifiable, Hashable, Sendable {
    public var id = UUID()
    public var above = 1
    public var alpha = 255
    public var startTime = BeatTime.zero
    public var endTime = BeatTime.zero
    public var isFake = false
    public var positionX = 0.0
    public var size = 1.0
    public var speed = 1.0
    public var type = NoteType.tap
    public var visibleTime = 999_999.0
    public var yOffset = 0.0
    public var tintRGB: Int?
    public var tintTail: [JSONValue] = []
    public var hitEffectTintRGB: Int?
    public var hitEffectTintTail: [JSONValue] = []
    public var judgeArea = 1.0
    public var raw: [String: JSONValue] = [:]

    public init() {}

    public init(json: JSONValue) throws {
        guard let object = json.objectValue else {
            throw ChartError.invalidJSON("Note must be an object")
        }
        above = object.int("above", default: 1)
        alpha = object.int("alpha", default: 255)
        startTime = BeatTime(json: object["startTime"])
        endTime = BeatTime(json: object["endTime"])
        isFake = object.int("isFake") != 0
        positionX = object.double("positionX")
        size = object.double("size", default: 1)
        speed = object.double("speed", default: 1)
        type = NoteType(rawValue: object.int("type", default: 1)) ?? .tap
        visibleTime = object.double("visibleTime", default: 999_999)
        yOffset = object.double("yOffset")
        judgeArea = object.double("judgeArea", default: 1)
        (tintRGB, tintTail) = Self.readColor(object["tint"])
        (hitEffectTintRGB, hitEffectTintTail) = Self.readColor(object["tintHitEffects"])
        raw = object.removing([
            "above", "alpha", "startTime", "endTime", "isFake", "positionX", "size",
            "speed", "type", "visibleTime", "yOffset", "tint", "tintHitEffects", "judgeArea",
        ])
    }

    public var json: JSONValue {
        var object = raw
        object["above"] = .integer(Int64(above))
        object["alpha"] = .integer(Int64(alpha))
        object["startTime"] = startTime.json
        object["endTime"] = endTime.json
        object["isFake"] = .integer(isFake ? 1 : 0)
        object["positionX"] = .number(positionX)
        object["size"] = .number(size)
        object["speed"] = .number(speed)
        object["type"] = .integer(Int64(type.rawValue))
        object["visibleTime"] = .number(visibleTime)
        object["yOffset"] = .number(yOffset)
        if let tintRGB {
            object["tint"] = Self.colorJSON(tintRGB, tail: tintTail)
        }
        if let hitEffectTintRGB {
            object["tintHitEffects"] = Self.colorJSON(hitEffectTintRGB, tail: hitEffectTintTail)
        }
        if judgeArea != 1 || raw["judgeArea"] != nil {
            object["judgeArea"] = .number(judgeArea)
        }
        return .object(object)
    }

    private static func readColor(_ json: JSONValue?) -> (Int?, [JSONValue]) {
        guard let values = json?.arrayValue, values.count >= 3 else { return (nil, []) }
        let red = min(255, max(0, values[0].intValue ?? 255))
        let green = min(255, max(0, values[1].intValue ?? 255))
        let blue = min(255, max(0, values[2].intValue ?? 255))
        return ((red << 16) | (green << 8) | blue, Array(values.dropFirst(3)))
    }

    private static func colorJSON(_ rgb: Int, tail: [JSONValue]) -> JSONValue {
        .array([
            .integer(Int64((rgb >> 16) & 0xFF)),
            .integer(Int64((rgb >> 8) & 0xFF)),
            .integer(Int64(rgb & 0xFF)),
        ] + tail)
    }
}

public struct LineEvent: Identifiable, Hashable, Sendable {
    public var id = UUID()
    public var type = EventType.moveX
    public var startTime = BeatTime.zero
    public var endTime = BeatTime(1, 0, 1)
    public var start = 0.0
    public var end = 0.0
    public var easingType = 1
    public var easingLeft = 0.0
    public var easingRight = 1.0
    public var linkGroup = 0
    public var usesBezier = false
    public var bezierPoints = [0.0, 0.0, 0.0, 0.0]
    public var raw: [String: JSONValue] = [:]

    public init(type: EventType = .moveX) {
        self.type = type
        start = type.defaultValue
        end = start
    }

    public init(type: EventType, json: JSONValue) throws {
        guard let object = json.objectValue else {
            throw ChartError.invalidJSON("\(type.title) event must be an object")
        }
        self.type = type
        startTime = BeatTime(json: object["startTime"])
        endTime = BeatTime(json: object["endTime"])
        start = object.double("start", default: type.defaultValue)
        end = object.double("end", default: start)
        easingType = type == .speed ? 1 : max(1, object.int("easingType", default: 1))
        easingLeft = object.double("easingLeft")
        easingRight = object.double("easingRight", default: 1)
        linkGroup = object.int("linkgroup")
        usesBezier = object.int("bezier") != 0
        if let points = object.array("bezierPoints") {
            for index in 0 ..< min(4, points.count) {
                bezierPoints[index] = points[index].doubleValue ?? 0
            }
        }
        let common: Set<String> = ["startTime", "endTime", "start", "end", "linkgroup"]
        raw = object.removing(type == .speed
            ? common
            : common.union([
                "easingType", "easingLeft", "easingRight", "bezier", "bezierPoints",
            ]))
    }

    public var json: JSONValue {
        var object = raw
        object["startTime"] = startTime.json
        object["endTime"] = endTime.json
        object["start"] = .number(start)
        object["end"] = .number(end)
        object["linkgroup"] = .integer(Int64(linkGroup))
        if type != .speed {
            object["easingType"] = .integer(Int64(max(1, easingType)))
            object["easingLeft"] = .number(easingLeft)
            object["easingRight"] = .number(easingRight)
            object["bezier"] = .integer(usesBezier ? 1 : 0)
            object["bezierPoints"] = .array(paddedBezierPoints.map(JSONValue.number))
        }
        return .object(object)
    }

    public var paddedBezierPoints: [Double] {
        Array((bezierPoints + [0, 0, 0, 0]).prefix(4))
    }

    public func value(at beat: Double) -> Double {
        let startBeat = startTime.doubleValue
        let endBeat = endTime.doubleValue
        if beat <= startBeat || endBeat <= startBeat { return start }
        if beat >= endBeat { return end }
        let input = (beat - startBeat) / (endBeat - startBeat)
        return start + (end - start) * progress(at: input)
    }

    public func progress(at input: Double) -> Double {
        if type == .speed { return Easing.apply(1, input) }
        if usesBezier {
            return Easing.cubicBezierWindowed(
                input,
                left: easingLeft,
                right: easingRight,
                points: paddedBezierPoints
            )
        }
        return Easing.applyWindowed(
            easingType,
            input,
            left: easingLeft,
            right: easingRight
        )
    }

    public func renderedSpeedValue(
        at beat: Double,
        rpeVersion: Int,
        useRPE170Speed: Bool
    ) -> Double {
        let startBeat = startTime.doubleValue
        let endBeat = endTime.doubleValue
        if beat <= startBeat || endBeat <= startBeat { return start }
        if beat >= endBeat { return end }
        let input = (beat - startBeat) / (endBeat - startBeat)
        return start + (end - start) * renderedSpeedProgress(
            input,
            rpeVersion: rpeVersion,
            useRPE170Speed: useRPE170Speed
        )
    }

    public func integratedRenderedSpeed(
        from fromBeat: Double,
        to toBeat: Double,
        rpeVersion: Int,
        useRPE170Speed: Bool
    ) -> Double {
        guard fromBeat.isFinite, toBeat.isFinite, toBeat > fromBeat else { return 0 }
        let eventStart = startTime.doubleValue
        let eventEnd = endTime.doubleValue
        guard eventStart.isFinite, eventEnd.isFinite, eventEnd > eventStart else {
            return start * (toBeat - fromBeat)
        }
        var result = 0.0
        var cursor = fromBeat
        if cursor < eventStart {
            let end = min(toBeat, eventStart)
            result += start * (end - cursor)
            cursor = end
        }
        if cursor < toBeat, cursor < eventEnd {
            let insideEnd = min(toBeat, eventEnd)
            let duration = eventEnd - eventStart
            let left = max(0, (cursor - eventStart) / duration)
            let right = min(1, (insideEnd - eventStart) / duration)
            let area = renderedSpeedIntegral(
                right,
                rpeVersion: rpeVersion,
                useRPE170Speed: useRPE170Speed
            ) - renderedSpeedIntegral(
                left,
                rpeVersion: rpeVersion,
                useRPE170Speed: useRPE170Speed
            )
            result += duration * (start * (right - left) + (end - start) * area)
            cursor = insideEnd
        }
        if cursor < toBeat { result += end * (toBeat - cursor) }
        return result
    }

    private var sourceEasingType: Int {
        max(0, raw.int("easingType", default: 1))
    }

    private func renderedSpeedProgress(
        _ input: Double,
        rpeVersion: Int,
        useRPE170Speed: Bool
    ) -> Double {
        guard useRPE170Speed else { return input }
        if rpeVersion < 170 || sourceEasingType <= 1 {
            return sourceEasingType == 0 && rpeVersion >= 170 ? 0 : input
        }
        if usesBezier {
            return Easing.cubicBezierWindowed(
                input,
                left: easingLeft,
                right: easingRight,
                points: paddedBezierPoints
            )
        }
        return Easing.applyWindowed(
            sourceEasingType,
            input,
            left: easingLeft,
            right: easingRight
        )
    }

    private func renderedSpeedIntegral(
        _ input: Double,
        rpeVersion: Int,
        useRPE170Speed: Bool
    ) -> Double {
        let t = min(1, max(0, input))
        guard useRPE170Speed else { return t * t / 2 }
        if rpeVersion < 170 || sourceEasingType == 1 { return t * t / 2 }
        if sourceEasingType == 0 { return 0 }
        if usesBezier {
            return Easing.integralCubicBezierWindowed(
                t,
                left: easingLeft,
                right: easingRight,
                points: paddedBezierPoints
            )
        }
        return Easing.integralWindowed(
            sourceEasingType,
            t,
            left: easingLeft,
            right: easingRight
        )
    }
}

public struct EventLayer: Identifiable, Hashable, Sendable {
    public var id = UUID()
    public var events: [EventType: [LineEvent]] = [:]
    public var raw: [String: JSONValue] = [:]
    public var sourceWasNull = false

    public init(createDefaults: Bool = false) {
        for type in EventType.allCases {
            events[type] = []
            if createDefaults {
                var event = LineEvent(type: type)
                event.startTime = .zero
                event.endTime = BeatTime(1, 0, 1)
                events[type] = [event]
            }
        }
    }

    public init(json: JSONValue) throws {
        self.init()
        if case .null = json {
            sourceWasNull = true
            return
        }
        guard let object = json.objectValue else {
            throw ChartError.invalidJSON("Event layer must be an object or null")
        }
        raw = object.removing(Set(EventType.allCases.map(\.rawValue)))
        for type in EventType.allCases {
            events[type] = try (object.array(type.rawValue) ?? []).compactMap { value in
                guard value.objectValue != nil else { return nil }
                return try LineEvent(type: type, json: value)
            }.sorted { $0.startTime < $1.startTime }
        }
    }

    public subscript(type: EventType) -> [LineEvent] {
        get { events[type] ?? [] }
        set {
            sourceWasNull = false
            events[type] = newValue.sorted { $0.startTime < $1.startTime }
        }
    }

    public var eventCount: Int {
        events.values.reduce(0) { $0 + $1.count }
    }

    public var json: JSONValue {
        if sourceWasNull, eventCount == 0 { return .null }
        var object = raw
        for type in EventType.allCases {
            let values = (events[type] ?? []).sorted { $0.startTime < $1.startTime }
            if values.isEmpty {
                object[type.rawValue] = nil
            } else {
                object[type.rawValue] = .array(values.map(\.json))
            }
        }
        return .object(object)
    }

    public func value(_ type: EventType, at beat: Double) -> Double {
        let values = events[type] ?? []
        var low = 0
        var high = values.count
        while low < high {
            let middle = (low + high) / 2
            if values[middle].startTime.doubleValue <= beat {
                low = middle + 1
            } else {
                high = middle
            }
        }
        return low > 0 ? values[low - 1].value(at: beat) : type.defaultValue
    }

    public func overlaps(_ candidate: LineEvent, ignoring id: UUID? = nil) -> Bool {
        (events[candidate.type] ?? []).contains { existing in
            existing.id != id
                && candidate.startTime < existing.endTime
                && candidate.endTime > existing.startTime
        }
    }
}
