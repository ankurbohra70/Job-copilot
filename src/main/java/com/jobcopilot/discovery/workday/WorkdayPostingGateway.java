package com.jobcopilot.discovery.workday;

public interface WorkdayPostingGateway {
    WorkdayFetchResult searchPage(WorkdaySource source, WorkdayPageRequest page);
    WorkdayFetchResult fetchDetail(WorkdaySource source, WorkdayDetailRequest detail);
}
