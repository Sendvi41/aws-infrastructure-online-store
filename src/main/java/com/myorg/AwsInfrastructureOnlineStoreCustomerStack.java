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
import software.amazon.awscdk.services.ec2.Port;
import software.amazon.awscdk.services.ec2.SubnetSelection;
import software.amazon.awscdk.services.ec2.SubnetType;
import software.amazon.awscdk.services.ec2.Vpc;
import software.amazon.awscdk.services.ecr.Repository;
import software.amazon.awscdk.services.ecs.AddCapacityOptions;
import software.amazon.awscdk.services.ecs.Cluster;
import software.amazon.awscdk.services.ecs.ContainerImage;
import software.amazon.awscdk.services.ecs.patterns.ApplicationLoadBalancedEc2Service;
import software.amazon.awscdk.services.ecs.patterns.ApplicationLoadBalancedTaskImageOptions;
import software.amazon.awscdk.services.iam.Role;
import software.amazon.awscdk.services.iam.ServicePrincipal;
import software.amazon.awscdk.services.opensearchservice.CapacityConfig;
import software.amazon.awscdk.services.opensearchservice.Domain;
import software.amazon.awscdk.services.opensearchservice.DomainProps;
import software.amazon.awscdk.services.opensearchservice.EngineVersion;
import software.amazon.awscdk.services.opensearchservice.ZoneAwarenessConfig;
import software.amazon.awscdk.services.rds.Credentials;
import software.amazon.awscdk.services.rds.DatabaseInstance;
import software.amazon.awscdk.services.rds.DatabaseInstanceEngine;
import software.amazon.awscdk.services.rds.PostgresEngineVersion;
import software.amazon.awscdk.services.rds.PostgresInstanceEngineProps;
import software.amazon.awscdk.services.ssm.StringParameter;
import software.constructs.Construct;

import java.util.Map;

public class AwsInfrastructureOnlineStoreCustomerStack extends Stack {
    public AwsInfrastructureOnlineStoreCustomerStack(final Construct scope, final String id) {
        this(scope, id, null);
    }

    public AwsInfrastructureOnlineStoreCustomerStack(final Construct scope, final String id, final StackProps props) {
        super(scope, id, props);

        Vpc vpc = Vpc.Builder.create(this, "vpcStack-customer")
                .maxAzs(2)
                .subnetConfiguration(Vpc.DEFAULT_SUBNETS_NO_NAT)
                .build();

        CfnParameter customerPostgresDbName = CfnParameter.Builder.create(this, "customerPostgresDbName")
                .description("Name of DB of customerService")
                .defaultValue("customerService")
                .build();

        CfnParameter customerPostgresUserName = CfnParameter.Builder.create(this, "customerPostgresUserName")
                .description("User of DB of customerService")
                .defaultValue("postgres")
                .build();

        CfnParameter customerRepositoryName = CfnParameter.Builder.create(this, "customerRepositoryName")
                .description("Repository of adminService")
                .defaultValue("customer-service")
                .build();

        CfnParameter customerImageTag = CfnParameter.Builder.create(this, "customerImageTag")
                .description("The tag of image")
                .defaultValue("latest")
                .build();

        CfnParameter rabbitmqNameUser = CfnParameter.Builder.create(this, "rabbitmq-user")
                .description("The tag of image")
                .defaultValue("rabbitmq")
                .build();

        String dbCustomerPass = StringParameter.valueForStringParameter(
                this, "customer-pass");

        String rabbitPass = StringParameter.valueForStringParameter(
                this, "rabbitmq-pass");

        String rabbitEndpoint = StringParameter.valueForStringParameter(
                this, "general-rabbitmq-host");

        Role taskRole = Role.Builder.create(this, "TaskRole")
                .assumedBy(ServicePrincipal.Builder.create("ecs-tasks.amazonaws.com").build())
                .build();

        DatabaseInstance postgres = DatabaseInstance.Builder.create(this, "postgres-customer")
                .vpc(vpc)
                .vpcSubnets(SubnetSelection.builder().subnetType(SubnetType.PRIVATE_ISOLATED).build())
                .engine(
                        DatabaseInstanceEngine.postgres(
                                PostgresInstanceEngineProps.builder()
                                        .version(PostgresEngineVersion.VER_13_4)
                                        .build()
                        )
                )
                .credentials(Credentials.fromPassword(customerPostgresUserName.getValueAsString(), new SecretValue(dbCustomerPass)))
                .instanceType(InstanceType.of(InstanceClass.BURSTABLE3, InstanceSize.MICRO))
                .databaseName(customerPostgresDbName.getValueAsString())
                .backupRetention(Duration.days(0))
                .removalPolicy(RemovalPolicy.DESTROY)
                .build();

        postgres.getConnections().allowFromAnyIpv4(Port.tcp(5432), "Allow connections to the database");
        postgres.grantConnect(taskRole);


        DomainProps domainProps = DomainProps.builder()
                .version(EngineVersion.openSearch("OPENSEARCH_1_1"))
                .removalPolicy(RemovalPolicy.DESTROY)
                .vpc(vpc)
                // must be enabled since our VPC contains multiple private subnets.
                .zoneAwareness(ZoneAwarenessConfig.builder()
                        .enabled(true)
                        .build())
                .capacity(CapacityConfig.builder()
                        // must be an even number since the default az count is 2.
                        .dataNodeInstanceType("t2.small.search")
                        .masterNodeInstanceType("t2.small.search")
                        .dataNodes(1)
                        .masterNodes(1)
                        .build())
                .build();

        Domain elastic = new Domain(this, "Elastic", domainProps);
        elastic.getConnections().allowFromAnyIpv4(Port.tcp(443));

        Cluster customerCluster = Cluster.Builder.create(this, "ecs-customer-online-store")
                .vpc(vpc)
                .build();
        AddCapacityOptions addCapacityOptions = AddCapacityOptions.builder()
                .instanceType(InstanceType.of(InstanceClass.BURSTABLE3, InstanceSize.MICRO))
                .vpcSubnets(SubnetSelection.builder()
                        .subnets(vpc.getPublicSubnets())
                        .build())
                .desiredCapacity(1)
                .build();
        customerCluster.addCapacity("cluster-capacity", addCapacityOptions);

        ApplicationLoadBalancedEc2Service customerService = ApplicationLoadBalancedEc2Service.Builder.create(this, "customerService")
                .cluster(customerCluster)
                .publicLoadBalancer(true)
                .desiredCount(1)
                .healthCheckGracePeriod(Duration.hours(4))
                .cpu(512)
                .memoryLimitMiB(400)
                .taskImageOptions(
                        ApplicationLoadBalancedTaskImageOptions.builder()
                                .containerName("customer-service")
                                .taskRole(taskRole)
                                .environment(Map.of(
                                        "CUSTOMER_USER", customerPostgresUserName.getValueAsString(),
                                        "CUSTOMER_DB_URL", "jdbc:postgresql://" + postgres.getDbInstanceEndpointAddress()
                                                + ":" + postgres.getDbInstanceEndpointPort() + "/" + customerPostgresDbName.getValueAsString(),
                                        "CUSTOMER_ELASTIC_HOST", elastic.getDomainEndpoint(),
                                        "CUSTOMER_ELASTIC_PORT", "443",
                                        "RABBITMQ_HOST", rabbitEndpoint,
                                        "RABBITMQ_USER", rabbitmqNameUser.getValueAsString(),
                                        "CUSTOMER_PASS", dbCustomerPass,
                                        "RABBITMQ_PASS", rabbitPass
                                ))
                                .image(ContainerImage.fromEcrRepository(
                                        Repository.fromRepositoryName(this, "customer-service-repository", customerRepositoryName.getValueAsString()),
                                        customerImageTag.getValueAsString()

                                ))
                                .containerPort(8080)
                                .build()
                )
                .build();
    }
}