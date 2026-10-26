package ni.edu.uam.campusfood.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, Long> {
    Optional<PasswordResetToken> findByTokenHash(String tokenHash);

    /** Un enlace nuevo invalida los anteriores sin usar. */
    @Modifying
    @Query("delete from PasswordResetToken t where t.userId = :userId and t.usedAt is null")
    void deleteUnusedByUserId(@Param("userId") Long userId);
}
