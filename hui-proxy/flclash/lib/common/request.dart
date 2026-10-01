import 'dart:async';
import 'dart:io';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:dio/io.dart';
import 'package:fl_clash/common/common.dart';
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/state.dart';

const subscriptionConnectTimeout = Duration(seconds: 12);
const subscriptionSendTimeout = Duration(seconds: 15);
const subscriptionReceiveTimeout = Duration(seconds: 30);
const subscriptionFollowRedirects = true;
const subscriptionMaxRedirects = 8;

String subscriptionFindProxy(Uri _) => 'DIRECT';

class Request {
  late final Dio dio;
  late final Dio _clashDio;
  late final Dio _subscriptionDio;
  String? userAgent;

  ProviderReader? _read;

  void attach(ProviderReader read) {
    _read = read;
  }

  Request() {
    dio = Dio(BaseOptions(headers: {'User-Agent': browserUa}));
    _clashDio = Dio();
    _clashDio.httpClientAdapter = IOHttpClientAdapter(
      createHttpClient: () {
        final client = HttpClient();
        client.findProxy = (Uri uri) {
          client.userAgent = globalState.ua;
          final read = _read;
          if (read == null) return 'DIRECT';
          return FlClashHttpOverrides.findProxyForReader(read, uri);
        };
        return client;
      },
    );
    _subscriptionDio = Dio(
      BaseOptions(
        connectTimeout: subscriptionConnectTimeout,
        sendTimeout: subscriptionSendTimeout,
        receiveTimeout: subscriptionReceiveTimeout,
        followRedirects: subscriptionFollowRedirects,
        maxRedirects: subscriptionMaxRedirects,
        headers: const {
          'Accept':
              'application/yaml, text/yaml, text/plain, application/octet-stream, */*',
        },
      ),
    );
    _subscriptionDio.httpClientAdapter = IOHttpClientAdapter(
      createHttpClient: () {
        final client = HttpClient()
          ..connectionTimeout = subscriptionConnectTimeout;
        client.findProxy = subscriptionFindProxy;
        final read = _read;
        if (read != null) {
          client.badCertificateCallback = (certificate, host, port) =>
              FlClashHttpOverrides.allowBadCertificateForReader(
                read,
                certificate,
                host,
                port,
              );
        }
        return client;
      },
    );
  }

  Future<Response<Uint8List>> getFileResponseForUrl(String url) async {
    final safeUrl = redactUrlForLog(url);
    try {
      final response = await _subscriptionDio.get<Uint8List>(
        url,
        options: Options(
          responseType: ResponseType.bytes,
          headers: {
            'User-Agent': userAgent?.trim().isNotEmpty == true
                ? userAgent!.trim()
                : globalState.ua,
          },
        ),
      );
      commonPrint.log(
        'SubscriptionFetchSuccess url=$safeUrl httpCode=${response.statusCode} '
        'contentType=${response.headers.value('content-type') ?? '-'} '
        'responseLength=${response.data?.length ?? 0}',
      );
      return response;
    } on DioException catch (error) {
      final mapped = mapSubscriptionDioException(error);
      _logSubscriptionFailure(safeUrl, mapped, error);
      throw mapped;
    } catch (error) {
      final mapped = SubscriptionException(
        stage: 'http_request',
        userMessage: '无法连接服务器（${error.runtimeType}）',
        detail: error.runtimeType.toString(),
        cause: error,
      );
      _logSubscriptionFailure(safeUrl, mapped, error);
      throw mapped;
    }
  }

  SubscriptionException mapSubscriptionDioException(DioException error) {
    final status = error.response?.statusCode;
    final contentType = error.response?.headers.value('content-type');
    final data = error.response?.data;
    final responseLength = switch (data) {
      final Uint8List bytes => bytes.length,
      final List<int> bytes => bytes.length,
      final String text => text.length,
      _ => null,
    };
    String userMessage;
    if (status != null) {
      userMessage = switch (status) {
        403 => '服务器拒绝请求（HTTP 403）',
        404 => '订阅不存在（HTTP 404）',
        429 => '请求过于频繁（HTTP 429）',
        >= 500 && <= 599 => '服务器错误（HTTP $status）',
        _ => '服务器拒绝请求（HTTP $status）',
      };
    } else {
      userMessage = switch (error.type) {
        DioExceptionType.connectionTimeout ||
        DioExceptionType.sendTimeout ||
        DioExceptionType.receiveTimeout => '连接服务器超时',
        DioExceptionType.badCertificate => 'SSL/TLS 连接失败',
        DioExceptionType.connectionError => _connectionErrorMessage(
          error.error,
          type: error.type,
        ),
        _ => _connectionErrorMessage(error.error, type: error.type),
      };
    }
    return SubscriptionException(
      stage: 'http_request',
      userMessage: userMessage,
      detail: error.error?.runtimeType.toString() ?? error.type.name,
      httpCode: status,
      contentType: contentType,
      responseLength: responseLength,
      cause: error.error ?? error,
    );
  }

  String _connectionErrorMessage(
    Object? cause, {
    required DioExceptionType type,
  }) {
    final errno = cause is SocketException ? cause.osError?.errorCode : null;
    if (errno == 111) return '服务器拒绝连接（检查订阅地址和端口）';
    if (errno == 101 || errno == 113) {
      return '网络无法到达订阅服务器（检查当前网络和 IPv6）';
    }
    if (errno == 110) return '连接服务器超时';
    if (errno == 104) return '连接被服务器中断';
    final description = '${cause.runtimeType} ${cause ?? ''}'.toLowerCase();
    if (description.contains('failed host lookup') ||
        description.contains('unknownhost') ||
        description.contains('no address associated with hostname') ||
        description.contains('name or service not known') ||
        description.contains('nodename nor servname')) {
      return '无法解析服务器地址';
    }
    if (description.contains('handshake') ||
        description.contains('certificate') ||
        description.contains('tls')) {
      return 'SSL/TLS 连接失败';
    }
    if (description.contains('connection refused')) {
      return '服务器拒绝连接（检查订阅地址和端口）';
    }
    if (description.contains('network is unreachable') ||
        description.contains('no route to host')) {
      return '网络无法到达订阅服务器（检查当前网络和 IPv6）';
    }
    if (description.contains('connection reset')) {
      return '连接被服务器中断';
    }
    // A small, non-sensitive diagnostic survives when the platform gives an
    // unrecognized error. Never show the raw exception: it may contain a URL.
    final code = errno == null ? '' : '，系统错误码 $errno';
    return '无法连接服务器（${type.name} / ${cause.runtimeType}$code）';
  }

  void _logSubscriptionFailure(
    String safeUrl,
    SubscriptionException mapped,
    Object raw,
  ) {
    final cause = raw is DioException ? raw.error ?? raw : raw;
    final socketError = cause is SocketException
        ? cause.osError?.errorCode
        : null;
    commonPrint.log(
      'SubscriptionFetchError url=$safeUrl stage=${mapped.stage} '
      'httpCode=${mapped.httpCode ?? '-'} '
      'contentType=${mapped.contentType ?? '-'} '
      'responseLength=${mapped.responseLength ?? '-'} '
      'dioType=${raw is DioException ? raw.type.name : '-'} '
      'exception=${cause.runtimeType} socketErrno=${socketError ?? '-'}',
      logLevel: LogLevel.warning,
    );
  }

  Future<Response<String>> getTextResponseForUrl(String url) async {
    try {
      return await _clashDio.get<String>(
        url,
        options: Options(responseType: ResponseType.plain),
      );
    } catch (e) {
      commonPrint.log(
        'getTextResponseForUrl error ${compactError(e)}',
        logLevel: LogLevel.warning,
      );
      rethrow;
    }
  }

  Future<Map<String, dynamic>?> checkForUpdate() async {
    try {
      final response = await dio.get(
        'https://api.github.com/repos/$repository/releases/latest',
        options: Options(responseType: ResponseType.json),
      );
      if (response.statusCode != 200) return null;
      final data = response.data as Map<String, dynamic>;
      final remoteVersion = data['tag_name'];
      final version = globalState.packageInfo.version;
      final hasUpdate =
          compareVersions(remoteVersion.replaceAll('v', ''), version) > 0;
      if (!hasUpdate) return null;
      return data;
    } catch (e) {
      commonPrint.log('checkForUpdate failed', logLevel: LogLevel.warning);
      return null;
    }
  }

  final Map<String, IpInfo Function(Map<String, dynamic>)> _ipInfoSources = {
    'https://ipwho.is': IpInfo.fromIpWhoIsJson,
    'https://api.myip.com': IpInfo.fromMyIpJson,
    'https://ipapi.co/json': IpInfo.fromIpApiCoJson,
    'https://ident.me/json': IpInfo.fromIdentMeJson,
    'http://ip-api.com/json': IpInfo.fromIpAPIJson,
    'https://api.ip.sb/geoip': IpInfo.fromIpSbJson,
    'https://ipinfo.io/json': IpInfo.fromIpInfoIoJson,
  };

  Future<Result<IpInfo?>> checkIp({CancelToken? cancelToken}) async {
    var failureCount = 0;
    final token = cancelToken ?? CancelToken();
    final futures = _ipInfoSources.entries.map((source) async {
      final Completer<Result<IpInfo?>> completer = Completer();
      void handleFailRes() {
        if (!completer.isCompleted && failureCount == _ipInfoSources.length) {
          completer.complete(Result.success(null));
        }
      }

      final future = dio
          .get<Map<String, dynamic>>(
            source.key,
            cancelToken: token,
            options: Options(responseType: ResponseType.json),
          )
          .timeout(const Duration(seconds: 10));
      unawaited(
        future
            .then((res) {
              if (res.statusCode == HttpStatus.ok && res.data != null) {
                completer.complete(Result.success(source.value(res.data!)));
                return;
              }
              commonPrint.log('checkIp data empty', logLevel: LogLevel.info);
              failureCount++;
              handleFailRes();
            })
            .catchError((e) {
              failureCount++;
              if (e is DioException && e.type == DioExceptionType.cancel) {
                completer.complete(Result.error('cancelled'));
                return;
              }
              commonPrint.log('checkIp error $e', logLevel: LogLevel.warning);
              handleFailRes();
            }),
      );
      return completer.future;
    });
    final res = await Future.any(futures);
    token.cancel();
    return res;
  }
}

final request = Request();

String? getFileNameForDisposition(String? disposition) {
  if (disposition == null) return null;
  final parseValue = HeaderValue.parse(disposition);
  final parameters = parseValue.parameters;
  final fileNamePointKey = parameters.keys.firstWhere(
    (key) => key == 'filename*',
    orElse: () => '',
  );
  if (fileNamePointKey.isNotEmpty) {
    final res = parameters[fileNamePointKey]?.split("''") ?? [];
    if (res.length >= 2) {
      return Uri.decodeComponent(res[1]);
    }
  }
  final fileNameKey = parameters.keys.firstWhere(
    (key) => key == 'filename',
    orElse: () => '',
  );
  if (fileNameKey.isEmpty) return null;
  return parameters[fileNameKey];
}
