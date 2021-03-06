package org.openstack4j.openstack.networking.internal.ext;

import java.util.List;

import org.openstack4j.api.networking.ext.NetQosPolicyService;
import org.openstack4j.core.transport.ExecutionOptions;
import org.openstack4j.core.transport.propagation.PropagateOnStatus;
import org.openstack4j.model.common.ActionResponse;
import org.openstack4j.model.network.ext.NetQosPolicy;
import org.openstack4j.model.network.ext.NetQosPolicyUpdate;
import org.openstack4j.openstack.networking.domain.ext.NeutronNetQosPolicy;
import org.openstack4j.openstack.networking.domain.ext.NeutronNetQosPolicy.NeutronNetQosPolicies;
import org.openstack4j.openstack.networking.internal.BaseNetworkingServices;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Networking (Neutron) Qos Policy Extension API
 *
 * @author bboyHan
 */
public class NetQosPolicyServiceImpl extends BaseNetworkingServices implements NetQosPolicyService {

    /**
     * {@inheritDoc}
     */
    @Override
    public List<? extends NetQosPolicy> list() {
        return get(NeutronNetQosPolicies.class, uri("/qos/policies")).execute().getList();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public NetQosPolicy get(String policyId) {
        checkNotNull(policyId, "qos policyId must not be null");
        return get(NeutronNetQosPolicy.class, uri("/qos/policies/%s", policyId)).execute();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public NetQosPolicy update(String policyId, NetQosPolicyUpdate netQosPolicy) {
        checkNotNull(policyId, "netQosPolicy id must not be null");
        checkNotNull(netQosPolicy, "netQosPolicy must not be null");
        return put(NeutronNetQosPolicy.class, uri("/qos/policies/%s", policyId)).entity(netQosPolicy).execute(ExecutionOptions.create(PropagateOnStatus.on(404)));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public NetQosPolicy create(NetQosPolicy netQosPolicy) {
        checkNotNull(netQosPolicy, "netQosPolicy must not be null");
        return post(NeutronNetQosPolicy.class, uri("/qos/policies")).entity(netQosPolicy).execute();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ActionResponse delete(String policyId) {
        checkNotNull(policyId);
        return deleteWithResponse(uri("/qos/policies/%s", policyId)).execute();
    }

}
