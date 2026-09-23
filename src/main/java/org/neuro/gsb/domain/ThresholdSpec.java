package org.neuro.gsb.domain;

/** Immutable detection threshold version (e.g. "-4.5 SD" on a channel). */
public record ThresholdSpec(String id, String scope, String channelId, String method, Measure level) {
}
