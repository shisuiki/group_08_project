package edu.illinois.group8.semantic;

import java.util.List;

public interface OpenRouterClient {
    OpenRouterCompletion complete(String model, List<OpenRouterMessage> messages, int maxTokens);
}
