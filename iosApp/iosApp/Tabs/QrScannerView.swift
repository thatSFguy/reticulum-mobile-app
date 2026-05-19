// SPDX-License-Identifier: MIT
//
// AVFoundation-backed QR scanner for adding contacts. Recognises two
// input shapes, matching the Android NodesScreen Add dialog:
//
//   1. A bare 32-hex destination hash — added as a manual destination
//      (KMP `engine.addManualDestination`), label optional. The user
//      can't message this contact until an announce arrives carrying
//      the public key, but Reticulum will still index it.
//   2. An IdentityCard JSON payload (see shared/.../IdentityCard.kt) —
//      `engine.applyIdentityCard` registers the hash + public key
//      immediately, so the contact is messagable straight away.
//
// Camera permission is requested lazily on first use. If the user
// declines, we surface the system Settings deeplink so they can grant
// it without leaving the app stack.

import AVFoundation
import Shared
import SwiftUI

struct QrScannerView: UIViewControllerRepresentable {
    /// Called with the raw scanned string. The caller decides what to
    /// do with it — typically: try IdentityCard.decode, fall back to
    /// bare-hash, fall back to "unrecognised" toast.
    let onScan: (String) -> Void

    func makeUIViewController(context: Context) -> QrScannerController {
        let vc = QrScannerController()
        vc.onScan = onScan
        return vc
    }

    func updateUIViewController(_ uiViewController: QrScannerController, context: Context) {}
}

final class QrScannerController: UIViewController, AVCaptureMetadataOutputObjectsDelegate {

    var onScan: ((String) -> Void)?

    private let session = AVCaptureSession()
    private var previewLayer: AVCaptureVideoPreviewLayer?

    // Lock the scanner to portrait — letting the camera feed
    // sensor-rotate to landscape mid-scan is jarring. The preview
    // layer's connection is pinned to portrait below; these overrides
    // keep the host VC portrait too. See docs/REDESIGN.md §10.
    override var supportedInterfaceOrientations: UIInterfaceOrientationMask { .portrait }
    override var preferredInterfaceOrientationForPresentation: UIInterfaceOrientation { .portrait }
    override var shouldAutorotate: Bool { false }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        configureSession()
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        if !session.isRunning {
            // AVCaptureSession.startRunning blocks; push it off the
            // main queue so the sheet animates in smoothly.
            DispatchQueue.global(qos: .userInitiated).async { [weak self] in
                self?.session.startRunning()
            }
        }
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        if session.isRunning { session.stopRunning() }
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        previewLayer?.frame = view.bounds
    }

    private func configureSession() {
        guard let device = AVCaptureDevice.default(for: .video),
              let input = try? AVCaptureDeviceInput(device: device) else {
            showError("Camera unavailable")
            return
        }
        session.beginConfiguration()
        if session.canAddInput(input) { session.addInput(input) }
        let output = AVCaptureMetadataOutput()
        if session.canAddOutput(output) { session.addOutput(output) }
        output.setMetadataObjectsDelegate(self, queue: .main)
        // Set object types AFTER addOutput — AVCaptureMetadataOutput
        // exposes its full availableMetadataObjectTypes only once
        // attached to the session.
        if output.availableMetadataObjectTypes.contains(.qr) {
            output.metadataObjectTypes = [.qr]
        }
        session.commitConfiguration()

        let layer = AVCaptureVideoPreviewLayer(session: session)
        layer.videoGravity = .resizeAspectFill
        layer.frame = view.bounds
        // Pin the preview feed to portrait (90° rotation angle) so it
        // stays upright regardless of how the device is held.
        if let conn = layer.connection, conn.isVideoRotationAngleSupported(90) {
            conn.videoRotationAngle = 90
        }
        view.layer.addSublayer(layer)
        previewLayer = layer
    }

    func metadataOutput(
        _ output: AVCaptureMetadataOutput,
        didOutput metadataObjects: [AVMetadataObject],
        from connection: AVCaptureConnection
    ) {
        // Stop on first hit — sheet dismissal happens in the SwiftUI
        // wrapper; we don't want a stream of identical scans.
        guard let first = metadataObjects.first as? AVMetadataMachineReadableCodeObject,
              let value = first.stringValue else { return }
        session.stopRunning()
        onScan?(value)
    }

    private func showError(_ msg: String) {
        let label = UILabel()
        label.text = msg
        label.textColor = .white
        label.textAlignment = .center
        label.numberOfLines = 0
        label.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(label)
        NSLayoutConstraint.activate([
            label.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            label.centerYAnchor.constraint(equalTo: view.centerYAnchor),
            label.widthAnchor.constraint(equalTo: view.widthAnchor, multiplier: 0.8),
        ])
    }
}

// MARK: - Sheet wrapper

/// Presented modally from NodesView's Add sheet. Handles the camera
/// permission flow before showing the live preview, dispatches the
/// recognised payload back via [onPayload], and dismisses itself.
struct QrScannerSheet: View {
    let onPayload: (ScannedPayload) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var permission: PermissionState = .undetermined
    @State private var scanError: String? = nil

    enum PermissionState {
        case undetermined, granted, denied
    }

    var body: some View {
        NavigationStack {
            Group {
                switch permission {
                case .granted:
                    ZStack(alignment: .bottom) {
                        QrScannerView { raw in
                            handleScan(raw)
                        }
                        .ignoresSafeArea()
                        if let err = scanError {
                            Text(err)
                                .font(.footnote)
                                .padding(8)
                                .background(.red.opacity(0.85))
                                .foregroundStyle(.white)
                                .clipShape(RoundedRectangle(cornerRadius: 6))
                                .padding()
                        }
                    }
                case .denied:
                    VStack(spacing: 16) {
                        Image(systemName: "camera.fill")
                            .font(.system(size: 48))
                            .foregroundStyle(.secondary)
                        Text("Camera access is needed to scan QR codes.")
                            .multilineTextAlignment(.center)
                        Button("Open Settings") {
                            if let url = URL(string: UIApplication.openSettingsURLString) {
                                UIApplication.shared.open(url)
                            }
                        }
                        .buttonStyle(.borderedProminent)
                    }
                    .padding()
                case .undetermined:
                    ProgressView("Requesting camera access…")
                }
            }
            .navigationTitle("Scan QR")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
            }
            .task { await requestPermission() }
        }
    }

    private func requestPermission() async {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            permission = .granted
        case .notDetermined:
            let ok = await AVCaptureDevice.requestAccess(for: .video)
            permission = ok ? .granted : .denied
        case .denied, .restricted:
            permission = .denied
        @unknown default:
            permission = .denied
        }
    }

    /// Best-effort parse: try IdentityCard JSON, then bare-hash. Sets
    /// [scanError] for anything else so the user knows the QR was
    /// unrecognised and they can rescan.
    private func handleScan(_ raw: String) {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.first == "{" {
            // IosEngineFactoryKt.decodeIdentityCardOrNull is a top-level
            // Kotlin helper on iosMain that wraps the throwing decoder
            // in runCatching. Returning nil here means "not a valid
            // IdentityCard" — fall through to bare-hash detection.
            if let card = IosEngineFactoryKt.decodeIdentityCardOrNull(text: trimmed) {
                onPayload(.identityCard(card))
                dismiss()
                return
            }
            scanError = "QR JSON didn't parse as an IdentityCard."
            return
        }
        let hex = trimmed.lowercased().filter { $0.isHexDigit }
        if hex.count == 32 {
            onPayload(.bareHash(hex))
            dismiss()
            return
        }
        scanError = "QR isn't a 32-hex hash or IdentityCard JSON."
    }
}

enum ScannedPayload {
    case bareHash(String)
    case identityCard(IdentityCard.Payload)
}
