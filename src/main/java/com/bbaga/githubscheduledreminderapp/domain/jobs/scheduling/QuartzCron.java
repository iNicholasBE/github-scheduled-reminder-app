package com.bbaga.githubscheduledreminderapp.domain.jobs.scheduling;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Users write Quartz cron expressions in their {@code .demo-bot.yaml} (the documented format). JobRunr parses
 * Unix style cron (5 fields, or 6 with seconds), so every expression is translated here before it is scheduled.
 *
 * <ul>
 *   <li>{@code ?} is rejected by JobRunr: it becomes {@code *}.</li>
 *   <li>The optional 7th (year) field is dropped when it is {@code *} or {@code ?}; a concrete year is rejected.</li>
 *   <li>Day-of-week numbers are 1-7 (SUN-SAT) in Quartz and 0-6 (SUN-SAT) in JobRunr, so they shift by one.
 *       Names (MON-FRI) are left alone.</li>
 *   <li>{@code W} and {@code #} are not supported by the JobRunr OSS parser and are rejected.</li>
 * </ul>
 */
public final class QuartzCron {

    private static final Pattern DAY_OF_WEEK_NUMBER = Pattern.compile("(?<![/#\\d])([1-7])(?!\\d)");

    private QuartzCron() {
    }

    public static String toJobRunr(String quartzExpression) {
        String[] fields = quartzExpression.trim().split("\\s+");
        if (fields.length < 6 || fields.length > 7) {
            throw new IllegalArgumentException("Expected a Quartz cron expression with 6 or 7 fields: " + quartzExpression);
        }
        if (fields.length == 7 && !fields[6].equals("*") && !fields[6].equals("?")) {
            throw new IllegalArgumentException("The Quartz year field is not supported: " + quartzExpression);
        }

        String dayOfMonth = fields[3].equals("?") ? "*" : fields[3];
        String dayOfWeek = fields[5].equals("?") ? "*" : shiftDayOfWeekNumbers(fields[5]);

        if (dayOfMonth.toUpperCase().contains("W") || dayOfWeek.contains("#")) {
            throw new IllegalArgumentException("'W' and '#' are not supported: " + quartzExpression);
        }

        return String.join(" ", fields[0], fields[1], fields[2], dayOfMonth, fields[4], dayOfWeek);
    }

    private static String shiftDayOfWeekNumbers(String field) {
        Matcher matcher = DAY_OF_WEEK_NUMBER.matcher(field);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(result, String.valueOf(Integer.parseInt(matcher.group(1)) - 1));
        }
        matcher.appendTail(result);
        return result.toString();
    }
}
