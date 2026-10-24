package ni.edu.uam.campusfood.admission;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ExamEnrollmentRepository extends JpaRepository<ExamEnrollment, Long> {
    @EntityGraph(attributePaths = {"session", "user"})
    List<ExamEnrollment> findBySessionIdOrderByEmailAsc(Long sessionId);

    List<ExamEnrollment> findBySessionIdIn(Collection<Long> sessionIds);

    @EntityGraph(attributePaths = {"session"})
    List<ExamEnrollment> findByUserId(Long userId);

    List<ExamEnrollment> findByUserIsNullAndCreatedAtBefore(Instant cutoff);

    @EntityGraph(attributePaths = {"session", "user"})
    Optional<ExamEnrollment> findByAccessCodeHash(String accessCodeHash);

    @EntityGraph(attributePaths = {"session"})
    List<ExamEnrollment> findByUserIdAndStatusOrderByCreatedAtDesc(Long userId, EnrollmentStatus status);

    @Query("select e from ExamEnrollment e join fetch e.session where e.session.id = :sessionId "
            + "and lower(e.email) = lower(:email) and e.status = 'ENABLED'")
    List<ExamEnrollment> findActiveByEmail(@Param("sessionId") Long sessionId, @Param("email") String email);

    @EntityGraph(attributePaths = {"session"})
    @Query("select e from ExamEnrollment e where e.status = 'ENABLED' and e.reminderSentAt is null "
            + "and e.session.cancelledAt is null and e.session.opensAt > :from and e.session.opensAt <= :to")
    List<ExamEnrollment> findNeedingReminder(@Param("from") Instant from, @Param("to") Instant to);

    @Query("select e.session.id, count(e) from ExamEnrollment e where e.session.id in :ids "
            + "and e.status = 'ENABLED' group by e.session.id")
    List<Object[]> countEnabledBySession(@Param("ids") Collection<Long> ids);
}
