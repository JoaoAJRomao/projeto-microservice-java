package com.ms.email.repositories;

import com.ms.email.models.EmailModel;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface EmailRepository extends JpaRepository<EmailModel, UUID> {

    Slice<EmailModel> findAllBy(Pageable pageable);

    List<EmailModel> findByUserIdOrderBySendDateEmailDesc(UUID userId);
}
