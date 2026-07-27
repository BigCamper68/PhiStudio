import Foundation
import ImageIO
import UIKit

/// A bounded, predecoded judge-line image. Decode work stays out of the Canvas callback so
/// animated custom objects do not stall playback.
public struct PreviewLineTexture: @unchecked Sendable {
    public let pixelSize: CGSize
    private let frames: [UIImage]
    private let frameEndTimes: [TimeInterval]
    public let duration: TimeInterval

    fileprivate init(
        pixelSize: CGSize,
        frames: [UIImage],
        frameDurations: [TimeInterval]
    ) {
        self.pixelSize = pixelSize
        self.frames = frames
        var elapsed = 0.0
        frameEndTimes = frameDurations.map {
            elapsed += max(0.011, $0)
            return elapsed
        }
        duration = elapsed
    }

    public var isAnimated: Bool {
        frames.count > 1 && duration > 0
    }

    public func image(
        progress: Double,
        chartTimeMilliseconds: Int64,
        gifEnabled: Bool,
        gifControlled: Bool,
        gifAnchorTimeMilliseconds: Int64
    ) -> UIImage? {
        guard let first = frames.first else { return nil }
        guard isAnimated else { return first }

        var resolvedProgress = gifEnabled && progress.isFinite ? progress : 0
        if gifEnabled, !gifControlled {
            resolvedProgress += Double(chartTimeMilliseconds - gifAnchorTimeMilliseconds)
                / max(1, duration * 1_000)
        }
        guard resolvedProgress.isFinite else { return first }
        if resolvedProgress < 0 { resolvedProgress = 0 }
        resolvedProgress.formTruncatingRemainder(dividingBy: 1)
        let target = resolvedProgress * duration
        var lower = 0
        var upper = frameEndTimes.count
        while lower < upper {
            let middle = lower + (upper - lower) / 2
            if target < frameEndTimes[middle] {
                upper = middle
            } else {
                lower = middle + 1
            }
        }
        let index = min(lower, frames.count - 1)
        return frames[index]
    }
}

public enum PreviewLineTextureLoader {
    private static let maximumTextures = 32
    private static let maximumFileBytes = 32 * 1_024 * 1_024
    private static let maximumTexturePixels = 4_000_000
    private static let maximumStaticPixels = 12_000_000
    private static let maximumDecodedFramePixels = 24_000_000
    private static let maximumGIFFrames = 120

    public static func load(
        workspaceURL: URL,
        textureNames: [String]
    ) -> [String: PreviewLineTexture] {
        let base = workspaceURL.standardizedFileURL
        let names = Array(Set(textureNames.compactMap { normalizedName($0) }))
            .sorted()
            .prefix(maximumTextures)
        var result: [String: PreviewLineTexture] = [:]
        var staticPixels = 0
        var decodedFramePixels = 0

        for name in names {
            guard let url = resolvedURL(name, inside: base),
                  let fileSize = try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize,
                  fileSize > 0,
                  fileSize <= maximumFileBytes,
                  let source = CGImageSourceCreateWithURL(url as CFURL, nil),
                  let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil)
                    as NSDictionary?,
                  let width = (properties[kCGImagePropertyPixelWidth] as? NSNumber)?.intValue,
                  let height = (properties[kCGImagePropertyPixelHeight] as? NSNumber)?.intValue,
                  width > 0,
                  height > 0
            else {
                continue
            }
            let (pixels, overflow) = width.multipliedReportingOverflow(by: height)
            guard !overflow,
                  pixels > 0,
                  pixels <= maximumTexturePixels,
                  staticPixels + pixels <= maximumStaticPixels
            else {
                continue
            }

            let sourceCount = max(1, CGImageSourceGetCount(source))
            let remainingFramePixels = maximumDecodedFramePixels - decodedFramePixels
            guard remainingFramePixels >= pixels else { break }
            let frameBudget = max(
                1,
                min(maximumGIFFrames, remainingFramePixels / pixels)
            )
            let selectedCount = min(sourceCount, frameBudget)
            let selectedIndices = (0 ..< selectedCount).map {
                min(sourceCount - 1, Int(Double($0) * Double(sourceCount) / Double(selectedCount)))
            }
            let decodeOptions = [
                kCGImageSourceShouldCacheImmediately: true,
            ] as CFDictionary
            var images: [UIImage] = []
            var durations: [TimeInterval] = []
            for position in selectedIndices.indices {
                let sourceIndex = selectedIndices[position]
                let next = position + 1 < selectedIndices.count
                    ? selectedIndices[position + 1]
                    : sourceCount
                guard let image = CGImageSourceCreateImageAtIndex(
                    source,
                    sourceIndex,
                    decodeOptions
                ) else {
                    continue
                }
                images.append(UIImage(cgImage: image, scale: 1, orientation: .up))
                durations.append(
                    (sourceIndex ..< max(sourceIndex + 1, next)).reduce(0) {
                        $0 + frameDuration(source, index: $1)
                    }
                )
            }
            guard !images.isEmpty else { continue }
            result[name] = PreviewLineTexture(
                pixelSize: CGSize(width: CGFloat(width), height: CGFloat(height)),
                frames: images,
                frameDurations: durations
            )
            staticPixels += pixels
            decodedFramePixels += pixels * images.count
        }
        return result
    }

    public static func normalizedName(_ value: String) -> String? {
        var trimmed = value
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: "\\", with: "/")
        while trimmed.hasPrefix("./") {
            trimmed.removeFirst(2)
        }
        guard !trimmed.isEmpty,
              trimmed.caseInsensitiveCompare("line.png") != .orderedSame,
              !trimmed.contains("\0"),
              !trimmed.hasPrefix("/"),
              !(trimmed.count >= 2
                  && trimmed[trimmed.index(after: trimmed.startIndex)] == ":")
        else {
            return nil
        }
        let components = trimmed.split(separator: "/", omittingEmptySubsequences: false)
        guard !components.isEmpty,
              components.allSatisfy({ !$0.isEmpty && $0 != "." && $0 != ".." })
        else {
            return nil
        }
        return components.joined(separator: "/")
    }

    private static func resolvedURL(_ name: String, inside workspace: URL) -> URL? {
        let root = workspace.resolvingSymlinksInPath().standardizedFileURL
        let candidate = root.appendingPathComponent(name)
            .resolvingSymlinksInPath()
            .standardizedFileURL
        let prefix = root.path.hasSuffix("/") ? root.path : "\(root.path)/"
        guard candidate.path.hasPrefix(prefix) else { return nil }
        var isDirectory: ObjCBool = false
        guard FileManager.default.fileExists(
            atPath: candidate.path,
            isDirectory: &isDirectory
        ), !isDirectory.boolValue else {
            return nil
        }
        return candidate
    }

    private static func frameDuration(
        _ source: CGImageSource,
        index: Int
    ) -> TimeInterval {
        guard let properties = CGImageSourceCopyPropertiesAtIndex(source, index, nil)
                as NSDictionary?,
              let gif = properties[kCGImagePropertyGIFDictionary] as? NSDictionary
        else {
            return 0.1
        }
        let unclamped = (gif[kCGImagePropertyGIFUnclampedDelayTime] as? NSNumber)?
            .doubleValue
        let clamped = (gif[kCGImagePropertyGIFDelayTime] as? NSNumber)?.doubleValue
        let value = unclamped ?? clamped ?? 0.1
        return value.isFinite && value >= 0.011 ? value : 0.1
    }
}
