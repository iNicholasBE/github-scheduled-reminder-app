package com.bbaga.githubscheduledreminderapp.domain.jobs.scheduling;

import com.bbaga.githubscheduledreminderapp.application.jobs.GitHubInstallationRepositoryScan;
import org.jobrunr.jobs.JobId;
import org.jobrunr.scheduling.JobScheduler;

public class InstallationScanJobScheduler {
    private final JobScheduler jobScheduler;

    public InstallationScanJobScheduler(JobScheduler jobScheduler) {
        this.jobScheduler = jobScheduler;
    }

    /**
     * Quartz needed a durable JobDetail per installation (addJob) before it could triggerJob it.
     * In JobRunr this is a single fire-and-forget job with the installation id as a typed argument.
     */
    public JobId triggerJob(long installationId) {
        return jobScheduler.<GitHubInstallationRepositoryScan>enqueue(scan -> scan.scan(installationId));
    }
}
