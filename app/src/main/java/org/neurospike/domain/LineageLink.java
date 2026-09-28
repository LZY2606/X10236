package org.neurospike.domain;

public record LineageLink(String parentId, String parentKind, String childId, String childKind, String relation) {
}
