package com.ms.user.repositores;

import com.ms.user.models.UserModel;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface UserRepository extends JpaRepository<UserModel, UUID> {

    boolean existsByEmail(String email);

    /** Slice (e não Page) para não executar um COUNT(*) na tabela inteira a cada página. */
    Slice<UserModel> findAllBy(Pageable pageable);
}
