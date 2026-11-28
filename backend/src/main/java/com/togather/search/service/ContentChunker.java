package com.togather.search.service;

import java.util.ArrayList;
import java.util.List;

/**
 * 긴 본문을 검색용 조각으로 나눈다. (DB-08: 긴 문서는 "content_ref_id-조각번호"로 나눠 저장)
 *
 * <p>긴 글 전체를 벡터 하나로 만들면 의미가 흐려져, 짧은 질문이 문서 중간의 한 줄과 잘 맞지 않는다.
 * 그래서 약 {@value #CHUNK_SIZE}자 단위로 자르되, 문단·줄·문장 경계에서 자르고 앞 조각의 끝 {@value #OVERLAP}자를
 * 다음 조각에 겹쳐 넣어 경계에 걸린 문장도 찾게 한다.</p>
 *
 * <p>같은 글이면 항상 같은 조각이 나온다. 검색 결과의 미리보기도 이 규칙으로 다시 잘라 가장 잘 맞은 조각을 보여 준다.</p>
 */
public final class ContentChunker {

    static final int CHUNK_SIZE = 1200;
    static final int OVERLAP = 200;
    /** 문서 하나의 최대 조각 수 (Drive 본문은 이미 5만 자까지만 뽑는다) */
    static final int MAX_CHUNKS = 40;
    /** 자를 위치를 찾을 때 뒤에서 이만큼 안에서 경계를 찾는다 */
    private static final int BOUNDARY_WINDOW = 300;
    private static final String[] BOUNDARIES = {"\n\n", "\n", ". ", "? ", "! ", "다. ", " "};

    private ContentChunker() {
    }

    public static List<String> split(String text) {
        List<String> chunks = new ArrayList<>();
        if (text == null) {
            return chunks;
        }
        String body = text.strip();
        if (body.isEmpty()) {
            return chunks;
        }
        int start = 0;
        while (start < body.length() && chunks.size() < MAX_CHUNKS) {
            int end = Math.min(body.length(), start + CHUNK_SIZE);
            if (end < body.length()) {
                end = boundaryBefore(body, start, end);
            }
            String chunk = body.substring(start, end).strip();
            if (!chunk.isEmpty()) {
                chunks.add(chunk);
            }
            if (end >= body.length()) {
                break;
            }
            // 겹쳐 넣되, 반드시 앞으로 나아가게 한다.
            start = Math.max(end - OVERLAP, start + 1);
            start = wordStart(body, start, end);
        }
        return chunks;
    }

    /** end 바로 앞 BOUNDARY_WINDOW 안에서 가장 늦은 경계 뒤를 자를 위치로 */
    private static int boundaryBefore(String body, int start, int end) {
        int from = Math.max(start + CHUNK_SIZE / 2, end - BOUNDARY_WINDOW);
        for (String boundary : BOUNDARIES) {
            int at = body.lastIndexOf(boundary, end - boundary.length());
            if (at >= from) {
                return at + boundary.length();
            }
        }
        return end;
    }

    /** 겹치는 부분이 낱말 중간에서 시작하지 않게 다음 공백 뒤로 옮긴다. (end를 넘지 않는다) */
    private static int wordStart(String body, int start, int end) {
        if (start == 0 || Character.isWhitespace(body.charAt(start - 1))) {
            return start;
        }
        for (int i = start; i < end; i++) {
            if (Character.isWhitespace(body.charAt(i))) {
                return i + 1;
            }
        }
        return start;
    }
}
