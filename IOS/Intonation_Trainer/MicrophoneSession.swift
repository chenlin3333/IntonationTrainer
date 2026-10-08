import AVFoundation
import Combine
import Foundation

/// Bounded handoff: the real-time tap copies PCM; analysis runs on a serial worker.
private final class AudioProcessor: @unchecked Sendable {
    private let lock=NSLock()
    private let queue=DispatchQueue(label:"intonation.analysis",qos:.userInitiated)
    private let rate:Double
    private var queued=0, inputCount=0, expectedCount=0
    private var cancelled=false
    private var frames:[AnalysisFrame]=[]
    private var pipeline:PitchPipeline?
    init(rate:Double) { self.rate=rate }
    func submit(_ samples:[Float]) {
        lock.lock()
        let start=inputCount; inputCount+=samples.count
        if cancelled || queued>=3 { lock.unlock(); return }
        queued+=1; lock.unlock()
        queue.async { [self] in
            lock.lock(); let shouldStop=cancelled; lock.unlock()
            if !shouldStop {
                if pipeline==nil || start != expectedCount {
                    let offset=Double(start)*1000/rate
                    if start != expectedCount { store(AnalysisFrame(timeMs:offset,frequency:nil,confidence:0,levelDb:-90)) }
                    pipeline=PitchPipeline(rate:rate) { [weak self] frame in
                        self?.store(AnalysisFrame(timeMs:frame.timeMs+offset,frequency:frame.frequency,confidence:frame.confidence,levelDb:frame.levelDb,onset:frame.onset))
                    }
                }
                pipeline?.accept(samples); expectedCount=start+samples.count
            }
            lock.lock(); queued-=1; lock.unlock()
        }
    }
    private func store(_ frame:AnalysisFrame) { lock.lock(); defer { lock.unlock() }; if frames.count==16 { frames.removeFirst() }; frames.append(frame) }
    func drain() -> [AnalysisFrame] { lock.lock(); defer { lock.unlock() }; let result=frames; frames=[]; return result }
    func cancel() { lock.lock(); cancelled=true; frames=[]; lock.unlock() }
}

@MainActor
final class MicrophoneSession: ObservableObject {
    enum Phase { case idle, starting, listening, error }
    @Published private(set) var phase: Phase = .idle
    @Published private(set) var message = "Ready to listen"
    @Published private(set) var levelDb: Float = -90
    var isActive: Bool { phase == .starting || phase == .listening }

    private let practiceEngine=PracticeEngine()
    @Published private(set) var practice=PracticeEngine().state
    private var processor:AudioProcessor?
    func configure(mode:PracticeMode?=nil,selected:Int?=nil,scoreIndex:Int?=nil) {
        practiceEngine.configure(mode:mode,selected:selected,scoreIndex:scoreIndex); practice=practiceEngine.state
    }
    func navigate(_ index:Int) { practiceEngine.navigate(index); practice=practiceEngine.state }
    private var engine: AVAudioEngine?
    private var timer: Timer?
    private var generation = 0
    private var lastAnalysisTime = ProcessInfo.processInfo.systemUptime
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
        practiceEngine.reset(); practice=practiceEngine.state
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
            let processor=AudioProcessor(rate:format.sampleRate)
            self.processor=processor
            input.installTap(onBus:0,bufferSize:1024,format:format) { buffer,_ in
                guard let samples=buffer.floatChannelData?[0],buffer.frameLength>0 else { return }
                processor.submit(Array(UnsafeBufferPointer(start:samples,count:Int(buffer.frameLength))))
            }
            engine = newEngine
            newEngine.prepare()
            try newEngine.start()
            lastAnalysisTime = ProcessInfo.processInfo.systemUptime
            phase = .listening
            message = "Listening"
            timer = Timer.scheduledTimer(withTimeInterval: 0.02, repeats: true) { [weak self] _ in
                Task { @MainActor [weak self] in
                    guard let self, self.generation == token else { return }
                    guard self.engine?.isRunning == true else {
                        self.fail("Microphone capture stopped. Tap Retry.")
                        return
                    }
                    let frames=processor.drain()
                    for frame in frames { self.practiceEngine.accept(frame); self.levelDb=Float(frame.levelDb) }
                    if !frames.isEmpty { self.practice=self.practiceEngine.state; self.lastAnalysisTime=ProcessInfo.processInfo.systemUptime }
                    else if ProcessInfo.processInfo.systemUptime-self.lastAnalysisTime>0.5 { self.fail("Microphone stopped delivering audio. Tap Retry.") }
                }
            }
        } catch { fail("Microphone unavailable: \(error.localizedDescription) Tap Retry.") }
    }

    func stop(message: String = "Ready to listen") {
        generation += 1
        timer?.invalidate()
        timer = nil
        processor?.cancel(); processor=nil
        practiceEngine.stop(); practice=practiceEngine.state
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
        processor?.cancel()
        engine?.stop()
    }
}
