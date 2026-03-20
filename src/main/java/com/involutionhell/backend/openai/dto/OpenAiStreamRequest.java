package com.involutionhell.backend.openai.dto;

import jakarta.validation.constraints.NotBlank;

public record OpenAiStreamRequest(
        @NotBlank(message = "消息不能为空")
        String message,
        String instructions,
        String model
) {
}
