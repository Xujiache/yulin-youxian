package com.xianda.freshdelivery.delivery.task;

import java.time.LocalDate;
import org.springframework.stereotype.Component;

@Component
public class TaskNumberGenerator {
    private static final String TASK_PREFIX = "PS";
    private static final String WAVE_PREFIX = "BC";
    private static final int TASK_SEQ_WIDTH = 6;
    private static final int WAVE_SEQ_WIDTH = 4;

    private final DeliveryTaskDao taskDao;
    private final DeliveryWaveDao waveDao;

    public TaskNumberGenerator(DeliveryTaskDao taskDao, DeliveryWaveDao waveDao) {
        this.taskDao = taskDao;
        this.waveDao = waveDao;
    }

    public String nextTaskNo(LocalDate date) {
        String prefix = TASK_PREFIX + date.format(TaskTimes.DAY_KEY);
        return prefix + pad(nextSequence(taskDao.maxTaskNoWithPrefix(prefix), prefix, TASK_SEQ_WIDTH), TASK_SEQ_WIDTH);
    }

    public String nextWaveNo(LocalDate date) {
        String prefix = WAVE_PREFIX + date.format(TaskTimes.DAY_KEY);
        return prefix + pad(nextSequence(waveDao.maxWaveNoWithPrefix(prefix), prefix, WAVE_SEQ_WIDTH), WAVE_SEQ_WIDTH);
    }

    static int nextSequence(String maxNo, String prefix, int width) {
        if (maxNo == null || maxNo.length() != prefix.length() + width) {
            return 1;
        }
        try {
            return Integer.parseInt(maxNo.substring(prefix.length())) + 1;
        } catch (NumberFormatException exception) {
            return 1;
        }
    }

    static String pad(int sequence, int width) {
        String text = Integer.toString(sequence);
        if (text.length() >= width) {
            return text.substring(text.length() - width);
        }
        return "0".repeat(width - text.length()) + text;
    }
}
