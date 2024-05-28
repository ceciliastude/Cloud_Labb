package ax.ha.clouddevelopment;

import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.ec2.*;
import software.amazon.awscdk.services.iam.*;
import software.amazon.awscdk.services.elasticloadbalancingv2.*;
import software.amazon.awscdk.services.elasticloadbalancingv2.targets.InstanceTarget;
import software.amazon.awscdk.services.route53.*;
import software.amazon.awscdk.services.route53.targets.LoadBalancerTarget;
import software.amazon.awscdk.services.rds.*;
import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.CfnOutputProps;
import software.constructs.Construct;
import software.amazon.awscdk.services.secretsmanager.Secret;
import software.amazon.awscdk.services.secretsmanager.SecretStringGenerator;

import java.util.Arrays;
import java.util.List;

public class EC2DockerApplicationStack extends Stack {

    private final IHostedZone hostedZone = HostedZone.fromHostedZoneAttributes(this, "HaHostedZone", HostedZoneAttributes.builder()
            .hostedZoneId("Z0413857YT73A0A8FRFF")
            .zoneName("cloud-ha.com")
            .build());

    private final Vpc vpc = Vpc.Builder.create(this, "MyVpc")
            .maxAzs(3)  // Default is all AZs in the region
            .subnetConfiguration(Arrays.asList(
                    SubnetConfiguration.builder()
                            .subnetType(SubnetType.PUBLIC)
                            .name("Public")
                            .build(),
                    SubnetConfiguration.builder()
                            .subnetType(SubnetType.PRIVATE_WITH_EGRESS)
                            .name("Private")
                            .build()
            ))
            .build();

    public EC2DockerApplicationStack(final Construct scope, final String id, final StackProps props, final String groupName) {
        super(scope, id, props);

        // Security Group
        SecurityGroup securityGroup = SecurityGroup.Builder.create(this, "SecurityGroup")
                .vpc(vpc)
                .allowAllOutbound(true)
                .build();

        // IAM Role
        Role role = Role.Builder.create(this, "InstanceRole")
                .assumedBy(new ServicePrincipal("ec2.amazonaws.com"))
                .managedPolicies(Arrays.asList(
                        ManagedPolicy.fromAwsManagedPolicyName("AmazonSSMManagedInstanceCore"),
                        ManagedPolicy.fromAwsManagedPolicyName("AmazonEC2ContainerRegistryReadOnly")
                ))
                .build();

        // Secret for the database credentials
        Secret dbSecret = Secret.Builder.create(this, "DBSecret")
                .secretName("postgresCredentialsNew")  // Change this to a unique name
                .description("Postgres credentials")
                .generateSecretString(SecretStringGenerator.builder()
                        .secretStringTemplate("{\"username\":\"master\"}")
                        .generateStringKey("password")
                        .passwordLength(16)
                        .excludeCharacters("/@\" ")
                        .build())
                .build();

        // Security Group for the RDS instance
        SecurityGroup databaseSecurityGroup = SecurityGroup.Builder.create(this, "DatabaseSecurityGroup")
                .vpc(vpc)
                .allowAllOutbound(true)
                .build();

        databaseSecurityGroup.addIngressRule(securityGroup, Port.tcp(5432), "Allow postgres access from EC2");

        // RDS Database Instance
        DatabaseInstance dbInstance = DatabaseInstance.Builder.create(this, "DBInstance")
                .engine(DatabaseInstanceEngine.postgres(PostgresInstanceEngineProps.builder()
                        .version(PostgresEngineVersion.VER_12_5)
                        .build()))
                .vpc(vpc)
                .vpcSubnets(SubnetSelection.builder()
                        .subnetType(SubnetType.PRIVATE_WITH_EGRESS)
                        .build())
                .credentials(Credentials.fromSecret(dbSecret))
                .instanceType(software.amazon.awscdk.services.ec2.InstanceType.of(InstanceClass.BURSTABLE3, InstanceSize.MICRO))
                .removalPolicy(RemovalPolicy.DESTROY)
                .securityGroups(List.of(databaseSecurityGroup))
                .allocatedStorage(20)
                .build();

        dbInstance.getConnections().allowFrom(securityGroup, Port.tcp(5432));

        // EC2 instance
        Instance ec2Instance = Instance.Builder.create(this, "EC2Instance")
                .instanceType(software.amazon.awscdk.services.ec2.InstanceType.of(InstanceClass.BURSTABLE3, InstanceSize.MICRO))
                .machineImage(MachineImage.latestAmazonLinux2())  // Updated to latest Amazon Linux 2
                .vpc(vpc)
                .securityGroup(securityGroup)
                .role(role)
                .vpcSubnets(SubnetSelection.builder()
                        .subnetType(SubnetType.PUBLIC)
                        .build())
                .build();

        // User Data to the instance
        ec2Instance.addUserData(
                "yum install docker -y",
                "sudo systemctl start docker",
                "aws ecr get-login-password --region eu-north-1 | docker login --username AWS --password-stdin 292370674225.dkr.ecr.eu-north-1.amazonaws.com",
                "DB_PASSWORD=$(aws secretsmanager get-secret-value --secret-id postgresCredentialsNew --query SecretString --output text --region eu-north-1 | jq -r .password)",
                "docker run -d --name my-application -p 80:8080 -e DB_URL=jdbc:postgresql://" + dbInstance.getDbInstanceEndpointAddress() + ":5432 -e DB_USERNAME=master -e DB_PASSWORD=$DB_PASSWORD -e SPRING_PROFILES_ACTIVE=postgres 292370674225.dkr.ecr.eu-north-1.amazonaws.com/webshop-api:latest"
        );

        // Application Load Balancer
        ApplicationLoadBalancer alb = ApplicationLoadBalancer.Builder.create(this, "ALB")
                .vpc(vpc)
                .internetFacing(true)
                .vpcSubnets(SubnetSelection.builder()
                        .subnetType(SubnetType.PUBLIC)
                        .build())
                .securityGroup(securityGroup)
                .build();

        // Listener to the ALB
        ApplicationListener listener = alb.addListener("Listener", BaseApplicationListenerProps.builder()
                .port(80)
                .protocol(ApplicationProtocol.HTTP)
                .build());

        // Target to the Listener
        listener.addTargets("Target", AddApplicationTargetsProps.builder()
                .port(80)
                .targets(Arrays.asList(new InstanceTarget(ec2Instance)))
                .build());

        // Route53 A Record
        ARecord.Builder.create(this, "AliasRecord")
                .zone(hostedZone)
                .recordName(groupName + "-api")
                .target(RecordTarget.fromAlias(new LoadBalancerTarget(alb)))
                .build();

        new CfnOutput(this, "database-endpoint", CfnOutputProps.builder()
                .value(dbInstance.getDbInstanceEndpointAddress())
                .description("Database Endpoint: ")
                .build());
    }
}
