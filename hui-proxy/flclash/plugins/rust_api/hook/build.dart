import 'dart:io';

import 'package:code_assets/code_assets.dart';
import 'package:flutter_rust_bridge_hooks/flutter_rust_bridge_hooks.dart';

void main(List<String> args) async {
  await build(args, (input, output) async {
    if (input.userDefines['build_assets'] == false) {
      stdout.writeln('Skipping the Rust build: user-define build_assets=false');
      return;
    }
    await FlutterRustBridgeNativeAssetsBuilder(
      cratePath: 'rust',
      extraCargoEnvironmentVariables: _bindgenEnvironment(input),
    ).run(input: input, output: output);
  });
}

// rquickjs runs bindgen on Android, which must load the NDK's libclang; Linux
// NDKs before r26 keep it under lib64, later ones and every macOS NDK under lib.
Map<String, String> _bindgenEnvironment(BuildInput input) {
  if (!input.config.buildCodeAssets ||
      input.config.code.targetOS != OS.android) {
    return const {};
  }
  final compiler = input.config.code.cCompiler?.compiler;
  if (compiler == null) {
    return const {};
  }
  final llvmRoot = File.fromUri(compiler).parent.parent;
  for (final name in const ['lib', 'lib64']) {
    final directory = Directory(
      '${llvmRoot.path}${Platform.pathSeparator}$name',
    );
    if (directory.existsSync() && directory.listSync().any(_isLibclang)) {
      final resourceInclude = _clangResourceInclude(llvmRoot);
      final targetTriple = switch (input.config.code.targetArchitecture) {
        Architecture.arm => 'armv7-linux-androideabi',
        Architecture.arm64 => 'aarch64-linux-android',
        Architecture.x64 => 'x86_64-linux-android',
        final architecture => throw StateError(
          'Unsupported Android architecture for bindgen: $architecture',
        ),
      };
      final sysrootTriple = targetTriple == 'armv7-linux-androideabi'
          ? 'arm-linux-androideabi'
          : targetTriple;
      final sysroot = Directory(
        '${llvmRoot.path}${Platform.pathSeparator}sysroot',
      );
      final targetInclude = Directory(
        '${sysroot.path}${Platform.pathSeparator}usr'
        '${Platform.pathSeparator}include${Platform.pathSeparator}$sysrootTriple',
      );
      final bindgenKey =
          'BINDGEN_EXTRA_CLANG_ARGS_${targetTriple.replaceAll('-', '_')}';
      final bindgenArgs =
          '--sysroot=${sysroot.path} -I${targetInclude.path} '
                  '-isystem ${resourceInclude.path}'
              .replaceAll('\\', '/');
      return {'LIBCLANG_PATH': directory.path, bindgenKey: bindgenArgs};
    }
  }
  throw StateError(
    'No libclang under ${llvmRoot.path} (lib or lib64); the NDK Flutter '
    'passed cannot run bindgen for rquickjs',
  );
}

Directory _clangResourceInclude(Directory llvmRoot) {
  final clangRoot = Directory(
    '${llvmRoot.path}${Platform.pathSeparator}lib${Platform.pathSeparator}clang',
  );
  if (clangRoot.existsSync()) {
    final candidates =
        clangRoot
            .listSync()
            .whereType<Directory>()
            .map(
              (directory) => Directory(
                '${directory.path}${Platform.pathSeparator}include',
              ),
            )
            .where(
              (directory) => File(
                '${directory.path}${Platform.pathSeparator}stdbool.h',
              ).existsSync(),
            )
            .toList()
          ..sort((a, b) => b.path.compareTo(a.path));
    if (candidates.isNotEmpty) return candidates.first;
  }
  throw StateError(
    'No clang resource include with stdbool.h under ${llvmRoot.path}',
  );
}

bool _isLibclang(FileSystemEntity entity) {
  return entity.path.split(Platform.pathSeparator).last.startsWith('libclang.');
}
