package ni.edu.uam.campusfood.assessment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "assessment_responses", uniqueConstraints =
        @UniqueConstraint(name = "uk_response_attempt_item", columnNames = {"attempt_id", "item_index"}))
public class AssessmentResponse {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @jakarta.persistence.ManyToOne(optional = false, fetch = jakarta.persistence.FetchType.LAZY)
    @jakarta.persistence.JoinColumn(name = "attempt_id", nullable = false)
    private AssessmentAttempt attempt;

    @Column(name = "item_index", nullable = false)
    private int itemIndex;

    @Column(name = "selected_option", nullable = false)
    private int selectedOption;

    protected AssessmentResponse() {
    }

    public AssessmentResponse(AssessmentAttempt attempt, int itemIndex, int selectedOption) {
        this.attempt = attempt;
        this.itemIndex = itemIndex;
        this.selectedOption = selectedOption;
    }

    public void select(int option) { selectedOption = option; }
    public Long ownerAttemptId() { return attempt.getId(); }
    public int getItemIndex() { return itemIndex; }
    public int getSelectedOption() { return selectedOption; }
}
