package org.neuro.spike.model;

/** Kind of provenance edge between clustering snapshots. */
public enum LineageRelation {
    BIRTH,
    MERGE,
    SPLIT,
    RENUMBER,
    PERSIST
}
