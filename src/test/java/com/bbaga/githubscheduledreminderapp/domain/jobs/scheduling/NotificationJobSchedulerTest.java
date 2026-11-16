package com.bbaga.githubscheduledreminderapp.domain.jobs.scheduling;

import com.bbaga.githubscheduledreminderapp.application.jobs.ScheduledNotificationRequest;
import com.bbaga.githubscheduledreminderapp.domain.configuration.Notification;
import com.bbaga.githubscheduledreminderapp.domain.configuration.SlackNotificationConfiguration;
import com.bbaga.githubscheduledreminderapp.domain.jobs.scheduling.exceptions.SchedulingException;
import org.jobrunr.jobs.RecurringJob;
import org.jobrunr.scheduling.JobRequestScheduler;
import org.jobrunr.storage.RecurringJobsResult;
import org.jobrunr.storage.StorageProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;

/**
 * Rewritten for JobRunr: the Quartz version captured the JobDetail and CronTrigger passed to a mocked Scheduler.
 */
class NotificationJobSchedulerTest {

    private JobRequestScheduler jobRequestScheduler;
    private StorageProvider storageProvider;
    private NotificationJobScheduler jobScheduler;

    @BeforeEach
    void setUp() {
        this.jobRequestScheduler = Mockito.mock(JobRequestScheduler.class);
        this.storageProvider = Mockito.mock(StorageProvider.class);
        this.jobScheduler = new NotificationJobScheduler(jobRequestScheduler, storageProvider);
    }

    private static SlackNotificationConfiguration config(String schedule, String timeZone) {
        SlackNotificationConfiguration config = new SlackNotificationConfiguration();
        Notification notification = new Notification(config);
        notification.setRepository("test");
        notification.setName("schedule");
        notification.setSchedule(schedule);
        notification.setTimeZone(timeZone);
        return config;
    }

    @Test
    void upsertScheduleCreatesRecurringJobWithTranslatedCronAndZone() throws SchedulingException {
        Mockito.when(storageProvider.getRecurringJobs()).thenReturn(new RecurringJobsResult());

        jobScheduler.upsertSchedule("acme/repo-schedule", config("0 */2 * * * ?", "Europe/Berlin"));

        Mockito.verify(jobRequestScheduler).scheduleRecurrently(
            matches("notification-acme\\.repo-schedule\\([0-9a-z]+\\)"),
            eq("0 */2 * * * *"),
            eq(ZoneId.of("Europe/Berlin")),
            eq(new ScheduledNotificationRequest("acme/repo-schedule"))
        );
    }

    @Test
    void upsertScheduleUpdatesTheExistingRecurringJob() throws SchedulingException {
        RecurringJobsResult existing = new RecurringJobsResult(List.of(
            recurringJob("notification-acme.repo-schedule(abc123)"),
            recurringJob("notification-acme.repo-schedule-other(def456)")
        ));
        Mockito.when(storageProvider.getRecurringJobs()).thenReturn(existing);

        jobScheduler.upsertSchedule("acme/repo-schedule", config("0 0 9 ? * MON-FRI", "UTC"));

        Mockito.verify(jobRequestScheduler).scheduleRecurrently(
            eq("notification-acme.repo-schedule(abc123)"), eq("0 0 9 * * MON-FRI"), any(ZoneId.class), any(ScheduledNotificationRequest.class)
        );
    }

    @Test
    void recreatedNotificationGetsANewRecurringJobId() {
        // Reusing the id of a deleted recurring job makes JobRunr OSS schedule every run missed in between at once.
        String first = NotificationJobScheduler.newRecurringJobId("acme/repo-schedule", Instant.parse("2026-10-29T16:00:00Z"));
        String second = NotificationJobScheduler.newRecurringJobId("acme/repo-schedule", Instant.parse("2026-10-29T16:05:00Z"));

        assertNotEquals(first, second);
        assertEquals(NotificationJobScheduler.baseOf(first), NotificationJobScheduler.baseOf(second));
        assertTrue(first.matches("[\\dA-Za-z-_(),.]+") && first.length() < 128, first);
    }

    @Test
    void upsertScheduleKeepsQuartzTimeZoneIds() throws SchedulingException {
        Mockito.when(storageProvider.getRecurringJobs()).thenReturn(new RecurringJobsResult());
        jobScheduler.upsertSchedule("acme/repo-schedule", config("30 * * ? * MON-FRI", "EST"));

        Mockito.verify(jobRequestScheduler).scheduleRecurrently(
            anyString(), eq("30 * * * * MON-FRI"), eq(ZoneId.of("-05:00")), any(ScheduledNotificationRequest.class)
        );
    }

    @Test
    void upsertScheduleWithoutScheduleDoesNothing() throws SchedulingException {
        SlackNotificationConfiguration config = new SlackNotificationConfiguration();
        new Notification(config);

        jobScheduler.upsertSchedule("acme/repo-schedule", config);

        Mockito.verifyNoInteractions(jobRequestScheduler);
    }

    @Test
    void invalidScheduleBecomesSchedulingException() {
        Mockito.when(storageProvider.getRecurringJobs()).thenReturn(new RecurringJobsResult());
        assertThrows(SchedulingException.class,
            () -> jobScheduler.upsertSchedule("acme/repo-schedule", config("0 0 9 15W * ?", "UTC")));
    }

    @Test
    void deleteSchedulesNotInDeletesOnlyStaleNotificationJobs() {
        RecurringJobsResult recurringJobs = new RecurringJobsResult(List.of(
            recurringJob("notification-acme.repo-kept(k1)"),
            recurringJob("notification-acme.repo-removed(r1)"),
            recurringJob("state-dump")
        ));
        Mockito.when(storageProvider.getRecurringJobs()).thenReturn(recurringJobs);

        jobScheduler.deleteSchedulesNotIn(Set.of("acme/repo-kept"));

        Mockito.verify(jobRequestScheduler).deleteRecurringJob("notification-acme.repo-removed(r1)");
        Mockito.verify(jobRequestScheduler, Mockito.never()).deleteRecurringJob("notification-acme.repo-kept(k1)");
        Mockito.verify(jobRequestScheduler, Mockito.never()).deleteRecurringJob("state-dump");
    }

    @Test
    void recurringJobIdBaseIsReadableWhenPossible() {
        assertEquals("notification-acme.billing-service-team-standup",
            NotificationJobScheduler.recurringJobIdBase("acme/billing-service-team-standup"));
    }

    @Test
    void recurringJobIdOnlyUsesAllowedCharactersAndStaysUnique() {
        String withSpace = NotificationJobScheduler.recurringJobIdBase("acme/repo-daily standup");
        String withUnderscore = NotificationJobScheduler.recurringJobIdBase("acme/repo-daily_standup");

        assertTrue(withSpace.matches("[\\dA-Za-z-_(),.]+"), withSpace);
        assertNotEquals(withSpace, withUnderscore);
    }

    @Test
    void recurringJobIdIsShorterThan128Characters() {
        String id = NotificationJobScheduler.recurringJobIdBase("acme/" + "a".repeat(300));

        assertTrue(id.length() < 128, id);
        assertTrue(id.matches("[\\dA-Za-z-_(),.]+"), id);
    }

    private static RecurringJob recurringJob(String id) {
        RecurringJob recurringJob = Mockito.mock(RecurringJob.class);
        Mockito.when(recurringJob.getId()).thenReturn(id);
        Mockito.when(recurringJob.getCreatedAt()).thenReturn(Instant.now());
        return recurringJob;
    }
}
