import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:twilio_programmable_video_example/conference/network_quality_indicator.dart';

/// `_getRect` maps each [NetworkQualityIndicatorPosition] onto two of the four
/// `Positioned` edges and leaves the other two null. Dart 3 only checks that
/// every enum constant is *handled* — it cannot check that a case assigns the
/// right edge, so a `right`/`left` swap would silently move the indicator to
/// the wrong corner with the analyzer clean. These tests pin the whole table.
void main() {
  // Layout constants: with a 300x200 viewport and a 50x15 indicator,
  // horizontal centring is (300 - 50) / 2 and vertical centring is
  // (200 - 15) / 2 — distinct from each other and from the 0.0 edges.
  const viewportWidth = 300.0;
  const viewportHeight = 200.0;
  const indicatorWidth = 50.0;
  const indicatorHeight = 15.0;
  const centreX = (viewportWidth - indicatorWidth) / 2; // 125.0
  const centreY = (viewportHeight - indicatorHeight) / 2; // 92.5

  Future<Positioned> pumpAt(WidgetTester tester, NetworkQualityIndicatorPosition? position) async {
    await tester.pumpWidget(
      MaterialApp(
        home: Center(
          child: SizedBox(
            width: viewportWidth,
            height: viewportHeight,
            child: NetworkQualityIndicator(
              width: indicatorWidth,
              height: indicatorHeight,
              networkQualityIndicatorPosition: position,
            ),
          ),
        ),
      ),
    );

    return tester.widget<Positioned>(
      find.descendant(
        of: find.byType(NetworkQualityIndicator),
        matching: find.byType(Positioned),
      ),
    );
  }

  void expectEdges(
    Positioned positioned, {
    double? left,
    double? top,
    double? right,
    double? bottom,
  }) {
    expect(positioned.left, left, reason: 'left');
    expect(positioned.top, top, reason: 'top');
    expect(positioned.right, right, reason: 'right');
    expect(positioned.bottom, bottom, reason: 'bottom');
  }

  group('NetworkQualityIndicator position mapping', () {
    testWidgets('topLeft pins top and left', (tester) async {
      expectEdges(await pumpAt(tester, NetworkQualityIndicatorPosition.topLeft), top: 0.0, left: 0.0);
    });

    testWidgets('topCenter pins top and centres horizontally', (tester) async {
      expectEdges(await pumpAt(tester, NetworkQualityIndicatorPosition.topCenter), top: 0.0, left: centreX);
    });

    testWidgets('topRight pins top and right', (tester) async {
      expectEdges(await pumpAt(tester, NetworkQualityIndicatorPosition.topRight), top: 0.0, right: 0.0);
    });

    testWidgets('middleLeft centres vertically and pins left', (tester) async {
      expectEdges(await pumpAt(tester, NetworkQualityIndicatorPosition.middleLeft), top: centreY, left: 0.0);
    });

    testWidgets('middleCenter centres both axes', (tester) async {
      expectEdges(await pumpAt(tester, NetworkQualityIndicatorPosition.middleCenter), top: centreY, left: centreX);
    });

    testWidgets('middleRight centres vertically and pins right', (tester) async {
      expectEdges(await pumpAt(tester, NetworkQualityIndicatorPosition.middleRight), top: centreY, right: 0.0);
    });

    testWidgets('bottomLeft pins bottom and left', (tester) async {
      expectEdges(await pumpAt(tester, NetworkQualityIndicatorPosition.bottomLeft), bottom: 0.0, left: 0.0);
    });

    testWidgets('bottomCenter pins bottom and centres horizontally', (tester) async {
      expectEdges(await pumpAt(tester, NetworkQualityIndicatorPosition.bottomCenter), bottom: 0.0, left: centreX);
    });

    testWidgets('bottomRight pins bottom and right', (tester) async {
      expectEdges(await pumpAt(tester, NetworkQualityIndicatorPosition.bottomRight), bottom: 0.0, right: 0.0);
    });

    testWidgets('a null position falls through to the explicit edge values', (tester) async {
      await tester.pumpWidget(
        MaterialApp(
          home: Center(
            child: SizedBox(
              width: viewportWidth,
              height: viewportHeight,
              child: const NetworkQualityIndicator(
                width: indicatorWidth,
                height: indicatorHeight,
                top: 4,
                left: 5,
                right: 6,
                bottom: 7,
              ),
            ),
          ),
        ),
      );

      final positioned = tester.widget<Positioned>(
        find.descendant(
          of: find.byType(NetworkQualityIndicator),
          matching: find.byType(Positioned),
        ),
      );

      expectEdges(positioned, top: 4.0, left: 5.0, right: 6.0, bottom: 7.0);
    });

    testWidgets('explicit offsets are added to the resolved position', (tester) async {
      await tester.pumpWidget(
        MaterialApp(
          home: Center(
            child: SizedBox(
              width: viewportWidth,
              height: viewportHeight,
              child: const NetworkQualityIndicator(
                width: indicatorWidth,
                height: indicatorHeight,
                top: 3,
                left: 9,
                networkQualityIndicatorPosition: NetworkQualityIndicatorPosition.topLeft,
              ),
            ),
          ),
        ),
      );

      final positioned = tester.widget<Positioned>(
        find.descendant(
          of: find.byType(NetworkQualityIndicator),
          matching: find.byType(Positioned),
        ),
      );

      expectEdges(positioned, top: 3.0, left: 9.0);
    });

    testWidgets('every enum constant is covered by this suite', (tester) async {
      // Guards against a new constant being added to the enum without a
      // matching test above (the switch itself already fails to compile).
      expect(NetworkQualityIndicatorPosition.values, hasLength(9));
    });
  });
}
