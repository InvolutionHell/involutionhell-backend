package com.involutionhell.backend.openai.service;

import java.io.IOException;

public interface OpenAiEventSink {

    /**
     * 发送一条 SSE 事件到下游客户端。
     */
    void send(String eventName, String data) throws IOException;

    /**
     * 标记当前 SSE 流已正常结束。
     */
    void complete();

    /**
     * 标记当前 SSE 流因异常结束。
     */
    void completeWithError(Throwable throwable);
}
