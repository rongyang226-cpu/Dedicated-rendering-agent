import 'package:fl_clash/common/app_localizations.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/providers/app.dart';
import 'package:fl_clash/providers/config.dart';
import 'package:fl_clash/providers/database.dart';
import 'package:fl_clash/state.dart';
import 'package:fl_clash/views/theme.dart';
import 'package:fl_clash/widgets/widgets.dart';
import 'package:material_ui/material_ui.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

import '../helpers/test_app.dart';
import '../helpers/test_profiles.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  late ProviderContainer container;

  setUp(() {
    container = ProviderContainer(
      overrides: [profilesProvider.overrideWith(TestProfiles.new)],
    );
    globalState.container = container;
    container.read(viewSizeProvider.notifier).value = const Size(1400, 1400);
  });

  tearDown(() => container.dispose());

  Future<void> pumpThemeView(WidgetTester tester) async {
    tester.view.physicalSize = const Size(1400, 1400);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);

    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: container,
        child: const TestApp(child: ThemeView()),
      ),
    );
    await tester.pumpAndSettle();
  }

  ThemeProps readTheme() => container.read(themeSettingProvider);

  Finder toggleForLabel(String label) {
    final item = find.ancestor(
      of: find.text(label),
      matching: find.byType(ListItem),
    );
    return find.descendant(of: item, matching: find.byType(Switch));
  }

  group('theme mode', () {
    testWidgets('defaults to the dark theme', (tester) async {
      await pumpThemeView(tester);

      expect(readTheme().themeMode, ThemeMode.dark);
    });

    testWidgets('switches to light and back to dark', (tester) async {
      await pumpThemeView(tester);

      await tester.tap(find.text('Light'));
      await tester.pumpAndSettle();
      expect(readTheme().themeMode, ThemeMode.light);

      await tester.tap(find.text('Dark'));
      await tester.pumpAndSettle();
      expect(readTheme().themeMode, ThemeMode.dark);

      await tester.tap(find.text('Auto'));
      await tester.pumpAndSettle();
      expect(readTheme().themeMode, ThemeMode.system);
    });
  });

  group('pure black', () {
    testWidgets('toggles both ways', (tester) async {
      await pumpThemeView(tester);
      final toggle = toggleForLabel(currentAppLocalizations.pureBlackMode);

      expect(readTheme().pureBlack, isFalse);

      await tester.tap(toggle);
      await tester.pumpAndSettle();
      expect(readTheme().pureBlack, isTrue);

      await tester.tap(toggle);
      await tester.pumpAndSettle();
      expect(readTheme().pureBlack, isFalse);
    });
  });

  group('text scale', () {
    testWidgets('is disabled until its toggle is enabled', (tester) async {
      await pumpThemeView(tester);

      expect(readTheme().textScale.enable, isFalse);

      await tester.tap(toggleForLabel(currentAppLocalizations.textScale));
      await tester.pumpAndSettle();

      expect(readTheme().textScale.enable, isTrue);
    });

    testWidgets('the slider writes a new scale once enabled', (tester) async {
      await pumpThemeView(tester);
      await tester.tap(toggleForLabel(currentAppLocalizations.textScale));
      await tester.pumpAndSettle();
      final before = readTheme().textScale.scale;

      final slider = find.byWidgetPredicate(
        (widget) => widget is Slider && widget.min == 0.8 && widget.max == 1.4,
      );
      expect(slider, findsOneWidget);
      await tester.drag(slider, const Offset(120, 0));
      await tester.pumpAndSettle();

      expect(readTheme().textScale.scale, isNot(before));
    });

    testWidgets('renders the scale as a rounded percentage', (tester) async {
      container
          .read(themeSettingProvider.notifier)
          .update(
            (state) => state.copyWith.textScale(enable: true, scale: 1.2),
          );

      await pumpThemeView(tester);

      expect(find.text('120%'), findsOneWidget);
    });
  });
}
