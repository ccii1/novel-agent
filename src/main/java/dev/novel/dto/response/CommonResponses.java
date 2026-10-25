package dev.novel.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

public final class CommonResponses {
    private CommonResponses() {
    }

    public record CreatedResponse(String id) {
    }

    public record ActionResponse(boolean ok) {
        public static final ActionResponse SUCCESS = new ActionResponse(true);
    }

    public record UserResponse(String username) {
    }

    public record CsrfResponse(String token) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ErrorResponse(String error, String provider, String errorCode) {
        public ErrorResponse(String error) {
            this(error, null, null);
        }
    }
}
