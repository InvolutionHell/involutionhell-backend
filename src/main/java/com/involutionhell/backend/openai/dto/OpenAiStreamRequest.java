package com.involutionhell.backend.openai.dto;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/**
 * 接收来自前端的 OpenAI 对话请求体。
 * 格式与 Vercel AI SDK 默认发送的 payload ({"messages": [...]}) 保持高度一致。
 */
public record OpenAiStreamRequest(
                /*
                 * ============================
                 * 🗑️ 被废弃的旧版结构留痕（由于它引发了参数名寻找报错）：
                 * 
                 * @NotBlank(message = "消息不能为空") String message,
                 * String instructions,
                 * ============================
                 */

                // 接收完整的多轮对话记录，赋能 AI 完整的上下文记忆能力
                @NotEmpty(message = "对话历史不能为空") List<Message> messages,
                // 可选的模型设定，由于保留了该参数，前端传参优先级最高
                String model) {
        /**
         * 单条对话消息的结构体。
         * role: 例如 "user" (用户) 或者 "assistant" (模型)
         * content: 实际的消息文本
         */
        public record Message(String role, String content) {
        }
}
