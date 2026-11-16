package com.bbaga.githubscheduledreminderapp.domain.jobs.scheduling;

import org.jobrunr.scheduling.cron.CronExpression;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.*;

class QuartzCronTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "0 */2 * * * ?           | 0 */2 * * * *",
        "30 * * ? * MON-FRI      | 30 * * * * MON-FRI",
        "*/30 * * * * ?          | */30 * * * * *",
        "0 0 9 ? * 2-6           | 0 0 9 * * 1-5",
        "0 0 9 ? * 1,7           | 0 0 9 * * 0,6",
        "0 0 9 ? * 1/2           | 0 0 9 * * 0/2",
        "0 0 9 ? * 6L            | 0 0 9 * * 5L",
        "0 15 10 ? * * *         | 0 15 10 * * *",
        "0 1 2 3 4 ?             | 0 1 2 3 4 *",
    })
    void translatesQuartzToJobRunr(String quartz, String jobRunr) {
        String translated = QuartzCron.toJobRunr(quartz);

        assertEquals(jobRunr, translated);
        assertDoesNotThrow(() -> new CronExpression(translated), "JobRunr must accept " + translated);
    }

    @Test
    void weekdaysStayWeekdays() {
        // Quartz 2-6 is Monday to Friday. Left untouched, JobRunr would read it as Tuesday to Saturday.
        CronExpression cron = new CronExpression(QuartzCron.toJobRunr("0 0 9 ? * 2-6"));
        ZoneId zone = ZoneId.of("Europe/Brussels");
        Instant friday = ZonedDateTime.of(2026, 10, 30, 10, 0, 0, 0, zone).toInstant();

        Instant next = cron.next(friday, friday, zone);

        assertEquals(DayOfWeek.MONDAY, next.atZone(zone).getDayOfWeek());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0 0 9 15W * ?", "0 0 9 ? * 6#3", "0 0 9 * * ? 2027", "0 0 9 * *", "* * * * * * * *"})
    void rejectsWhatJobRunrOssCannotRun(String quartz) {
        assertThrows(IllegalArgumentException.class, () -> QuartzCron.toJobRunr(quartz));
    }
}
