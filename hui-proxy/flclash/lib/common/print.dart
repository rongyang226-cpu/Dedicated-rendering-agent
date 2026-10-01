import 'package:dio/dio.dart';
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/providers/app.dart';
import 'package:fl_clash/state.dart';
import 'package:material_ui/material_ui.dart';

String redactSensitiveText(String input) {
  var output = input;
  final keyValuePattern = RegExp(
    r'\b(password|passwd|token|secret|authorization|proxy-authorization|username)\b\s*[:=]\s*([^\s,;}&#?]+)',
    caseSensitive: false,
  );
  final keyValueSource = output;
  output = keyValueSource.replaceAllMapped(keyValuePattern, (match) {
    final previous = match.start > 0 ? keyValueSource[match.start - 1] : '';
    final separator = previous == '?' || previous == '&' ? '=' : ': ';
    return '${match.group(1)}$separator****';
  });
  output = output.replaceAllMapped(
    RegExp(r'\bBearer\s+[^\s,;}]+', caseSensitive: false),
    (_) => 'Bearer ****',
  );
  output = output.replaceAllMapped(
    RegExp(r'://([^:/@\s]+):([^@/\s]+)@'),
    (_) => '://***:***@',
  );
  output = output.replaceAllMapped(
    RegExp(
      r'([?&](?:token|key|auth|password|passwd|secret)=)[^&#\s]+',
      caseSensitive: false,
    ),
    (match) => '${match.group(1)}****',
  );
  return output;
}

String redactUrlForLog(String input) {
  final uri = Uri.tryParse(input.trim());
  if (uri == null || uri.host.isEmpty) {
    return redactSensitiveText(input);
  }
  final port = uri.hasPort ? ':${uri.port}' : '';
  final queryKeys = uri.queryParametersAll.keys.toList()..sort();
  final query = queryKeys.isEmpty
      ? ''
      : '?${queryKeys.map((key) => '$key=****').join('&')}';
  return '${uri.scheme}://${uri.host}$port/…$query';
}

String compactError(Object error) {
  if (error is DioException) {
    final statusCode = error.response?.statusCode;
    return statusCode != null
        ? 'DioException(${error.type.name}, HTTP $statusCode)'
        : 'DioException(${error.type.name})';
  }
  return redactSensitiveText(error.toString());
}

class CommonPrint {
  static CommonPrint? _instance;

  CommonPrint._internal();

  factory CommonPrint() {
    _instance ??= CommonPrint._internal();
    return _instance!;
  }

  void log(String? text, {LogLevel logLevel = LogLevel.info}) {
    final payload = redactSensitiveText('[APP] $text');
    debugPrint(payload);
    if (!globalState.isAttach) {
      return;
    }
    globalState.container
        .read(logsProvider.notifier)
        .add(Log.app(payload).copyWith(logLevel: logLevel));
  }
}

final commonPrint = CommonPrint();
