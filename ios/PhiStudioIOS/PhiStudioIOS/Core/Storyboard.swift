import Foundation

public enum StoryboardEventType: String, CaseIterable, Identifiable, Sendable {
    case scaleX = "scaleXEvents"
    case scaleY = "scaleYEvents"
    case color = "colorEvents"
    case paint = "paintEvents"
    case text = "textEvents"
    case incline = "inclineEvents"
    case gif = "gifEvents"

    public var id: String { rawValue }

    public var title: String {
        switch self {
        case .scaleX: "Scale X"
        case .scaleY: "Scale Y"
        case .color: "Color"
        case .paint: "Paint"
        case .text: "Text"
        case .incline: "Incline"
        case .gif: "GIF"
        }
    }

    public var isNumeric: Bool {
        self != .color && self != .text
    }
}

public enum StoryboardValue: Hashable, Sendable {
    case numeric(start: Double, end: Double)
    case color(startRGB: Int, endRGB: Int)
    case text(start: String, end: String)
}

public struct StoryboardEvent: Identifiable, Hashable, Sendable {
    public var id = UUID()
    public var type: StoryboardEventType
    public var startTime = BeatTime.zero
    public var endTime = BeatTime(1, 0, 1)
    public var easingType = 1
    public var easingLeft = 0.0
    public var easingRight = 1.0
    public var linkGroup = 0
    public var usesBezier = false
    public var bezierPoints = [0.0, 0.0, 0.0, 0.0]
    public var value: StoryboardValue
    public var raw: [String: JSONValue] = [:]
    public var isModified = true

    public init(type: StoryboardEventType) {
        self.type = type
        switch type {
        case .color:
            value = .color(startRGB: 0xFFFFFF, endRGB: 0xFFFFFF)
        case .text:
            value = .text(start: "", end: "")
        default:
            value = .numeric(start: 0, end: 0)
        }
    }

    public init(type: StoryboardEventType, json: JSONValue) throws {
        guard let object = json.objectValue else {
            throw ChartError.invalidJSON("\(type.title) storyboard event must be an object")
        }
        self.type = type
        startTime = BeatTime(json: object["startTime"])
        endTime = BeatTime(json: object["endTime"])
        easingType = min(
            Easing.maximumType,
            max(Easing.minimumType, object.int("easingType", default: 1))
        )
        easingLeft = object.double("easingLeft")
        easingRight = object.double("easingRight", default: 1)
        linkGroup = object.int("linkgroup")
        usesBezier = object.int("bezier") != 0
        if let points = object.array("bezierPoints") {
            for index in 0 ..< min(4, points.count) {
                bezierPoints[index] = points[index].doubleValue ?? 0
            }
        }
        switch type {
        case .color:
            let start = Self.readRGB(object["start"], fallback: 0xFFFFFF)
            value = .color(
                startRGB: start,
                endRGB: Self.readRGB(object["end"], fallback: start)
            )
        case .text:
            let start = object.string("start")
            value = .text(start: start, end: object.string("end", default: start))
        default:
            let start = object.double("start")
            value = .numeric(start: start, end: object.double("end", default: start))
        }
        raw = object
        isModified = false
    }

    public var json: JSONValue {
        if !isModified { return .object(raw) }
        var object = raw.removing([
            "startTime", "endTime", "easingType", "easingLeft", "easingRight",
            "linkgroup", "bezier", "bezierPoints", "start", "end",
        ])
        object["startTime"] = startTime.json
        object["endTime"] = endTime.json
        object["easingType"] = .integer(Int64(easingType))
        object["easingLeft"] = .number(easingLeft)
        object["easingRight"] = .number(easingRight)
        object["linkgroup"] = .integer(Int64(linkGroup))
        object["bezier"] = .integer(usesBezier ? 1 : 0)
        object["bezierPoints"] = .array(paddedBezierPoints.map(JSONValue.number))
        switch value {
        case let .numeric(start, end):
            object["start"] = .number(start)
            object["end"] = .number(end)
        case let .color(startRGB, endRGB):
            object["start"] = Self.rgbJSON(startRGB)
            object["end"] = Self.rgbJSON(endRGB)
        case let .text(start, end):
            object["start"] = .string(start)
            object["end"] = .string(end)
        }
        return .object(object)
    }

    public var paddedBezierPoints: [Double] {
        Array((bezierPoints + [0, 0, 0, 0]).prefix(4))
    }

    public func progress(at beat: Double) -> Double {
        let startBeat = startTime.doubleValue
        let endBeat = endTime.doubleValue
        if beat <= startBeat || endBeat <= startBeat { return 0 }
        if beat >= endBeat { return 1 }
        let input = (beat - startBeat) / (endBeat - startBeat)
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

    public func numericValue(at beat: Double, fallback: Double = 0) -> Double {
        guard case let .numeric(start, end) = value else { return fallback }
        if beat <= startTime.doubleValue { return start }
        if beat >= endTime.doubleValue { return end }
        return start + (end - start) * progress(at: beat)
    }

    public func colorValue(at beat: Double, fallback: Int = 0xFFFFFF) -> Int {
        guard case let .color(start, end) = value else { return fallback }
        let progress = progress(at: beat)
        func channel(_ shift: Int) -> Int {
            let first = (start >> shift) & 0xFF
            let last = (end >> shift) & 0xFF
            return min(255, max(0, Int((Double(first) + Double(last - first) * progress).rounded())))
        }
        return (channel(16) << 16) | (channel(8) << 8) | channel(0)
    }

    public func textValue(at beat: Double, fallback: String = "") -> String {
        guard case let .text(start, end) = value else { return fallback }
        if beat >= endTime.doubleValue { return end }
        return Self.tweenText(start, end, progress: progress(at: beat))
    }

    public mutating func markModified() {
        isModified = true
    }

    public static func tweenText(_ start: String, _ end: String, progress: Double) -> String {
        let t = min(1, max(0, progress.isFinite ? progress : 0))
        if start.contains("%P%"), end.contains("%P%") {
            let firstText = start.replacingOccurrences(of: "%P%", with: "")
            let secondText = end.replacingOccurrences(of: "%P%", with: "")
            if t <= 0 { return firstText }
            if t >= 1 { return secondText }
            let first = Double(firstText) ?? 0
            let second = Double(secondText) ?? 0
            let value = first + t * (second - first)
            if first.rounded() == first, second.rounded() == second {
                return String(format: "%.0f", value)
            }
            return String(format: "%.3f", value)
        }
        if start.isEmpty, end.isEmpty { return "" }
        if end.isEmpty {
            return tweenText("", start.replacingOccurrences(of: "%P%", with: ""), progress: 1 - t)
        }
        if start.isEmpty {
            let count = Int((Double(end.count) * t).rounded())
            return String(end.prefix(max(0, min(end.count, count))))
        }
        if end.hasPrefix(start) {
            let count = start.count + Int(floor(Double(end.count - start.count) * t))
            return String(end.prefix(count))
        }
        if start.hasPrefix(end) {
            let count = end.count + Int((Double(start.count - end.count) * (1 - t)).rounded())
            return String(start.prefix(count))
        }
        return start.replacingOccurrences(of: "%P%", with: "")
    }

    private static func readRGB(_ json: JSONValue?, fallback: Int) -> Int {
        guard let values = json?.arrayValue, values.count >= 3 else { return fallback }
        let red = min(255, max(0, values[0].intValue ?? ((fallback >> 16) & 0xFF)))
        let green = min(255, max(0, values[1].intValue ?? ((fallback >> 8) & 0xFF)))
        let blue = min(255, max(0, values[2].intValue ?? (fallback & 0xFF)))
        return (red << 16) | (green << 8) | blue
    }

    private static func rgbJSON(_ value: Int) -> JSONValue {
        .array([
            .integer(Int64((value >> 16) & 0xFF)),
            .integer(Int64((value >> 8) & 0xFF)),
            .integer(Int64(value & 0xFF)),
        ])
    }
}

public struct StoryboardTracks: Hashable, Sendable {
    public var events: [StoryboardEventType: [StoryboardEvent]] = [:]
    public var raw: [String: JSONValue] = [:]
    public var passthrough: [StoryboardEventType: [JSONValue]] = [:]

    public init() {
        for type in StoryboardEventType.allCases {
            events[type] = []
            passthrough[type] = []
        }
    }

    public init(json: JSONValue?) throws {
        self.init()
        guard let object = json?.objectValue else { return }
        raw = object.removing(Set(StoryboardEventType.allCases.map(\.rawValue)))
        for type in StoryboardEventType.allCases {
            for value in object.array(type.rawValue) ?? [] {
                if value.objectValue != nil {
                    events[type, default: []].append(try StoryboardEvent(type: type, json: value))
                } else {
                    passthrough[type, default: []].append(value)
                }
            }
            events[type]?.sort { $0.startTime < $1.startTime }
        }
    }

    public subscript(type: StoryboardEventType) -> [StoryboardEvent] {
        get { events[type] ?? [] }
        set { events[type] = newValue.sorted { $0.startTime < $1.startTime } }
    }

    public var count: Int {
        events.values.reduce(0) { $0 + $1.count }
    }

    public var json: JSONValue {
        var object = raw
        for type in StoryboardEventType.allCases {
            let values = (events[type] ?? []).sorted { $0.startTime < $1.startTime }.map(\.json)
                + (passthrough[type] ?? [])
            if values.isEmpty {
                object[type.rawValue] = nil
            } else {
                object[type.rawValue] = .array(values)
            }
        }
        return .object(object)
    }

    public func latest(_ type: StoryboardEventType, at beat: Double) -> StoryboardEvent? {
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
        return low > 0 ? values[low - 1] : nil
    }
}

public struct NoteControlEvent: Hashable, Sendable {
    public var x = 0.0
    public var easing = 1
    public var value = 1.0
}

public struct NoteControls: Hashable, Sendable {
    public var position: [NoteControlEvent] = []
    public var size: [NoteControlEvent] = []
    public var alpha: [NoteControlEvent] = []
    public var y: [NoteControlEvent] = []

    public init() {}

    public init(line: [String: JSONValue]) {
        position = Self.read(line.array("posControl"), key: "pos")
        size = Self.read(line.array("sizeControl"), key: "size")
        alpha = Self.read(line.array("alphaControl"), key: "alpha")
        y = Self.read(line.array("yControl"), key: "y")
    }

    public var count: Int {
        position.count + size.count + alpha.count + y.count
    }

    public static func value(
        _ events: [NoteControlEvent],
        height: Double,
        fallback: Double
    ) -> Double {
        if events.isEmpty || isIdentitySentinel(events) { return fallback }
        guard let first = events.first else { return fallback }
        if events.count == 1 || height <= first.x { return finite(first.value, fallback) }
        for index in 1 ..< events.count {
            let next = events[index]
            if height < next.x {
                let previous = events[index - 1]
                let span = next.x - previous.x
                guard span.isFinite, span > 0 else { return finite(next.value, fallback) }
                let progress = (height - previous.x) / span
                let eased = Easing.apply(max(1, next.easing), progress)
                return finite(previous.value, fallback)
                    + (finite(next.value, fallback) - finite(previous.value, fallback)) * eased
            }
        }
        return finite(events.last?.value ?? fallback, fallback)
    }

    private static func read(_ source: [JSONValue]?, key: String) -> [NoteControlEvent] {
        (source ?? []).compactMap { value in
            guard let object = value.objectValue else { return nil }
            return NoteControlEvent(
                x: object.double("x"),
                easing: min(
                    Easing.maximumType,
                    max(1, object.int("easing", default: 1))
                ),
                value: object.double(key, default: 1)
            )
        }
    }

    private static func isIdentitySentinel(_ events: [NoteControlEvent]) -> Bool {
        events.count == 2 && events[0].easing == 1 && abs(events[0].value - 1) < 1.0e-4
    }

    private static func finite(_ value: Double, _ fallback: Double) -> Double {
        value.isFinite ? value : fallback
    }
}
