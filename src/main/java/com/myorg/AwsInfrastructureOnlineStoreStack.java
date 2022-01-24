package com.myorg;

import software.amazon.awscdk.CfnParameter;
import software.amazon.awscdk.Duration;
import software.amazon.awscdk.Fn;
import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.SecretValue;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.amazonmq.CfnBroker;
import software.amazon.awscdk.services.autoscaling.AutoScalingGroup;
import software.amazon.awscdk.services.autoscaling.AutoScalingGroupProps;
import software.amazon.awscdk.services.ec2.AmazonLinuxImage;
import software.amazon.awscdk.services.ec2.InstanceClass;
import software.amazon.awscdk.services.ec2.InstanceSize;
import software.amazon.awscdk.services.ec2.InstanceType;
import software.amazon.awscdk.services.ec2.Port;
import software.amazon.awscdk.services.ec2.SubnetSelection;
import software.amazon.awscdk.services.ec2.SubnetType;
import software.amazon.awscdk.services.ec2.Vpc;
import software.amazon.awscdk.services.ecr.Repository;
import software.amazon.awscdk.services.ecs.AddCapacityOptions;
import software.amazon.awscdk.services.ecs.AsgCapacityProvider;
import software.amazon.awscdk.services.ecs.Cluster;
import software.amazon.awscdk.services.ecs.ContainerImage;
import software.amazon.awscdk.services.ecs.MachineImageType;
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

import java.util.ArrayList;
import java.util.List;
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

        CfnParameter rabbitmqNameUser = CfnParameter.Builder.create(this, "rabbitmq-user")
                .description("The tag of image")
                .defaultValue("rabbitmq")
                .build();


        String dbAdminPass = StringParameter.valueForStringParameter(
                this, "admin-pass");


        String rabbitPass = StringParameter.valueForStringParameter(
                this, "rabbitmq-pass");

        Vpc vpc = Vpc.Builder.create(this, "vpcStack")
                .maxAzs(2)
                .subnetConfiguration(Vpc.DEFAULT_SUBNETS_NO_NAT)
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
                .vpcSubnets(SubnetSelection.builder().subnetType(SubnetType.PRIVATE_ISOLATED).build())
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

        List<User> rabbitUserList = new ArrayList<>();
        rabbitUserList.add(new User(
                rabbitmqNameUser.getValueAsString(),
                rabbitPass
        ));

        CfnBroker rabbitMq = CfnBroker.Builder.create(this, "rabbitmq")
                .brokerName("rabbitmq")
                .deploymentMode("SINGLE_INSTANCE")
                .engineType("RABBITMQ")
                .engineVersion("3.8.26")
                .hostInstanceType("mq.t3.micro")
                .authenticationStrategy("SIMPLE")
                .publiclyAccessible(false)
                .autoMinorVersionUpgrade(true)
                .users(rabbitUserList)
                .build();

        Cluster adminCluster = Cluster.Builder.create(this, "ecs-admin-online-store")
                .vpc(vpc)
                .build();
        AddCapacityOptions addCapacityOptions = AddCapacityOptions.builder()
                .instanceType(InstanceType.of(InstanceClass.BURSTABLE3, InstanceSize.MICRO))
                .vpcSubnets(SubnetSelection.builder()
                        .subnets(vpc.getPublicSubnets())
                        .build())
                .desiredCapacity(1)
                .build();
        adminCluster.addCapacity("cluster-capacity", addCapacityOptions);

        ApplicationLoadBalancedEc2Service adminService = ApplicationLoadBalancedEc2Service.Builder.create(this, "adminService")
                .cluster(adminCluster)
                .publicLoadBalancer(true)
                .desiredCount(1)
                .cpu(512)
                .memoryLimitMiB(512)
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
                                        "RABBITMQ_HOST", Fn.select(0, rabbitMq.getAttrAmqpEndpoints()),
                                        "RABBITMQ_USER", rabbitmqNameUser.getValueAsString(),
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


    static class User {

        String username;

        String password;

        public User() {
        }

        public User(String username, String password) {
            this.username = username;
            this.password = password;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }
    }
}
