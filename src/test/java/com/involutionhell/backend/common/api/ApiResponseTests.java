package com.involutionhell.backend.common.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ApiResponseTests {

    @Test
    void okWithMessageCreatesSuccessResponse() {
        ApiResponse<Integer> response = ApiResponse.ok("created", 1);

        assertThat(response.success()).isTrue();
        assertThat(response.message()).isEqualTo("created");
        assertThat(response.data()).isEqualTo(1);
    }

    @Test
    void okWithoutMessageUsesDefaultMessage() {
        ApiResponse<String> response = ApiResponse.ok("payload");

        assertThat(response.success()).isTrue();
        assertThat(response.message()).isEqualTo("ok");
        assertThat(response.data()).isEqualTo("payload");
    }

    @Test
    void okMessageCreatesEmptySuccessResponse() {
        ApiResponse<Void> response = ApiResponse.okMessage("done");

        assertThat(response.success()).isTrue();
        assertThat(response.message()).isEqualTo("done");
        assertThat(response.data()).isNull();
    }

    @Test
    void failCreatesFailureResponse() {
        ApiResponse<Void> response = ApiResponse.fail("error");

        assertThat(response.success()).isFalse();
        assertThat(response.message()).isEqualTo("error");
        assertThat(response.data()).isNull();
    }
}
