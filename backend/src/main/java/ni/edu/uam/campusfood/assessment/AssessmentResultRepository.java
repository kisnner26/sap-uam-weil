package ni.edu.uam.campusfood.assessment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AssessmentResultRepository extends JpaRepository<AssessmentResult, Long> {
    Optional<AssessmentResult> findByAttemptId(Long attemptId);
}
