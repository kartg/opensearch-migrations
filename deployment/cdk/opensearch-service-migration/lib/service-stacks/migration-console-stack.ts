import {StackPropsExt} from "../stack-composer";
import {IVpc, SecurityGroup} from "aws-cdk-lib/aws-ec2";
import {MountPoint, Volume} from "aws-cdk-lib/aws-ecs";
import {Construct} from "constructs";
import {join} from "path";
import {MigrationServiceCore} from "./migration-service-core";
import {StringParameter} from "aws-cdk-lib/aws-ssm";
import {Effect, PolicyStatement, Role, ServicePrincipal} from "aws-cdk-lib/aws-iam";
import {
    createMSKConsumerIAMPolicies,
    createOpenSearchIAMAccessPolicy,
    createOpenSearchServerlessIAMAccessPolicy
} from "../common-utilities";
import {StreamingSourceType} from "../streaming-source-type";


export interface MigrationConsoleProps extends StackPropsExt {
    readonly vpc: IVpc,
    readonly streamingSourceType: StreamingSourceType,
    readonly fetchMigrationEnabled: boolean,
    readonly migrationAnalyticsEnabled: boolean
}

export class MigrationConsoleStack extends MigrationServiceCore {
    
    createMSKAdminIAMPolicies(stage: string, deployId: string): PolicyStatement[] {
        const mskClusterARN = StringParameter.valueForStringParameter(this, `/migration/${stage}/${deployId}/mskClusterARN`);
        const mskClusterName = StringParameter.valueForStringParameter(this, `/migration/${stage}/${deployId}/mskClusterName`);
        const mskClusterAdminPolicy = new PolicyStatement({
            effect: Effect.ALLOW,
            resources: [mskClusterARN],
            actions: [
                "kafka-cluster:*"
            ]
        })
        const mskClusterAllTopicArn = `arn:aws:kafka:${this.region}:${this.account}:topic/${mskClusterName}/*`
        const mskTopicAdminPolicy = new PolicyStatement({
            effect: Effect.ALLOW,
            resources: [mskClusterAllTopicArn],
            actions: [
                "kafka-cluster:*"
            ]
        })
        const mskClusterAllGroupArn = `arn:aws:kafka:${this.region}:${this.account}:group/${mskClusterName}/*`
        const mskConsumerGroupAdminPolicy = new PolicyStatement({
            effect: Effect.ALLOW,
            resources: [mskClusterAllGroupArn],
            actions: [
                "kafka-cluster:*"
            ]
        })
        return [mskClusterAdminPolicy, mskTopicAdminPolicy, mskConsumerGroupAdminPolicy]
    }

    constructor(scope: Construct, id: string, props: MigrationConsoleProps) {
        super(scope, id, props)
        const domainAccessGroupId = StringParameter.valueForStringParameter(this, `/migration/${props.stage}/${props.defaultDeployId}/osAccessSecurityGroupId`)
        let securityGroups = [
            SecurityGroup.fromSecurityGroupId(this, "serviceConnectSG", StringParameter.valueForStringParameter(this, `/migration/${props.stage}/${props.defaultDeployId}/serviceConnectSecurityGroupId`)),
            SecurityGroup.fromSecurityGroupId(this, "trafficStreamSourceAccessSG", StringParameter.valueForStringParameter(this, `/migration/${props.stage}/${props.defaultDeployId}/trafficStreamSourceAccessSecurityGroupId`)),
            SecurityGroup.fromSecurityGroupId(this, "defaultDomainAccessSG", domainAccessGroupId),
            SecurityGroup.fromSecurityGroupId(this, "replayerOutputAccessSG", StringParameter.valueForStringParameter(this, `/migration/${props.stage}/${props.defaultDeployId}/replayerOutputAccessSecurityGroupId`))
        ]
        if (props.migrationAnalyticsEnabled) {
            securityGroups.push(SecurityGroup.fromSecurityGroupId(this, "migrationAnalyticsSGId", StringParameter.valueForStringParameter(this, `/migration/${props.stage}/${props.defaultDeployId}/analyticsDomainSGId`)))
        }

        const osClusterEndpoint = StringParameter.valueForStringParameter(this, `/migration/${props.stage}/${props.defaultDeployId}/osClusterEndpoint`)
        const brokerEndpoints = StringParameter.valueForStringParameter(this, `/migration/${props.stage}/${props.defaultDeployId}/kafkaBrokers`);

        const volumeName = "sharedReplayerOutputVolume"
        const volumeId = StringParameter.valueForStringParameter(this, `/migration/${props.stage}/${props.defaultDeployId}/replayerOutputEFSId`)
        const replayerOutputEFSVolume: Volume = {
            name: volumeName,
            efsVolumeConfiguration: {
                fileSystemId: volumeId,
                transitEncryption: "ENABLED"
            }
        };
        const replayerOutputMountPoint: MountPoint = {
            containerPath: "/shared-replayer-output",
            readOnly: false,
            sourceVolume: volumeName
        }
        const replayerOutputEFSArn = `arn:aws:elasticfilesystem:${props.env?.region}:${props.env?.account}:file-system/${volumeId}`
        const replayerOutputMountPolicy = new PolicyStatement( {
            effect: Effect.ALLOW,
            resources: [replayerOutputEFSArn],
            actions: [
                "elasticfilesystem:ClientMount",
                "elasticfilesystem:ClientWrite"
            ]
        })

        const allReplayerServiceArn = `arn:aws:ecs:${props.env?.region}:${props.env?.account}:service/migration-${props.stage}-ecs-cluster/migration-${props.stage}-traffic-replayer*`
        const updateReplayerServicePolicy = new PolicyStatement({
            effect: Effect.ALLOW,
            resources: [allReplayerServiceArn],
            actions: [
                "ecs:UpdateService"
            ]
        })
        const environment: { [key: string]: string; } = {
            "MIGRATION_DOMAIN_ENDPOINT": osClusterEndpoint,
            "MIGRATION_KAFKA_BROKER_ENDPOINTS": brokerEndpoints
        }
        const openSearchPolicy = createOpenSearchIAMAccessPolicy(this.region, this.account)
        const openSearchServerlessPolicy = createOpenSearchServerlessIAMAccessPolicy(this.region, this.account)
        let servicePolicies = [replayerOutputMountPolicy, openSearchPolicy, openSearchServerlessPolicy, updateReplayerServicePolicy]
        if (props.streamingSourceType === StreamingSourceType.AWS_MSK) {
            const mskAdminPolicies = this.createMSKAdminIAMPolicies(props.stage, props.defaultDeployId)
            servicePolicies = servicePolicies.concat(mskAdminPolicies)
        }
        if (props.fetchMigrationEnabled) {
            environment["FETCH_MIGRATION_COMMAND"] = StringParameter.valueForStringParameter(this, `/migration/${props.stage}/${props.defaultDeployId}/fetchMigrationCommand`)
            // [POC] Add a pipeline role for OSIS
            const osisPipelineRole = new Role(this, 'osisPipelineRole', {
                assumedBy: new ServicePrincipal('osis-pipelines.amazonaws.com'),
                description: 'OSIS Pipeline role for Fetch Migration'
            });
            // Add policy to allow access to Opensearch domains
            osisPipelineRole.addToPolicy(new PolicyStatement({
                effect: Effect.ALLOW,
                actions: ["es:DescribeDomain", "es:ESHttp*"],
                resources: [`arn:aws:es:${props.env?.region}:${props.env?.account}:domain/*`]
            }))
            environment["OSIS_PIPELINE_ROLE_ARN"] = osisPipelineRole.roleArn

            const fetchMigrationTaskDefArn = StringParameter.valueForStringParameter(this, `/migration/${props.stage}/${props.defaultDeployId}/fetchMigrationTaskDefArn`);
            const fetchMigrationTaskRunPolicy = new PolicyStatement({
                effect: Effect.ALLOW,
                resources: [fetchMigrationTaskDefArn],
                actions: [
                    "ecs:RunTask"
                ]
            })
            const fetchMigrationTaskRoleArn = StringParameter.valueForStringParameter(this, `/migration/${props.stage}/${props.defaultDeployId}/fetchMigrationTaskRoleArn`);
            const fetchMigrationTaskExecRoleArn = StringParameter.valueForStringParameter(this, `/migration/${props.stage}/${props.defaultDeployId}/fetchMigrationTaskExecRoleArn`);
            // Required as per https://docs.aws.amazon.com/AmazonECS/latest/userguide/task-iam-roles.html
            // [POC] Allow passing of pipeline role
            const fetchMigrationPassRolePolicy = new PolicyStatement({
                effect: Effect.ALLOW,
                resources: [fetchMigrationTaskRoleArn, fetchMigrationTaskExecRoleArn, osisPipelineRole.roleArn],
                actions: [
                    "iam:PassRole"
                ]
            })
            servicePolicies.push(fetchMigrationTaskRunPolicy)
            servicePolicies.push(fetchMigrationPassRolePolicy)

            // [POC] Enable Migration Console to fetch pipeline from Secrets Manager
            const osiMigrationGetSecretPolicy = new PolicyStatement({
                effect: Effect.ALLOW,
                resources: [`arn:aws:secretsmanager:${props.env?.region}:${props.env?.account}:secret:${props.stage}-${props.defaultDeployId}-fetch-migration-pipelineConfig-*`],
                actions: [
                    "secretsmanager:GetSecretValue"
                ]
            })

            // [POC] Enable OSIS management from Migration Console
            const osisManagementPolicy = new PolicyStatement({
                effect: Effect.ALLOW,
                resources: ["*"],
                actions: [
                    "osis:*"
                ]
            })
            servicePolicies.push(fetchMigrationTaskRunPolicy)
            servicePolicies.push(fetchMigrationPassRolePolicy)
            servicePolicies.push(osiMigrationGetSecretPolicy)
            servicePolicies.push(osisManagementPolicy)

            // [POC] Add VPC options to environment
            environment["OSIS_PIPELINE_VPC_OPTIONS"] = `SubnetIds=${props.vpc.privateSubnets.map(_ => _.subnetId).join(",")},SecurityGroupIds=${domainAccessGroupId}`
        }

        this.createService({
            serviceName: "migration-console",
            dockerFilePath: join(__dirname, "../../../../../", "TrafficCapture/dockerSolution/src/main/docker/migrationConsole"),
            securityGroups: securityGroups,
            volumes: [replayerOutputEFSVolume],
            mountPoints: [replayerOutputMountPoint],
            environment: environment,
            taskRolePolicies: servicePolicies,
            taskCpuUnits: 512,
            taskMemoryLimitMiB: 1024,
            ...props
        });
    }

}
