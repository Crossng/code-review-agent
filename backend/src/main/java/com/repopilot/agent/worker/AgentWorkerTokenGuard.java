package com.repopilot.agent.worker;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import com.repopilot.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class AgentWorkerTokenGuard {

    private final AgentWorkerProperties properties;

    public AgentWorkerTokenGuard(AgentWorkerProperties properties) {
        this.properties = properties;
    }

    public void requireValidToken(String callbackToken) {
        String expected = properties.getCallbackToken();
        if (expected == null || expected.isBlank()) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    "AGENT_WORKER_CALLBACK_DISABLED",
                    "Agent Worker callback token is not configured"
            );
        }
        if (callbackToken == null || callbackToken.isBlank() || !constantTimeEquals(expected, callbackToken)) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    "AGENT_WORKER_CALLBACK_FORBIDDEN",
                    "Agent Worker callback token is invalid"
            );
        }
    }

    private boolean constantTimeEquals(String expected, String actual) {
        byte[] expectedBytes = expected.getBytes(StandardCharsets.UTF_8);
        byte[] actualBytes = actual.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expectedBytes, actualBytes);
    }
}
