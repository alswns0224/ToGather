package com.togather.meeting.domain;

import com.togather.common.domain.BaseTimeEntity;
import com.togather.common.domain.DateRange;
import com.togather.common.enums.SlotPreference;
import com.togather.common.enums.SlotSource;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** C-00-17 */
@Getter
@Entity
@Table(name = "availability_slot")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AvailabilitySlot extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long slotId;

    private Long meetingId;

    private Long userId;

    @Enumerated(EnumType.STRING)
    private SlotPreference preference;

    @Enumerated(EnumType.STRING)
    private SlotSource source;

    @Embedded
    private DateRange period;

    public AvailabilitySlot(Long meetingId, Long userId, SlotPreference preference, SlotSource source, DateRange period) {
        this.meetingId = meetingId;
        this.userId = userId;
        this.preference = preference;
        this.source = source;
        this.period = period;
    }
}
