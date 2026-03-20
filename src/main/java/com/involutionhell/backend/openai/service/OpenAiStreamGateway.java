package com.involutionhell.backend.openai.service;

import com.involutionhell.backend.openai.dto.OpenAiStreamRequest;
import java.io.IOException;
import java.io.InputStream;

public interface OpenAiStreamGateway {

    /**
     * 校验 OpenAI 接口调用所需的基础配置是否完整。
     */
    void validateConfiguration(OpenAiStreamRequest request);

    /**
     * 建立到 OpenAI Responses API 的流式连接并返回响应输入流。
     */
    InputStream openStream(OpenAiStreamRequest request) throws IOException, InterruptedException;
}
