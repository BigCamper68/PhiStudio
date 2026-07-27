import SwiftUI

/// Live visualization matching PhiStudio's easing preview: event time runs bottom-to-top and
/// eased output runs left-to-right.
struct EasingPreviewView: View {
    var type: Int
    var left = 0.0
    var right = 1.0
    var usesBezier = false
    var bezierPoints = [0.0, 0.0, 1.0, 1.0]

    var body: some View {
        VStack(alignment: .leading, spacing: 5) {
            Text(usesBezier ? "Custom Cubic Bézier" : Easing.title(for: type))
                .font(.caption.weight(.medium))
                .foregroundStyle(.secondary)
            Canvas(opaque: false, colorMode: .nonLinear, rendersAsynchronously: false) {
                context, size in
                let rect = CGRect(origin: .zero, size: size).insetBy(dx: 10, dy: 9)
                context.fill(
                    Path(roundedRect: rect, cornerRadius: 8),
                    with: .color(Color(red: 0.075, green: 0.095, blue: 0.12))
                )
                for index in 1 ..< 4 {
                    let fraction = CGFloat(index) / 4
                    var grid = Path()
                    grid.move(to: CGPoint(x: rect.minX + rect.width * fraction, y: rect.minY))
                    grid.addLine(
                        to: CGPoint(x: rect.minX + rect.width * fraction, y: rect.maxY)
                    )
                    grid.move(to: CGPoint(x: rect.minX, y: rect.minY + rect.height * fraction))
                    grid.addLine(
                        to: CGPoint(x: rect.maxX, y: rect.minY + rect.height * fraction)
                    )
                    context.stroke(grid, with: .color(.white.opacity(0.13)), lineWidth: 1)
                }

                var curve = Path()
                for index in 0 ... 96 {
                    let progress = Double(index) / 96
                    let eased = value(at: progress)
                    let point = CGPoint(
                        x: rect.minX + CGFloat(eased) * rect.width,
                        y: rect.maxY - CGFloat(progress) * rect.height
                    )
                    if index == 0 {
                        curve.move(to: point)
                    } else {
                        curve.addLine(to: point)
                    }
                }
                var clipped = context
                clipped.clip(to: Path(rect))
                clipped.stroke(
                    curve,
                    with: .color(Color(red: 1, green: 0.59, blue: 0.15)),
                    style: StrokeStyle(lineWidth: 3, lineCap: .round, lineJoin: .round)
                )
            }
            .frame(height: 104)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(
            "Easing curve, \(usesBezier ? "Custom Cubic Bézier" : Easing.title(for: type))"
        )
    }

    private func value(at progress: Double) -> Double {
        let value: Double
        if usesBezier {
            value = Easing.cubicBezierWindowed(
                progress,
                left: left,
                right: right,
                points: bezierPoints
            )
        } else {
            value = Easing.applyWindowed(
                type,
                progress,
                left: left,
                right: right
            )
        }
        return min(1.25, max(-0.25, value.isFinite ? value : progress))
    }
}
