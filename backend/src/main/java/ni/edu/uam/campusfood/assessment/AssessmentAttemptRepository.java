package ni.edu.uam.campusfood.assessment;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AssessmentAttemptRepository extends JpaRepository<AssessmentAttempt, Long> {
    Optional<AssessmentAttempt> findFirstByUserIdAndTestIdAndStatusOrderByStartedAtDesc(
            Long userId, String testId, AttemptStatus status);

    List<AssessmentAttempt> findByUserIdOrderByStartedAtDesc(Long userId);

    @EntityGraph(attributePaths = "user")
    List<AssessmentAttempt> findByTestIdAndStatusOrderBySubmittedAtDesc(String testId, AttemptStatus status);

    Optional<AssessmentAttempt> findFirstByEnrollmentIdOrderByStartedAtDesc(Long enrollmentId);

    boolean existsByEnrollmentId(Long enrollmentId);

    List<AssessmentAttempt> findByEnrollmentIdIn(Collection<Long> enrollmentIds);

    @EntityGraph(attributePaths = {"enrollment", "enrollment.session"})
    List<AssessmentAttempt> findByStatusAndDeadlineAtBefore(AttemptStatus status, Instant cutoff);

    /** Intentos entregados, con aspirante y convocatoria, para el listado del personal. */
    @Query("select a from AssessmentAttempt a join fetch a.user left join fetch a.enrollment e left join fetch e.session "
            + "where a.testId = :testId and a.status = 'SUBMITTED' and (:sessionId is null or e.session.id = :sessionId) "
            + "order by a.submittedAt desc")
    List<AssessmentAttempt> findSubmitted(@Param("testId") String testId, @Param("sessionId") Long sessionId);

    /** Página de intentos entregados; los filtros se resuelven en la base y no en memoria. */
    @Query(value = "select a from AssessmentAttempt a join fetch a.user u left join fetch a.enrollment e left join fetch e.session s "
            + "where a.testId = :testId and a.status = 'SUBMITTED' and (:sessionId is null or s.id = :sessionId) "
            + "and (:needle is null or lower(u.fullName) like :needle escape '\\' or lower(u.email) like :needle escape '\\') "
            + "and (:invalid is null or (:invalid = true and a.invalidatedAt is not null) or (:invalid = false and a.invalidatedAt is null))",
            countQuery = "select count(a) from AssessmentAttempt a join a.user u left join a.enrollment e left join e.session s "
            + "where a.testId = :testId and a.status = 'SUBMITTED' and (:sessionId is null or s.id = :sessionId) "
            + "and (:needle is null or lower(u.fullName) like :needle escape '\\' or lower(u.email) like :needle escape '\\') "
            + "and (:invalid is null or (:invalid = true and a.invalidatedAt is not null) or (:invalid = false and a.invalidatedAt is null))")
    Page<AssessmentAttempt> searchSubmitted(@Param("testId") String testId, @Param("sessionId") Long sessionId,
                                            @Param("needle") String needle, @Param("invalid") Boolean invalid,
                                            Pageable pageable);

    /** Totales para el panel: [entregadas, promedio de válidas, mejor puntaje de válidas]. */
    @Query("select count(a), avg(case when a.invalidatedAt is null then a.directScore end), "
            + "max(case when a.invalidatedAt is null then a.directScore end) "
            + "from AssessmentAttempt a where a.testId = :testId and a.status = 'SUBMITTED'")
    List<Object[]> submittedTotals(@Param("testId") String testId);

    /** Datos del titular para atender una solicitud de acceso o eliminacion. */
    List<AssessmentAttempt> findByUserId(Long userId);
}
