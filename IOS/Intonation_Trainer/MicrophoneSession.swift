import AVFoundation
import Combine
import Foundation

/// Shared only between the audio callback and the main-thread meter timer.
private final class InputMeter: @unchecked Sendable {
    private let lock = NSLock()
    private var value: Float = -90
    func set(_ level: Float) { lock.lock(); value = level; lock.unlock() }
    func get() -> Float { lock.lock(); defer { lock.unlock() }; return value }
}

@MainActor
final class MicrophoneSession: ObservableObject {
    enum Phase { case idle, starting, listening, error }
    @Published private(set) var phase: Phase = .idle
    @Published private(set) var message = "Ready to listen"
    @Published private(set) var levelDb: Float = -90
    var isActive: Bool { phase == .starting || phase == .listening }

    private var engine: AVAudioEngine?
    private var timer: Timer?
    private var generation = 0
    private var observers: [NSObjectProtocol] = []

    init() {
        let center = NotificationCenter.default
        observers.append(center.addObserver(forName: AVAudioSession.interruptionNotification, object: nil, queue: .main) { [weak self] notification in
            let type = notification.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt
            if type == AVAudioSession.InterruptionType.began.rawValue {
                Task { @MainActor [weak self] in self?.stop(message: "Audio interrupted. Tap Start listening to resume.") }
            }
        })
        observers.append(center.addObserver(forName: AVAudioSession.routeChangeNotification, object: nil, queue: .main) { [weak self] notification in
            let reason = notification.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt
            if reason == AVAudioSession.RouteChangeReason.oldDeviceUnavailable.rawValue || reason == AVAudioSession.RouteChangeReason.newDeviceAvailable.rawValue {
                Task { @MainActor [weak self] in
                    guard let self, self.isActive else { return }
                    self.stop(message: "Audio device changed. Tap Start listening to resume.")
                }
            }
        })
        observers.append(center.addObserver(forName: AVAudioSession.mediaServicesWereResetNotification, object: nil, queue: .main) { [weak self] _ in
            Task { @MainActor [weak self] in self?.stop(message: "Audio service restarted. Tap Start listening to resume.") }
        })
    }

    func start() {
        guard !isActive else { return }
        generation += 1
        let token = generation
        phase = .starting
        message = "Waiting for microphone permission…"
        AVAudioSession.sharedInstance().requestRecordPermission { [weak self] granted in
            Task { @MainActor [weak self] in
                guard let self, token == self.generation else { return }
                if granted { self.startCapture(token: token) }
                else { self.fail("Microphone permission is required. Allow it in Settings, then tap Retry.") }
            }
        }
    }

    private func startCapture(token: Int) {
        do {
            message = "Starting microphone…"
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.record, mode: .measurement)
            try session.setActive(true)
            if let builtIn = session.availableInputs?.first(where: { $0.portType == .builtInMic }) {
                try session.setPreferredInput(builtIn)
            }
            let newEngine = AVAudioEngine()
            let input = newEngine.inputNode
            let format = input.outputFormat(forBus: 0)
            guard format.sampleRate > 0, format.channelCount > 0 else {
                throw NSError(domain: "Microphone", code: 1, userInfo: [NSLocalizedDescriptionKey: "No microphone input is available."])
            }
            let meter = InputMeter()
            input.installTap(onBus: 0, bufferSize: 2048, format: format) { buffer, _ in
                guard let samples = buffer.floatChannelData?[0], buffer.frameLength > 0 else { return }
                var energy: Float = 0
                for index in 0..<Int(buffer.frameLength) { energy += samples[index] * samples[index] }
                let rms = sqrt(energy / Float(buffer.frameLength))
                meter.set(min(0, max(-90, 20 * log10(max(rms, 0.00003162)))))
            }
            engine = newEngine
            newEngine.prepare()
            try newEngine.start()
            phase = .listening
            message = "Listening"
            timer = Timer.scheduledTimer(withTimeInterval: 0.1, repeats: true) { [weak self] _ in
                Task { @MainActor [weak self] in
                    guard let self, self.generation == token else { return }
                    guard self.engine?.isRunning == true else {
                        self.fail("Microphone capture stopped. Tap Retry.")
                        return
                    }
                    self.levelDb = meter.get()
                }
            }
        } catch { fail("Microphone unavailable: \(error.localizedDescription) Tap Retry.") }
    }

    func stop(message: String = "Ready to listen") {
        generation += 1
        timer?.invalidate()
        timer = nil
        if let engine { engine.stop(); engine.inputNode.removeTap(onBus: 0) }
        engine = nil
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        levelDb = -90
        phase = .idle
        self.message = message
    }

    private func fail(_ message: String) { stop(); phase = .error; self.message = message }

    deinit {
        timer?.invalidate()
        for observer in observers { NotificationCenter.default.removeObserver(observer) }
        engine?.stop()
    }
}
