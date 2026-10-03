package com.jobcopilot.discovery;

import java.util.Set;

/** Constructed only after the provider adapter reports authoritative traversal completion. */
record CompletedTraversal(Set<String> seenIds) {
    CompletedTraversal { seenIds = Set.copyOf(seenIds); }
}
