require "yaml"
require "ostruct"
project = OpenStruct.new YAML.load_file("../pubspec.yaml")

Pod::Spec.new do |s|
  s.name             = project.name
  s.version          = project.version
  s.summary          = 'Twilio Programmable Video Flutter package.'
  s.description      = project.description
  s.homepage         = project.homepage
  s.license          = { :type => 'MIT', :file => '../LICENSE' }
  s.author           = 'Twilio Flutter'
  s.source           = { :http => 'https://gitlab.com/twilio-flutter/programmable-video/-/tree/master/programmable_video' }
  # Sources live in the Swift Package layout (ios/twilio_programmable_video/Sources/...)
  # so the same tree serves both CocoaPods and Swift Package Manager.
  s.source_files = 'twilio_programmable_video/Sources/twilio_programmable_video/**/*.swift'

  s.dependency 'Flutter'
  s.dependency 'TwilioVideo', '~> 4.6'

  # Keep in sync with `platforms:` in twilio_programmable_video/Package.swift — SwiftPM
  # manifests are sandboxed, so they cannot read a shared value from here or pubspec.
  s.platform = :ios, '13.0'

  # Flutter.framework does not contain a i386 slice. `$(inherited)` keeps any
  # exclusion the consuming project or Podfile already set (e.g. arm64 for a
  # dependency without an arm64-simulator slice) instead of replacing it.
  s.pod_target_xcconfig = { 'DEFINES_MODULE' => 'YES', 'EXCLUDED_ARCHS[sdk=iphonesimulator*]' => '$(inherited) i386' }
  s.swift_version = '5.0'
  s.static_framework = true
end
