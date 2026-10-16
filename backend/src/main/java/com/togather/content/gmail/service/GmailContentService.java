package com.togather.content.gmail.service;

import com.togather.common.enums.Provider;
import com.togather.content.dto.ContentRefDTO;
import com.togather.content.service.ContentRefService;
import com.togather.sync.service.SyncService;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GmailContentService {

    /**
     * 화면 진입 시 이 시간보다 오래됐으면 Gmail을 다시 동기화한다.
     */
    private static final Duration SYNC_MAX_AGE = Duration.ofMinutes(5);

    private final ContentRefService contentRefService;
    private final SyncService syncService;

    /**
     * 현재 워크스페이스에서 사용자의 Gmail 메일을 조회한다.
     *
     * 오래된 경우 먼저 증분 동기화를 시도한 뒤
     * 저장된 ContentRef(MAIL)를 반환한다.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public List<ContentRefDTO> getContentsByUser(
        Long workspaceId,
        Long userId
    ) {
        syncService.syncIfStale(
            workspaceId,
            Provider.GMAIL,
            userId,
            SYNC_MAX_AGE
        );

        return contentRefService.getPersonalContents(
            workspaceId,
            userId,
            Provider.GMAIL,
            null
        );
    }

    /**
     * 특정 시각 이후 변경된 Gmail 콘텐츠 조회.
     * AI 업무 보조나 추후 증분 처리에서 사용할 수 있다.
     */
    public List<ContentRefDTO> getContentsByUser(
        Long workspaceId,
        Long userId,
        LocalDateTime since
    ) {
        return contentRefService.getPersonalContents(
            workspaceId,
            userId,
            Provider.GMAIL,
            since
        );
    }
}
