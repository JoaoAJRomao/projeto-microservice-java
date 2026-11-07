package com.ms.email.configs;

import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Fila principal + Dead Letter Queue (DLQ).
 * Mensagens que falham após as tentativas de retry vão para a DLQ, onde podem
 * ser inspecionadas na UI do RabbitMQ (http://localhost:15672) — ver ADR-0001.
 * ATENÇÃO: os argumentos precisam ser idênticos aos declarados no ms-user.
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
