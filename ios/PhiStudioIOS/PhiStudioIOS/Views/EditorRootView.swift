import SwiftUI
import UniformTypeIdentifiers

private enum EditorSheet: String, Identifiable {
    case inspector
    case tools
    case metadata
    case bpm
    case lines
    case storyboard
    case curveNotes
    case complexMove
    case batchEdit
    case eventClone
    case settings

    var id: String { rawValue }
}

struct EditorRootView: View {
    @Bindable var store: EditorStore
    @Bindable var appModel: AppModel
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass

    @State private var exportDocument = PackageArchiveDocument()
    @State private var exporting = false
    @State private var preparingExport = false
    @State private var importingPackage = false
    @State private var replacingAudio = false
    @State private var replacingIllustration = false
    @State private var showingMenu = false
    @State private var presentedSheet: EditorSheet?

    var body: some View {
        Group {
            if horizontalSizeClass == .regular {
                IPadEditorLayout(
                    store: store,
                    showingMenu: $showingMenu,
                    openSheet: { presentedSheet = $0 },
                    openProjects: {
                        Task { await appModel.closeEditor() }
                    },
                    importPackage: { importingPackage = true },
                    exportPackage: prepareExport,
                    replaceAudio: { replacingAudio = true },
                    replaceIllustration: { replacingIllustration = true }
                )
                .persistentSystemOverlays(.hidden)
                .ignoresSafeArea()
            } else {
                NavigationStack {
                    EditorWorkspace(store: store)
                        .toolbar { compactToolbar }
                }
            }
        }
        .tint(Color(red: 0.22, green: 0.79, blue: 0.68))
        .sheet(item: $presentedSheet) { sheet in
            EditorSheetHost(
                sheet: sheet,
                store: store,
                close: { presentedSheet = nil }
            )
        }
        .sheet(isPresented: $store.toolsPresented) {
            ToolsView(store: store)
        }
        .sheet(isPresented: $store.inspectorPresented) {
            NavigationStack {
                InspectorView(store: store)
                    .toolbar {
                        ToolbarItem(placement: .confirmationAction) {
                            Button("Done") { store.inspectorPresented = false }
                        }
                    }
            }
        }
        .sheet(isPresented: $store.diagnosticsPresented) {
            DiagnosticsView(store: store)
        }
        .sheet(isPresented: $importingPackage) {
            SystemDocumentPicker(isPresented: $importingPackage) { result in
                importSelection(result)
            }
        }
        .sheet(isPresented: $replacingAudio) {
            SystemDocumentPicker(isPresented: $replacingAudio) { result in
                replaceAsset(from: result, kind: .audio)
            }
        }
        .sheet(isPresented: $replacingIllustration) {
            SystemDocumentPicker(isPresented: $replacingIllustration) { result in
                replaceAsset(from: result, kind: .illustration)
            }
        }
        .fileExporter(
            isPresented: $exporting,
            document: exportDocument,
            contentType: .phiPackage,
            defaultFilename: safeExportName
        ) { result in
            if case let .failure(error) = result {
                store.presentedError = error.localizedDescription
            } else {
                store.statusMessage = "Exported package"
            }
        }
        .alert(
            "PhiStudio",
            isPresented: Binding(
                get: { store.presentedError != nil || appModel.presentedError != nil },
                set: {
                    if !$0 {
                        store.presentedError = nil
                        appModel.presentedError = nil
                    }
                }
            )
        ) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(store.presentedError ?? appModel.presentedError ?? "")
        }
    }

    @ToolbarContentBuilder
    private var compactToolbar: some ToolbarContent {
        ToolbarItem(placement: .topBarLeading) {
            Button {
                Task { await appModel.closeEditor() }
            } label: {
                Image(systemName: "chevron.backward")
            }
        }
        ToolbarItemGroup(placement: .topBarTrailing) {
            Button { store.undo() } label: {
                Image(systemName: "arrow.uturn.backward")
            }
            .disabled(!store.canUndo)
            Button { store.togglePlayback() } label: {
                Image(systemName: store.isPlaying ? "pause.fill" : "play.fill")
            }
            Menu {
                Button("Inspector", systemImage: "slider.horizontal.3") {
                    store.inspectorPresented = true
                }
                Button("Tools", systemImage: "hammer") {
                    store.toolsPresented = true
                }
                Button("Save", systemImage: "square.and.arrow.down") {
                    Task { await store.save() }
                }
                Button("Export PEZ", systemImage: "square.and.arrow.up") {
                    prepareExport()
                }
                Divider()
                Button("Replace Music", systemImage: "waveform") {
                    replacingAudio = true
                }
                Button("Replace Illustration", systemImage: "photo") {
                    replacingIllustration = true
                }
            } label: {
                Image(systemName: "ellipsis.circle")
            }
        }
    }

    private var safeExportName: String {
        let value = store.chart.name.replacingOccurrences(
            of: "[^A-Za-z0-9._ -]",
            with: "_",
            options: .regularExpression
        )
        return (value.isEmpty ? "chart" : value) + ".pez"
    }

    private func prepareExport() {
        guard !preparingExport else { return }
        preparingExport = true
        Task {
            if let data = await store.makeExportData() {
                exportDocument = PackageArchiveDocument(data: data)
                exporting = true
            }
            preparingExport = false
        }
    }

    private func importSelection(_ result: Result<[URL], Error>) {
        Task {
            do {
                guard let source = try result.get().first else { return }
                let staged = try await ImportedFileStager.stage(source, as: .chart)
                defer { staged.remove() }
                await appModel.importFile(staged.url)
            } catch {
                store.presentedError = error.localizedDescription
            }
        }
    }

    private func replaceAsset(
        from result: Result<[URL], Error>,
        kind: ProjectLibrary.AssetKind
    ) {
        Task {
            do {
                guard let source = try result.get().first else { return }
                let selectionKind: ImportedFileKind = kind == .audio
                    ? .audio
                    : .illustration
                let staged = try await ImportedFileStager.stage(source, as: selectionKind)
                defer { staged.remove() }
                await store.replaceAsset(staged.url, kind: kind)
            } catch {
                store.presentedError = error.localizedDescription
            }
        }
    }
}

private struct IPadEditorLayout: View {
    @Bindable var store: EditorStore
    @Binding var showingMenu: Bool
    let openSheet: (EditorSheet) -> Void
    let openProjects: () -> Void
    let importPackage: () -> Void
    let exportPackage: () -> Void
    let replaceAudio: () -> Void
    let replaceIllustration: () -> Void

    var body: some View {
        ZStack {
            Color(red: 0.025, green: 0.035, blue: 0.045)
            VStack(spacing: 0) {
                IPadTopBar(store: store, showMenu: { showingMenu = true })

                if store.mode == .preview {
                    PreviewCanvasView(store: store, showsStatus: false)
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                    IPadPreviewStatusBar(store: store)
                } else {
                    TimelineEditorView(store: store)
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                    IPadControlDock(store: store, openSheet: openSheet)
                }
            }

            if showingMenu {
                AndroidStyleMenu(
                    close: { showingMenu = false },
                    action: handleMenuAction
                )
                .transition(.opacity)
                .zIndex(20)
            }
        }
        .animation(.easeOut(duration: 0.14), value: showingMenu)
        .environment(\.colorScheme, .dark)
    }

    private func handleMenuAction(_ action: AndroidMenuAction) {
        showingMenu = false
        switch action {
        case .projects:
            openProjects()
        case .importPackage:
            importPackage()
        case .exportPackage:
            exportPackage()
        case .metadata:
            openSheet(.metadata)
        case .bpm:
            openSheet(.bpm)
        case .lines:
            openSheet(.lines)
        case .storyboard:
            openSheet(.storyboard)
        case .curveNotes:
            openSheet(.curveNotes)
        case .complexMove:
            openSheet(.complexMove)
        case .settings:
            openSheet(.settings)
        case .diagnostics:
            store.refreshDiagnostics()
            store.diagnosticsPresented = true
        case .replaceAudio:
            replaceAudio()
        case .replaceIllustration:
            replaceIllustration()
        }
    }
}

private struct IPadPreviewStatusBar: View {
    @Bindable var store: EditorStore

    var body: some View {
        Text(
            "Preview · beat "
                + store.currentBeat.formatted(
                    .number.precision(.fractionLength(3))
                )
                + " · BPM "
                + store.chart.bpm(at: store.currentBeat).formatted(
                    .number.precision(.fractionLength(2))
                )
                + " · \(store.metrics.totalNotes) notes"
                + " · \(store.metrics.totalEvents) events"
        )
        .font(.system(size: 11).monospacedDigit())
        .foregroundStyle(.white.opacity(0.78))
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
        .padding(.horizontal, 9)
        .frame(height: 28)
        .background(Color(red: 0.035, green: 0.055, blue: 0.070))
        .overlay(alignment: .top) {
            Rectangle().fill(.white.opacity(0.12)).frame(height: 1)
        }
    }
}

private struct IPadTopBar: View {
    @Bindable var store: EditorStore
    let showMenu: () -> Void

    var body: some View {
        GeometryReader { proxy in
            let controlsWidth = min(proxy.size.width * 0.52, 660)
            HStack(spacing: 0) {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 6) {
                        TopBarButton(store.isPlaying ? "Pause" : "Play") {
                            store.togglePlayback()
                        }
                        TopBarButton(String(format: "%.2fx", Double(store.audio.rate))) {
                            store.cyclePlaybackRate()
                        }
                        TopBarButton(store.mode == .preview ? "Editor" : "Preview") {
                            store.togglePreviewMode()
                        }
                        TopBarButton(store.isSaving ? "Saving" : "Save") {
                            Task { await store.save() }
                        }
                        .disabled(store.isSaving)
                        TopBarButton("Undo", enabled: store.canUndo) { store.undo() }
                        TopBarButton("Redo", enabled: store.canRedo) { store.redo() }
                        TopBarButton("Menu", action: showMenu)
                    }
                }
                .frame(width: controlsWidth, alignment: .leading)
                .padding(.leading, 8)

                VStack(alignment: .leading, spacing: 7) {
                    Text(
                        "\(store.chart.name) · L\(store.currentLineIndex)/"
                            + "\(max(0, store.chart.judgeLines.count - 1))"
                    )
                    .font(.system(size: 16, weight: .medium))
                    .lineLimit(1)
                    Text(
                        "BPM "
                            + store.chart.bpm(at: store.currentBeat).formatted(
                                .number.precision(.fractionLength(0 ... 2))
                            )
                            + " · offset \(effectiveOffset) ms"
                    )
                    .font(.system(size: 13))
                    .foregroundStyle(.white.opacity(0.72))
                }
                .frame(minWidth: 190, alignment: .leading)
                .padding(.horizontal, 18)

                VStack(alignment: .trailing, spacing: 6) {
                    Text("\(time(store.playbackPosition)) / \(time(store.audio.duration))")
                        .font(.system(size: 11).monospacedDigit())
                        .foregroundStyle(.white.opacity(0.75))
                    Slider(
                        value: Binding(
                            get: { store.playbackPosition },
                            set: { store.setPlaybackPosition($0) }
                        ),
                        in: 0 ... max(0.001, store.audio.duration),
                        onEditingChanged: { editing in
                            if editing { store.beginScrubbing() } else { store.endScrubbing() }
                        }
                    )
                    .tint(Color(red: 0.25, green: 0.82, blue: 0.70))
                    .disabled(!store.audio.isLoaded || store.audio.duration <= 0)
                }
                .padding(.trailing, 16)
            }
        }
        .frame(height: 82)
        .background(Color(red: 0.035, green: 0.055, blue: 0.075))
        .overlay(alignment: .bottom) {
            Rectangle().fill(.white.opacity(0.10)).frame(height: 1)
        }
    }

    private var effectiveOffset: Int64 {
        Int64(store.chart.offsetMilliseconds) + store.package.manifestOffsetMilliseconds
    }

    private func time(_ seconds: TimeInterval) -> String {
        guard seconds.isFinite, seconds >= 0 else { return "0:00.000" }
        let minutes = Int(seconds) / 60
        return String(format: "%d:%06.3f", minutes, seconds - Double(minutes * 60))
    }
}

private struct TopBarButton: View {
    let title: String
    let enabled: Bool
    let action: () -> Void

    init(_ title: String, enabled: Bool = true, action: @escaping () -> Void) {
        self.title = title
        self.enabled = enabled
        self.action = action
    }

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(.system(size: 14, weight: .medium))
                .foregroundStyle(.white.opacity(enabled ? 0.92 : 0.35))
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .frame(width: 88, height: 66)
        .background(Color(red: 0.13, green: 0.17, blue: 0.21))
    }
}

private struct IPadControlDock: View {
    @Bindable var store: EditorStore
    let openSheet: (EditorSheet) -> Void

    var body: some View {
        VStack(alignment: .center, spacing: 9) {
            ZStack {
                HStack(spacing: 8) {
                    ForEach(EditorControlPanel.allCases) { panel in
                        DockButton(
                            panel.title,
                            selected: store.controlPanel == panel
                        ) {
                            store.controlPanel = panel
                        }
                    }
                }
                Text(selectionSummary)
                    .font(.system(size: 12))
                    .foregroundStyle(.white.opacity(0.72))
                    .lineLimit(1)
                    .frame(maxWidth: .infinity, alignment: .trailing)
                    .padding(.trailing, 4)
            }
            .frame(maxWidth: .infinity, alignment: .center)

            GeometryReader { proxy in
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        switch store.controlPanel {
                        case .create:
                            createControls
                        case .edit:
                            editControls
                        case .arrange:
                            arrangeControls
                        }
                    }
                    .frame(minWidth: proxy.size.width, alignment: .center)
                }
            }
            .frame(height: 46)

            Text(hint)
                .font(.system(size: 11))
                .foregroundStyle(.white.opacity(0.64))
                .lineLimit(1)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 10)
        .frame(height: 160, alignment: .top)
        .background(Color(red: 0.035, green: 0.055, blue: 0.070))
        .overlay(alignment: .top) {
            Rectangle().fill(.white.opacity(0.12)).frame(height: 1)
        }
    }

    @ViewBuilder
    private var createControls: some View {
        ForEach(EditorTool.allCases) { tool in
            DockButton(tool.title, selected: store.activeTool == tool) {
                store.selectTool(tool)
            }
        }
    }

    @ViewBuilder
    private var editControls: some View {
        DockButton(
            store.rectangleSelectionEnabled ? "Box+ ✓" : "Box+ Off",
            selected: store.rectangleSelectionEnabled
        ) {
            store.toggleRectangleSelection()
        }
        DockButton(store.noteMoveMode.title, selected: store.noteMoveMode != .off) {
            store.cycleNoteMoveMode()
        }

        if store.selectedNoteIDs.count + store.selectedEventIDs.count > 1 {
            DockButton("Batch edit") { openSheet(.batchEdit) }
            DockButton("Event Clone", enabled: !store.selectedEventIDs.isEmpty) {
                openSheet(.eventClone)
            }
            multipleSelectionControls
        } else if !store.selectedNoteIDs.isEmpty {
            DockButton("Properties") { openSheet(.inspector) }
            deleteCopyCutControls
            DockButton("Mirror X") { store.mirrorSelectedNotes() }
            DockButton(store.selectedNote?.above == 1 ? "Side Up" : "Side Down") {
                store.flipSelectedNoteSides()
            }
            DockButton(store.selectedNote?.isFake == true ? "Fake ✓" : "Fake") {
                store.toggleSelectedNoteFake()
            }
            DockButton("Width −") { store.resizeSelectedNote(by: -0.1) }
            DockButton("Width +") { store.resizeSelectedNote(by: 0.1) }
        } else if !store.selectedEventIDs.isEmpty {
            DockButton("Properties") { openSheet(.inspector) }
            if store.selectedEventIDs.count == 1 {
                DockButton("Split") {
                    store.splitSelectedEvent(
                        at: .fromDouble(
                            store.currentBeat,
                            division: store.settings.value.horizontalSubdivision
                        )
                    )
                }
                DockButton("Pass") { store.passSelectedEvent() }
                DockButton("Rand") { store.randomizeSelectedEvent() }
                DockButton("Glue") { store.glueSelectedEvent() }
            }
            deleteCopyCutControls
            DockButton("Start −") {
                store.adjustSelectedEventValue(startValue: true, delta: -eventStep)
            }
            DockButton("Start +") {
                store.adjustSelectedEventValue(startValue: true, delta: eventStep)
            }
            DockButton("End −") {
                store.adjustSelectedEventValue(startValue: false, delta: -eventStep)
            }
            DockButton("End +") {
                store.adjustSelectedEventValue(startValue: false, delta: eventStep)
            }
            DockButton(eventEaseLabel) { openSheet(.inspector) }
            DockButton(store.selectedEvent?.linkGroup == 0 ? "Link" : "Linked", selected: store.selectedEvent?.linkGroup != 0) {
                store.toggleSelectedEventLink()
            }
        } else {
            DockButton("Paste", enabled: !store.clipboard.isEmpty) {
                store.paste(at: store.currentBeat)
            }
            DockButton("Mirror paste", enabled: !store.clipboard.isEmpty) {
                store.paste(at: store.currentBeat, mirrored: true)
            }
            DockButton(
                "Clear",
                enabled: !store.selectedStoryboardIDs.isEmpty
            ) {
                store.clearSelection()
            }
        }
    }

    @ViewBuilder
    private var deleteCopyCutControls: some View {
        DockButton("Delete") { store.deleteSelection() }
        DockButton("Copy") { store.copySelection() }
        DockButton("Cut") { store.cutSelection() }
    }

    @ViewBuilder
    private var multipleSelectionControls: some View {
        deleteCopyCutControls
        DockButton("Paste", enabled: !store.clipboard.isEmpty) {
            store.paste(at: store.currentBeat)
        }
        DockButton("Mirror paste", enabled: !store.clipboard.isEmpty) {
            store.paste(at: store.currentBeat, mirrored: true)
        }
        DockButton("Clear") { store.clearSelection() }
    }

    @ViewBuilder
    private var arrangeControls: some View {
        DockButton(store.xyBindingEnabled ? "XY ✓" : "XY Off", selected: store.xyBindingEnabled) {
            store.toggleXYBinding()
        }
        DockButton("Layer −", enabled: store.currentLayerIndex > 0) {
            store.changeLayer(by: -1)
        }
        DockButton("Layer +", enabled: store.currentLayerIndex < 3) {
            store.changeLayer(by: 1)
        }
        DockButton("Line −", enabled: store.currentLineIndex > 0) {
            store.changeLine(by: -1)
        }
        DockButton(
            "Line +",
            enabled: store.currentLineIndex + 1 < store.chart.judgeLines.count
        ) {
            store.changeLine(by: 1)
        }
        DockButton("X grid −") { store.changeVerticalGrid(by: -1) }
        DockButton("X grid +") { store.changeVerticalGrid(by: 1) }
        DockButton("Beat grid −") { store.changeSubdivision(by: -1) }
        DockButton("Beat grid +") { store.changeSubdivision(by: 1) }
    }

    private var selectionSummary: String {
        "Select · L\(store.currentLineIndex) · layer \(store.currentLayerIndex)"
            + " · beat 1/\(store.settings.value.horizontalSubdivision)"
            + " · X grid \(store.settings.value.verticalGridLines)"
    }

    private var hint: String {
        if !store.statusMessage.isEmpty { return store.statusMessage }
        switch store.controlPanel {
        case .create:
            return "No selection · Choose a placement tool; drag Hold or Event to set its duration"
        case .edit:
            return "Box+ adds to selection · Move cycles through XY, X, Y and Off"
        case .arrange:
            return "Adjust layer, line, beat subdivision and X grid"
        }
    }

    private var eventStep: Double {
        guard let type = store.selectedEvent?.type else { return 10 }
        switch type {
        case .rotate: return 0.25
        case .speed: return 0.1
        default: return 10
        }
    }

    private var eventEaseLabel: String {
        guard let event = store.selectedEvent else { return "Ease" }
        if event.usesBezier, event.type != .speed {
            return "Ease Custom Bézier"
        }
        let type = event.type == .speed ? 1 : event.easingType
        return "Ease \(Easing.title(for: type))"
    }
}

private struct DockButton: View {
    let title: String
    let selected: Bool
    let enabled: Bool
    let action: () -> Void

    init(
        _ title: String,
        selected: Bool = false,
        enabled: Bool = true,
        action: @escaping () -> Void
    ) {
        self.title = title
        self.selected = selected
        self.enabled = enabled
        self.action = action
    }

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(.system(size: 12, weight: .medium))
                .foregroundStyle(.white.opacity(enabled ? 0.94 : 0.36))
                .lineLimit(1)
                .multilineTextAlignment(.center)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .frame(width: 98, height: 46)
        .background(
            selected
                ? Color(red: 0.16, green: 0.58, blue: 0.53)
                : Color(red: 0.14, green: 0.18, blue: 0.22)
        )
    }
}

private enum AndroidMenuAction: String, Identifiable {
    case projects
    case importPackage
    case exportPackage
    case metadata
    case bpm
    case lines
    case storyboard
    case curveNotes
    case complexMove
    case settings
    case diagnostics
    case replaceAudio
    case replaceIllustration

    var id: String { rawValue }
}

private struct AndroidStyleMenu: View {
    let close: () -> Void
    let action: (AndroidMenuAction) -> Void

    var body: some View {
        GeometryReader { proxy in
            ZStack {
                Color.black.opacity(0.72)
                    .ignoresSafeArea()
                    .onTapGesture(perform: close)

                VStack(alignment: .leading, spacing: 8) {
                    Text("Menu")
                        .font(.system(size: 25, weight: .medium))
                        .padding(.horizontal, 20)
                        .padding(.top, 20)

                    HStack(alignment: .top, spacing: 18) {
                        menuColumn(
                            title: "Projects",
                            items: [
                                ("Project library", .projects),
                                ("Import package", .importPackage),
                                ("Export package", .exportPackage),
                            ]
                        )
                        menuColumn(
                            title: "Chart editing",
                            items: [
                                ("Chart metadata", .metadata),
                                ("BPM List", .bpm),
                                ("Line List", .lines),
                                ("Storyboard events", .storyboard),
                                ("Curve Notes", .curveNotes),
                                ("Complex Move", .complexMove),
                                ("Settings", .settings),
                                ("Chart diagnostics", .diagnostics),
                            ]
                        )
                    }
                    .padding(.horizontal, 14)

                    Spacer(minLength: 6)
                    HStack {
                        Spacer()
                        Button("Cancel", action: close)
                            .font(.system(size: 16, weight: .medium))
                            .buttonStyle(.plain)
                            .padding(.trailing, 22)
                            .padding(.bottom, 18)
                    }
                }
                .frame(
                    width: min(870, proxy.size.width - 80),
                    height: min(405, proxy.size.height - 72)
                )
                .background(Color(white: 0.25))
            }
        }
    }

    private func menuColumn(
        title: String,
        items: [(String, AndroidMenuAction)]
    ) -> some View {
        VStack(alignment: .leading, spacing: 5) {
            Text(title)
                .font(.system(size: 17))
                .foregroundStyle(.white.opacity(0.75))
                .padding(.horizontal, 6)
            ScrollView(showsIndicators: false) {
                LazyVStack(spacing: 0) {
                    ForEach(Array(items.enumerated()), id: \.offset) { _, entry in
                        Button {
                            action(entry.1)
                        } label: {
                            Text(entry.0)
                                .font(.system(size: 16))
                                .foregroundStyle(.white.opacity(0.94))
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .padding(.horizontal, 2)
                                .frame(height: 60)
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
            .padding(.horizontal, 2)
            .background(Color(white: 0.29))
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

private struct EditorSheetHost: View {
    let sheet: EditorSheet
    @Bindable var store: EditorStore
    let close: () -> Void

    @ViewBuilder
    var body: some View {
        switch sheet {
        case .inspector:
            wrapped { InspectorView(store: store) }
        case .tools:
            ToolsView(store: store)
        case .metadata:
            ChartMetadataEditor(store: store, close: close)
        case .bpm:
            wrapped { BPMManagerView(store: store) }
        case .lines:
            wrapped { JudgeLineManager(store: store) }
        case .storyboard:
            wrapped { StoryboardManager(store: store) }
        case .curveNotes:
            wrapped { CurveNotesTool(store: store) }
        case .complexMove:
            wrapped { ComplexMoveTool(store: store) }
        case .batchEdit:
            wrapped { BatchEditTool(store: store) }
        case .eventClone:
            wrapped { EventCloneTool(store: store) }
        case .settings:
            SettingsView(store: store.settings)
        }
    }

    private func wrapped<Content: View>(
        @ViewBuilder content: () -> Content
    ) -> some View {
        NavigationStack {
            content()
                .toolbar {
                    ToolbarItem(placement: .confirmationAction) {
                        Button("Done", action: close)
                    }
                }
        }
    }
}

private struct ChartMetadataEditor: View {
    @Bindable var store: EditorStore
    let close: () -> Void
    @State private var name: String
    @State private var composer: String
    @State private var charter: String
    @State private var level: String
    @State private var offset: Int

    init(store: EditorStore, close: @escaping () -> Void) {
        self.store = store
        self.close = close
        _name = State(initialValue: store.chart.name)
        _composer = State(initialValue: store.chart.composer)
        _charter = State(initialValue: store.chart.charter)
        _level = State(initialValue: store.chart.level)
        _offset = State(initialValue: store.chart.offsetMilliseconds)
    }

    var body: some View {
        NavigationStack {
            Form {
                TextField("Name", text: $name)
                TextField("Composer", text: $composer)
                TextField("Charter", text: $charter)
                TextField("Level", text: $level)
                TextField("Chart offset (ms)", value: $offset, format: .number)
                    .keyboardType(.numbersAndPunctuation)
            }
            .navigationTitle("Chart metadata")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel", action: close)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Apply") {
                        store.updateMetadata(
                            name: name,
                            composer: composer,
                            charter: charter,
                            level: level,
                            offsetMilliseconds: offset
                        )
                        close()
                    }
                }
            }
        }
    }
}

private struct JudgeLineManager: View {
    @Bindable var store: EditorStore
    @State private var pendingDeleteIndex: Int?

    var body: some View {
        List {
            ForEach(Array(store.chart.judgeLines.enumerated()), id: \.element.id) {
                index, line in
                HStack(spacing: 12) {
                    Button {
                        store.selectLine(index)
                    } label: {
                        VStack(alignment: .leading) {
                            Text(line.name.isEmpty ? "Line \(index)" : line.name)
                            Text("\(line.notes.count) notes · \(line.eventCount) events")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)

                    NavigationLink {
                        LineInspector(store: store, line: line)
                            .onAppear { store.selectLine(index) }
                            .navigationTitle("Line \(index) settings")
                    } label: {
                        Image(systemName: "slider.horizontal.3")
                            .frame(width: 38, height: 38)
                    }
                    .buttonStyle(.bordered)
                    .accessibilityLabel("Edit line \(index)")

                    Button {
                        store.selectLine(index)
                        store.duplicateCurrentLine()
                    } label: {
                        Image(systemName: "plus.square.on.square")
                            .frame(width: 28, height: 28)
                    }
                    .buttonStyle(.bordered)
                    .accessibilityLabel("Duplicate line \(index)")

                    Button(role: .destructive) {
                        pendingDeleteIndex = index
                    } label: {
                        Image(systemName: "trash")
                            .frame(width: 28, height: 28)
                    }
                    .buttonStyle(.bordered)
                    .disabled(store.chart.judgeLines.count <= 1)
                    .accessibilityLabel("Delete line \(index)")

                    if index == store.currentLineIndex {
                        Image(systemName: "checkmark").foregroundStyle(.tint)
                    }
                }
                .padding(.vertical, 3)
            }
        }
        .navigationTitle("Line list")
        .confirmationDialog(
            "Delete this judge line?",
            isPresented: Binding(
                get: { pendingDeleteIndex != nil },
                set: { if !$0 { pendingDeleteIndex = nil } }
            ),
            titleVisibility: .visible
        ) {
            Button("Delete line", role: .destructive) {
                guard let index = pendingDeleteIndex else { return }
                pendingDeleteIndex = nil
                store.selectLine(index)
                store.removeCurrentLine()
            }
            Button("Cancel", role: .cancel) {
                pendingDeleteIndex = nil
            }
        } message: {
            Text("Notes, events and storyboard tracks on this line will be removed.")
        }
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button {
                    store.addLine()
                } label: {
                    Label("Add line", systemImage: "plus")
                }
            }
        }
    }
}

struct StoryboardManager: View {
    @Bindable var store: EditorStore

    var body: some View {
        List {
            ForEach(StoryboardEventType.allCases) { type in
                Section(type.title) {
                    ForEach(store.currentLine?.storyboard.events[type] ?? []) { event in
                        NavigationLink {
                            InspectorView(store: store)
                                .onAppear {
                                    store.selectStoryboardEvent(event.id, extending: false)
                                }
                        } label: {
                            HStack {
                                Text(event.startTime.description)
                                    .font(.body.monospacedDigit())
                                Spacer()
                                Text(event.endTime.description)
                                    .foregroundStyle(.secondary)
                            }
                        }
                    }
                    Button {
                        store.addStoryboardEvent(type: type, at: store.currentBeat)
                    } label: {
                        Label("Add \(type.title)", systemImage: "plus")
                    }
                }
            }
        }
        .navigationTitle("Storyboard")
    }
}

private struct EditorWorkspace: View {
    @Bindable var store: EditorStore

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 12) {
                Picker("Mode", selection: $store.mode) {
                    ForEach(EditorMode.allCases) { mode in
                        Label(mode.title, systemImage: mode.symbol).tag(mode)
                    }
                }
                .pickerStyle(.segmented)
                if store.isDirty {
                    Circle()
                        .fill(.orange)
                        .frame(width: 7, height: 7)
                        .accessibilityLabel("Unsaved changes")
                }
            }
            .padding(.horizontal)
            .padding(.vertical, 10)
            .background(.bar)

            Group {
                if store.mode == .preview {
                    PreviewCanvasView(store: store)
                } else {
                    TimelineEditorView(store: store)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)

            IPadControlDock(store: store) { sheet in
                if sheet == .inspector {
                    store.inspectorPresented = true
                } else {
                    store.toolsPresented = true
                }
            }
            TransportBar(store: store)
        }
        .navigationTitle(store.chart.name)
        .navigationBarTitleDisplayMode(.inline)
    }
}

private struct TransportBar: View {
    @Bindable var store: EditorStore

    var body: some View {
        HStack(spacing: 14) {
            Button { store.setBeat(max(0, store.currentBeat - 1)) } label: {
                Image(systemName: "backward.frame.fill")
            }
            Button { store.togglePlayback() } label: {
                Image(systemName: store.isPlaying ? "pause.fill" : "play.fill")
                    .frame(width: 24)
            }
            Button { store.setBeat(store.currentBeat + 1) } label: {
                Image(systemName: "forward.frame.fill")
            }
            Slider(
                value: Binding(
                    get: { store.currentBeat },
                    set: { store.setBeat($0, seekAudio: false) }
                ),
                in: 0 ... max(1, store.chart.finalBeat + 4),
                onEditingChanged: { editing in
                    if editing { store.beginScrubbing() } else { store.endScrubbing() }
                }
            )
            Text(BeatTime.fromDouble(store.currentBeat, division: 1_000).description)
                .font(.caption.monospacedDigit())
                .frame(minWidth: 62, alignment: .trailing)
            Text(store.statusMessage)
                .font(.caption)
                .foregroundStyle(.secondary)
                .lineLimit(1)
                .frame(maxWidth: 150, alignment: .trailing)
        }
        .buttonStyle(.borderless)
        .padding(.horizontal)
        .padding(.vertical, 9)
        .background(.bar)
    }
}
