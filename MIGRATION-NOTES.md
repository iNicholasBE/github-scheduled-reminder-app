# Quartz to JobRunr migration notes

Branch `migrate-to-jobrunr`, based on `baseline-runnable` (upstream `main` at b3dde6c plus one fix, see below).
JobRunr 9.0.3 OSS with `jobrunr-spring-boot-3-starter` (the app is on Spring Boot 3.5; on Spring Boot 4 use
`jobrunr-spring-boot-4-starter`).

## Before the migration: the app did not run at HEAD

Dependabot bumped `jjwt` to 0.12.6. `github-api` 1.316 calls `JwtBuilder.setIssuedAt(Date)`, which jjwt 0.12 removed,
so the installation scan failed with `NoSuchMethodError` on every run. The test suite did not catch it because
`GithubScheduledReminderAppApplicationTests.contextLoads` has no `@Test`. Branch `baseline-runnable` pins jjwt
back to 0.11.5. That is the baseline the migration was verified against.

## Inventory

| Quartz job | Trigger | Data | Concurrency | Now |
|---|---|---|---|---|
| `GitHubInstallationScan` | `SimpleTriggerFactoryBean`, interval from `JOBS_GITHUB_INSTALLATION_SCAN_INTERVAL` (ms), repeat forever, first run at startup | none | `@DisallowConcurrentExecution` | recurring job `github-installation-scan` with `Duration.ofMillis(interval)`, registered in `JobSchedulingConfig`, plus one enqueued run at startup |
| `GitHubInstallationRepositoryScan` | durable `JobDetail` per installation, `addJob` + `triggerJob` on demand (from the installation scan and the GitHub webhook) | `installationId` in the JobDataMap | `@DisallowConcurrentExecution` per installation | `jobScheduler.enqueue(scan -> scan.scan(installationId))`, lock per installation inside the job |
| `ScheduledNotification` | one `CronTrigger` per notification, cron and time zone from the user's `.demo-bot.yaml`, `JobKey` = notification key | the JobKey name | none | `jobRequestScheduler.scheduleRecurrently(id, cron, zoneId, new ScheduledNotificationRequest(key))`, handled by `ScheduledNotification implements JobRequestHandler` |
| `ScheduledStateDump` | `SimpleTrigger` every hour, first run at startup | none | `@DisallowConcurrentExecution` | `@Recurring(id = "state-dump", interval = "PT1H")` |
| `SlackChannelMessageDelete` | `SimpleTrigger` every minute, first run at startup | in-memory queues | `@DisallowConcurrentExecution` | `@Recurring(id = "slack-channel-message-delete", interval = "PT1M")` |

Store: Spring Boot's default `RAMJobStore` (no `spring.quartz.*` properties). No listeners, calendars, misfire
instructions, `@PersistJobDataAfterExecution` or JDBC tables.

## Lambda or JobRequest

- Lambdas for the scans, the state dump and the Slack cleanup: each is one public method on a Spring bean with at
  most one simple argument.
- `JobRequest` for notifications: they are created from user data, one recurring job per notification, the payload
  is a small command object (`ScheduledNotificationRequest(notificationKey)`), and the test can assert on it.

## Behaviour changes

1. **Cron expressions are translated** (`QuartzCron`). The README tells users to write Quartz cron. JobRunr rejects
   `?` and the year field, and numbers day-of-week 0-6 instead of 1-7. Without translation `0 0 9 ? * 2-6`
   fails to parse, and `0 0 9 * * 2-6` silently runs Tuesday to Saturday. `W` and `#` are rejected with a logged
   error (JobRunr Pro supports them).
2. **Time zones** keep Quartz semantics through `TimeZone.getTimeZone(id).toZoneId()`. The docs example uses `EST`,
   which `ZoneId.of` rejects.
3. **Recurring job ids**: JobRunr only allows `[A-Za-z0-9-_(),.]`, shorter than 128 characters. Notification keys
   contain `/`, so the id is `notification-` + key with `/` replaced by `.` (plus a hash when other characters had to
   be replaced or the key is too long), plus a generation suffix: `notification-acme.billing-service-team-standup(mvts1fse)`.
   The suffix exists because JobRunr OSS remembers the last run of a recurring job id in the running server: when the
   first version deleted a notification's recurring job and the owner added it back, the same id came back and JobRunr
   scheduled the six runs missed in between in one poll (six Slack messages at 17:43:06). The recurring jobs docs
   say to recreate under a different id; updates look the existing id up by its base.
4. **First run of interval jobs**: Quartz fired them at startup, JobRunr one interval later. The installation scan is
   enqueued once at startup to keep the old behaviour (installations live in memory). The state dump and Slack
   cleanup start one interval after startup, which is harmless.
5. **Recurring jobs start a few seconds late**: JobRunr polls every 15 s. Observed: 0 to 15 s later than the cron
   moment. Schedules shorter than the poll interval are not supported.
6. **Retries**: the Quartz jobs caught and printed every exception. Now the scans let `IOException` propagate, so
   JobRunr retries them (default 10 with exponential back-off) and the dashboard shows the stack trace.
   Notifications get `@Job(retries = 2)`: a reminder hours late is worse than none. Slack delivery errors are still
   caught inside `ChannelNotification` (business code, unchanged), so they do not trigger a retry; making that throw
   is a follow-up.
7. **Durable step** in the installation scan: enqueueing each repository scan is a `runStepOnce` step, so a retry
   after a partial failure does not enqueue the same scans again.
8. **Stale notification cleanup now works.** The Quartz version looked for triggers in group `notifications`, but
   all triggers were created in `DEFAULT`, so it never deleted anything and removed notifications kept firing
   (and failing with a NullPointerException). The repository scan now deletes `notification-*` recurring jobs
   whose key is no longer in the config graph. A run that JobRunr had already scheduled ahead can still start once
   after the delete (JobRunr Pro cleans these up); the handler skips notifications that are not in the config graph.
9. **Invalid schedules no longer abort the scan**: Quartz threw a `RuntimeException` from `cronSchedule()` that
   escaped the per-notification `catch (SchedulerException)`. The translator errors are wrapped in the domain
   `SchedulingException`, so one bad config is logged and the others still get scheduled.
10. **Restarts**: the next run of each recurring job is stored ahead of time, so a run whose moment passed while the
    app was down runs once right after startup. No catch-up beyond that.
11. **Persistence**: jobs are stored in a database (`DATABASE_URL`, default H2 file `./data/reminder-jobs`, verified
    with Postgres 17). Recurring jobs survive restarts; the config graph is still restored from the state dump.

## No 1:1 equivalent

- `@DisallowConcurrentExecution` on the per-installation repository scan: JobRunr OSS has no per-key mutex for
  one-off jobs (Pro has mutexes). The app keeps its state in memory, so it is single-node anyway; a
  `ReentrantLock` per installation inside the job gives the same guarantee. **Do not run two instances against
  the same database**: jobs would run on a node that does not hold the in-memory config graph.
- Misfires: Quartz `RAMJobStore` lost everything on restart, JobRunr OSS does not catch up runs missed during
  downtime either. No change in practice.
- JobRunr OSS supports up to 100 recurring jobs. This app creates one per notification config across all
  installations, plus three fixed ones. Large installations need JobRunr Pro or a different design.

## Removed

- `spring-boot-starter-quartz` (and with it `org.quartz-scheduler:quartz` 2.5.0). The enforcer plugin now bans
  `org.quartz-scheduler` so it cannot come back transitively.
- `SchedulerConfig`, the `JobDetailFactoryBean` / `SimpleTriggerFactoryBean` beans in `Config`.
- Unused Quartz-shaped abstractions from an unfinished refactor: `JobSchedulingService`,
  `QuartzJobSchedulingService`, `JobClassProvider` and its two implementations (no callers).
- `org.quartz.SchedulerException` in domain signatures, replaced by the existing domain `SchedulingException`.

## Tests

- `NotificationJobSchedulerTest` was rewritten: it captured Quartz `JobDetail` and `CronTrigger` objects. It now
  mocks `JobRequestScheduler` and `StorageProvider` and checks id, translated cron, zone and request, plus cleanup and
  id sanitising and generation ids (2 tests became 10).
- New `QuartzCronTest` (15 tests) runs every translation through JobRunr's own `CronExpression` parser.
- `ConfigGraphUpdaterTest`, `RepositoryInstallationEventListenerTest`: exception type only.
- Total: 74 tests before, 97 after, all green.
