package com.ms.user.exceptions;

import com.ms.user.observability.CorrelationId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Converte erros de negócio em respostas RFC 9457 (Problem Details).
 * Toda resposta de erro carrega o correlationId, para o cliente informar ao suporte.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(EmailAlreadyRegisteredException.class)
    public ResponseEntity<ProblemDetail> handleDuplicatedEmail(EmailAlreadyRegisteredException ex) {
        log.warn("Cadastro recusado: e-mail duplicado");
        return conflict(ex.getMessage());
    }

    /** Duas requisições simultâneas com o mesmo e-mail: a constraint UNIQUE do banco decide. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> handleIntegrity(DataIntegrityViolationException ex) {
        // A mensagem original do PostgreSQL contém o e-mail (dado pessoal); por isso não é logada.
        log.warn("Cadastro recusado pela constraint UNIQUE do banco ({})", ex.getClass().getSimpleName());
        return conflict("Já existe um usuário cadastrado com este e-mail.");
    }

    private ResponseEntity<ProblemDetail> conflict(String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, detail);
        problem.setTitle("E-mail já cadastrado");
        problem.setProperty("correlationId", CorrelationId.currentOrNew());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }
}
