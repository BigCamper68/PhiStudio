import Foundation

/// A lossless-enough JSON tree used to preserve RPE fields that PhiStudio does not edit yet.
///
/// Integers and floating-point numbers are kept separately. This prevents values such as large
/// identifiers from being rounded when a chart is opened and exported again.
public enum JSONValue: Hashable, Sendable {
    case null
    case bool(Bool)
    case integer(Int64)
    case number(Double)
    case string(String)
    case array([JSONValue])
    case object([String: JSONValue])
}

extension JSONValue: Codable {
    private struct DynamicKey: CodingKey {
        let stringValue: String
        let intValue: Int?

        init?(stringValue: String) {
            self.stringValue = stringValue
            intValue = nil
        }

        init?(intValue: Int) {
            stringValue = String(intValue)
            self.intValue = intValue
        }
    }

    public init(from decoder: Decoder) throws {
        if var container = try? decoder.unkeyedContainer() {
            var values: [JSONValue] = []
            while !container.isAtEnd {
                values.append(try container.decode(JSONValue.self))
            }
            self = .array(values)
            return
        }

        if let container = try? decoder.container(keyedBy: DynamicKey.self) {
            var values: [String: JSONValue] = [:]
            for key in container.allKeys {
                values[key.stringValue] = try container.decode(JSONValue.self, forKey: key)
            }
            self = .object(values)
            return
        }

        let container = try decoder.singleValueContainer()
        if container.decodeNil() {
            self = .null
        } else if let value = try? container.decode(Bool.self) {
            self = .bool(value)
        } else if let value = try? container.decode(Int64.self) {
            self = .integer(value)
        } else if let value = try? container.decode(Double.self) {
            guard value.isFinite else {
                throw DecodingError.dataCorruptedError(
                    in: container,
                    debugDescription: "JSON numbers must be finite"
                )
            }
            self = .number(value)
        } else if let value = try? container.decode(String.self) {
            self = .string(value)
        } else {
            throw DecodingError.dataCorruptedError(
                in: container,
                debugDescription: "Unsupported JSON value"
            )
        }
    }

    public func encode(to encoder: Encoder) throws {
        switch self {
        case .null:
            var container = encoder.singleValueContainer()
            try container.encodeNil()
        case let .bool(value):
            var container = encoder.singleValueContainer()
            try container.encode(value)
        case let .integer(value):
            var container = encoder.singleValueContainer()
            try container.encode(value)
        case let .number(value):
            guard value.isFinite else {
                throw EncodingError.invalidValue(
                    value,
                    .init(codingPath: encoder.codingPath, debugDescription: "JSON numbers must be finite")
                )
            }
            var container = encoder.singleValueContainer()
            try container.encode(value)
        case let .string(value):
            var container = encoder.singleValueContainer()
            try container.encode(value)
        case let .array(values):
            var container = encoder.unkeyedContainer()
            for value in values {
                try container.encode(value)
            }
        case let .object(values):
            var container = encoder.container(keyedBy: DynamicKey.self)
            for key in values.keys.sorted() {
                guard let codingKey = DynamicKey(stringValue: key), let value = values[key] else {
                    continue
                }
                try container.encode(value, forKey: codingKey)
            }
        }
    }
}

public extension JSONValue {
    static func parse(_ data: Data) throws -> JSONValue {
        try JSONDecoder().decode(JSONValue.self, from: data)
    }

    static func parse(_ text: String) throws -> JSONValue {
        guard let data = text.data(using: .utf8) else {
            throw ChartError.invalidJSON("The chart is not valid UTF-8")
        }
        return try parse(data)
    }

    func encoded(prettyPrinted: Bool = false) throws -> Data {
        let encoder = JSONEncoder()
        encoder.outputFormatting = prettyPrinted
            ? [.prettyPrinted, .sortedKeys, .withoutEscapingSlashes]
            : [.sortedKeys, .withoutEscapingSlashes]
        return try encoder.encode(self)
    }

    var objectValue: [String: JSONValue]? {
        guard case let .object(value) = self else { return nil }
        return value
    }

    var arrayValue: [JSONValue]? {
        guard case let .array(value) = self else { return nil }
        return value
    }

    var stringValue: String? {
        switch self {
        case let .string(value): value
        case let .integer(value): String(value)
        case let .number(value): String(value)
        default: nil
        }
    }

    var boolValue: Bool? {
        switch self {
        case let .bool(value): value
        case let .integer(value): value != 0
        case let .number(value): value != 0
        default: nil
        }
    }

    var intValue: Int? {
        switch self {
        case let .integer(value):
            Int(exactly: value)
        case let .number(value):
            value.isFinite ? Int(exactly: value.rounded(.towardZero)) : nil
        case let .bool(value):
            value ? 1 : 0
        default:
            nil
        }
    }

    var int64Value: Int64? {
        switch self {
        case let .integer(value):
            value
        case let .number(value):
            value.isFinite ? Int64(exactly: value.rounded(.towardZero)) : nil
        case let .bool(value):
            value ? 1 : 0
        default:
            nil
        }
    }

    var doubleValue: Double? {
        switch self {
        case let .integer(value): Double(value)
        case let .number(value): value
        case let .bool(value): value ? 1 : 0
        default: nil
        }
    }
}

public extension Dictionary where Key == String, Value == JSONValue {
    func string(_ key: String, default fallback: String = "") -> String {
        self[key]?.stringValue ?? fallback
    }

    func int(_ key: String, default fallback: Int = 0) -> Int {
        self[key]?.intValue ?? fallback
    }

    func int64(_ key: String, default fallback: Int64 = 0) -> Int64 {
        self[key]?.int64Value ?? fallback
    }

    func double(_ key: String, default fallback: Double = 0) -> Double {
        guard let value = self[key]?.doubleValue, value.isFinite else { return fallback }
        return value
    }

    func bool(_ key: String, default fallback: Bool = false) -> Bool {
        self[key]?.boolValue ?? fallback
    }

    func array(_ key: String) -> [JSONValue]? {
        self[key]?.arrayValue
    }

    func object(_ key: String) -> [String: JSONValue]? {
        self[key]?.objectValue
    }

    func removing(_ keys: Set<String>) -> [String: JSONValue] {
        filter { !keys.contains($0.key) }
    }
}

public enum ChartError: LocalizedError, Equatable, Sendable {
    case invalidJSON(String)
    case unsupportedFormat(String)
    case invalidValue(String)
    case missingResource(String)
    case archiveRejected(String)
    case fileSystem(String)

    public var errorDescription: String? {
        switch self {
        case let .invalidJSON(message): "Invalid chart JSON: \(message)"
        case let .unsupportedFormat(message): "Unsupported chart format: \(message)"
        case let .invalidValue(message): "Invalid chart value: \(message)"
        case let .missingResource(message): "Missing package resource: \(message)"
        case let .archiveRejected(message): "Package rejected: \(message)"
        case let .fileSystem(message): "File operation failed: \(message)"
        }
    }
}
