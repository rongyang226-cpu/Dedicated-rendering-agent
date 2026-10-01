import 'dart:math';

import 'package:fl_clash/providers/app.dart';
import 'package:material_ui/material_ui.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'hui_glass.dart';

class CommonDialog extends ConsumerWidget {
  final String title;
  final Widget? child;
  final List<Widget>? actions;
  final EdgeInsets? padding;
  final bool overrideScroll;
  final Color? backgroundColor;

  const CommonDialog({
    super.key,
    required this.title,
    this.actions,
    this.child,
    this.padding,
    this.overrideScroll = false,
    this.backgroundColor,
  });

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final size = ref.watch(viewSizeProvider);
    final content = child ?? const SizedBox.shrink();
    return Dialog(
      backgroundColor: Colors.transparent,
      surfaceTintColor: Colors.transparent,
      insetPadding: const EdgeInsets.symmetric(horizontal: 20, vertical: 24),
      child: HuiGlassSurface(
        strong: true,
        blurFactor: 1.08,
        opacity: 0.96,
        tint: backgroundColor,
        borderRadius: BorderRadius.circular(28),
        padding: padding ?? const EdgeInsets.fromLTRB(22, 20, 22, 14),
        child: ConstrainedBox(
          constraints: BoxConstraints(
            maxHeight: min(size.height - 48, 520),
            maxWidth: 340,
          ),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Text(title, style: Theme.of(context).textTheme.titleLarge),
              const SizedBox(height: 16),
              Flexible(
                fit: FlexFit.loose,
                child: overrideScroll
                    ? content
                    : SingleChildScrollView(child: content),
              ),
              if (actions?.isNotEmpty == true) ...[
                const SizedBox(height: 14),
                Wrap(
                  alignment: WrapAlignment.end,
                  spacing: 8,
                  runSpacing: 8,
                  children: actions!,
                ),
              ],
            ],
          ),
        ),
      ),
    );
  }
}

class CommonModal extends ConsumerWidget {
  final Widget? child;

  const CommonModal({super.key, this.child});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final size = ref.watch(viewSizeProvider);
    return Center(
      child: SizedBox(
        width: size.width * 0.85,
        height: size.height * 0.85,
        child: HuiGlassSurface(
          strong: true,
          blurFactor: 1.05,
          opacity: 0.96,
          borderRadius: BorderRadius.circular(30),
          child: child ?? const SizedBox.shrink(),
        ),
      ),
    );
  }
}
