import Foundation

public struct ChartHistory: Sendable {
    private var undoStack: [ChartDocument] = []
    private var redoStack: [ChartDocument] = []
    public var limit: Int

    public init(limit: Int = 100) {
        self.limit = max(1, limit)
    }

    public var canUndo: Bool { !undoStack.isEmpty }
    public var canRedo: Bool { !redoStack.isEmpty }

    public mutating func record(_ document: ChartDocument) {
        undoStack.append(document)
        if undoStack.count > limit { undoStack.removeFirst(undoStack.count - limit) }
        redoStack.removeAll(keepingCapacity: true)
    }

    public mutating func undo(current: ChartDocument) -> ChartDocument? {
        guard let previous = undoStack.popLast() else { return nil }
        redoStack.append(current)
        return previous
    }

    public mutating func redo(current: ChartDocument) -> ChartDocument? {
        guard let next = redoStack.popLast() else { return nil }
        undoStack.append(current)
        return next
    }

    public mutating func clear() {
        undoStack.removeAll()
        redoStack.removeAll()
    }
}

public enum PropertyValidationError: String, LocalizedError, Sendable {
    case missingType
    case nonFiniteNumber
    case negativeStartTime
    case endTimeNotAfterStart
    case noteXOutOfRange
    case noteAlphaOutOfRange
    case noteSizeNotPositive
    case noteVisibleTimeNegative
    case eventAlphaOutOfRange
    case eventEasingOutOfRange
    case eventEasingWindowInvalid
    case eventLinkGroupNegative

    public var errorDescription: String? {
        rawValue
            .replacingOccurrences(of: "([a-z])([A-Z])", with: "$1 $2", options: .regularExpression)
            .capitalized
    }
}

public enum PropertyValidator {
    public static func validate(_ note: Note) -> PropertyValidationError? {
        if note.startTime.doubleValue < 0 { return .negativeStartTime }
        if note.type == .hold, note.endTime <= note.startTime { return .endTimeNotAfterStart }
        guard [
            note.positionX, note.speed, note.size, note.visibleTime, note.yOffset, note.judgeArea,
        ].allSatisfy(\.isFinite)
        else {
            return .nonFiniteNumber
        }
        if !(-675 ... 675).contains(note.positionX) { return .noteXOutOfRange }
        if !(0 ... 255).contains(note.alpha) { return .noteAlphaOutOfRange }
        if note.size <= 0 { return .noteSizeNotPositive }
        if note.visibleTime < 0 { return .noteVisibleTimeNegative }
        return nil
    }

    public static func validate(_ event: LineEvent) -> PropertyValidationError? {
        if event.startTime.doubleValue < 0 { return .negativeStartTime }
        if event.endTime <= event.startTime { return .endTimeNotAfterStart }
        guard ([event.start, event.end, event.easingLeft, event.easingRight]
            + event.paddedBezierPoints).allSatisfy(\.isFinite)
        else {
            return .nonFiniteNumber
        }
        if event.type == .alpha,
           (!(-255 ... 255).contains(event.start)
               || !(-255 ... 255).contains(event.end))
        {
            return .eventAlphaOutOfRange
        }
        if event.type == .speed, event.easingType != 1 {
            return .eventEasingOutOfRange
        }
        if event.type != .speed,
           !(Easing.minimumType ... Easing.maximumType).contains(event.easingType)
        {
            return .eventEasingOutOfRange
        }
        if event.easingLeft < 0 || event.easingRight > 1 || event.easingLeft > event.easingRight {
            return .eventEasingWindowInvalid
        }
        if event.linkGroup < 0 { return .eventLinkGroupNegative }
        return nil
    }
}

public enum BatchMode: String, CaseIterable, Identifiable, Sendable {
    case by
    case to
    case times
    case maximum
    case minimum

    public var id: String { rawValue }
}

public struct BatchValueProfile: Hashable, Sendable {
    public static let maximumSequenceLength = 256
    public var lowerBound = 0.0
    public var upperBound = 0.0
    public var easingType = 1
    public var periodicSequence = [1.0]
    public var disturbance = 0.0
    public var randomSeed: Int64 = 0

    public init() {}

    public func values(count: Int) throws -> [Double] {
        guard count >= 0,
              lowerBound.isFinite,
              upperBound.isFinite,
              disturbance.isFinite,
              (Easing.minimumType ... Easing.maximumType).contains(easingType),
              !periodicSequence.isEmpty,
              periodicSequence.count <= Self.maximumSequenceLength,
              periodicSequence.allSatisfy(\.isFinite)
        else {
            throw ChartError.invalidValue("Invalid batch value profile")
        }
        var random = JavaRandom(seed: randomSeed)
        return try (0 ..< count).map { index in
            let input = count <= 1 ? 0 : Double(index) / Double(count - 1)
            let base = lowerBound + (upperBound - lowerBound) * Easing.apply(easingType, input)
            let noise = disturbance == 0
                ? 0
                : (random.nextDouble() * 2 - 1) * abs(disturbance)
            let result = (base + noise) * periodicSequence[index % periodicSequence.count]
            guard result.isFinite else {
                throw ChartError.invalidValue("Batch profile produced a non-finite value")
            }
            return result
        }
    }

    public static func parseSequence(_ source: String) throws -> [Double] {
        let parts = source.split { $0.isWhitespace || $0 == "," || $0 == ";" }
        guard !parts.isEmpty, parts.count <= maximumSequenceLength else {
            throw ChartError.invalidValue("Periodic sequence is empty or too long")
        }
        return try parts.map { value in
            guard let result = Double(value), result.isFinite else {
                throw ChartError.invalidValue("Periodic sequence contains an invalid number")
            }
            return result
        }
    }

    public static func apply(_ mode: BatchMode, current: Double, generated: Double) throws -> Double {
        guard current.isFinite, generated.isFinite else {
            throw ChartError.invalidValue("Batch values must be finite")
        }
        let result: Double
        switch mode {
        case .by: result = current + generated
        case .to: result = generated
        case .times: result = current * generated
        case .maximum: result = max(current, generated)
        case .minimum: result = min(current, generated)
        }
        guard result.isFinite else {
            throw ChartError.invalidValue("Batch operation produced a non-finite value")
        }
        return result
    }
}

private struct JavaRandom {
    private static let multiplier: UInt64 = 0x5DEECE66D
    private static let addend: UInt64 = 0xB
    private static let mask: UInt64 = (1 << 48) - 1
    private var seed: UInt64

    init(seed: Int64) {
        self.seed = (UInt64(bitPattern: seed) ^ Self.multiplier) & Self.mask
    }

    mutating func nextDouble() -> Double {
        let high = UInt64(next(bits: 26))
        let low = UInt64(next(bits: 27))
        return Double((high << 27) + low) / Double(1 << 53)
    }

    private mutating func next(bits: Int) -> UInt32 {
        seed = (seed &* Self.multiplier &+ Self.addend) & Self.mask
        return UInt32(seed >> UInt64(48 - bits))
    }
}

public enum NoteBatchField: String, CaseIterable, Identifiable, Sendable {
    case x
    case speed
    case size
    case yOffset
    case visibleTime

    public var id: String { rawValue }
}

public enum EventBatchField: String, CaseIterable, Identifiable, Sendable {
    case startValue
    case endValue
    case easingType

    public var id: String { rawValue }
}

public struct EventCloneSpec: Hashable, Sendable {
    public static let maximumTargetLines = 256
    public static let maximumGeneratedEvents = 4_096

    public var lineSequence: [Int]
    public var timeIncrement: BeatTime
    public var xProfile: BatchValueProfile
    public var yProfile: BatchValueProfile
    public var rotateProfile: BatchValueProfile
    public var alphaProfile: BatchValueProfile
    public var keepSource: Bool

    public init(
        lineSequence: [Int],
        timeIncrement: BeatTime = .zero,
        xProfile: BatchValueProfile = BatchValueProfile(),
        yProfile: BatchValueProfile = BatchValueProfile(),
        rotateProfile: BatchValueProfile = BatchValueProfile(),
        alphaProfile: BatchValueProfile = BatchValueProfile(),
        keepSource: Bool = false
    ) {
        self.lineSequence = lineSequence
        self.timeIncrement = timeIncrement
        self.xProfile = xProfile
        self.yProfile = yProfile
        self.rotateProfile = rotateProfile
        self.alphaProfile = alphaProfile
        self.keepSource = keepSource
    }

    public static func parseLineSequence(_ source: String) throws -> [Int] {
        let parts = source.split { $0.isWhitespace || $0 == "," || $0 == ";" }
        guard !parts.isEmpty, parts.count <= maximumTargetLines else {
            throw ChartError.invalidValue("Line sequence is empty or too long")
        }
        let values = try parts.map { part -> Int in
            guard let value = Int(part) else {
                throw ChartError.invalidValue("Line sequence contains an invalid index")
            }
            return value
        }
        guard Set(values).count == values.count else {
            throw ChartError.invalidValue("Line sequence contains duplicates")
        }
        return values
    }
}

public struct EventCloneResult: Hashable, Sendable {
    public var generatedEventIDsByLine: [Int: [UUID]]

    public var count: Int {
        generatedEventIDsByLine.values.reduce(0) { $0 + $1.count }
    }
}

public enum EditorOperations {
    public static func cloneEvents(
        in chart: inout ChartDocument,
        sourceLineIndex: Int,
        layerIndex: Int,
        selectedIDs: Set<UUID>,
        spec: EventCloneSpec
    ) throws -> EventCloneResult {
        guard chart.judgeLines.indices.contains(sourceLineIndex) else {
            throw ChartError.invalidValue("Source line no longer exists")
        }
        guard (0 ... 3).contains(layerIndex),
              chart.judgeLines[sourceLineIndex].eventLayers.indices.contains(layerIndex)
        else {
            throw ChartError.invalidValue("The selected event layer is reserved")
        }
        let sourceLayer = chart.judgeLines[sourceLineIndex].eventLayers[layerIndex]
        let sources = sourceLayer.events.values.flatMap { $0 }
            .filter { selectedIDs.contains($0.id) }
        guard !sources.isEmpty else {
            throw ChartError.invalidValue("Select at least one event to clone")
        }
        guard !spec.lineSequence.isEmpty,
              spec.lineSequence.count <= EventCloneSpec.maximumTargetLines,
              Set(spec.lineSequence).count == spec.lineSequence.count,
              spec.lineSequence.allSatisfy({
                  chart.judgeLines.indices.contains($0)
              })
        else {
            throw ChartError.invalidValue("Every target line must exist and appear once")
        }
        guard sources.count
            <= EventCloneSpec.maximumGeneratedEvents / spec.lineSequence.count
        else {
            throw ChartError.invalidValue("Event Clone is limited to 4096 generated events")
        }

        let targetCount = spec.lineSequence.count
        let xOffsets = try spec.xProfile.values(count: targetCount)
        let yOffsets = try spec.yProfile.values(count: targetCount)
        let rotateOffsets = try spec.rotateProfile.values(count: targetCount)
        let alphaOffsets = try spec.alphaProfile.values(count: targetCount)
        var generated: [Int: [LineEvent]] = [:]

        for (sequenceIndex, targetLineIndex) in spec.lineSequence.enumerated() {
            var shift = BeatTime.zero
            if sequenceIndex > 0 {
                for _ in 0 ..< sequenceIndex {
                    shift = shift.adding(spec.timeIncrement)
                }
            }
            let existingLayer = chart.judgeLines[targetLineIndex].eventLayers.indices
                .contains(layerIndex)
                ? chart.judgeLines[targetLineIndex].eventLayers[layerIndex]
                : EventLayer()

            for source in sources {
                var clone = source
                clone.id = UUID()
                clone.startTime = source.startTime.adding(shift)
                clone.endTime = source.endTime.adding(shift)
                if clone.type != .speed {
                    switch clone.type {
                    case .moveX:
                        clone.start += xOffsets[sequenceIndex]
                        clone.end += xOffsets[sequenceIndex]
                    case .moveY:
                        clone.start += yOffsets[sequenceIndex]
                        clone.end += yOffsets[sequenceIndex]
                    case .rotate:
                        clone.start += rotateOffsets[sequenceIndex]
                        clone.end += rotateOffsets[sequenceIndex]
                    case .alpha:
                        clone.start += alphaOffsets[sequenceIndex]
                        clone.end += alphaOffsets[sequenceIndex]
                    case .speed:
                        break
                    }
                }
                if let error = PropertyValidator.validate(clone) { throw error }

                let overlapsExisting = existingLayer[clone.type].contains { existing in
                    let sourceWillBeRemoved = !spec.keepSource
                        && targetLineIndex == sourceLineIndex
                        && selectedIDs.contains(existing.id)
                    return !sourceWillBeRemoved && intervalsOverlap(existing, clone)
                }
                let overlapsGenerated = (generated[targetLineIndex] ?? []).contains {
                    $0.type == clone.type && intervalsOverlap($0, clone)
                }
                guard !overlapsExisting, !overlapsGenerated else {
                    throw ChartError.invalidValue(
                        "Cloned events would overlap existing or generated events"
                    )
                }
                generated[targetLineIndex, default: []].append(clone)
            }
        }

        if !spec.keepSource {
            for type in EventType.allCases {
                chart.judgeLines[sourceLineIndex].eventLayers[layerIndex]
                    .events[type]?.removeAll { selectedIDs.contains($0.id) }
            }
        }
        for targetLineIndex in spec.lineSequence {
            while chart.judgeLines[targetLineIndex].eventLayers.count <= layerIndex {
                chart.judgeLines[targetLineIndex].eventLayers.append(EventLayer())
            }
            for event in generated[targetLineIndex] ?? [] {
                chart.judgeLines[targetLineIndex].eventLayers[layerIndex]
                    .events[event.type, default: []].append(event)
            }
            for type in EventType.allCases {
                chart.judgeLines[targetLineIndex].eventLayers[layerIndex]
                    .events[type]?.sort { $0.startTime < $1.startTime }
            }
        }

        return EventCloneResult(
            generatedEventIDsByLine: generated.mapValues { $0.map(\.id) }
        )
    }

    public static func batchEditNotes(
        _ notes: inout [Note],
        selectedIDs: Set<UUID>,
        field: NoteBatchField,
        profile: BatchValueProfile,
        mode: BatchMode
    ) throws {
        let indices = notes.indices.filter { selectedIDs.contains(notes[$0].id) }
            .sorted { notes[$0].startTime < notes[$1].startTime }
        guard !indices.isEmpty else { throw ChartError.invalidValue("Select at least one note") }
        let values = try profile.values(count: indices.count)
        var edited = notes
        for (offset, index) in indices.enumerated() {
            let value = values[offset]
            switch field {
            case .x:
                edited[index].positionX = try BatchValueProfile.apply(
                    mode,
                    current: edited[index].positionX,
                    generated: value
                )
            case .speed:
                edited[index].speed = try BatchValueProfile.apply(
                    mode,
                    current: edited[index].speed,
                    generated: value
                )
            case .size:
                edited[index].size = try BatchValueProfile.apply(
                    mode,
                    current: edited[index].size,
                    generated: value
                )
            case .yOffset:
                edited[index].yOffset = try BatchValueProfile.apply(
                    mode,
                    current: edited[index].yOffset,
                    generated: value
                )
            case .visibleTime:
                edited[index].visibleTime = try BatchValueProfile.apply(
                    mode,
                    current: edited[index].visibleTime,
                    generated: value
                )
            }
            if let error = PropertyValidator.validate(edited[index]) { throw error }
        }
        notes = edited.sorted { $0.startTime < $1.startTime }
    }

    public static func batchEditEvents(
        _ events: inout [LineEvent],
        selectedIDs: Set<UUID>,
        field: EventBatchField,
        profile: BatchValueProfile,
        mode: BatchMode
    ) throws {
        let indices = events.indices.filter { selectedIDs.contains(events[$0].id) }
            .sorted { events[$0].startTime < events[$1].startTime }
        guard !indices.isEmpty else { throw ChartError.invalidValue("Select at least one event") }
        let types = Set(indices.map { events[$0].type })
        guard types.count == 1 else {
            throw ChartError.invalidValue("Batch event editing requires one event type")
        }
        if field == .easingType, types.first == .speed {
            throw ChartError.invalidValue("Speed-event easing is renderer metadata")
        }
        let values = try profile.values(count: indices.count)
        var edited = events
        for (offset, index) in indices.enumerated() {
            switch field {
            case .startValue:
                edited[index].start = try BatchValueProfile.apply(
                    mode,
                    current: edited[index].start,
                    generated: values[offset]
                )
            case .endValue:
                edited[index].end = try BatchValueProfile.apply(
                    mode,
                    current: edited[index].end,
                    generated: values[offset]
                )
            case .easingType:
                edited[index].easingType = Int(
                    try BatchValueProfile.apply(
                        mode,
                        current: Double(edited[index].easingType),
                        generated: values[offset]
                    ).rounded()
                )
            }
            if let error = PropertyValidator.validate(edited[index]) { throw error }
        }
        events = edited.sorted { $0.startTime < $1.startTime }
    }

    private static func intervalsOverlap(_ first: LineEvent, _ second: LineEvent) -> Bool {
        first.startTime < second.endTime && first.endTime > second.startTime
    }

    public static func splitEvent(
        _ events: inout [LineEvent],
        eventID: UUID,
        at cutTime: BeatTime
    ) throws -> UUID {
        guard let index = events.firstIndex(where: { $0.id == eventID }) else {
            throw ChartError.invalidValue("Event was not found")
        }
        let target = events[index]
        guard cutTime > target.startTime, cutTime < target.endTime else {
            throw ChartError.invalidValue("Cut time must be inside the event")
        }
        var left = target
        var right = target
        right.id = UUID()
        let cutValue = target.value(at: cutTime.doubleValue)
        left.endTime = cutTime
        left.end = cutValue
        right.startTime = cutTime
        right.start = cutValue
        if target.type == .speed {
            left.easingType = 1
            right.easingType = 1
        } else {
            let duration = target.endTime.doubleValue - target.startTime.doubleValue
            let progress = (cutTime.doubleValue - target.startTime.doubleValue) / duration
            let easingCut = target.easingLeft
                + (target.easingRight - target.easingLeft) * progress
            left.easingRight = easingCut
            right.easingLeft = easingCut
        }
        events[index] = left
        events.append(right)
        events.sort { $0.startTime < $1.startTime }
        return right.id
    }

    public static func glueEvent(_ events: inout [LineEvent], eventID: UUID) throws {
        guard let index = events.firstIndex(where: { $0.id == eventID }) else {
            throw ChartError.invalidValue("Event was not found")
        }
        let target = events[index]
        guard let previous = events
            .filter({ $0.type == target.type && $0.startTime < target.startTime })
            .max(by: { $0.startTime < $1.startTime })
        else {
            throw ChartError.invalidValue("There is no previous event of the same type")
        }
        events[index].start = previous.end
    }

    public static func stickEvents(_ events: inout [LineEvent], selectedIDs: Set<UUID>) throws {
        let selected = events.indices.filter { selectedIDs.contains(events[$0].id) }
            .sorted { events[$0].startTime < events[$1].startTime }
        guard selected.count >= 2 else {
            throw ChartError.invalidValue("Select at least two events")
        }
        guard Set(selected.map { events[$0].type }).count == 1 else {
            throw ChartError.invalidValue("Selected events must have the same type")
        }
        for offset in 1 ..< selected.count {
            events[selected[offset]].start = events[selected[offset - 1]].end
        }
    }

    public static func generateCurveNotes(
        in line: inout JudgeLine,
        from startID: UUID,
        to endID: UUID,
        density: Double,
        subdivision: Int,
        noteType: NoteType,
        easingType: Int
    ) throws -> [UUID] {
        guard let start = line.notes.first(where: { $0.id == startID }),
              let end = line.notes.first(where: { $0.id == endID })
        else {
            throw ChartError.invalidValue("Curve endpoints were not found")
        }
        guard start.id != end.id, start.startTime < end.startTime else {
            throw ChartError.invalidValue("Curve endpoints must be ordered in time")
        }
        guard density.isFinite, density > 0, subdivision > 0 else {
            throw ChartError.invalidValue("Curve density and subdivision must be positive")
        }
        guard noteType != .hold else {
            throw ChartError.invalidValue("Curve Notes supports Tap, Drag, and Flick")
        }
        guard (Easing.minimumType ... Easing.maximumType).contains(easingType) else {
            throw ChartError.invalidValue("Invalid curve easing")
        }
        let duration = end.startTime.doubleValue - start.startTime.doubleValue
        let requested = duration * Double(subdivision) * density
        guard requested.isFinite, requested <= 4_096 else {
            throw ChartError.invalidValue("Curve would create too many notes")
        }
        let intervals = max(1, Int(ceil(requested - 1.0e-12)))
        guard intervals >= 2 else {
            throw ChartError.invalidValue("Curve has no intermediate notes")
        }
        var generated: [Note] = []
        for index in 1 ..< intervals {
            let progress = Double(index) / Double(intervals)
            let x = start.positionX
                + (end.positionX - start.positionX) * Easing.apply(easingType, progress)
            guard x.isFinite, (-675 ... 675).contains(x) else {
                throw ChartError.invalidValue("Generated curve leaves the chart area")
            }
            var note = Note()
            note.type = noteType
            note.startTime = .interpolate(
                from: start.startTime,
                to: end.startTime,
                step: index,
                steps: intervals
            )
            note.endTime = note.startTime
            note.positionX = x
            note.above = start.above
            generated.append(note)
        }
        line.notes.append(contentsOf: generated)
        line.notes.sort { $0.startTime < $1.startTime }
        return generated.map(\.id)
    }

    public static func mirrorNotes(_ notes: inout [Note], selectedIDs: Set<UUID>) {
        for index in notes.indices where selectedIDs.contains(notes[index].id) {
            notes[index].positionX = -notes[index].positionX
        }
    }

    public static func flipNoteSides(_ notes: inout [Note], selectedIDs: Set<UUID>) {
        for index in notes.indices where selectedIDs.contains(notes[index].id) {
            notes[index].above = notes[index].above == 1 ? 0 : 1
        }
    }
}

public struct ComplexMoveTimeEasing: Hashable, Sendable {
    public var type = 1
    public var left = 0.0
    public var right = 1.0
    public static let linear = ComplexMoveTimeEasing(
        uncheckedType: 1,
        left: 0,
        right: 1
    )

    public init(type: Int = 1, left: Double = 0, right: Double = 1) throws {
        guard (Easing.minimumType ... Easing.maximumType).contains(type),
              left.isFinite,
              right.isFinite,
              left >= 0,
              right <= 1,
              left <= right
        else {
            throw ChartError.invalidValue("Invalid easing window")
        }
        self.type = type
        self.left = left
        self.right = right
    }

    private init(uncheckedType: Int, left: Double, right: Double) {
        type = uncheckedType
        self.left = left
        self.right = right
    }

    public static func parse(_ source: String) throws -> ComplexMoveTimeEasing {
        let parts = source.split { $0.isWhitespace || $0 == "," || $0 == ";" }
        guard parts.count == 1 || parts.count == 3,
              let type = Int(parts[0])
        else {
            throw ChartError.invalidValue("Easing needs type or type,left,right")
        }
        let left = parts.count == 3 ? Double(parts[1]) : 0
        let right = parts.count == 3 ? Double(parts[2]) : 1
        guard let left, let right else { throw ChartError.invalidValue("Invalid easing") }
        return try ComplexMoveTimeEasing(type: type, left: left, right: right)
    }

    public func apply(_ input: Double) -> Double {
        Easing.applyWindowed(type, input, left: left, right: right)
    }
}

public struct ComplexMoveSpec: Hashable, Sendable {
    public var startTime = BeatTime.zero
    public var endTime = BeatTime(1, 0, 1)
    public var xExpression = "675*cos(2*pi*t)"
    public var yExpression = "450*sin(2*pi*t)"
    public var xTimeEasing: ComplexMoveTimeEasing
    public var yTimeEasing: ComplexMoveTimeEasing
    public var density = 8.0

    public init() {
        xTimeEasing = .linear
        yTimeEasing = .linear
    }
}

public struct ComplexMoveResult: Hashable, Sendable {
    public var moveXEvents: [LineEvent]
    public var moveYEvents: [LineEvent]
    public var path: [(x: Double, y: Double)]
    public var segmentCount: Int

    public static func == (lhs: ComplexMoveResult, rhs: ComplexMoveResult) -> Bool {
        lhs.moveXEvents == rhs.moveXEvents
            && lhs.moveYEvents == rhs.moveYEvents
            && lhs.path.elementsEqual(rhs.path) { $0.x == $1.x && $0.y == $1.y }
            && lhs.segmentCount == rhs.segmentCount
    }

    public func hash(into hasher: inout Hasher) {
        hasher.combine(moveXEvents)
        hasher.combine(moveYEvents)
        path.forEach {
            hasher.combine($0.x)
            hasher.combine($0.y)
        }
        hasher.combine(segmentCount)
    }
}

public extension EditorOperations {
    static func generateComplexMove(
        _ spec: ComplexMoveSpec,
        checking layer: EventLayer? = nil
    ) throws -> ComplexMoveResult {
        guard spec.startTime.doubleValue >= 0, spec.endTime > spec.startTime else {
            throw ChartError.invalidValue("Complex Move time range is invalid")
        }
        guard spec.density.isFinite, spec.density > 0 else {
            throw ChartError.invalidValue("Complex Move density must be positive")
        }
        let duration = spec.endTime.doubleValue - spec.startTime.doubleValue
        let requested = duration * spec.density
        guard requested.isFinite, requested <= 4_096 else {
            throw ChartError.invalidValue("Complex Move would create too many segments")
        }
        let segments = max(1, Int(ceil(requested - 1.0e-12)))
        let xExpression = try MathExpression.compile(spec.xExpression)
        let yExpression = try MathExpression.compile(spec.yExpression)
        var path: [(Double, Double)] = []
        for index in 0 ... segments {
            let progress = Double(index) / Double(segments)
            let x = try xExpression.evaluate(spec.xTimeEasing.apply(progress))
            let y = try yExpression.evaluate(spec.yTimeEasing.apply(progress))
            guard (-675 ... 675).contains(x), (-450 ... 450).contains(y) else {
                throw ChartError.invalidValue("Complex Move path leaves the chart area")
            }
            path.append((x, y))
        }
        var xEvents: [LineEvent] = []
        var yEvents: [LineEvent] = []
        for index in 0 ..< segments {
            let start = BeatTime.interpolate(
                from: spec.startTime,
                to: spec.endTime,
                step: index,
                steps: segments
            )
            let end = BeatTime.interpolate(
                from: spec.startTime,
                to: spec.endTime,
                step: index + 1,
                steps: segments
            )
            var xEvent = LineEvent(type: .moveX)
            xEvent.startTime = start
            xEvent.endTime = end
            xEvent.start = path[index].0
            xEvent.end = path[index + 1].0
            var yEvent = LineEvent(type: .moveY)
            yEvent.startTime = start
            yEvent.endTime = end
            yEvent.start = path[index].1
            yEvent.end = path[index + 1].1
            if let layer, layer.overlaps(xEvent) || layer.overlaps(yEvent) {
                throw ChartError.invalidValue("Complex Move overlaps existing events")
            }
            xEvents.append(xEvent)
            yEvents.append(yEvent)
        }
        return ComplexMoveResult(
            moveXEvents: xEvents,
            moveYEvents: yEvents,
            path: path.map { (x: $0.0, y: $0.1) },
            segmentCount: segments
        )
    }
}

public struct ChartClipboardSnapshot: Hashable, Sendable {
    public var notes: [Note]
    public var events: [LineEvent]
    public var earliestTime: BeatTime?

    public init(notes: [Note], events: [LineEvent]) {
        self.notes = notes
        self.events = events
        earliestTime = (notes.map(\.startTime) + events.map(\.startTime)).min()
    }

    public var isEmpty: Bool { notes.isEmpty && events.isEmpty }

    public func shifted(
        to anchor: BeatTime,
        mirrorNotes: Bool = false
    ) throws -> ChartClipboardSnapshot {
        guard let earliestTime else {
            throw ChartError.invalidValue("Clipboard is empty")
        }
        var shiftedNotes = notes
        for index in shiftedNotes.indices {
            let startDelta = shiftedNotes[index].startTime.subtracting(earliestTime)
            let endDelta = shiftedNotes[index].endTime.subtracting(earliestTime)
            shiftedNotes[index].id = UUID()
            shiftedNotes[index].startTime = anchor.adding(startDelta)
            shiftedNotes[index].endTime = anchor.adding(endDelta)
            if mirrorNotes { shiftedNotes[index].positionX *= -1 }
            if let error = PropertyValidator.validate(shiftedNotes[index]) { throw error }
        }
        var shiftedEvents = events
        for index in shiftedEvents.indices {
            let startDelta = shiftedEvents[index].startTime.subtracting(earliestTime)
            let endDelta = shiftedEvents[index].endTime.subtracting(earliestTime)
            shiftedEvents[index].id = UUID()
            shiftedEvents[index].startTime = anchor.adding(startDelta)
            shiftedEvents[index].endTime = anchor.adding(endDelta)
            if let error = PropertyValidator.validate(shiftedEvents[index]) { throw error }
        }
        return ChartClipboardSnapshot(notes: shiftedNotes, events: shiftedEvents)
    }
}
