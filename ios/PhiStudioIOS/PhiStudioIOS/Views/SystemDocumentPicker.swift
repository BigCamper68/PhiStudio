import SwiftUI
import UIKit
import UniformTypeIdentifiers

struct SystemDocumentPicker: UIViewControllerRepresentable {
    @Binding var isPresented: Bool
    let completion: (Result<[URL], Error>) -> Void

    func makeCoordinator() -> Coordinator {
        Coordinator(parent: self)
    }

    func makeUIViewController(context: Context) -> UIDocumentPickerViewController {
        let picker = UIDocumentPickerViewController(
            forOpeningContentTypes: [.data],
            asCopy: true
        )
        picker.delegate = context.coordinator
        picker.allowsMultipleSelection = false
        picker.shouldShowFileExtensions = true
        return picker
    }

    func updateUIViewController(
        _: UIDocumentPickerViewController,
        context _: Context
    ) {}

    final class Coordinator: NSObject, UIDocumentPickerDelegate {
        private let parent: SystemDocumentPicker

        init(parent: SystemDocumentPicker) {
            self.parent = parent
        }

        func documentPicker(
            _: UIDocumentPickerViewController,
            didPickDocumentsAt urls: [URL]
        ) {
            parent.isPresented = false
            parent.completion(.success(urls))
        }

        func documentPickerWasCancelled(_: UIDocumentPickerViewController) {
            parent.isPresented = false
        }
    }
}
