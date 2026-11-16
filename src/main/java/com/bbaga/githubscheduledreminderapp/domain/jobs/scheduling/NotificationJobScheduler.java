package com.bbaga.githubscheduledreminderapp.domain.jobs.scheduling;

import com.bbaga.githubscheduledreminderapp.application.jobs.ScheduledNotificationRequest;
import com.bbaga.githubscheduledreminderapp.domain.configuration.ScheduledNotificationConfigurationInterface;
import com.bbaga.githubscheduledreminderapp.domain.jobs.scheduling.exceptions.SchedulingException;
import org.jobrunr.jobs.RecurringJob;
import org.jobrunr.scheduling.JobRequestScheduler;
import org.jobrunr.storage.StorageProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;
import java.util.TimeZone;
import java.util.stream.Collectors;

public class NotificationJobScheduler {
    static final String RECURRING_JOB_ID_PREFIX = "notification-";
    // 127 is JobRunr's maximum, minus room for the "(generation)" suffix
    private static final int MAX_RECURRING_JOB_ID_BASE_LENGTH = 112;

    private final JobRequestScheduler jobRequestScheduler;
    private final StorageProvider storageProvider;
    private final Logger logger = LoggerFactory.getLogger(NotificationJobScheduler.class);

    public NotificationJobScheduler(JobRequestScheduler jobRequestScheduler, StorageProvider storageProvider) {
        this.jobRequestScheduler = jobRequestScheduler;
        this.storageProvider = storageProvider;
    }

    /**
     * Creates the recurring job for a notification, or updates its schedule when it already exists.
     * JobRunr upserts by recurring job id, so Quartz's checkExists + scheduleJob / rescheduleJob pair collapses into one call.
     */
    public void upsertSchedule(String fullName, ScheduledNotificationConfigurationInterface config) throws SchedulingException {
        Optional<String> schedule = config.getSchedule();

        if (schedule.isEmpty()) {
            return;
        }

        try {
            jobRequestScheduler.scheduleRecurrently(
                existingRecurringJobId(fullName).orElseGet(() -> newRecurringJobId(fullName, Instant.now())),
                QuartzCron.toJobRunr(schedule.get()),
                toZoneId(config.getTimeZone()),
                new ScheduledNotificationRequest(fullName)
            );
        } catch (RuntimeException e) {
            throw new SchedulingException("Could not schedule notification " + fullName + " with schedule '" + schedule.get() + "'", e);
        }
    }

    /**
     * Deletes the recurring notification jobs whose notification is no longer in the config graph.
     */
    public void deleteSchedulesNotIn(Set<String> notificationKeys) {
        Set<String> expectedBases = notificationKeys.stream()
            .map(NotificationJobScheduler::recurringJobIdBase)
            .collect(Collectors.toSet());

        storageProvider.getRecurringJobs().stream()
            .map(RecurringJob::getId)
            .filter(id -> id.startsWith(RECURRING_JOB_ID_PREFIX))
            .filter(id -> !expectedBases.contains(baseOf(id)))
            .forEach(id -> {
                logger.info("Deleting recurring job {}, its notification no longer exists", id);
                jobRequestScheduler.deleteRecurringJob(id);
            });
    }

    private Optional<String> existingRecurringJobId(String notificationKey) {
        String base = recurringJobIdBase(notificationKey);
        return storageProvider.getRecurringJobs().stream()
            .map(RecurringJob::getId)
            .filter(id -> baseOf(id).equals(base))
            .findFirst();
    }

    /**
     * A new id every time a notification's recurring job is created, never the id of one that was deleted.
     * JobRunr OSS remembers the last run of a recurring job id in the running server: deleting a recurring job and
     * creating it again under the same id makes JobRunr schedule every run that was missed in between, all at once
     * (we saw 6 Slack notifications in one poll). The recurring jobs docs say: create it with a different id.
     */
    static String newRecurringJobId(String notificationKey, Instant now) {
        return recurringJobIdBase(notificationKey) + "(" + Long.toString(now.toEpochMilli(), 36) + ")";
    }

    /**
     * Quartz accepted any JobKey name. JobRunr recurring job ids only allow letters, digits and -_(),. and must be
     * shorter than 128 characters. A notification key looks like "acme/billing-service-team-standup".
     * Parentheses are reserved for the generation suffix added by newRecurringJobId.
     */
    static String recurringJobIdBase(String notificationKey) {
        String readable = notificationKey.replace('/', '.');
        String sanitized = readable.replaceAll("[^A-Za-z0-9\\-_,.]", "_");
        String id = RECURRING_JOB_ID_PREFIX + sanitized;

        if (!sanitized.equals(readable) || id.length() > MAX_RECURRING_JOB_ID_BASE_LENGTH) {
            String hash = sha256(notificationKey).substring(0, 10);
            int maxPrefixLength = MAX_RECURRING_JOB_ID_BASE_LENGTH - hash.length() - 1;
            id = (id.length() > maxPrefixLength ? id.substring(0, maxPrefixLength) : id) + "-" + hash;
        }

        return id;
    }

    static String baseOf(String recurringJobId) {
        int generation = recurringJobId.lastIndexOf('(');
        return generation < 0 ? recurringJobId : recurringJobId.substring(0, generation);
    }

    /**
     * Quartz used java.util.TimeZone, which accepts ids like "EST" (used in the docs of this app) that ZoneId.of rejects.
     */
    static ZoneId toZoneId(String timeZone) {
        return TimeZone.getTimeZone(timeZone).toZoneId();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
