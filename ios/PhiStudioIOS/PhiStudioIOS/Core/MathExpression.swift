import Foundation

public struct MathExpression: Sendable {
    private let root: Node

    public static func compile(_ source: String) throws -> MathExpression {
        var parser = Parser(source)
        let root = try parser.parse()
        return MathExpression(root: root)
    }

    public func evaluate(_ t: Double) throws -> Double {
        let value = root.evaluate(t)
        guard value.isFinite else {
            throw ChartError.invalidValue("Expression produced a non-finite value")
        }
        return value
    }

    private indirect enum Node: Sendable {
        case constant(Double)
        case variable
        case unary(Character, Node)
        case binary(Character, Node, Node)
        case function(String, [Node])

        func evaluate(_ t: Double) -> Double {
            switch self {
            case let .constant(value):
                return value
            case .variable:
                return t
            case let .unary(operation, operand):
                let value = operand.evaluate(t)
                return operation == "-" ? -value : value
            case let .binary(operation, left, right):
                let first = left.evaluate(t)
                let second = right.evaluate(t)
                switch operation {
                case "+": return first + second
                case "-": return first - second
                case "*": return first * second
                case "/": return first / second
                case "%": return first.truncatingRemainder(dividingBy: second)
                case "^": return pow(first, second)
                default: return .nan
                }
            case let .function(name, arguments):
                let values = arguments.map { $0.evaluate(t) }
                return Self.call(name, values)
            }
        }

        private static func call(_ name: String, _ values: [Double]) -> Double {
            let first = values.first ?? 0
            switch name {
            case "sin": return sin(first)
            case "cos": return cos(first)
            case "tan": return tan(first)
            case "asin": return asin(first)
            case "acos": return acos(first)
            case "atan": return atan(first)
            case "atan2": return atan2(first, values[1])
            case "sinh": return sinh(first)
            case "cosh": return cosh(first)
            case "tanh": return tanh(first)
            case "sqrt": return sqrt(first)
            case "abs": return abs(first)
            case "floor": return floor(first)
            case "ceil": return ceil(first)
            case "round": return first.rounded()
            case "exp": return exp(first)
            case "ln", "log": return log(first)
            case "log10": return log10(first)
            case "sign", "signum": return first == 0 ? 0 : (first > 0 ? 1 : -1)
            case "pow": return pow(first, values[1])
            case "clamp": return max(values[1], min(values[2], first))
            case "min": return values.min() ?? 0
            case "max": return values.max() ?? 0
            default: return .nan
            }
        }
    }

    private struct Parser {
        private static let maximumDepth = 64
        private var characters: [Character]
        private var position = 0
        private var depth = 0

        init(_ source: String) {
            characters = Array(source)
        }

        mutating func parse() throws -> Node {
            guard !characters.isEmpty else {
                throw error("Expression is required")
            }
            let node = try expression()
            skipWhitespace()
            guard atEnd else { throw error("Unexpected character") }
            return node
        }

        private mutating func expression() throws -> Node {
            try enter()
            defer { leave() }
            var node = try term()
            while true {
                if take("+") {
                    node = .binary("+", node, try term())
                } else if take("-") {
                    node = .binary("-", node, try term())
                } else {
                    return node
                }
            }
        }

        private mutating func term() throws -> Node {
            var node = try power()
            while true {
                if take("*") {
                    node = .binary("*", node, try power())
                } else if take("/") {
                    node = .binary("/", node, try power())
                } else if take("%") {
                    node = .binary("%", node, try power())
                } else {
                    return node
                }
            }
        }

        private mutating func power() throws -> Node {
            let node = try unary()
            return take("^") ? .binary("^", node, try power()) : node
        }

        private mutating func unary() throws -> Node {
            if take("+") { return .unary("+", try unary()) }
            if take("-") { return .unary("-", try unary()) }
            return try primary()
        }

        private mutating func primary() throws -> Node {
            skipWhitespace()
            if take("(") {
                let node = try expression()
                try require(")")
                return node
            }
            guard !atEnd else { throw error("Expected a value") }
            if current.isNumber || current == "." {
                return .constant(try number())
            }
            if current.isLetter || current == "_" {
                let name = identifier().lowercased()
                switch name {
                case "t", "x":
                    return .variable
                case "pi":
                    return .constant(.pi)
                case "e":
                    return .constant(exp(1))
                default:
                    try require("(")
                    var arguments: [Node] = []
                    if !take(")") {
                        repeat {
                            arguments.append(try expression())
                        } while take(",")
                        try require(")")
                    }
                    try Self.validate(name, count: arguments.count, error: error)
                    return .function(name, arguments)
                }
            }
            throw error("Expected a number, t, constant, function, or parenthesized expression")
        }

        private mutating func number() throws -> Double {
            skipWhitespace()
            let start = position
            var sawDigit = false
            while !atEnd, current.isNumber {
                sawDigit = true
                position += 1
            }
            if !atEnd, current == "." {
                position += 1
                while !atEnd, current.isNumber {
                    sawDigit = true
                    position += 1
                }
            }
            guard sawDigit else { throw error("Invalid number") }
            if !atEnd, (current == "e" || current == "E") {
                position += 1
                if !atEnd, (current == "+" || current == "-") { position += 1 }
                let exponentStart = position
                while !atEnd, current.isNumber { position += 1 }
                if exponentStart == position { throw error("Invalid exponent") }
            }
            let text = String(characters[start ..< position])
            guard let value = Double(text), value.isFinite else {
                throw error("Invalid number")
            }
            return value
        }

        private mutating func identifier() -> String {
            skipWhitespace()
            let start = position
            while !atEnd, (current.isLetter || current.isNumber || current == "_") {
                position += 1
            }
            return String(characters[start ..< position])
        }

        private static func validate(
            _ name: String,
            count: Int,
            error: (String) -> ChartError
        ) throws {
            switch name {
            case "min", "max":
                if count >= 2 { return }
            case "pow", "atan2":
                if count == 2 { return }
            case "clamp":
                if count == 3 { return }
            case "sin", "cos", "tan", "asin", "acos", "atan", "sinh", "cosh", "tanh",
                 "sqrt", "abs", "floor", "ceil", "round", "exp", "ln", "log", "log10",
                 "sign", "signum":
                if count == 1 { return }
            default:
                throw error("Unknown function \(name)")
            }
            throw error("Invalid argument count for \(name)")
        }

        private mutating func enter() throws {
            depth += 1
            if depth > Self.maximumDepth { throw error("Expression is too deeply nested") }
        }

        private mutating func leave() {
            depth -= 1
        }

        private mutating func take(_ expected: Character) -> Bool {
            skipWhitespace()
            guard !atEnd, current == expected else { return false }
            position += 1
            return true
        }

        private mutating func require(_ expected: Character) throws {
            guard take(expected) else { throw error("Expected '\(expected)'") }
        }

        private mutating func skipWhitespace() {
            while !atEnd, current.isWhitespace { position += 1 }
        }

        private var current: Character { characters[position] }
        private var atEnd: Bool { position >= characters.count }

        private func error(_ message: String) -> ChartError {
            .invalidValue("\(message) at position \(position)")
        }
    }
}
