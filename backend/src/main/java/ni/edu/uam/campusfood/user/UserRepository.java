package ni.edu.uam.campusfood.user;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

import java.util.Optional;
import java.util.List;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByMoodleId(Integer moodleId);

    Optional<User> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);

    List<User> findAllByRoleInOrderByFullNameAsc(List<Role> roles);

    boolean existsByRole(Role role);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    Optional<User> findByIdForUpdate(@Param("id") Long id);

    @Query("select u from User u where u.role = 'CLIENTE' and u.anonymizedAt is null and u.createdAt < :cutoff")
    List<User> findRetentionCandidates(@Param("cutoff") java.time.Instant cutoff);

    @Query("select u from User u where u.role = 'CLIENTE' and (lower(u.email) like lower(concat('%', :q, '%')) "
            + "or lower(u.fullName) like lower(concat('%', :q, '%'))) order by u.fullName")
    List<User> searchApplicants(@Param("q") String q, org.springframework.data.domain.Pageable page);
}
