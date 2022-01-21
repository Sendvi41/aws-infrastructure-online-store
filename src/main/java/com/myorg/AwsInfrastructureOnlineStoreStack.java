package com.myorg;

import software.amazon.awscdk.CfnParameter;
import software.amazon.awscdk.Duration;
import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.ec2.InstanceClass;
import software.amazon.awscdk.services.ec2.InstanceSize;
import software.amazon.awscdk.services.ec2.InstanceType;
import software.amazon.awscdk.services.ec2.Vpc;
import software.amazon.awscdk.services.iam.Role;
import software.amazon.awscdk.services.iam.ServicePrincipal;
import software.amazon.awscdk.services.rds.Credentials;
import software.amazon.awscdk.services.rds.DatabaseInstance;
import software.amazon.awscdk.services.rds.DatabaseInstanceEngine;
import software.amazon.awscdk.services.rds.PostgresEngineVersion;
import software.amazon.awscdk.services.rds.PostgresInstanceEngineProps;
import software.amazon.awscdk.services.s3.Bucket;
import software.amazon.awscdk.services.s3.BucketProps;
import software.constructs.Construct;

public class AwsInfrastructureOnlineStoreStack extends Stack {
    public AwsInfrastructureOnlineStoreStack(final Construct scope, final String id) {
        this(scope, id, null);
    }

    public AwsInfrastructureOnlineStoreStack(final Construct scope, final String id, final StackProps props) {
        super(scope, id, props);

        CfnParameter adminPostgresDbName = CfnParameter.Builder.create(this,"adminPostgresDbName")
                .description("Name of DB of adminService")
                .defaultValue("adminService")
                .build();

        CfnParameter adminPostgresUserName = CfnParameter.Builder.create(this,"adminPostgresUserName")
                .description("User of DB of adminService")
                .defaultValue("postgres")
                .build();

        Credentials postgresUserAdminSecret = Credentials.fromGeneratedSecret(adminPostgresUserName.getValueAsString());

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

        DatabaseInstance postgres = DatabaseInstance.Builder.create(this, "postgres-admin")
                .vpc(vpc)
                .engine(
                        DatabaseInstanceEngine.postgres(
                                PostgresInstanceEngineProps.builder()
                                        .version(PostgresEngineVersion.VER_13_4)
                                        .build()
                        )
                )
                .credentials(Credentials.fromGeneratedSecret("postgres"))
                .instanceType(InstanceType.of(InstanceClass.BURSTABLE3, InstanceSize.MICRO))
                .databaseName(adminPostgresDbName.getValueAsString())
                .backupRetention(Duration.days(0))
                .removalPolicy(RemovalPolicy.DESTROY)
                .build();

    }
}
