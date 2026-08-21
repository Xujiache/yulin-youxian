package com.xianda.freshdelivery.delivery.task;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class TaskIdempotencyGuard {
    private static final int CACHE_CAPACITY = 4096;

    private final DeliveryTaskEventDao eventDao;
    private final Map<Scope, Object> replayCache;

    public TaskIdempotencyGuard(DeliveryTaskEventDao eventDao) {
        this.eventDao = eventDao;
        this.replayCache = Collections.synchronizedMap(new LinkedHashMap<>(256, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Scope, Object> eldest) {
                return size() > CACHE_CAPACITY;
            }
        });
    }

    public boolean isReplay(Scope scope) {
        if (scope == null || !scope.valid()) {
            return false;
        }
        return replayCache.containsKey(scope) || eventDao.findByIdempotencyScope(
                scope.clientEventId(), scope.taskId(), scope.riderId(), scope.action()
        ).isPresent();
    }

    public Optional<Object> peek(Scope scope) {
        if (scope == null || !scope.valid()) {
            return Optional.empty();
        }
        return Optional.ofNullable(replayCache.get(scope));
    }

    public void remember(Scope scope, Object result) {
        if (scope == null || !scope.valid() || result == null) {
            return;
        }
        replayCache.put(scope, result);
    }

    public void evict(String clientEventId) {
        if (clientEventId == null) {
            return;
        }
        replayCache.keySet().removeIf(scope -> clientEventId.equals(scope.clientEventId()));
    }

    public void evictTask(long taskId) {
        replayCache.keySet().removeIf(scope -> scope.taskId() == taskId);
    }

    public record Scope(String clientEventId, long taskId, long riderId, String action) {
        boolean valid() {
            return clientEventId != null && !clientEventId.isBlank()
                    && action != null && !action.isBlank();
        }
    }
}
