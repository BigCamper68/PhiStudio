import Foundation

public enum DiagnosticSeverity: Int, CaseIterable, Sendable {
    case error
    case warning
    case caution

    public var title: String {
        switch self {
        case .error: "Error"
        case .warning: "Warning"
        case .caution: "Caution"
        }
    }
}

public enum DiagnosticCode: String, CaseIterable, Sendable {
    case bpmInvalid
    case bpmTimeOutOfRange
    case duplicateBPMTime
    case fatherOutOfRange
    case fatherCycle
    case noteTimeOutOfRange
    case holdIntervalInvalid
    case noteXOutOfRange
    case noteAlphaOutOfRange
    case noteSizeInvalid
    case noteVisibleTimeInvalid
    case eventTimeOutOfRange
    case eventIntervalInvalid
    case eventOverlap
    case eventAlphaOutOfRange
    case eventValueNonFinite
    case reservedLayerNormalEvent
    case emptyChart

    public var title: String {
        rawValue
            .replacingOccurrences(of: "([a-z])([A-Z])", with: "$1 $2", options: .regularExpression)
            .capitalized
    }
}

public enum DiagnosticCategory: String, Sendable {
    case bpm
    case line
    case note
    case event
    case other
}

public struct ChartDiagnostic: Identifiable, Hashable, Sendable {
    public var id = UUID()
    public var severity: DiagnosticSeverity
    public var category: DiagnosticCategory
    public var code: DiagnosticCode
    public var lineIndex: Int
    public var layerIndex: Int
    public var beat: BeatTime
    public var noteID: UUID?
    public var eventID: UUID?

    public var message: String {
        var location: [String] = []
        if lineIndex >= 0 { location.append("line \(lineIndex)") }
        if layerIndex >= 0 { location.append("layer \(layerIndex)") }
        location.append("beat \(beat)")
        return "\(code.title) · \(location.joined(separator: ", "))"
    }
}

public struct DiagnosticReport: Hashable, Sendable {
    public var items: [ChartDiagnostic]
    public var totalCount: Int
    public var errorCount: Int
    public var warningCount: Int
    public var cautionCount: Int

    public var isTruncated: Bool { items.count < totalCount }
}

public enum ChartDiagnostics {
    private static let maximumDisplayedItems = 200
    private static let maximumDisplayedPerCode = 20

    public static func diagnose(
        _ chart: ChartDocument,
        maximumBeat: Double? = nil
    ) -> DiagnosticReport {
        var collector = Collector()
        if chart.judgeLines.isEmpty {
            collector.add(
                .error,
                .other,
                .emptyChart,
                line: -1,
                layer: -1,
                beat: .zero
            )
        }
        var previousBPMTime: BeatTime?
        for change in chart.bpmChanges.sorted(by: { $0.startTime < $1.startTime }) {
            if !change.bpm.isFinite || change.bpm <= 0 {
                collector.add(.error, .bpm, .bpmInvalid, line: -1, layer: -1, beat: change.startTime)
            }
            if change.startTime.doubleValue < 0 || exceeds(change.startTime, maximumBeat) {
                collector.add(
                    .error,
                    .bpm,
                    .bpmTimeOutOfRange,
                    line: -1,
                    layer: -1,
                    beat: change.startTime
                )
            }
            if previousBPMTime == change.startTime {
                collector.add(
                    .warning,
                    .bpm,
                    .duplicateBPMTime,
                    line: -1,
                    layer: -1,
                    beat: change.startTime
                )
            }
            previousBPMTime = change.startTime
        }
        for lineIndex in chart.judgeLines.indices {
            let line = chart.judgeLines[lineIndex]
            if line.father >= chart.judgeLines.count || line.father == lineIndex {
                collector.add(
                    .error,
                    .line,
                    .fatherOutOfRange,
                    line: lineIndex,
                    layer: -1,
                    beat: .zero
                )
            } else if line.father >= 0, hasParentCycle(chart, from: lineIndex) {
                collector.add(
                    .error,
                    .line,
                    .fatherCycle,
                    line: lineIndex,
                    layer: -1,
                    beat: .zero
                )
            }
            diagnoseNotes(line, lineIndex: lineIndex, maximumBeat: maximumBeat, collector: &collector)
            diagnoseEvents(line, lineIndex: lineIndex, maximumBeat: maximumBeat, collector: &collector)
        }
        return collector.report
    }

    private static func diagnoseNotes(
        _ line: JudgeLine,
        lineIndex: Int,
        maximumBeat: Double?,
        collector: inout Collector
    ) {
        for note in line.notes {
            let outOfRange = note.startTime.doubleValue < 0
                || note.endTime.doubleValue < 0
                || exceeds(note.startTime, maximumBeat)
                || exceeds(note.endTime, maximumBeat)
            if outOfRange {
                collector.add(
                    .error,
                    .note,
                    .noteTimeOutOfRange,
                    line: lineIndex,
                    layer: -1,
                    beat: note.startTime,
                    noteID: note.id
                )
            }
            if note.type == .hold, note.endTime <= note.startTime {
                collector.add(
                    .error,
                    .note,
                    .holdIntervalInvalid,
                    line: lineIndex,
                    layer: -1,
                    beat: note.startTime,
                    noteID: note.id
                )
            }
            if !note.positionX.isFinite || !(-675 ... 675).contains(note.positionX) {
                collector.add(
                    .warning,
                    .note,
                    .noteXOutOfRange,
                    line: lineIndex,
                    layer: -1,
                    beat: note.startTime,
                    noteID: note.id
                )
            }
            if !(0 ... 255).contains(note.alpha) {
                collector.add(
                    .warning,
                    .note,
                    .noteAlphaOutOfRange,
                    line: lineIndex,
                    layer: -1,
                    beat: note.startTime,
                    noteID: note.id
                )
            }
            if !note.size.isFinite || note.size <= 0 {
                collector.add(
                    .warning,
                    .note,
                    .noteSizeInvalid,
                    line: lineIndex,
                    layer: -1,
                    beat: note.startTime,
                    noteID: note.id
                )
            }
            if !note.visibleTime.isFinite || note.visibleTime < 0 {
                collector.add(
                    .warning,
                    .note,
                    .noteVisibleTimeInvalid,
                    line: lineIndex,
                    layer: -1,
                    beat: note.startTime,
                    noteID: note.id
                )
            }
        }
    }

    private static func diagnoseEvents(
        _ line: JudgeLine,
        lineIndex: Int,
        maximumBeat: Double?,
        collector: inout Collector
    ) {
        for layerIndex in line.eventLayers.indices {
            let layer = line.eventLayers[layerIndex]
            if layerIndex >= 4, layer.eventCount > 0 {
                collector.add(
                    .caution,
                    .other,
                    .reservedLayerNormalEvent,
                    line: lineIndex,
                    layer: layerIndex,
                    beat: .zero
                )
                continue
            }
            for type in EventType.allCases {
                let values = layer[type].sorted {
                    $0.startTime == $1.startTime
                        ? $0.endTime < $1.endTime
                        : $0.startTime < $1.startTime
                }
                var previousValid: LineEvent?
                for event in values {
                    let validInterval = event.endTime > event.startTime
                    if event.startTime.doubleValue < 0
                        || event.endTime.doubleValue < 0
                        || exceeds(event.startTime, maximumBeat)
                        || exceeds(event.endTime, maximumBeat)
                    {
                        collector.add(
                            .error,
                            .event,
                            .eventTimeOutOfRange,
                            line: lineIndex,
                            layer: layerIndex,
                            beat: event.startTime,
                            eventID: event.id
                        )
                    }
                    if !validInterval {
                        collector.add(
                            .error,
                            .event,
                            .eventIntervalInvalid,
                            line: lineIndex,
                            layer: layerIndex,
                            beat: event.startTime,
                            eventID: event.id
                        )
                    }
                    if validInterval,
                       let previousValid,
                       event.startTime < previousValid.endTime
                    {
                        collector.add(
                            .error,
                            .event,
                            .eventOverlap,
                            line: lineIndex,
                            layer: layerIndex,
                            beat: event.startTime,
                            eventID: event.id
                        )
                    }
                    if validInterval,
                       (previousValid == nil || event.endTime > previousValid!.endTime)
                    {
                        previousValid = event
                    }
                    if !event.start.isFinite || !event.end.isFinite {
                        collector.add(
                            .error,
                            .event,
                            .eventValueNonFinite,
                            line: lineIndex,
                            layer: layerIndex,
                            beat: event.startTime,
                            eventID: event.id
                        )
                    }
                    if type == .alpha,
                       (!event.start.isFinite
                           || !event.end.isFinite
                           || !(0 ... 255).contains(event.start)
                           || !(0 ... 255).contains(event.end))
                    {
                        collector.add(
                            .warning,
                            .event,
                            .eventAlphaOutOfRange,
                            line: lineIndex,
                            layer: layerIndex,
                            beat: event.startTime,
                            eventID: event.id
                        )
                    }
                }
            }
        }
    }

    private static func exceeds(_ beat: BeatTime, _ maximum: Double?) -> Bool {
        guard let maximum, maximum.isFinite else { return false }
        return beat.doubleValue > maximum
    }

    private static func hasParentCycle(_ chart: ChartDocument, from start: Int) -> Bool {
        var visited: Set<Int> = []
        var current = start
        while current >= 0, current < chart.judgeLines.count {
            if !visited.insert(current).inserted { return true }
            current = chart.judgeLines[current].father
        }
        return false
    }

    private struct Collector {
        var items: [ChartDiagnostic] = []
        var storedByCode: [DiagnosticCode: Int] = [:]
        var total = 0
        var errors = 0
        var warnings = 0
        var cautions = 0

        mutating func add(
            _ severity: DiagnosticSeverity,
            _ category: DiagnosticCategory,
            _ code: DiagnosticCode,
            line: Int,
            layer: Int,
            beat: BeatTime,
            noteID: UUID? = nil,
            eventID: UUID? = nil
        ) {
            total += 1
            switch severity {
            case .error: errors += 1
            case .warning: warnings += 1
            case .caution: cautions += 1
            }
            let stored = storedByCode[code, default: 0]
            guard items.count < maximumDisplayedItems, stored < maximumDisplayedPerCode else {
                return
            }
            items.append(
                ChartDiagnostic(
                    severity: severity,
                    category: category,
                    code: code,
                    lineIndex: line,
                    layerIndex: layer,
                    beat: beat,
                    noteID: noteID,
                    eventID: eventID
                )
            )
            storedByCode[code] = stored + 1
        }

        var report: DiagnosticReport {
            let sorted = items.sorted {
                if $0.severity != $1.severity { return $0.severity.rawValue < $1.severity.rawValue }
                if $0.lineIndex != $1.lineIndex { return $0.lineIndex < $1.lineIndex }
                if $0.layerIndex != $1.layerIndex { return $0.layerIndex < $1.layerIndex }
                if $0.beat != $1.beat { return $0.beat < $1.beat }
                return $0.code.rawValue < $1.code.rawValue
            }
            return DiagnosticReport(
                items: sorted,
                totalCount: total,
                errorCount: errors,
                warningCount: warnings,
                cautionCount: cautions
            )
        }
    }
}
