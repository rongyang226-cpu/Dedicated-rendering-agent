import 'package:fl_clash/common/common.dart';
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/providers/providers.dart';
import 'package:material_ui/material_ui.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

enum _ConnectionUiState {
  starting,
  connected,
  suspended,
  disconnecting,
  disconnected,
  error,
}

class RunTimeText extends StatelessWidget {
  final int? timeStamp;

  const RunTimeText({super.key, required this.timeStamp});

  @override
  Widget build(BuildContext context) {
    return Text(
      getTimeText(timeStamp),
      maxLines: 1,
      overflow: TextOverflow.visible,
      style: context.textTheme.labelSmall?.toSoftBold.copyWith(
        color: context.colorScheme.onPrimaryContainer,
        fontFeatures: const [FontFeature.tabularFigures()],
      ),
    );
  }
}

class StartButton extends ConsumerStatefulWidget {
  const StartButton({super.key});

  @override
  ConsumerState<StartButton> createState() => _StartButtonState();
}

class _StartButtonState extends ConsumerState<StartButton> {
  bool _switching = false;
  bool _lastFailed = false;
  bool? _targetRunning;

  Future<void> _handleSwitchStart() async {
    if (_switching) return;
    final targetRunning = !ref.read(isStartProvider);
    setState(() {
      _switching = true;
      _lastFailed = false;
      _targetRunning = targetRunning;
    });
    final success = await ref
        .read(commonActionProvider.notifier)
        .toggleRunning();
    if (!mounted) return;
    setState(() {
      _switching = false;
      _lastFailed = !success;
      _targetRunning = null;
    });
  }

  _ConnectionUiState _resolveState({
    required bool isStart,
    required bool suspend,
    required CoreStatus coreStatus,
  }) {
    if (_switching) {
      return _targetRunning == true
          ? _ConnectionUiState.starting
          : _ConnectionUiState.disconnecting;
    }
    if (_lastFailed) return _ConnectionUiState.error;
    if (!isStart) return _ConnectionUiState.disconnected;
    if (suspend) return _ConnectionUiState.suspended;
    return switch (coreStatus) {
      CoreStatus.connected => _ConnectionUiState.connected,
      CoreStatus.connecting => _ConnectionUiState.starting,
      CoreStatus.disconnected => _ConnectionUiState.error,
    };
  }

  @override
  Widget build(BuildContext context) {
    final hasProfile = ref.watch(
      profilesProvider.select((state) => state.isNotEmpty),
    );
    if (!hasProfile) return const SizedBox.shrink();

    final isStart = ref.watch(isStartProvider);
    final coreStatus = ref.watch(coreStatusProvider);
    final suspend = ref.watch(suspendProvider);
    final runTime = ref.watch(runTimeProvider);
    final state = _resolveState(
      isStart: isStart,
      suspend: suspend,
      coreStatus: coreStatus,
    );
    final colorScheme = context.colorScheme;
    final isBusy =
        state == _ConnectionUiState.starting ||
        state == _ConnectionUiState.disconnecting;
    final isConnected = state == _ConnectionUiState.connected;

    final backgroundColor = switch (state) {
      _ConnectionUiState.connected => colorScheme.primaryContainer,
      _ConnectionUiState.suspended => colorScheme.tertiaryContainer,
      _ConnectionUiState.starting ||
      _ConnectionUiState.disconnecting => colorScheme.surfaceContainerHigh,
      _ConnectionUiState.error => colorScheme.errorContainer,
      _ConnectionUiState.disconnected => colorScheme.surfaceContainerLow,
    };
    final foregroundColor = switch (state) {
      _ConnectionUiState.connected => colorScheme.onPrimaryContainer,
      _ConnectionUiState.suspended => colorScheme.onTertiaryContainer,
      _ConnectionUiState.error => colorScheme.onErrorContainer,
      _ => colorScheme.onSurfaceVariant,
    };
    final semanticLabel = switch (state) {
      _ConnectionUiState.starting => context.appLocalizations.connecting,
      _ConnectionUiState.connected => context.appLocalizations.connected,
      _ConnectionUiState.suspended => context.appLocalizations.suspended,
      _ConnectionUiState.disconnecting => context.appLocalizations.stopVpn,
      _ConnectionUiState.disconnected => context.appLocalizations.disconnected,
      _ConnectionUiState.error => context.appLocalizations.disconnected,
    };

    return RepaintBoundary(
      child: Semantics(
        button: true,
        enabled: !isBusy,
        label: semanticLabel,
        child: SizedBox.square(
          dimension: 72,
          child: AnimatedContainer(
            duration: const Duration(milliseconds: 180),
            curve: Curves.easeOutCubic,
            decoration: BoxDecoration(
              shape: BoxShape.circle,
              color: backgroundColor.withValues(alpha: 0.86),
              border: Border.all(
                color: isConnected
                    ? colorScheme.primary.withValues(alpha: 0.55)
                    : colorScheme.outlineVariant.withValues(alpha: 0.65),
              ),
              boxShadow: isConnected
                  ? [
                      BoxShadow(
                        color: colorScheme.primary.withValues(alpha: 0.18),
                        blurRadius: 20,
                        spreadRadius: 1,
                      ),
                    ]
                  : const [],
            ),
            child: Material(
              type: MaterialType.transparency,
              shape: const CircleBorder(),
              clipBehavior: Clip.antiAlias,
              child: InkResponse(
                onTap: isBusy ? null : _handleSwitchStart,
                containedInkWell: true,
                customBorder: const CircleBorder(),
                radius: 36,
                overlayColor: WidgetStateProperty.resolveWith((states) {
                  if (states.contains(WidgetState.pressed)) {
                    return foregroundColor.withValues(alpha: 0.10);
                  }
                  if (states.contains(WidgetState.hovered) ||
                      states.contains(WidgetState.focused)) {
                    return foregroundColor.withValues(alpha: 0.06);
                  }
                  return null;
                }),
                child: Center(
                  child: AnimatedSwitcher(
                    duration: const Duration(milliseconds: 160),
                    child: isBusy
                        ? SizedBox.square(
                            key: ValueKey(state),
                            dimension: 24,
                            child: CircularProgressIndicator(
                              strokeWidth: 2.4,
                              color: foregroundColor,
                            ),
                          )
                        : state == _ConnectionUiState.error
                        ? Icon(
                            Icons.error_outline_rounded,
                            key: const ValueKey('error'),
                            color: foregroundColor,
                            size: 28,
                          )
                        : Column(
                            key: ValueKey(state),
                            mainAxisSize: MainAxisSize.min,
                            children: [
                              Icon(
                                state == _ConnectionUiState.suspended
                                    ? Icons.pause_rounded
                                    : Icons.power_settings_new_rounded,
                                color: foregroundColor,
                                size: 28,
                              ),
                              if (isConnected) ...[
                                const SizedBox(height: 2),
                                RunTimeText(timeStamp: runTime),
                              ],
                            ],
                          ),
                  ),
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}
