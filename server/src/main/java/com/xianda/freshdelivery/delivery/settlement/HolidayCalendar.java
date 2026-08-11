package com.xianda.freshdelivery.delivery.settlement;

import java.time.LocalDate;
import java.time.MonthDay;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class HolidayCalendar {
    private static final Set<MonthDay> FIXED_HOLIDAYS = Set.of(
            MonthDay.of(1, 1),
            MonthDay.of(5, 1),
            MonthDay.of(5, 2),
            MonthDay.of(5, 3),
            MonthDay.of(10, 1),
            MonthDay.of(10, 2),
            MonthDay.of(10, 3),
            MonthDay.of(10, 4),
            MonthDay.of(10, 5),
            MonthDay.of(10, 6),
            MonthDay.of(10, 7)
    );

    private static final Set<LocalDate> LUNAR_HOLIDAYS = Set.of(
            LocalDate.of(2026, 2, 16),
            LocalDate.of(2026, 2, 17),
            LocalDate.of(2026, 2, 18),
            LocalDate.of(2026, 2, 19),
            LocalDate.of(2026, 2, 20),
            LocalDate.of(2026, 4, 5),
            LocalDate.of(2026, 6, 19),
            LocalDate.of(2026, 9, 25),
            LocalDate.of(2027, 2, 6),
            LocalDate.of(2027, 2, 7),
            LocalDate.of(2027, 2, 8),
            LocalDate.of(2027, 2, 9),
            LocalDate.of(2027, 2, 10),
            LocalDate.of(2027, 4, 5),
            LocalDate.of(2027, 6, 9),
            LocalDate.of(2027, 9, 15)
    );

    public boolean isStatutoryHoliday(LocalDate date) {
        if (date == null) {
            return false;
        }
        return FIXED_HOLIDAYS.contains(MonthDay.of(date.getMonthValue(), date.getDayOfMonth()))
                || LUNAR_HOLIDAYS.contains(date);
    }

    public String displayName(LocalDate date) {
        if (date == null || !isStatutoryHoliday(date)) {
            return null;
        }
        return date.toString();
    }
}
