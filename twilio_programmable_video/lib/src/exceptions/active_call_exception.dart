part of '../parts.dart';

class ActiveCallException extends PlatformException {
  ActiveCallException({
    required super.code,
    super.message,
    super.details,
  });

  @override
  String toString() => 'ActiveCallException($code, $message, $details)';
}
