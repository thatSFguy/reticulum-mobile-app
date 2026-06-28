import AVFoundation
import Combine
import Shared

/// Decoded voice clip ready for [VoicePlayer.play].
struct DecodedVoice {
    let pcm: Data
    let sampleRate: Double
    let channels: Int
}

// Voice clips on iOS: AVFoundation can't read/write Opus, so these capture
// and play raw PCM and hand it to the Kotlin Opus codec (OpusCodecKt). The
// recorder produces 48 kHz mono interleaved Int16 (what OpusCodec.encode
// expects); the player consumes the Int16 PCM that OpusCodec.decode returns.
//
// All AVAudioEngine work needs a real device + mic to verify; it can't be
// unit-tested or run in the simulator meaningfully.

/// Captures the microphone to 48 kHz mono Int16 PCM. Drives a tap on the
/// input node and resamples each buffer to the target format.
@MainActor
final class VoiceRecorder: ObservableObject {
    @Published private(set) var isRecording = false
    @Published private(set) var elapsed: TimeInterval = 0

    /// Hard clip-length cap (mirrors the decode-side bomb guard intent).
    let maxDuration: TimeInterval = 120

    private let engine = AVAudioEngine()
    private var sink: PcmSink?
    private var startTime: Date?
    private var timer: Timer?

    private let targetFormat = AVAudioFormat(
        commonFormat: .pcmFormatInt16, sampleRate: 48000, channels: 1, interleaved: true
    )!

    /// Converts + accumulates tap buffers on the realtime audio thread.
    /// A tap's AVAudioPCMBuffer is only valid during the callback, so the
    /// conversion happens synchronously here (never hopped to another
    /// thread/actor). AVAudioConverter isn't thread-safe but the tap is its
    /// only, serial, caller; the accumulated Data is lock-guarded for the
    /// main-actor drain on stop().
    private final class PcmSink {
        private let lock = NSLock()
        private var pcm = Data()
        private let converter: AVAudioConverter
        private let target: AVAudioFormat

        init?(from: AVAudioFormat, to: AVAudioFormat) {
            guard let c = AVAudioConverter(from: from, to: to) else { return nil }
            converter = c
            target = to
        }

        func consume(_ buffer: AVAudioPCMBuffer) {
            let ratio = target.sampleRate / buffer.format.sampleRate
            let cap = AVAudioFrameCount(Double(buffer.frameLength) * ratio) + 1024
            guard let out = AVAudioPCMBuffer(pcmFormat: target, frameCapacity: cap) else { return }
            var err: NSError?
            var fed = false
            converter.convert(to: out, error: &err) { _, status in
                if fed { status.pointee = .noDataNow; return nil }
                fed = true; status.pointee = .haveData; return buffer
            }
            guard err == nil, out.frameLength > 0, let ch = out.int16ChannelData else { return }
            let bytes = Data(bytes: ch[0], count: Int(out.frameLength) * MemoryLayout<Int16>.size)
            lock.lock(); pcm.append(bytes); lock.unlock()
        }

        func drain() -> Data { lock.lock(); defer { lock.unlock() }; return pcm }
    }

    /// Request mic permission (iOS 17 API). Completion on the main actor.
    static func requestPermission(_ done: @escaping (Bool) -> Void) {
        AVAudioApplication.requestRecordPermission { granted in
            DispatchQueue.main.async { done(granted) }
        }
    }

    func start() throws {
        let session = AVAudioSession.sharedInstance()
        try session.setCategory(.playAndRecord, mode: .default, options: [.defaultToSpeaker, .allowBluetooth])
        try session.setActive(true)

        let input = engine.inputNode
        let hwFormat = input.inputFormat(forBus: 0)
        guard let sink = PcmSink(from: hwFormat, to: targetFormat) else {
            throw NSError(domain: "VoiceRecorder", code: 1,
                          userInfo: [NSLocalizedDescriptionKey: "Could not create audio converter"])
        }
        self.sink = sink
        // Tap runs on the realtime audio thread; convert synchronously
        // (the buffer is only valid for this callback).
        input.installTap(onBus: 0, bufferSize: 2048, format: hwFormat) { buffer, _ in
            sink.consume(buffer)
        }
        engine.prepare()
        try engine.start()

        isRecording = true
        startTime = Date()
        timer = Timer.scheduledTimer(withTimeInterval: 0.1, repeats: true) { [weak self] _ in
            Task { @MainActor in
                guard let self, let s = self.startTime else { return }
                self.elapsed = Date().timeIntervalSince(s)
                if self.elapsed >= self.maxDuration { _ = self.stop() }
            }
        }
    }

    /// Stop and return the captured PCM (empty if nothing was recorded).
    @discardableResult
    func stop() -> Data {
        guard isRecording else { return sink?.drain() ?? Data() }
        engine.inputNode.removeTap(onBus: 0)
        engine.stop()
        timer?.invalidate(); timer = nil
        isRecording = false
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        return sink?.drain() ?? Data()
    }

    func cancel() {
        _ = stop()
        sink = nil
        elapsed = 0
    }
}

/// Plays 48 kHz Int16 PCM (as produced by OpusCodec.decode). Converts to
/// Float32 for the engine mixer and reports which message is playing so the
/// bubble can show a stop icon.
@MainActor
final class VoicePlayer: ObservableObject {
    @Published private(set) var playingId: Int64? = nil

    private let engine = AVAudioEngine()
    private let player = AVAudioPlayerNode()
    private var attached = false

    func play(pcm data: Data, sampleRate: Double, channelCount: Int, id: Int64) {
        stop()
        let channels = AVAudioChannelCount(max(1, channelCount))
        let bytesPerFrame = 2 * Int(channels)
        let frames = AVAudioFrameCount(data.count / max(bytesPerFrame, 1))
        guard frames > 0,
              let inFormat = AVAudioFormat(commonFormat: .pcmFormatInt16, sampleRate: sampleRate, channels: channels, interleaved: true),
              let playFormat = AVAudioFormat(standardFormatWithSampleRate: sampleRate, channels: channels),
              let inBuf = AVAudioPCMBuffer(pcmFormat: inFormat, frameCapacity: frames),
              let outBuf = AVAudioPCMBuffer(pcmFormat: playFormat, frameCapacity: frames)
        else { return }

        inBuf.frameLength = frames
        data.withUnsafeBytes { raw in
            if let base = raw.baseAddress { memcpy(inBuf.int16ChannelData![0], base, data.count) }
        }
        guard let conv = AVAudioConverter(from: inFormat, to: playFormat) else { return }
        var err: NSError?
        var fed = false
        conv.convert(to: outBuf, error: &err) { _, status in
            if fed { status.pointee = .noDataNow; return nil }
            fed = true; status.pointee = .haveData; return inBuf
        }
        guard err == nil else { return }

        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .default)
        try? AVAudioSession.sharedInstance().setActive(true)

        if !attached { engine.attach(player); attached = true }
        engine.connect(player, to: engine.mainMixerNode, format: playFormat)
        do { try engine.start() } catch { return }

        playingId = id
        player.scheduleBuffer(outBuf, at: nil, options: []) { [weak self] in
            Task { @MainActor in if self?.playingId == id { self?.stop() } }
        }
        player.play()
    }

    func stop() {
        if player.isPlaying { player.stop() }
        if engine.isRunning { engine.stop() }
        playingId = nil
    }
}
