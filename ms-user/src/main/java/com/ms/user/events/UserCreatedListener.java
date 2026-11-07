package com.ms.user.events;

import com.ms.user.producers.UserProducer;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Publica a mensagem no RabbitMQ somente APÓS o commit no banco (ADR-0001).
 *
 * <p>Isso elimina o cenário "e-mail enviado para um usuário que não foi salvo"
 * (rollback depois da publicação). O cenário inverso — usuário salvo e broker
 * indisponível — continua possível: é um risco reconhecido, registrado em log
 * com o Correlation ID, cuja solução definitiva é o padrão Transactional Outbox.</p>
 */
@Component
@RequiredArgsConstructor
public class UserCreatedListener {

    private static final Logger log = LoggerFactory.getLogger(UserCreatedListener.class);

    private final UserProducer userProducer;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onUserCreated(UserCreatedEvent event) {
        try {
            userProducer.publishMessageEmail(event);
        } catch (AmqpException e) {
            log.error("Falha ao publicar pedido de e-mail para userId={}. Usuário já está salvo; "
                    + "e-mail de boas-vindas NÃO será enviado (risco aceito no ADR-0001)", event.userId(), e);
        }
    }
}
