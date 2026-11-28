package com.togather.search.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.togather.common.enums.ContentType;
import com.togather.common.enums.Provider;
import com.togather.content.domain.ContentRef;
import org.junit.jupiter.api.Test;

/** 낱말 찾기 결과의 관련도: 모두 같은 점수가 아니라 제목·등장 횟수에 따라 차등을 둔다. */
class KeywordScoreTest {

    @Test
    void 본문에_한_번만_나오면_기본_점수() {
        assertThat(SearchService.keywordScore(drive("발표자료.pptx", "다음 회의 전에 정리"), "회의"))
                .isEqualTo(SearchService.KEYWORD_SCORE);
    }

    @Test
    void 제목에_있으면_본문에만_있는_것보다_높다() {
        double inTitle = SearchService.keywordScore(drive("회의록_초안.docx", "1차 팀 정리"), "회의");
        double inBody = SearchService.keywordScore(drive("레퍼런스.pdf", "회의 시간을 정한다"), "회의");

        assertThat(inTitle).isEqualTo(0.75);
        assertThat(inTitle).isGreaterThan(inBody);
    }

    @Test
    void 여러_번_나올수록_높지만_한도가_있다() {
        double twice = SearchService.keywordScore(drive("a.txt", "회의 그리고 회의"), "회의");
        double many = SearchService.keywordScore(drive("회의.txt", "회의 ".repeat(30)), "회의");

        assertThat(twice).isEqualTo(0.6);
        assertThat(many).isEqualTo(0.9);
    }

    @Test
    void Slack_메시지는_채널_이름을_제목으로_치지_않는다() {
        ContentRef message = ContentRef.builder().workspaceId(1L).provider(Provider.SLACK)
                .contentType(ContentType.MESSAGE).originId("m1").title("#회의").content("내일 회의 어때").build();

        assertThat(SearchService.keywordScore(message, "회의")).isEqualTo(SearchService.KEYWORD_SCORE);
    }

    private static ContentRef drive(String title, String content) {
        return ContentRef.builder().workspaceId(1L).provider(Provider.GOOGLE_DRIVE).contentType(ContentType.FILE)
                .originId(title).title(title).content(content).build();
    }
}
