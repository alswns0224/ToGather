package com.togather.task.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record DeliverableInput(
    @NotBlank @Size(max = 200) String name,
    @Size(max = 20) @Pattern(regexp = "(?:\\.?[a-zA-Z0-9]+)?") String ext
) {
}
