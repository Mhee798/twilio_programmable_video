#!/usr/bin/env bash
#
# Validates ios/twilio_programmable_video/Package.swift.
#
# The Swift Package manifest is the only build path for consumers that have Swift
# Package Manager enabled, but nothing else in the repo builds it: the example app is
# still CocoaPods, and `pod lib lint` only exercises the podspec. Without this check a
# broken manifest ships green.
#
# Two modes:
#
#   --manifest-only   No network, no Darwin toolchain — this is what CI runs on the
#                     Linux swift image. Catches manifest syntax / PackageDescription
#                     errors, a `.product(package:)` identity matching no declared
#                     dependency, a target whose source directory does not exist or
#                     holds no Swift, and a missing/renamed FlutterFramework
#                     declaration (which flutter_tools requires verbatim).
#
#   (default)         Everything above, plus full dependency resolution and a
#                     cross-check that every `.product(name:)` is actually declared by
#                     the package it names. Requires macOS: TwilioVideo ships as an
#                     iOS-only binary xcframework.
#
# Resolution needs `.package(name: "FlutterFramework", path: "../FlutterFramework")`,
# which only exists inside a real Flutter build, so the full mode stands up a stub
# sibling package modelled on the one flutter_tools generates (a single library product
# named FlutterFramework — see flutter_tools/lib/src/macos/swift_package_manager.dart,
# createFlutterFrameworkSwiftPackage).
#
# NOTE: neither mode type-checks the Swift sources. That needs an iOS SDK, i.e. a
# macOS runner with Xcode, which this repo's CI does not have.
#
set -euo pipefail

usage() {
  echo "usage: $(basename "$0") [--manifest-only]" >&2
  exit 2
}

mode=full
[[ $# -le 1 ]] || usage
case "${1-}" in
  '') ;;
  --manifest-only) mode=manifest-only ;;
  *) usage ;;
esac

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
package_dir="${script_dir}/../twilio_programmable_video"

if [[ ! -f "${package_dir}/Package.swift" ]]; then
  echo "error: ${package_dir}/Package.swift not found" >&2
  exit 1
fi

work_dir="$(mktemp -d)"
trap 'rm -rf "${work_dir}"' EXIT

if [[ "${mode}" == full ]]; then
  # Stub out the Flutter-generated sibling package so the graph resolves standalone.
  mkdir -p "${work_dir}/FlutterFramework/Sources/FlutterFramework"
  cat > "${work_dir}/FlutterFramework/Package.swift" <<'EOF'
// swift-tools-version: 5.9
import PackageDescription

// No `platforms:` on purpose: SwiftPM only rejects a dependency whose minimum is
// HIGHER than the consumer's, so leaving it unset keeps the plugin's iOS floor from
// being restated here.
let package = Package(
    name: "FlutterFramework",
    products: [.library(name: "FlutterFramework", targets: ["FlutterFramework"])],
    targets: [.target(name: "FlutterFramework")]
)
EOF
  echo 'public let flutterFrameworkStub = 0' \
    > "${work_dir}/FlutterFramework/Sources/FlutterFramework/Stub.swift"

  # Copy only what the checks read. A resolved package dir carries a ~326MB .build
  # (the extracted TwilioVideo.xcframework), and an inherited Package.resolved would
  # make `resolve` honour old pins instead of the range the manifest declares.
  check_dir="${work_dir}/twilio_programmable_video"
  mkdir -p "${check_dir}"
  cp "${package_dir}/Package.swift" "${check_dir}/"
  cp -R "${package_dir}/Sources" "${check_dir}/"
else
  # Read-only: dump the manifest in place and write nothing into the repo.
  check_dir="${package_dir}"
fi

cd "${check_dir}"

echo "==> swift package dump-package"
swift package dump-package > "${work_dir}/manifest.json"

echo "==> checking target sources and the FlutterFramework declaration"
python3 - "${work_dir}/manifest.json" "${check_dir}" <<'PY'
import json
import pathlib
import sys

manifest = json.loads(pathlib.Path(sys.argv[1]).read_text())
package_dir = pathlib.Path(sys.argv[2])

failures = []

# 1. Every target's source directory must exist and hold Swift. This is stricter than
#    SwiftPM on purpose: `swift package describe` accepts a bare `Sources/` fallback, so
#    it stays silent when `Sources/<target>` is renamed away (verified) — yet the podspec
#    globs `Sources/twilio_programmable_video/**/*.swift`, so CocoaPods would break. The
#    invariant here is that both build systems keep seeing the same tree.
for target in manifest["targets"]:
    name = target["name"]
    rel = target.get("path") or f"Sources/{name}"
    source_dir = package_dir / rel
    if not source_dir.is_dir():
        failures.append(f"target '{name}' declares sources at '{rel}', which is not a directory")
        continue
    swift_files = list(source_dir.rglob("*.swift"))
    if not swift_files:
        failures.append(f"target '{name}' source directory '{rel}' contains no .swift files")
    else:
        print(f"    ok: target '{name}' has {len(swift_files)} Swift file(s) under '{rel}'")

# 2. flutter_tools requires these two declarations verbatim and rejects a plugin
#    manifest without them. See flutter_tools/lib/src/macos/darwin_dependency_management.dart,
#    validatePluginSupportsSwiftPackageManager.
FLUTTER_FRAMEWORK = "flutterframework"
flutter_framework_failures = len(failures)

path_identities = {
    dep["identity"].lower()
    for group in manifest["dependencies"]
    for dep in group.get("fileSystem", [])
}
if FLUTTER_FRAMEWORK not in path_identities:
    failures.append(
        'no path dependency on FlutterFramework; flutter_tools requires '
        '.package(name: "FlutterFramework", path: "../FlutterFramework")'
    )

product_deps = {
    (dep["product"][0], (dep["product"][1] or "").lower())
    for target in manifest["targets"]
    for dep in target["dependencies"]
    if dep.get("product")
}
if ("FlutterFramework", FLUTTER_FRAMEWORK) not in product_deps:
    failures.append(
        'no target dependency on the FlutterFramework product; flutter_tools requires '
        '.product(name: "FlutterFramework", package: "FlutterFramework")'
    )
if len(failures) == flutter_framework_failures:
    print("    ok: FlutterFramework declared the way flutter_tools requires")

for failure in failures:
    print(f"error: {failure}", file=sys.stderr)
sys.exit(1 if failures else 0)
PY

if [[ "${mode}" == manifest-only ]]; then
  echo "==> Package.swift OK (manifest-only; product names and resolution not checked)"
  exit 0
fi

echo "==> swift package resolve"
swift package resolve

echo "==> cross-checking .product(name:) against every named package"
python3 - "${work_dir}/manifest.json" <<'PY'
import json
import pathlib
import subprocess
import sys

manifest = json.loads(pathlib.Path(sys.argv[1]).read_text())

# identity -> directory holding that package's manifest. Source-control
# dependencies land in .build/checkouts under the URL's last path component;
# path dependencies are wherever the manifest says.
package_dirs = {}
for group in manifest["dependencies"]:
    for dep in group.get("fileSystem", []):
        package_dirs[dep["identity"].lower()] = pathlib.Path(dep["path"])
checkouts = pathlib.Path(".build/checkouts")
if checkouts.is_dir():
    for entry in checkouts.iterdir():
        if entry.is_dir():
            package_dirs.setdefault(entry.name.lower(), entry)

wanted = {}
for target in manifest["targets"]:
    for dep in target["dependencies"]:
        product = dep.get("product")
        if not product:
            continue
        # ["<product name>", "<package identity>", ...]
        name, package = product[0], product[1]
        if package:
            wanted.setdefault(package.lower(), set()).add(name)

if not wanted:
    print("error: manifest declares no .product dependencies — did the parse shape change?",
          file=sys.stderr)
    sys.exit(1)

failures = []
for package, names in sorted(wanted.items()):
    directory = package_dirs.get(package)
    # A package we cannot locate must fail: silently skipping it is how a wrong
    # product name on a path dependency used to pass as "Package.swift OK".
    if directory is None or not (directory / "Package.swift").is_file():
        failures.append(
            f"cannot locate the manifest for package '{package}' "
            f"(looked in .build/checkouts and the manifest's path dependencies), "
            f"so products {sorted(names)} could not be verified"
        )
        continue
    dumped = subprocess.run(
        ["swift", "package", "dump-package"],
        cwd=directory, capture_output=True, text=True,
    )
    if dumped.returncode != 0:
        failures.append(f"could not dump the manifest of '{package}': {dumped.stderr.strip()}")
        continue
    declared = {p["name"] for p in json.loads(dumped.stdout)["products"]}
    for name in sorted(names):
        if name in declared:
            print(f"    ok: {package} declares product '{name}'")
        else:
            failures.append(
                f"{package} does not declare a product named '{name}'; "
                f"declared products are {sorted(declared)}"
            )

for failure in failures:
    print(f"error: {failure}", file=sys.stderr)
sys.exit(1 if failures else 0)
PY

echo "==> Package.swift OK"
