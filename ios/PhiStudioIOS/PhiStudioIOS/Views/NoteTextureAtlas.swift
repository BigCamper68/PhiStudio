import SwiftUI
import UIKit

/// Android PhiStudio's original note skin, decoded once and shared by the
/// editor timeline and realtime preview.
struct NoteTextureAtlas: @unchecked Sendable {
    struct HoldSlices {
        var tail: UIImage
        var body: UIImage
        var head: UIImage
        var sourceWidth: CGFloat
        var tailHeight: CGFloat
        var headHeight: CGFloat
    }

    static let shared = NoteTextureAtlas()

    private let normal: [NoteType: UIImage]
    private let multi: [NoteType: UIImage]
    private let normalHold: HoldSlices?
    private let multiHold: HoldSlices?
    let hitEffects: [UIImage]

    private init() {
        let normalImages = Self.loadSet(suffix: "")
        let multiImages = Self.loadSet(suffix: "_mh")
        normal = normalImages
        multi = multiImages
        normalHold = normalImages[.hold].flatMap {
            Self.makeHoldSlices(image: $0, tailHeight: 50, headHeight: 50)
        }
        multiHold = multiImages[.hold].flatMap {
            Self.makeHoldSlices(image: $0, tailHeight: 50, headHeight: 95)
        }
        hitEffects = Self.loadHitEffects()
    }

    func image(for type: NoteType, multiHit: Bool) -> UIImage? {
        let selected = multiHit ? multi : normal
        return selected[type] ?? normal[type]
    }

    func holdSlices(multiHit: Bool) -> HoldSlices? {
        multiHit ? (multiHold ?? normalHold) : normalHold
    }

    func widthScale(multiHit: Bool) -> CGFloat {
        multiHit ? CGFloat(1_089) / CGFloat(989) : 1
    }

    private static func loadSet(suffix: String) -> [NoteType: UIImage] {
        Dictionary(uniqueKeysWithValues: NoteType.allCases.compactMap { type in
            guard let image = load("note_\(resourceName(type))\(suffix)") else { return nil }
            return (type, image)
        })
    }

    private static func resourceName(_ type: NoteType) -> String {
        switch type {
        case .tap: "click"
        case .drag: "drag"
        case .flick: "flick"
        case .hold: "hold"
        }
    }

    private static func load(_ name: String) -> UIImage? {
        if let url = Bundle.main.url(
            forResource: name,
            withExtension: "png",
            subdirectory: "Textures"
        ) ?? Bundle.main.url(forResource: name, withExtension: "png") {
            return UIImage(contentsOfFile: url.path)
        }
        return UIImage(named: name)
    }

    private static func makeHoldSlices(
        image: UIImage,
        tailHeight: CGFloat,
        headHeight: CGFloat
    ) -> HoldSlices? {
        guard let source = image.cgImage else { return nil }
        let width = CGFloat(source.width)
        let height = CGFloat(source.height)
        guard width > 0, height > tailHeight + headHeight else { return nil }

        func crop(y: CGFloat, height: CGFloat) -> UIImage? {
            let rect = CGRect(x: 0, y: y, width: width, height: height).integral
            guard let result = source.cropping(to: rect) else { return nil }
            return UIImage(cgImage: result, scale: image.scale, orientation: .up)
        }

        guard let tail = crop(y: 0, height: tailHeight),
              let body = crop(
                  y: tailHeight,
                  height: max(1, height - tailHeight - headHeight)
              ),
              let head = crop(y: height - headHeight, height: headHeight)
        else {
            return nil
        }
        return HoldSlices(
            tail: tail,
            body: body,
            head: head,
            sourceWidth: width,
            tailHeight: tailHeight,
            headHeight: headHeight
        )
    }

    private static func loadHitEffects() -> [UIImage] {
        guard let atlas = load("hit_fx"), let source = atlas.cgImage else { return [] }
        let columns = 5
        let rows = 6
        let width = source.width / columns
        let height = source.height / rows
        return (0 ..< rows).flatMap { row in
            (0 ..< columns).compactMap { column -> UIImage? in
                guard let frame = source.cropping(
                    to: CGRect(
                        x: column * width,
                        y: row * height,
                        width: width,
                        height: height
                    )
                ) else {
                    return nil
                }
                return UIImage(cgImage: frame)
            }
        }
    }
}
