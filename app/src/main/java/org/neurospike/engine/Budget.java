package org.neurospike.engine;

import org.neurospike.domain.DomainExceptions;

public final class Budget {
    private final int maximumRecords;
    private int observed;

    public Budget(int maximumRecords) {
        this.maximumRecords = maximumRecords;
    }

    public void use() throws DomainExceptions.BudgetExhaustedException {
        observed++;
        if (observed > maximumRecords) {
            throw new DomainExceptions.BudgetExhaustedException(maximumRecords, observed);
        }
    }

    public int observed() {
        return observed;
    }
}
