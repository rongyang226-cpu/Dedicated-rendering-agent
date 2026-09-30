import 'dart:math' as math;

import 'package:flutter/material.dart';

class HuiSakuraLayer extends StatefulWidget {
  final bool enabled;
  final int level;

  const HuiSakuraLayer({super.key, required this.enabled, required this.level});

  @override
  State<HuiSakuraLayer> createState() => _HuiSakuraLayerState();
}

class _HuiSakuraLayerState extends State<HuiSakuraLayer>
    with SingleTickerProviderStateMixin {
  late final AnimationController _controller;
  late List<_Petal> _petals;

  @override
  void initState() {
    super.initState();
    _petals = _makePetals(widget.level);
    _controller = AnimationController(
      vsync: this,
      duration: const Duration(seconds: 26),
    );
    if (widget.enabled) {
      _controller.repeat();
    }
  }

  @override
  void didUpdateWidget(covariant HuiSakuraLayer oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.level != widget.level) {
      _petals = _makePetals(widget.level);
    }
    if (oldWidget.enabled != widget.enabled) {
      widget.enabled ? _controller.repeat() : _controller.stop();
    }
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  List<_Petal> _makePetals(int level) {
    final safeLevel = level.clamp(0, 2);
    final count = const [12, 24, 40][safeLevel];
    final random = math.Random(20260930 + safeLevel);
    return List.generate(count, (index) {
      return _Petal(
        x: random.nextDouble(),
        y: random.nextDouble() * 1.2,
        size: 5.5 + random.nextDouble() * 8,
        speed: (1 + random.nextInt(safeLevel + 1)).toDouble(),
        sway: 0.018 + random.nextDouble() * 0.055,
        wind: 0.012 + random.nextDouble() * 0.035,
        phase: random.nextDouble() * math.pi * 2,
        frequency: (1 + random.nextInt(2)).toDouble(),
        rotation: random.nextDouble() * math.pi * 2,
        rotationSpeed: (random.nextInt(5) - 2).toDouble(),
        opacity: 0.34 + random.nextDouble() * 0.46,
        depth: 0.72 + random.nextDouble() * 0.46,
      );
    });
  }

  @override
  Widget build(BuildContext context) {
    final reduceMotion =
        MediaQuery.maybeOf(context)?.disableAnimations ?? false;
    if (!widget.enabled || reduceMotion) {
      return const SizedBox.shrink();
    }
    return IgnorePointer(
      child: RepaintBoundary(
        child: AnimatedBuilder(
          animation: _controller,
          builder: (_, _) => CustomPaint(
            painter: _SakuraPainter(
              progress: _controller.value,
              petals: _petals,
            ),
            size: Size.infinite,
          ),
        ),
      ),
    );
  }
}

class _Petal {
  final double x;
  final double y;
  final double size;
  final double speed;
  final double sway;
  final double wind;
  final double phase;
  final double frequency;
  final double rotation;
  final double rotationSpeed;
  final double opacity;
  final double depth;

  const _Petal({
    required this.x,
    required this.y,
    required this.size,
    required this.speed,
    required this.sway,
    required this.wind,
    required this.phase,
    required this.frequency,
    required this.rotation,
    required this.rotationSpeed,
    required this.opacity,
    required this.depth,
  });
}

class _SakuraPainter extends CustomPainter {
  final double progress;
  final List<_Petal> petals;

  const _SakuraPainter({required this.progress, required this.petals});

  @override
  void paint(Canvas canvas, Size size) {
    if (size.isEmpty) return;
    for (var i = 0; i < petals.length; i++) {
      final petal = petals[i];
      // Every term is periodic at progress 0/1 so repeat() has no visible jump.
      final vertical = (petal.y + progress * petal.speed * 1.22) % 1.22 - 0.11;
      final oscillation =
          math.sin(progress * math.pi * 2 * petal.frequency + petal.phase) *
          petal.sway;
      final wind =
          math.sin(progress * math.pi * 2 + petal.phase * 0.73) * petal.wind;
      final horizontal = (petal.x + oscillation + wind) % 1.08 - 0.04;
      final x = horizontal * size.width;
      final y = vertical * size.height;
      final scale = petal.depth;
      final w = petal.size * scale;
      final h = w * 1.32;

      canvas.save();
      canvas.translate(x, y);
      canvas.rotate(
        petal.rotation + progress * math.pi * 2 * petal.rotationSpeed,
      );

      final paint = Paint()
        ..isAntiAlias = true
        ..color = (i.isEven ? const Color(0xFFFFD8E6) : const Color(0xFFFFE6EF))
            .withValues(alpha: petal.opacity);
      final path = Path()
        ..moveTo(0, -h * 0.52)
        ..quadraticBezierTo(w * 0.52, -h * 0.20, w * 0.32, h * 0.17)
        ..quadraticBezierTo(w * 0.12, h * 0.48, 0, h * 0.52)
        ..quadraticBezierTo(-w * 0.12, h * 0.48, -w * 0.32, h * 0.17)
        ..quadraticBezierTo(-w * 0.52, -h * 0.20, 0, -h * 0.52)
        ..close();
      canvas.drawPath(path, paint);

      final highlight = Paint()
        ..isAntiAlias = true
        ..strokeWidth = math.max(0.5, w * 0.055)
        ..style = PaintingStyle.stroke
        ..color = Colors.white.withValues(alpha: petal.opacity * 0.38);
      canvas.drawLine(Offset(0, -h * 0.28), Offset(0, h * 0.24), highlight);
      canvas.restore();
    }
  }

  @override
  bool shouldRepaint(covariant _SakuraPainter oldDelegate) {
    return oldDelegate.progress != progress || oldDelegate.petals != petals;
  }
}
