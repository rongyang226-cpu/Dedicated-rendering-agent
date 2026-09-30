import 'package:fl_clash/common/common.dart';
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/models/models.dart';

int _proxyRegionRank(String name) {
  final lower = name.toLowerCase();
  bool hasAny(List<String> values) => values.any(lower.contains);
  bool hasCode(String code) {
    final normalized = lower.replaceAll(RegExp(r'[^a-z0-9]+'), ' ');
    return normalized.split(' ').any((token) {
      if (token == code) return true;
      if (!token.startsWith(code)) return false;
      final suffix = token.substring(code.length);
      return suffix.isNotEmpty && int.tryParse(suffix) != null;
    });
  }

  if (hasAny(['香港', 'hong kong', 'hongkong', '🇭🇰']) || hasCode('hk')) {
    return 0;
  }
  if (hasAny(['台湾', '臺灣', 'taiwan', 'taipei', '🇹🇼']) || hasCode('tw')) {
    return 1;
  }
  if (hasAny(['日本', 'japan', 'tokyo', 'osaka', '东京', '東京', '大阪', '🇯🇵']) ||
      hasCode('jp')) {
    return 2;
  }
  if (hasAny(['新加坡', 'singapore', '🇸🇬']) || hasCode('sg')) {
    return 3;
  }
  if (hasAny(['韩国', '韓國', 'korea', 'seoul', '首尔', '首爾', '🇰🇷']) ||
      hasCode('kr')) {
    return 4;
  }
  if (hasAny([
        '美国',
        '美國',
        'united states',
        'los angeles',
        'san jose',
        'seattle',
        'new york',
        '🇺🇸',
      ]) ||
      hasCode('us')) {
    return 5;
  }
  if (hasAny(['英国', '英國', 'united kingdom', 'london', '🇬🇧']) ||
      hasCode('uk') ||
      hasCode('gb')) {
    return 6;
  }
  if (hasAny(['德国', '德國', 'germany', 'frankfurt', '🇩🇪']) || hasCode('de')) {
    return 7;
  }
  if (hasAny(['法国', '法國', 'france', 'paris', '🇫🇷']) || hasCode('fr')) {
    return 8;
  }
  if (hasAny(['加拿大', 'canada', 'toronto', '🇨🇦']) || hasCode('ca')) {
    return 9;
  }
  if (hasAny(['澳大利亚', '澳大利亞', 'australia', 'sydney', '🇦🇺']) ||
      hasCode('au')) {
    return 10;
  }
  return 99;
}

List<Group> computeSort({
  required List<Group> groups,
  required ProxiesSortType sortType,
  required DelayMap delayMap,
  required Map<String, String> selectedMap,
  required String defaultTestUrl,
}) {
  List<Proxy> sortOfDelay({
    required List<Group> groups,
    required List<Proxy> proxies,
    required DelayMap delayMap,
    required Map<String, String> selectedMap,
    required String testUrl,
  }) {
    return List.from(proxies)..sort((a, b) {
      final aDelayState = computeProxyDelayState(
        proxyName: a.name,
        testUrl: testUrl,
        groups: groups,
        selectedMap: selectedMap,
        delayMap: delayMap,
      );
      final bDelayState = computeProxyDelayState(
        proxyName: b.name,
        testUrl: testUrl,
        groups: groups,
        selectedMap: selectedMap,
        delayMap: delayMap,
      );
      return aDelayState.compareTo(bDelayState);
    });
  }

  List<Proxy> sortOfName(List<Proxy> proxies) {
    return List.of(proxies)..sort((a, b) => a.name.compareTo(b.name));
  }

  List<Proxy> sortOfRegion(List<Proxy> proxies) {
    return List.of(proxies)..sort((a, b) {
      final regionCompare = _proxyRegionRank(
        a.name,
      ).compareTo(_proxyRegionRank(b.name));
      if (regionCompare != 0) return regionCompare;
      return a.name.compareTo(b.name);
    });
  }

  return groups.map((group) {
    final proxies = group.all;
    final newProxies = switch (sortType) {
      ProxiesSortType.none => proxies,
      ProxiesSortType.delay => sortOfDelay(
        groups: groups,
        proxies: proxies,
        delayMap: delayMap,
        selectedMap: selectedMap,
        testUrl: group.testUrl.takeFirstValid([defaultTestUrl]),
      ),
      ProxiesSortType.region => sortOfRegion(proxies),
      ProxiesSortType.name => sortOfName(proxies),
    };
    return group.copyWith(all: newProxies);
  }).toList();
}

SelectedProxyState getRealSelectedProxyState(
  SelectedProxyState state, {
  required List<Group> groups,
  required Map<String, String> selectedMap,
}) {
  if (state.proxyName.isEmpty) return state;
  final index = groups.indexWhere((element) => element.name == state.proxyName);
  final newState = state.copyWith(group: true);
  if (index == -1) return newState;
  final group = groups[index];
  final currentSelectedName = group.getCurrentSelectedName(
    selectedMap[newState.proxyName] ?? '',
  );
  if (currentSelectedName.isEmpty) {
    return newState;
  }
  return getRealSelectedProxyState(
    newState.copyWith(proxyName: currentSelectedName, testUrl: group.testUrl),
    groups: groups,
    selectedMap: selectedMap,
  );
}

SelectedProxyState computeRealSelectedProxyState(
  String proxyName, {
  required List<Group> groups,
  required Map<String, String> selectedMap,
}) {
  return getRealSelectedProxyState(
    SelectedProxyState(proxyName: proxyName),
    groups: groups,
    selectedMap: selectedMap,
  );
}

String delayTestKey(String testUrl, String proxyName) {
  return '$testUrl\u0000$proxyName';
}

DelayState computeProxyDelayState({
  required String proxyName,
  required String testUrl,
  required List<Group> groups,
  required Map<String, String> selectedMap,
  required DelayMap delayMap,
}) {
  final state = computeRealSelectedProxyState(
    proxyName,
    groups: groups,
    selectedMap: selectedMap,
  );
  final currentDelayMap =
      delayMap[state.testUrl.takeFirstValid([testUrl])] ?? {};
  final delay = currentDelayMap[state.proxyName];
  return DelayState(delay: delay ?? 0, group: state.group);
}
