package com.togather.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record DevLoginRequest(
        @NotBlank @Email String email,
        @NotBlank @Size(max = 100) String name
) {
}
