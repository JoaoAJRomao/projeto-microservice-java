package com.ms.user.events;

import java.util.UUID;

/**
 * Evento interno (dentro do ms-user) disparado quando um usuário é salvo.
 * Só é tratado depois do COMMIT da transação — ver {@link UserCreatedListener}.
 */
public record UserCreatedEvent(UUID userId, String name, String email) {
}
