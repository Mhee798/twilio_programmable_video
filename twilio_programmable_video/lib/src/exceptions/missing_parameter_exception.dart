part of '../parts.dart';

class MissingParameterException extends PlatformException {
  MissingParameterException({
    required super.code,
    super.message,
    super.details,
  });

  @override
  String toString() => 'MissingParameterException($code, $message, $details)';
}
