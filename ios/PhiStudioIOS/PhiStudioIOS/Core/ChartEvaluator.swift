import Foundation

public struct RenderScene: Hashable, Sendable {
    public var beat: Double
    public var chartTimeMilliseconds: Int64
    public var lines: [RenderLine]
    public var hud: HUDState
}

public struct RenderLine: Identifiable, Hashable, Sendable {
    public var id: UUID
    public var sourceIndex: Int
    public var zOrder: Int
    public var x: Double
    public var y: Double
    public var rotationDegrees: Double
    public var alpha: Int
    public var colorRGB: Int?
    public var scaleX: Double
    public var scaleY: Double
    public var inclineDegrees: Double
    public var text: String
    public var textureName: String
    public var paintMode: Bool
    public var gifEnabled: Bool
    public var gifControlled: Bool
    public var gifProgress: Double
    public var gifAnchorTimeMilliseconds: Int64
    public var isCover: Bool
    public var notes: [RenderNote]
    public var hitEffects: [RenderHitEffect]
}

public struct RenderNote: Identifiable, Hashable, Sendable {
    public var id: UUID
    public var type: NoteType
    public var x: Double
    public var startDistance: Double
    public var endDistance: Double
    public var isAbove: Bool
    public var size: Double
    public var alpha: Int
    public var isFake: Bool
    public var isMultiHit: Bool
    public var holdHeadVisible: Bool
    public var colorRGB: Int
}

public struct RenderHitEffect: Identifiable, Hashable, Sendable {
    public var id: String
    public var x: Double
    public var y: Double
    public var progress: Double
    public var colorRGB: Int
    public var seed: Int
}

public struct HUDState: Hashable, Sendable {
    public var name: String
    public var level: String
    public var combo: Int
    public var score: Int
    public var progress: Double
    public var transforms: [AttachedUIElement: HUDTransform]
}

public struct HUDTransform: Hashable, Sendable {
    public var sourceIndex: Int
    public var x: Double
    public var y: Double
    public var rotationDegrees: Double
    public var alpha: Int
    public var colorRGB: Int?
    public var scaleX: Double
    public var scaleY: Double
}

/// Reusable immutable indexes for realtime chart evaluation.
///
/// A cache belongs to one editor session. It automatically rebuilds after an
/// edit, undo/redo, project replacement, or a change to the RPE speed rules.
public struct ChartEvaluationCache: @unchecked Sendable {
    fileprivate var prepared: ChartEvaluator.PreparedChart?

    public init() {}

    public mutating func invalidate() {
        prepared = nil
    }

    fileprivate mutating func value(
        for chart: ChartDocument,
        useRPE170Speed: Bool
    ) -> ChartEvaluator.PreparedChart {
        if let prepared,
           prepared.matches(chart, useRPE170Speed: useRPE170Speed)
        {
            return prepared
        }
        let rebuilt = ChartEvaluator.PreparedChart.build(
            chart,
            useRPE170Speed: useRPE170Speed
        )
        prepared = rebuilt
        return rebuilt
    }
}

public enum ChartEvaluator {
    private static let pixelsPerSpeedSecond = 100.0 / 0.83175
    private static let noteDistanceLimit = 6_000.0
    private static let hitEffectDurationMilliseconds: Int64 = 500
    private static let holdEffectIntervalMilliseconds: Int64 = 150

    public static func evaluate(
        _ chart: ChartDocument,
        at requestedBeat: Double,
        highlightSimultaneousNotes: Bool = true,
        trackDurationMilliseconds: Int64 = -1,
        useRPE170Speed: Bool = true
    ) -> RenderScene {
        var cache = ChartEvaluationCache()
        return evaluate(
            chart,
            at: requestedBeat,
            highlightSimultaneousNotes: highlightSimultaneousNotes,
            trackDurationMilliseconds: trackDurationMilliseconds,
            useRPE170Speed: useRPE170Speed,
            cache: &cache
        )
    }

    public static func evaluate(
        _ chart: ChartDocument,
        at requestedBeat: Double,
        highlightSimultaneousNotes: Bool = true,
        trackDurationMilliseconds: Int64 = -1,
        useRPE170Speed: Bool = true,
        cache: inout ChartEvaluationCache
    ) -> RenderScene {
        let beat = max(0, requestedBeat.isFinite ? requestedBeat : 0)
        let prepared = cache.value(for: chart, useRPE170Speed: useRPE170Speed)
        let chartTime = prepared.timing.milliseconds(atBeat: beat)
        let simultaneous = highlightSimultaneousNotes ? prepared.simultaneous : [:]
        let resolved = resolveLines(chart, prepared: prepared, at: beat)
        var renderedLines: [RenderLine] = []
        var hudTransforms: [AttachedUIElement: HUDTransform] = [:]

        for lineIndex in chart.judgeLines.indices {
            let line = chart.judgeLines[lineIndex]
            let preparedLine = prepared.lines[lineIndex]
            let transform = resolved[lineIndex]
            let rawAlpha = preparedLine.value(.alpha, at: beat)
            if let attached = line.attachedUI {
                hudTransforms[attached] = HUDTransform(
                    sourceIndex: lineIndex,
                    x: transform.x,
                    y: transform.y,
                    rotationDegrees: transform.rotation,
                    alpha: clampAlpha(rawAlpha),
                    colorRGB: latestStoryboard(line, .color, at: beat)?.colorValue(at: beat),
                    scaleX: storyboardScale(line, .scaleX, at: beat),
                    scaleY: storyboardScale(line, .scaleY, at: beat)
                )
                continue
            }
            if rawAlpha < 0 { continue }
            let speedProfile = preparedLine.speed
            let currentDistance = speedProfile.distance(at: beat)
            var notes: [RenderNote] = []
            var effects: [RenderHitEffect] = []
            notes.reserveCapacity(min(line.notes.count, 128))
            effects.reserveCapacity(min(line.notes.count, 32))

            for (noteIndex, note) in line.notes.enumerated() {
                let cachedTiming = prepared.noteTimingsByLine[lineIndex][noteIndex]
                let startBeat = cachedTiming.startBeat
                let isHold = note.type == .hold
                let endBeat = isHold ? cachedTiming.endBeat : startBeat
                if isHold, endBeat <= startBeat { continue }
                let startTime = cachedTiming.startMilliseconds
                let endTime = cachedTiming.endMilliseconds
                let effectActive: Bool
                if note.isFake || chartTime < startTime {
                    effectActive = false
                } else if isHold {
                    effectActive = chartTime < endTime + hitEffectDurationMilliseconds
                } else {
                    effectActive = chartTime < startTime + hitEffectDurationMilliseconds
                }
                let shouldRender: Bool
                if isHold {
                    shouldRender = beat < endBeat
                } else if note.isFake {
                    shouldRender = !line.isCover || beat < startBeat
                } else {
                    shouldRender = beat <= startBeat
                }
                let secondsUntilHit = Double(startTime - chartTime) / 1_000
                if shouldRender,
                   secondsUntilHit > max(0, note.visibleTime),
                   !effectActive
                {
                    continue
                }
                if !shouldRender, !effectActive { continue }

                let noteSpeed = finite(note.speed, fallback: 1)
                let yOffset = finite(note.yOffset, fallback: 0)
                let baseStart = cachedTiming.startDistance - currentDistance
                let baseEnd = isHold
                    ? cachedTiming.endDistance - currentDistance
                    : baseStart
                if !effectActive, line.noteControls.y.isEmpty {
                    let roughOffset = yOffset * noteSpeed
                    let roughStart = baseStart * noteSpeed + roughOffset
                    let roughEnd = baseEnd * noteSpeed + roughOffset
                    if !isHold, abs(roughStart) > noteDistanceLimit {
                        continue
                    }
                    if isHold,
                       roughStart > noteDistanceLimit,
                       roughEnd > noteDistanceLimit
                    {
                        continue
                    }
                    if isHold,
                       roughStart < -noteDistanceLimit,
                       roughEnd < -noteDistanceLimit
                    {
                        continue
                    }
                }
                let controlHeight = baseStart + yOffset
                let positionControl = finite(
                    NoteControls.value(
                        line.noteControls.position,
                        height: controlHeight,
                        fallback: 1
                    ),
                    fallback: 1
                )
                let sizeControl = finite(
                    NoteControls.value(line.noteControls.size, height: controlHeight, fallback: 1),
                    fallback: 1
                )
                let alphaControl = finite(
                    NoteControls.value(line.noteControls.alpha, height: controlHeight, fallback: 1),
                    fallback: 1
                )
                let yControl = finite(
                    NoteControls.value(line.noteControls.y, height: controlHeight, fallback: 1),
                    fallback: 1
                )
                let x = finite(note.positionX, fallback: 0) * (isHold ? 1 : positionControl)
                let renderedOffset = yOffset * noteSpeed
                var startDistance = baseStart * noteSpeed * yControl + renderedOffset
                let endDistance = baseEnd * noteSpeed * yControl + renderedOffset
                if isHold, beat >= startBeat { startDistance = renderedOffset }

                appendHitEffects(
                    chart: chart,
                    prepared: prepared,
                    lineIndex: lineIndex,
                    note: note,
                    startTime: startTime,
                    endTime: endTime,
                    chartTime: chartTime,
                    noteSpeed: noteSpeed,
                    yOffset: yOffset,
                    target: &effects
                )

                if !shouldRender { continue }
                if !isHold, abs(startDistance) > noteDistanceLimit { continue }
                if isHold,
                   startDistance > noteDistanceLimit,
                   endDistance > noteDistanceLimit
                {
                    continue
                }
                if isHold,
                   startDistance < -noteDistanceLimit,
                   endDistance < -noteDistanceLimit
                {
                    continue
                }
                if secondsUntilHit > max(0, note.visibleTime) { continue }
                if line.isCover, beat < startBeat, startDistance < 0 { continue }
                let renderedSize = abs(finite(note.size, fallback: 1) * sizeControl)
                let renderedAlpha = clampAlpha(Double(note.alpha) * alphaControl)
                if renderedSize <= 1.0e-6 || renderedAlpha == 0 { continue }
                notes.append(
                    RenderNote(
                        id: note.id,
                        type: note.type,
                        x: x,
                        startDistance: startDistance,
                        endDistance: endDistance,
                        isAbove: note.above == 1,
                        size: renderedSize,
                        alpha: renderedAlpha,
                        isFake: note.isFake,
                        isMultiHit: highlightSimultaneousNotes
                            && (simultaneous[note.startTime] ?? 0) > 1,
                        holdHeadVisible: !isHold || beat < startBeat,
                        colorRGB: note.tintRGB ?? 0xFFFFFF
                    )
                )
            }

            let color = latestStoryboard(line, .color, at: beat)?.colorValue(at: beat)
            let trimmedTexture = line.texture.trimmingCharacters(
                in: .whitespacesAndNewlines
            )
            let defaultTexture = trimmedTexture.isEmpty
                || trimmedTexture.caseInsensitiveCompare("line.png") == .orderedSame
            let paintMode = defaultTexture && !line.storyboard[.paint].isEmpty
            let text = defaultTexture && !paintMode
                ? latestStoryboard(line, .text, at: beat)?.textValue(at: beat) ?? ""
                : ""
            let gif = gifState(
                events: defaultTexture ? [] : line.storyboard[.gif],
                at: beat,
                timing: prepared.timing
            )
            renderedLines.append(
                RenderLine(
                    id: line.id,
                    sourceIndex: lineIndex,
                    zOrder: line.zOrder,
                    x: transform.x,
                    y: transform.y,
                    rotationDegrees: transform.rotation,
                    alpha: clampAlpha(rawAlpha),
                    colorRGB: color,
                    scaleX: storyboardScale(line, .scaleX, at: beat),
                    scaleY: storyboardScale(line, .scaleY, at: beat),
                    inclineDegrees: latestStoryboard(line, .incline, at: beat)?
                        .numericValue(at: beat) ?? 0,
                    text: text,
                    textureName: line.texture,
                    paintMode: paintMode,
                    gifEnabled: gif.enabled,
                    gifControlled: gif.controlled,
                    gifProgress: gif.progress,
                    gifAnchorTimeMilliseconds: gif.anchorTimeMilliseconds,
                    isCover: line.isCover,
                    notes: notes,
                    hitEffects: effects
                )
            )
        }
        renderedLines.sort {
            $0.zOrder == $1.zOrder ? $0.sourceIndex < $1.sourceIndex : $0.zOrder < $1.zOrder
        }

        let judgementBeats = prepared.judgementBeats
        let combo = judgementBeats.partitioningIndex { $0 > beat }
        let score = judgementBeats.isEmpty
            ? 0
            : Int((Double(combo) / Double(judgementBeats.count) * 1_000_000).rounded())
        let progress: Double
        if trackDurationMilliseconds > 0 {
            progress = min(1, max(0, Double(chartTime) / Double(trackDurationMilliseconds)))
        } else {
            progress = prepared.finalBeat <= 0
                ? 0
                : min(1, max(0, beat / prepared.finalBeat))
        }
        return RenderScene(
            beat: beat,
            chartTimeMilliseconds: chartTime,
            lines: renderedLines,
            hud: HUDState(
                name: chart.name,
                level: chart.level,
                combo: combo,
                score: score,
                progress: progress,
                transforms: hudTransforms
            )
        )
    }

    private static func appendHitEffects(
        chart: ChartDocument,
        prepared: PreparedChart,
        lineIndex: Int,
        note: Note,
        startTime: Int64,
        endTime: Int64,
        chartTime: Int64,
        noteSpeed: Double,
        yOffset: Double,
        target: inout [RenderHitEffect]
    ) {
        if note.isFake { return }
        if note.type != .hold {
            appendHitEffect(
                chart: chart,
                prepared: prepared,
                lineIndex: lineIndex,
                note: note,
                effectTime: startTime,
                chartTime: chartTime,
                noteSpeed: noteSpeed,
                yOffset: yOffset,
                target: &target
            )
            return
        }
        let visibleStart = max(startTime, chartTime - hitEffectDurationMilliseconds)
        let firstIndex = max(0, (visibleStart - startTime) / holdEffectIntervalMilliseconds)
        let lastIndex = max(0, (min(chartTime, endTime) - startTime) / holdEffectIntervalMilliseconds)
        if lastIndex < firstIndex { return }
        for index in firstIndex ... min(firstIndex + 31, lastIndex) {
            appendHitEffect(
                chart: chart,
                prepared: prepared,
                lineIndex: lineIndex,
                note: note,
                effectTime: startTime + index * holdEffectIntervalMilliseconds,
                chartTime: chartTime,
                noteSpeed: noteSpeed,
                yOffset: yOffset,
                target: &target
            )
        }
    }

    private static func appendHitEffect(
        chart: ChartDocument,
        prepared: PreparedChart,
        lineIndex: Int,
        note: Note,
        effectTime: Int64,
        chartTime: Int64,
        noteSpeed: Double,
        yOffset: Double,
        target: inout [RenderHitEffect]
    ) {
        let age = chartTime - effectTime
        guard age >= 0, age < hitEffectDurationMilliseconds else { return }
        let effectBeat = note.type == .hold
            ? prepared.timing.beat(atMilliseconds: effectTime)
            : max(0, note.startTime.doubleValue)
        let positionControl = note.type == .hold
            ? 1
            : finite(
                NoteControls.value(
                    chart.judgeLines[lineIndex].noteControls.position,
                    height: yOffset,
                    fallback: 1
                ),
                fallback: 1
            )
        let localX = finite(note.positionX, fallback: 0) * positionControl
        let localY = yOffset * noteSpeed * (note.above == 1 ? 1 : -1)
        let transform = resolveLineTransform(
            chart,
            prepared: prepared,
            lineIndex: lineIndex,
            at: effectBeat
        )
        let radians = transform.rotation * .pi / 180
        let worldX = transform.x + cos(radians) * localX - sin(radians) * localY
        let worldY = transform.y + sin(radians) * localX + cos(radians) * localY
        target.append(
            RenderHitEffect(
                id: "\(note.id.uuidString)-\(effectTime)",
                x: worldX,
                y: worldY,
                progress: Double(age) / Double(hitEffectDurationMilliseconds),
                colorRGB: note.hitEffectTintRGB ?? 0xFEFFA9,
                seed: hitEffectSeed(
                    lineIndex: lineIndex,
                    startTime: note.startTime,
                    effectBeat: effectBeat
                )
            )
        )
    }

    private static func hitEffectSeed(
        lineIndex: Int,
        startTime: BeatTime,
        effectBeat: Double
    ) -> Int {
        // Match Java's Objects.hash(whole, numerator, denominator) and
        // Double.hashCode(effectBeat) so Android and iOS particles coincide.
        var beatHash: Int32 = 1
        beatHash = beatHash &* 31 &+ Int32(truncatingIfNeeded: startTime.whole)
        beatHash = beatHash &* 31 &+ Int32(truncatingIfNeeded: startTime.numerator)
        beatHash = beatHash &* 31 &+ Int32(truncatingIfNeeded: startTime.denominator)
        let bits = effectBeat.bitPattern
        let doubleHash = Int32(
            bitPattern: UInt32(truncatingIfNeeded: bits ^ (bits >> 32))
        )
        let lineHash = Int32(truncatingIfNeeded: lineIndex + 1)
        let seed = (31 &* ((31 &* lineHash) &+ beatHash)) &+ doubleHash
        return Int(seed)
    }

    private static func latestStoryboard(
        _ line: JudgeLine,
        _ type: StoryboardEventType,
        at beat: Double
    ) -> StoryboardEvent? {
        line.storyboard.latest(type, at: beat)
    }

    private static func storyboardScale(
        _ line: JudgeLine,
        _ type: StoryboardEventType,
        at beat: Double
    ) -> Double {
        let value = latestStoryboard(line, type, at: beat)?.numericValue(at: beat, fallback: 1) ?? 1
        return value.isFinite ? value : 1
    }

    private static func gifState(
        events: [StoryboardEvent],
        at beat: Double,
        timing: TimingProfile
    ) -> (
        enabled: Bool,
        controlled: Bool,
        progress: Double,
        anchorTimeMilliseconds: Int64
    ) {
        var latest: StoryboardEvent?
        for event in events where event.startTime.doubleValue <= beat {
            if let current = latest {
                if current.startTime < event.startTime { latest = event }
            } else {
                latest = event
            }
        }
        guard let latest else {
            return (!events.isEmpty, false, 0, 0)
        }
        let endBeat = max(latest.startTime.doubleValue, latest.endTime.doubleValue)
        if endBeat > latest.startTime.doubleValue, beat < endBeat {
            return (true, true, latest.numericValue(at: beat), 0)
        }
        let finalValue = latest.numericValue(at: endBeat)
        return (
            true,
            false,
            finalValue.isFinite ? finalValue : 0,
            timing.milliseconds(atBeat: max(0, endBeat))
        )
    }

    private struct ResolvedTransform {
        var x: Double
        var y: Double
        var rotation: Double
    }

    private static func resolveLines(
        _ chart: ChartDocument,
        prepared: PreparedChart,
        at beat: Double
    ) -> [ResolvedTransform] {
        let local = chart.judgeLines.indices.map { index in
            let line = prepared.lines[index]
            return ResolvedTransform(
                x: line.value(.moveX, at: beat),
                y: line.value(.moveY, at: beat),
                rotation: -line.value(.rotate, at: beat)
            )
        }
        var resolved = Array<ResolvedTransform?>(repeating: nil, count: local.count)
        var state = Array(repeating: 0, count: local.count)

        func resolve(_ index: Int) -> ResolvedTransform {
            if let result = resolved[index] { return result }
            if state[index] == 1 {
                resolved[index] = local[index]
                state[index] = 2
                return local[index]
            }
            state[index] = 1
            let line = chart.judgeLines[index]
            let parentIndex = line.father
            guard parentIndex >= 0,
                  parentIndex < chart.judgeLines.count,
                  parentIndex != index,
                  state[parentIndex] != 1
            else {
                resolved[index] = local[index]
                state[index] = 2
                return local[index]
            }
            let parent = resolve(parentIndex)
            let radians = parent.rotation * .pi / 180
            let rotatedX = local[index].x * cos(radians) - local[index].y * sin(radians)
            let rotatedY = local[index].x * sin(radians) + local[index].y * cos(radians)
            let result = ResolvedTransform(
                x: parent.x + rotatedX,
                y: parent.y + rotatedY,
                rotation: local[index].rotation + (line.rotateWithFather ? parent.rotation : 0)
            )
            resolved[index] = result
            state[index] = 2
            return result
        }
        for index in local.indices { _ = resolve(index) }
        return resolved.map { $0 ?? ResolvedTransform(x: 0, y: 0, rotation: 0) }
    }

    private static func resolveLineTransform(
        _ chart: ChartDocument,
        prepared: PreparedChart,
        lineIndex: Int,
        at beat: Double
    ) -> ResolvedTransform {
        var visited: Set<Int> = []

        func resolve(_ index: Int) -> ResolvedTransform {
            let preparedLine = prepared.lines[index]
            let local = ResolvedTransform(
                x: preparedLine.value(.moveX, at: beat),
                y: preparedLine.value(.moveY, at: beat),
                rotation: -preparedLine.value(.rotate, at: beat)
            )
            guard visited.insert(index).inserted else { return local }
            let line = chart.judgeLines[index]
            let parentIndex = line.father
            guard parentIndex >= 0,
                  parentIndex < chart.judgeLines.count,
                  parentIndex != index,
                  !visited.contains(parentIndex)
            else {
                return local
            }
            let parent = resolve(parentIndex)
            let radians = parent.rotation * .pi / 180
            let rotatedX = local.x * cos(radians) - local.y * sin(radians)
            let rotatedY = local.x * sin(radians) + local.y * cos(radians)
            return ResolvedTransform(
                x: parent.x + rotatedX,
                y: parent.y + rotatedY,
                rotation: local.rotation + (line.rotateWithFather ? parent.rotation : 0)
            )
        }

        return resolve(lineIndex)
    }

    fileprivate struct PreparedChart: Sendable {
        var revision: UInt64
        var lineIDs: [UUID]
        var bpmIDs: [UUID]
        var useRPE170Speed: Bool
        var lines: [PreparedLine]
        var simultaneous: [BeatTime: Int]
        var judgementBeats: [Double]
        var finalBeat: Double
        var timing: TimingProfile
        var noteTimingsByLine: [[NoteTiming]]

        func matches(
            _ chart: ChartDocument,
            useRPE170Speed: Bool
        ) -> Bool {
            guard revision == chart.revision,
                  self.useRPE170Speed == useRPE170Speed,
                  lineIDs.count == chart.judgeLines.count,
                  bpmIDs.count == chart.bpmChanges.count
            else {
                return false
            }
            for index in lineIDs.indices
            where lineIDs[index] != chart.judgeLines[index].id {
                return false
            }
            for index in bpmIDs.indices
            where bpmIDs[index] != chart.bpmChanges[index].id {
                return false
            }
            return true
        }

        static func build(
            _ chart: ChartDocument,
            useRPE170Speed: Bool
        ) -> PreparedChart {
            let timing = TimingProfile(chart)
            var simultaneous: [BeatTime: Int] = [:]
            var judgementBeats: [Double] = []
            var noteTimingsByLine: [[NoteTiming]] = []
            var maximumBeat = max(1, chart.finalBeat)

            for line in chart.judgeLines {
                var lineTimings: [NoteTiming] = []
                lineTimings.reserveCapacity(line.notes.count)
                for note in line.notes {
                    let start = max(0, note.startTime.doubleValue)
                    let end = note.type == .hold
                        ? max(start, note.endTime.doubleValue)
                        : start
                    simultaneous[note.startTime, default: 0] += 1
                    lineTimings.append(
                        NoteTiming(
                            startBeat: start,
                            endBeat: end,
                            startMilliseconds: timing.milliseconds(atBeat: start),
                            endMilliseconds: timing.milliseconds(atBeat: end),
                            startDistance: 0,
                            endDistance: 0
                        )
                    )
                    maximumBeat = max(maximumBeat, end)
                    if !note.isFake {
                        if line.attachedUI == nil {
                            judgementBeats.append(note.type == .hold ? end : start)
                        }
                    }
                }
                noteTimingsByLine.append(lineTimings)
            }
            judgementBeats.sort()
            maximumBeat = max(1_000_000, maximumBeat + 1_024)
            let lines = chart.judgeLines.map {
                PreparedLine(
                    chart: chart,
                    line: $0,
                    maximumBeat: maximumBeat,
                    timing: timing,
                    useRPE170Speed: useRPE170Speed
                )
            }
            for lineIndex in chart.judgeLines.indices {
                let speed = lines[lineIndex].speed
                for noteIndex in chart.judgeLines[lineIndex].notes.indices {
                    let note = chart.judgeLines[lineIndex].notes[noteIndex]
                    var noteTiming = noteTimingsByLine[lineIndex][noteIndex]
                    noteTiming.startDistance = speed.distance(at: noteTiming.startBeat)
                    noteTiming.endDistance = note.type == .hold
                        ? speed.distance(at: noteTiming.endBeat)
                        : noteTiming.startDistance
                    noteTimingsByLine[lineIndex][noteIndex] = noteTiming
                }
            }
            return PreparedChart(
                revision: chart.revision,
                lineIDs: chart.judgeLines.map(\.id),
                bpmIDs: chart.bpmChanges.map(\.id),
                useRPE170Speed: useRPE170Speed,
                lines: lines,
                simultaneous: simultaneous,
                judgementBeats: judgementBeats,
                finalBeat: judgementBeats.last ?? 0,
                timing: timing,
                noteTimingsByLine: noteTimingsByLine
            )
        }
    }

    fileprivate struct PreparedLine: Sendable {
        var events: [EventType: [[LineEvent]]]
        var speed: SpeedProfile

        init(
            chart: ChartDocument,
            line: JudgeLine,
            maximumBeat: Double,
            timing: TimingProfile,
            useRPE170Speed: Bool
        ) {
            var preparedEvents: [EventType: [[LineEvent]]] = [:]
            for type in EventType.allCases {
                preparedEvents[type] = line.eventLayers.map {
                    $0[type].sorted { $0.startTime < $1.startTime }
                }
            }
            events = preparedEvents
            speed = SpeedProfile(
                chart: chart,
                speedLayers: preparedEvents[.speed] ?? [],
                maximumBeat: maximumBeat,
                timing: timing,
                useRPE170Speed: useRPE170Speed
            )
        }

        func value(_ type: EventType, at beat: Double) -> Double {
            guard let layers = events[type] else { return type.defaultValue }
            var total = 0.0
            var affected = false
            for layer in layers {
                var low = 0
                var high = layer.count
                while low < high {
                    let middle = (low + high) / 2
                    if layer[middle].startTime.doubleValue <= beat {
                        low = middle + 1
                    } else {
                        high = middle
                    }
                }
                if low > 0 {
                    total += layer[low - 1].value(at: beat)
                    affected = true
                }
            }
            return affected ? total : type.defaultValue
        }
    }

    fileprivate struct NoteTiming: Sendable {
        var startBeat: Double
        var endBeat: Double
        var startMilliseconds: Int64
        var endMilliseconds: Int64
        var startDistance: Double
        var endDistance: Double
    }

    fileprivate struct TimingProfile: Sendable {
        private struct Segment: Sendable {
            var startBeat: Double
            var startMilliseconds: Double
            var bpm: Double
        }

        private var segments: [Segment]

        init(_ chart: ChartDocument) {
            let changes = chart.bpmChanges.sorted { $0.startTime < $1.startTime }
            var currentBPM = Self.validBPM(changes.first?.bpm ?? 120, fallback: 120)
            var cursorBeat = 0.0
            var cursorMilliseconds = 0.0
            var result = [
                Segment(
                    startBeat: 0,
                    startMilliseconds: 0,
                    bpm: currentBPM
                ),
            ]

            for change in changes {
                let changeBeat = max(0, change.startTime.doubleValue)
                let nextBPM = Self.validBPM(change.bpm, fallback: currentBPM)
                if changeBeat <= cursorBeat {
                    currentBPM = nextBPM
                    result[result.count - 1].bpm = currentBPM
                    continue
                }
                cursorMilliseconds += (changeBeat - cursorBeat) * 60_000 / currentBPM
                cursorBeat = changeBeat
                currentBPM = nextBPM
                result.append(
                    Segment(
                        startBeat: cursorBeat,
                        startMilliseconds: cursorMilliseconds,
                        bpm: currentBPM
                    )
                )
            }
            segments = result
        }

        func bpm(at beat: Double) -> Double {
            segments[segmentIndex(at: max(0, beat))].bpm
        }

        func milliseconds(atBeat beat: Double) -> Int64 {
            let target = max(0, beat.isFinite ? beat : 0)
            let segment = segments[segmentIndex(at: target)]
            let value = segment.startMilliseconds
                + (target - segment.startBeat) * 60_000 / segment.bpm
            return Int64(max(0, value.rounded()))
        }

        func beat(atMilliseconds milliseconds: Int64) -> Double {
            let target = max(0, Double(milliseconds))
            var low = 0
            var high = segments.count
            while low < high {
                let middle = (low + high) / 2
                if segments[middle].startMilliseconds <= target {
                    low = middle + 1
                } else {
                    high = middle
                }
            }
            let segment = segments[max(0, low - 1)]
            return segment.startBeat
                + (target - segment.startMilliseconds) * segment.bpm / 60_000
        }

        private func segmentIndex(at beat: Double) -> Int {
            var low = 0
            var high = segments.count
            while low < high {
                let middle = (low + high) / 2
                if segments[middle].startBeat <= beat {
                    low = middle + 1
                } else {
                    high = middle
                }
            }
            return max(0, low - 1)
        }

        private static func validBPM(_ value: Double, fallback: Double) -> Double {
            value.isFinite && value > 0 ? value : fallback
        }
    }

    fileprivate struct SpeedInterval: Sendable {
        var startBeat: Double
        var endBeat: Double
        var contributions: [LineEvent]
        var rpeVersion: Int
        var useRPE170Speed: Bool
        var bpm: Double
        var startDistance: Double
        var endDistance: Double

        func distance(at beat: Double) -> Double {
            if beat <= startBeat { return startDistance }
            if beat >= endBeat { return endDistance }
            let speed = contributions.reduce(0) {
                $0 + $1.integratedRenderedSpeed(
                    from: startBeat,
                    to: beat,
                    rpeVersion: rpeVersion,
                    useRPE170Speed: useRPE170Speed
                )
            }
            return startDistance + speed * 60 / bpm * pixelsPerSpeedSecond
        }
    }

    fileprivate struct SpeedProfile: Sendable {
        var intervals: [SpeedInterval]

        init(
            chart: ChartDocument,
            speedLayers: [[LineEvent]],
            maximumBeat: Double,
            timing: TimingProfile,
            useRPE170Speed: Bool
        ) {
            var points: Set<Double> = [0, maximumBeat]
            for change in chart.bpmChanges {
                let value = change.startTime.doubleValue
                if value > 0, value < maximumBeat, value.isFinite { points.insert(value) }
            }
            for layer in speedLayers {
                for event in layer {
                    for value in [event.startTime.doubleValue, event.endTime.doubleValue]
                    where value > 0 && value < maximumBeat && value.isFinite {
                        points.insert(value)
                    }
                }
            }
            let sorted = points.sorted()
            var result: [SpeedInterval] = []
            var cumulative = 0.0
            for index in 0 ..< max(0, sorted.count - 1) {
                let start = sorted[index]
                let end = sorted[index + 1]
                if end <= start { continue }
                let midpoint = (start + end) / 2
                let contributions = speedLayers.compactMap { layer -> LineEvent? in
                    var low = 0
                    var high = layer.count
                    while low < high {
                        let middle = (low + high) / 2
                        if layer[middle].startTime.doubleValue <= midpoint {
                            low = middle + 1
                        } else {
                            high = middle
                        }
                    }
                    return low > 0 ? layer[low - 1] : nil
                }
                let bpm = max(1.0e-9, timing.bpm(at: midpoint))
                let integrated = contributions.reduce(0) {
                    $0 + $1.integratedRenderedSpeed(
                        from: start,
                        to: end,
                        rpeVersion: chart.rpeVersion,
                        useRPE170Speed: useRPE170Speed
                    )
                }
                let distance = integrated * 60 / bpm * pixelsPerSpeedSecond
                result.append(
                    SpeedInterval(
                        startBeat: start,
                        endBeat: end,
                        contributions: contributions,
                        rpeVersion: chart.rpeVersion,
                        useRPE170Speed: useRPE170Speed,
                        bpm: bpm,
                        startDistance: cumulative,
                        endDistance: cumulative + distance
                    )
                )
                cumulative += distance
            }
            intervals = result
        }

        func distance(at beat: Double) -> Double {
            if beat <= 0 || intervals.isEmpty { return 0 }
            var low = 0
            var high = intervals.count - 1
            while low <= high {
                let middle = (low + high) / 2
                let interval = intervals[middle]
                if beat < interval.startBeat {
                    high = middle - 1
                } else if beat > interval.endBeat {
                    low = middle + 1
                } else {
                    return interval.distance(at: beat)
                }
            }
            if low >= intervals.count { return intervals.last?.endDistance ?? 0 }
            return intervals[max(0, low)].startDistance
        }
    }

    private static func clampAlpha(_ value: Double) -> Int {
        guard value.isFinite else { return 0 }
        return Int(min(255, max(0, value)).rounded())
    }

    private static func finite(_ value: Double, fallback: Double) -> Double {
        value.isFinite ? value : fallback
    }
}

private extension Array where Element == Double {
    func partitioningIndex(where predicate: (Double) -> Bool) -> Int {
        var low = 0
        var high = count
        while low < high {
            let middle = (low + high) / 2
            if predicate(self[middle]) {
                high = middle
            } else {
                low = middle + 1
            }
        }
        return low
    }
}
