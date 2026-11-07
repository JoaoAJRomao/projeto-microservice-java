package com.ms.user.producers;

import com.ms.user.dtos.EmailDto;
import com.ms.user.events.UserCreatedEvent;
import com.ms.user.observability.CorrelationId;
import com.ms.user.observability.LogMasking;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Publica o pedido de e-mail de boas-vindas na fila do ms-email (ADR-0001).
 * O Correlation ID da requisição HTTP segue como header AMQP (ADR-0003).
 */
@Component
public class UserProducer {

    private static final Logger log = LoggerFactory.getLogger(UserProducer.class);

    private final RabbitTemplate rabbitTemplate;
    private final String routingKey;

    public UserProducer(RabbitTemplate rabbitTemplate,
                        @Value("${broker.queue.email.name}") String routingKey) {
        this.rabbitTemplate = rabbitTemplate;
        this.routingKey = routingKey;
    }

    public void publishMessageEmail(UserCreatedEvent user) {
        var emailDto = new EmailDto();
        emailDto.setUserId(user.userId());
        emailDto.setEmailTo(user.email());
        emailDto.setSubject("Cadastro realizado com sucesso!");
        emailDto.setText(user.name() + ", seja bem-vindo(a)! \nAgradecemos o seu cadastro.");

        String correlationId = CorrelationId.currentOrNew();

        // Exchange "" (default): a routing key é o próprio nome da fila.
        rabbitTemplate.convertAndSend("", routingKey, emailDto, message -> {
            message.getMessageProperties().setHeader(CorrelationId.HEADER, correlationId);
            message.getMessageProperties().setCorrelationId(correlationId);
            return message;
        });

        log.info("Pedido de e-mail publicado na fila {} (userId={}, para={})",
                routingKey, user.userId(), LogMasking.email(user.email()));
    }
}
