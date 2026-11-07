package com.ms.user.services;

import com.ms.user.events.UserCreatedEvent;
import com.ms.user.exceptions.EmailAlreadyRegisteredException;
import com.ms.user.models.UserModel;
import com.ms.user.observability.LogMasking;
import com.ms.user.repositores.UserRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private final UserRepository userRepository;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * Regra de negócio: e-mail único (normalizado em minúsculas).
     * A mensagem para o ms-email só sai depois do commit (ver UserCreatedListener).
     */
    @Transactional
    public UserModel save(UserModel user) {
        user.setEmail(user.getEmail().trim().toLowerCase(Locale.ROOT));

        if (userRepository.existsByEmail(user.getEmail())) {
            throw new EmailAlreadyRegisteredException();
        }

        user = userRepository.save(user);
        log.info("Usuário cadastrado (userId={}, email={})", user.getUserId(), LogMasking.email(user.getEmail()));

        eventPublisher.publishEvent(new UserCreatedEvent(user.getUserId(), user.getName(), user.getEmail()));
        return user;
    }

    /** Listagem paginada — evita devolver a tabela inteira a cada chamada. */
    @Transactional(readOnly = true)
    public List<UserModel> findAll(int page, int size) {
        return userRepository.findAllBy(PageRequest.of(page, size, Sort.by("email"))).getContent();
    }
}
