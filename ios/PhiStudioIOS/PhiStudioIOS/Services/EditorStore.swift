import Foundation
import Observation
import UIKit

public enum EditorMode: String, CaseIterable, Identifiable, Sendable {
    case notes
    case events
    case storyboard
    case preview

    public var id: String { rawValue }

    public var title: String {
        rawValue.capitalized
    }

    public var symbol: String {
        switch self {
        case .notes: "music.note"
        case .events: "waveform.path.ecg"
        case .storyboard: "wand.and.stars"
        case .preview: "play.rectangle"
        }
    }
}

public enum EditorTool: String, CaseIterable, Identifiable, Sendable {
    case select
    case tap
    case drag
    case flick
    case hold
    case event

    public var id: String { rawValue }

    public var title: String {
        rawValue.capitalized
    }
}

public enum EditorControlPanel: String, CaseIterable, Identifiable, Sendable {
    case create
    case edit
    case arrange

    public var id: String { rawValue }
    public var title: String { rawValue.capitalized }
}

public enum NoteMoveMode: String, CaseIterable, Identifiable, Sendable {
    case off
    case xy
    case x
    case y

    public var id: String { rawValue }

    public var title: String {
        switch self {
        case .off: "Move Off"
        case .xy: "Move XY"
        case .x: "Move X"
        case .y: "Move Y"
        }
    }

    public var next: NoteMoveMode {
        switch self {
        case .off: .xy
        case .xy: .x
        case .x: .y
        case .y: .off
        }
    }
}

public struct EditorChartMetrics: Hashable, Sendable {
    public var totalNotes: Int
    public var totalEvents: Int
    public var finalBeat: Double
}

public struct NoteMultiHintKey: Hashable, Sendable {
    public var type: NoteType
    public var beat: BeatTime

    public init(type: NoteType, beat: BeatTime) {
        self.type = type
        self.beat = beat
    }
}

private struct SceneCacheKey: Hashable {
    var revision: UInt64
    var beatBits: UInt64
    var highlightSimultaneousNotes: Bool
    var trackDurationMilliseconds: Int64
    var useRPE170Speed: Bool
}

private struct PlaybackHitSound {
    var timeMilliseconds: Int64
    var lineIndex: Int
    var noteIndex: Int
    var type: NoteType
}

private struct EventInterval: Hashable {
    var start: BeatTime
    var end: BeatTime
}

@MainActor
@Observable
public final class EditorStore {
    public private(set) var record: ProjectRecord
    public private(set) var package: ChartPackage
    public var chart: ChartDocument
    public var mode = EditorMode.notes
    public var currentLineIndex = 0
    public var currentLayerIndex = 0
    public var currentBeat = 0.0
    public var visibleBeats = 8.0
    public var activeNoteType = NoteType.tap
    public var activeEventType = EventType.moveX
    public var activeStoryboardType = StoryboardEventType.scaleX
    public var activeTool = EditorTool.select
    public var controlPanel = EditorControlPanel.create
    public var noteMoveMode = NoteMoveMode.off
    public var rectangleSelectionEnabled = false
    public var xyBindingEnabled = false
    public var isMultiSelecting = false
    public var selectedNoteIDs: Set<UUID> = []
    public var selectedEventIDs: Set<UUID> = []
    public var selectedStoryboardIDs: Set<UUID> = []
    public var clipboard = ChartClipboardSnapshot(notes: [], events: [])
    public var diagnostics = DiagnosticReport(
        items: [],
        totalCount: 0,
        errorCount: 0,
        warningCount: 0,
        cautionCount: 0
    )
    public private(set) var isDirty = false
    public private(set) var isSaving = false
    public private(set) var isScrubbingPlayback = false
    public var statusMessage = ""
    public var presentedError: String?
    public var inspectorPresented = false
    public var toolsPresented = false
    public var diagnosticsPresented = false
    public private(set) var illustrationImage: UIImage?
    public private(set) var lineTextures: [String: PreviewLineTexture] = [:]

    public let audio = AudioController()
    public let settings: SettingsStore

    private let library: ProjectLibrary
    private var history = ChartHistory(limit: 100)
    private var autosaveTask: Task<Void, Never>?
    private var displayLink: PlaybackDisplayLink?
    private var simulatedPlaybackDate: Date?
    private var lastEditingMode = EditorMode.notes
    private var resumePlaybackAfterScrub = false
    @ObservationIgnored private var evaluationCache = ChartEvaluationCache()
    @ObservationIgnored private var cachedScene: RenderScene?
    @ObservationIgnored private var cachedSceneKey: SceneCacheKey?
    @ObservationIgnored private var cachedMetrics: EditorChartMetrics?
    @ObservationIgnored private var cachedMetricsRevision: UInt64?
    @ObservationIgnored private var simultaneousNoteCounts: [BeatTime: Int] = [:]
    @ObservationIgnored private var editorNoteHintCounts: [NoteMultiHintKey: Int] = [:]
    @ObservationIgnored private var simultaneousNoteRevision: UInt64?
    @ObservationIgnored private var hitSoundEvents: [PlaybackHitSound] = []
    @ObservationIgnored private var hitSoundRevision: UInt64?
    @ObservationIgnored private var nextHitSoundIndex = 0
    @ObservationIgnored private var lineTextureLoadGeneration = 0

    public init(
        loaded: LoadedProject,
        library: ProjectLibrary = .shared,
        settings: SettingsStore
    ) {
        record = loaded.record
        package = loaded.package
        chart = loaded.package.chart
        self.library = library
        self.settings = settings
        visibleBeats = settings.value.defaultVisibleBeats
        let autosaveURL = loaded.package.workspaceURL.appendingPathComponent("autosave.json")
        if let autosaveDate = try? autosaveURL.resourceValues(
            forKeys: [.contentModificationDateKey]
        ).contentModificationDate,
            let chartDate = try? loaded.package.chartURL.resourceValues(
                forKeys: [.contentModificationDateKey]
            ).contentModificationDate,
            autosaveDate > chartDate,
            let recovered = try? ChartDocument(data: Data(contentsOf: autosaveURL))
        {
            chart = recovered
            isDirty = true
            statusMessage = "Recovered a newer autosave"
        }
        xyBindingEnabled = isXYBindingCompatible
        refreshDiagnostics()
        displayLink = PlaybackDisplayLink { [weak self] timestamp in
            self?.playbackTick(timestamp: timestamp)
        }
        Task { [weak self] in
            await self?.loadAudio()
        }
        Task { [weak self] in
            await self?.loadIllustration()
        }
        Task { [weak self] in
            await self?.loadLineTextures()
        }
    }

    public func shutdown() {
        autosaveTask?.cancel()
        autosaveTask = nil
        displayLink?.stop()
        audio.shutdown()
    }

    public var currentLine: JudgeLine? {
        chart.judgeLines.indices.contains(currentLineIndex)
            ? chart.judgeLines[currentLineIndex]
            : nil
    }

    public var currentLayer: EventLayer? {
        guard let currentLine,
              currentLine.eventLayers.indices.contains(currentLayerIndex)
        else {
            return nil
        }
        return currentLine.eventLayers[currentLayerIndex]
    }

    public var selectedNote: Note? {
        guard selectedNoteIDs.count == 1, let id = selectedNoteIDs.first else { return nil }
        return currentLine?.notes.first { $0.id == id }
    }

    public var selectedEvent: LineEvent? {
        guard selectedEventIDs.count == 1, let id = selectedEventIDs.first else { return nil }
        return currentLayer?.events.values.lazy.flatMap { $0 }.first { $0.id == id }
    }

    public var selectedStoryboardEvent: StoryboardEvent? {
        guard selectedStoryboardIDs.count == 1, let id = selectedStoryboardIDs.first else {
            return nil
        }
        return currentLine?.storyboard.events.values.lazy.flatMap { $0 }.first { $0.id == id }
    }

    public var canUndo: Bool { history.canUndo }
    public var canRedo: Bool { history.canRedo }
    public var isPlaying: Bool { audio.isPlaying || simulatedPlaybackDate != nil }
    public var playbackPosition: TimeInterval {
        if isScrubbingPlayback || !audio.isLoaded {
            let milliseconds = chart.audioMilliseconds(
                atBeat: currentBeat,
                packageOffset: package.manifestOffsetMilliseconds
            )
            return Double(milliseconds) / 1_000
        }
        return audio.currentTime
    }

    public var scene: RenderScene {
        let duration = audio.duration > 0 ? Int64(audio.duration * 1_000) : -1
        let useRPE170Speed = package.useRPE170Speed || settings.value.useRPE170Speed
        let key = SceneCacheKey(
            revision: chart.revision,
            beatBits: currentBeat.bitPattern,
            highlightSimultaneousNotes: settings.value.highlightSimultaneousNotes,
            trackDurationMilliseconds: duration,
            useRPE170Speed: useRPE170Speed
        )
        if cachedSceneKey == key, let cachedScene {
            return cachedScene
        }
        let rendered = ChartEvaluator.evaluate(
            chart,
            at: currentBeat,
            highlightSimultaneousNotes: settings.value.highlightSimultaneousNotes,
            trackDurationMilliseconds: duration,
            useRPE170Speed: useRPE170Speed,
            cache: &evaluationCache
        )
        cachedSceneKey = key
        cachedScene = rendered
        return rendered
    }

    public var metrics: EditorChartMetrics {
        if cachedMetricsRevision == chart.revision, let cachedMetrics {
            return cachedMetrics
        }
        let value = EditorChartMetrics(
            totalNotes: chart.judgeLines.reduce(0) { $0 + $1.notes.count },
            totalEvents: chart.judgeLines.reduce(0) { $0 + $1.eventCount },
            finalBeat: chart.finalBeat
        )
        cachedMetricsRevision = chart.revision
        cachedMetrics = value
        return value
    }

    public func isSimultaneousNote(at beat: BeatTime) -> Bool {
        prepareSimultaneousNoteCounts()
        return (simultaneousNoteCounts[beat] ?? 0) > 1
    }

    public var noteCountsByBeat: [BeatTime: Int] {
        prepareSimultaneousNoteCounts()
        return simultaneousNoteCounts
    }

    public var editorNoteCountsByTypeAndBeat: [NoteMultiHintKey: Int] {
        prepareSimultaneousNoteCounts()
        return editorNoteHintCounts
    }

    private func prepareSimultaneousNoteCounts() {
        if simultaneousNoteRevision != chart.revision {
            var values: [BeatTime: Int] = [:]
            var editorValues: [NoteMultiHintKey: Int] = [:]
            for line in chart.judgeLines {
                for note in line.notes {
                    values[note.startTime, default: 0] += 1
                    editorValues[
                        NoteMultiHintKey(type: note.type, beat: note.startTime),
                        default: 0
                    ] += 1
                }
            }
            simultaneousNoteCounts = values
            editorNoteHintCounts = editorValues
            simultaneousNoteRevision = chart.revision
        }
    }

    public func selectLine(_ index: Int) {
        guard chart.judgeLines.indices.contains(index) else { return }
        currentLineIndex = index
        currentLayerIndex = min(
            currentLayerIndex,
            max(0, chart.judgeLines[index].eventLayers.count - 1)
        )
        clearSelection()
    }

    public func selectLayer(_ index: Int) {
        guard (0 ... 3).contains(index) else { return }
        currentLayerIndex = index
        selectedEventIDs.removeAll()
    }

    public func setBeat(_ beat: Double, seekAudio: Bool = true) {
        currentBeat = max(0, beat.isFinite ? beat : 0)
        if seekAudio, audio.isLoaded {
            let milliseconds = chart.audioMilliseconds(
                atBeat: currentBeat,
                packageOffset: package.manifestOffsetMilliseconds
            )
            audio.seek(to: Double(milliseconds) / 1_000)
        }
        resetHitSoundCursor(after: currentBeat)
    }

    public func setPlaybackPosition(_ seconds: TimeInterval) {
        guard seconds.isFinite else { return }
        let milliseconds = Int64(
            min(
                9_000_000_000_000_000,
                max(0, seconds * 1_000)
            ).rounded()
        )
        currentBeat = chart.beat(
            atAudioMilliseconds: milliseconds,
            packageOffset: package.manifestOffsetMilliseconds
        )
        resetHitSoundCursor(after: currentBeat)
    }

    public func snapBeat(_ beat: Double) -> Double {
        let subdivision = max(1, settings.value.horizontalSubdivision)
        return max(0, (beat * Double(subdivision)).rounded() / Double(subdivision))
    }

    public func snapX(_ x: Double) -> Double {
        guard settings.value.snapToVerticalGrid, settings.value.showVerticalGrid else {
            return min(675, max(-675, x))
        }
        let lines = max(2, settings.value.verticalGridLines)
        let spacing = 1_350.0 / Double(lines - 1)
        return min(675, max(-675, ((x + 675) / spacing).rounded() * spacing - 675))
    }

    public func selectTool(_ tool: EditorTool) {
        activeTool = tool
        controlPanel = .create
        rectangleSelectionEnabled = false
        switch tool {
        case .tap:
            activeNoteType = .tap
            mode = .notes
        case .drag:
            activeNoteType = .drag
            mode = .notes
        case .flick:
            activeNoteType = .flick
            mode = .notes
        case .hold:
            activeNoteType = .hold
            mode = .notes
        case .event:
            mode = .events
        case .select:
            break
        }
    }

    public func cycleNoteMoveMode() {
        noteMoveMode = noteMoveMode.next
        activeTool = .select
        controlPanel = .edit
        rectangleSelectionEnabled = false
    }

    public func toggleRectangleSelection() {
        rectangleSelectionEnabled.toggle()
        activeTool = .select
        controlPanel = .edit
        if rectangleSelectionEnabled {
            statusMessage = "Drag a rectangle to add notes and events to the selection"
        }
    }

    public func togglePreviewMode() {
        if mode == .preview {
            mode = lastEditingMode == .preview ? .notes : lastEditingMode
        } else {
            lastEditingMode = mode
            mode = .preview
        }
    }

    public func cyclePlaybackRate() {
        let next: Float
        if abs(audio.rate - 1) < 0.01 {
            next = 0.75
        } else if abs(audio.rate - 0.75) < 0.01 {
            next = 0.5
        } else {
            next = 1
        }
        audio.setRate(next)
    }

    public func selectNote(_ id: UUID, extending: Bool) {
        if !extending { selectedNoteIDs.removeAll() }
        if extending, selectedNoteIDs.contains(id) {
            selectedNoteIDs.remove(id)
        } else {
            selectedNoteIDs.insert(id)
        }
        selectedEventIDs.removeAll()
        selectedStoryboardIDs.removeAll()
        mode = .notes
        activeTool = .select
        controlPanel = .edit
    }

    public func selectEvent(_ id: UUID, extending: Bool) {
        if !extending { selectedEventIDs.removeAll() }
        if extending, selectedEventIDs.contains(id) {
            selectedEventIDs.remove(id)
        } else {
            selectedEventIDs.insert(id)
        }
        selectedNoteIDs.removeAll()
        selectedStoryboardIDs.removeAll()
        mode = .events
        activeTool = .select
        controlPanel = .edit
    }

    public func selectStoryboardEvent(_ id: UUID, extending: Bool) {
        if !extending { selectedStoryboardIDs.removeAll() }
        if extending, selectedStoryboardIDs.contains(id) {
            selectedStoryboardIDs.remove(id)
        } else {
            selectedStoryboardIDs.insert(id)
        }
        selectedNoteIDs.removeAll()
        selectedEventIDs.removeAll()
        mode = .storyboard
        controlPanel = .edit
    }

    public func clearSelection() {
        selectedNoteIDs.removeAll()
        selectedEventIDs.removeAll()
        selectedStoryboardIDs.removeAll()
    }

    public func addNote(
        atX x: Double,
        beat: Double,
        type: NoteType? = nil,
        duration: Double = 1
    ) {
        mutate("Added note") { chart in
            guard chart.judgeLines.indices.contains(currentLineIndex) else { return }
            var note = Note()
            note.type = type ?? activeNoteType
            note.startTime = .fromDouble(snapBeat(beat), division: settings.value.horizontalSubdivision)
            note.endTime = note.type == .hold
                ? .fromDouble(
                    note.startTime.doubleValue + max(
                        1.0 / Double(settings.value.horizontalSubdivision),
                        duration
                    ),
                    division: settings.value.horizontalSubdivision
                )
                : note.startTime
            note.positionX = snapX(x)
            chart.judgeLines[currentLineIndex].notes.append(note)
            chart.judgeLines[currentLineIndex].notes.sort { $0.startTime < $1.startTime }
            selectedNoteIDs = [note.id]
            selectedEventIDs.removeAll()
            controlPanel = .edit
        }
    }

    public func updateNote(_ note: Note) {
        guard PropertyValidator.validate(note) == nil else {
            presentedError = PropertyValidator.validate(note)?.localizedDescription
            return
        }
        mutate("Edited note") { chart in
            guard let index = chart.judgeLines[currentLineIndex].notes.firstIndex(
                where: { $0.id == note.id }
            ) else {
                throw ChartError.invalidValue("Selected note no longer exists")
            }
            chart.judgeLines[currentLineIndex].notes[index] = note
            chart.judgeLines[currentLineIndex].notes.sort { $0.startTime < $1.startTime }
        }
    }

    public func moveSelectedNotes(beatDelta: Double, xDelta: Double) {
        guard !selectedNoteIDs.isEmpty else { return }
        mutate("Moved notes") { chart in
            var notes = chart.judgeLines[currentLineIndex].notes
            for index in notes.indices where selectedNoteIDs.contains(notes[index].id) {
                let duration = notes[index].endTime.doubleValue - notes[index].startTime.doubleValue
                let start = max(0, snapBeat(notes[index].startTime.doubleValue + beatDelta))
                notes[index].startTime = .fromDouble(
                    start,
                    division: settings.value.horizontalSubdivision
                )
                notes[index].endTime = .fromDouble(
                    start + max(0, duration),
                    division: settings.value.horizontalSubdivision
                )
                notes[index].positionX = snapX(notes[index].positionX + xDelta)
                if let error = PropertyValidator.validate(notes[index]) { throw error }
            }
            chart.judgeLines[currentLineIndex].notes = notes.sorted { $0.startTime < $1.startTime }
        }
    }

    public func addEvent(
        type: EventType? = nil,
        at beat: Double,
        duration: Double = 1,
        startValue: Double? = nil,
        endValue: Double? = nil
    ) {
        mutate("Added event") { chart in
            guard (0 ... 3).contains(currentLayerIndex) else {
                throw ChartError.invalidValue("Layers 4 and above are reserved")
            }
            ensureCurrentLayer(in: &chart)
            let eventType = type ?? activeEventType
            var event = LineEvent(type: eventType)
            let start = snapBeat(beat)
            event.startTime = .fromDouble(start, division: settings.value.horizontalSubdivision)
            event.endTime = .fromDouble(
                max(start + 1.0 / Double(settings.value.horizontalSubdivision), start + duration),
                division: settings.value.horizontalSubdivision
            )
            let layer = chart.judgeLines[currentLineIndex].eventLayers[currentLayerIndex]
            let previous = layer[eventType]
                .last { $0.startTime < event.startTime }
            event.start = startValue ?? previous?.end ?? eventType.defaultValue
            event.end = endValue ?? event.start
            if eventType != .speed, let previous {
                event.easingType = previous.easingType
            }
            if layer.overlaps(event) {
                throw ChartError.invalidValue("The new event overlaps an existing event")
            }
            chart.judgeLines[currentLineIndex].eventLayers[currentLayerIndex]
                .events[eventType, default: []].append(event)
            if xyBindingEnabled, (eventType == .moveX || eventType == .moveY) {
                let pairType: EventType = eventType == .moveX ? .moveY : .moveX
                var pair = LineEvent(type: pairType)
                pair.startTime = event.startTime
                pair.endTime = event.endTime
                let pairPrevious = layer[pairType]
                    .last { $0.startTime < pair.startTime }
                pair.start = pairPrevious?.end ?? pairType.defaultValue
                pair.end = pair.start
                if let pairPrevious {
                    pair.easingType = pairPrevious.easingType
                }
                if layer.overlaps(pair) {
                    throw ChartError.invalidValue(
                        "XY binding cannot place the paired event because it overlaps"
                    )
                }
                chart.judgeLines[currentLineIndex].eventLayers[currentLayerIndex]
                    .events[pairType, default: []].append(pair)
                chart.judgeLines[currentLineIndex].eventLayers[currentLayerIndex]
                    .events[pairType]?.sort { $0.startTime < $1.startTime }
            }
            chart.judgeLines[currentLineIndex].eventLayers[currentLayerIndex]
                .events[eventType]?.sort { $0.startTime < $1.startTime }
            selectedEventIDs = [event.id]
            selectedNoteIDs.removeAll()
            controlPanel = .edit
        }
    }

    public func updateEvent(_ event: LineEvent) {
        if let error = PropertyValidator.validate(event) {
            presentedError = error.localizedDescription
            return
        }
        mutate("Edited event") { chart in
            ensureCurrentLayer(in: &chart)
            var layer = chart.judgeLines[currentLineIndex].eventLayers[currentLayerIndex]
            guard let index = layer.events[event.type]?.firstIndex(where: { $0.id == event.id }) else {
                throw ChartError.invalidValue("Selected event no longer exists")
            }
            let original = layer.events[event.type]?[index]
            if layer.overlaps(event, ignoring: event.id) {
                throw ChartError.invalidValue("Edited event overlaps another event")
            }
            if xyBindingEnabled,
               (event.type == .moveX || event.type == .moveY),
               let original,
               (original.startTime != event.startTime || original.endTime != event.endTime)
            {
                let pairType: EventType = event.type == .moveX ? .moveY : .moveX
                let matches = (layer.events[pairType] ?? []).indices.filter { pairIndex in
                    let candidate = layer.events[pairType]?[pairIndex]
                    return candidate?.startTime == original.startTime
                        && candidate?.endTime == original.endTime
                }
                guard matches.count == 1, let pairIndex = matches.first,
                      var pair = layer.events[pairType]?[pairIndex]
                else {
                    throw ChartError.invalidValue(
                        "XY binding requires one matching opposite-axis event"
                    )
                }
                pair.startTime = event.startTime
                pair.endTime = event.endTime
                if layer.overlaps(pair, ignoring: pair.id) {
                    throw ChartError.invalidValue(
                        "The paired XY event would overlap another event"
                    )
                }
                layer.events[pairType]?[pairIndex] = pair
                layer.events[pairType]?.sort { $0.startTime < $1.startTime }
            }
            layer.events[event.type]?[index] = event
            layer.events[event.type]?.sort { $0.startTime < $1.startTime }
            chart.judgeLines[currentLineIndex].eventLayers[currentLayerIndex] = layer
        }
    }

    public func addStoryboardEvent(type: StoryboardEventType? = nil, at beat: Double) {
        mutate("Added storyboard event") { chart in
            let eventType = type ?? activeStoryboardType
            var event = StoryboardEvent(type: eventType)
            let start = snapBeat(beat)
            event.startTime = .fromDouble(start, division: settings.value.horizontalSubdivision)
            event.endTime = .fromDouble(start + 1, division: settings.value.horizontalSubdivision)
            chart.judgeLines[currentLineIndex].storyboard.events[eventType, default: []].append(event)
            chart.judgeLines[currentLineIndex].storyboard.events[eventType]?.sort {
                $0.startTime < $1.startTime
            }
            selectedStoryboardIDs = [event.id]
        }
    }

    public func updateStoryboardEvent(_ event: StoryboardEvent) {
        mutate("Edited storyboard event") { chart in
            guard let index = chart.judgeLines[currentLineIndex].storyboard.events[event.type]?
                .firstIndex(where: { $0.id == event.id })
            else {
                throw ChartError.invalidValue("Storyboard event no longer exists")
            }
            var changed = event
            changed.markModified()
            chart.judgeLines[currentLineIndex].storyboard.events[event.type]?[index] = changed
            chart.judgeLines[currentLineIndex].storyboard.events[event.type]?.sort {
                $0.startTime < $1.startTime
            }
        }
    }

    public func deleteSelection() {
        let eventsToDelete = expandedEventSelection(selectedEventIDs)
        mutate("Deleted selection") { chart in
            chart.judgeLines[currentLineIndex].notes.removeAll {
                selectedNoteIDs.contains($0.id)
            }
            if chart.judgeLines[currentLineIndex].eventLayers.indices.contains(currentLayerIndex) {
                for type in EventType.allCases {
                    chart.judgeLines[currentLineIndex].eventLayers[currentLayerIndex]
                        .events[type]?.removeAll { eventsToDelete.contains($0.id) }
                }
            }
            for type in StoryboardEventType.allCases {
                chart.judgeLines[currentLineIndex].storyboard.events[type]?.removeAll {
                    selectedStoryboardIDs.contains($0.id)
                }
            }
            clearSelection()
        }
    }

    public func copySelection() {
        let notes = currentLine?.notes.filter { selectedNoteIDs.contains($0.id) } ?? []
        let eventsToCopy = expandedEventSelection(selectedEventIDs)
        let events = currentLayer?.events.values.flatMap { $0 }
            .filter { eventsToCopy.contains($0.id) } ?? []
        clipboard = ChartClipboardSnapshot(notes: notes, events: events)
        statusMessage = clipboard.isEmpty
            ? "Nothing selected"
            : "Copied \(notes.count) notes and \(events.count) events"
    }

    public func cutSelection() {
        copySelection()
        if !clipboard.isEmpty { deleteSelection() }
    }

    public func cloneSelectedEvents(using spec: EventCloneSpec) {
        let sourceIDs = expandedEventSelection(selectedEventIDs)
        guard !sourceIDs.isEmpty else { return }
        mutate("Cloned events") { chart in
            let result = try EditorOperations.cloneEvents(
                in: &chart,
                sourceLineIndex: currentLineIndex,
                layerIndex: currentLayerIndex,
                selectedIDs: sourceIDs,
                spec: spec
            )
            let targetLineIndex = spec.lineSequence[0]
            currentLineIndex = targetLineIndex
            selectedEventIDs = Set(result.generatedEventIDsByLine[targetLineIndex] ?? [])
            selectedNoteIDs.removeAll()
            selectedStoryboardIDs.removeAll()
            mode = .events
            activeTool = .select
            controlPanel = .edit
        }
    }

    public func paste(at beat: Double, mirrored: Bool = false) {
        mutate("Pasted selection") { chart in
            ensureCurrentLayer(in: &chart)
            let shifted = try clipboard.shifted(
                to: .fromDouble(snapBeat(beat), division: settings.value.horizontalSubdivision),
                mirrorNotes: mirrored
            )
            var layer = chart.judgeLines[currentLineIndex].eventLayers[currentLayerIndex]
            for event in shifted.events {
                if layer.overlaps(event) {
                    throw ChartError.invalidValue("Pasted events overlap existing events")
                }
                layer.events[event.type, default: []].append(event)
            }
            for type in EventType.allCases {
                layer.events[type]?.sort { $0.startTime < $1.startTime }
            }
            chart.judgeLines[currentLineIndex].eventLayers[currentLayerIndex] = layer
            chart.judgeLines[currentLineIndex].notes.append(contentsOf: shifted.notes)
            chart.judgeLines[currentLineIndex].notes.sort { $0.startTime < $1.startTime }
            selectedNoteIDs = Set(shifted.notes.map(\.id))
            selectedEventIDs = Set(shifted.events.map(\.id))
        }
    }

    public func addLine() {
        mutate("Added judge line") { chart in
            var line = JudgeLine()
            line.name = "Line \(chart.judgeLines.count)"
            chart.judgeLines.append(line)
            currentLineIndex = chart.judgeLines.count - 1
            currentLayerIndex = 0
            clearSelection()
        }
    }

    public func duplicateCurrentLine() {
        mutate("Duplicated judge line") { chart in
            guard chart.judgeLines.indices.contains(currentLineIndex) else { return }
            var copy = chart.judgeLines[currentLineIndex]
            copy.id = UUID()
            copy.name += " Copy"
            copy.notes = copy.notes.map {
                var value = $0
                value.id = UUID()
                return value
            }
            copy.eventLayers = copy.eventLayers.map { layer in
                var result = layer
                result.id = UUID()
                result.events = result.events.mapValues { events in
                    events.map {
                        var event = $0
                        event.id = UUID()
                        return event
                    }
                }
                return result
            }
            copy.storyboard.events = copy.storyboard.events.mapValues { events in
                events.map {
                    var event = $0
                    event.id = UUID()
                    return event
                }
            }
            chart.judgeLines.insert(copy, at: currentLineIndex + 1)
            currentLineIndex += 1
            clearSelection()
        }
    }

    public func removeCurrentLine() {
        guard chart.judgeLines.count > 1 else {
            presentedError = "A chart must contain at least one judge line"
            return
        }
        mutate("Removed judge line") { chart in
            chart.judgeLines.remove(at: currentLineIndex)
            for index in chart.judgeLines.indices {
                if chart.judgeLines[index].father == currentLineIndex {
                    chart.judgeLines[index].father = -1
                } else if chart.judgeLines[index].father > currentLineIndex {
                    chart.judgeLines[index].father -= 1
                }
            }
            currentLineIndex = min(currentLineIndex, chart.judgeLines.count - 1)
            clearSelection()
        }
    }

    public func updateLine(_ line: JudgeLine) {
        let textureChanged = currentLine?.texture != line.texture
        mutate("Edited judge line") { chart in
            guard chart.judgeLines.indices.contains(currentLineIndex) else { return }
            chart.judgeLines[currentLineIndex] = line
        }
        if textureChanged {
            Task { [weak self] in await self?.loadLineTextures() }
        }
    }

    public func addBPM(at beat: BeatTime, bpm: Double) {
        mutate("Added BPM change") { chart in
            guard bpm.isFinite, bpm > 0 else {
                throw ChartError.invalidValue("BPM must be positive")
            }
            chart.bpmChanges.append(BPMChange(bpm: bpm, startTime: beat))
            chart.bpmChanges.sort { $0.startTime < $1.startTime }
        }
    }

    public func updateBPM(_ change: BPMChange) {
        mutate("Edited BPM change") { chart in
            guard change.bpm.isFinite, change.bpm > 0 else {
                throw ChartError.invalidValue("BPM must be positive")
            }
            guard let index = chart.bpmChanges.firstIndex(where: { $0.id == change.id }) else {
                throw ChartError.invalidValue("BPM change no longer exists")
            }
            chart.bpmChanges[index] = change
            chart.bpmChanges.sort { $0.startTime < $1.startTime }
        }
    }

    public func removeBPM(_ id: UUID) {
        guard chart.bpmChanges.count > 1 else {
            presentedError = "A chart must contain at least one BPM change"
            return
        }
        mutate("Removed BPM change") { chart in
            chart.bpmChanges.removeAll { $0.id == id }
        }
    }

    public func updateMetadata(
        name: String,
        composer: String,
        charter: String,
        level: String,
        offsetMilliseconds: Int
    ) {
        mutate("Edited chart information") { chart in
            chart.name = name
            chart.composer = composer
            chart.charter = charter
            chart.level = level
            chart.offsetMilliseconds = offsetMilliseconds
        }
    }

    public func mirrorSelectedNotes() {
        mutate("Mirrored notes") { chart in
            EditorOperations.mirrorNotes(
                &chart.judgeLines[currentLineIndex].notes,
                selectedIDs: selectedNoteIDs
            )
        }
    }

    public func flipSelectedNoteSides() {
        mutate("Flipped note sides") { chart in
            EditorOperations.flipNoteSides(
                &chart.judgeLines[currentLineIndex].notes,
                selectedIDs: selectedNoteIDs
            )
        }
    }

    public func toggleSelectedNoteFake() {
        guard var note = selectedNote else { return }
        note.isFake.toggle()
        updateNote(note)
    }

    public func resizeSelectedNote(by delta: Double) {
        guard var note = selectedNote else { return }
        note.size = min(8, max(0.1, note.size + delta))
        updateNote(note)
    }

    public func adjustSelectedEventValue(startValue: Bool, delta: Double) {
        guard var event = selectedEvent else { return }
        if startValue {
            event.start += delta
            if event.type == .alpha {
                event.start = min(255, max(-255, event.start))
            }
        } else {
            event.end += delta
            if event.type == .alpha {
                event.end = min(255, max(-255, event.end))
            }
        }
        updateEvent(event)
    }

    public func toggleSelectedEventLink() {
        guard var event = selectedEvent else { return }
        event.linkGroup = event.linkGroup == 0 ? 1 : 0
        updateEvent(event)
    }

    public func passSelectedEvent() {
        guard var event = selectedEvent, let layer = currentLayer else { return }
        let previous = (layer.events[event.type] ?? [])
            .filter { $0.id != event.id && $0.startTime < event.startTime }
            .sorted { $0.startTime < $1.startTime }
        guard previous.count >= 2 else {
            presentedError = "Pass requires two previous events of the same type"
            return
        }
        let older = previous[previous.count - 2]
        let newer = previous[previous.count - 1]
        event.start = newer.start + (newer.start - older.start)
        event.end = newer.end + (newer.end - older.end)
        updateEvent(event)
    }

    public func randomizeSelectedEvent() {
        guard var event = selectedEvent else { return }
        switch event.type {
        case .moveX:
            event.end = Double(Int.random(in: -675 ... 675))
        case .moveY:
            event.end = Double(Int.random(in: -450 ... 450))
        case .rotate:
            event.end = Double(Int.random(in: -180 ... 180))
        case .alpha:
            event.end = Double(Int.random(in: 0 ... 255))
        case .speed:
            event.end = Double(Int.random(in: 1 ... 200)) / 10
        }
        updateEvent(event)
    }

    public func selectAllVisible() {
        selectedNoteIDs = Set(currentLine?.notes.map(\.id) ?? [])
        selectedEventIDs = Set(currentLayer?.events.values.flatMap { $0 }.map(\.id) ?? [])
        selectedStoryboardIDs.removeAll()
        controlPanel = .edit
    }

    public func changeLine(by delta: Int) {
        selectLine(
            min(
                max(0, currentLineIndex + delta),
                max(0, chart.judgeLines.count - 1)
            )
        )
    }

    public func changeLayer(by delta: Int) {
        selectLayer(min(3, max(0, currentLayerIndex + delta)))
    }

    public func changeSubdivision(by delta: Int) {
        let values = [1, 2, 3, 4, 6, 8, 12, 16, 24, 32]
        let current = values.firstIndex(of: settings.value.horizontalSubdivision) ?? 3
        let next = values[min(values.count - 1, max(0, current + delta))]
        settings.update(\.horizontalSubdivision, to: next)
    }

    public func changeVerticalGrid(by delta: Int) {
        let values = EditorSettings.verticalGridOptions
        let current = values.enumerated().min { left, right in
            let target = settings.value.verticalGridLines
            let leftDistance = Swift.abs(left.element - target)
            let rightDistance = Swift.abs(right.element - target)
            return leftDistance < rightDistance
        }?.offset ?? 4
        let next = values[min(values.count - 1, max(0, current + delta))]
        settings.update(\.verticalGridLines, to: next)
    }

    public func toggleXYBinding() {
        if xyBindingEnabled {
            xyBindingEnabled = false
        } else {
            guard isXYBindingCompatible else {
                presentedError = "XY binding requires matching Move X and Move Y intervals"
                return
            }
            xyBindingEnabled = true
        }
        statusMessage = xyBindingEnabled ? "XY binding enabled" : "XY binding disabled"
    }

    public func splitSelectedEvent(at beat: BeatTime) {
        guard let id = selectedEventIDs.first, selectedEventIDs.count == 1 else {
            presentedError = "Select exactly one event"
            return
        }
        mutate("Split event") { chart in
            guard (0 ... 3).contains(currentLayerIndex) else {
                throw ChartError.invalidValue("Layers 4 and above are reserved")
            }
            ensureCurrentLayer(in: &chart)
            var layer = chart.judgeLines[currentLineIndex].eventLayers[currentLayerIndex]
            guard let type = layer.events.first(
                where: { $0.value.contains { $0.id == id } }
            )?.key,
                let source = layer[type].first(where: { $0.id == id })
            else {
                throw ChartError.invalidValue("Selected event no longer exists")
            }
            var pair: LineEvent?
            if xyBindingEnabled, type == .moveX || type == .moveY {
                let pairType: EventType = type == .moveX ? .moveY : .moveX
                let matches = layer[pairType].filter {
                    $0.startTime == source.startTime && $0.endTime == source.endTime
                }
                guard matches.count == 1 else {
                    throw ChartError.invalidValue(
                        "XY binding requires one matching opposite-axis event"
                    )
                }
                pair = matches[0]
            }
            let right = try EditorOperations.splitEvent(
                &layer.events[type, default: []],
                eventID: id,
                at: beat
            )
            if let pair {
                _ = try EditorOperations.splitEvent(
                    &layer.events[pair.type, default: []],
                    eventID: pair.id,
                    at: beat
                )
            }
            chart.judgeLines[currentLineIndex].eventLayers[currentLayerIndex] = layer
            selectedEventIDs = [right]
        }
    }

    public func glueSelectedEvent() {
        guard let id = selectedEventIDs.first, selectedEventIDs.count == 1 else {
            presentedError = "Select exactly one event"
            return
        }
        mutate("Glued event") { chart in
            ensureCurrentLayer(in: &chart)
            guard let type = chart.judgeLines[currentLineIndex].eventLayers[currentLayerIndex]
                .events.first(where: { $0.value.contains { $0.id == id } })?.key
            else {
                throw ChartError.invalidValue("Selected event no longer exists")
            }
            try EditorOperations.glueEvent(
                &chart.judgeLines[currentLineIndex].eventLayers[currentLayerIndex]
                    .events[type, default: []],
                eventID: id
            )
        }
    }

    public func generateCurveNotes(
        startID: UUID,
        endID: UUID,
        density: Double,
        subdivision: Int,
        type: NoteType,
        easing: Int
    ) {
        mutate("Generated Curve Notes") { chart in
            let ids = try EditorOperations.generateCurveNotes(
                in: &chart.judgeLines[currentLineIndex],
                from: startID,
                to: endID,
                density: density,
                subdivision: subdivision,
                noteType: type,
                easingType: easing
            )
            selectedNoteIDs = Set(ids)
        }
    }

    public func generateComplexMove(_ spec: ComplexMoveSpec) {
        mutate("Generated Complex Move") { chart in
            ensureCurrentLayer(in: &chart)
            let layer = chart.judgeLines[currentLineIndex].eventLayers[currentLayerIndex]
            let result = try EditorOperations.generateComplexMove(spec, checking: layer)
            chart.judgeLines[currentLineIndex].eventLayers[currentLayerIndex]
                .events[.moveX, default: []].append(contentsOf: result.moveXEvents)
            chart.judgeLines[currentLineIndex].eventLayers[currentLayerIndex]
                .events[.moveY, default: []].append(contentsOf: result.moveYEvents)
            chart.judgeLines[currentLineIndex].eventLayers[currentLayerIndex]
                .events[.moveX]?.sort { $0.startTime < $1.startTime }
            chart.judgeLines[currentLineIndex].eventLayers[currentLayerIndex]
                .events[.moveY]?.sort { $0.startTime < $1.startTime }
            selectedEventIDs = Set((result.moveXEvents + result.moveYEvents).map(\.id))
        }
    }

    public func batchNotes(
        field: NoteBatchField,
        profile: BatchValueProfile,
        mode: BatchMode
    ) {
        mutate("Batch edited notes") { chart in
            try EditorOperations.batchEditNotes(
                &chart.judgeLines[currentLineIndex].notes,
                selectedIDs: selectedNoteIDs,
                field: field,
                profile: profile,
                mode: mode
            )
        }
    }

    public func batchEvents(
        field: EventBatchField,
        profile: BatchValueProfile,
        mode: BatchMode,
        eventType: EventType? = nil
    ) {
        mutate("Batch edited events") { chart in
            ensureCurrentLayer(in: &chart)
            let type = eventType ?? selectedEventIDs.compactMap({ id in
                chart.judgeLines[currentLineIndex].eventLayers[currentLayerIndex]
                    .events.first(where: { $0.value.contains { $0.id == id } })?.key
            }).first
            guard let type else {
                throw ChartError.invalidValue("Select at least one event")
            }
            try EditorOperations.batchEditEvents(
                &chart.judgeLines[currentLineIndex].eventLayers[currentLayerIndex]
                    .events[type, default: []],
                selectedIDs: selectedEventIDs,
                field: field,
                profile: profile,
                mode: mode
            )
        }
    }

    public func undo() {
        guard let previous = history.undo(current: chart) else { return }
        chart = previous
        invalidateDerivedState()
        normalizeIndices()
        isDirty = true
        scheduleAutosave()
        refreshDiagnostics()
        Task { [weak self] in await self?.loadLineTextures() }
    }

    public func redo() {
        guard let next = history.redo(current: chart) else { return }
        chart = next
        invalidateDerivedState()
        normalizeIndices()
        isDirty = true
        scheduleAutosave()
        refreshDiagnostics()
        Task { [weak self] in await self?.loadLineTextures() }
    }

    public func save() async {
        guard !isSaving else { return }
        isSaving = true
        let snapshot = chart
        do {
            let updated = try await library.save(
                projectID: record.id,
                package: package,
                chart: snapshot
            )
            record = updated
            package.chart = snapshot
            isDirty = chart.revision != snapshot.revision
            try? FileManager.default.removeItem(
                at: package.workspaceURL.appendingPathComponent("autosave.json")
            )
            statusMessage = "Saved"
        } catch {
            presentedError = error.localizedDescription
        }
        isSaving = false
    }

    public func makeExportData() async -> Data? {
        let destination = FileManager.default.temporaryDirectory
            .appendingPathComponent("PhiStudio-\(UUID().uuidString).pez")
        defer { try? FileManager.default.removeItem(at: destination) }
        do {
            try await library.export(record.id, chart: chart, to: destination)
            return try Data(contentsOf: destination)
        } catch {
            presentedError = error.localizedDescription
            return nil
        }
    }

    public func replaceAsset(_ sourceURL: URL, kind: ProjectLibrary.AssetKind) async {
        let scoped = sourceURL.startAccessingSecurityScopedResource()
        defer { if scoped { sourceURL.stopAccessingSecurityScopedResource() } }
        do {
            let loaded = try await library.replaceAsset(
                projectID: record.id,
                sourceURL: sourceURL,
                kind: kind,
                chart: chart
            )
            record = loaded.record
            package = loaded.package
            chart = loaded.package.chart
            invalidateDerivedState()
            isDirty = false
            if kind == .audio {
                try await audio.load(package.audioURL)
            } else {
                await loadIllustration()
            }
            statusMessage = kind == .audio
                ? "Replaced music"
                : "Replaced illustration"
        } catch {
            presentedError = error.localizedDescription
        }
    }

    public func togglePlayback() {
        isPlaying ? pausePlayback() : startPlayback()
    }

    public func startPlayback(fromBeginning: Bool = false) {
        if fromBeginning { setBeat(0) }
        prepareHitSoundIndex()
        resetHitSoundCursor(after: currentBeat <= 1.0e-9 ? -1.0e-9 : currentBeat)
        if audio.isLoaded {
            let milliseconds = chart.audioMilliseconds(
                atBeat: currentBeat,
                packageOffset: package.manifestOffsetMilliseconds
            )
            audio.seek(to: Double(milliseconds) / 1_000)
            audio.play()
        } else {
            simulatedPlaybackDate = .now
        }
        displayLink?.start()
    }

    public func pausePlayback() {
        audio.pause()
        simulatedPlaybackDate = nil
        displayLink?.stop()
    }

    public func stopPlayback(reset: Bool = false) {
        audio.stop()
        simulatedPlaybackDate = nil
        displayLink?.stop()
        if reset { setBeat(0) }
    }

    public func beginScrubbing() {
        isScrubbingPlayback = true
        resumePlaybackAfterScrub = isPlaying
        audio.pause()
        simulatedPlaybackDate = nil
        displayLink?.stop()
    }

    public func endScrubbing() {
        resetHitSoundCursor(after: currentBeat)
        if audio.isLoaded {
            let milliseconds = chart.audioMilliseconds(
                atBeat: currentBeat,
                packageOffset: package.manifestOffsetMilliseconds
            )
            audio.seek(to: Double(milliseconds) / 1_000)
            if resumePlaybackAfterScrub {
                audio.play()
            }
        } else if resumePlaybackAfterScrub {
            simulatedPlaybackDate = .now
        }
        if resumePlaybackAfterScrub {
            displayLink?.start()
        }
        resumePlaybackAfterScrub = false
        isScrubbingPlayback = false
    }

    public func refreshDiagnostics() {
        diagnostics = ChartDiagnostics.diagnose(chart)
    }

    private func loadAudio() async {
        do {
            try await audio.load(package.audioURL)
        } catch {
            presentedError = "Audio: \(error.localizedDescription)"
        }
    }

    private func loadIllustration() async {
        guard let url = package.illustrationURL else {
            illustrationImage = nil
            return
        }
        let data = await Task.detached(priority: .utility) {
            try? Data(contentsOf: url, options: [.mappedIfSafe])
        }.value
        illustrationImage = data.flatMap(UIImage.init(data:))
    }

    private func loadLineTextures() async {
        lineTextureLoadGeneration &+= 1
        let generation = lineTextureLoadGeneration
        let workspace = package.workspaceURL.standardizedFileURL
        let names = chart.judgeLines.map(\.texture)
        let textures = await Task.detached(priority: .utility) {
            PreviewLineTextureLoader.load(
                workspaceURL: workspace,
                textureNames: names
            )
        }.value
        guard generation == lineTextureLoadGeneration,
              package.workspaceURL.standardizedFileURL == workspace
        else {
            return
        }
        lineTextures = textures
    }

    private func playbackTick(timestamp _: CFTimeInterval) {
        let previous = currentBeat
        if audio.isLoaded {
            audio.updateTime()
            currentBeat = chart.beat(
                atAudioMilliseconds: Int64(audio.currentTime * 1_000),
                packageOffset: package.manifestOffsetMilliseconds
            )
            if !audio.isPlaying {
                displayLink?.stop()
            }
        } else if let date = simulatedPlaybackDate {
            let elapsed = Date().timeIntervalSince(date)
            simulatedPlaybackDate = .now
            currentBeat += elapsed * chart.bpm(at: currentBeat) / 60 * Double(audio.rate)
            if currentBeat >= chart.finalBeat + 1 {
                simulatedPlaybackDate = nil
                displayLink?.stop()
            }
        }
        if settings.value.enableHitSounds, currentBeat >= previous {
            playCrossedHitSounds(to: currentBeat)
        } else if currentBeat < previous {
            resetHitSoundCursor(after: currentBeat)
        }
    }

    private func prepareHitSoundIndex() {
        guard hitSoundRevision != chart.revision else { return }
        var result: [PlaybackHitSound] = []
        for (lineIndex, line) in chart.judgeLines.enumerated() {
            for (noteIndex, note) in line.notes.enumerated() {
                if note.isFake { continue }
                result.append(
                    PlaybackHitSound(
                        timeMilliseconds: chart.milliseconds(
                            atBeat: max(0, note.startTime.doubleValue)
                        ),
                    lineIndex: lineIndex,
                    noteIndex: noteIndex,
                    type: note.type
                )
                )
            }
        }
        hitSoundEvents = result.sorted {
            if $0.timeMilliseconds != $1.timeMilliseconds {
                return $0.timeMilliseconds < $1.timeMilliseconds
            }
            if $0.lineIndex != $1.lineIndex {
                return $0.lineIndex < $1.lineIndex
            }
            return $0.noteIndex < $1.noteIndex
        }
        hitSoundRevision = chart.revision
        nextHitSoundIndex = 0
    }

    private func resetHitSoundCursor(after beat: Double) {
        prepareHitSoundIndex()
        let timeMilliseconds = beat < 0
            ? -1
            : chart.milliseconds(atBeat: beat)
        var low = 0
        var high = hitSoundEvents.count
        while low < high {
            let middle = (low + high) / 2
            if hitSoundEvents[middle].timeMilliseconds <= timeMilliseconds {
                low = middle + 1
            } else {
                high = middle
            }
        }
        nextHitSoundIndex = low
    }

    private func playCrossedHitSounds(to beat: Double) {
        prepareHitSoundIndex()
        let timeMilliseconds = chart.milliseconds(atBeat: beat)
        var crossed: [PlaybackHitSound] = []
        while nextHitSoundIndex < hitSoundEvents.count,
              hitSoundEvents[nextHitSoundIndex].timeMilliseconds <= timeMilliseconds
        {
            if crossed.count < 48 {
                crossed.append(hitSoundEvents[nextHitSoundIndex])
            }
            nextHitSoundIndex += 1
        }
        guard let firstTime = crossed.first?.timeMilliseconds else { return }
        audio.playHitSounds(
            crossed.map {
                ScheduledHitSound(
                    type: $0.type,
                    delaySeconds: Double($0.timeMilliseconds - firstTime) / 1_000
                )
            }
        )
    }

    private func invalidateDerivedState() {
        evaluationCache.invalidate()
        cachedScene = nil
        cachedSceneKey = nil
        cachedMetrics = nil
        cachedMetricsRevision = nil
        simultaneousNoteCounts.removeAll(keepingCapacity: true)
        editorNoteHintCounts.removeAll(keepingCapacity: true)
        simultaneousNoteRevision = nil
        hitSoundEvents.removeAll(keepingCapacity: true)
        hitSoundRevision = nil
        nextHitSoundIndex = 0
    }

    private func expandedEventSelection(_ source: Set<UUID>) -> Set<UUID> {
        guard xyBindingEnabled, let layer = currentLayer else { return source }
        var result = source
        let selected = layer.events.values.flatMap { $0 }.filter { source.contains($0.id) }
        for event in selected where (event.type == .moveX || event.type == .moveY) {
            let pairedType: EventType = event.type == .moveX ? .moveY : .moveX
            let matches = layer[pairedType].filter {
                $0.startTime == event.startTime && $0.endTime == event.endTime
            }
            if matches.count == 1, let pair = matches.first {
                result.insert(pair.id)
            }
        }
        return result
    }

    private var isXYBindingCompatible: Bool {
        for line in chart.judgeLines {
            for layer in line.eventLayers.prefix(4) {
                let moveX = layer[.moveX]
                let moveY = layer[.moveY]
                guard validIntervals(moveX), validIntervals(moveY) else { return false }
                let xIntervals = moveX.map {
                    EventInterval(start: $0.startTime, end: $0.endTime)
                }
                let yIntervals = moveY.map {
                    EventInterval(start: $0.startTime, end: $0.endTime)
                }
                guard xIntervals.count == Set(xIntervals).count,
                      yIntervals.count == Set(yIntervals).count,
                      Set(xIntervals) == Set(yIntervals)
                else {
                    return false
                }
            }
        }
        return true
    }

    private func validIntervals(_ events: [LineEvent]) -> Bool {
        let sorted = events.sorted { $0.startTime < $1.startTime }
        for index in sorted.indices {
            if sorted[index].startTime.doubleValue < 0
                || sorted[index].endTime <= sorted[index].startTime
            {
                return false
            }
            if index > 0, sorted[index].startTime < sorted[index - 1].endTime {
                return false
            }
        }
        return true
    }

    @discardableResult
    private func mutate<T>(
        _ status: String,
        _ body: (inout ChartDocument) throws -> T
    ) -> T? {
        let before = chart
        do {
            let result = try body(&chart)
            history.record(before)
            chart.markEdited()
            invalidateDerivedState()
            isDirty = true
            statusMessage = status
            refreshDiagnostics()
            scheduleAutosave()
            return result
        } catch {
            chart = before
            presentedError = error.localizedDescription
            return nil
        }
    }

    private func ensureCurrentLayer(in chart: inout ChartDocument) {
        while chart.judgeLines[currentLineIndex].eventLayers.count <= currentLayerIndex {
            chart.judgeLines[currentLineIndex].eventLayers.append(EventLayer())
        }
    }

    private func normalizeIndices() {
        currentLineIndex = min(max(0, currentLineIndex), max(0, chart.judgeLines.count - 1))
        currentLayerIndex = min(
            max(0, currentLayerIndex),
            max(0, chart.judgeLines[currentLineIndex].eventLayers.count - 1)
        )
        clearSelection()
    }

    private func scheduleAutosave() {
        autosaveTask?.cancel()
        guard settings.value.autosaveEnabled else { return }
        let delay = settings.value.autosaveDelaySeconds
        let snapshot = chart
        let destination = package.workspaceURL.appendingPathComponent("autosave.json")
        autosaveTask = Task {
            try? await Task.sleep(for: .seconds(delay))
            guard !Task.isCancelled else { return }
            do {
                let data = try snapshot.encoded()
                try await Task.detached(priority: .utility) {
                    try data.write(to: destination, options: .atomic)
                }.value
                guard !Task.isCancelled else { return }
                statusMessage = "Autosaved"
            } catch {
                if !Task.isCancelled {
                    presentedError = "Autosave: \(error.localizedDescription)"
                }
            }
        }
    }
}

@MainActor
private final class PlaybackDisplayLink: NSObject {
    private let callback: (CFTimeInterval) -> Void
    private var link: CADisplayLink?

    init(callback: @escaping (CFTimeInterval) -> Void) {
        self.callback = callback
    }

    func start() {
        guard link == nil else { return }
        let link = CADisplayLink(target: self, selector: #selector(tick(_:)))
        link.preferredFrameRateRange = CAFrameRateRange(minimum: 30, maximum: 60, preferred: 60)
        link.add(to: .main, forMode: .common)
        self.link = link
    }

    func stop() {
        link?.invalidate()
        link = nil
    }

    @objc private func tick(_ sender: CADisplayLink) {
        callback(sender.timestamp)
    }
}

@MainActor
@Observable
public final class AppModel {
    public private(set) var projects: [ProjectRecord] = []
    public var editor: EditorStore?
    public var isBusy = false
    public var presentedError: String?
    public let settings = SettingsStore()

    private let library: ProjectLibrary

    public init(library: ProjectLibrary = .shared) {
        self.library = library
    }

    public func refresh() async {
        isBusy = true
        do {
            projects = try await library.list()
        } catch {
            presentedError = error.localizedDescription
        }
        isBusy = false
    }

    public func open(_ id: UUID) async {
        isBusy = true
        do {
            let loaded = try await library.open(id)
            editor = EditorStore(loaded: loaded, library: library, settings: settings)
        } catch {
            presentedError = error.localizedDescription
        }
        isBusy = false
    }

    public func importFile(_ url: URL) async {
        isBusy = true
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        do {
            let loaded = try await library.importFile(url)
            projects = try await library.list()
            editor = EditorStore(loaded: loaded, library: library, settings: settings)
        } catch {
            presentedError = error.localizedDescription
        }
        isBusy = false
    }

    public func createProject(
        name: String,
        composer: String,
        charter: String,
        level: String,
        bpm: Double,
        audioURL: URL?,
        illustrationURL: URL?
    ) async {
        isBusy = true
        let audioScoped = audioURL?.startAccessingSecurityScopedResource() ?? false
        let artScoped = illustrationURL?.startAccessingSecurityScopedResource() ?? false
        defer {
            if audioScoped { audioURL?.stopAccessingSecurityScopedResource() }
            if artScoped { illustrationURL?.stopAccessingSecurityScopedResource() }
        }
        do {
            let loaded = try await library.createProject(
                name: name,
                composer: composer,
                charter: charter,
                level: level,
                bpm: bpm,
                audioSource: audioURL,
                illustrationSource: illustrationURL
            )
            projects = try await library.list()
            editor = EditorStore(loaded: loaded, library: library, settings: settings)
        } catch {
            presentedError = error.localizedDescription
        }
        isBusy = false
    }

    public func duplicate(_ id: UUID) async {
        isBusy = true
        do {
            _ = try await library.duplicate(id)
            projects = try await library.list()
        } catch {
            presentedError = error.localizedDescription
        }
        isBusy = false
    }

    public func remove(_ id: UUID) async {
        isBusy = true
        do {
            try await library.remove(id)
            projects = try await library.list()
        } catch {
            presentedError = error.localizedDescription
        }
        isBusy = false
    }

    public func closeEditor() async {
        if let editor {
            if editor.isDirty {
                await editor.save()
            }
            editor.shutdown()
        }
        editor = nil
        await refresh()
    }
}
