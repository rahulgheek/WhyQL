package com.whyql.whyql.jobs.config;

import java.util.HashMap;
import java.util.Map;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

/**
 * Kafka producer/consumer wiring. Payloads are JSON strings (serialized with
 * the shared tools.jackson {@code ObjectMapper}); key = jobId.
 */
@Configuration
public class KafkaConfig {

  @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
  private String bootstrapServers;

  @Bean
  public ProducerFactory<String, String> producerFactory() {
    Map<String, Object> props = new HashMap<>();
    props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    props.put(ProducerConfig.ACKS_CONFIG, "all");
    props.put(ProducerConfig.RETRIES_CONFIG, 3);
    return new DefaultKafkaProducerFactory<>(props);
  }

  @Bean
  public KafkaTemplate<String, String> kafkaTemplate(
      ProducerFactory<String, String> producerFactory) {
    return new KafkaTemplate<>(producerFactory);
  }

  @Bean
  public ConsumerFactory<String, String> consumerFactory(
      @Value("${spring.kafka.consumer.group-id:whyql}") String groupId) {
    Map<String, Object> props = new HashMap<>();
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
    return new DefaultKafkaConsumerFactory<>(props);
  }

  /**
   * Own listener container factory with plain String payloads. Without it Boot's
   * auto-config installs a JacksonJsonMessageConverter that tries type-mapping
   * and fails on record payloads ("Cannot deserialize String from START_OBJECT").
   * The bean name is the one {@code @KafkaListener} looks up by default, so the
   * auto-configured alternative backs off.
   */
  @Bean
  public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
      ConsumerFactory<String, String> consumerFactory) {
    ConcurrentKafkaListenerContainerFactory<String, String> factory =
        new ConcurrentKafkaListenerContainerFactory<>();
    factory.setConsumerFactory(consumerFactory);
    return factory;
  }
}
