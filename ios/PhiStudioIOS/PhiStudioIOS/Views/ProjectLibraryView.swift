import SwiftUI
import UniformTypeIdentifiers

struct ProjectLibraryView: View {
    @Bindable var model: AppModel
    @State private var showingImporter = false
    @State private var showingCreator = false
    @State private var showingSettings = false
    @State private var pendingDeletion: ProjectRecord?
    @State private var stagingImport = false

    private let columns = [
        GridItem(.adaptive(minimum: 280, maximum: 420), spacing: 18),
    ]

    var body: some View {
        NavigationStack {
            ScrollView {
                if model.projects.isEmpty, !model.isBusy {
                    EmptyStateView(
                        title: "No projects yet",
                        message: "Create a chart or import an RPE, Phigros, PEC, ZIP, or PEZ file.",
                        symbol: "square.stack.3d.up.slash"
                    )
                    .frame(maxWidth: .infinity, minHeight: 480)
                } else {
                    LazyVGrid(columns: columns, spacing: 18) {
                        ForEach(model.projects) { project in
                            ProjectCard(project: project)
                                .contentShape(Rectangle())
                                .onTapGesture {
                                    Task { await model.open(project.id) }
                                }
                                .contextMenu {
                                    Button {
                                        Task { await model.duplicate(project.id) }
                                    } label: {
                                        Label("Duplicate", systemImage: "plus.square.on.square")
                                    }
                                    Button(role: .destructive) {
                                        pendingDeletion = project
                                    } label: {
                                        Label("Delete", systemImage: "trash")
                                    }
                                }
                        }
                    }
                    .padding(22)
                }
            }
            .background(
                LinearGradient(
                    colors: [
                        Color(red: 0.035, green: 0.045, blue: 0.08),
                        Color(red: 0.07, green: 0.04, blue: 0.12),
                    ],
                    startPoint: .topLeading,
                    endPoint: .bottomTrailing
                )
                .ignoresSafeArea()
            )
            .navigationTitle("PhiStudio")
            .toolbar {
                ToolbarItemGroup(placement: .primaryAction) {
                    Button {
                        showingImporter = true
                    } label: {
                        Label("Import", systemImage: "square.and.arrow.down")
                    }
                    Button {
                        showingCreator = true
                    } label: {
                        Label("New Chart", systemImage: "plus")
                    }
                    Button {
                        showingSettings = true
                    } label: {
                        Label("Settings", systemImage: "gearshape")
                    }
                }
            }
            .overlay {
                if model.isBusy || stagingImport {
                    ZStack {
                        Color.black.opacity(0.25).ignoresSafeArea()
                        ProgressView("Working…")
                            .controlSize(.large)
                            .phiPanel()
                    }
                }
            }
            .refreshable {
                await model.refresh()
            }
            .sheet(isPresented: $showingImporter) {
                SystemDocumentPicker(isPresented: $showingImporter) { result in
                    handleImportSelection(result)
                }
            }
            .sheet(isPresented: $showingCreator) {
                CreateProjectView(model: model)
            }
            .sheet(isPresented: $showingSettings) {
                SettingsView(store: model.settings)
            }
            .alert(
                "PhiStudio",
                isPresented: Binding(
                    get: { model.presentedError != nil },
                    set: { if !$0 { model.presentedError = nil } }
                )
            ) {
                Button("OK", role: .cancel) {}
            } message: {
                Text(model.presentedError ?? "")
            }
            .confirmationDialog(
                "Delete \(pendingDeletion?.name ?? "project")?",
                isPresented: Binding(
                    get: { pendingDeletion != nil },
                    set: { if !$0 { pendingDeletion = nil } }
                ),
                titleVisibility: .visible
            ) {
                Button("Delete Project", role: .destructive) {
                    guard let project = pendingDeletion else { return }
                    pendingDeletion = nil
                    Task { await model.remove(project.id) }
                }
                Button("Cancel", role: .cancel) { pendingDeletion = nil }
            } message: {
                Text("This removes the private project workspace and cannot be undone.")
            }
        }
    }

    private func handleImportSelection(_ result: Result<[URL], Error>) {
        switch result {
        case let .success(urls):
            if let url = urls.first {
                Task {
                    stagingImport = true
                    defer { stagingImport = false }
                    do {
                        let staged = try await ImportedFileStager.stage(url, as: .chart)
                        defer { staged.remove() }
                        await model.importFile(staged.url)
                    } catch {
                        model.presentedError = error.localizedDescription
                    }
                }
            }
        case let .failure(error):
            model.presentedError = error.localizedDescription
        }
    }
}

private struct ProjectCard: View {
    let project: ProjectRecord

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            ZStack {
                LinearGradient(
                    colors: [.indigo.opacity(0.8), .purple.opacity(0.6), .cyan.opacity(0.35)],
                    startPoint: .topLeading,
                    endPoint: .bottomTrailing
                )
                Image(systemName: "waveform.path")
                    .font(.system(size: 56, weight: .thin))
                    .foregroundStyle(.white.opacity(0.75))
            }
            .frame(height: 145)
            .clipShape(RoundedRectangle(cornerRadius: 13))

            VStack(alignment: .leading, spacing: 5) {
                Text(project.name)
                    .font(.title3.weight(.bold))
                    .lineLimit(1)
                Text(project.sourceDisplayName)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }

            HStack(spacing: 8) {
                StatusPill(text: "\(project.noteCount)", symbol: "music.note", tint: .cyan)
                StatusPill(
                    text: "\(project.eventCount)",
                    symbol: "waveform.path.ecg",
                    tint: .orange
                )
                Spacer()
                Text(project.modifiedAt, style: .relative)
                    .font(.caption2)
                    .foregroundStyle(.tertiary)
            }
        }
        .padding(14)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 19))
        .overlay {
            RoundedRectangle(cornerRadius: 19)
                .stroke(.white.opacity(0.1))
        }
        .shadow(color: .black.opacity(0.24), radius: 18, y: 8)
    }
}
