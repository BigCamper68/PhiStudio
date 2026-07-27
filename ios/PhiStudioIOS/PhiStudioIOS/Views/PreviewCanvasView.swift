import SwiftUI
import UIKit

struct PreviewCanvasView: View {
    @Bindable var store: EditorStore
    @State private var scrubOriginBeat: Double?
    @State private var didScrub = false
    var showsStatus = true
    var togglesPlaybackOnTap = true
    var includesGameHUD = true
    var includesHitEffects = true
    var includesBackground = true
    var includesNotes = true

    private let textures = NoteTextureAtlas.shared

    var body: some View {
        GeometryReader { proxy in
            let scene = store.scene
            let settings = store.settings.value
            let currentLineIndex = store.currentLineIndex
            let lineTextures = store.lineTextures
            let viewport = PreviewProjection.fittedViewport(in: proxy.size)
            ZStack {
                if includesBackground {
                    Color.black
                    previewBackground(size: viewport.size)
                        .frame(width: viewport.width, height: viewport.height)
                }

                Canvas(opaque: false, colorMode: .nonLinear, rendersAsynchronously: false) {
                    context, size in
                    drawScene(
                        scene,
                        settings: settings,
                        currentLineIndex: currentLineIndex,
                        lineTextures: lineTextures,
                        context: context,
                        size: size
                    )
                }

                if showsStatus {
                    VStack {
                        Spacer()
                        HStack {
                            StatusPill(
                                text: "Beat " + scene.beat.formatted(
                                    .number.precision(.fractionLength(2))
                                ),
                                symbol: "metronome",
                                tint: .cyan
                            )
                            Spacer()
                            StatusPill(
                                text: "\(scene.lines.reduce(0) { $0 + $1.notes.count }) visible",
                                symbol: "music.note",
                                tint: .green
                            )
                        }
                        .padding()
                    }
                }
            }
            .contentShape(Rectangle())
            .gesture(previewGesture(height: max(1, viewport.height)))
            .allowsHitTesting(togglesPlaybackOnTap)
            .clipped()
        }
        .accessibilityLabel("Chart preview")
        .accessibilityHint(togglesPlaybackOnTap ? "Tap to play or pause." : "")
    }

    private func previewGesture(height: CGFloat) -> some Gesture {
        DragGesture(minimumDistance: 0, coordinateSpace: .local)
            .onChanged { value in
                guard togglesPlaybackOnTap else { return }
                if scrubOriginBeat == nil {
                    scrubOriginBeat = store.currentBeat
                    didScrub = false
                }
                if abs(value.translation.height) >= 6 {
                    if !didScrub {
                        didScrub = true
                        store.beginScrubbing()
                    }
                    let delta = Double(value.translation.height / height) * store.visibleBeats
                    store.setBeat(
                        max(0, (scrubOriginBeat ?? store.currentBeat) + delta),
                        seekAudio: false
                    )
                }
            }
            .onEnded { _ in
                guard togglesPlaybackOnTap, scrubOriginBeat != nil else { return }
                if didScrub {
                    store.endScrubbing()
                } else {
                    store.togglePlayback()
                }
                scrubOriginBeat = nil
                didScrub = false
            }
    }

    @ViewBuilder
    private func previewBackground(size: CGSize) -> some View {
        if let image = store.illustrationImage {
            Image(uiImage: image)
                .resizable()
                .scaledToFill()
                .frame(width: size.width, height: size.height)
                .clipped()
                .opacity(0.60)
        } else {
            Color.black
        }
        Color.black.opacity(store.settings.value.backgroundDim)
    }

    private func drawScene(
        _ scene: RenderScene,
        settings: EditorSettings,
        currentLineIndex: Int,
        lineTextures: [String: PreviewLineTexture],
        context: GraphicsContext,
        size: CGSize
    ) {
        let projection = PreviewProjection(
            viewport: PreviewProjection.fittedViewport(in: size)
        )
        let lines = settings.showOtherLines
            ? scene.lines
            : scene.lines.filter { $0.sourceIndex == currentLineIndex }
        var clippedContext = context
        clippedContext.clip(to: Path(projection.viewport))

        for line in lines {
            drawLine(
                line,
                settings: settings,
                isActive: line.sourceIndex == currentLineIndex,
                chartTimeMilliseconds: scene.chartTimeMilliseconds,
                lineTextures: lineTextures,
                context: clippedContext,
                projection: projection
            )
        }
        for line in lines {
            let center = projection.point(x: line.x, y: line.y)
            clippedContext.draw(
                Text("\(line.sourceIndex)")
                    .font(.system(size: 10, weight: .medium))
                    .foregroundColor(.white),
                at: CGPoint(
                    x: center.x,
                    y: center.y - 7
                ),
                anchor: .bottom
            )
        }
        if includesGameHUD, settings.showGameHUD {
            drawHUD(
                scene.hud,
                context: clippedContext,
                projection: projection
            )
        }
    }

    private func drawLine(
        _ line: RenderLine,
        settings: EditorSettings,
        isActive: Bool,
        chartTimeMilliseconds: Int64,
        lineTextures: [String: PreviewLineTexture],
        context: GraphicsContext,
        projection: PreviewProjection
    ) {
        let center = projection.point(x: line.x, y: line.y)
        let alpha = min(1, max(0, Double(line.alpha) / 255))
        let resolvedLineRGB = isActive ? 0x5BD3AC : line.colorRGB ?? 0xFFFFFF
        let lineColor = Color.rgb(resolvedLineRGB, alpha: alpha)
        let lineTintColor = Color.rgb(resolvedLineRGB)
        let halfLength = 4_050 * line.scaleX
        let radians = line.rotationDegrees * .pi / 180
        let left = projection.point(
            x: line.x - cos(radians) * halfLength,
            y: line.y - sin(radians) * halfLength
        )
        let right = projection.point(
            x: line.x + cos(radians) * halfLength,
            y: line.y + sin(radians) * halfLength
        )
        let rawThicknessScale = abs(line.scaleY)
        let thicknessScale = abs(rawThicknessScale - 1) > 1.0e-4
            ? rawThicknessScale * 0.76
            : rawThicknessScale
        let textureName = PreviewLineTextureLoader.normalizedName(line.textureName)
        let customTexture = textureName.flatMap { lineTextures[$0] }
        if line.paintMode {
            // A paint storyboard suppresses the default line itself.
        } else if !line.text.isEmpty {
            var textContext = context
            textContext.translateBy(x: center.x, y: center.y)
            textContext.rotate(
                by: .radians(projection.screenAngle(for: line.rotationDegrees))
            )
            textContext.scaleBy(
                x: CGFloat(line.scaleX),
                y: CGFloat(line.scaleY)
            )
            textContext.draw(
                Text(line.text)
                    .font(
                        .system(
                            size: max(12, 24 * projection.uniformScale),
                            weight: .semibold
                        )
                    )
                    .foregroundColor(lineColor),
                at: .zero,
                anchor: .center
            )
        } else if let customTexture,
                  let image = customTexture.image(
                      progress: line.gifProgress,
                      chartTimeMilliseconds: chartTimeMilliseconds,
                      gifEnabled: line.gifEnabled,
                      gifControlled: line.gifControlled,
                      gifAnchorTimeMilliseconds: line.gifAnchorTimeMilliseconds
                  )
        {
            var textureContext = context
            textureContext.translateBy(x: center.x, y: center.y)
            textureContext.rotate(
                by: .radians(projection.screenAngle(for: line.rotationDegrees))
            )
            textureContext.scaleBy(
                x: line.scaleX < 0 ? -1 : 1,
                y: line.scaleY < 0 ? -1 : 1
            )
            textureContext.opacity = alpha
            if resolvedLineRGB != 0xFFFFFF {
                textureContext.addFilter(.colorMultiply(lineTintColor))
            }
            let width = customTexture.pixelSize.width * projection.scaleX * CGFloat(abs(line.scaleX))
            let height = customTexture.pixelSize.height * projection.scaleY * CGFloat(abs(line.scaleY))
            if width >= 0.5, height >= 0.5 {
                textureContext.draw(
                    Image(uiImage: image),
                    in: CGRect(
                        x: -width / 2,
                        y: -height / 2,
                        width: width,
                        height: height
                    )
                )
            }
        } else {
            var judgeLine = Path()
            judgeLine.move(to: left)
            judgeLine.addLine(to: right)
            context.stroke(
                judgeLine,
                with: .color(lineColor),
                lineWidth: max(
                    1,
                    projection.viewport.width * CGFloat(9.0 / 1_920.0)
                        * CGFloat(settings.previewLineWidth / 1.7)
                        * CGFloat(thicknessScale)
                )
            )
        }

        if includesNotes {
            for type in [NoteType.hold, .drag, .tap, .flick] {
                for note in line.notes where note.type == type {
                    drawNote(
                        note,
                        on: line,
                        context: context,
                        projection: projection,
                        settings: settings
                    )
                }
            }
        }
        if includesHitEffects {
            for effect in line.hitEffects {
                drawHitEffect(
                    effect,
                    context: context,
                    projection: projection
                )
            }
        }
    }

    private func drawNote(
        _ note: RenderNote,
        on line: RenderLine,
        context: GraphicsContext,
        projection: PreviewProjection,
        settings: EditorSettings
    ) {
        let start = notePoint(
            note,
            distance: note.startDistance,
            on: line,
            projection: projection
        )
        let naturalWidth = max(
            12,
            projection.viewport.width * (CGFloat(989) / CGFloat(8_000))
                * CGFloat(settings.noteScale)
                * textures.widthScale(multiHit: note.isMultiHit)
        )
        let width = naturalWidth * CGFloat(note.size)
        guard width >= 0.5 else { return }
        let opacity = min(1, max(0, Double(note.alpha) / 255))

        if note.type == .hold, let slices = textures.holdSlices(multiHit: note.isMultiHit) {
            let end = notePoint(
                note,
                distance: note.endDistance,
                on: line,
                projection: projection
            )
            let deltaX = end.x - start.x
            let deltaY = end.y - start.y
            let length = CGFloat(
                hypot(Double(deltaX), Double(deltaY))
            )
            guard length >= 0.5 else { return }
            var local = context
            local.translateBy(x: start.x, y: start.y)
            local.rotate(
                by: .radians(
                    atan2(Double(deltaX), Double(-deltaY))
                )
            )
            local.opacity = opacity
            var textureContext = local
            if note.colorRGB != 0xFFFFFF {
                textureContext.addFilter(
                    .colorMultiply(Color.rgb(note.colorRGB, alpha: 1))
                )
            }
            drawHold(
                slices,
                x: 0,
                startY: 0,
                endY: -length,
                width: width,
                naturalWidth: naturalWidth,
                headVisible: note.holdHeadVisible,
                context: textureContext
            )
            if note.isFake {
                local.stroke(
                    Path(
                        roundedRect: CGRect(
                            x: -width / 2 - 2,
                            y: -length - 2,
                            width: width + 4,
                            height: length + 4
                        ),
                        cornerRadius: 4
                    ),
                    with: .color(.white),
                    lineWidth: 1.4
                )
            }
            return
        }

        guard note.holdHeadVisible,
              let texture = textures.image(for: note.type, multiHit: note.isMultiHit)
        else {
            return
        }
        let aspect = max(0.01, texture.size.width / texture.size.height)
        let height = max(3, naturalWidth / aspect)
        var local = context
        local.translateBy(x: start.x, y: start.y)
        let belowRotation = note.isAbove ? 0 : Double.pi
        local.rotate(
            by: .radians(
                projection.screenAngle(for: line.rotationDegrees) + belowRotation
            )
        )
        local.opacity = opacity
        var textureContext = local
        if note.colorRGB != 0xFFFFFF {
            textureContext.addFilter(
                .colorMultiply(Color.rgb(note.colorRGB, alpha: 1))
            )
        }
        textureContext.draw(
            Image(uiImage: texture),
            in: CGRect(
                x: -width / 2,
                y: -height / 2,
                width: width,
                height: height
            )
        )
        if note.isFake {
            local.stroke(
                Path(
                    roundedRect: CGRect(
                        x: -width / 2 - 2,
                        y: -height / 2 - 2,
                        width: width + 4,
                        height: height + 4
                    ),
                    cornerRadius: 4
                ),
                with: .color(.white),
                lineWidth: 1.4
            )
        }
    }

    private func notePoint(
        _ note: RenderNote,
        distance: Double,
        on line: RenderLine,
        projection: PreviewProjection
    ) -> CGPoint {
        let radians = line.rotationDegrees * .pi / 180
        let side = note.isAbove ? 1.0 : -1.0
        let inclineScale = note.type == .hold
            ? 1
            : 1 - sin(line.inclineDegrees * .pi / 180) * distance / 360
        let transformedX = note.x * inclineScale
        return projection.point(
            x: line.x + cos(radians) * transformedX
                - sin(radians) * distance * side,
            y: line.y + sin(radians) * transformedX
                + cos(radians) * distance * side
        )
    }

    private func drawHold(
        _ slices: NoteTextureAtlas.HoldSlices,
        x: CGFloat,
        startY: CGFloat,
        endY: CGFloat,
        width: CGFloat,
        naturalWidth: CGFloat,
        headVisible: Bool,
        context: GraphicsContext
    ) {
        let low = min(startY, endY)
        let high = max(startY, endY)
        let total = max(4, high - low)
        let tailHeight = naturalWidth * slices.tailHeight / slices.sourceWidth
        let headHeight = naturalWidth * slices.headHeight / slices.sourceWidth
        let headAtHigh = startY >= endY
        let tailRect = CGRect(
            x: x - width / 2,
            y: headAtHigh ? low : high - tailHeight,
            width: width,
            height: tailHeight
        )
        let headRect = CGRect(
            x: x - width / 2,
            y: headAtHigh ? high - headHeight : low,
            width: width,
            height: headHeight
        )
        context.draw(
            Image(uiImage: slices.body),
            in: CGRect(
                x: x - width / 2,
                y: low,
                width: width,
                height: total
            )
        )
        context.draw(Image(uiImage: slices.tail), in: tailRect)
        if headVisible {
            context.draw(Image(uiImage: slices.head), in: headRect)
        }
    }

    private func drawHitEffect(
        _ effect: RenderHitEffect,
        context: GraphicsContext,
        projection: PreviewProjection
    ) {
        let viewport = projection.viewport
        let point = projection.point(
            x: effect.x,
            y: effect.y
        )
        let progress = min(1, max(0, effect.progress))
        let diameter = max(28, viewport.width * CGFloat(1_536.0 / 8_000.0))
        let rect = CGRect(
            x: point.x - diameter / 2,
            y: point.y - diameter / 2,
            width: diameter,
            height: diameter
        )
        if !textures.hitEffects.isEmpty {
            let index = min(
                textures.hitEffects.count - 1,
                Int(progress * Double(textures.hitEffects.count))
            )
            var effectContext = context
            if effect.colorRGB != 0xFFFFFF {
                effectContext.addFilter(
                    .colorMultiply(Color.rgb(effect.colorRGB, alpha: 1))
                )
            }
            effectContext.draw(Image(uiImage: textures.hitEffects[index]), in: rect)
        }

        for index in 0 ..< 4 {
            let theta = particleFraction(
                seed: effect.seed ^ 0x6D2B_79F5,
                index: index
            ) * .pi * 2
            let distance = viewport.width / 426 * 55 * CGFloat(
                sin(progress * .pi / 2)
            )
            let particleCenter = CGPoint(
                x: point.x + CGFloat(cos(theta)) * distance,
                y: point.y + CGFloat(sin(theta)) * distance
            )
            let side = viewport.width / 426 * CGFloat(
                7 + particleFraction(seed: effect.seed, index: index) * 3
            ) * CGFloat(1 - progress * 0.35)
            let rotation = theta + .pi / 4
            var particle = Path()
            for index in 0 ..< 4 {
                let corner = rotation + Double(index) * .pi / 2
                let point = CGPoint(
                    x: particleCenter.x + CGFloat(cos(corner)) * side / 2,
                    y: particleCenter.y + CGFloat(sin(corner)) * side / 2
                )
                if index == 0 { particle.move(to: point) } else { particle.addLine(to: point) }
            }
            particle.closeSubpath()
            context.fill(
                particle,
                with: .color(Color.rgb(effect.colorRGB, alpha: (1 - progress) * 0.9))
            )
        }
    }

    private func particleFraction(seed: Int, index: Int) -> Double {
        var value = UInt32(bitPattern: Int32(truncatingIfNeeded: seed))
        value = value &+ UInt32(truncatingIfNeeded: index) &* 0x9E37_79B9
        value ^= value >> 16
        value &*= 0x7FEB_352D
        value ^= value >> 15
        value &*= 0x846C_A68B
        value ^= value >> 16
        return Double(value & 0x7FFF_FFFF) / Double(Int32.max)
    }

    private func drawHUD(
        _ hud: HUDState,
        context: GraphicsContext,
        projection: PreviewProjection
    ) {
        let viewport = projection.viewport
        let unit = max(0.5, viewport.width / 1_000)
        let marginX = viewport.width * 0.03
        let marginY = viewport.height * 0.045
        let left = viewport.minX + marginX
        let right = viewport.maxX - marginX
        let top = viewport.minY + marginY
        let bottom = viewport.maxY - marginY

        drawPauseHUD(
            hud,
            fallback: CGPoint(
                x: left + viewport.width * 0.025,
                y: top
            ),
            unit: unit,
            context: context,
            projection: projection
        )
        drawHUDText(
            hud.name,
            element: .name,
            fallback: CGPoint(x: left, y: bottom),
            anchor: .bottomLeading,
            hud: hud,
            context: context,
            projection: projection,
            font: .caption
        )
        drawHUDText(
            hud.level,
            element: .level,
            fallback: CGPoint(x: right, y: bottom),
            anchor: .bottomTrailing,
            hud: hud,
            context: context,
            projection: projection,
            font: .caption
        )
        drawHUDText(
            String(format: "%07d", hud.score),
            element: .score,
            fallback: CGPoint(x: right, y: top + 27 * unit),
            anchor: .topTrailing,
            hud: hud,
            context: context,
            projection: projection,
            font: .title3
        )
        if hud.combo >= 3 {
            drawHUDText(
                "\(hud.combo)",
                element: .comboNumber,
                fallback: CGPoint(
                    x: viewport.midX,
                    y: top + 39 * unit
                ),
                anchor: .top,
                hud: hud,
                context: context,
                projection: projection,
                font: .title
            )
            drawHUDText(
                "AUTOPLAY",
                element: .combo,
                fallback: CGPoint(
                    x: viewport.midX,
                    y: top + 61 * unit
                ),
                anchor: .top,
                hud: hud,
                context: context,
                projection: projection,
                font: .caption2
            )
        }
        let progress = min(1, max(0, CGFloat(hud.progress)))
        let barHeight = max(2, 4 * unit)
        let barY = viewport.minY + max(2, 4 * unit)
        let barTransform = hud.transforms[.bar]
        let barAlpha = barTransform.map {
            min(1, max(0, Double($0.alpha) / 255))
        } ?? 1
        guard barAlpha > 0 else { return }
        var barContext = transformedHUDContext(
            context,
            transform: barTransform,
            fallback: CGPoint(x: viewport.minX, y: barY),
            projection: projection
        )
        barContext.opacity = barAlpha
        barContext.fill(
            Path(
                CGRect(
                    x: viewport.minX,
                    y: barY,
                    width: viewport.width,
                    height: barHeight
                )
            ),
            with: .color(.white.opacity(0.22))
        )
        barContext.fill(
            Path(
                CGRect(
                    x: viewport.minX,
                    y: barY,
                    width: viewport.width * progress,
                    height: barHeight
                )
            ),
            with: .color(.white.opacity(0.68))
        )
        let markerX = viewport.minX + viewport.width * progress
        barContext.fill(
            Path(
                CGRect(
                    x: markerX - barHeight,
                    y: barY - barHeight,
                    width: barHeight * 2,
                    height: barHeight * 3
                )
            ),
            with: .color(.white)
        )
    }

    private func drawPauseHUD(
        _ hud: HUDState,
        fallback: CGPoint,
        unit: CGFloat,
        context: GraphicsContext,
        projection: PreviewProjection
    ) {
        let transform = hud.transforms[.pause]
        let alpha = transform.map {
            min(1, max(0, Double($0.alpha) / 255))
        } ?? 1
        guard alpha > 0 else { return }
        let color = Color.rgb(transform?.colorRGB ?? 0xFFFFFF, alpha: alpha)
        var local = transformedHUDContext(
            context,
            transform: transform,
            fallback: fallback,
            projection: projection
        )
        let barWidth = max(2, 7 * unit)
        let barHeight = 28 * unit
        let gap = 7 * unit
        local.fill(
            Path(
                roundedRect: CGRect(
                    x: fallback.x - gap - barWidth,
                    y: fallback.y,
                    width: barWidth,
                    height: barHeight
                ),
                cornerRadius: barWidth / 2
            ),
            with: .color(color)
        )
        local.fill(
            Path(
                roundedRect: CGRect(
                    x: fallback.x + gap,
                    y: fallback.y,
                    width: barWidth,
                    height: barHeight
                ),
                cornerRadius: barWidth / 2
            ),
            with: .color(color)
        )
    }

    private func drawHUDText(
        _ value: String,
        element: AttachedUIElement,
        fallback: CGPoint,
        anchor: UnitPoint,
        hud: HUDState,
        context: GraphicsContext,
        projection: PreviewProjection,
        font: Font
    ) {
        let transform = hud.transforms[element]
        let alpha = transform.map { min(1, max(0, Double($0.alpha) / 255)) } ?? 1
        let color = Color.rgb(transform?.colorRGB ?? 0xFFFFFF, alpha: alpha)
        var local = transformedHUDContext(
            context,
            transform: transform,
            fallback: fallback,
            projection: projection
        )
        local.draw(
            Text(value).font(font.weight(.semibold)).foregroundColor(color),
            at: fallback,
            anchor: anchor
        )
    }

    private func transformedHUDContext(
        _ context: GraphicsContext,
        transform: HUDTransform?,
        fallback: CGPoint,
        projection: PreviewProjection
    ) -> GraphicsContext {
        guard let transform else { return context }
        var local = context
        let translated = projection.point(x: transform.x, y: transform.y)
        local.translateBy(
            x: translated.x - projection.viewport.midX,
            y: translated.y - projection.viewport.midY
        )
        local.translateBy(x: fallback.x, y: fallback.y)
        local.rotate(
            by: .radians(
                projection.screenAngle(for: transform.rotationDegrees)
            )
        )
        local.scaleBy(
            x: CGFloat(transform.scaleX),
            y: CGFloat(transform.scaleY)
        )
        local.translateBy(x: -fallback.x, y: -fallback.y)
        return local
    }

}

private struct PreviewProjection {
    let viewport: CGRect
    let scaleX: CGFloat
    let scaleY: CGFloat

    init(viewport: CGRect) {
        self.viewport = viewport
        scaleX = viewport.width / 1_350
        scaleY = viewport.height / 900
    }

    static func fittedViewport(in size: CGSize) -> CGRect {
        guard size.width > 0, size.height > 0 else {
            return CGRect(origin: .zero, size: size)
        }
        let ratio: CGFloat = 1_350 / 900
        var width = size.width
        var height = width / ratio
        if height > size.height {
            height = size.height
            width = height * ratio
        }
        return CGRect(
            x: (size.width - width) / 2,
            y: (size.height - height) / 2,
            width: width,
            height: height
        )
    }

    var uniformScale: CGFloat {
        min(scaleX, scaleY)
    }

    func point(x: Double, y: Double) -> CGPoint {
        CGPoint(
            x: viewport.midX + CGFloat(x) * scaleX,
            y: viewport.midY - CGFloat(y) * scaleY
        )
    }

    func screenAngle(for rotationDegrees: Double) -> Double {
        let radians = rotationDegrees * .pi / 180
        let x = cos(radians) * Double(scaleX)
        let y = -sin(radians) * Double(scaleY)
        return atan2(y, x)
    }
}
