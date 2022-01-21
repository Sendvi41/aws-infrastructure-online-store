package com.myorg;

import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.ec2.Vpc;
import software.amazon.awscdk.services.iam.Role;
import software.amazon.awscdk.services.iam.ServicePrincipal;
import software.amazon.awscdk.services.s3.Bucket;
import software.amazon.awscdk.services.s3.BucketProps;
import software.constructs.Construct;

public class AwsInfrastructureOnlineStoreStack extends Stack {
    public AwsInfrastructureOnlineStoreStack(final Construct scope, final String id) {
        this(scope, id, null);
    }

    public AwsInfrastructureOnlineStoreStack(final Construct scope, final String id, final StackProps props) {
        super(scope, id, props);

        Vpc vpc = Vpc.Builder.create(this, "vpc-stack")
                .maxAzs(2)
                .build();

        Bucket bucket = new Bucket(this, "image-products", BucketProps.builder()
                .versioned(false)
                .publicReadAccess(true)
                .removalPolicy(RemovalPolicy.DESTROY)
                .build());

        Role taskRole = Role.Builder.create(this, "TaskRole")
                .assumedBy(ServicePrincipal.Builder.create("ecs-tasks.amazonaws.com").build())
                .build();

        bucket.grantReadWrite(taskRole);

    }
}
