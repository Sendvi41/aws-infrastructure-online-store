package com.myorg;

import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.constructs.Construct;

public class AwsInfrastructureOnlineStoreStack extends Stack {
    public AwsInfrastructureOnlineStoreStack(final Construct scope, final String id) {
        this(scope, id, null);
    }

    public AwsInfrastructureOnlineStoreStack(final Construct scope, final String id, final StackProps props) {
        super(scope, id, props);
    }
}
