package com.bbaga.githubscheduledreminderapp.application.jobs;

import com.bbaga.githubscheduledreminderapp.domain.jobs.scheduling.InstallationScanJobScheduler;
import com.bbaga.githubscheduledreminderapp.infrastructure.github.repositories.GitHubInstallationRepository;
import org.jobrunr.jobs.annotations.Job;
import org.jobrunr.jobs.context.JobContext;
import org.kohsuke.github.GHAppInstallation;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.PagedIterable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

/**
 * Recurring job (registered in JobSchedulingConfig) that lists the GitHub App installations and enqueues a
 * repository scan for each of them.
 */
@Component
public class GitHubInstallationScan {

    private final GitHub gitHub;
    private final GitHubInstallationRepository installationRepository;
    private final InstallationScanJobScheduler jobScheduler;
    private final Logger logger = LoggerFactory.getLogger(GitHubInstallationScan.class);

    @Autowired
    public GitHubInstallationScan(
            GitHub gitHub,
            GitHubInstallationRepository installationRepository,
            InstallationScanJobScheduler jobScheduler
    ) {
        this.gitHub = gitHub;
        this.installationRepository = installationRepository;
        this.jobScheduler = jobScheduler;
    }

    @Job(name = "Scan GitHub App installations")
    public void scan(JobContext jobContext) throws IOException {

        logger.info("Starting Installation Scan");
        // Quartz version: caught the IOException, printed it and returned "successfully".
        // Now it fails the job, so JobRunr retries it and the dashboard shows why.
        PagedIterable<GHAppInstallation> list = gitHub.getApp().listInstallations();

        Set<Long> installationIds = new HashSet<>(installationRepository.getIds());
        HashMap<Long, Boolean> checkList = new HashMap<>();
        for (Long installationId : installationIds) {
            checkList.put(installationId, false);
        }

        for (GHAppInstallation installation : list) {
            checkList.put(installation.getId(), true);
            installationRepository.put(installation);
        }

        for (Long installationId : checkList.keySet()) {
            if (!checkList.get(installationId)) {
                installationRepository.remove(installationId);
                continue;
            }

            // Durable step: if this job fails halfway and JobRunr retries it, the repository scans that were
            // already enqueued are not enqueued a second time. Block lambda on purpose: an expression lambda returning
            // the JobId would pick the ThrowingSupplier overload and JobRunr would try to store the JobId as step result.
            jobContext.runStepOnce("enqueue-repository-scan-" + installationId, () -> {
                jobScheduler.triggerJob(installationId);
            });
        }

        logger.info("Installation Scanning is done");
    }
}
