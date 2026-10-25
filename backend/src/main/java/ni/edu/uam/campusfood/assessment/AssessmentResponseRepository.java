package ni.edu.uam.campusfood.assessment;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AssessmentResponseRepository extends JpaRepository<AssessmentResponse, Long> {
    Optional<AssessmentResponse> findByAttemptIdAndItemIndex(Long attemptId, int itemIndex);
    List<AssessmentResponse> findByAttemptIdOrderByItemIndex(Long attemptId);
    long countByAttemptId(Long attemptId);
    void deleteByAttemptId(Long attemptId);
    void deleteByAttemptIdAndItemIndex(Long attemptId, int itemIndex);

    @Query("select r from AssessmentResponse r where r.attempt.id in :ids order by r.attempt.id, r.itemIndex")
    List<AssessmentResponse> findForAttempts(@Param("ids") Collection<Long> ids);
}
