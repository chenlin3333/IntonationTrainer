//
//  ContentView.swift
//  Intonation Trainer
//
//  Created by Chenyang Lin on 10/5/26.
//

import SwiftUI
import AudioKit
import AVFoundation

struct ContentView: View {
    @StateObject var audioManager = AudioManager()

    var body: some View {
        VStack {
            Text("Pitch: \(audioManager.tracker.frequency, specifier: "%.2f") Hz")
                .font(.largeTitle)
                .padding()
        }
    }
}

@main
struct IntonationTrainerApp: App {
    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}

