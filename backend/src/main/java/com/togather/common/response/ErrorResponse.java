package com.togather.common.response;

import com.togather.common.exception.ErrorCode;

/**
 * 실패 응답: { "error": { "code": "...", "message": "..." } }
 */
public record ErrorResponse(ErrorBody error) {

    public record ErrorBody(String code, String message) {
    }

    public static ErrorResponse of(ErrorCode errorCode) {
        return new ErrorResponse(new ErrorBody(errorCode.name(), errorCode.getMessage()));
    }

    public static ErrorResponse of(ErrorCode errorCode, String message) {
        return new ErrorResponse(new ErrorBody(errorCode.name(), message));
    }
}
