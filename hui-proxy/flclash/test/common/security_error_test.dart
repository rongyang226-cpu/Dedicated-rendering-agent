import 'package:fl_clash/common/app_localizations.dart';
import 'package:fl_clash/common/exception.dart';
import 'package:fl_clash/common/print.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  group('log redaction', () {
    test('redacts key value credentials', () {
      final value = redactSensitiveText(
        'password: demo token=abc username=test authorization=BearerValue',
      );
      expect(value, isNot(contains('demo')));
      expect(value, isNot(contains('abc')));
      expect(value, isNot(contains('BearerValue')));
    });

    test('redacts URL credentials and query secrets', () {
      final value = redactSensitiveText(
        'https://user:pass@example.com/sub?token=abc&name=ok',
      );
      expect(value, contains('://***:***@'));
      expect(value, contains('token=****'));
      expect(value, contains('name=ok'));
    });
  });
  group('config error classification', () {
    test('classifies YAML errors', () {
      final value = configValidationErrorMessage(
        const MessageException('yaml: line 3 column 2 invalid mapping'),
      );
      expect(value, startsWith('YAML ·'));
    });

    test('classifies DNS and provider errors', () {
      expect(
        configValidationErrorMessage(
          const MessageException('invalid nameserver value'),
        ),
        startsWith('DNS ·'),
      );
      expect(
        configValidationErrorMessage(
          const MessageException('proxy-provider download failed'),
        ),
        startsWith('Provider ·'),
      );
    });
  });
}
