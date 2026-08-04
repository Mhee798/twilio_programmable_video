part of '../parts.dart';

abstract class NetworkQualityLevelChangedEvent {
  /// The new [NetworkQualityLevel]
  NetworkQualityLevel get networkQualityLevel;
}
