package ax.ha.clouddevelopment;

import software.amazon.awscdk.*;
import software.amazon.awscdk.services.iam.AnyPrincipal;
import software.amazon.awscdk.services.iam.Effect;
import software.amazon.awscdk.services.s3.BlockPublicAccess;
import software.amazon.awscdk.services.s3.Bucket;
import software.constructs.Construct;
import software.amazon.awscdk.services.iam.PolicyStatement;

import java.util.List;
import java.util.Map;


public class WebsiteBucketStack extends Stack {

    /**
     * Creates a CloudFormation stack for a simple S3 bucket used as a website
     */
    public WebsiteBucketStack(final Construct scope,
                              final String id,
                              final StackProps props,
                              final String groupName) {
        super(scope, id, props);
        // S3 Bucket resource

        final Bucket siteBucket =
                Bucket.Builder.create(this, "websiteBucket")
                        .bucketName(groupName + "-website")
                        .publicReadAccess(true)
                        .blockPublicAccess(BlockPublicAccess.BLOCK_ACLS)
                        .removalPolicy(RemovalPolicy.DESTROY)
                        .autoDeleteObjects(true)
                        .websiteIndexDocument("index.html")
                        .build();

        PolicyStatement sitePolicyStatement = PolicyStatement.Builder.create()
                .actions(List.of("s3:GetObject"))
                .resources(List.of(siteBucket.getBucketArn() + "/*"))
                .effect(Effect.ALLOW)
                .principals(List.of(new AnyPrincipal()))
                .conditions(Map.of(
                        "IpAddress", Map.of("aws:SourceIp", List.of("194.132.145.78/32"))
                ))
                .build();

        siteBucket.addToResourcePolicy(PolicyStatement.Builder.create().build());


        // Bucket ARN CfnOutput variable. This is helpful for a later stage when simply wanting
        // to refer to your storage bucket using only a simple variable
        CfnOutput.Builder.create(this, "websiteBucketOutput")
                .description(String.format("URL of your bucket.", groupName))
                .value(siteBucket.getBucketWebsiteUrl())
                .exportName(groupName + "-s3-demo-url")

                .build();

    }
}