import Foundation

/// Exact RPE beat notation (`whole:numerator/denominator`).
public struct BeatTime: Hashable, Comparable, Sendable, CustomStringConvertible {
    public var whole: Int
    public var numerator: Int
    public var denominator: Int

    public init(_ whole: Int = 0, _ numerator: Int = 0, _ denominator: Int = 1) {
        let safeDenominator = denominator == 0 ? 1 : denominator
        let sign: Int64 = safeDenominator < 0 ? -1 : 1
        let rawWhole = Int64(whole)
        let rawNumerator = Int64(numerator)
        let rawDenominator = Int64(safeDenominator)
        let positiveDenominator = Swift.abs(rawDenominator)

        let multiplied = rawWhole.multipliedReportingOverflow(by: rawDenominator)
        let added = multiplied.partialValue.addingReportingOverflow(rawNumerator)
        let signed = added.partialValue.multipliedReportingOverflow(by: sign)
        guard !multiplied.overflow, !added.overflow, !signed.overflow else {
            self.whole = whole
            self.numerator = 0
            self.denominator = 1
            return
        }

        let divided = Self.floorDivision(signed.partialValue, positiveDenominator)
        let common = Self.gcd(divided.remainder, positiveDenominator)
        self.whole = Int(clamping: divided.quotient)
        self.numerator = Int(clamping: divided.remainder / common)
        self.denominator = max(1, Int(clamping: positiveDenominator / common))
    }

    public static let zero = BeatTime()

    public init(json: JSONValue?) {
        guard let values = json?.arrayValue, values.count >= 3 else {
            self = .zero
            return
        }
        self.init(
            values[0].intValue ?? 0,
            values[1].intValue ?? 0,
            values[2].intValue ?? 1
        )
    }

    public var json: JSONValue {
        .array([
            .integer(Int64(whole)),
            .integer(Int64(numerator)),
            .integer(Int64(denominator)),
        ])
    }

    public var doubleValue: Double {
        Double(whole) + Double(numerator) / Double(denominator)
    }

    public var description: String {
        "\(whole):\(numerator)/\(denominator)"
    }

    public static func < (lhs: BeatTime, rhs: BeatTime) -> Bool {
        if lhs.whole != rhs.whole { return lhs.whole < rhs.whole }
        return (Int64(lhs.numerator) * Int64(rhs.denominator))
            < (Int64(rhs.numerator) * Int64(lhs.denominator))
    }

    public static func fromDouble(_ beats: Double, division: Int) -> BeatTime {
        guard beats.isFinite else { return .zero }
        let safeDivision = max(1, division)
        var whole = Int(floor(beats))
        var numerator = Int(((beats - Double(whole)) * Double(safeDivision)).rounded())
        if numerator >= safeDivision {
            whole += numerator / safeDivision
            numerator %= safeDivision
        }
        return BeatTime(whole, numerator, safeDivision)
    }

    public static func parse(_ source: String) throws -> BeatTime {
        let value = source.trimmingCharacters(in: .whitespacesAndNewlines)
        let colonParts = value.split(separator: ":", omittingEmptySubsequences: false)
        guard colonParts.count == 2 else {
            throw ChartError.invalidValue("Beat time must use whole:numerator/denominator")
        }
        let fractionParts = colonParts[1].split(separator: "/", omittingEmptySubsequences: false)
        guard fractionParts.count == 2,
              let whole = Int(colonParts[0].trimmingCharacters(in: .whitespaces)),
              let numerator = Int(fractionParts[0].trimmingCharacters(in: .whitespaces)),
              let denominator = Int(fractionParts[1].trimmingCharacters(in: .whitespaces)),
              denominator != 0
        else {
            throw ChartError.invalidValue("Beat time must use integer components")
        }
        return BeatTime(whole, numerator, denominator)
    }

    public static func parseFlexible(_ source: String) throws -> BeatTime {
        let value = source.trimmingCharacters(in: .whitespacesAndNewlines)
        if value.contains(":") || value.contains("/") {
            return try parse(value)
        }
        guard !value.isEmpty else {
            throw ChartError.invalidValue("Beat time is required")
        }

        var sign: Int64 = 1
        var digits = value
        if digits.hasPrefix("-") {
            sign = -1
            digits.removeFirst()
        } else if digits.hasPrefix("+") {
            digits.removeFirst()
        }
        let parts = digits.split(separator: ".", omittingEmptySubsequences: false)
        guard parts.count <= 2,
              parts.allSatisfy({ $0.allSatisfy(\.isNumber) }),
              !parts.allSatisfy(\.isEmpty)
        else {
            throw ChartError.invalidValue(
                "Beat time must use whole:numerator/denominator or decimal notation"
            )
        }
        let fractionalCount = parts.count == 2 ? parts[1].count : 0
        guard fractionalCount <= 9 else {
            guard let approximate = Double(value), approximate.isFinite else {
                throw ChartError.invalidValue("Beat time is outside the supported range")
            }
            return fromDouble(approximate, division: 1_000_000)
        }
        let joined = parts.map(String.init).joined()
        guard let magnitude = Int64(joined.isEmpty ? "0" : joined) else {
            throw ChartError.invalidValue("Beat time is outside the supported range")
        }
        let denominator = Self.powerOfTen(fractionalCount)
        return fromImproper(sign * magnitude, denominator)
    }

    public func adding(_ other: BeatTime) -> BeatTime {
        let left = Int64(whole) * Int64(denominator) + Int64(numerator)
        let right = Int64(other.whole) * Int64(other.denominator) + Int64(other.numerator)
        let first = left.multipliedReportingOverflow(by: Int64(other.denominator))
        let second = right.multipliedReportingOverflow(by: Int64(denominator))
        let sum = first.partialValue.addingReportingOverflow(second.partialValue)
        let divisor = Int64(denominator).multipliedReportingOverflow(by: Int64(other.denominator))
        guard !first.overflow, !second.overflow, !sum.overflow, !divisor.overflow else {
            return .fromDouble(doubleValue + other.doubleValue, division: 1_000_000)
        }
        return Self.fromImproper(sum.partialValue, divisor.partialValue)
    }

    public func subtracting(_ other: BeatTime) -> BeatTime {
        adding(BeatTime(-other.whole, -other.numerator, other.denominator))
    }

    public static func interpolate(
        from start: BeatTime,
        to end: BeatTime,
        step: Int,
        steps: Int
    ) -> BeatTime {
        guard steps > 0, step >= 0, step <= steps else { return start }
        let progress = Double(step) / Double(steps)
        return .fromDouble(
            start.doubleValue + (end.doubleValue - start.doubleValue) * progress,
            division: max(1_000_000, max(start.denominator, end.denominator))
        )
    }

    private static func fromImproper(_ numerator: Int64, _ denominator: Int64) -> BeatTime {
        guard denominator > 0 else { return .zero }
        let divided = floorDivision(numerator, denominator)
        return BeatTime(
            Int(clamping: divided.quotient),
            Int(clamping: divided.remainder),
            Int(clamping: denominator)
        )
    }

    private static func floorDivision(
        _ numerator: Int64,
        _ denominator: Int64
    ) -> (quotient: Int64, remainder: Int64) {
        var quotient = numerator / denominator
        var remainder = numerator % denominator
        if remainder < 0 {
            quotient -= 1
            remainder += denominator
        }
        return (quotient, remainder)
    }

    private static func gcd(_ first: Int64, _ second: Int64) -> Int64 {
        var left = Swift.abs(first)
        var right = Swift.abs(second)
        if left == 0 { return max(1, right) }
        while right != 0 {
            (left, right) = (right, left % right)
        }
        return max(1, left)
    }

    private static func powerOfTen(_ exponent: Int) -> Int64 {
        guard exponent > 0 else { return 1 }
        return (0 ..< exponent).reduce(Int64(1)) { value, _ in value * 10 }
    }
}
