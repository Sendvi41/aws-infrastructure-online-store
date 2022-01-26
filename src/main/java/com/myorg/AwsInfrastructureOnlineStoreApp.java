package com.myorg;

import software.amazon.awscdk.App;
import software.amazon.awscdk.StackProps;


public class AwsInfrastructureOnlineStoreApp {
    public static void main(final String[] args) {
        App app = new App();

        new AwsInfrastructureOnlineStoreStack(app, "admin-stack",
                StackProps.builder()
                        .description("Online-store-infrastructure-admin-service")
                        .build());

        new AwsInfrastructureOnlineStoreCustomerStack(app, "customer-stack",
                StackProps.builder()
                        .description("Online-store-infrastructure-customer-service")
                        .build());
        app.synth();
    }
}

