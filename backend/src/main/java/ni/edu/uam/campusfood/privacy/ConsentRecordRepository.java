package ni.edu.uam.campusfood.privacy;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ConsentRecordRepository extends JpaRepository<ConsentRecord, Long> {
    boolean existsByUserIdAndPolicyVersion(Long userId, int policyVersion);

    List<ConsentRecord> findByUserIdOrderByAcceptedAtDesc(Long userId);

    void deleteByUserId(Long userId);
}
