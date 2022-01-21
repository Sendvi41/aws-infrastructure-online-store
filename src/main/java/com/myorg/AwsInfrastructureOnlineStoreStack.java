package com.myorg;

import software.amazon.awscdk.CfnParameter;
import software.amazon.awscdk.Duration;
import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.SecretValue;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.ec2.InstanceClass;
import software.amazon.awscdk.services.ec2.InstanceSize;
import software.amazon.awscdk.services.ec2.InstanceType;
import software.amazon.awscdk.services.ec2.Vpc;
import software.amazon.awscdk.services.ecr.Repository;
import software.amazon.awscdk.services.ecs.AddCapacityOptions;
import software.amazon.awscdk.services.ecs.Cluster;
import software.amazon.awscdk.services.ecs.ContainerImage;
import software.amazon.awscdk.services.ecs.patterns.ApplicationLoadBalancedEc2Service;
import software.amazon.awscdk.services.ecs.patterns.ApplicationLoadBalancedTaskImageOptions;
import software.amazon.awscdk.services.iam.Role;
import software.amazon.awscdk.services.iam.ServicePrincipal;
import software.amazon.awscdk.services.rds.Credentials;
import software.amazon.awscdk.services.rds.DatabaseInstance;
import software.amazon.awscdk.services.rds.DatabaseInstanceEngine;
import software.amazon.awscdk.services.rds.PostgresEngineVersion;
import software.amazon.awscdk.services.rds.PostgresInstanceEngineProps;
import software.amazon.awscdk.services.s3.Bucket;
import software.amazon.awscdk.services.s3.BucketProps;
import software.amazon.awscdk.services.ssm.StringParameter;
import software.constructs.Construct;

import java.util.Map;

public class AwsInfrastructureOnlineStoreStack extends Stack {
    public AwsInfrastructureOnlineStoreStack(final Construct scope, final String id) {
        this(scope, id, null);
    }

    public AwsInfrastructureOnlineStoreStack(final Construct scope, final String id, final StackProps props) {
        super(scope, id, props);

        CfnParameter adminPostgresDbName = CfnParameter.Builder.create(this, "adminPostgresDbName")
                .description("Name of DB of adminService")
                .defaultValue("adminService")
                .build();

        CfnParameter adminPostgresUserName = CfnParameter.Builder.create(this, "adminPostgresUserName")
                .description("User of DB of adminService")
                .defaultValue("postgres")
                .build();

        CfnParameter adminRepositoryName = CfnParameter.Builder.create(this, "adminRepositoryName")
                .description("Repository of adminService")
                .defaultValue("admin-service")
                .build();

        CfnParameter adminImageTag = CfnParameter.Builder.create(this, "adminImageTag")
                .description("The tag of image")
                .defaultValue("latest")
                .build();

        String dbAdminPass = StringParameter.valueForStringParameter(
                this, "admin-pass");


        String rabbitPass = StringParameter.valueForStringParameter(
                this, "rabbitmq-pass");

        Vpc vpc = Vpc.Builder.create(this, "vpcStack")
                .maxAzs(2)
                .build();

        Bucket bucket = new Bucket(this, "imageProductsBucket", BucketProps.builder()
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
                .credentials(Credentials.fromPassword(adminPostgresUserName.getValueAsString(), new SecretValue(dbAdminPass)))
                .instanceType(InstanceType.of(InstanceClass.BURSTABLE3, InstanceSize.MICRO))
                .databaseName(adminPostgresDbName.getValueAsString())
                .backupRetention(Duration.days(0))
                .removalPolicy(RemovalPolicy.DESTROY)
                .build();

        postgres.grantConnect(taskRole);

        Cluster adminCluster = Cluster.Builder.create(this, "ECS-ADMIN-ONLINE-STORE")
                .vpc(vpc).build();
        adminCluster.addCapacity("AdminAutoScalingGroup", AddCapacityOptions.builder()
                .instanceType(InstanceType.of(InstanceClass.BURSTABLE3, InstanceSize.MICRO))
                .desiredCapacity(1)
                .build());

        ApplicationLoadBalancedEc2Service.Builder.create(this, "adminService")
                .cluster(adminCluster)
                .publicLoadBalancer(true)
                .desiredCount(1)
                .cpu(512)
                .memoryLimitMiB(1024)
                .taskImageOptions(
                        ApplicationLoadBalancedTaskImageOptions.builder()
                                .containerName("admin-service")
                                .taskRole(taskRole)
                                .environment(Map.of(
                                        "ADMIN_USER", adminPostgresUserName.getValueAsString(),
                                        "ADMIN_DB_URL", "jdbc:postgresql://" + postgres.getDbInstanceEndpointAddress()
                                                + ":" + postgres.getDbInstanceEndpointPort() + "/" + adminPostgresDbName.getValueAsString(),
                                        "S3_AWS_REGION", getRegion(),
                                        "S3_AWS_NAME_BUCKET", bucket.getBucketName(),
                                        "S3_AWS_ENDPOINT", bucket.getBucketWebsiteUrl(),
                                        "RABBITMQ_HOST", "test",
                                        "RABBITMQ_USER", "test",
                                        "ADMIN_PASS", dbAdminPass,
                                        "RABBITMQ_PASS", rabbitPass
                                ))
                                .image(ContainerImage.fromEcrRepository(
                                        Repository.fromRepositoryName(this, "admin-service-repository", adminRepositoryName.getValueAsString()),
                                        adminImageTag.getValueAsString()

                                ))
                                .containerPort(8080)
                                .build()
                )
                .build();
    }
}
