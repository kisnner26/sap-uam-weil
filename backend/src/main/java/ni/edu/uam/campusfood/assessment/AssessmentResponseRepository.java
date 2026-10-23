package ni.edu.uam.campusfood.assessment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AssessmentResponseRepository extends JpaRepository<AssessmentResponse, Long> {
    Optional<AssessmentResponse> findByAttemptIdAndItemIndex(Long attemptId, int itemIndex);
    List<AssessmentResponse> findByAttemptIdOrderByItemIndex(Long attemptId);
    long countByAttemptId(Long attemptId);
}
