package com.ms.email.controllers;

import com.ms.email.models.EmailModel;
import com.ms.email.services.EmailService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Consulta do histórico de envios (somente leitura). */
@RestController
@RequestMapping("/emails")
@RequiredArgsConstructor
public class EmailController {

    private static final int MAX_PAGE_SIZE = 100;

    private final EmailService emailService;

    /** GET /emails?page=0&size=20 — mais recentes primeiro. */
    @GetMapping
    public ResponseEntity<List<EmailModel>> findAll(@RequestParam(name = "page", defaultValue = "0") int page,
                                                    @RequestParam(name = "size", defaultValue = "20") int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        return ResponseEntity.ok(emailService.findAll(safePage, safeSize));
    }

    /** GET /emails/user/{userId} — histórico de e-mails de um usuário. */
    @GetMapping("/user/{userId}")
    public ResponseEntity<List<EmailModel>> findByUser(@PathVariable("userId") UUID userId) {
        return ResponseEntity.ok(emailService.findByUserId(userId));
    }
}
