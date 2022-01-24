package com.myorg;

import software.amazon.awscdk.App;

import software.amazon.awscdk.StackProps;


public class AwsInfrastructureOnlineStoreApp {
    public static void main(final String[] args) {
        App app = new App();

        new AwsInfrastructureOnlineStoreStack(app, "AwsInfrastructureOnlineStoreStack",
                StackProps.builder()
                        .description("Online-store-infrastructure-admin-service")
                        .build());

        app.synth();
    }
}

