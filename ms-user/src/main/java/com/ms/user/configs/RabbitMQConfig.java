package com.ms.user.configs;

import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * A fila é declarada pelos DOIS serviços (declaração idempotente no RabbitMQ).
 * Assim, se o ms-user publicar antes de o ms-email subir, a mensagem não se perde.
 * ATENÇÃO: os argumentos precisam ser idênticos aos do ms-email (ver ADR-0001).
 */
@Configuration
public class RabbitMQConfig {

    @Value("${broker.queue.email.name}")
    private String queue;

    @Value("${broker.queue.email.dlq}")
    private String deadLetterQueue;

    @Bean
    public Queue emailQueue() {
        return QueueBuilder.durable(queue)
                .deadLetterExchange("")
                .deadLetterRoutingKey(deadLetterQueue)
                .build();
    }

    @Bean
    public Queue emailDeadLetterQueue() {
        return QueueBuilder.durable(deadLetterQueue).build();
    }

    @Bean
    public MessageConverter messageConverter() {
        return new JacksonJsonMessageConverter();
    }
}
