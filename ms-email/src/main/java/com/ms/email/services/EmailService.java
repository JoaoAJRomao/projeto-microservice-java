package com.ms.email.services;

import com.ms.email.enums.StatusEmail;
import com.ms.email.models.EmailModel;
import com.ms.email.observability.LogMasking;
import com.ms.email.repositories.EmailRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    private final EmailRepository emailRepository;
    private final JavaMailSender emailSender;

    @Value("${app.mail.from}")
    private String emailFrom;

    /**
     * Envia o e-mail e SEMPRE registra o resultado (SENT ou ERROR).
     * Falha de SMTP não derruba o consumidor: vira status ERROR no histórico.
     */
    public EmailModel sendEmail(EmailModel emailModel) {
        long start = System.nanoTime();
        try {
            emailModel.setSendDateEmail(LocalDateTime.now());
            emailModel.setEmailFrom(emailFrom);

            var message = new SimpleMailMessage();
            message.setFrom(emailModel.getEmailFrom());
            message.setTo(emailModel.getEmailTo());
            message.setSubject(emailModel.getSubject());
            message.setText(emailModel.getText());
            emailSender.send(message);

            emailModel.setStatusEmail(StatusEmail.SENT);
        } catch (MailException e) {
            emailModel.setStatusEmail(StatusEmail.ERROR);
            // A mensagem completa pode conter o endereço do destinatário (dado pessoal): só em DEBUG.
            log.warn("Falha no envio SMTP (userId={}, erro={})", emailModel.getUserId(), e.getClass().getSimpleName());
            log.debug("Detalhe da falha SMTP", e);
        } finally {
            emailModel = emailRepository.save(emailModel);
        }

        long durationMs = (System.nanoTime() - start) / 1_000_000;
        log.info("E-mail processado (emailId={}, userId={}, para={}, status={}, {} ms)",
                emailModel.getEmailId(), emailModel.getUserId(), LogMasking.email(emailModel.getEmailTo()),
                emailModel.getStatusEmail(), durationMs);
        return emailModel;
    }

    @Transactional(readOnly = true)
    public List<EmailModel> findAll(int page, int size) {
        return emailRepository.findAllBy(PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "sendDateEmail")))
                .getContent();
    }

    @Transactional(readOnly = true)
    public List<EmailModel> findByUserId(UUID userId) {
        return emailRepository.findByUserIdOrderBySendDateEmailDesc(userId);
    }
}
