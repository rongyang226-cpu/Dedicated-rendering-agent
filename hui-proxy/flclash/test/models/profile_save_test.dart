import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'package:fl_clash/common/common.dart';
import 'package:fl_clash/models/models.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:path_provider_platform_interface/path_provider_platform_interface.dart';

class _FakePathProvider extends PathProviderPlatform {
  final String root;

  _FakePathProvider(this.root);

  @override
  Future<String?> getTemporaryPath() async => root;

  @override
  Future<String?> getApplicationSupportPath() async => root;

  @override
  Future<String?> getApplicationCachePath() async => root;
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  late Directory tempDir;

  setUpAll(() {
    tempDir = Directory.systemTemp.createTempSync('profile_save_test');
    PathProviderPlatform.instance = _FakePathProvider(tempDir.path);
  });

  tearDownAll(() {
    try {
      tempDir.deleteSync(recursive: true);
    } catch (_) {}
  });

  group('Profile.saveFile', () {
    // EditProfileView relies on this throwing rather than silently
    // succeeding, so a caller-level try/catch can surface the failure.
    test('rejects an invalid config without touching the saved file', () async {
      final profile = Profile.normal(label: 'p');
      final bytes = Uint8List.fromList(utf8.encode('bad: ['));

      await expectLater(
        profile.saveFile(bytes, validate: (_) async => 'invalid config'),
        throwsA(
          isA<MessageException>().having(
            (e) => e.message,
            'message',
            'invalid config',
          ),
        ),
      );

      final savedFile = await profile.file;
      expect(await savedFile.length(), 0);
    });

    test('copies validated bytes and stamps the update time', () async {
      final profile = Profile.normal(label: 'p');
      final bytes = Uint8List.fromList(utf8.encode('proxies: []'));

      final saved = await profile.saveFile(bytes, validate: (_) async => '');

      expect(saved.lastUpdateDate, isNotNull);
      final savedFile = await profile.file;
      expect(await savedFile.readAsString(), 'proxies: []');
    });

    test('failed update keeps the previous valid config untouched', () async {
      final profile = Profile.normal(label: 'existing');
      final savedFile = await profile.file;
      await savedFile.writeAsString('proxies: []\nrules: [MATCH,DIRECT]\n');

      await expectLater(
        profile.saveFile(
          Uint8List.fromList(utf8.encode('bad: [')),
          validate: (_) async => 'invalid config',
        ),
        throwsA(isA<MessageException>()),
      );

      expect(
        await savedFile.readAsString(),
        'proxies: []\nrules: [MATCH,DIRECT]\n',
      );
    });

    test('subscription validation failure is classified for users', () async {
      final profile = Profile.normal(label: 'subscription');
      await expectLater(
        profile.saveFile(
          Uint8List.fromList(utf8.encode('proxies: []')),
          validate: (_) async => 'proxy 0: unsupported type',
          subscriptionSource: true,
        ),
        throwsA(
          isA<SubscriptionException>().having(
            (e) => e.userMessage,
            'userMessage',
            'Mihomo 配置验证失败',
          ),
        ),
      );
    });
  });
}
