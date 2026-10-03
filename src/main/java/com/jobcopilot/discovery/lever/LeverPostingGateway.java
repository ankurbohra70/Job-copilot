package com.jobcopilot.discovery.lever;

public interface LeverPostingGateway {
    LeverFetchResult fetchPage(LeverSource source, LeverPageRequest page);
}
