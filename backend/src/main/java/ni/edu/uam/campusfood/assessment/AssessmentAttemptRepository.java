package ni.edu.uam.campusfood.assessment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AssessmentAttemptRepository extends JpaRepository<AssessmentAttempt, Long> {
    Optional<AssessmentAttempt> findFirstByUserIdAndTestIdAndStatusOrderByStartedAtDesc(
            Long userId, String testId, AttemptStatus status);

    List<AssessmentAttempt> findByUserIdOrderByStartedAtDesc(Long userId);
}
