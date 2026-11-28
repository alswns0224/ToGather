package com.togather.analysis.service;

import java.time.LocalTime;

/** 테스트용: 다른 패키지의 테스트에서 DiscussionAnalyzer.parseTime을 부른다. */
public final class DiscussionAnalyzerTimeAccess {

    private DiscussionAnalyzerTimeAccess() {
    }

    public static LocalTime parseTime(String value) {
        return DiscussionAnalyzer.parseTime(value);
    }
}
