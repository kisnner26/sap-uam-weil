package ni.edu.uam.campusfood.admission;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/** Cada hora avisa a quienes tienen una convocatoria que abre en las proximas 24 horas (una sola vez por habilitacion). */
@Component
public class ReminderJob {
    private final AdmissionService admission;

    public ReminderJob(AdmissionService admission) {
        this.admission = admission;
    }

    @Scheduled(cron = "${app.admission.reminder-cron:0 0 * * * *}")
    public void run() {
        admission.sendReminders(Instant.now(), Duration.ofHours(24));
    }
}
