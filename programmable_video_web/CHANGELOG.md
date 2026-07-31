## Unreleased

- **BREAKING**: the Dart SDK constraint is now `>=3.0.0 <4.0.0` (was `>=2.12.0 <3.0.0`) and the
  Flutter constraint is `>=3.10.0` (was `>=1.20.0`). The package was already null-safe; this only
  drops support for Dart 2 toolchains.
- The public API is unchanged — verified with `dart_apitool diff` against 1.1.0.
- Internal: `analysis_options.yaml` includes `flutter_lints` instead of the discontinued `pedantic`,
  and library directives no longer carry redundant names.
- Known issue, unchanged by this release: this package does not compile against current Flutter.
  `package:js` is discontinued and `allowInterop`/`promiseToFuture` are gone, so the interop layer
  needs porting to `dart:js_interop` + `package:web`. The web implementation is still commented out
  in `twilio_programmable_video`'s pubspec, so it does not affect the mobile plugin.

## 1.1.0

- Fixed some small annotation errors in the Web implementation.
- Implemented `VideoRenderMode` as required by the platform interface. For backwards compatibility, it defaults to `VideoRenderMode.BALANCED`.

## 1.0.1

- Stop video and audio tracks on disconnect.

## 1.0.0

- Released web to stable.

## 1.0.0-alpha.1

- Initial pre-release of the web implementation.
