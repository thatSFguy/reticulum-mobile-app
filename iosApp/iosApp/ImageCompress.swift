// SPDX-License-Identifier: MIT
//
// JPEG compression ladders for outbound LXMF image attachments.
// Mirrors Android `ImageCompress.kt` — the user picks an
// `ImageResolutionTier` (the shared commonMain enum) in the
// compose-row "+" → Photo flow; each tier names a byte budget and
// gets a dimension+quality ladder here, and `compressForLxmf` ships
// the first rung that lands within budget.
//
// Tiers run from `full` (≤ 4 MB — a near-full-res JPEG, a TCP-path
// luxury) to `micro` (≤ 20 KB — the original LoRa-safe tier, ~4 s of
// airtime at SF7). The receive side caps independently at
// `INBOUND_ATTACHMENT_MAX_BYTES` (4 MB).

import ImageIO
import Shared
import UIKit

enum ImageCompress {

    /// Decode the image file at [path] **downsampled** so its longer
    /// edge is at most [maxPixelSize] pixels, via ImageIO's thumbnail
    /// path — the full-resolution pixels never materialise, so a
    /// multi-MB attachment can't OOM a scrolled conversation the way
    /// `UIImage(data:)` on a 4 MB JPEG (≈40 MB decoded) would.
    /// `kCGImageSourceCreateThumbnailWithTransform` bakes in the EXIF
    /// orientation so a portrait shot renders upright. Returns nil
    /// when the file is missing or undecodable.
    ///
    /// Counterpart of Android's `ImageCompress.decodeDownsampledFile`.
    /// See docs/ATTACHMENT-STORE.md §3.6.
    static func downsampledImage(path: String, maxPixelSize: Int) -> UIImage? {
        let url = URL(fileURLWithPath: path)
        guard let source = CGImageSourceCreateWithURL(url as CFURL, nil) else { return nil }
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: maxPixelSize,
        ]
        guard let cgImage = CGImageSourceCreateThumbnailAtIndex(
            source, 0, options as CFDictionary,
        ) else { return nil }
        return UIImage(cgImage: cgImage)
    }

    private struct Step {
        let maxDim: CGFloat
        let quality: CGFloat
    }

    /// Dimension/quality ladder per tier — must stay in lock-step with
    /// `tierSteps` in Android `ImageCompress.kt`. Switched on
    /// `tier.name` (the Kotlin enum entry name) because a Kotlin enum
    /// bridges to Swift as a class, not a Swift enum.
    private static func steps(for tier: ImageResolutionTier) -> [Step] {
        switch tier.name {
        case "FULL":
            return [Step(maxDim: 2560, quality: 0.92), Step(maxDim: 2560, quality: 0.80),
                    Step(maxDim: 2048, quality: 0.75), Step(maxDim: 1600, quality: 0.70),
                    Step(maxDim: 1280, quality: 0.60)]
        case "MEDIUM":
            return [Step(maxDim: 1600, quality: 0.80), Step(maxDim: 1280, quality: 0.70),
                    Step(maxDim: 1024, quality: 0.60), Step(maxDim: 1024, quality: 0.45)]
        case "SMALL":
            return [Step(maxDim: 1024, quality: 0.70), Step(maxDim: 768, quality: 0.55),
                    Step(maxDim: 640, quality: 0.45), Step(maxDim: 512, quality: 0.35)]
        default: // MICRO
            return [Step(maxDim: 512, quality: 0.60), Step(maxDim: 512, quality: 0.40),
                    Step(maxDim: 384, quality: 0.25)]
        }
    }

    /// Run [image] through the [tier]'s ladder. Returns the first JPEG
    /// within the tier's byte budget, or nil if the source can't be
    /// encoded OR even the smallest rung was still too big.
    static func compressForLxmf(_ image: UIImage, tier: ImageResolutionTier) -> Data? {
        let budget = Int(tier.byteBudget)
        for step in steps(for: tier) {
            let scaled = scale(image, maxDim: step.maxDim)
            guard let data = scaled.jpegData(compressionQuality: step.quality) else {
                continue
            }
            if data.count <= budget {
                return data
            }
        }
        return nil
    }

    /// Scale [image] so its longer edge is at most [maxDim] points,
    /// preserving aspect ratio. Returns the original image when no
    /// scaling is needed. Uses a CGSize-clamped UIGraphicsImageRenderer
    /// so the output color space matches what jpegData expects.
    private static func scale(_ image: UIImage, maxDim: CGFloat) -> UIImage {
        let size = image.size
        let longer = max(size.width, size.height)
        if longer <= maxDim {
            return image
        }
        let ratio = maxDim / longer
        let target = CGSize(
            width: max(1, floor(size.width * ratio)),
            height: max(1, floor(size.height * ratio)),
        )
        // UIGraphicsImageRenderer (iOS 10+) draws into a CG-backed
        // context using the device color space, matching the
        // PhotosPicker source's color profile closely. Setting
        // `prefersExtendedRange = false` clamps to sRGB so jpegData
        // doesn't accidentally emit a wide-gamut JPEG that a vanilla
        // Android BitmapFactory.decodeByteArray would mis-render.
        let format = UIGraphicsImageRendererFormat.default()
        format.scale = 1
        format.opaque = true
        if #available(iOS 12.0, *) {
            format.preferredRange = .standard
        }
        let renderer = UIGraphicsImageRenderer(size: target, format: format)
        return renderer.image { _ in
            image.draw(in: CGRect(origin: .zero, size: target))
        }
    }
}
