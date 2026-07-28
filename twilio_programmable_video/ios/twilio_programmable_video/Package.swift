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
        // Mirrors the CocoaPods constraint `~> 4.6` in the podspec. Twilio ships
        // TwilioVideo as a binary xcframework through this repo; tags 4.6.0-4.6.3
        // each carry a Package.swift.
        .package(url: "https://github.com/twilio/twilio-video-ios.git", "4.6.0"..<"5.0.0")
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
