import SwiftUI

struct InspectorView: View {
    @Bindable var store: EditorStore

    var body: some View {
        Group {
            if let note = store.selectedNote {
                NoteInspector(store: store, note: note)
                    .id(note.id)
            } else if let event = store.selectedEvent {
                EventInspector(store: store, event: event)
                    .id(event.id)
            } else if let event = store.selectedStoryboardEvent {
                StoryboardInspector(store: store, event: event)
                    .id(event.id)
            } else if let line = store.currentLine {
                LineInspector(store: store, line: line)
                    .id(line.id)
            } else {
                EmptyStateView(
                    title: "No judge line",
                    message: "Add a judge line to begin editing.",
                    symbol: "rectangle.slash"
                )
            }
        }
        .navigationTitle("Inspector")
        .navigationBarTitleDisplayMode(.inline)
    }
}

private struct NoteInspector: View {
    @Bindable var store: EditorStore
    @State private var draft: Note
    @State private var tint: String
    @State private var hitTint: String

    init(store: EditorStore, note: Note) {
        self.store = store
        _draft = State(initialValue: note)
        _tint = State(initialValue: Self.hex(note.tintRGB))
        _hitTint = State(initialValue: Self.hex(note.hitEffectTintRGB))
    }

    var body: some View {
        Form {
            Section("Note") {
                Picker("Type", selection: $draft.type) {
                    ForEach(NoteType.allCases) { type in
                        Text(type.title).tag(type)
                    }
                }
                .pickerStyle(.segmented)
                BeatValueField("Start", value: $draft.startTime)
                if draft.type == .hold {
                    BeatValueField("End", value: $draft.endTime)
                }
                TextField("X", value: $draft.positionX, format: .number)
                    .keyboardType(.numbersAndPunctuation)
                FieldHelp("Horizontal position on the judge line: −675 is left, 675 is right.")
                Picker("Side", selection: $draft.above) {
                    Text("Below").tag(0)
                    Text("Above").tag(1)
                }
                .pickerStyle(.segmented)
            }

            Section("Appearance") {
                Stepper("Alpha: \(draft.alpha)", value: $draft.alpha, in: 0 ... 255)
                FieldHelp("Opacity from 0 (invisible) to 255 (fully visible).")
                TextField("Size", value: $draft.size, format: .number)
                    .keyboardType(.decimalPad)
                FieldHelp("Horizontal note width multiplier. Height is not changed.")
                TextField("Speed", value: $draft.speed, format: .number)
                    .keyboardType(.numbersAndPunctuation)
                FieldHelp("Travel-speed multiplier relative to the judge line speed event.")
                TextField("Y offset", value: $draft.yOffset, format: .number)
                    .keyboardType(.numbersAndPunctuation)
                FieldHelp("Fixed distance from the judge line in chart coordinates.")
                TextField("Visible time", value: $draft.visibleTime, format: .number)
                    .keyboardType(.decimalPad)
                FieldHelp("How many seconds before its hit time the note becomes visible.")
                TextField("Judge area", value: $draft.judgeArea, format: .number)
                    .keyboardType(.decimalPad)
                FieldHelp("Judgement-area multiplier used by compatible players.")
                TextField("Note tint (#RRGGBB)", text: $tint)
                    .textInputAutocapitalization(.characters)
                FieldHelp("Multiplies the note texture color; leave empty for the original texture.")
                TextField("Hit tint (#RRGGBB)", text: $hitTint)
                    .textInputAutocapitalization(.characters)
                FieldHelp("Color applied to this note's hit effect; leave empty for the default.")
            }

            Section("Flags") {
                Toggle(
                    "Fake note",
                    isOn: Binding(
                        get: { draft.isFake },
                        set: { draft.isFake = $0 }
                    )
                )
            }

            Section {
                Button("Apply Changes") {
                    draft.tintRGB = Self.parseHex(tint)
                    draft.hitEffectTintRGB = Self.parseHex(hitTint)
                    if draft.type != .hold {
                        draft.endTime = draft.startTime
                    }
                    store.updateNote(draft)
                }
                .buttonStyle(.borderedProminent)

                Button("Delete Note", role: .destructive) {
                    store.deleteSelection()
                }
            }
        }
    }

    private static func hex(_ value: Int?) -> String {
        guard let value else { return "" }
        return String(format: "#%06X", value & 0xFFFFFF)
    }

    private static func parseHex(_ source: String) -> Int? {
        let value = source
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: "#", with: "")
        guard !value.isEmpty, value.count == 6 else { return nil }
        return Int(value, radix: 16)
    }
}

private struct EventInspector: View {
    @Bindable var store: EditorStore
    @State private var draft: LineEvent

    init(store: EditorStore, event: LineEvent) {
        self.store = store
        _draft = State(initialValue: event)
    }

    var body: some View {
        Form {
            Section(draft.type.title) {
                BeatValueField("Start", value: $draft.startTime)
                BeatValueField("End", value: $draft.endTime)
                TextField("Start value", value: $draft.start, format: .number)
                    .keyboardType(.numbersAndPunctuation)
                TextField("End value", value: $draft.end, format: .number)
                    .keyboardType(.numbersAndPunctuation)
                FieldHelp(eventValueExplanation)
                Stepper("Link group: \(draft.linkGroup)", value: $draft.linkGroup)
                FieldHelp("Events with the same non-zero group can be edited as a linked set.")
            }

            if draft.type != .speed {
                Section("Easing") {
                    Picker("Type", selection: $draft.easingType) {
                        ForEach(Easing.minimumType ... Easing.maximumType, id: \.self) {
                            Text(Easing.title(for: $0)).tag($0)
                        }
                    }
                    TextField("Window left", value: $draft.easingLeft, format: .number)
                        .keyboardType(.decimalPad)
                    TextField("Window right", value: $draft.easingRight, format: .number)
                        .keyboardType(.decimalPad)
                    FieldHelp("Left/right select the normalized portion of the curve, from 0 to 1.")
                    Toggle("Cubic Bézier", isOn: $draft.usesBezier)
                    if draft.usesBezier {
                        ForEach(0 ..< 4, id: \.self) { index in
                            TextField(
                                "Point \(index + 1)",
                                value: bezierBinding(index),
                                format: .number
                            )
                            .keyboardType(.numbersAndPunctuation)
                        }
                    }
                    EasingPreviewView(
                        type: draft.easingType,
                        left: draft.easingLeft,
                        right: draft.easingRight,
                        usesBezier: draft.usesBezier,
                        bezierPoints: draft.paddedBezierPoints
                    )
                }
            }

            Section {
                Button("Apply Changes") {
                    store.updateEvent(draft)
                }
                .buttonStyle(.borderedProminent)
                Button("Split at Playhead") {
                    store.splitSelectedEvent(
                        at: .fromDouble(
                            store.currentBeat,
                            division: store.settings.value.horizontalSubdivision
                        )
                    )
                }
                Button("Match Previous End Value") {
                    store.glueSelectedEvent()
                }
                Button("Delete Event", role: .destructive) {
                    store.deleteSelection()
                }
            }
        }
    }

    private func bezierBinding(_ index: Int) -> Binding<Double> {
        Binding(
            get: {
                draft.paddedBezierPoints[index]
            },
            set: { value in
                while draft.bezierPoints.count < 4 {
                    draft.bezierPoints.append(0)
                }
                draft.bezierPoints[index] = value
            }
        )
    }

    private var eventValueExplanation: String {
        switch draft.type {
        case .moveX:
            "Start/end horizontal position in chart coordinates (−675…675)."
        case .moveY:
            "Start/end vertical position in chart coordinates (−450…450)."
        case .rotate:
            "Start/end judge-line rotation in degrees."
        case .alpha:
            "Start/end opacity from 0 to 255."
        case .speed:
            "Start/end scroll-speed multiplier. Speed events are integrated over time."
        }
    }
}

private struct StoryboardInspector: View {
    @Bindable var store: EditorStore
    @State private var draft: StoryboardEvent
    @State private var startText = ""
    @State private var endText = ""

    init(store: EditorStore, event: StoryboardEvent) {
        self.store = store
        _draft = State(initialValue: event)
        switch event.value {
        case let .numeric(start, end):
            _startText = State(initialValue: start.formatted())
            _endText = State(initialValue: end.formatted())
        case let .color(start, end):
            _startText = State(initialValue: String(format: "#%06X", start))
            _endText = State(initialValue: String(format: "#%06X", end))
        case let .text(start, end):
            _startText = State(initialValue: start)
            _endText = State(initialValue: end)
        }
    }

    var body: some View {
        Form {
            Section(draft.type.title) {
                BeatValueField("Start", value: $draft.startTime)
                BeatValueField("End", value: $draft.endTime)
                TextField("Start value", text: $startText)
                TextField("End value", text: $endText)
                FieldHelp(storyboardValueExplanation)
            }
            Section("Easing") {
                Picker("Type", selection: $draft.easingType) {
                    ForEach(Easing.minimumType ... Easing.maximumType, id: \.self) {
                        Text(Easing.title(for: $0)).tag($0)
                    }
                }
                TextField("Window left", value: $draft.easingLeft, format: .number)
                    .keyboardType(.decimalPad)
                TextField("Window right", value: $draft.easingRight, format: .number)
                    .keyboardType(.decimalPad)
                Stepper("Link group: \(draft.linkGroup)", value: $draft.linkGroup)
                Toggle("Cubic Bézier", isOn: $draft.usesBezier)
                if draft.usesBezier {
                    ForEach(0 ..< 4, id: \.self) { index in
                        TextField(
                            "Point \(index + 1)",
                            value: storyboardBezierBinding(index),
                            format: .number
                        )
                        .keyboardType(.numbersAndPunctuation)
                    }
                }
                EasingPreviewView(
                    type: draft.easingType,
                    left: draft.easingLeft,
                    right: draft.easingRight,
                    usesBezier: draft.usesBezier,
                    bezierPoints: draft.paddedBezierPoints
                )
            }
            Section {
                Button("Apply Changes") {
                    updateValue()
                    store.updateStoryboardEvent(draft)
                }
                .buttonStyle(.borderedProminent)
                Button("Delete Event", role: .destructive) {
                    store.deleteSelection()
                }
            }
        }
    }

    private func updateValue() {
        switch draft.type {
        case .color:
            let first = parseHex(startText) ?? 0xFFFFFF
            draft.value = .color(startRGB: first, endRGB: parseHex(endText) ?? first)
        case .text:
            draft.value = .text(start: startText, end: endText)
        default:
            let first = Double(startText) ?? 0
            draft.value = .numeric(start: first, end: Double(endText) ?? first)
        }
    }

    private func parseHex(_ source: String) -> Int? {
        Int(source.replacingOccurrences(of: "#", with: ""), radix: 16)
    }

    private func storyboardBezierBinding(_ index: Int) -> Binding<Double> {
        Binding(
            get: { draft.paddedBezierPoints[index] },
            set: { value in
                while draft.bezierPoints.count < 4 {
                    draft.bezierPoints.append(0)
                }
                draft.bezierPoints[index] = value
            }
        )
    }

    private var storyboardValueExplanation: String {
        switch draft.type {
        case .scaleX:
            "Horizontal scale multiplier for the line texture or custom image."
        case .scaleY:
            "Vertical scale multiplier; also controls normal-line thickness."
        case .color:
            "Start/end RGB tint in #RRGGBB format."
        case .paint:
            "Brush radius. Positive values draw a persistent paint trail; zero clears it."
        case .text:
            "Text shown instead of the default judge line."
        case .incline:
            "Perspective incline in degrees applied to approaching notes."
        case .gif:
            "Normalized GIF playback position; after the event, animation continues from its end."
        }
    }
}

struct LineInspector: View {
    @Bindable var store: EditorStore
    @State private var line: JudgeLine
    @State private var name: String
    @State private var composer: String
    @State private var charter: String
    @State private var level: String
    @State private var offset: Int

    init(store: EditorStore, line: JudgeLine) {
        self.store = store
        _line = State(initialValue: line)
        _name = State(initialValue: store.chart.name)
        _composer = State(initialValue: store.chart.composer)
        _charter = State(initialValue: store.chart.charter)
        _level = State(initialValue: store.chart.level)
        _offset = State(initialValue: store.chart.offsetMilliseconds)
    }

    var body: some View {
        Form {
            Section("Chart Information") {
                TextField("Name", text: $name)
                TextField("Composer", text: $composer)
                TextField("Charter", text: $charter)
                TextField("Level", text: $level)
                TextField("Offset (ms)", value: $offset, format: .number)
                    .keyboardType(.numbersAndPunctuation)
                Button("Apply Chart Information") {
                    store.updateMetadata(
                        name: name,
                        composer: composer,
                        charter: charter,
                        level: level,
                        offsetMilliseconds: offset
                    )
                }
            }

            Section("Judge Line \(store.currentLineIndex)") {
                TextField("Name", text: $line.name)
                TextField("Texture", text: $line.texture)
                FieldHelp("Project-relative image path. Use line.png for the normal judge line.")
                Stepper("Group: \(line.group)", value: $line.group, in: 0 ... 255)
                TextField("BPM factor", value: $line.bpmFactor, format: .number)
                    .keyboardType(.decimalPad)
                FieldHelp("Multiplies beat timing for this judge line.")
                Picker("Parent line", selection: $line.father) {
                    Text("None").tag(-1)
                    ForEach(store.chart.judgeLines.indices, id: \.self) { index in
                        if index != store.currentLineIndex {
                            Text(
                                "\(index) · "
                                    + (store.chart.judgeLines[index].name.isEmpty
                                        ? "Line \(index)"
                                        : store.chart.judgeLines[index].name)
                            )
                            .tag(index)
                        }
                    }
                }
                FieldHelp("Parent line index, or −1 for no parent.")
                Stepper("Z order: \(line.zOrder)", value: $line.zOrder)
                Toggle("Rotate with parent", isOn: $line.rotateWithFather)
                Toggle("Cover notes behind line", isOn: $line.isCover)
                Picker("Attached UI", selection: $line.attachedUI) {
                    Text("None").tag(Optional<AttachedUIElement>.none)
                    ForEach(AttachedUIElement.allCases) {
                        Text($0.rawValue).tag(Optional($0))
                    }
                }
                Button("Apply Judge Line") {
                    store.updateLine(line)
                }
                .buttonStyle(.borderedProminent)
            }

            Section("Resources") {
                LabeledContent("Music", value: store.chart.song.isEmpty ? "None" : store.chart.song)
                LabeledContent(
                    "Illustration",
                    value: store.chart.background.isEmpty ? "None" : store.chart.background
                )
                LabeledContent("RPE version", value: "\(store.chart.rpeVersion)")
            }

            Section {
                Button("Duplicate Judge Line", systemImage: "plus.square.on.square") {
                    store.duplicateCurrentLine()
                }
                Button("Delete Judge Line", systemImage: "trash", role: .destructive) {
                    store.removeCurrentLine()
                }
            }
        }
    }
}

private struct FieldHelp: View {
    let text: String

    init(_ text: String) {
        self.text = text
    }

    var body: some View {
        Text(text)
            .font(.caption)
            .foregroundStyle(.secondary)
            .fixedSize(horizontal: false, vertical: true)
    }
}

struct BeatValueField: View {
    let label: String
    @Binding var value: BeatTime
    @State private var text: String
    @FocusState private var focused: Bool

    init(_ label: String, value: Binding<BeatTime>) {
        self.label = label
        _value = value
        _text = State(initialValue: value.wrappedValue.description)
    }

    var body: some View {
        TextField(label, text: $text)
            .fontDesign(.monospaced)
            .keyboardType(.numbersAndPunctuation)
            .focused($focused)
            .onSubmit(commit)
            .onChange(of: focused) { _, isFocused in
                if !isFocused { commit() }
            }
            .accessibilityHint("Use whole:numerator/denominator or a decimal beat.")
    }

    private func commit() {
        if let parsed = try? BeatTime.parseFlexible(text) {
            value = parsed
            text = parsed.description
        } else {
            text = value.description
        }
    }
}
