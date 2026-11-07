package com.ms.user.exceptions;

/** Regra de negócio: um e-mail só pode ser cadastrado uma vez. */
public class EmailAlreadyRegisteredException extends RuntimeException {

    public EmailAlreadyRegisteredException() {
        super("Já existe um usuário cadastrado com este e-mail.");
    }
}
