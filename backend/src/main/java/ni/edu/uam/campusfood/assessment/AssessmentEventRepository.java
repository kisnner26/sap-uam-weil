package ni.edu.uam.campusfood.assessment;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface AssessmentEventRepository extends JpaRepository<AssessmentEvent, Long> {
    void deleteByAttemptId(Long attemptId);

    @Query("select e from AssessmentEvent e where e.attempt.id in :ids order by e.attempt.id, e.occurredAt")
    List<AssessmentEvent> findForAttempts(@Param("ids") Collection<Long> ids);
}
