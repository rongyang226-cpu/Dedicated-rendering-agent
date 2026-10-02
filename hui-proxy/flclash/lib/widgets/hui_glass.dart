import 'dart:ui';

import 'package:fl_clash/providers/config.dart';
import 'package:material_ui/material_ui.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

/// Marks a static grid whose glass cards do not overlap.
class HuiGlassGroupScope extends InheritedWidget {
  const HuiGlassGroupScope({super.key, required super.child});

  static bool enabled(BuildContext context) =>
      context.dependOnInheritedWidgetOfExactType<HuiGlassGroupScope>() != null;

  @override
  bool updateShouldNotify(HuiGlassGroupScope oldWidget) => false;
}

/// Shared liquid-glass surface for Hui.
///
/// Keep this component cheap enough to be reused by cards, list rows, dialogs
/// and small controls. Blur strength follows the existing Hui visual setting.
class HuiGlassSurface extends StatelessWidget {
  const HuiGlassSurface({
    super.key,
    required this.child,
    this.borderRadius,
    this.padding,
    this.margin,
    this.blurFactor = 1,
    this.opacity = 1,
    this.strong = false,
    this.selected = false,
    this.tint,
    this.useBlur = true,
    this.groupBlur = false,
  });

  final Widget child;
  final BorderRadius? borderRadius;
  final EdgeInsetsGeometry? padding;
  final EdgeInsetsGeometry? margin;
  final double blurFactor;
  final double opacity;
  final bool strong;
  final bool selected;
  final Color? tint;
  final bool useBlur;
  final bool groupBlur;
  @override
  Widget build(BuildContext context) {
    try {
      ProviderScope.containerOf(context, listen: false);
    } on StateError {
      // Keep shared surfaces usable in independent widgets and overlays.
      return _buildSurface(context, 12.0);
    }
    return Consumer(
      builder: (context, ref, _) => _buildSurface(
        context,
        ref.watch(themeSettingProvider.select((value) => value.glassBlur)),
      ),
    );
  }

  Widget _buildSurface(BuildContext context, double glassBlur) {
    final scheme = Theme.of(context).colorScheme;
    final dark = Theme.of(context).brightness == Brightness.dark;
    final radius = borderRadius ?? BorderRadius.circular(24);
    final alphaScale = opacity.clamp(0.0, 1.4);
    // The wallpaper is deliberately vivid, so glass needs a real tint layer.
    // Blur alone smears detail but does not create enough text contrast.
    final surfaceAlpha =
        (dark ? (strong ? 0.54 : 0.38) : (strong ? 0.61 : 0.45)) * alphaScale;
    final topAlpha =
        (dark ? (strong ? 0.19 : 0.15) : (strong ? 0.29 : 0.25)) * alphaScale;
    final edgeAlpha = dark ? 0.36 : 0.58;
    // Many cards blur the animated background at once. Keep the maximum
    // kernel bounded when the user raises the strength slider.
    final blur = (glassBlur * blurFactor * 0.6).clamp(0.0, 12.0);
    final tintColor = tint ?? scheme.surface;

    Widget content = DecoratedBox(
      decoration: BoxDecoration(
        gradient: LinearGradient(
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
          colors: [
            Colors.white.withValues(alpha: topAlpha.clamp(0.0, 1.0)),
            Colors.white.withValues(alpha: (topAlpha * 0.48).clamp(0.0, 1.0)),
            tintColor.withValues(alpha: surfaceAlpha.clamp(0.0, 1.0)),
            scheme.surface.withValues(
              alpha: (surfaceAlpha * 0.75).clamp(0.0, 1.0),
            ),
          ],
          stops: const [0.0, 0.12, 0.52, 1.0],
        ),
        borderRadius: radius,
        border: Border.all(
          width: selected ? 1.35 : 0.9,
          color: selected
              ? scheme.primary.withValues(alpha: 0.72)
              : Colors.white.withValues(alpha: edgeAlpha),
        ),
        boxShadow: [
          BoxShadow(
            color: Colors.black.withValues(alpha: dark ? 0.16 : 0.07),
            blurRadius: strong ? 24 : 16,
            offset: const Offset(0, 7),
          ),
          BoxShadow(
            color: Colors.white.withValues(alpha: dark ? 0.025 : 0.16),
            blurRadius: 10,
            offset: const Offset(-3, -3),
          ),
        ],
      ),
      child: Padding(
        padding: padding ?? EdgeInsets.zero,
        child: Material(type: MaterialType.transparency, child: child),
      ),
    );

    if (useBlur && blur > 0.1) {
      final filter = ImageFilter.blur(sigmaX: blur, sigmaY: blur);
      content = groupBlur
          ? BackdropFilter.grouped(filter: filter, child: content)
          : BackdropFilter(filter: filter, child: content);
    }
    return RepaintBoundary(
      child: Container(
        margin: margin,
        child: ClipRRect(
          borderRadius: radius,
          clipBehavior: Clip.antiAlias,
          child: content,
        ),
      ),
    );
  }
}
