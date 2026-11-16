package com.bbaga.githubscheduledreminderapp.application.jobs;

import org.jobrunr.jobs.lambdas.JobRequest;

/**
 * The JobRunr payload of a scheduled notification: only the key of the notification in the config graph.
 * Replaces the Quartz JobKey name that ScheduledNotification used to read from its JobExecutionContext.
 */
public record ScheduledNotificationRequest(String notificationKey) implements JobRequest {

    @Override
    public Class<ScheduledNotification> getJobRequestHandler() {
        return ScheduledNotification.class;
    }

    @Override
    public String toString() {
        return notificationKey;
    }
}
