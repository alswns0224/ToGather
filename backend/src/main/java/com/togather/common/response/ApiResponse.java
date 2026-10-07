package com.togather.common.response;

/**
 * 성공 응답: { "data": ... }
 */
public record ApiResponse<T>(T data) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(data);
    }

    public static ApiResponse<Void> ok() {
        return new ApiResponse<>(null);
    }
}
