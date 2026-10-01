import 'dart:convert';
import 'dart:io';

import 'package:fl_clash/common/common.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  late HttpServer server;
  late Request client;
  late String baseUrl;
  String? lastUserAgent;

  setUpAll(() async {
    HttpOverrides.global = null;
    server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    baseUrl = 'http://${server.address.address}:${server.port}';
    client = Request()..userAgent = 'HuiTest/1.0';

    server.listen((request) async {
      lastUserAgent = request.headers.value(HttpHeaders.userAgentHeader);
      switch (request.uri.path) {
        case '/yaml':
          request.response.headers.contentType = ContentType.text;
          request.response.write('proxies: []\nrules:\n  - MATCH,DIRECT\n');
        case '/redirect301':
          request.response.statusCode = HttpStatus.movedPermanently;
          request.response.headers.set(HttpHeaders.locationHeader, '/yaml');
        case '/redirect302':
          request.response.statusCode = HttpStatus.found;
          request.response.headers.set(HttpHeaders.locationHeader, '/yaml');
        case '/redirect307':
          request.response.statusCode = HttpStatus.temporaryRedirect;
          request.response.headers.set(HttpHeaders.locationHeader, '/yaml');
        case '/redirect308':
          request.response.statusCode = HttpStatus.permanentRedirect;
          request.response.headers.set(HttpHeaders.locationHeader, '/yaml');
        case '/403':
          request.response.statusCode = HttpStatus.forbidden;
          request.response.write('<html>forbidden</html>');
        case '/404':
          request.response.statusCode = HttpStatus.notFound;
        case '/429':
          request.response.statusCode = HttpStatus.tooManyRequests;
        case '/500':
          request.response.statusCode = HttpStatus.internalServerError;
        case '/html':
          request.response.headers.contentType = ContentType.html;
          request.response.write('<!doctype html><html>bad</html>');
        case '/empty':
          request.response.statusCode = HttpStatus.ok;
        default:
          request.response.statusCode = HttpStatus.notFound;
      }
      await request.response.close();
    });
  });

  tearDownAll(() async {
    await server.close(force: true);
  });

  test('downloads normal YAML and sends a User-Agent', () async {
    final response = await client.getFileResponseForUrl('$baseUrl/yaml');
    final text = utf8.decode(response.data!);
    expect(response.statusCode, HttpStatus.ok);
    expect(text, contains('MATCH,DIRECT'));
    expect(lastUserAgent, 'HuiTest/1.0');
  });

  for (final path in [
    '/redirect301',
    '/redirect302',
    '/redirect307',
    '/redirect308',
  ]) {
    test('follows $path to subscription content', () async {
      final response = await client.getFileResponseForUrl('$baseUrl$path');
      expect(response.statusCode, HttpStatus.ok);
      expect(utf8.decode(response.data!), contains('MATCH,DIRECT'));
    });
  }

  for (final entry in {
    '/403': '服务器拒绝请求（HTTP 403）',
    '/404': '订阅不存在（HTTP 404）',
    '/429': '请求过于频繁（HTTP 429）',
    '/500': '服务器错误（HTTP 500）',
  }.entries) {
    test('maps ${entry.key} to a specific subscription error', () async {
      await expectLater(
        client.getFileResponseForUrl('$baseUrl${entry.key}'),
        throwsA(
          isA<SubscriptionException>().having(
            (e) => e.userMessage,
            'userMessage',
            entry.value,
          ),
        ),
      );
    });
  }

  test('rejects HTML before config validation', () async {
    final response = await client.getFileResponseForUrl('$baseUrl/html');
    expect(
      () => normalizeSubscriptionPayload(
        response.data!,
        contentType: response.headers.value('content-type'),
      ),
      throwsA(
        isA<SubscriptionException>().having(
          (e) => e.userMessage,
          'userMessage',
          '不是有效的代理配置',
        ),
      ),
    );
  });

  test('rejects empty content before config validation', () async {
    final response = await client.getFileResponseForUrl('$baseUrl/empty');
    expect(
      () => normalizeSubscriptionPayload(response.data!),
      throwsA(
        isA<SubscriptionException>().having(
          (e) => e.userMessage,
          'userMessage',
          '下载内容为空',
        ),
      ),
    );
  });
}
