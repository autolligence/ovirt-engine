package org.ovirt.engine.core.bll;

import org.ovirt.engine.core.common.queries.GetQuotaByStoragePoolIdQueryParameters;
import org.ovirt.engine.core.dal.dbbroker.DbFacade;

public class GetQuotaByStoragePoolIdQuery<P extends GetQuotaByStoragePoolIdQueryParameters> extends QueriesCommandBase<P> {

    public GetQuotaByStoragePoolIdQuery(P parameters) {
        super(parameters);
    }

    @Override
    protected void executeQueryCommand() {
        getQueryReturnValue().setReturnValue(DbFacade.getInstance()
                .getQuotaDao()
                .getQuotaByStoragePoolGuid(getParameters().getStoragePoolId()));
    }
}
