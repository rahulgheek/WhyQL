package com.whyql.whyql.query.config;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AI configuration for the query module. Creates a ChatClient from the
 * auto-configured OpenAI-compatible ChatModel (Groq via Spring AI OpenAI starter).
 */
@Configuration
public class QueryAiConfig {

  @Bean
  ChatClient chatClient(ChatModel chatModel) {
    return ChatClient.builder(chatModel)
        .defaultOptions(ChatOptions.builder().temperature(0.0))
        .build();
  }
}
