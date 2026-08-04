part of '../parts.dart';

class MissingCameraException extends PlatformException {
  MissingCameraException({
    required super.code,
    super.message,
    super.details,
  });

  @override
  String toString() => 'MissingCameraException($code, $message, $details)';
}
