package com.xianda.freshdelivery.delivery.task;

import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class TaskUnitOfWork {
    private final TransactionTemplate transactionTemplate;

    @Autowired
    public TaskUnitOfWork(ObjectProvider<TransactionTemplate> transactionTemplateProvider) {
        this.transactionTemplate = transactionTemplateProvider.getIfAvailable();
    }

    private TaskUnitOfWork(TransactionTemplate transactionTemplate) {
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * Lightweight tests which build services without a Spring context can use the
     * same unit-of-work boundary while deliberately opting out of a real database
     * transaction.
     */
    public static TaskUnitOfWork direct() {
        return new TaskUnitOfWork((TransactionTemplate) null);
    }

    public static TaskUnitOfWork transactional(TransactionTemplate transactionTemplate) {
        return new TaskUnitOfWork(transactionTemplate);
    }

    public <T> T commit(Supplier<T> work) {
        if (transactionTemplate == null) {
            return work.get();
        }
        return transactionTemplate.execute(status -> work.get());
    }

    public void run(Runnable work) {
        commit(() -> {
            work.run();
            return Boolean.TRUE;
        });
    }
}
