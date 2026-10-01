import 'dart:convert';
import 'dart:typed_data';

import 'package:fl_clash/common/exception.dart';
import 'package:fl_clash/common/yaml.dart';
import 'package:yaml/yaml.dart' as yaml_parser;

Uint8List normalizeSubscriptionPayload(Uint8List bytes, {String? contentType}) {
  String text;
  try {
    text = utf8.decode(bytes).replaceFirst('\ufeff', '').trim();
  } catch (error) {
    throw SubscriptionException(
      stage: 'content_decode',
      userMessage: '不是有效的代理配置',
      detail: error.toString(),
      cause: error,
    );
  }
  if (text.isEmpty) {
    throw const SubscriptionException(
      stage: 'content_check',
      userMessage: '下载内容为空',
    );
  }
  if (_looksLikeHtml(text, contentType)) {
    throw const SubscriptionException(
      stage: 'content_check',
      userMessage: '不是有效的代理配置',
      detail: 'HTML response received',
    );
  }

  final decoded = _tryDecodeWholeBase64(text);
  if (decoded != null) text = decoded;

  final nodeConfig = _nodeSubscriptionToYaml(text);
  if (nodeConfig != null) {
    return Uint8List.fromList(utf8.encode(nodeConfig));
  }

  dynamic parsed;
  try {
    parsed = yaml_parser.loadYaml(text);
  } catch (error) {
    throw SubscriptionException(
      stage: 'yaml_parse',
      userMessage: 'YAML 配置解析失败',
      detail: error.toString(),
      cause: error,
    );
  }
  final root = _toDart(parsed);
  if (root is Map<String, dynamic>) {
    if (root['proxies'] case final List<dynamic> proxies) {
      if (!_hasFullConfigSections(root)) {
        return Uint8List.fromList(utf8.encode(_wrapProxies(proxies)));
      }
      return Uint8List.fromList(utf8.encode(text));
    }
    if (_hasFullConfigSections(root)) {
      return Uint8List.fromList(utf8.encode(text));
    }
  }
  if (root is List<dynamic>) {
    return Uint8List.fromList(utf8.encode(_wrapProxies(root)));
  }
  throw const SubscriptionException(
    stage: 'content_check',
    userMessage: '不是有效的代理配置',
    detail: 'YAML root is not a Mihomo/Clash config',
  );
}

bool _looksLikeHtml(String text, String? contentType) {
  final type = contentType?.toLowerCase() ?? '';
  final lower = text.trimLeft().toLowerCase();
  return type.contains('text/html') ||
      type.contains('application/xhtml') ||
      lower.startsWith('<!doctype html') ||
      lower.startsWith('<html') ||
      lower.contains('<title>just a moment...</title>');
}

String? _tryDecodeWholeBase64(String input) {
  final compact = input.replaceAll(RegExp(r'\s+'), '');
  if (compact.length < 16 ||
      !RegExp(r'^[A-Za-z0-9+/_=-]+$').hasMatch(compact)) {
    return null;
  }
  try {
    final decoded = utf8.decode(_decodeBase64(compact)).trim();
    if (decoded.isEmpty) return null;
    if (_looksLikeNodeList(decoded) ||
        decoded.contains('proxies:') ||
        decoded.contains('proxy-providers:') ||
        decoded.contains('rules:')) {
      return decoded;
    }
  } catch (_) {
    return null;
  }
  return null;
}

Uint8List _decodeBase64(String value) {
  var normalized = value.replaceAll('-', '+').replaceAll('_', '/');
  final remainder = normalized.length % 4;
  if (remainder != 0) normalized += '=' * (4 - remainder);
  return Uint8List.fromList(base64.decode(normalized));
}

bool _looksLikeNodeList(String text) => text
    .split(RegExp(r'\r?\n'))
    .map((line) => line.trim())
    .where((line) => line.isNotEmpty)
    .any(_isSupportedNodeUri);
bool _isSupportedNodeUri(String value) {
  final scheme = Uri.tryParse(value)?.scheme.toLowerCase();
  return const {
    'vmess',
    'vless',
    'trojan',
    'ss',
    'hysteria2',
    'hy2',
    'tuic',
  }.contains(scheme);
}

String? _nodeSubscriptionToYaml(String text) {
  final lines = text
      .split(RegExp(r'\r?\n'))
      .map((line) => line.trim())
      .where((line) => line.isNotEmpty && !line.startsWith('#'))
      .toList();
  if (lines.isEmpty || !lines.any(_isSupportedNodeUri)) return null;
  final proxies = <Map<String, dynamic>>[];
  for (final line in lines) {
    if (!_isSupportedNodeUri(line)) continue;
    proxies.add(_proxyFromUri(line));
  }
  if (proxies.isEmpty) return null;
  return _wrapProxies(proxies);
}

Map<String, dynamic> _proxyFromUri(String raw) {
  final scheme = Uri.parse(raw).scheme.toLowerCase();
  return switch (scheme) {
    'vmess' => _parseVmess(raw),
    'vless' => _parseVless(raw),
    'trojan' => _parseTrojan(raw),
    'ss' => _parseSs(raw),
    'hysteria2' || 'hy2' => _parseHysteria2(raw),
    'tuic' => _parseTuic(raw),
    _ => throw const SubscriptionException(
      stage: 'content_check',
      userMessage: '不是有效的代理配置',
      detail: 'Unsupported node scheme',
    ),
  };
}

Map<String, dynamic> _parseVmess(String raw) {
  final payload = raw.substring('vmess://'.length).split('#').first;
  try {
    final data = json.decode(utf8.decode(_decodeBase64(payload)));
    if (data is! Map) throw const FormatException('vmess payload is not JSON');
    final map = Map<String, dynamic>.from(data);
    final server = '${map['add'] ?? ''}'.trim();
    final port = int.tryParse('${map['port'] ?? ''}') ?? 0;
    final uuid = '${map['id'] ?? ''}'.trim();
    if (server.isEmpty || port <= 0 || uuid.isEmpty) {
      throw const FormatException('vmess required fields missing');
    }
    final proxy = <String, dynamic>{
      'name': '${map['ps'] ?? ''}'.trim().isEmpty
          ? 'VMess $server'
          : '${map['ps']}',
      'type': 'vmess',
      'server': server,
      'port': port,
      'uuid': uuid,
      'alterId': int.tryParse('${map['aid'] ?? 0}') ?? 0,
      'cipher': '${map['scy'] ?? 'auto'}',
    };
    final network = '${map['net'] ?? ''}'.trim();
    if (network.isNotEmpty && network != 'tcp') proxy['network'] = network;
    final tls = '${map['tls'] ?? ''}'.toLowerCase();
    if (tls == 'tls') proxy['tls'] = true;
    final sni = '${map['sni'] ?? ''}'.trim();
    if (sni.isNotEmpty) proxy['servername'] = sni;
    _applyTransport(
      proxy,
      network,
      '${map['path'] ?? ''}',
      '${map['host'] ?? ''}',
    );
    return proxy;
  } catch (error) {
    throw SubscriptionException(
      stage: 'content_check',
      userMessage: '不是有效的代理配置',
      detail: 'Invalid vmess node: $error',
      cause: error,
    );
  }
}

Map<String, dynamic> _parseVless(String raw) {
  final uri = Uri.parse(raw);
  final q = uri.queryParameters;
  final uuid = Uri.decodeComponent(uri.userInfo.split(':').first);
  final proxy = <String, dynamic>{
    'name': _nodeName(uri, 'VLESS ${uri.host}'),
    'type': 'vless',
    'server': uri.host,
    'port': _requiredPort(uri),
    'uuid': uuid,
    'udp': true,
  };
  final security = (q['security'] ?? '').toLowerCase();
  if (security == 'tls' || security == 'reality') proxy['tls'] = true;
  final sni = q['sni'] ?? q['servername'];
  if (sni?.isNotEmpty == true) proxy['servername'] = sni;
  if (q['flow']?.isNotEmpty == true) proxy['flow'] = q['flow'];
  if (q['fp']?.isNotEmpty == true) proxy['client-fingerprint'] = q['fp'];
  if (_queryTrue(q['allowInsecure']) || _queryTrue(q['insecure'])) {
    proxy['skip-cert-verify'] = true;
  }
  if (security == 'reality') {
    final reality = <String, dynamic>{};
    if (q['pbk']?.isNotEmpty == true) reality['public-key'] = q['pbk'];
    if (q['sid']?.isNotEmpty == true) reality['short-id'] = q['sid'];
    if (reality.isNotEmpty) proxy['reality-opts'] = reality;
  }
  final network = q['type'] ?? q['network'] ?? '';
  if (network.isNotEmpty && network != 'tcp') proxy['network'] = network;
  _applyTransport(proxy, network, q['path'] ?? '', q['host'] ?? '');
  return proxy;
}

Map<String, dynamic> _parseTrojan(String raw) {
  final uri = Uri.parse(raw);
  final q = uri.queryParameters;
  final proxy = <String, dynamic>{
    'name': _nodeName(uri, 'Trojan ${uri.host}'),
    'type': 'trojan',
    'server': uri.host,
    'port': _requiredPort(uri),
    'password': Uri.decodeComponent(uri.userInfo),
    'udp': true,
  };
  final sni = q['sni'] ?? q['peer'];
  if (sni?.isNotEmpty == true) proxy['sni'] = sni;
  if (_queryTrue(q['allowInsecure']) || _queryTrue(q['insecure'])) {
    proxy['skip-cert-verify'] = true;
  }
  final network = q['type'] ?? q['network'] ?? '';
  if (network.isNotEmpty && network != 'tcp') proxy['network'] = network;
  _applyTransport(proxy, network, q['path'] ?? '', q['host'] ?? '');
  return proxy;
}

Map<String, dynamic> _parseSs(String raw) {
  final hashIndex = raw.indexOf('#');
  final name = hashIndex == -1 ? '' : raw.substring(hashIndex + 1);
  var body = raw.substring('ss://'.length, hashIndex == -1 ? null : hashIndex);
  final queryIndex = body.indexOf('?');
  final query = queryIndex == -1 ? '' : body.substring(queryIndex + 1);
  if (queryIndex != -1) body = body.substring(0, queryIndex);
  if (!body.contains('@')) {
    body = utf8.decode(_decodeBase64(body));
  }
  final at = body.lastIndexOf('@');
  if (at <= 0) throw const FormatException('invalid ss authority');
  var credentials = body.substring(0, at);
  final authority = body.substring(at + 1);
  if (!credentials.contains(':')) {
    credentials = utf8.decode(_decodeBase64(credentials));
  }
  final colon = credentials.indexOf(':');
  if (colon <= 0) throw const FormatException('invalid ss credentials');
  final endpoint = Uri.parse('ss://x@$authority');
  final proxy = <String, dynamic>{
    'name': name.isEmpty ? 'SS ${endpoint.host}' : Uri.decodeComponent(name),
    'type': 'ss',
    'server': endpoint.host,
    'port': _requiredPort(endpoint),
    'cipher': credentials.substring(0, colon),
    'password': credentials.substring(colon + 1),
    'udp': true,
  };
  final plugin = Uri.splitQueryString(query)['plugin'];
  if (plugin?.isNotEmpty == true) proxy['plugin'] = plugin;
  return proxy;
}

Map<String, dynamic> _parseHysteria2(String raw) {
  final uri = Uri.parse(raw);
  final q = uri.queryParameters;
  final proxy = <String, dynamic>{
    'name': _nodeName(uri, 'Hysteria2 ${uri.host}'),
    'type': 'hysteria2',
    'server': uri.host,
    'port': _requiredPort(uri),
    'password': Uri.decodeComponent(uri.userInfo),
  };
  final sni = q['sni'] ?? q['peer'];
  if (sni?.isNotEmpty == true) proxy['sni'] = sni;
  if (_queryTrue(q['insecure']) || _queryTrue(q['allowInsecure'])) {
    proxy['skip-cert-verify'] = true;
  }
  if (q['obfs']?.isNotEmpty == true) proxy['obfs'] = q['obfs'];
  if (q['obfs-password']?.isNotEmpty == true) {
    proxy['obfs-password'] = q['obfs-password'];
  }
  return proxy;
}

Map<String, dynamic> _parseTuic(String raw) {
  final uri = Uri.parse(raw);
  final q = uri.queryParameters;
  final userInfo = Uri.decodeComponent(uri.userInfo).split(':');
  if (userInfo.length < 2) {
    throw const FormatException('invalid tuic credentials');
  }
  final proxy = <String, dynamic>{
    'name': _nodeName(uri, 'TUIC ${uri.host}'),
    'type': 'tuic',
    'server': uri.host,
    'port': _requiredPort(uri),
    'uuid': userInfo.first,
    'password': userInfo.sublist(1).join(':'),
  };
  if (q['sni']?.isNotEmpty == true) proxy['sni'] = q['sni'];
  if (_queryTrue(q['insecure']) || _queryTrue(q['allowInsecure'])) {
    proxy['skip-cert-verify'] = true;
  }
  return proxy;
}

void _applyTransport(
  Map<String, dynamic> proxy,
  String network,
  String path,
  String host,
) {
  if (network == 'ws') {
    final ws = <String, dynamic>{};
    if (path.isNotEmpty) ws['path'] = path;
    if (host.isNotEmpty) ws['headers'] = {'Host': host};
    if (ws.isNotEmpty) proxy['ws-opts'] = ws;
  } else if (network == 'grpc') {
    if (path.isNotEmpty) {
      proxy['grpc-opts'] = {'grpc-service-name': path};
    }
  }
}

String _nodeName(Uri uri, String fallback) {
  final fragment = uri.fragment.trim();
  if (fragment.isEmpty) return fallback;
  try {
    return Uri.decodeComponent(fragment);
  } catch (_) {
    return fragment;
  }
}

int _requiredPort(Uri uri) {
  final port = uri.port;
  if (port <= 0) throw const FormatException('missing port');
  return port;
}

bool _queryTrue(String? value) {
  final normalized = value?.toLowerCase();
  return normalized == '1' || normalized == 'true' || normalized == 'yes';
}

dynamic _toDart(dynamic value) {
  if (value is yaml_parser.YamlMap) {
    return <String, dynamic>{
      for (final entry in value.entries) '${entry.key}': _toDart(entry.value),
    };
  }
  if (value is yaml_parser.YamlList) {
    return value.map(_toDart).toList();
  }
  return value;
}

bool _hasFullConfigSections(Map<String, dynamic> root) {
  const keys = {
    'proxy-groups',
    'proxy-providers',
    'rules',
    'rule-providers',
    'dns',
    'tun',
    'listeners',
    'mode',
    'mixed-port',
    'port',
    'socks-port',
  };
  return root.keys.any(keys.contains);
}

String _wrapProxies(List<dynamic> rawProxies) {
  final proxies = rawProxies.whereType<Map>().map((item) {
    return item.map((key, value) => MapEntry('$key', _toDart(value)));
  }).toList();
  final names = proxies
      .map((proxy) => '${proxy['name'] ?? ''}'.trim())
      .where((name) => name.isNotEmpty)
      .toList();
  if (proxies.isEmpty || names.isEmpty) {
    throw const SubscriptionException(
      stage: 'content_check',
      userMessage: '不是有效的代理配置',
      detail: 'Provider contains no named proxies',
    );
  }
  var groupName = 'HUI-PROXY';
  var suffix = 1;
  while (names.contains(groupName)) {
    groupName = 'HUI-PROXY-${suffix++}';
  }
  return yaml.encode({
    'proxies': proxies,
    'proxy-groups': [
      {'name': groupName, 'type': 'select', 'proxies': names},
    ],
    'rules': ['MATCH,$groupName'],
  });
}
