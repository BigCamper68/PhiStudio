import Foundation

/// RPE easing identifiers used by current RPE charts and renderers.
public enum Easing {
    public static let minimumType = 1
    public static let maximumType = 29
    private static let epsilon = 1.0e-12
    private static let titles = [
        "01 · Linear",
        "02 · Out Sine",
        "03 · In Sine",
        "04 · Out Quad",
        "05 · In Quad",
        "06 · In Out Sine",
        "07 · In Out Quad",
        "08 · Out Cubic",
        "09 · In Cubic",
        "10 · Out Quart",
        "11 · In Quart",
        "12 · In Out Cubic",
        "13 · In Out Quart",
        "14 · Out Quint",
        "15 · In Quint",
        "16 · Out Expo",
        "17 · In Expo",
        "18 · Out Circ",
        "19 · In Circ",
        "20 · Out Back",
        "21 · In Back",
        "22 · In Out Circ",
        "23 · In Out Back",
        "24 · Out Elastic",
        "25 · In Elastic",
        "26 · Out Bounce",
        "27 · In Bounce",
        "28 · In Out Bounce",
        "29 · In Out Elastic",
    ]

    public static func title(for type: Int) -> String {
        guard (minimumType ... maximumType).contains(type) else {
            return "Unknown \(type)"
        }
        return titles[type - minimumType]
    }

    public static func apply(_ type: Int, _ input: Double) -> Double {
        let t = clamp(input)
        switch type {
        case 2: return sin(t * .pi / 2)
        case 3: return 1 - cos(t * .pi / 2)
        case 4: return 1 - pow(1 - t, 2)
        case 5: return t * t
        case 6: return (1 - cos(.pi * t)) / 2
        case 7:
            return t < 0.5 ? 2 * t * t : 1 - pow(-2 * t + 2, 2) / 2
        case 8: return 1 - pow(1 - t, 3)
        case 9: return t * t * t
        case 10: return 1 - pow(1 - t, 4)
        case 11: return pow(t, 4)
        case 12:
            return t < 0.5 ? 4 * t * t * t : 1 - pow(-2 * t + 2, 3) / 2
        case 13:
            return t < 0.5 ? 8 * pow(t, 4) : 1 - pow(-2 * t + 2, 4) / 2
        case 14: return 1 - pow(1 - t, 5)
        case 15: return pow(t, 5)
        case 16: return t == 1 ? 1 : 1 - pow(2, -10 * t)
        case 17: return t == 0 ? 0 : pow(2, 10 * t - 10)
        case 18: return sqrt(max(0, 1 - pow(t - 1, 2)))
        case 19: return 1 - sqrt(max(0, 1 - t * t))
        case 20:
            let c1 = 1.70158
            return 1 + (c1 + 1) * pow(t - 1, 3) + c1 * pow(t - 1, 2)
        case 21:
            let c1 = 1.70158
            return (c1 + 1) * t * t * t - c1 * t * t
        case 22:
            return t < 0.5
                ? (1 - sqrt(max(0, 1 - pow(2 * t, 2)))) / 2
                : (sqrt(max(0, 1 - pow(-2 * t + 2, 2))) + 1) / 2
        case 23:
            let c2 = 1.70158 * 1.525
            return t < 0.5
                ? pow(2 * t, 2) * ((c2 + 1) * 2 * t - c2) / 2
                : (pow(2 * t - 2, 2) * ((c2 + 1) * (2 * t - 2) + c2) + 2) / 2
        case 24:
            let c4 = 2 * Double.pi / 3
            if t == 0 || t == 1 { return t }
            return pow(2, -10 * t) * sin((t * 10 - 0.75) * c4) + 1
        case 25:
            return inElastic(t)
        case 26:
            return outBounce(t)
        case 27:
            return 1 - outBounce(1 - t)
        case 28:
            return t < 0.5
                ? (1 - outBounce(1 - 2 * t)) / 2
                : (1 + outBounce(2 * t - 1)) / 2
        case 29:
            if t == 0 || t == 1 { return t }
            let doubled = t * 2
            return doubled < 1 ? inElastic(doubled) / 2 : 1 - inElastic(2 - doubled) / 2
        default:
            return t
        }
    }

    public static func applyWindowed(
        _ type: Int,
        _ input: Double,
        left: Double,
        right: Double
    ) -> Double {
        let t = clamp(input)
        let bounds = validBounds(left, right)
        let start = apply(type, bounds.left)
        let end = apply(type, bounds.right)
        let value = apply(type, bounds.left + (bounds.right - bounds.left) * t)
        return normalizeWindow(t, start: start, end: end, value: value)
    }

    public static func integral(_ type: Int, _ input: Double) -> Double {
        let t = clamp(input)
        switch type {
        case 2: return outIntegral(.sine, t)
        case 3: return inIntegral(.sine, t)
        case 4: return outIntegral(.quad, t)
        case 5: return inIntegral(.quad, t)
        case 6: return inOutIntegral(.sine, t)
        case 7: return inOutIntegral(.quad, t)
        case 8: return outIntegral(.cubic, t)
        case 9: return inIntegral(.cubic, t)
        case 10: return outIntegral(.quart, t)
        case 11: return inIntegral(.quart, t)
        case 12: return inOutIntegral(.cubic, t)
        case 13: return inOutIntegral(.quart, t)
        case 14: return outIntegral(.quint, t)
        case 15: return inIntegral(.quint, t)
        case 16: return outIntegral(.expo, t)
        case 17: return inIntegral(.expo, t)
        case 18: return outIntegral(.circ, t)
        case 19: return inIntegral(.circ, t)
        case 20: return outIntegral(.back, t)
        case 21: return inIntegral(.back, t)
        case 22: return inOutIntegral(.circ, t)
        case 23: return inOutIntegral(.back, t)
        case 24: return outIntegral(.elastic, t)
        case 25: return inIntegral(.elastic, t)
        case 26: return outIntegral(.bounce, t)
        case 27: return inIntegral(.bounce, t)
        case 28: return inOutIntegral(.bounce, t)
        case 29: return inOutIntegral(.elastic, t)
        default: return t * t / 2
        }
    }

    public static func integralWindowed(
        _ type: Int,
        _ input: Double,
        left: Double,
        right: Double
    ) -> Double {
        let t = clamp(input)
        let bounds = validBounds(left, right)
        let span = bounds.right - bounds.left
        let startValue = apply(type, bounds.left)
        let endValue = apply(type, bounds.right)
        let valueSpan = endValue - startValue
        guard valueSpan.isFinite, abs(valueSpan) >= epsilon, span >= epsilon else {
            return t * t / 2
        }
        let x = bounds.left + span * t
        let area = integral(type, x) - integral(type, bounds.left) - startValue * span * t
        let result = area / (span * valueSpan)
        return result.isFinite ? result : t * t / 2
    }

    public static func cubicBezier(
        _ input: Double,
        x1: Double,
        y1: Double,
        x2: Double,
        y2: Double
    ) -> Double {
        let x = clamp(input)
        if x == 0 || x == 1 { return x }
        let controlX1 = clamp(x1)
        let controlX2 = clamp(x2)
        var parameter = x
        for _ in 0 ..< 8 {
            let error = cubic(parameter, controlX1, controlX2) - x
            if abs(error) < 1.0e-7 { break }
            let derivative = cubicDerivative(parameter, controlX1, controlX2)
            if abs(derivative) < 1.0e-7 { break }
            let candidate = parameter - error / derivative
            if !(0 ... 1).contains(candidate) { break }
            parameter = candidate
        }
        var low = 0.0
        var high = 1.0
        for _ in 0 ..< 18 {
            let currentX = cubic(parameter, controlX1, controlX2)
            if abs(currentX - x) < 1.0e-7 { break }
            if currentX < x { low = parameter } else { high = parameter }
            parameter = (low + high) / 2
        }
        return cubic(parameter, y1, y2)
    }

    public static func cubicBezierWindowed(
        _ input: Double,
        left: Double,
        right: Double,
        points: [Double]
    ) -> Double {
        let values = points + Array(repeating: 0, count: max(0, 4 - points.count))
        let t = clamp(input)
        let bounds = validBounds(left, right)
        let start = cubicBezier(
            bounds.left,
            x1: values[0],
            y1: values[1],
            x2: values[2],
            y2: values[3]
        )
        let end = cubicBezier(
            bounds.right,
            x1: values[0],
            y1: values[1],
            x2: values[2],
            y2: values[3]
        )
        let value = cubicBezier(
            bounds.left + (bounds.right - bounds.left) * t,
            x1: values[0],
            y1: values[1],
            x2: values[2],
            y2: values[3]
        )
        return normalizeWindow(t, start: start, end: end, value: value)
    }

    public static func integralCubicBezierWindowed(
        _ input: Double,
        left: Double,
        right: Double,
        points: [Double]
    ) -> Double {
        let x = clamp(input)
        if x == 0 { return 0 }
        let node = 0.7745966692414834
        let radius = x / 2
        let first = cubicBezierWindowed(
            radius * (1 - node),
            left: left,
            right: right,
            points: points
        )
        let middle = cubicBezierWindowed(radius, left: left, right: right, points: points)
        let last = cubicBezierWindowed(
            radius * (1 + node),
            left: left,
            right: right,
            points: points
        )
        return radius * (5.0 / 9 * first + 8.0 / 9 * middle + 5.0 / 9 * last)
    }

    private enum Family {
        case sine, quad, cubic, quart, quint, expo, circ, back, elastic, bounce
    }

    private static func inIntegral(_ family: Family, _ x: Double) -> Double {
        switch family {
        case .sine:
            return x - sin(x * .pi / 2) * 2 / .pi
        case .quad:
            return pow(x, 3) / 3
        case .cubic:
            return pow(x, 4) / 4
        case .quart:
            return pow(x, 5) / 5
        case .quint:
            return pow(x, 6) / 6
        case .expo:
            return (pow(2, 10 * x - 10) - pow(2, -10)) / (10 * log(2))
        case .circ:
            return x - 0.5 * (x * sqrt(max(0, 1 - x * x)) + asin(x))
        case .back:
            let c1 = 1.70158
            return ((c1 + 1) * x / 4 - c1 / 3) * x * x * x
        case .elastic:
            return elasticAntiderivative(x) - elasticAntiderivative(0)
        case .bounce:
            return x - bounceAntiderivative(1) + bounceAntiderivative(1 - x)
        }
    }

    private static func outIntegral(_ family: Family, _ x: Double) -> Double {
        x + inIntegral(family, 1 - x) - inIntegral(family, 1)
    }

    private static func inOutIntegral(_ family: Family, _ x: Double) -> Double {
        let doubled = x * 2
        return doubled < 1
            ? inIntegral(family, doubled) / 4
            : x - 0.5 + inIntegral(family, 2 - doubled) / 4
    }

    private static func normalizeWindow(
        _ t: Double,
        start: Double,
        end: Double,
        value: Double
    ) -> Double {
        let span = end - start
        guard value.isFinite, span.isFinite, abs(span) >= epsilon else { return t }
        if t == 0 || t == 1 { return t }
        return (value - start) / span
    }

    private static func validBounds(
        _ left: Double,
        _ right: Double
    ) -> (left: Double, right: Double) {
        guard left.isFinite, right.isFinite, clamp(right) >= clamp(left) else {
            return (0, 1)
        }
        return (clamp(left), clamp(right))
    }

    private static func elasticAntiderivative(_ x: Double) -> Double {
        let c4 = 2 * Double.pi / 3
        let a = log(2.0)
        let u = 10 * x - 10
        let v = (x * 10 - 10.75) * c4
        return -pow(2, u) / (10 * (a * a + c4 * c4))
            * (a * sin(v) - c4 * cos(v))
    }

    private static func bounceAntiderivative(_ x: Double) -> Double {
        let n1 = 7.5625
        let d1 = 2.75
        let end1 = 1 / d1
        let end2 = 2 / d1
        let end3 = 2.5 / d1
        let value1 = n1 / 3 * pow(end1, 3)
        let c2 = value1 - (n1 / 3 * pow(end1 - 1.5 / d1, 3) + 0.75 * end1)
        let value2 = n1 / 3 * pow(end2 - 1.5 / d1, 3) + 0.75 * end2 + c2
        let c3 = value2 - (n1 / 3 * pow(end2 - 2.25 / d1, 3) + 0.9375 * end2)
        let value3 = n1 / 3 * pow(end3 - 2.25 / d1, 3) + 0.9375 * end3 + c3
        let c4 = value3 - (n1 / 3 * pow(end3 - 2.625 / d1, 3) + 0.984375 * end3)
        if x < end1 { return n1 / 3 * pow(x, 3) }
        if x < end2 { return n1 / 3 * pow(x - 1.5 / d1, 3) + 0.75 * x + c2 }
        if x < end3 { return n1 / 3 * pow(x - 2.25 / d1, 3) + 0.9375 * x + c3 }
        return n1 / 3 * pow(x - 2.625 / d1, 3) + 0.984375 * x + c4
    }

    private static func cubic(_ t: Double, _ control1: Double, _ control2: Double) -> Double {
        let inverse = 1 - t
        return 3 * inverse * inverse * t * control1
            + 3 * inverse * t * t * control2
            + t * t * t
    }

    private static func cubicDerivative(
        _ t: Double,
        _ control1: Double,
        _ control2: Double
    ) -> Double {
        let inverse = 1 - t
        return 3 * inverse * inverse * control1
            + 6 * inverse * t * (control2 - control1)
            + 3 * t * t * (1 - control2)
    }

    private static func outBounce(_ input: Double) -> Double {
        var t = input
        let n1 = 7.5625
        let d1 = 2.75
        if t < 1 / d1 { return n1 * t * t }
        if t < 2 / d1 {
            t -= 1.5 / d1
            return n1 * t * t + 0.75
        }
        if t < 2.5 / d1 {
            t -= 2.25 / d1
            return n1 * t * t + 0.9375
        }
        t -= 2.625 / d1
        return n1 * t * t + 0.984375
    }

    private static func inElastic(_ t: Double) -> Double {
        if t == 0 || t == 1 { return t }
        let c4 = 2 * Double.pi / 3
        return -pow(2, 10 * t - 10) * sin((t * 10 - 10.75) * c4)
    }

    private static func clamp(_ value: Double) -> Double {
        value.isFinite ? min(1, max(0, value)) : 0
    }
}
