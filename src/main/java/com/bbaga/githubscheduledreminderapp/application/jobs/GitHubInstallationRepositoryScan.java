package com.bbaga.githubscheduledreminderapp.application.jobs;

import com.bbaga.githubscheduledreminderapp.domain.configuration.ConfigGraphNode;
import com.bbaga.githubscheduledreminderapp.domain.configuration.configGraphUpdater.ConfigGraphUpdater;
import com.bbaga.githubscheduledreminderapp.domain.configuration.NotificationInterface;
import com.bbaga.githubscheduledreminderapp.domain.jobs.scheduling.NotificationJobScheduler;
import com.bbaga.githubscheduledreminderapp.domain.jobs.scheduling.exceptions.SchedulingException;
import com.bbaga.githubscheduledreminderapp.infrastructure.configuration.InRepoConfig;
import com.bbaga.githubscheduledreminderapp.infrastructure.configuration.InRepoConfigParser;
import com.bbaga.githubscheduledreminderapp.infrastructure.github.GitHubAppInstallation;
import com.bbaga.githubscheduledreminderapp.infrastructure.github.GitHubBuilderFactory;
import com.bbaga.githubscheduledreminderapp.infrastructure.github.repositories.GitHubInstallationRepository;
import org.jobrunr.jobs.annotations.Job;
import org.kohsuke.github.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

@Component
public class GitHubInstallationRepositoryScan {

    private final ConfigGraphUpdater configGraphUpdater;
    private final GitHubBuilderFactory gitHubBuilderFactory;
    private final GitHubInstallationRepository installationRepository;
    private final NotificationJobScheduler notificationJobScheduler;

    private final ConcurrentHashMap<String, ConfigGraphNode> configGraph;

    // Quartz: @DisallowConcurrentExecution on a durable JobDetail per installation. JobRunr OSS has no per-key mutex
    // for fire-and-forget jobs (JobRunr Pro has mutexes). The config graph lives in this JVM anyway, so a local lock
    // per installation gives the same guarantee: a second scan of the same installation waits for the first one.
    private final ConcurrentHashMap<Long, ReentrantLock> installationLocks = new ConcurrentHashMap<>();

    private final Logger logger = LoggerFactory.getLogger(GitHubInstallationRepositoryScan.class);
    private final InRepoConfigParser inRepoConfigParser;

    @Autowired
    GitHubInstallationRepositoryScan(
        GitHubBuilderFactory gitHubBuilderFactory,
        GitHubInstallationRepository installationRepository,
        @Qualifier("ConfigGraph") ConcurrentHashMap<String, ConfigGraphNode> configGraph,
        ConfigGraphUpdater configGraphUpdater,
        InRepoConfigParser inRepoConfigParser,
        NotificationJobScheduler notificationJobScheduler
    ) {
        this.gitHubBuilderFactory = gitHubBuilderFactory;
        this.installationRepository = installationRepository;
        this.configGraph = configGraph;
        this.configGraphUpdater = configGraphUpdater;
        this.inRepoConfigParser = inRepoConfigParser;
        this.notificationJobScheduler = notificationJobScheduler;
    }

    @Job(name = "Scan repositories of installation %0")
    public void scan(long installationId) throws IOException, SchedulingException {
        ReentrantLock lock = installationLocks.computeIfAbsent(installationId, id -> new ReentrantLock());
        lock.lock();
        try {
            scanRepositories(installationId);
        } finally {
            lock.unlock();
        }
    }

    private void scanRepositories(long installationId) throws IOException, SchedulingException {
        Instant currentRunStamp = Instant.now();

        logger.info("Starting Repository scanning for Installation {}", installationId);

        GitHubAppInstallation installation = installationRepository.get(installationId);

        GHAppInstallationToken token = installation.unwrap().createToken().create();
        GitHub githubAuthAsInst = gitHubBuilderFactory.create()
                .withAppInstallationToken(token.getToken())
                .build();

        for (GHRepository repo : GitHubClientUtil.listRepositories(githubAuthAsInst)) {
            try {
                InRepoConfig inRepoConfig = inRepoConfigParser.getFrom(repo);

                if (repo.isArchived() || !inRepoConfig.getEnabled()) {
                    continue;
                }

                for (NotificationInterface notification : inRepoConfig.getNotifications()) {
                    configGraphUpdater.updateEntry(notification, installationId, repo.getFullName(), currentRunStamp);
                }
            } catch (GHFileNotFoundException e) {
                logger.debug("No config file in {}", repo.getFullName());
            }
        }

        // Remove entries if they didn't appear in this round
        configGraphUpdater.clearOutdated(installationId, currentRunStamp);

        // Unschedule notifications that don't exist anymore. The Quartz version looked for triggers in the group
        // "notifications", but the triggers were created in the DEFAULT group, so this cleanup never deleted anything.
        notificationJobScheduler.deleteSchedulesNotIn(configGraph.keySet());

        logger.info("Repository scanning for Installation {} is complete", installationId);
    }
}
