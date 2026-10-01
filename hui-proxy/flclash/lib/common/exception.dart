final class MessageException implements Exception {
  final String message;

  const MessageException(this.message);

  @override
  String toString() => message;
}

final class SubscriptionException implements Exception {
  final String stage;
  final String userMessage;
  final String detail;
  final int? httpCode;
  final String? contentType;
  final int? responseLength;
  final Object? cause;

  const SubscriptionException({
    required this.stage,
    required this.userMessage,
    this.detail = '',
    this.httpCode,
    this.contentType,
    this.responseLength,
    this.cause,
  });

  @override
  String toString() => userMessage;
}
