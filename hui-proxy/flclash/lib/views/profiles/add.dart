import 'dart:async';
import 'dart:convert';

import 'package:fl_clash/common/common.dart';
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/pages/scan.dart';
import 'package:fl_clash/providers/action.dart';
import 'package:fl_clash/providers/core.dart';
import 'package:fl_clash/state.dart';
import 'package:fl_clash/widgets/widgets.dart';
import 'package:material_ui/material_ui.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter/services.dart';

bool _isSubscriptionUrl(String value) {
  final uri = Uri.tryParse(value.trim());
  return uri != null &&
      uri.host.isNotEmpty &&
      (uri.scheme == 'http' || uri.scheme == 'https');
}

class AddProfileView extends ConsumerWidget {
  final BuildContext context;

  const AddProfileView({super.key, required this.context});

  Future<void> _handleAddProfileFormFile(WidgetRef ref) async {
    unawaited(ref.read(profilesActionProvider.notifier).addProfileFormFile());
  }

  Future<void> _handleAddProfileFromClipboard(WidgetRef ref) async {
    final currentContext = context;
    final data = await Clipboard.getData(Clipboard.kTextPlain);
    final url = data?.text?.trim() ?? '';
    if (!currentContext.mounted) return;
    if (url.isEmpty || !_isSubscriptionUrl(url)) {
      currentContext.showNotifier(
        currentContext.appLocalizations.urlTip('').trim(),
        level: MessageLevel.warning,
      );
      return;
    }
    unawaited(ref.read(profilesActionProvider.notifier).addProfileFormURL(url));
  }

  Future<void> _handleManualCreate(WidgetRef ref) async {
    final data = await dialogs.showCommonDialog<String>(
      child: const ManualProfileDialog(),
    );
    if (data == null || data.trim().isEmpty) return;
    final saved = await globalState.safeRun<Profile>(() async {
      final profile = Profile.normal(label: '手动配置');
      return profile.saveFile(
        Uint8List.fromList(utf8.encode(data)),
        validate: (path) => ref.read(coreHandlerProvider).validateConfig(path),
      );
    }, title: '手动创建');
    if (saved != null) {
      ref.read(profilesActionProvider.notifier).putProfile(saved);
    }
  }

  Future<void> _toScan(WidgetRef ref) async {
    final profilesAction = ref.read(profilesActionProvider.notifier);
    if (system.isDesktop) {
      unawaited(profilesAction.addProfileFormQrCode());
      return;
    }
    final url = await BaseNavigator.push(context, const ScanPage());
    if (url != null) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        unawaited(profilesAction.addProfileFormURL(url));
      });
    }
  }

  Future<void> _toAdd(WidgetRef ref) async {
    final profilesAction = ref.read(profilesActionProvider.notifier);
    final appLocalizations = context.appLocalizations;
    final url = await dialogs.showCommonDialog<String>(
      child: InputDialog(
        autovalidateMode: AutovalidateMode.onUnfocus,
        title: appLocalizations.importFromURL,
        labelText: appLocalizations.url,
        value: '',
        inputFormatters: TextInputLimits.limit(TextInputLimits.url),
        validator: (value) {
          if (value == null || value.isEmpty) {
            return appLocalizations.emptyTip('').trim();
          }
          if (!_isSubscriptionUrl(value)) {
            return appLocalizations.urlTip('').trim();
          }
          return null;
        },
      ),
    );
    if (url != null) {
      unawaited(profilesAction.addProfileFormURL(url));
    }
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final appLocalizations = context.appLocalizations;
    return ListView(
      children: [
        ListItem(
          leading: const Icon(Icons.link_rounded),
          title: Text(appLocalizations.importFromURL),
          subtitle: Text(appLocalizations.urlDesc),
          onTap: () => _toAdd(ref),
        ),
        ListItem(
          leading: const Icon(Icons.content_paste_rounded),
          title: Text(appLocalizations.clipboardImport),
          subtitle: Text(appLocalizations.urlDesc),
          onTap: () => _handleAddProfileFromClipboard(ref),
        ),
        ListItem(
          leading: const Icon(Icons.upload_file_sharp),
          title: Text(appLocalizations.file),
          subtitle: Text(appLocalizations.fileDesc),
          onTap: () => _handleAddProfileFormFile(ref),
        ),
        ListItem(
          leading: const Icon(Icons.edit_note_rounded),
          title: const Text('手动创建'),
          subtitle: const Text('编辑完整 Mihomo YAML 配置'),
          onTap: () => _handleManualCreate(ref),
        ),
        ListItem(
          leading: const Icon(Icons.qr_code_sharp),
          title: Text(appLocalizations.qrcode),
          subtitle: Text(appLocalizations.qrcodeDesc),
          onTap: () => _toScan(ref),
        ),
      ],
    );
  }
}

class ManualProfileDialog extends StatefulWidget {
  const ManualProfileDialog({super.key});

  @override
  State<ManualProfileDialog> createState() => _ManualProfileDialogState();
}

class _ManualProfileDialogState extends State<ManualProfileDialog> {
  final _controller = TextEditingController(
    text: '''mixed-port: 7890
mode: rule
proxies: []
proxy-groups: []
rules:
  - MATCH,DIRECT
''',
  );

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return CommonDialog(
      title: '手动创建',
      actions: [
        TextButton(
          onPressed: () => Navigator.of(context).pop(),
          child: const Text('取消'),
        ),
        FilledButton(
          onPressed: () => Navigator.of(context).pop(_controller.text),
          child: const Text('验证并保存'),
        ),
      ],
      child: SizedBox(
        width: 520,
        child: TextField(
          controller: _controller,
          minLines: 12,
          maxLines: 22,
          keyboardType: TextInputType.multiline,
          textInputAction: TextInputAction.newline,
          style: const TextStyle(fontFamily: 'JetBrainsMono'),
          decoration: const InputDecoration(
            labelText: 'Mihomo YAML',
            alignLabelWithHint: true,
            border: OutlineInputBorder(),
          ),
        ),
      ),
    );
  }
}

class URLFormDialog extends StatefulWidget {
  const URLFormDialog({super.key});

  @override
  State<URLFormDialog> createState() => _URLFormDialogState();
}

class _URLFormDialogState extends State<URLFormDialog> {
  final _urlController = TextEditingController();

  Future<void> _handleAddProfileFormURL() async {
    final url = _urlController.value.text;
    if (url.isEmpty) return;
    Navigator.of(context).pop<String>(url);
  }

  @override
  void dispose() {
    _urlController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final appLocalizations = context.appLocalizations;
    return CommonDialog(
      title: appLocalizations.importFromURL,
      actions: [
        TextButton(
          onPressed: _handleAddProfileFormURL,
          child: Text(appLocalizations.submit),
        ),
      ],
      child: SizedBox(
        width: 300,
        child: Wrap(
          runSpacing: 16,
          children: [
            TextField(
              keyboardType: TextInputType.url,
              minLines: 1,
              maxLines: 5,
              inputFormatters: TextInputLimits.limit(TextInputLimits.url),
              onSubmitted: (_) {
                _handleAddProfileFormURL();
              },
              onEditingComplete: _handleAddProfileFormURL,
              controller: _urlController,
              decoration: InputDecoration(labelText: appLocalizations.url),
            ),
          ],
        ),
      ),
    );
  }
}
