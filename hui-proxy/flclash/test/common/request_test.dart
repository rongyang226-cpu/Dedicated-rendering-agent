import 'dart:io';

import 'package:dio/dio.dart';
import 'package:fl_clash/common/exception.dart';
import 'package:fl_clash/common/request.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  late Request client;
  late RequestOptions options;

  setUp(() {
    client = Request();
    options = RequestOptions(path: 'https://example.com/sub?token=secret');
  });

  group('subscription transport policy', () {
    test('always uses system DIRECT for subscription fetches', () {
      expect(
        subscriptionFindProxy(Uri.parse('https://example.com/sub')),
        'DIRECT',
      );
    });

    test('has bounded timeouts and redirects', () {
      expect(subscriptionConnectTimeout, const Duration(seconds: 12));
      expect(subscriptionReceiveTimeout, const Duration(seconds: 30));
      expect(subscriptionFollowRedirects, isTrue);
      expect(subscriptionMaxRedirects, 8);
    });
  });
  test(
    'downloads bytes through a redirect before the core is attached',
    () async {
      final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
      addTearDown(() => server.close(force: true));
      server.listen((request) async {
        if (request.uri.path == '/redirect') {
          request.response
            ..statusCode = HttpStatus.found
            ..headers.set(HttpHeaders.locationHeader, '/subscription');
        } else {
          request.response
            ..statusCode = HttpStatus.ok
            ..write('proxies: []');
        }
        await request.response.close();
      });
      client.userAgent = 'HuiTransportTest';
      final response = await client.getFileResponseForUrl(
        'http://127.0.0.1:${server.port}/redirect?token=test',
      );
      expect(String.fromCharCodes(response.data!), 'proxies: []');
    },
  );
  SubscriptionException statusError(int status) {
    final response = Response<Object?>(
      requestOptions: options,
      statusCode: status,
      headers: Headers.fromMap({
        Headers.contentTypeHeader: ['text/html'],
      }),
      data: 'error',
    );
    return client.mapSubscriptionDioException(
      DioException(
        requestOptions: options,
        response: response,
        type: DioExceptionType.badResponse,
      ),
    );
  }

  group('HTTP status classification', () {
    test('403', () {
      expect(statusError(403).userMessage, '服务器拒绝请求（HTTP 403）');
    });
    test('404', () {
      expect(statusError(404).userMessage, '订阅不存在（HTTP 404）');
    });
    test('429', () {
      expect(statusError(429).userMessage, '请求过于频繁（HTTP 429）');
    });
    test('5xx', () {
      expect(statusError(500).userMessage, '服务器错误（HTTP 500）');
    });
  });
  group('network exception classification', () {
    test('timeout', () {
      final error = DioException(
        requestOptions: options,
        type: DioExceptionType.connectionTimeout,
      );
      expect(client.mapSubscriptionDioException(error).userMessage, '连接服务器超时');
    });

    test('DNS lookup failure', () {
      final error = DioException(
        requestOptions: options,
        type: DioExceptionType.connectionError,
        error: const SocketException('Failed host lookup: no.such.host'),
      );
      expect(
        client.mapSubscriptionDioException(error).userMessage,
        '无法解析服务器地址',
      );
    });

    test('TLS handshake failure', () {
      final error = DioException(
        requestOptions: options,
        type: DioExceptionType.connectionError,
        error: const HandshakeException('CERTIFICATE_VERIFY_FAILED'),
      );
      expect(
        client.mapSubscriptionDioException(error).userMessage,
        'SSL/TLS 连接失败',
      );
    });

    test('connection refused', () {
      final error = DioException(
        requestOptions: options,
        type: DioExceptionType.connectionError,
        error: const SocketException('Connection refused'),
      );
      expect(
        client.mapSubscriptionDioException(error).userMessage,
        '服务器拒绝连接（检查订阅地址和端口）',
      );
    });

    test('unreachable network', () {
      final error = DioException(
        requestOptions: options,
        type: DioExceptionType.connectionError,
        error: const SocketException('Network is unreachable'),
      );
      expect(
        client.mapSubscriptionDioException(error).userMessage,
        '网络无法到达订阅服务器（检查当前网络和 IPv6）',
      );
    });
    test(
      'unknown transport failure reports safe type without URL or token',
      () {
        final error = DioException(
          requestOptions: options,
          type: DioExceptionType.unknown,
          error: StateError('https://example.com/sub?token=secret'),
        );
        final message = client.mapSubscriptionDioException(error).userMessage;
        expect(message, contains('unknown / StateError'));
        expect(message, isNot(contains('example.com')));
        expect(message, isNot(contains('secret')));
      },
    );
  });
}
