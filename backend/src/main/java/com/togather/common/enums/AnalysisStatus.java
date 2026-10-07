package com.togather.common.enums;

/** C-00-25 분석 상태 */
public enum AnalysisStatus {
    ANALYZING, // 분석 중
    FAILED, // Gemini 호출 실패, 같은 범위로 재시도 대상
    NO_CONTENT, // 정리할 내용 없음
    REVIEWING, // 분석 완료, 팀원 검토 대기
    CONFIRMED; // 검토 후 확정
}
