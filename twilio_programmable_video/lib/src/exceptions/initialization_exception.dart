part of '../parts.dart';

class InitializationException extends PlatformException {
  InitializationException({
    required super.code,
    super.message,
    super.details,
  });

  @override
  String toString() => 'InitializationException($code, $message, $details)';
}
