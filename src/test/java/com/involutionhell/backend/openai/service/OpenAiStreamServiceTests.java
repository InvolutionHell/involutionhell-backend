package com.involutionhell.backend.openai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.involutionhell.backend.openai.dto.OpenAiStreamRequest;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class OpenAiStreamServiceTests {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void streamToSinkRelaysTypedEventsAndCompletesNormally() {
        OpenAiStreamService service = new OpenAiStreamService(
                new FakeGateway("""
                        data: {"type":"response.output_text.delta","delta":"你"}

                        data: {"type":"response.completed"}

                        """),
                objectMapper
        );
        RecordingSink sink = new RecordingSink();

        service.streamToSink(new OpenAiStreamRequest("你好", null, null), sink);

        assertThat(sink.events).hasSize(2);
        assertThat(sink.events.get(0).eventName()).isEqualTo("response.output_text.delta");
        assertThat(sink.events.get(0).data()).contains("\"delta\":\"你\"");
        assertThat(sink.events.get(1).eventName()).isEqualTo("response.completed");
        assertThat(sink.completed).isTrue();
        assertThat(sink.error).isNull();
    }

    @Test
    void streamToSinkRelaysDoneSentinelAndCompletesNormally() {
        OpenAiStreamService service = new OpenAiStreamService(
                new FakeGateway("""
                        data: [DONE]

                        """),
                objectMapper
        );
        RecordingSink sink = new RecordingSink();

        service.streamToSink(new OpenAiStreamRequest("你好", null, null), sink);

        assertThat(sink.events).containsExactly(new RecordedEvent("done", "[DONE]"));
        assertThat(sink.completed).isTrue();
        assertThat(sink.error).isNull();
    }

    @Test
    void streamRejectsMissingConfigurationBeforeCreatingEmitter() {
        OpenAiStreamService service = new OpenAiStreamService(new InvalidGateway(), objectMapper);

        assertThatThrownBy(() -> service.stream(new OpenAiStreamRequest("你好", null, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("OPENAI_API_KEY 未配置");
    }

    @Test
    void streamToSinkSendsErrorEventWhenGatewayFails() {
        OpenAiStreamService service = new OpenAiStreamService(new FailingGateway(), objectMapper);
        RecordingSink sink = new RecordingSink();

        service.streamToSink(new OpenAiStreamRequest("你好", null, null), sink);

        assertThat(sink.events).hasSize(1);
        assertThat(sink.events.get(0).eventName()).isEqualTo("error");
        assertThat(sink.events.get(0).data()).contains("上游连接失败");
        assertThat(sink.completed).isFalse();
        assertThat(sink.error).isInstanceOf(IOException.class);
    }

    private static final class FakeGateway implements OpenAiStreamGateway {

        private final String sseBody;

        private FakeGateway(String sseBody) {
            this.sseBody = sseBody;
        }

        @Override
        public void validateConfiguration(OpenAiStreamRequest request) {
        }

        @Override
        public InputStream openStream(OpenAiStreamRequest request) {
            return new ByteArrayInputStream(sseBody.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static final class InvalidGateway implements OpenAiStreamGateway {

        @Override
        public void validateConfiguration(OpenAiStreamRequest request) {
            throw new IllegalStateException("OPENAI_API_KEY 未配置");
        }

        @Override
        public InputStream openStream(OpenAiStreamRequest request) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class FailingGateway implements OpenAiStreamGateway {

        @Override
        public void validateConfiguration(OpenAiStreamRequest request) {
        }

        @Override
        public InputStream openStream(OpenAiStreamRequest request) throws IOException {
            throw new IOException("上游连接失败");
        }
    }

    private static final class RecordingSink implements OpenAiEventSink {

        private final List<RecordedEvent> events = new ArrayList<>();
        private boolean completed;
        private Throwable error;

        @Override
        public void send(String eventName, String data) {
            events.add(new RecordedEvent(eventName, data));
        }

        @Override
        public void complete() {
            completed = true;
        }

        @Override
        public void completeWithError(Throwable throwable) {
            error = throwable;
        }
    }

    private record RecordedEvent(String eventName, String data) {
    }
}
