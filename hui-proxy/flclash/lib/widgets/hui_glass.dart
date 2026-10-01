import 'dart:ui';

import 'package:fl_clash/providers/config.dart';
import 'package:material_ui/material_ui.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

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
  @override
  Widget build(BuildContext context) {
    try {
      ProviderScope.containerOf(context, listen: false);
    } on StateError {
      // Keep shared surfaces usable in independent widgets and overlays.
      return _buildSurface(context, 12.0);
    }
    return Consumer(
      builder: (context, ref, _) =>
          _buildSurface(context, ref.watch(themeSettingProvider).glassBlur),
    );
  }

  Widget _buildSurface(BuildContext context, double glassBlur) {
    final scheme = Theme.of(context).colorScheme;
    final dark = Theme.of(context).brightness == Brightness.dark;
    final radius = borderRadius ?? BorderRadius.circular(24);
    final alphaScale = opacity.clamp(0.0, 1.4);
    final surfaceAlpha =
        (dark ? (strong ? 0.30 : 0.18) : (strong ? 0.42 : 0.28)) * alphaScale;
    final topAlpha =
        (dark ? (strong ? 0.20 : 0.13) : (strong ? 0.48 : 0.34)) * alphaScale;
    final edgeAlpha = dark ? 0.26 : 0.68;
    final blur = (glassBlur * blurFactor).clamp(0.0, 24.0);
    final tintColor = tint ?? scheme.surface;

    Widget content = DecoratedBox(
      decoration: BoxDecoration(
        gradient: LinearGradient(
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
          colors: [
            Colors.white.withValues(alpha: topAlpha.clamp(0.0, 1.0)),
            tintColor.withValues(alpha: surfaceAlpha.clamp(0.0, 1.0)),
            scheme.surface.withValues(
              alpha: (surfaceAlpha * 0.68).clamp(0.0, 1.0),
            ),
          ],
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
      content = BackdropFilter(
        filter: ImageFilter.blur(sigmaX: blur, sigmaY: blur),
        child: content,
      );
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
