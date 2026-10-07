import SwiftUI

struct ContentView: View {
    @StateObject private var microphone = MicrophoneSession()
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        VStack(alignment: .leading, spacing: 20) {
            Text("Intonation Trainer").font(.largeTitle)
            Text("Microphone check").font(.title2)
            Text(microphone.message)
            if microphone.phase == .listening {
                Text("Input level: \(microphone.levelDb, specifier: "%.0f") dBFS")
                ProgressView(value: Double((microphone.levelDb + 90) / 90))
                    .accessibilityLabel("Microphone input level")
            }
            Text("Play or sing to check the input level. Pitch detection and practice modes are coming next.")
                .foregroundStyle(.secondary)
            Button(microphone.isActive ? "Stop listening" : microphone.phase == .error ? "Retry" : "Start listening") {
                if microphone.isActive { microphone.stop() } else { microphone.start() }
            }
            .buttonStyle(.borderedProminent)
            if microphone.phase == .error {
                Button("Open Settings") {
                    if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
                }
            }
            Spacer()
        }
        .padding(24)
        .onChange(of: scenePhase) { phase in
            if phase == .background { microphone.stop(message: "Listening stopped. Tap Start listening to begin again.") }
        }
    }
}
