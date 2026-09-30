import 'dart:async';

import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/providers/providers.dart';
import 'package:fl_clash/state.dart';
import 'package:fl_clash/views/dashboard/widgets/start_button.dart';
import 'package:material_ui/material_ui.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:riverpod_annotation/riverpod_annotation.dart';

import '../helpers/test_app.dart';

ProviderContainer _container({SetupAction Function()? setupAction}) {
  final overrides = <Override>[
    profilesProvider.overrideWithValue([
      const Profile(id: 1, autoUpdateDuration: Duration.zero),
    ]),
    initProvider.overrideWithBuild((_, _) => true),
  ];
  if (setupAction != null) {
    overrides.add(setupActionProvider.overrideWith(setupAction));
  }
  return ProviderContainer(overrides: overrides);
}

Future<void> _pumpButton(
  WidgetTester tester,
  ProviderContainer container,
) async {
  globalState.container = container;
  await tester.pumpWidget(
    UncontrolledProviderScope(
      container: container,
      child: TestApp(
        includeNavigatorKey: false,
        setTheme: false,
        homeBuilder: (child) => Scaffold(body: Center(child: child)),
        child: const StartButton(),
      ),
    ),
  );
  await tester.pump();
}

Finder _buttonTapTarget() => find.descendant(
  of: find.byType(StartButton),
  matching: find.byType(InkResponse),
);

void main() {
  testWidgets('RunTimeText renders long runtimes without special-case spans', (
    tester,
  ) async {
    const colorScheme = ColorScheme.light(
      primary: Color(0xFF6750A4),
      onPrimaryContainer: Color(0xFF21005D),
    );
    await tester.pumpWidget(
      MaterialApp(
        theme: ThemeData(colorScheme: colorScheme),
        home: const RunTimeText(timeStamp: 100 * 60 * 60 * 1000),
      ),
    );

    final text = tester.widget<Text>(
      find.descendant(
        of: find.byType(RunTimeText),
        matching: find.byType(Text),
      ),
    );
    expect(text.data, '100:00:00');
    expect(text.style?.color, colorScheme.onPrimaryContainer);
  });

  testWidgets('StartButton stays a fixed 72px circle and shows runtime', (
    tester,
  ) async {
    final container = _container();
    addTearDown(container.dispose);
    container.read(runTimeProvider.notifier).value = 100 * 60 * 60 * 1000;
    container.read(coreStatusProvider.notifier).value = CoreStatus.connected;

    await _pumpButton(tester, container);

    final fixedBox = find.descendant(
      of: find.byType(StartButton),
      matching: find.byWidgetPredicate(
        (widget) =>
            widget is SizedBox && widget.width == 72 && widget.height == 72,
      ),
    );
    expect(fixedBox, findsOneWidget);
    expect(tester.getSize(fixedBox), const Size.square(72));
    expect(find.text('100:00:00'), findsOneWidget);
    expect(_buttonTapTarget(), findsOneWidget);
  });

  testWidgets('StartButton is hidden until a profile exists', (tester) async {
    final container = ProviderContainer();
    addTearDown(container.dispose);
    await _pumpButton(tester, container);

    expect(_buttonTapTarget(), findsNothing);
    expect(find.byIcon(Icons.power_settings_new_rounded), findsNothing);
  });

  testWidgets('dispatches start and stop through the shared setup action', (
    tester,
  ) async {
    final container = _container(setupAction: _RecordingSetupAction.new);
    addTearDown(container.dispose);
    await _pumpButton(tester, container);

    final action =
        container.read(setupActionProvider.notifier) as _RecordingSetupAction;
    final button = _buttonTapTarget();

    await tester.tap(button);
    await tester.pump();
    expect(action.requests, [true]);
    expect(container.read(isStartProvider), isTrue);

    await tester.tap(button);
    await tester.pump();
    expect(action.requests, [true, false]);
    expect(container.read(isStartProvider), isFalse);
  });

  testWidgets('ignores repeated taps while a state change is pending', (
    tester,
  ) async {
    final container = _container(setupAction: _DelayedSetupAction.new);
    addTearDown(container.dispose);
    await _pumpButton(tester, container);

    final action =
        container.read(setupActionProvider.notifier) as _DelayedSetupAction;
    final button = _buttonTapTarget();

    await tester.tap(button);
    await tester.pump();
    expect(action.requests, [true]);
    expect(find.byType(CircularProgressIndicator), findsOneWidget);

    await tester.tap(button);
    await tester.pump();
    expect(action.requests, [true]);

    action.complete(success: true);
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 200));
    expect(find.byType(CircularProgressIndicator), findsNothing);
  });
}

class _RecordingSetupAction extends SetupAction {
  final requests = <bool>[];

  @override
  Future<bool> setRunning(bool running, {bool initialize = false}) async {
    requests.add(running);
    ref.read(runTimeProvider.notifier).value = running ? 1 : null;
    ref.read(coreStatusProvider.notifier).value = running
        ? CoreStatus.connected
        : CoreStatus.disconnected;
    return true;
  }
}

class _DelayedSetupAction extends SetupAction {
  final requests = <bool>[];
  Completer<bool>? _completer;
  bool? _pendingRunning;

  @override
  Future<bool> setRunning(bool running, {bool initialize = false}) {
    requests.add(running);
    _pendingRunning = running;
    _completer = Completer<bool>();
    return _completer!.future;
  }

  void complete({required bool success}) {
    if (success && _pendingRunning != null) {
      ref.read(runTimeProvider.notifier).value = _pendingRunning! ? 1 : null;
      ref.read(coreStatusProvider.notifier).value = _pendingRunning!
          ? CoreStatus.connected
          : CoreStatus.disconnected;
    }
    _completer?.complete(success);
    _completer = null;
    _pendingRunning = null;
  }
}
