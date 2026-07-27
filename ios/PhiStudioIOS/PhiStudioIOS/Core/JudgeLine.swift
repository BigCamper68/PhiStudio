import Foundation

public struct JudgeLine: Identifiable, Hashable, Sendable {
    public var id = UUID()
    public var group = 0
    public var name = "Line"
    public var texture = "line.png"
    public var bpmFactor = 1.0
    public var father = -1
    public var rotateWithFather = false
    public var isCover = true
    public var zOrder = 0
    public var attachedUI: AttachedUIElement?
    public var storyboard = StoryboardTracks()
    public var noteControls = NoteControls()
    public var notes: [Note] = []
    public var eventLayers: [EventLayer] = [EventLayer(createDefaults: true)]
    public var raw: [String: JSONValue] = [:]

    public init() {}

    public init(json: JSONValue) throws {
        guard let object = json.objectValue else {
            throw ChartError.invalidJSON("Judge line must be an object")
        }
        group = object.int("Group")
        name = object.string("Name", default: "Line")
        texture = object.string("Texture", default: "line.png")
        bpmFactor = object.double("bpmfactor", default: 1)
        father = object.int("father", default: -1)
        rotateWithFather = object.bool("rotateWithFather")
        isCover = object.int("isCover", default: 1) != 0
        zOrder = object.int("zOrder")
        attachedUI = AttachedUIElement(jsonValue: object["attachUI"])
        storyboard = try StoryboardTracks(json: object["extended"])
        noteControls = NoteControls(line: object)
        notes = try (object.array("notes") ?? []).compactMap { value in
            guard value.objectValue != nil else { return nil }
            return try Note(json: value)
        }.sorted { $0.startTime < $1.startTime }
        eventLayers = try (object.array("eventLayers") ?? []).map(EventLayer.init(json:))
        if eventLayers.isEmpty { eventLayers = [EventLayer()] }
        raw = object.removing([
            "Group", "Name", "Texture", "bpmfactor", "father", "rotateWithFather",
            "isCover", "zOrder", "attachUI", "extended", "notes", "numOfNotes", "eventLayers",
        ])
    }

    public var json: JSONValue {
        var object = raw
        object["Group"] = .integer(Int64(group))
        object["Name"] = .string(name)
        object["Texture"] = .string(texture)
        object["bpmfactor"] = .number(bpmFactor)
        object["father"] = .integer(Int64(father))
        object["rotateWithFather"] = .bool(rotateWithFather)
        object["isCover"] = .integer(isCover ? 1 : 0)
        object["zOrder"] = .integer(Int64(zOrder))
        if let attachedUI {
            object["attachUI"] = .string(attachedUI.rawValue)
        } else {
            object["attachUI"] = nil
        }
        let storyboardJSON = storyboard.json
        if storyboard.count > 0 || !(storyboardJSON.objectValue ?? [:]).isEmpty {
            object["extended"] = storyboardJSON
        } else {
            object["extended"] = nil
        }
        let sortedNotes = notes.sorted { $0.startTime < $1.startTime }
        object["notes"] = .array(sortedNotes.map(\.json))
        object["numOfNotes"] = .integer(
            Int64(sortedNotes.filter { $0.type != .hold }.count)
        )
        object["eventLayers"] = .array(eventLayers.map(\.json))
        return .object(object)
    }

    public mutating func layer(_ index: Int) -> EventLayer {
        let safe = max(0, index)
        while eventLayers.count <= safe { eventLayers.append(EventLayer()) }
        return eventLayers[safe]
    }

    public var eventCount: Int {
        eventLayers.reduce(storyboard.count + noteControls.count) { $0 + $1.eventCount }
    }

    public func eventValue(_ type: EventType, at beat: Double) -> Double {
        var total = 0.0
        var affected = false
        for layer in eventLayers where !layer[type].isEmpty {
            total += layer.value(type, at: beat)
            affected = true
        }
        return affected ? total : type.defaultValue
    }
}
