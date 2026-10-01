import 'dart:io';
import 'dart:math';

import 'package:fl_clash/common/common.dart';
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/providers/config.dart';
import 'package:fl_clash/widgets/widgets.dart';
import 'package:material_ui/material_ui.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:image_picker/image_picker.dart';
import 'package:material_color_utilities/hct/hct.dart';

class ThemeModeItem {
  final ThemeMode themeMode;
  final IconData iconData;
  final String label;

  const ThemeModeItem({
    required this.themeMode,
    required this.iconData,
    required this.label,
  });
}

class FontFamilyItem {
  final FontFamily fontFamily;
  final String label;

  const FontFamilyItem({required this.fontFamily, required this.label});
}

class ThemeView extends StatelessWidget {
  const ThemeView({super.key});

  @override
  Widget build(BuildContext context) {
    final appLocalizations = context.appLocalizations;
    return BaseScaffold(
      title: appLocalizations.theme,
      body: const CustomScrollView(
        slivers: [
          _ThemeModeItem(),
          SliverToBoxAdapter(child: SizedBox(height: 16)),
          _HuiVisualItem(),
          SliverToBoxAdapter(child: SizedBox(height: 16)),
          _PrimaryColorItem(),
          SliverToBoxAdapter(child: SizedBox(height: 16)),
          _PrueBlackItem(),
          SliverToBoxAdapter(child: SizedBox(height: 16)),
          _TextScaleFactorItem(),
          SliverToBoxAdapter(child: SizedBox(height: 32)),
        ],
      ),
    );
  }
}

class ItemCard extends StatelessWidget {
  final Widget child;
  final Info info;
  final List<Widget> actions;

  const ItemCard({
    super.key,
    required this.info,
    required this.child,
    this.actions = const [],
  });

  @override
  Widget build(BuildContext context) {
    return Wrap(
      runSpacing: 16,
      children: [
        InfoHeader(info: info, actions: actions),
        child,
      ],
    );
  }
}

class _HuiVisualItem extends ConsumerWidget {
  const _HuiVisualItem();

  Future<void> _pickBackground(BuildContext context, WidgetRef ref) async {
    final picked = await ImagePicker().pickImage(
      source: ImageSource.gallery,
      imageQuality: 100,
      maxWidth: 4096,
      maxHeight: 4096,
    );
    if (picked == null) return;
    final homePath = await appPath.homeDirPath;
    final target = File('$homePath/hui_background_custom');
    final bytes = await picked.readAsBytes();
    await target.writeAsBytes(bytes, flush: true);
    if (!context.mounted) return;
    ref
        .read(themeSettingProvider.notifier)
        .update((state) => state.copyWith(backgroundImagePath: target.path));
  }

  Future<void> _restoreBackground(BuildContext context, WidgetRef ref) async {
    final homePath = await appPath.homeDirPath;
    final target = File('$homePath/hui_background_custom');
    if (await target.exists()) {
      await target.delete();
    }
    if (!context.mounted) return;
    ref
        .read(themeSettingProvider.notifier)
        .update((state) => state.copyWith(backgroundImagePath: ''));
  }

  Widget _slider({
    required BuildContext context,
    required String title,
    required double value,
    required double min,
    required double max,
    required int divisions,
    required String label,
    required ValueChanged<double> onChanged,
  }) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 4, 16, 8),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              Text(title, style: context.textTheme.bodyMedium),
              Text(label, style: context.textTheme.labelMedium),
            ],
          ),
          Slider(
            value: value.clamp(min, max),
            min: min,
            max: max,
            divisions: divisions,
            onChanged: onChanged,
          ),
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final visual = ref.watch(themeSettingProvider);
    final hasCustomBackground = visual.backgroundImagePath.isNotEmpty;
    return SliverToBoxAdapter(
      child: ItemCard(
        info: const Info(label: '绘视觉', iconData: Icons.auto_awesome_rounded),
        child: Column(
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 4, 16, 12),
              child: Row(
                children: [
                  Expanded(
                    child: FilledButton.tonalIcon(
                      onPressed: () => _pickBackground(context, ref),
                      icon: const Icon(Icons.wallpaper_rounded),
                      label: Text(hasCustomBackground ? '更换背景' : '自定义背景'),
                    ),
                  ),
                  const SizedBox(width: 12),
                  Expanded(
                    child: OutlinedButton.icon(
                      onPressed: hasCustomBackground
                          ? () => _restoreBackground(context, ref)
                          : null,
                      icon: const Icon(Icons.restore_rounded),
                      label: const Text('恢复默认'),
                    ),
                  ),
                ],
              ),
            ),
            _slider(
              context: context,
              title: '背景模糊',
              value: visual.backgroundBlur,
              min: 0,
              max: 12,
              divisions: 12,
              label: visual.backgroundBlur < 0.5
                  ? '关闭'
                  : visual.backgroundBlur.toStringAsFixed(0),
              onChanged: (value) {
                ref
                    .read(themeSettingProvider.notifier)
                    .update((state) => state.copyWith(backgroundBlur: value));
              },
            ),
            _slider(
              context: context,
              title: '背景亮度',
              value: visual.backgroundBrightness,
              min: 0.55,
              max: 1.25,
              divisions: 14,
              label: '${(visual.backgroundBrightness * 100).round()}%',
              onChanged: (value) {
                ref
                    .read(themeSettingProvider.notifier)
                    .update(
                      (state) => state.copyWith(backgroundBrightness: value),
                    );
              },
            ),
            _slider(
              context: context,
              title: '背景遮罩',
              value: visual.backgroundMask,
              min: 0,
              max: 0.40,
              divisions: 20,
              label: '${(visual.backgroundMask * 100).round()}%',
              onChanged: (value) {
                ref
                    .read(themeSettingProvider.notifier)
                    .update((state) => state.copyWith(backgroundMask: value));
              },
            ),
            _slider(
              context: context,
              title: '玻璃模糊',
              value: visual.glassBlur,
              min: 0,
              max: 20,
              divisions: 20,
              label: visual.glassBlur < 0.5
                  ? '关闭'
                  : visual.glassBlur.toStringAsFixed(0),
              onChanged: (value) {
                ref
                    .read(themeSettingProvider.notifier)
                    .update((state) => state.copyWith(glassBlur: value));
              },
            ),
            HuiGlassSurface(
              margin: const EdgeInsets.fromLTRB(10, 4, 10, 8),
              borderRadius: BorderRadius.circular(20),
              blurFactor: 0.52,
              opacity: 0.72,
              child: SwitchListTile.adaptive(
                contentPadding: const EdgeInsets.symmetric(horizontal: 16),
                title: const Text('樱花动画'),
                subtitle: const Text('独立粒子层，页面切换不会重新生成'),
                value: visual.sakuraEnabled,
                onChanged: (value) {
                  ref
                      .read(themeSettingProvider.notifier)
                      .update((state) => state.copyWith(sakuraEnabled: value));
                },
              ),
            ),
            if (visual.sakuraEnabled)
              Padding(
                padding: const EdgeInsets.fromLTRB(16, 0, 16, 14),
                child: HuiGlassSurface(
                  borderRadius: BorderRadius.circular(18),
                  blurFactor: 0.48,
                  opacity: 0.70,
                  padding: const EdgeInsets.all(3),
                  child: SizedBox(
                    width: double.infinity,
                    child: SegmentedButton<int>(
                      segments: const [
                        ButtonSegment(value: 0, label: Text('低')),
                        ButtonSegment(value: 1, label: Text('中')),
                        ButtonSegment(value: 2, label: Text('高')),
                      ],
                      selected: {visual.sakuraLevel.clamp(0, 2)},
                      onSelectionChanged: (values) {
                        ref
                            .read(themeSettingProvider.notifier)
                            .update(
                              (state) =>
                                  state.copyWith(sakuraLevel: values.first),
                            );
                      },
                    ),
                  ),
                ),
              ),
          ],
        ),
      ),
    );
  }
}

class _ThemeModeItem extends ConsumerWidget {
  const _ThemeModeItem();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final appLocalizations = context.appLocalizations;
    final themeMode = ref.watch(
      themeSettingProvider.select((state) => state.themeMode),
    );
    final List<ThemeModeItem> themeModeItems = [
      ThemeModeItem(
        iconData: Icons.auto_mode,
        label: appLocalizations.auto,
        themeMode: ThemeMode.system,
      ),
      ThemeModeItem(
        iconData: Icons.light_mode,
        label: appLocalizations.light,
        themeMode: ThemeMode.light,
      ),
      ThemeModeItem(
        iconData: Icons.dark_mode,
        label: appLocalizations.dark,
        themeMode: ThemeMode.dark,
      ),
    ];
    return SliverToBoxAdapter(
      child: ItemCard(
        info: Info(
          label: appLocalizations.themeMode,
          iconData: Icons.brightness_high,
        ),
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 16),
          height: 56,
          child: ListView.separated(
            scrollDirection: Axis.horizontal,
            itemCount: themeModeItems.length,
            itemBuilder: (_, index) {
              final themeModeItem = themeModeItems[index];
              return CommonCard(
                isSelected: themeModeItem.themeMode == themeMode,
                onPressed: () {
                  ref
                      .read(themeSettingProvider.notifier)
                      .update(
                        (state) =>
                            state.copyWith(themeMode: themeModeItem.themeMode),
                      );
                },
                child: Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 16),
                  child: Row(
                    mainAxisSize: MainAxisSize.min,
                    mainAxisAlignment: MainAxisAlignment.start,
                    children: [
                      Flexible(child: Icon(themeModeItem.iconData)),
                      const SizedBox(width: 8),
                      Flexible(child: Text(themeModeItem.label)),
                    ],
                  ),
                ),
              );
            },
            separatorBuilder: (_, _) {
              return const SizedBox(width: 16);
            },
          ),
        ),
      ),
    );
  }
}

class _PrimaryColorItem extends ConsumerStatefulWidget {
  const _PrimaryColorItem();

  @override
  ConsumerState<_PrimaryColorItem> createState() => _PrimaryColorItemState();
}

class _PrimaryColorItemState extends ConsumerState<_PrimaryColorItem> {
  int? _removablePrimaryColor;

  Future<void> _handleReset() async {
    final res = await dialogs.showMessage(
      message: TextSpan(text: context.appLocalizations.resetTip),
    );
    if (res != true) {
      return;
    }
    ref.read(themeSettingProvider.notifier).update((state) {
      return state.copyWith(
        primaryColors: defaultPrimaryColors,
        primaryColor: defaultPrimaryColor,
        schemeVariant: DynamicSchemeVariant.content,
      );
    });
  }

  Future<void> _handleDel() async {
    final appLocalizations = context.appLocalizations;
    if (_removablePrimaryColor == null) {
      return;
    }
    final res = await dialogs.showMessage(
      message: TextSpan(
        text: appLocalizations.deleteTip(appLocalizations.colorSchemes),
      ),
    );
    if (res != true) {
      return;
    }
    ref.read(themeSettingProvider.notifier).update((state) {
      final newPrimaryColors = List<int>.from(state.primaryColors)
        ..remove(_removablePrimaryColor);
      int? newPrimaryColor = state.primaryColor;
      if (state.primaryColor == _removablePrimaryColor) {
        if (newPrimaryColors.contains(defaultPrimaryColor)) {
          newPrimaryColor = defaultPrimaryColor;
        } else {
          newPrimaryColor = null;
        }
      }
      return state.copyWith(
        primaryColors: newPrimaryColors,
        primaryColor: newPrimaryColor,
      );
    });
    setState(() {
      _removablePrimaryColor = null;
    });
  }

  Future<void> _handleAdd() async {
    final appLocalizations = context.appLocalizations;
    final res = await dialogs.showCommonDialog<int>(
      child: const _PaletteDialog(),
    );
    if (res == null) {
      return;
    }
    final isExists = ref.read(
      themeSettingProvider.select((state) => state.primaryColors.contains(res)),
    );
    if (isExists && mounted) {
      context.showNotifier(
        appLocalizations.existsTip(appLocalizations.colorSchemes),
        level: MessageLevel.warning,
      );
      return;
    }
    ref.read(themeSettingProvider.notifier).update((state) {
      return state.copyWith(
        primaryColors: List.from(state.primaryColors)..add(res),
      );
    });
  }

  Future<void> _handleChangeSchemeVariant() async {
    final schemeVariant = ref.read(
      themeSettingProvider.select((state) => state.schemeVariant),
    );
    final value = await dialogs.showCommonDialog<DynamicSchemeVariant>(
      child: OptionsDialog<DynamicSchemeVariant>(
        title: context.appLocalizations.colorSchemes,
        options: DynamicSchemeVariant.values,
        textBuilder: (item) => item.label,
        value: schemeVariant,
      ),
    );
    if (value == null) {
      return;
    }
    ref.read(themeSettingProvider.notifier).update((state) {
      return state.copyWith(schemeVariant: value);
    });
  }

  @override
  Widget build(BuildContext context) {
    final appLocalizations = context.appLocalizations;
    final themeColors = ref.watch(
      themeSettingProvider.select(
        (state) => ThemeColorsSelectorState(
          primaryColor: state.primaryColor,
          primaryColors: state.primaryColors,
          schemeVariant: state.schemeVariant,
          isDefault:
              state.primaryColor == defaultPrimaryColor &&
              intListEquality.equals(
                state.primaryColors,
                defaultPrimaryColors,
              ) &&
              state.schemeVariant == DynamicSchemeVariant.content,
        ),
      ),
    );
    final primaryColor = themeColors.primaryColor;
    final primaryColors = [null, ...themeColors.primaryColors];
    final schemeVariant = themeColors.schemeVariant;
    final isEquals = themeColors.isDefault;

    return SliverToBoxAdapter(
      child: CommonPopScope(
        onPop: (context) {
          if (_removablePrimaryColor != null) {
            setState(() {
              _removablePrimaryColor = null;
            });
            return false;
          }
          return true;
        },
        child: ItemCard(
          info: Info(
            label: appLocalizations.themeColor,
            iconData: Icons.palette,
          ),
          actions: genActions([
            if (_removablePrimaryColor == null)
              FilledButton(
                style: FilledButton.styleFrom(
                  visualDensity: VisualDensity.compact,
                ),
                onPressed: _handleChangeSchemeVariant,
                child: Text(schemeVariant.label),
              ),
            if (_removablePrimaryColor != null)
              FilledButton(
                style: FilledButton.styleFrom(
                  visualDensity: VisualDensity.compact,
                ),
                onPressed: _clearRemovable,
                child: Text(appLocalizations.cancel),
              ),
            if (_removablePrimaryColor == null && !isEquals)
              IconButton.filledTonal(
                tooltip: context.appLocalizations.reset,
                iconSize: 20,
                padding: const EdgeInsets.all(4),
                visualDensity: VisualDensity.compact,
                onPressed: _handleReset,
                icon: const Icon(Icons.replay),
              ),
          ], space: 8),
          child: Container(
            margin: const EdgeInsets.symmetric(horizontal: 16),
            child: _PrimaryColorGrid(
              colors: primaryColors,
              selectedColor: primaryColor,
              removableColor: _removablePrimaryColor,
              onSelect: _handleSelectColor,
              onRequestRemove: _markRemovable,
              onDelete: _handleDel,
              onAdd: _handleAdd,
            ),
          ),
        ),
      ),
    );
  }

  void _clearRemovable() {
    setState(() {
      _removablePrimaryColor = null;
    });
  }

  void _markRemovable(int? color) {
    setState(() {
      _removablePrimaryColor = color;
    });
  }

  void _handleSelectColor(int? color) {
    _clearRemovable();
    ref
        .read(themeSettingProvider.notifier)
        .update((state) => state.copyWith(primaryColor: color));
  }
}

class _PrimaryColorGrid extends StatelessWidget {
  const _PrimaryColorGrid({
    required this.colors,
    required this.selectedColor,
    required this.removableColor,
    required this.onSelect,
    required this.onRequestRemove,
    required this.onDelete,
    required this.onAdd,
  });

  final List<int?> colors;
  final int? selectedColor;
  final int? removableColor;
  final void Function(int? color) onSelect;
  final void Function(int? color) onRequestRemove;
  final VoidCallback onDelete;
  final VoidCallback onAdd;

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(
      builder: (_, constraints) {
        final columns = max((constraints.maxWidth / 96).ceil(), 3);
        final itemWidth = (constraints.maxWidth - (columns - 1) * 16) / columns;
        return Wrap(
          spacing: 16,
          runSpacing: 16,
          children: [
            for (final color in colors)
              _PrimaryColorTile(
                color: color,
                width: itemWidth,
                isSelected: color == selectedColor,
                isRemovable: removableColor != null && removableColor == color,
                onSelect: () => onSelect(color),
                onRequestRemove: () => onRequestRemove(color),
                onDelete: onDelete,
              ),
            if (removableColor == null)
              _AddPrimaryColorTile(width: itemWidth, onPressed: onAdd),
          ],
        );
      },
    );
  }
}

class _PrimaryColorTile extends StatelessWidget {
  const _PrimaryColorTile({
    required this.color,
    required this.width,
    required this.isSelected,
    required this.isRemovable,
    required this.onSelect,
    required this.onRequestRemove,
    required this.onDelete,
  });

  final int? color;
  final double width;
  final bool isSelected;
  final bool isRemovable;
  final VoidCallback onSelect;
  final VoidCallback onRequestRemove;
  final VoidCallback onDelete;

  @override
  Widget build(BuildContext context) {
    return Container(
      clipBehavior: Clip.none,
      width: width,
      height: width,
      child: Stack(
        alignment: Alignment.center,
        clipBehavior: Clip.none,
        children: [
          EffectGestureDetector(
            onLongPress: onRequestRemove,
            child: ColorSchemeBox(
              isSelected: isSelected,
              primaryColor: color != null ? Color(color!) : null,
              onPressed: onSelect,
            ),
          ),
          if (isRemovable)
            Container(
              color: Colors.white.opacity0,
              padding: const EdgeInsets.all(8),
              child: IconButton.filledTonal(
                tooltip: context.appLocalizations.delete,
                onPressed: onDelete,
                padding: const EdgeInsets.all(12),
                iconSize: 30,
                icon: Icon(color: context.colorScheme.primary, Icons.delete),
              ),
            ),
        ],
      ),
    );
  }
}

class _AddPrimaryColorTile extends StatelessWidget {
  const _AddPrimaryColorTile({required this.width, required this.onPressed});

  final double width;
  final VoidCallback onPressed;

  @override
  Widget build(BuildContext context) {
    return Container(
      width: width,
      height: width,
      padding: const EdgeInsets.all(4),
      child: IconButton.filledTonal(
        tooltip: context.appLocalizations.add,
        onPressed: onPressed,
        iconSize: 32,
        icon: Icon(color: context.colorScheme.primary, Icons.add),
      ),
    );
  }
}

class _PrueBlackItem extends ConsumerWidget {
  const _PrueBlackItem();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final appLocalizations = context.appLocalizations;
    final prueBlack = ref.watch(
      themeSettingProvider.select((state) => state.pureBlack),
    );
    return SliverToBoxAdapter(
      child: ListItem.toggle(
        leading: const Icon(Icons.contrast),
        horizontalTitleGap: 12,
        title: Text(
          appLocalizations.pureBlackMode,
          style: Theme.of(context).textTheme.titleSmall?.copyWith(
            color: context.colorScheme.onSurfaceVariant,
          ),
        ),
        value: prueBlack,
        onChanged: (value) {
          ref
              .read(themeSettingProvider.notifier)
              .update((state) => state.copyWith(pureBlack: value));
        },
      ),
    );
  }
}

class _TextScaleFactorItem extends ConsumerWidget {
  const _TextScaleFactorItem();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final appLocalizations = context.appLocalizations;
    final textScale = ref.watch(
      themeSettingProvider.select((state) => state.textScale),
    );
    final String process = '${(textScale.scale * 100).round()}%';
    return SliverToBoxAdapter(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Padding(
            padding: const EdgeInsets.only(bottom: 8),
            child: ListItem.toggle(
              leading: const Icon(Icons.text_fields),
              horizontalTitleGap: 12,
              title: Text(
                appLocalizations.textScale,
                style: Theme.of(context).textTheme.titleSmall?.copyWith(
                  color: context.colorScheme.onSurfaceVariant,
                ),
              ),
              value: textScale.enable,
              onChanged: (value) {
                ref
                    .read(themeSettingProvider.notifier)
                    .update((state) => state.copyWith.textScale(enable: value));
              },
            ),
          ),
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 16),
            child: Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              mainAxisSize: MainAxisSize.max,
              spacing: 32,
              children: [
                Expanded(
                  child: DisabledMask(
                    status: !textScale.enable,
                    child: ActivateBox(
                      active: textScale.enable,
                      child: SliderTheme(
                        data: SliderDefaultsM3(context),
                        child: Slider(
                          padding: EdgeInsets.zero,
                          min: minTextScale,
                          max: maxTextScale,
                          value: textScale.scale,
                          onChanged: (value) {
                            ref
                                .read(themeSettingProvider.notifier)
                                .update(
                                  (state) =>
                                      state.copyWith.textScale(scale: value),
                                );
                          },
                        ),
                      ),
                    ),
                  ),
                ),
                Padding(
                  padding: const EdgeInsets.only(right: 4),
                  child: Text(process, style: context.textTheme.titleMedium),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _PaletteDialog extends StatefulWidget {
  const _PaletteDialog();

  @override
  State<_PaletteDialog> createState() => _PaletteDialogState();
}

class _PaletteDialogState extends State<_PaletteDialog> {
  final _controller = ValueNotifier<Color>(Color(Hct.from(0, 0, 60).toInt()));

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final appLocalizations = context.appLocalizations;
    return CommonDialog(
      title: appLocalizations.palette,
      actions: [
        TextButton(
          onPressed: () {
            Navigator.of(context).pop();
          },
          child: Text(appLocalizations.cancel),
        ),
        TextButton(
          onPressed: () {
            Navigator.of(context).pop(_controller.value.toARGB32());
          },
          child: Text(appLocalizations.confirm),
        ),
      ],
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          SizedBox(width: 300, child: Palette(controller: _controller)),
        ],
      ),
    );
  }
}
