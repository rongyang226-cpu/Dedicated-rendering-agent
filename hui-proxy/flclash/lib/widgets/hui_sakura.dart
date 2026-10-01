import 'dart:math' as math;

import 'package:material_ui/material_ui.dart';

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
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    _syncAnimation();
  }

  void _syncAnimation() {
    final canAnimate =
        widget.enabled &&
        !(MediaQuery.maybeOf(context)?.disableAnimations ?? false);
    if (canAnimate && !_controller.isAnimating) {
      _controller.repeat();
    } else if (!canAnimate && _controller.isAnimating) {
      _controller.stop();
    }
  }

  @override
  void didUpdateWidget(covariant HuiSakuraLayer oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.level != widget.level) {
      _petals = _makePetals(widget.level);
    }
    if (oldWidget.enabled != widget.enabled) {
      _syncAnimation();
    }
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  List<_Petal> _makePetals(int level) {
    final safeLevel = level.clamp(0, 2);
    final count = const [9, 17, 28][safeLevel];
    final random = math.Random(20260930 + safeLevel);
    return List.generate(count, (index) {
      return _Petal(
        x: random.nextDouble(),
        y: random.nextDouble() * 1.2,
        size: 4.2 + random.nextDouble() * 9.5,
        speed: (1 + random.nextInt(safeLevel + 1)).toDouble(),
        sway: 0.012 + random.nextDouble() * 0.036,
        wind: 0.006 + random.nextDouble() * 0.024,
        phase: random.nextDouble() * math.pi * 2,
        frequency: (1 + random.nextInt(2)).toDouble(),
        rotation: random.nextDouble() * math.pi * 2,
        rotationSpeed: (random.nextInt(5) - 2).toDouble(),
        opacity: 0.14 + random.nextDouble() * 0.26,
        depth: 0.58 + random.nextDouble() * 0.58,
        driftCycles: random.nextInt(3) - 1,
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
  final int driftCycles;

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
    required this.driftCycles,
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
      final drift = progress * petal.driftCycles * 1.18;
      final horizontal = (petal.x + drift + oscillation + wind) % 1.18 - 0.09;
      final x = horizontal * size.width;
      final y = vertical * size.height;
      final scale = petal.depth;
      final w = petal.size * scale;
      final h = w * 1.38;
      final flip =
          0.42 +
          math
                  .cos(progress * math.pi * 4 * petal.frequency + petal.phase)
                  .abs() *
              0.58;

      canvas.save();
      canvas.translate(x, y);
      canvas.rotate(
        petal.rotation + progress * math.pi * 2 * petal.rotationSpeed,
      );
      canvas.scale(flip, 1);

      final path = Path()
        ..moveTo(0, h * 0.50)
        ..cubicTo(w * 0.18, h * 0.38, w * 0.48, h * 0.08, w * 0.42, -h * 0.22)
        ..cubicTo(w * 0.38, -h * 0.43, w * 0.13, -h * 0.49, 0.03 * w, -h * 0.40)
        ..quadraticBezierTo(0, -h * 0.34, -0.07 * w, -h * 0.41)
        ..cubicTo(
          -w * 0.22,
          -h * 0.48,
          -w * 0.48,
          -h * 0.30,
          -w * 0.38,
          -h * 0.04,
        )
        ..cubicTo(-w * 0.31, h * 0.19, -w * 0.13, h * 0.40, 0, h * 0.50)
        ..close();
      final rect = Rect.fromCenter(center: Offset.zero, width: w, height: h);
      final paint = Paint()
        ..isAntiAlias = true
        ..shader = const LinearGradient(
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
          colors: [Color(0xFFFFF2F7), Color(0xFFFFC8DA), Color(0xFFFFAFC9)],
        ).createShader(rect)
        ..color = Colors.white.withValues(alpha: petal.opacity);
      if (petal.depth < 0.78) {
        paint.maskFilter = MaskFilter.blur(
          BlurStyle.normal,
          (0.78 - petal.depth) * 2.2,
        );
      }
      canvas.drawPath(path, paint);

      final vein = Paint()
        ..isAntiAlias = true
        ..strokeWidth = math.max(0.35, w * 0.035)
        ..style = PaintingStyle.stroke
        ..color = const Color(
          0xFFFF7FA8,
        ).withValues(alpha: petal.opacity * 0.34);
      canvas.drawLine(Offset(0, h * 0.30), Offset(w * 0.035, -h * 0.18), vein);
      canvas.restore();
    }
  }

  @override
  bool shouldRepaint(covariant _SakuraPainter oldDelegate) {
    return oldDelegate.progress != progress || oldDelegate.petals != petals;
  }
}
