package com.jobcopilot.discovery;

import java.util.List;

public record LiveOpportunityListingSet(int liveListingCount,
        List<LiveOpportunityListingSnapshot> currentListings) {
    public LiveOpportunityListingSet {
        currentListings = List.copyOf(currentListings);
    }
}
