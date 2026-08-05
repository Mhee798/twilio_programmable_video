// swift-tools-version: 5.9
// The swift-tools-version declares the minimum version of Swift required to build this package.

import PackageDescription

let package = Package(
    name: "twilio_programmable_video",
    platforms: [
        .iOS("13.0")
    ],
    products: [
        .library(name: "twilio-programmable-video", targets: ["twilio_programmable_video"])
    ],
    dependencies: [
        // FlutterFramework is a local package that the Flutter SPM integration
        // generates next to the plugin at build time, so `../FlutterFramework`
        // resolves during a Flutter build (not a standalone `swift build`).
        // Flutter 3.44 warns if a plugin's Package.swift omits it — see
        // flutter_tools/lib/src/macos/darwin_dependency_management.dart.
        .package(name: "FlutterFramework", path: "../FlutterFramework"),
        // Mirrors the CocoaPods constraint `>= 5.11.3, < 6.0` in the podspec — the
        // podspec spells it out with two requirements rather than `~> 5.11.3`,
        // because CocoaPods reads that as `< 5.12.0` and the two build systems would
        // then disagree on which minor versions are allowed. Twilio ships TwilioVideo
        // as a binary xcframework through this repo; every 5.x tag carries a
        // Package.swift. The floor is 5.11.3 (not 5.11.0) so a resolution constrained
        // by some other dependency still cannot land below the version this plugin
        // was verified against.
        .package(url: "https://github.com/twilio/twilio-video-ios.git", "5.11.3"..<"6.0.0")
    ],
    targets: [
        .target(
            name: "twilio_programmable_video",
            dependencies: [
                .product(name: "FlutterFramework", package: "FlutterFramework"),
                .product(name: "TwilioVideo", package: "twilio-video-ios")
            ]
        )
    ]
)
