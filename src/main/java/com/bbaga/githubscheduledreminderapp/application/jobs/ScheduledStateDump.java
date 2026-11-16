package com.bbaga.githubscheduledreminderapp.application.jobs;

import com.bbaga.githubscheduledreminderapp.infrastructure.configuration.persitance.ConfigPersistenceInterface;
import com.bbaga.githubscheduledreminderapp.domain.configuration.ConfigGraphNode;
import org.jobrunr.jobs.annotations.Job;
import org.jobrunr.jobs.annotations.Recurring;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

@Component
public class ScheduledStateDump {

    @Autowired
    @Qualifier("ConfigGraph")
    private ConcurrentHashMap<String, ConfigGraphNode> configGraph;

    @Autowired
    private ConfigPersistenceInterface persistentConfigStorage;

    private final Logger logger = LoggerFactory.getLogger(ScheduledStateDump.class);

    // Quartz: SimpleTrigger withIntervalInHours(1).repeatForever(), first run at startup.
    // JobRunr: first run one interval after startup; a recurring job never runs concurrently with itself in OSS.
    @Recurring(id = "state-dump", interval = "PT1H")
    @Job(name = "Dump notification state")
    public void dump() {
        logger.info("Starting state dump job");

        persistentConfigStorage.dump(this.configGraph);

        logger.info("Finished state dump job");
    }
}
