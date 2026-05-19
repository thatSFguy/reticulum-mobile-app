// SPDX-License-Identifier: MIT
//
// JPEG compression ladder for LXMF image attachments. Mirrors the
// Android `ImageCompress.kt` ladder exactly so a picture picked on
// either platform fits inside the same 20 KB wire ceiling and
// produces equivalent JPEG quality decay before refusing.
//
// Wire path is the Reticulum Resource framing (SPEC §10) added in
// Phase 1; that caps at HASHMAP_MAX_LEN = 84 chunks of 433 bytes
// ≈ 35.5 KB raw payload before Token encryption + LXMF msgpack
// wrapping. A 20 KB JPEG ceiling keeps comfortable headroom for the
// encryption + container overhead and degrades gracefully on slow
// LoRa links.
//
// The receive side is defensive at a different threshold (32 KB; see
// Phase 3 in `todo.md`) so a hostile peer can't OOM us with a 10 MB
// blob even if they bypass this sender-side ceiling.

import ImageIO
import UIKit

enum ImageCompress {

    /// 20 KB ceiling per the LXMF Resource wire budget above.
    static let maxBytes: Int = 20 * 1024

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

    private static let steps: [Step] = [
        Step(maxDim: 512, quality: 0.60),
        Step(maxDim: 512, quality: 0.40),
        Step(maxDim: 384, quality: 0.25),
    ]

    /// Run [image] through the ladder. Returns the smallest JPEG ≤
    /// [maxBytes] across the three steps, or nil if the source can't
    /// be encoded OR even step 3 was still too big.
    ///
    /// The Android counterpart's "step 3 always succeeds for realistic
    /// dimensions" observation applies here too — refusal is a
    /// defensive backstop, not a code path users hit in practice.
    static func compressForLxmf(_ image: UIImage) -> Data? {
        for step in steps {
            let scaled = scale(image, maxDim: step.maxDim)
            guard let data = scaled.jpegData(compressionQuality: step.quality) else {
                continue
            }
            if data.count <= maxBytes {
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
