package com.xianda.freshdelivery.lottery;

import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 抽奖需要自己控制事务边界：微信关单是同步 HTTPS 调用，必须落在事务和行锁之外；
 * 对账要一条流水一个短事务，不能整轮包在一个事务里反复抢 StorefrontService 的 monitor。
 * 用 @Transactional 做不到这两件事（同类自调用不走代理），所以显式用事务模板。
 */
@Component
public class LotteryUnitOfWork {
    private final TransactionTemplate transactionTemplate;
    private final TransactionTemplate readCommittedTemplate;

    @Autowired
    public LotteryUnitOfWork(ObjectProvider<TransactionTemplate> transactionTemplateProvider) {
        this(transactionTemplateProvider.getIfAvailable());
    }

    private LotteryUnitOfWork(TransactionTemplate transactionTemplate) {
        this.transactionTemplate = transactionTemplate;
        this.readCommittedTemplate = readCommitted(transactionTemplate);
    }

    /**
     * 裸构造的轻量测试沿用同一个工作单元边界，但不真的开事务。
     */
    public static LotteryUnitOfWork direct() {
        return new LotteryUnitOfWork((TransactionTemplate) null);
    }

    public static LotteryUnitOfWork of(TransactionTemplate transactionTemplate) {
        return new LotteryUnitOfWork(transactionTemplate);
    }

    public <T> T commit(Supplier<T> work) {
        if (transactionTemplate == null) {
            return work.get();
        }
        return transactionTemplate.execute(status -> work.get());
    }

    /**
     * 抽奖事务显式降到 READ COMMITTED：MySQL 默认的 REPEATABLE READ 会让加锁读之后的
     * 普通 SELECT 仍旧看到事务开始时的快照，当日预算和当日次数因此不是硬约束。
     */
    public <T> T commitReadCommitted(Supplier<T> work) {
        if (readCommittedTemplate == null) {
            return work.get();
        }
        return readCommittedTemplate.execute(status -> work.get());
    }

    public void run(Runnable work) {
        commit(() -> {
            work.run();
            return Boolean.TRUE;
        });
    }

    private static TransactionTemplate readCommitted(TransactionTemplate source) {
        if (source == null || source.getTransactionManager() == null) {
            return source;
        }
        TransactionTemplate template = new TransactionTemplate(source.getTransactionManager());
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return template;
    }
}
