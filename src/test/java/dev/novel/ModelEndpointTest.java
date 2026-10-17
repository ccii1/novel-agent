package dev.novel;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ModelEndpointTest {
    @Test void localBaseAddressResolvesToChatCompletions() {
        assertThat(ModelEndpoint.resolve("http://127.0.0.1:18080/v1").toString()).isEqualTo("http://127.0.0.1:18080/v1/chat/completions");
        assertThat(ModelEndpoint.resolve("http://localhost:18080/v1/").toString()).isEqualTo("http://localhost:18080/v1/chat/completions");
        assertThat(ModelEndpoint.resolve("http://[::1]:18080/v1").toString()).isEqualTo("http://[::1]:18080/v1/chat/completions");
    }
    @Test void completeHttpsAddressPreservesProviderQuery() {
        String address="https://model.example/v1/chat/completions?api-version=2026";
        assertThat(ModelEndpoint.resolve(address).toString()).isEqualTo(address);
    }
    @Test void remotePlainHttpAndEmbeddedCredentialsAreRejected() {
        assertThatThrownBy(()->ModelEndpoint.resolve("http://model.example/v1")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("HTTPS");
        assertThatThrownBy(()->ModelEndpoint.resolve("https://user:secret@model.example/v1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->ModelEndpoint.resolve("http://127.0.0.1.example/v1")).isInstanceOf(IllegalArgumentException.class);
    }
}
