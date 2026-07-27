import Foundation
import Observation

public struct EditorSettings: Codable, Hashable, Sendable {
    public static let verticalGridOptions = [3, 5, 7, 9, 11, 13, 17, 21, 25, 33]

    public var horizontalSubdivision = 4
    public var verticalGridLines = 11
    public var showHorizontalGrid = true
    public var showVerticalGrid = true
    public var snapToVerticalGrid = true
    public var highlightSimultaneousNotes = true
    public var showGameHUD = true
    public var showOtherLines = true
    public var enableHitSounds = true
    public var autosaveEnabled = true
    public var autosaveDelaySeconds = 2.0
    public var defaultVisibleBeats = 8.0
    public var backgroundDim = 108.0 / 255.0
    public var useRPE170Speed = true
    public var previewLineWidth = 1.7
    public var noteScale = 1.0

    public init() {}

    public mutating func normalize() {
        horizontalSubdivision = min(64, max(1, horizontalSubdivision))
        let requestedVerticalGridLines = verticalGridLines
        verticalGridLines = Self.verticalGridOptions.min { left, right in
            let leftDistance = Swift.abs(left - requestedVerticalGridLines)
            let rightDistance = Swift.abs(right - requestedVerticalGridLines)
            return leftDistance < rightDistance
        } ?? 11
        autosaveDelaySeconds = min(30, max(0.5, autosaveDelaySeconds))
        defaultVisibleBeats = min(64, max(1, defaultVisibleBeats))
        backgroundDim = min(0.95, max(0, backgroundDim))
        previewLineWidth = min(24, max(1, previewLineWidth))
        noteScale = min(3, max(0.25, noteScale))
    }
}

@MainActor
@Observable
public final class SettingsStore {
    public private(set) var value: EditorSettings {
        didSet {
            persist()
        }
    }

    private let defaults: UserDefaults
    private let key = "PhiStudio.EditorSettings.v1"

    public init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        if let data = defaults.data(forKey: key),
           var decoded = try? JSONDecoder().decode(EditorSettings.self, from: data)
        {
            decoded.normalize()
            value = decoded
        } else {
            value = EditorSettings()
        }
    }

    public func reset() {
        value = EditorSettings()
    }

    public func update<Value>(
        _ keyPath: WritableKeyPath<EditorSettings, Value>,
        to newValue: Value
    ) {
        var updated = value
        updated[keyPath: keyPath] = newValue
        updated.normalize()
        value = updated
    }

    private func persist() {
        guard let data = try? JSONEncoder().encode(value) else { return }
        defaults.set(data, forKey: key)
    }
}
