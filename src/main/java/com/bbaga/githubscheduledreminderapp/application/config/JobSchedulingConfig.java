package com.bbaga.githubscheduledreminderapp.application.config;

import com.bbaga.githubscheduledreminderapp.application.jobs.GitHubInstallationScan;
import com.bbaga.githubscheduledreminderapp.domain.jobs.scheduling.InstallationScanJobScheduler;
import com.bbaga.githubscheduledreminderapp.domain.jobs.scheduling.NotificationJobScheduler;
import org.jobrunr.jobs.context.JobContext;
import org.jobrunr.scheduling.JobRequestScheduler;
import org.jobrunr.scheduling.JobScheduler;
import org.jobrunr.storage.StorageProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;

import java.time.Duration;

/**
 * Replaces the Quartz SchedulerConfig and the JobDetailFactoryBean / SimpleTriggerFactoryBean beans.
 * ScheduledStateDump and SlackChannelMessageDelete register themselves with @Recurring.
 */
@Configuration
public class JobSchedulingConfig {
    static final String INSTALLATION_SCAN_JOB_ID = "github-installation-scan";

    private final Logger logger = LoggerFactory.getLogger(JobSchedulingConfig.class);

    // Kept in milliseconds so existing JOBS_GITHUB_INSTALLATION_SCAN_INTERVAL values keep working.
    @Value("${application.jobs.github.installationScan.interval}")
    private long installationScanIntervalMillis;

    @Bean
    public NotificationJobScheduler getNotificationJobScheduler(JobRequestScheduler jobRequestScheduler, StorageProvider storageProvider) {
        return new NotificationJobScheduler(jobRequestScheduler, storageProvider);
    }

    @Bean
    public InstallationScanJobScheduler getInstallationScanJobScheduler(JobScheduler jobScheduler) {
        return new InstallationScanJobScheduler(jobScheduler);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void scheduleInstallationScan(ApplicationReadyEvent event) {
        JobScheduler jobScheduler = event.getApplicationContext().getBean(JobScheduler.class);
        Duration interval = Duration.ofMillis(installationScanIntervalMillis);

        jobScheduler.<GitHubInstallationScan>scheduleRecurrently(INSTALLATION_SCAN_JOB_ID, interval, scan -> scan.scan(JobContext.Null));

        // A Quartz SimpleTrigger fires right away, a JobRunr interval job fires one interval after it was created.
        // The installations and the notification schedules live in memory, so scan once at startup as before.
        jobScheduler.<GitHubInstallationScan>enqueue(scan -> scan.scan(JobContext.Null));
        logger.info("Installation scan scheduled every {} and enqueued once for startup", interval);
    }
}
