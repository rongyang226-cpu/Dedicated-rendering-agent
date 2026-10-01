import 'dart:convert';
import 'dart:typed_data';

import 'package:fl_clash/common/exception.dart';
import 'package:fl_clash/common/subscription.dart';
import 'package:flutter_test/flutter_test.dart';

Uint8List bytes(String value) => Uint8List.fromList(utf8.encode(value));
String text(Uint8List value) => utf8.decode(value);

void main() {
  test('keeps a full Clash/Mihomo YAML config', () {
    const source = '''
proxies:
  - name: node-a
    type: ss
    server: 1.1.1.1
    port: 443
    cipher: aes-128-gcm
    password: test
proxy-groups:
  - name: PROXY
    type: select
    proxies: [node-a]
rules:
  - MATCH,PROXY
''';
    expect(
      text(normalizeSubscriptionPayload(bytes(source))),
      contains('MATCH,PROXY'),
    );
  });
  test('wraps provider-style proxies into a usable profile', () {
    const provider = '''
proxies:
  - name: provider-node
    type: trojan
    server: example.com
    port: 443
    password: secret
''';
    final output = text(normalizeSubscriptionPayload(bytes(provider)));
    expect(output, contains('provider-node'));
    expect(output, contains('HUI-PROXY'));
    expect(output, contains('MATCH,HUI-PROXY'));
  });

  test('decodes a Base64 node subscription', () {
    const node =
        'vless://11111111-1111-1111-1111-111111111111@example.com:443?security=tls&type=ws&path=%2Fws#Tokyo';
    final encoded = base64Encode(utf8.encode(node));
    final output = text(normalizeSubscriptionPayload(bytes(encoded)));
    expect(output, contains('type: "vless"'));
    expect(output, contains('Tokyo'));
    expect(output, contains('HUI-PROXY'));
  });

  test('accepts a raw supported node list', () {
    const node = 'trojan://password@example.com:443?sni=example.com#JP';
    final output = text(normalizeSubscriptionPayload(bytes(node)));
    expect(output, contains('type: "trojan"'));
    expect(output, contains('JP'));
  });
  test('rejects HTML before config parsing', () {
    expect(
      () => normalizeSubscriptionPayload(
        bytes('<!doctype html><html><title>403</title></html>'),
        contentType: 'text/html; charset=utf-8',
      ),
      throwsA(
        isA<SubscriptionException>().having(
          (e) => e.userMessage,
          'message',
          '不是有效的代理配置',
        ),
      ),
    );
  });

  test('rejects empty content', () {
    expect(
      () => normalizeSubscriptionPayload(Uint8List(0)),
      throwsA(
        isA<SubscriptionException>().having(
          (e) => e.userMessage,
          'message',
          '下载内容为空',
        ),
      ),
    );
  });

  test('distinguishes malformed YAML', () {
    expect(
      () => normalizeSubscriptionPayload(bytes('proxies:\n  - name: [broken')),
      throwsA(
        isA<SubscriptionException>()
            .having((e) => e.stage, 'stage', 'yaml_parse')
            .having((e) => e.userMessage, 'message', 'YAML 配置解析失败'),
      ),
    );
  });
}
