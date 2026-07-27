import SwiftUI
import UniformTypeIdentifiers

struct CreateProjectView: View {
    @Environment(\.dismiss) private var dismiss
    @Bindable var model: AppModel
    @State private var name = "Untitled"
    @State private var composer = ""
    @State private var charter = ""
    @State private var level = "IN Lv.1"
    @State private var bpm = 120.0
    @State private var audioSelection: StagedImportFile?
    @State private var illustrationSelection: StagedImportFile?
    @State private var pickingAudio = false
    @State private var pickingIllustration = false
    @State private var selectionError: String?

    var body: some View {
        NavigationStack {
            Form {
                Section("Chart") {
                    TextField("Name", text: $name)
                    TextField("Composer", text: $composer)
                    TextField("Charter", text: $charter)
                    TextField("Level", text: $level)
                    TextField("BPM", value: $bpm, format: .number)
                        .keyboardType(.decimalPad)
                }
                Section("Assets") {
                    Button {
                        pickingAudio = true
                    } label: {
                        LabeledContent(
                            "Music",
                            value: audioSelection?.url.lastPathComponent ?? "Choose file"
                        )
                    }
                    Button {
                        pickingIllustration = true
                    } label: {
                        LabeledContent(
                            "Illustration",
                            value: illustrationSelection?.url.lastPathComponent ?? "Choose image"
                        )
                    }
                }
                Section {
                    Text("Music and illustration are optional and can be replaced later.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }
            .navigationTitle("New Chart")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") {
                        removeSelections()
                        dismiss()
                    }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Create") {
                        Task {
                            await model.createProject(
                                name: name,
                                composer: composer,
                                charter: charter,
                                level: level,
                                bpm: bpm,
                                audioURL: audioSelection?.url,
                                illustrationURL: illustrationSelection?.url
                            )
                            if model.editor != nil {
                                removeSelections()
                                dismiss()
                            }
                        }
                    }
                    .disabled(name.trimmingCharacters(in: .whitespaces).isEmpty || bpm <= 0)
                }
            }
            .sheet(isPresented: $pickingAudio) {
                SystemDocumentPicker(isPresented: $pickingAudio) { result in
                    selectAudio(from: result)
                }
            }
            .sheet(isPresented: $pickingIllustration) {
                SystemDocumentPicker(isPresented: $pickingIllustration) { result in
                    selectIllustration(from: result)
                }
            }
            .alert(
                "Could Not Select File",
                isPresented: Binding(
                    get: { selectionError != nil },
                    set: { if !$0 { selectionError = nil } }
                )
            ) {
                Button("OK", role: .cancel) {}
            } message: {
                Text(selectionError ?? "")
            }
            .onDisappear {
                removeSelections()
            }
        }
    }

    private func selectAudio(from result: Result<[URL], Error>) {
        Task {
            do {
                guard let source = try result.get().first else { return }
                let staged = try await ImportedFileStager.stage(source, as: .audio)
                audioSelection?.remove()
                audioSelection = staged
            } catch {
                selectionError = error.localizedDescription
            }
        }
    }

    private func selectIllustration(from result: Result<[URL], Error>) {
        Task {
            do {
                guard let source = try result.get().first else { return }
                let staged = try await ImportedFileStager.stage(source, as: .illustration)
                illustrationSelection?.remove()
                illustrationSelection = staged
            } catch {
                selectionError = error.localizedDescription
            }
        }
    }

    private func removeSelections() {
        audioSelection?.remove()
        illustrationSelection?.remove()
    }
}

struct SettingsView: View {
    @Environment(\.dismiss) private var dismiss
    @Bindable var store: SettingsStore

    var body: some View {
        NavigationStack {
            Form {
                Section("Grid") {
                    Stepper(
                        "Beat subdivision: \(store.value.horizontalSubdivision)",
                        value: setting(\.horizontalSubdivision),
                        in: 1 ... 64
                    )
                    Picker(
                        "Vertical lines",
                        selection: setting(\.verticalGridLines)
                    ) {
                        ForEach(EditorSettings.verticalGridOptions, id: \.self) {
                            Text("\($0)").tag($0)
                        }
                    }
                    Toggle("Show horizontal grid", isOn: setting(\.showHorizontalGrid))
                    Toggle("Show vertical grid", isOn: setting(\.showVerticalGrid))
                    Toggle("Snap X to vertical grid", isOn: setting(\.snapToVerticalGrid))
                }
                Section("Preview") {
                    Toggle(
                        "Highlight simultaneous notes",
                        isOn: setting(\.highlightSimultaneousNotes)
                    )
                    Toggle("Show game HUD", isOn: setting(\.showGameHUD))
                    Toggle("Show other lines", isOn: setting(\.showOtherLines))
                    Toggle("Hit sounds", isOn: setting(\.enableHitSounds))
                    Toggle("Use RPE 1.7 speed rules", isOn: setting(\.useRPE170Speed))
                    Slider(value: setting(\.backgroundDim), in: 0 ... 0.95) {
                        Text("Background dim")
                    }
                    Slider(value: setting(\.noteScale), in: 0.25 ... 3) {
                        Text("Note scale")
                    }
                    Slider(value: setting(\.previewLineWidth), in: 1 ... 8) {
                        Text("Judge-line width")
                    }
                    LabeledContent(
                        "Judge-line width",
                        value: store.value.previewLineWidth.formatted(
                            .number.precision(.fractionLength(1))
                        )
                    )
                }
                Section("Saving") {
                    Toggle("Autosave", isOn: setting(\.autosaveEnabled))
                    Slider(value: setting(\.autosaveDelaySeconds), in: 0.5 ... 15) {
                        Text("Autosave delay")
                    }
                    LabeledContent(
                        "Delay",
                        value: store.value.autosaveDelaySeconds.formatted(
                            .number.precision(.fractionLength(1))
                        ) + " s"
                    )
                }
                Section {
                    Button("Reset Settings", role: .destructive) {
                        store.reset()
                    }
                }
            }
            .navigationTitle("Settings")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
    }

    private func setting<Value>(
        _ keyPath: WritableKeyPath<EditorSettings, Value>
    ) -> Binding<Value> {
        Binding(
            get: { store.value[keyPath: keyPath] },
            set: { store.update(keyPath, to: $0) }
        )
    }
}
