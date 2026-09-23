package org.neuro.gsb.domain;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Immutable probe geometry: channel sites relative to a declared coordinate datum. */
public record ProbeGeometry(String id, String name, String coordinateDatum, List<Coord> channels) {

    public Map<String, Coord> byChannel() {
        return channels.stream().collect(Collectors.toUnmodifiableMap(Coord::channelId, Function.identity()));
    }
}
