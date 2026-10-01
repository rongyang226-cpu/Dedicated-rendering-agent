import 'package:dio/dio.dart';
import 'package:fl_clash/common/exception.dart';
import 'package:fl_clash/core/desktop/launch_policy.dart';
import 'package:fl_clash/core/method.dart';
import 'package:fl_clash/l10n/l10n.dart';

import 'dart:ui';

final currentAppLocalizations = AppLocalizations.current;

String? networkErrorMessage(Object error, AppLocalizations appLocalizations) {
  if (error is SubscriptionException) {
    return error.userMessage;
  }
  if (error case CoreMethodException(:final code)) {
    return switch (code) {
      'request_bad_response' => appLocalizations.networkException,
      'request_error' => appLocalizations.unknownNetworkError,
      _ => null,
    };
  }
  if (error is DioException) {
    return error.type == DioExceptionType.badResponse
        ? appLocalizations.networkException
        : appLocalizations.unknownNetworkError;
  }
  return null;
}

String? coreLaunchBlockedMessage(
  Object error,
  AppLocalizations appLocalizations,
) {
  if (!isPolicyBlockedLaunch(error)) {
    return null;
  }
  return switch (smartAppControlStateReader()) {
    SmartAppControlState.on || SmartAppControlState.evaluation =>
      appLocalizations.coreBlockedBySmartAppControlTip,
    _ => appLocalizations.coreBlockedByPolicyTip(launchOsError(error)!),
  };
}

String? configValidationErrorMessage(Object error) {
  if (error is! MessageException) return null;
  final raw = error.message.trim();
  if (raw.isEmpty) return null;
  final lower = raw.toLowerCase();

  String? category;
  if (lower.contains('yaml') ||
      lower.contains('unmarshal') ||
      lower.contains('mapping values') ||
      (lower.contains('line ') && lower.contains('column'))) {
    category = 'YAML';
  } else if (lower.contains('proxy-provider') ||
      lower.contains('rule-provider') ||
      lower.contains('provider')) {
    category = 'Provider';
  } else if (lower.contains('nameserver') ||
      lower.contains('fake-ip') ||
      lower.contains('dns')) {
    category = 'DNS';
  } else if (lower.contains('vless') ||
      lower.contains('vmess') ||
      lower.contains('trojan') ||
      lower.contains('hysteria') ||
      lower.contains('tuic') ||
      lower.contains('reality') ||
      lower.contains('proxy')) {
    category = '节点';
  } else if (lower.contains('permission') || lower.contains('denied')) {
    category = '权限';
  } else if (lower.contains('no such file') ||
      lower.contains('not exist') ||
      lower.contains('path')) {
    category = '路径';
  } else if (lower.contains('unsupported') ||
      lower.contains('not support') ||
      lower.contains('unknown type')) {
    category = 'Mihomo';
  }
  return category == null ? null : '$category · $raw';
}

String userFacingErrorMessage(Object error, AppLocalizations appLocalizations) {
  return networkErrorMessage(error, appLocalizations) ??
      coreLaunchBlockedMessage(error, appLocalizations) ??
      configValidationErrorMessage(error) ??
      switch (error) {
        CoreMethodException(:final message) => message,
        _ => error.toString(),
      };
}

Locale? getLocaleForString(String? localString) {
  if (localString == null) return null;
  final localSplit = localString.split('_');
  if (localSplit.length == 1) {
    return Locale(localSplit[0]);
  }
  if (localSplit.length == 2) {
    return Locale(localSplit[0], localSplit[1]);
  }
  if (localSplit.length == 3) {
    return Locale.fromSubtags(
      languageCode: localSplit[0],
      scriptCode: localSplit[1],
      countryCode: localSplit[2],
    );
  }
  return null;
}
