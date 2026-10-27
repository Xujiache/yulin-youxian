package com.xianda.freshdelivery.delivery.dispatch;

import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class DispatchUnitOfWork {
    private final TransactionTemplate transactionTemplate;
    private final ReentrantLock localLock = new ReentrantLock();

    @Autowired
    public DispatchUnitOfWork(ObjectProvider<PlatformTransactionManager> transactionManagerProvider) {
        PlatformTransactionManager manager = transactionManagerProvider.getIfAvailable();
        if (manager == null) {
            this.transactionTemplate = null;
            return;
        }
        TransactionTemplate template = new TransactionTemplate(manager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.transactionTemplate = template;
    }

    DispatchUnitOfWork() {
        this.transactionTemplate = null;
    }

    public <T> T commit(Supplier<T> work) {
        localLock.lock();
        try {
            if (transactionTemplate == null) {
                return work.get();
            }
            return transactionTemplate.execute(status -> work.get());
        } finally {
            localLock.unlock();
        }
    }
}
