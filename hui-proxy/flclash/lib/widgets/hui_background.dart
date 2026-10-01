import 'dart:io';
import 'dart:ui';

import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/providers/config.dart';
import 'package:material_ui/material_ui.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

/// The same wallpaper layer is used by the main page and pushed mobile pages.
class HuiBackground extends StatelessWidget {
  const HuiBackground({super.key, required this.visual});

  final ThemeProps visual;

  @override
  Widget build(BuildContext context) {
    final customPath = visual.backgroundImagePath.trim();
    final customFile = customPath.isEmpty ? null : File(customPath);
    final hasCustom = customFile?.existsSync() ?? false;
    final image = hasCustom
        ? FileImage(customFile!) as ImageProvider
        : const AssetImage('assets/images/hui_background.webp');
    Widget result = DecoratedBox(
      decoration: BoxDecoration(
        image: DecorationImage(
          image: image,
          fit: BoxFit.cover,
          alignment: Alignment.center,
          filterQuality: FilterQuality.medium,
        ),
      ),
    );
    final brightness = visual.backgroundBrightness.clamp(0.55, 1.25);
    if ((brightness - 1).abs() > 0.01) {
      result = ColorFiltered(
        colorFilter: ColorFilter.matrix([
          brightness,
          0,
          0,
          0,
          0,
          0,
          brightness,
          0,
          0,
          0,
          0,
          0,
          brightness,
          0,
          0,
          0,
          0,
          0,
          1,
          0,
        ]),
        child: result,
      );
    }
    final blur = visual.backgroundBlur.clamp(0.0, 12.0);
    if (blur > 0.1) {
      result = ClipRect(
        child: ImageFiltered(
          imageFilter: ImageFilter.blur(sigmaX: blur * 0.7, sigmaY: blur * 0.7),
          child: Transform.scale(scale: 1.035, child: result),
        ),
      );
    }
    return RepaintBoundary(child: result);
  }
}

/// Pushed routes need their own backdrop so the previous page cannot show
/// through transparent glass controls.
class HuiPageBackground extends ConsumerWidget {
  const HuiPageBackground({super.key, required this.child});

  final Widget child;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final visual = ref.watch(themeSettingProvider);
    final dark = Theme.brightnessOf(context) == Brightness.dark;
    final mask = visual.backgroundMask.clamp(0.0, 0.40);
    return Stack(
      fit: StackFit.expand,
      children: [
        HuiBackground(visual: visual),
        ColoredBox(
          color: (dark ? Colors.black : Colors.white).withValues(alpha: mask),
        ),
        Material(color: Colors.transparent, child: child),
      ],
    );
  }
}
