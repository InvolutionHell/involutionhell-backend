package com.involutionhell.backend.openai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.involutionhell.backend.openai.dto.OpenAiStreamRequest;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class OpenAiStreamServiceTests {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private OpenAiStreamRequest createMockRequest() {
        return new OpenAiStreamRequest(List.of(new OpenAiStreamRequest.Message("user", "你好")), null);
    }

    @Test
    void streamToOutputStreamRelaysAndTransformsEvents() {
        OpenAiStreamService service = new OpenAiStreamService(
                new FakeGateway("""
                        data: {"id":"123","choices":[{"delta":{"content":"你"}}]}

                        data: {"id":"123","choices":[{"delta":{"content":"好"}}]}

                        data: [DONE]

                        """),
                objectMapper);
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        service.streamToOutputStream(createMockRequest(), outputStream);

        String result = outputStream.toString(StandardCharsets.UTF_8);
        assertThat(result).contains("0:\"你\"\n");
        assertThat(result).contains("0:\"好\"\n");
    }

    @Test
    void streamToOutputStreamSendsErrorEventWhenGatewayFails() {
        OpenAiStreamService service = new OpenAiStreamService(new FailingGateway(), objectMapper);
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        service.streamToOutputStream(createMockRequest(), outputStream);

        String result = outputStream.toString(StandardCharsets.UTF_8);
        assertThat(result).contains("e:\"上游连接失败\"\n");
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

    private static final class FailingGateway implements OpenAiStreamGateway {

        @Override
        public void validateConfiguration(OpenAiStreamRequest request) {
        }

        @Override
        public InputStream openStream(OpenAiStreamRequest request) throws IOException {
            throw new IOException("上游连接失败");
        }
    }
}
