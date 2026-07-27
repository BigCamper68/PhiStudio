import SwiftUI
import UniformTypeIdentifiers

extension UTType {
    static let phiPackage = UTType(
        exportedAs: "com.bigcamper68.phistudio.package",
        conformingTo: .zip
    )
    static let pecChart = UTType(
        exportedAs: "com.bigcamper68.phistudio.pec-chart",
        conformingTo: .plainText
    )
}

struct PackageArchiveDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.phiPackage, .zip, .data] }
    var data: Data

    init(data: Data = Data()) {
        self.data = data
    }

    init(configuration: ReadConfiguration) throws {
        data = configuration.file.regularFileContents ?? Data()
    }

    func fileWrapper(configuration _: WriteConfiguration) throws -> FileWrapper {
        FileWrapper(regularFileWithContents: data)
    }
}

struct StatusPill: View {
    var text: String
    var symbol: String
    var tint: Color = .accentColor

    var body: some View {
        Label(text, systemImage: symbol)
            .font(.caption.weight(.semibold))
            .padding(.horizontal, 10)
            .padding(.vertical, 6)
            .background(tint.opacity(0.18), in: Capsule())
            .foregroundStyle(tint)
    }
}

struct EmptyStateView: View {
    var title: String
    var message: String
    var symbol: String

    var body: some View {
        ContentUnavailableView(title, systemImage: symbol, description: Text(message))
    }
}

extension Color {
    static func rgb(_ value: Int, alpha: Double = 1) -> Color {
        Color(
            red: Double((value >> 16) & 0xFF) / 255,
            green: Double((value >> 8) & 0xFF) / 255,
            blue: Double(value & 0xFF) / 255,
            opacity: alpha
        )
    }

    static func note(_ type: NoteType, multi: Bool = false) -> Color {
        if multi { return .cyan }
        switch type {
        case .tap: return Color(red: 0.25, green: 0.72, blue: 1)
        case .drag: return Color(red: 1, green: 0.84, blue: 0.2)
        case .flick: return Color(red: 1, green: 0.35, blue: 0.4)
        case .hold: return Color(red: 0.43, green: 0.88, blue: 0.65)
        }
    }

    static func event(_ type: EventType) -> Color {
        switch type {
        case .moveX: .cyan
        case .moveY: .green
        case .rotate: .orange
        case .alpha: .purple
        case .speed: .pink
        }
    }
}

extension View {
    func phiPanel() -> some View {
        padding()
            .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 16))
            .overlay {
                RoundedRectangle(cornerRadius: 16)
                    .stroke(.white.opacity(0.08))
            }
    }
}
