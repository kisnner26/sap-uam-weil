package ni.edu.uam.campusfood.security;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

public interface LoginThrottleRepository extends JpaRepository<LoginThrottle, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from LoginThrottle t where t.key = :key")
    Optional<LoginThrottle> findForUpdate(@Param("key") String key);
}
