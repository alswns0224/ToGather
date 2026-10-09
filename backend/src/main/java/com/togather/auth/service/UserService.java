package com.togather.auth.service;

import com.togather.auth.dto.UserSummary;
import com.togather.auth.repository.UserRepository;
import com.togather.common.exception.BusinessException;
import com.togather.common.exception.ErrorCode;
import java.util.Collection;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 다른 파트에 사용자 정보를 제공한다. (docs/shared-interfaces.md, A 제공)
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserService {

    private final UserRepository userRepository;

    public UserSummary getUser(Long userId) {
        return userRepository.findById(userId)
                .map(UserSummary::from)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
    }

    /** 없는 ID는 결과에서 빠진다. */
    public List<UserSummary> getUsers(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return List.of();
        }
        return userRepository.findAllById(userIds).stream().map(UserSummary::from).toList();
    }
}
