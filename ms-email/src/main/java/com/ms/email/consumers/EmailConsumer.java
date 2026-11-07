package com.ms.email.consumers;

import com.ms.email.dtos.EmailRecordDto;
import com.ms.email.models.EmailModel;
import com.ms.email.observability.CorrelationId;
import com.ms.email.services.EmailService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class EmailConsumer {

    private static final Logger log = LoggerFactory.getLogger(EmailConsumer.class);

    private final EmailService emailService;

    /**
     * Recebe o pedido de e-mail publicado pelo ms-user.
     * O Correlation ID vem no header AMQP e é colocado no MDC enquanto a mensagem
     * é processada — assim os logs dos dois serviços podem ser ligados (ADR-0003).
     */
    @RabbitListener(queues = "${broker.queue.email.name}")
    public void listenEmail(@Payload EmailRecordDto emailRecordDto,
                            @Header(name = CorrelationId.HEADER, required = false) String correlationIdHeader) {

        String correlationId = CorrelationId.resolve(correlationIdHeader);
        MDC.put(CorrelationId.MDC_KEY, correlationId);
        try {
            log.info("Pedido de e-mail recebido da fila (userId={})", emailRecordDto.userId());

            var emailModel = new EmailModel();
            emailModel.setUserId(emailRecordDto.userId());
            emailModel.setEmailTo(emailRecordDto.emailTo());
            emailModel.setSubject(emailRecordDto.subject());
            emailModel.setText(emailRecordDto.text());
            emailModel.setCorrelationId(correlationId);

            emailService.sendEmail(emailModel);
        } finally {
            MDC.remove(CorrelationId.MDC_KEY);
        }
    }
}
