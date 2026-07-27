import SwiftUI

@main
struct PhiStudioIOSApp: App {
    @State private var model = AppModel()

    var body: some Scene {
        WindowGroup {
            Group {
                if let editor = model.editor {
                    EditorRootView(store: editor, appModel: model)
                } else {
                    ProjectLibraryView(model: model)
                }
            }
            .preferredColorScheme(.dark)
            .task {
                if model.projects.isEmpty {
                    await model.refresh()
                }
            }
            .onOpenURL { url in
                Task {
                    do {
                        let staged = try await ImportedFileStager.stage(url, as: .chart)
                        defer { staged.remove() }
                        await model.importFile(staged.url)
                    } catch {
                        model.presentedError = error.localizedDescription
                    }
                }
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
        }
    }
}
