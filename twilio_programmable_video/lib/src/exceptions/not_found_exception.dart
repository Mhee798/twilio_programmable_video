part of '../parts.dart';

class NotFoundException extends PlatformException {
  NotFoundException({
    required super.code,
    super.message,
    super.details,
  });

  @override
  String toString() => 'NotFoundException($code, $message, $details)';
}
