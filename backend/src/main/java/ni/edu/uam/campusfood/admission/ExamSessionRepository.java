package ni.edu.uam.campusfood.admission;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ExamSessionRepository extends JpaRepository<ExamSession, Long> {
    List<ExamSession> findByTestIdOrderByOpensAtDesc(String testId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from ExamSession s where s.id = :id")
    Optional<ExamSession> findByIdForUpdate(@Param("id") Long id);
}
