import SwiftUI

struct ToolsView: View {
    @Environment(\.dismiss) private var dismiss
    @Bindable var store: EditorStore
    @State private var showingSettings = false

    var body: some View {
        NavigationStack {
            List {
                Section("Generate") {
                    NavigationLink {
                        CurveNotesTool(store: store)
                    } label: {
                        Label("Curve Notes", systemImage: "point.topleft.down.to.point.bottomright.curvepath")
                    }
                    NavigationLink {
                        ComplexMoveTool(store: store)
                    } label: {
                        Label("Complex Move", systemImage: "scribble.variable")
                    }
                }

                Section("Batch") {
                    NavigationLink {
                        BatchNotesTool(store: store)
                    } label: {
                        Label("Batch Notes", systemImage: "music.note.list")
                    }
                    NavigationLink {
                        BatchEventsTool(store: store)
                    } label: {
                        Label("Batch Events", systemImage: "waveform.path.ecg.rectangle")
                    }
                    NavigationLink {
                        EventCloneTool(store: store)
                    } label: {
                        Label("Event Clone", systemImage: "square.on.square")
                    }
                }

                Section("Chart") {
                    NavigationLink {
                        BPMManagerView(store: store)
                    } label: {
                        Label("BPM List", systemImage: "metronome")
                    }
                    NavigationLink {
                        StoryboardManager(store: store)
                    } label: {
                        Label("Storyboard", systemImage: "wand.and.stars")
                    }
                    Button {
                        store.refreshDiagnostics()
                        dismiss()
                        Task {
                            try? await Task.sleep(for: .milliseconds(250))
                            store.diagnosticsPresented = true
                        }
                    } label: {
                        Label("Diagnostics", systemImage: "stethoscope")
                    }
                    .badge(store.diagnostics.errorCount + store.diagnostics.warningCount)
                    Button {
                        showingSettings = true
                    } label: {
                        Label("Editor Settings", systemImage: "gearshape")
                    }
                }

                Section("Selection") {
                    Button("Mirror Selected Notes", systemImage: "arrow.left.and.right") {
                        store.mirrorSelectedNotes()
                    }
                    .disabled(store.selectedNoteIDs.isEmpty)
                    Button("Flip Selected Note Sides", systemImage: "arrow.up.and.down") {
                        store.flipSelectedNoteSides()
                    }
                    .disabled(store.selectedNoteIDs.isEmpty)
                    Button("Paste Mirrored", systemImage: "doc.on.clipboard") {
                        store.paste(at: store.currentBeat, mirrored: true)
                    }
                    .disabled(store.clipboard.isEmpty)
                }
            }
            .navigationTitle("Tools")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
            .sheet(isPresented: $showingSettings) {
                SettingsView(store: store.settings)
            }
        }
    }
}

struct EventCloneTool: View {
    @Bindable var store: EditorStore
    @State private var lineSequence: String
    @State private var timeIncrement = "0:0/1"
    @State private var xProfile = BatchValueProfile()
    @State private var yProfile = BatchValueProfile()
    @State private var rotateProfile = BatchValueProfile()
    @State private var alphaProfile = BatchValueProfile()
    @State private var xSequence = "1"
    @State private var ySequence = "1"
    @State private var rotateSequence = "1"
    @State private var alphaSequence = "1"
    @State private var keepSource = false

    init(store: EditorStore) {
        self.store = store
        _lineSequence = State(initialValue: String(store.currentLineIndex))
    }

    var body: some View {
        Form {
            Section {
                LabeledContent("Selected events", value: "\(store.selectedEventIDs.count)")
                TextField("Target line sequence", text: $lineSequence)
                    .keyboardType(.numbersAndPunctuation)
                TextField("Time increment", text: $timeIncrement)
                    .fontDesign(.monospaced)
                Toggle("Keep source events", isOn: $keepSource)
                Text(
                    "Line indices may be separated by spaces or commas. "
                        + "Time accepts a decimal or whole:numerator/denominator."
                )
                .font(.footnote)
                .foregroundStyle(.secondary)
            }

            EventCloneProfileEditor(
                title: "Move X U.L.E.D.",
                profile: $xProfile,
                sequence: $xSequence
            )
            EventCloneProfileEditor(
                title: "Move Y U.L.E.D.",
                profile: $yProfile,
                sequence: $ySequence
            )
            EventCloneProfileEditor(
                title: "Rotate U.L.E.D.",
                profile: $rotateProfile,
                sequence: $rotateSequence
            )
            EventCloneProfileEditor(
                title: "Alpha U.L.E.D.",
                profile: $alphaProfile,
                sequence: $alphaSequence
            )

            Section {
                Button("Clone") {
                    apply()
                }
                .buttonStyle(.borderedProminent)
                .disabled(store.selectedEventIDs.isEmpty)
            }
        }
        .navigationTitle("Event Clone")
    }

    private func apply() {
        do {
            var x = xProfile
            var y = yProfile
            var rotate = rotateProfile
            var alpha = alphaProfile
            x.periodicSequence = try BatchValueProfile.parseSequence(xSequence)
            y.periodicSequence = try BatchValueProfile.parseSequence(ySequence)
            rotate.periodicSequence = try BatchValueProfile.parseSequence(rotateSequence)
            alpha.periodicSequence = try BatchValueProfile.parseSequence(alphaSequence)
            let seed = Int64(
                (Date().timeIntervalSinceReferenceDate * 1_000_000)
                    .truncatingRemainder(dividingBy: Double(Int64.max))
            )
            x.randomSeed = seed &+ 1
            y.randomSeed = seed &+ 2
            rotate.randomSeed = seed &+ 3
            alpha.randomSeed = seed &+ 4
            store.cloneSelectedEvents(
                using: EventCloneSpec(
                    lineSequence: try EventCloneSpec.parseLineSequence(lineSequence),
                    timeIncrement: try BeatTime.parseFlexible(timeIncrement),
                    xProfile: x,
                    yProfile: y,
                    rotateProfile: rotate,
                    alphaProfile: alpha,
                    keepSource: keepSource
                )
            )
        } catch {
            store.presentedError = error.localizedDescription
        }
    }
}

private struct EventCloneProfileEditor: View {
    let title: String
    @Binding var profile: BatchValueProfile
    @Binding var sequence: String

    var body: some View {
        Section(title) {
            TextField("Lower bound", value: $profile.lowerBound, format: .number)
                .keyboardType(.numbersAndPunctuation)
            TextField("Upper bound", value: $profile.upperBound, format: .number)
                .keyboardType(.numbersAndPunctuation)
            Picker("Easing", selection: $profile.easingType) {
                ForEach(Easing.minimumType ... Easing.maximumType, id: \.self) {
                    Text(Easing.title(for: $0)).tag($0)
                }
            }
            EasingPreviewView(type: profile.easingType)
            TextField("Periodic sequence", text: $sequence)
                .fontDesign(.monospaced)
            TextField("Random disturbance", value: $profile.disturbance, format: .number)
                .keyboardType(.numbersAndPunctuation)
        }
    }
}

struct CurveNotesTool: View {
    @Bindable var store: EditorStore
    @State private var density = 1.0
    @State private var subdivision = 4
    @State private var noteType = NoteType.tap
    @State private var easing = 1

    var body: some View {
        Form {
            Section {
                LabeledContent("Selected endpoints", value: "\(selectedNotes.count) / 2")
                Text(
                    "Select exactly two notes on the current judge line. "
                        + "Generated notes are placed strictly between them."
                )
                .font(.footnote)
                .foregroundStyle(.secondary)
            }
            Section("Generation") {
                TextField("Density", value: $density, format: .number)
                    .keyboardType(.decimalPad)
                Stepper("Subdivision: \(subdivision)", value: $subdivision, in: 1 ... 64)
                Picker("Note type", selection: $noteType) {
                    ForEach(NoteType.allCases) { Text($0.title).tag($0) }
                }
                Picker("Easing", selection: $easing) {
                    ForEach(Easing.minimumType ... Easing.maximumType, id: \.self) {
                        Text(Easing.title(for: $0)).tag($0)
                    }
                }
                EasingPreviewView(type: easing)
            }
            Section {
                Button("Generate Curve Notes") {
                    guard selectedNotes.count == 2 else { return }
                    store.generateCurveNotes(
                        startID: selectedNotes[0].id,
                        endID: selectedNotes[1].id,
                        density: density,
                        subdivision: subdivision,
                        type: noteType,
                        easing: easing
                    )
                }
                .buttonStyle(.borderedProminent)
                .disabled(selectedNotes.count != 2 || density <= 0)
            }
        }
        .navigationTitle("Curve Notes")
    }

    private var selectedNotes: [Note] {
        (store.currentLine?.notes.filter { store.selectedNoteIDs.contains($0.id) } ?? [])
            .sorted { $0.startTime < $1.startTime }
    }
}

struct ComplexMoveTool: View {
    @Bindable var store: EditorStore
    @State private var spec = ComplexMoveSpec()

    var body: some View {
        Form {
            Section("Time") {
                BeatValueField("Start", value: $spec.startTime)
                BeatValueField("End", value: $spec.endTime)
                TextField("Segments per beat", value: $spec.density, format: .number)
                    .keyboardType(.decimalPad)
            }
            Section("Path Expressions") {
                TextField("X(t)", text: $spec.xExpression, axis: .vertical)
                    .fontDesign(.monospaced)
                TextField("Y(t)", text: $spec.yExpression, axis: .vertical)
                    .fontDesign(.monospaced)
                Text("Functions include sin, cos, pow, min, max and clamp. t runs from 0 to 1.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            Section("X Time Easing") {
                easingFields($spec.xTimeEasing)
            }
            Section("Y Time Easing") {
                easingFields($spec.yTimeEasing)
            }
            Section {
                Button("Generate Move Events") {
                    do {
                        spec.xTimeEasing = try ComplexMoveTimeEasing(
                            type: spec.xTimeEasing.type,
                            left: spec.xTimeEasing.left,
                            right: spec.xTimeEasing.right
                        )
                        spec.yTimeEasing = try ComplexMoveTimeEasing(
                            type: spec.yTimeEasing.type,
                            left: spec.yTimeEasing.left,
                            right: spec.yTimeEasing.right
                        )
                        store.generateComplexMove(spec)
                    } catch {
                        store.presentedError = error.localizedDescription
                    }
                }
                .buttonStyle(.borderedProminent)
            }
        }
        .navigationTitle("Complex Move")
        .onAppear {
            spec.startTime = .fromDouble(
                store.currentBeat,
                division: store.settings.value.horizontalSubdivision
            )
            spec.endTime = .fromDouble(
                store.currentBeat + 4,
                division: store.settings.value.horizontalSubdivision
            )
        }
    }

    @ViewBuilder
    private func easingFields(_ easing: Binding<ComplexMoveTimeEasing>) -> some View {
        Picker("Type", selection: easing.type) {
            ForEach(Easing.minimumType ... Easing.maximumType, id: \.self) {
                Text(Easing.title(for: $0)).tag($0)
            }
        }
        TextField("Window left", value: easing.left, format: .number)
            .keyboardType(.decimalPad)
        TextField("Window right", value: easing.right, format: .number)
            .keyboardType(.decimalPad)
        EasingPreviewView(
            type: easing.wrappedValue.type,
            left: easing.wrappedValue.left,
            right: easing.wrappedValue.right
        )
    }
}

private struct BatchNotesTool: View {
    @Bindable var store: EditorStore
    @State private var field = NoteBatchField.x
    @State private var mode = BatchMode.to
    @State private var profile = BatchValueProfile()
    @State private var sequence = "1"

    var body: some View {
        BatchEditorForm(
            selectionCount: store.selectedNoteIDs.count,
            mode: $mode,
            profile: $profile,
            sequence: $sequence
        ) {
            Picker("Field", selection: $field) {
                ForEach(NoteBatchField.allCases) {
                    Text($0.rawValue).tag($0)
                }
            }
        } apply: {
            do {
                profile.periodicSequence = try BatchValueProfile.parseSequence(sequence)
                store.batchNotes(field: field, profile: profile, mode: mode)
            } catch {
                store.presentedError = error.localizedDescription
            }
        }
        .navigationTitle("Batch Notes")
    }
}

private enum BatchEditTarget: String, CaseIterable, Identifiable {
    case notes
    case events

    var id: String { rawValue }
    var title: String { rawValue.capitalized }
}

struct BatchEditTool: View {
    @Bindable var store: EditorStore
    @State private var target = BatchEditTarget.notes
    @State private var noteField = NoteBatchField.x
    @State private var eventField = EventBatchField.startValue
    @State private var eventType = EventType.moveX
    @State private var mode = BatchMode.to
    @State private var profile = BatchValueProfile()
    @State private var sequence = "1"

    var body: some View {
        BatchEditorForm(
            selectionCount: selectionCount,
            mode: $mode,
            profile: $profile,
            sequence: $sequence
        ) {
            if availableTargets.count > 1 {
                Picker("Target", selection: $target) {
                    ForEach(availableTargets) {
                        Text("\($0.title) (\(count(for: $0)))").tag($0)
                    }
                }
            }
            if target == .notes {
                Picker("Field", selection: $noteField) {
                    ForEach(NoteBatchField.allCases) {
                        Text($0.rawValue).tag($0)
                    }
                }
            } else {
                Picker("Event type", selection: $eventType) {
                    ForEach(EventType.allCases) {
                        Text($0.title).tag($0)
                    }
                }
                Picker("Field", selection: $eventField) {
                    ForEach(EventBatchField.allCases) {
                        Text($0.rawValue).tag($0)
                    }
                }
            }
        } apply: {
            do {
                profile.periodicSequence = try BatchValueProfile.parseSequence(sequence)
                if target == .notes {
                    store.batchNotes(field: noteField, profile: profile, mode: mode)
                } else {
                    store.batchEvents(
                        field: eventField,
                        profile: profile,
                        mode: mode,
                        eventType: eventType
                    )
                }
            } catch {
                store.presentedError = error.localizedDescription
            }
        }
        .navigationTitle("Batch edit")
        .onAppear {
            if store.selectedNoteIDs.isEmpty, !store.selectedEventIDs.isEmpty {
                target = .events
                if let selectedType = store.selectedEvent?.type {
                    eventType = selectedType
                }
            }
        }
    }

    private var availableTargets: [BatchEditTarget] {
        BatchEditTarget.allCases.filter { count(for: $0) > 0 }
    }

    private var selectionCount: Int {
        count(for: target)
    }

    private func count(for target: BatchEditTarget) -> Int {
        switch target {
        case .notes: store.selectedNoteIDs.count
        case .events: store.selectedEventIDs.count
        }
    }
}

private struct BatchEventsTool: View {
    @Bindable var store: EditorStore
    @State private var field = EventBatchField.startValue
    @State private var mode = BatchMode.to
    @State private var profile = BatchValueProfile()
    @State private var sequence = "1"

    var body: some View {
        BatchEditorForm(
            selectionCount: store.selectedEventIDs.count,
            mode: $mode,
            profile: $profile,
            sequence: $sequence
        ) {
            Picker("Field", selection: $field) {
                ForEach(EventBatchField.allCases) {
                    Text($0.rawValue).tag($0)
                }
            }
        } apply: {
            do {
                profile.periodicSequence = try BatchValueProfile.parseSequence(sequence)
                store.batchEvents(field: field, profile: profile, mode: mode)
            } catch {
                store.presentedError = error.localizedDescription
            }
        }
        .navigationTitle("Batch Events")
    }
}

private struct BatchEditorForm<FieldContent: View>: View {
    let selectionCount: Int
    @Binding var mode: BatchMode
    @Binding var profile: BatchValueProfile
    @Binding var sequence: String
    @ViewBuilder var fieldContent: () -> FieldContent
    let apply: () -> Void

    var body: some View {
        Form {
            Section {
                LabeledContent("Selected items", value: "\(selectionCount)")
                fieldContent()
                Picker("Operation", selection: $mode) {
                    ForEach(BatchMode.allCases) {
                        Text($0.rawValue).tag($0)
                    }
                }
            }
            Section("Value Profile") {
                TextField("Lower bound", value: $profile.lowerBound, format: .number)
                    .keyboardType(.numbersAndPunctuation)
                TextField("Upper bound", value: $profile.upperBound, format: .number)
                    .keyboardType(.numbersAndPunctuation)
                Picker("Easing", selection: $profile.easingType) {
                    ForEach(Easing.minimumType ... Easing.maximumType, id: \.self) {
                        Text(Easing.title(for: $0)).tag($0)
                    }
                }
                EasingPreviewView(type: profile.easingType)
                TextField("Periodic sequence", text: $sequence)
                    .fontDesign(.monospaced)
                TextField("Random disturbance", value: $profile.disturbance, format: .number)
                    .keyboardType(.numbersAndPunctuation)
                TextField("Random seed", value: $profile.randomSeed, format: .number)
                    .keyboardType(.numbersAndPunctuation)
            }
            Section {
                Button("Apply Batch Edit", action: apply)
                    .buttonStyle(.borderedProminent)
                    .disabled(selectionCount == 0)
            }
        }
    }
}

struct BPMManagerView: View {
    @Bindable var store: EditorStore

    var body: some View {
        List {
            ForEach(store.chart.bpmChanges) { change in
                BPMEditorRow(
                    store: store,
                    change: change,
                    canDelete: store.chart.bpmChanges.count > 1
                )
                    .id(change.id)
            }
            .onDelete { offsets in
                let ids = offsets.compactMap { index in
                    store.chart.bpmChanges.indices.contains(index)
                        ? store.chart.bpmChanges[index].id
                        : nil
                }
                ids.forEach(store.removeBPM)
            }
        }
        .navigationTitle("BPM List")
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button {
                    store.addBPM(
                        at: .fromDouble(
                            store.currentBeat,
                            division: store.settings.value.horizontalSubdivision
                        ),
                        bpm: store.chart.bpm(at: store.currentBeat)
                    )
                } label: {
                    Label("Add BPM", systemImage: "plus")
                }
            }
        }
    }
}

private struct BPMEditorRow: View {
    @Bindable var store: EditorStore
    @State private var draft: BPMChange
    let change: BPMChange
    let canDelete: Bool

    init(store: EditorStore, change: BPMChange, canDelete: Bool) {
        self.store = store
        _draft = State(initialValue: change)
        self.change = change
        self.canDelete = canDelete
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            BeatValueField("Beat", value: $draft.startTime)
            HStack {
                TextField("BPM", value: $draft.bpm, format: .number)
                    .keyboardType(.decimalPad)
                Button("Apply") {
                    store.updateBPM(draft)
                }
                .buttonStyle(.borderedProminent)
                Button(role: .destructive) {
                    store.removeBPM(draft.id)
                } label: {
                    Label("Delete", systemImage: "trash")
                }
                .buttonStyle(.bordered)
                .disabled(!canDelete)
            }
            Text("Beat is the exact change point; BPM must be greater than zero.")
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        .padding(.vertical, 5)
        .onChange(of: change) { _, updated in
            draft = updated
        }
    }
}

struct DiagnosticsView: View {
    @Environment(\.dismiss) private var dismiss
    @Bindable var store: EditorStore

    var body: some View {
        NavigationStack {
            List {
                Section {
                    HStack {
                        diagnosticCount(
                            store.diagnostics.errorCount,
                            title: "Errors",
                            color: .red
                        )
                        diagnosticCount(
                            store.diagnostics.warningCount,
                            title: "Warnings",
                            color: .orange
                        )
                        diagnosticCount(
                            store.diagnostics.cautionCount,
                            title: "Cautions",
                            color: .yellow
                        )
                    }
                }

                if store.diagnostics.items.isEmpty {
                    ContentUnavailableView(
                        "No issues",
                        systemImage: "checkmark.seal",
                        description: Text("The chart passed all built-in diagnostic checks.")
                    )
                } else {
                    Section("Issues") {
                        ForEach(store.diagnostics.items) { item in
                            Button {
                                navigate(to: item)
                            } label: {
                                HStack(alignment: .top, spacing: 12) {
                                    Image(systemName: severitySymbol(item.severity))
                                        .foregroundStyle(severityColor(item.severity))
                                    VStack(alignment: .leading, spacing: 4) {
                                        Text(item.code.title)
                                            .foregroundStyle(.primary)
                                        Text(item.message)
                                            .font(.caption)
                                            .foregroundStyle(.secondary)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            .navigationTitle("Diagnostics")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("Refresh") { store.refreshDiagnostics() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
    }

    private func diagnosticCount(_ count: Int, title: String, color: Color) -> some View {
        VStack(spacing: 3) {
            Text("\(count)")
                .font(.title2.bold())
                .foregroundStyle(color)
            Text(title)
                .font(.caption2)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity)
    }

    private func navigate(to item: ChartDiagnostic) {
        if item.lineIndex >= 0 {
            store.selectLine(item.lineIndex)
        }
        if item.layerIndex >= 0 {
            store.selectLayer(item.layerIndex)
        }
        store.setBeat(item.beat.doubleValue)
        if let noteID = item.noteID {
            store.selectedNoteIDs = [noteID]
            store.mode = .notes
        } else if let eventID = item.eventID {
            store.selectedEventIDs = [eventID]
            store.mode = .events
        }
        dismiss()
    }

    private func severitySymbol(_ severity: DiagnosticSeverity) -> String {
        switch severity {
        case .error: "xmark.octagon.fill"
        case .warning: "exclamationmark.triangle.fill"
        case .caution: "exclamationmark.circle.fill"
        }
    }

    private func severityColor(_ severity: DiagnosticSeverity) -> Color {
        switch severity {
        case .error: .red
        case .warning: .orange
        case .caution: .yellow
        }
    }
}
