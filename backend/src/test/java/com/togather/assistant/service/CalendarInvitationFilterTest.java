package com.togather.assistant.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.togather.common.enums.ContentType;
import com.togather.common.enums.Provider;
import com.togather.content.domain.ContentRef;
import com.togather.content.domain.ContentRef.DriveFileState;
import org.junit.jupiter.api.Test;

/** Google Calendar 초대 메일은 일정 후보에서 빼고, Drive 휴지통에 들어간 시각은 처음 본 때로 남긴다. */
class CalendarInvitationFilterTest {

    @Test
    void Google_Calendar_초대_변경_취소_메일은_후보_대상이_아니다() {
        assertThat(SuggestionService.isCalendarInvitation(mail(
            "Invitation from an unknown sender: [QA] 초대 확인 회의 @ Wed Dec 2, 2026 1am - 2am (UTC) (b@example.com)",
            "This event isn't in your calendar yet https://calendar.google.com/calendar/event?eid=abc"))).isTrue();
        assertThat(SuggestionService.isCalendarInvitation(mail(
            "Updated invitation: 주간 회의 @ Mon", "https://calendar.google.com/calendar/r"))).isTrue();
        assertThat(SuggestionService.isCalendarInvitation(mail(
            "초대장: 기획 회의 @ 2026년 12월 3일", "https://calendar.google.com/calendar/event?eid=x"))).isTrue();
    }

    @Test
    void 다른_서비스의_초대_메일이나_일반_메일은_그대로_살펴본다() {
        assertThat(SuggestionService.isCalendarInvitation(mail(
            "정철준 님이 Togather 테스트에서 함께 작업할 수 있도록 고객님을 초대했습니다", "https://join.slack.com/t/x"))).isFalse();
        assertThat(SuggestionService.isCalendarInvitation(mail(
            "Invitation: 다음 주 화요일 3시에 만나요", "회의실은 3층이에요"))).as("캘린더 링크가 없는 일반 메일").isFalse();
    }

    @Test
    void Drive_휴지통에_들어간_때를_처음_본_시각으로_남기고_복원하면_지운다() {
        ContentRef file = ContentRef.builder().workspaceId(1L).provider(Provider.GOOGLE_DRIVE).contentType(ContentType.FILE)
            .originId("f1").channelId("root").title("a.pdf").build();

        file.applyDriveLocation("root", DriveFileState.TRASHED);
        var seen = file.getDriveTrashedAt();
        assertThat(seen).isNotNull();

        file.applyDriveLocation("root", DriveFileState.TRASHED);
        assertThat(file.getDriveTrashedAt()).as("다시 동기화해도 처음 본 시각 유지").isEqualTo(seen);

        file.applyDriveLocation("root", DriveFileState.AVAILABLE);
        assertThat(file.getDriveTrashedAt()).isNull();
    }

    private static ContentRef mail(String subject, String body) {
        return ContentRef.builder().workspaceId(1L).ownerUserId(2L).provider(Provider.GMAIL).contentType(ContentType.MAIL)
            .originId(subject.hashCode() + "").title(subject).content(body).authorName("\"정철준\" <a@example.com>").build();
    }
}
