package com.togather.common.enums;

/** C-00-31 가용 시간 선호 */
public enum SlotPreference {
    AVAILABLE, // 가능
    UNAVAILABLE, // 불가능, 캘린더 일정은 자동으로 불가능
    NOT_PREFERRED; // 비선호
}
