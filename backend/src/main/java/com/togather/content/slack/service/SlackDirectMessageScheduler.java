package com.togather.content.slack.service;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SlackDirectMessageScheduler {
    private final SlackDirectMessageService directMessages;

    @Scheduled(initialDelayString = "${togather.sync.initial-delay:PT1M}", fixedDelayString = "${togather.sync.interval:PT5M}")
    public void synchronize() { directMessages.syncKnownOwners(); }
}
