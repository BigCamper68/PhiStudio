import SwiftUI

struct TimelineEditorView: View {
    @Bindable var store: EditorStore
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @State private var gestureStart: CGPoint?
    @State private var scrollOriginBeat: Double?
    @State private var zoomOrigin = 8.0
    @State private var selectionRectangle: CGRect?
    @State private var isMovingSelection = false
    @State private var movePreviewTranslation = CGSize.zero

    private var noteRegionRatio: CGFloat {
        guard horizontalSizeClass != .regular else { return 0.70 }
        return store.mode == .events ? 0 : 1
    }

    var body: some View {
        GeometryReader { proxy in
            let renderSnapshot = TimelineRenderSnapshot(
                store: store,
                movePreview: isMovingSelection
                    ? moveDeltas(for: movePreviewTranslation, size: proxy.size)
                    : nil
            )
            ZStack {
                HStack(spacing: 0) {
                    if noteRegionRatio > 0 {
                        ZStack {
                            timelineBackground
                            PreviewCanvasView(
                                store: store,
                                showsStatus: false,
                                togglesPlaybackOnTap: false,
                                includesGameHUD: false,
                                includesHitEffects: true,
                                includesBackground: false,
                                includesNotes: false
                            )
                            .opacity(0.50)
                        }
                        .frame(width: proxy.size.width * noteRegionRatio)
                    }
                    if noteRegionRatio < 1 {
                        Color(red: 0.045, green: 0.075, blue: 0.09)
                    }
                }

                Canvas(opaque: false, colorMode: .nonLinear, rendersAsynchronously: false) {
                    context, size in
                    TimelineRenderer(
                        snapshot: renderSnapshot,
                        noteRegionRatio: noteRegionRatio
                    ).draw(context: context, size: size)
                }

                if let selectionRectangle {
                    Rectangle()
                        .fill(Color.cyan.opacity(0.12))
                        .overlay {
                            Rectangle()
                                .stroke(Color.cyan.opacity(0.9), lineWidth: 1.5)
                        }
                        .frame(
                            width: selectionRectangle.width,
                            height: selectionRectangle.height
                        )
                        .position(
                            x: selectionRectangle.midX,
                            y: selectionRectangle.midY
                        )
                        .allowsHitTesting(false)
                }
            }
            .contentShape(Rectangle())
            .gesture(editorGesture(in: proxy.size))
            .simultaneousGesture(
                MagnifyGesture()
                    .onChanged { value in
                        let magnification = max(0.2, Double(value.magnification))
                        store.visibleBeats = min(64, max(1, zoomOrigin / magnification))
                    }
                    .onEnded { _ in
                        zoomOrigin = store.visibleBeats
                    }
            )
            .clipped()
        }
        .background(Color(red: 0.025, green: 0.03, blue: 0.055))
        .onAppear { zoomOrigin = store.visibleBeats }
        .accessibilityLabel("Chart timeline and event lanes")
        .accessibilityHint("Tap to place or select. Drag Hold and Event to set duration.")
    }

    @ViewBuilder
    private var timelineBackground: some View {
        ZStack {
            Color(red: 0.039, green: 0.055, blue: 0.071)
            if let image = store.illustrationImage {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFill()
                    .clipped()
            }
            Color.black.opacity(store.settings.value.backgroundDim)
        }
    }

    private func editorGesture(in size: CGSize) -> some Gesture {
        DragGesture(minimumDistance: 0, coordinateSpace: .local)
            .onChanged { value in
                if gestureStart == nil {
                    gestureStart = value.startLocation
                    if store.rectangleSelectionEnabled {
                        selectionRectangle = CGRect(
                            origin: value.startLocation,
                            size: .zero
                        )
                    } else if store.activeTool == .select {
                        if store.noteMoveMode != .off,
                           !store.selectedNoteIDs.isEmpty,
                           isTouchingSelectedNote(at: value.startLocation, size: size)
                        {
                            isMovingSelection = true
                            movePreviewTranslation = .zero
                        } else {
                            scrollOriginBeat = store.currentBeat
                            store.beginScrubbing()
                        }
                    }
                }
                if store.rectangleSelectionEnabled {
                    selectionRectangle = rectangle(
                        from: value.startLocation,
                        to: value.location
                    )
                } else if isMovingSelection {
                    movePreviewTranslation = value.translation
                } else if store.activeTool == .select,
                          value.translation.height.magnitude > 8
                {
                    let origin = scrollOriginBeat ?? store.currentBeat
                    let usableHeight = max(
                        1,
                        size.height - TimelineRenderer.headerHeight
                            - TimelineRenderer.playheadInset
                    )
                    let delta = Double(
                        value.translation.height / usableHeight
                    ) * store.visibleBeats
                    store.setBeat(max(0, origin + delta), seekAudio: false)
                }
            }
            .onEnded { value in
                defer {
                    if scrollOriginBeat != nil {
                        store.endScrubbing()
                    }
                    gestureStart = nil
                    scrollOriginBeat = nil
                    selectionRectangle = nil
                    isMovingSelection = false
                    movePreviewTranslation = .zero
                }

                if store.rectangleSelectionEnabled {
                    selectItems(in: rectangle(from: value.startLocation, to: value.location), size: size)
                    return
                }

                if isMovingSelection {
                    let delta = moveDeltas(for: value.translation, size: size)
                    store.moveSelectedNotes(
                        beatDelta: delta.beatDelta,
                        xDelta: delta.xDelta
                    )
                    return
                }

                let isTap = hypot(value.translation.width, value.translation.height) < 8
                if store.activeTool == .select {
                    if isTap {
                        selectNearest(at: value.location, size: size)
                    }
                    return
                }
                placeTool(
                    store.activeTool,
                    from: value.startLocation,
                    to: value.location,
                    size: size
                )
            }
    }

    private func placeTool(
        _ tool: EditorTool,
        from start: CGPoint,
        to end: CGPoint,
        size: CGSize
    ) {
        let split = size.width * noteRegionRatio
        switch tool {
        case .select:
            break
        case .tap, .drag, .flick, .hold:
            guard start.x < split else { return }
            let startBeat = beat(at: start.y, height: size.height)
            let endBeat = beat(at: end.y, height: size.height)
            let type: NoteType
            switch tool {
            case .tap: type = .tap
            case .drag: type = .drag
            case .flick: type = .flick
            case .hold: type = .hold
            default: return
            }
            store.addNote(
                atX: noteX(at: start.x, split: split),
                beat: min(startBeat, endBeat),
                type: type,
                duration: type == .hold ? abs(endBeat - startBeat) : 0
            )
        case .event:
            guard start.x >= split else { return }
            let lane = eventLane(at: start.x, split: split, width: size.width)
            guard EventType.allCases.indices.contains(lane) else { return }
            let type = EventType.allCases[lane]
            store.activeEventType = type
            let startBeat = beat(at: start.y, height: size.height)
            let endBeat = beat(at: end.y, height: size.height)
            store.addEvent(
                type: type,
                at: min(startBeat, endBeat),
                duration: abs(endBeat - startBeat)
            )
        }
    }

    private func selectNearest(at point: CGPoint, size: CGSize) {
        let split = size.width * noteRegionRatio
        if point.x < split {
            guard let note = nearestNote(to: point, size: size, split: split),
                  note.distance < 36
            else {
                store.clearSelection()
                return
            }
            store.selectNote(note.id, extending: store.isMultiSelecting)
        } else {
            guard let event = nearestEvent(to: point, size: size, split: split),
                  event.distance < 42
            else {
                store.clearSelection()
                return
            }
            store.selectEvent(event.id, extending: store.isMultiSelecting)
        }
    }

    private func selectItems(in rect: CGRect, size: CGSize) {
        let split = size.width * noteRegionRatio
        let noteIDs = store.currentLine?.notes.compactMap { note -> UUID? in
            let point = CGPoint(
                x: timelineX(note.positionX, split: split),
                y: TimelineRenderer.yPosition(
                    beat: note.startTime.doubleValue,
                    currentBeat: store.currentBeat,
                    visibleBeats: store.visibleBeats,
                    height: size.height
                )
            )
            let endY = note.type == .hold
                ? TimelineRenderer.yPosition(
                    beat: note.endTime.doubleValue,
                    currentBeat: store.currentBeat,
                    visibleBeats: store.visibleBeats,
                    height: size.height
                )
                : point.y
            let noteRect = CGRect(
                x: point.x - 18,
                y: min(point.y, endY),
                width: 36,
                height: max(1, abs(point.y - endY))
            )
            return rect.intersects(noteRect) ? note.id : nil
        } ?? []
        let eventIDs = store.currentLayer?.events.values.flatMap { $0 }.compactMap {
            event -> UUID? in
            let typeIndex = EventType.allCases.firstIndex(of: event.type) ?? 0
            let startPoint = eventPoint(
                typeIndex: typeIndex,
                beat: event.startTime.doubleValue,
                size: size,
                split: split
            )
            let endPoint = eventPoint(
                typeIndex: typeIndex,
                beat: event.endTime.doubleValue,
                size: size,
                split: split
            )
            let laneWidth = (size.width - split) / CGFloat(EventType.allCases.count)
            let eventRect = CGRect(
                x: startPoint.x - laneWidth / 2,
                y: min(startPoint.y, endPoint.y),
                width: laneWidth,
                height: max(1, abs(startPoint.y - endPoint.y))
            )
            return rect.intersects(eventRect) ? event.id : nil
        } ?? []
        store.selectedNoteIDs.formUnion(noteIDs)
        store.selectedEventIDs.formUnion(eventIDs)
        store.activeTool = .select
        store.controlPanel = .edit
        store.statusMessage = "Added \(noteIDs.count + eventIDs.count) items to selection"
    }

    private func moveDeltas(for translation: CGSize, size: CGSize) -> NoteMovePreview {
        let split = max(1, size.width * noteRegionRatio)
        let usableHeight = max(
            1,
            size.height - TimelineRenderer.headerHeight - TimelineRenderer.playheadInset
        )
        var xDelta = Double(translation.width / split) * 1_350
        var beatDelta = -Double(translation.height / usableHeight) * store.visibleBeats
        switch store.noteMoveMode {
        case .off:
            return NoteMovePreview(xDelta: 0, beatDelta: 0)
        case .xy:
            break
        case .x:
            beatDelta = 0
        case .y:
            xDelta = 0
        }
        return NoteMovePreview(xDelta: xDelta, beatDelta: beatDelta)
    }

    private func isTouchingSelectedNote(at point: CGPoint, size: CGSize) -> Bool {
        let split = size.width * noteRegionRatio
        guard point.x < split else { return false }
        return store.currentLine?.notes.contains { note in
            guard store.selectedNoteIDs.contains(note.id) else { return false }
            let start = CGPoint(
                x: timelineX(note.positionX, split: split),
                y: TimelineRenderer.yPosition(
                    beat: note.startTime.doubleValue,
                    currentBeat: store.currentBeat,
                    visibleBeats: store.visibleBeats,
                    height: size.height
                )
            )
            let endY = note.type == .hold
                ? TimelineRenderer.yPosition(
                    beat: note.endTime.doubleValue,
                    currentBeat: store.currentBeat,
                    visibleBeats: store.visibleBeats,
                    height: size.height
                )
                : start.y
            let nearestY = min(max(point.y, min(start.y, endY)), max(start.y, endY))
            return hypot(start.x - point.x, nearestY - point.y) <= 38
        } == true
    }

    private func nearestNote(
        to point: CGPoint,
        size: CGSize,
        split: CGFloat
    ) -> (id: UUID, distance: CGFloat)? {
        store.currentLine?.notes.compactMap { note in
            let location = CGPoint(
                x: timelineX(note.positionX, split: split),
                y: TimelineRenderer.yPosition(
                    beat: note.startTime.doubleValue,
                    currentBeat: store.currentBeat,
                    visibleBeats: store.visibleBeats,
                    height: size.height
                )
            )
            let endY = note.type == .hold
                ? TimelineRenderer.yPosition(
                    beat: note.endTime.doubleValue,
                    currentBeat: store.currentBeat,
                    visibleBeats: store.visibleBeats,
                    height: size.height
                )
                : location.y
            guard max(location.y, endY) >= -40,
                  min(location.y, endY) <= size.height + 40
            else {
                return nil
            }
            let nearestY = min(max(point.y, min(location.y, endY)), max(location.y, endY))
            return (note.id, hypot(location.x - point.x, nearestY - point.y))
        }.min { $0.distance < $1.distance }
    }

    private func nearestEvent(
        to point: CGPoint,
        size: CGSize,
        split: CGFloat
    ) -> (id: UUID, distance: CGFloat)? {
        store.currentLayer?.events.values.flatMap { $0 }.map { event in
            let start = eventPoint(
                typeIndex: EventType.allCases.firstIndex(of: event.type) ?? 0,
                beat: event.startTime.doubleValue,
                size: size,
                split: split
            )
            let end = eventPoint(
                typeIndex: EventType.allCases.firstIndex(of: event.type) ?? 0,
                beat: event.endTime.doubleValue,
                size: size,
                split: split
            )
            let nearestY = min(max(point.y, min(start.y, end.y)), max(start.y, end.y))
            return (event.id, hypot(start.x - point.x, nearestY - point.y))
        }.min { $0.distance < $1.distance }
    }

    private func beat(at y: CGFloat, height: CGFloat) -> Double {
        let usableHeight = max(
            1,
            height - TimelineRenderer.headerHeight - TimelineRenderer.playheadInset
        )
        let fraction = Double((height - TimelineRenderer.playheadInset - y) / usableHeight)
        return store.snapBeat(max(0, store.currentBeat + fraction * store.visibleBeats))
    }

    private func noteX(at x: CGFloat, split: CGFloat) -> Double {
        store.snapX(Double(x / max(1, split)) * 1_350 - 675)
    }

    private func timelineX(_ x: Double, split: CGFloat) -> CGFloat {
        CGFloat((x + 675) / 1_350) * split
    }

    private func eventLane(at x: CGFloat, split: CGFloat, width: CGFloat) -> Int {
        let laneWidth = max(1, (width - split) / CGFloat(EventType.allCases.count))
        return min(
            EventType.allCases.count - 1,
            max(0, Int((x - split) / laneWidth))
        )
    }

    private func eventPoint(
        typeIndex: Int,
        beat: Double,
        size: CGSize,
        split: CGFloat
    ) -> CGPoint {
        let laneWidth = (size.width - split) / CGFloat(EventType.allCases.count)
        return CGPoint(
            x: split + laneWidth * (CGFloat(typeIndex) + 0.5),
            y: TimelineRenderer.yPosition(
                beat: beat,
                currentBeat: store.currentBeat,
                visibleBeats: store.visibleBeats,
                height: size.height
            )
        )
    }

    private func rectangle(from start: CGPoint, to end: CGPoint) -> CGRect {
        CGRect(
            x: min(start.x, end.x),
            y: min(start.y, end.y),
            width: abs(end.x - start.x),
            height: abs(end.y - start.y)
        )
    }
}

private struct NoteMovePreview: Sendable {
    var xDelta: Double
    var beatDelta: Double
}

private struct TimelineRenderSnapshot: Sendable {
    var currentLine: JudgeLine?
    var currentLayer: EventLayer?
    var currentBeat: Double
    var visibleBeats: Double
    var settings: EditorSettings
    var activeTool: EditorTool
    var activeEventType: EventType
    var selectedNoteIDs: Set<UUID>
    var selectedEventIDs: Set<UUID>
    var noteCountsByTypeAndBeat: [NoteMultiHintKey: Int]
    var metrics: EditorChartMetrics
    var lineNoteCount: Int
    var lineEventCount: Int
    var bpm: Double
    var audioCurrentTime: TimeInterval
    var audioDuration: TimeInterval
    var movePreview: NoteMovePreview?

    @MainActor
    init(store: EditorStore, movePreview: NoteMovePreview?) {
        currentLine = store.currentLine
        currentLayer = store.currentLayer
        currentBeat = store.currentBeat
        visibleBeats = store.visibleBeats
        settings = store.settings.value
        activeTool = store.activeTool
        activeEventType = store.activeEventType
        selectedNoteIDs = store.selectedNoteIDs
        selectedEventIDs = store.selectedEventIDs
        noteCountsByTypeAndBeat = store.editorNoteCountsByTypeAndBeat
        metrics = store.metrics
        lineNoteCount = store.currentLine?.notes.count ?? 0
        lineEventCount = store.currentLine?.eventCount ?? 0
        bpm = store.chart.bpm(at: store.currentBeat)
        audioCurrentTime = store.audio.currentTime
        audioDuration = store.audio.duration
        self.movePreview = movePreview
    }
}

private struct TimelineRenderer {
    static let headerHeight: CGFloat = 43
    static let playheadInset: CGFloat = 24

    let snapshot: TimelineRenderSnapshot
    let noteRegionRatio: CGFloat
    private let textures = NoteTextureAtlas.shared

    func draw(context: GraphicsContext, size: CGSize) {
        let split = size.width * noteRegionRatio
        drawGrid(context: context, size: size, split: split)
        if split > 1 {
            drawNotes(context: context, size: size, split: split)
        }
        if size.width - split > 1 {
            drawEvents(context: context, size: size, split: split)
        }
        drawPlayhead(context: context, size: size)
        drawStatus(context: context, size: size)
    }

    static func yPosition(
        beat: Double,
        currentBeat: Double,
        visibleBeats: Double,
        height: CGFloat
    ) -> CGFloat {
        let usableHeight = max(1, height - headerHeight - playheadInset)
        return height - playheadInset
            - CGFloat((beat - currentBeat) / max(0.001, visibleBeats)) * usableHeight
    }

    private func drawGrid(
        context: GraphicsContext,
        size: CGSize,
        split: CGFloat
    ) {
        if size.width - split > 1 {
            context.fill(
                Path(
                    roundedRect: CGRect(
                        x: split,
                        y: 0,
                        width: size.width - split,
                        height: Self.headerHeight
                    ),
                    cornerRadius: 0
                ),
                with: .color(Color(red: 0.055, green: 0.085, blue: 0.10).opacity(0.95))
            )
        }

        let subdivision = max(1, snapshot.settings.horizontalSubdivision)
        let first = Int(floor(snapshot.currentBeat * Double(subdivision)))
        let last = Int(ceil(
            (snapshot.currentBeat + snapshot.visibleBeats + 0.25) * Double(subdivision)
        ))
        if first <= last {
            for tick in first ... last {
                let beat = Double(tick) / Double(subdivision)
                let y = Self.yPosition(
                    beat: beat,
                    currentBeat: snapshot.currentBeat,
                    visibleBeats: snapshot.visibleBeats,
                    height: size.height
                )
                guard y >= Self.headerHeight, y <= size.height else { continue }
                let whole = tick % subdivision == 0
                var path = Path()
                path.move(to: CGPoint(x: 0, y: y))
                path.addLine(to: CGPoint(x: size.width, y: y))
                context.stroke(
                    path,
                    with: .color(
                        whole
                            ? Color(red: 0.75, green: 0.20, blue: 0.28).opacity(0.55)
                            : .white.opacity(0.10)
                    ),
                    lineWidth: whole ? 1 : 0.65
                )
                if whole {
                    context.draw(
                        Text("\(Int(beat))")
                            .font(.system(size: 12, weight: .medium).monospacedDigit())
                            .foregroundColor(.white.opacity(0.72)),
                        at: CGPoint(x: 8, y: y - 4),
                        anchor: .bottomLeading
                    )
                }
            }
        }

        if split > 1, snapshot.settings.showVerticalGrid {
            let count = max(2, snapshot.settings.verticalGridLines)
            for index in 0 ..< count {
                let x = CGFloat(index) / CGFloat(count - 1) * split
                var path = Path()
                path.move(to: CGPoint(x: x, y: 0))
                path.addLine(to: CGPoint(x: x, y: size.height))
                let color = index == count / 2
                    ? Color(red: 83 / 255, green: 101 / 255, blue: 62 / 255)
                    : .white.opacity(0.13)
                context.stroke(path, with: .color(color), lineWidth: 0.7)
            }
        }

        var splitPath = Path()
        splitPath.move(to: CGPoint(x: split, y: 0))
        splitPath.addLine(to: CGPoint(x: split, y: size.height))
        context.stroke(splitPath, with: .color(.white.opacity(0.26)), lineWidth: 1.2)

        if size.width - split > 1 {
            let laneWidth = (size.width - split) / CGFloat(EventType.allCases.count)
            for (index, type) in EventType.allCases.enumerated() {
                let x = split + CGFloat(index) * laneWidth
                var lane = Path()
                lane.move(to: CGPoint(x: x, y: 0))
                lane.addLine(to: CGPoint(x: x, y: size.height))
                context.stroke(lane, with: .color(.white.opacity(0.16)), lineWidth: 0.8)
                context.draw(
                    Text(type.title)
                        .font(.system(size: 12, weight: .medium))
                        .foregroundColor(
                            snapshot.activeTool == .event
                                && snapshot.activeEventType == type
                                ? Color(red: 0.20, green: 0.78, blue: 0.68)
                                : .white.opacity(0.78)
                        ),
                    at: CGPoint(x: x + laneWidth / 2, y: Self.headerHeight / 2),
                    anchor: .center
                )
            }
        }
    }

    private func drawNotes(
        context: GraphicsContext,
        size: CGSize,
        split: CGFloat
    ) {
        guard let line = snapshot.currentLine else { return }
        for note in line.notes {
            let selected = snapshot.selectedNoteIDs.contains(note.id)
            let preview = selected ? snapshot.movePreview : nil
            let displayedStartBeat = note.startTime.doubleValue
                + (preview?.beatDelta ?? 0)
            let displayedEndBeat = note.endTime.doubleValue
                + (preview?.beatDelta ?? 0)
            let lowerBeat = snapshot.currentBeat - 0.75
            let upperBeat = snapshot.currentBeat + snapshot.visibleBeats + 0.75
            if note.type == .hold {
                if displayedEndBeat < lowerBeat || displayedStartBeat > upperBeat {
                    continue
                }
            } else if displayedStartBeat < lowerBeat || displayedStartBeat > upperBeat {
                continue
            }
            let y = Self.yPosition(
                beat: displayedStartBeat,
                currentBeat: snapshot.currentBeat,
                visibleBeats: snapshot.visibleBeats,
                height: size.height
            )
            let endY = note.type == .hold
                ? Self.yPosition(
                    beat: displayedEndBeat,
                    currentBeat: snapshot.currentBeat,
                    visibleBeats: snapshot.visibleBeats,
                    height: size.height
                )
                : y
            guard max(y, endY) >= Self.headerHeight - 100,
                  min(y, endY) <= size.height + 100
            else {
                continue
            }
            let displayedX = note.positionX + (preview?.xDelta ?? 0)
            let x = CGFloat((displayedX + 675) / 1_350) * split
            let multiHit = (
                snapshot.noteCountsByTypeAndBeat[
                    NoteMultiHintKey(type: note.type, beat: note.startTime)
                ] ?? 0
            ) > 1
            let naturalWidth = max(
                18,
                split * (CGFloat(989) / CGFloat(8_000))
            ) * textures.widthScale(multiHit: multiHit)
            let width = max(0.5, naturalWidth * CGFloat(max(0.1, note.size)))

            if note.type == .hold,
               let slices = textures.holdSlices(multiHit: multiHit)
            {
                var noteContext = context
                noteContext.opacity = min(1, max(0, Double(note.alpha) / 255))
                let rect = drawHold(
                    slices,
                    x: x,
                    startY: y,
                    endY: endY,
                    width: width,
                    naturalWidth: naturalWidth,
                    context: noteContext
                )
                if note.isFake {
                    context.stroke(
                        Path(roundedRect: rect, cornerRadius: 5),
                        with: .color(.white),
                        lineWidth: 1.5
                    )
                }
                if selected {
                    noteContext.stroke(
                        Path(roundedRect: rect.insetBy(dx: -3, dy: -3), cornerRadius: 4),
                        with: .color(.white),
                        lineWidth: 2
                    )
                }
            } else if let image = textures.image(for: note.type, multiHit: multiHit) {
                let aspect = max(0.01, image.size.width / image.size.height)
                let height = max(4, naturalWidth / aspect)
                let rect = CGRect(
                    x: x - width / 2,
                    y: y - height / 2,
                    width: width,
                    height: height
                )
                var noteContext = context
                noteContext.opacity = min(1, max(0, Double(note.alpha) / 255))
                noteContext.draw(Image(uiImage: image), in: rect)
                if note.isFake {
                    context.stroke(
                        Path(roundedRect: rect, cornerRadius: 5),
                        with: .color(.white),
                        lineWidth: 1.5
                    )
                }
                if selected {
                    noteContext.stroke(
                        Path(roundedRect: rect.insetBy(dx: -4, dy: -4), cornerRadius: 5),
                        with: .color(.white),
                        lineWidth: 2
                    )
                }
            }
        }
    }

    private func drawHold(
        _ slices: NoteTextureAtlas.HoldSlices,
        x: CGFloat,
        startY: CGFloat,
        endY: CGFloat,
        width: CGFloat,
        naturalWidth: CGFloat,
        context: GraphicsContext
    ) -> CGRect {
        let low = min(startY, endY)
        let high = max(startY, endY)
        let total = max(4, high - low)
        let tailHeight = naturalWidth * slices.tailHeight / slices.sourceWidth
        let headHeight = naturalWidth * slices.headHeight / slices.sourceWidth
        let headAtHigh = startY >= endY
        let tailRect = CGRect(
            x: x - width / 2,
            y: headAtHigh ? low : high - tailHeight,
            width: width,
            height: tailHeight
        )
        let headRect = CGRect(
            x: x - width / 2,
            y: headAtHigh ? high - headHeight : low,
            width: width,
            height: headHeight
        )
        context.draw(
            Image(uiImage: slices.body),
            in: CGRect(
                x: x - width / 2,
                y: low,
                width: width,
                height: total
            )
        )
        context.draw(Image(uiImage: slices.tail), in: tailRect)
        context.draw(Image(uiImage: slices.head), in: headRect)
        return CGRect(x: x - width / 2, y: low, width: width, height: total)
    }

    private func drawEvents(
        context: GraphicsContext,
        size: CGSize,
        split: CGFloat
    ) {
        guard let layer = snapshot.currentLayer else { return }
        let laneWidth = (size.width - split) / CGFloat(EventType.allCases.count)
        for (typeIndex, type) in EventType.allCases.enumerated() {
            for event in layer[type] {
                let startY = Self.yPosition(
                    beat: event.startTime.doubleValue,
                    currentBeat: snapshot.currentBeat,
                    visibleBeats: snapshot.visibleBeats,
                    height: size.height
                )
                let endY = Self.yPosition(
                    beat: event.endTime.doubleValue,
                    currentBeat: snapshot.currentBeat,
                    visibleBeats: snapshot.visibleBeats,
                    height: size.height
                )
                let low = min(startY, endY)
                let high = max(startY, endY)
                guard high >= Self.headerHeight, low <= size.height else { continue }
                let rect = CGRect(
                    x: split + CGFloat(typeIndex) * laneWidth + 3,
                    y: low,
                    width: max(4, laneWidth - 6),
                    height: max(16, high - low)
                )
                let selected = snapshot.selectedEventIDs.contains(event.id)
                context.fill(
                    Path(roundedRect: rect, cornerRadius: 3),
                    with: .color(
                        selected
                            ? Color(red: 0.22, green: 0.75, blue: 0.94).opacity(0.9)
                            : Color(red: 0.20, green: 0.56, blue: 0.78).opacity(0.74)
                    )
                )
                if selected {
                    context.stroke(
                        Path(roundedRect: rect, cornerRadius: 3),
                        with: .color(.white.opacity(0.9)),
                        lineWidth: 1.5
                    )
                }
                drawEventCurve(event, rect: rect, context: context)
                let labelRect = CGRect(
                    x: rect.minX + 1,
                    y: rect.minY + 1,
                    width: max(2, rect.width - 2),
                    height: min(16, rect.height - 2)
                )
                context.fill(
                    Path(roundedRect: labelRect, cornerRadius: 2),
                    with: .color(.black.opacity(0.55))
                )
                context.draw(
                    Text(event.start.formatted(.number.precision(.fractionLength(0 ... 2))))
                        .font(.system(size: 9, weight: .medium).monospacedDigit())
                        .foregroundColor(.white),
                    at: CGPoint(x: rect.midX, y: rect.minY + 9),
                    anchor: .center
                )
            }
        }
    }

    private func drawEventCurve(
        _ event: LineEvent,
        rect: CGRect,
        context: GraphicsContext
    ) {
        let curveRect = CGRect(
            x: rect.minX + 1,
            y: rect.minY + min(18, rect.height),
            width: max(1, rect.width - 2),
            height: max(0, rect.height - 19)
        )
        guard curveRect.height > 3 else { return }
        let range = event.type.displayRange
        let span = max(0.0001, range.upperBound - range.lowerBound)
        var path = Path()
        for index in 0 ... 24 {
            let progress = Double(index) / 24
            let beat = event.startTime.doubleValue
                + (event.endTime.doubleValue - event.startTime.doubleValue) * progress
            let value = event.value(at: beat)
            let normalized = min(1, max(0, (value - range.lowerBound) / span))
            let point = CGPoint(
                x: curveRect.minX + CGFloat(normalized) * curveRect.width,
                y: curveRect.maxY - CGFloat(progress) * curveRect.height
            )
            if index == 0 { path.move(to: point) } else { path.addLine(to: point) }
        }
        context.stroke(path, with: .color(.orange), lineWidth: 2)
    }

    private func drawPlayhead(context: GraphicsContext, size: CGSize) {
        let y = size.height - Self.playheadInset
        var playhead = Path()
        playhead.move(to: CGPoint(x: 0, y: y))
        playhead.addLine(to: CGPoint(x: size.width, y: y))
        context.stroke(
            playhead,
            with: .color(Color(red: 0.18, green: 0.95, blue: 0.73)),
            lineWidth: 2
        )
    }

    private func drawStatus(context: GraphicsContext, size: CGSize) {
        let audioCurrent = Self.time(snapshot.audioCurrentTime)
        let audioDuration = Self.time(snapshot.audioDuration)
        let value = "Beat \(snapshot.currentBeat.formatted(.number.precision(.fractionLength(3))))"
            + "  BPM \(snapshot.bpm.formatted(.number.precision(.fractionLength(2))))"
            + "  Audio \(audioCurrent)/\(audioDuration)"
            + "  Line notes \(snapshot.lineNoteCount)  Events \(snapshot.lineEventCount)"
            + "  Total notes \(snapshot.metrics.totalNotes)"
            + "  Total events \(snapshot.metrics.totalEvents)"
        context.draw(
            Text(value)
                .font(.system(size: 11, weight: .regular).monospacedDigit())
                .foregroundColor(.white.opacity(0.84)),
            at: CGPoint(x: 10, y: size.height - Self.playheadInset - 7),
            anchor: .bottomLeading
        )
    }

    private static func time(_ seconds: TimeInterval) -> String {
        guard seconds.isFinite, seconds >= 0 else { return "0:00.000" }
        let minutes = Int(seconds) / 60
        let remainder = seconds - Double(minutes * 60)
        return String(format: "%d:%06.3f", minutes, remainder)
    }
}
