// swift-tools-version: 5.9
import PackageDescription
let package = Package(name: "PracticeCore", products: [.library(name: "PracticeCore", targets: ["PracticeCore"])], targets: [
    .target(name: "PracticeCore", path: "Intonation_Trainer/Core"),
    .testTarget(name: "PracticeCoreTests", dependencies: ["PracticeCore"], path: "CoreTests")
])
