#!/bin/bash

# Start by clearing any previous state
if [ -e /tmp/user_pipeline ]; then
    rm /tmp/user_pipeline
fi
if [ -e /tmp/temp_pipeline ]; then
    rm /tmp/temp_pipeline
fi
if [ -e /tmp/osi_pipeline.yaml ]; then
    rm /tmp/osi_pipeline.yaml
fi

# Ensure target cluster endpoint is available as an env var
if [ -z "$MIGRATION_DOMAIN_ENDPOINT" ]; then
    echo "MIGRATION_DOMAIN_ENDPOINT environment variable not found for target cluster endpoint, exiting..."
    exit 1
fi
# Ensure OSIS pipeline role ARN is available as an env var
if [ -z "$OSIS_PIPELINE_ROLE_ARN" ]; then
    echo "OSIS_PIPELINE_ROLE_ARN environment variable not found for OSIS pipeline role, exiting..."
    exit 1
fi
# Ensure OSIS pipeline VPC options are available as an env var
if [ -z "$OSIS_PIPELINE_VPC_OPTIONS" ]; then
    echo "OSIS_PIPELINE_VPC_OPTIONS environment variable not found, exiting..."
    exit 1
fi

# Default values
secret_name="dev-default-fetch-migration-pipelineConfig"

# Override default values with optional command-line arguments
while [[ $# -gt 0 ]]; do
    key="$1"
    case $key in
        --secret)
            secret_name="$2"
            shift
            shift
            ;;
        *)
            shift
            ;;
    esac
done

# Get pipeline config from secrets manager
pipeline_config=`aws secretsmanager get-secret-value --secret-id $secret_name | jq -r '.SecretString' | base64 -d`
# Remove any port from target endpoint because OSIS doesn't allow it
target_endpoint=${MIGRATION_DOMAIN_ENDPOINT%:[0-9]*}
# Replace target cluster placeholder with actual endpoint value
pipeline_config=${pipeline_config/<TARGET_CLUSTER_ENDPOINT_PLACEHOLDER>/$target_endpoint}
# Write output to temp file for use by metadata migration
cat <<<$pipeline_config > /tmp/user_pipeline
# Setup and run metadata migration
cd python/
pip3 install --user -r requirements.txt
# Run metadata migration
python3 metadata_migration.py -r /tmp/user_pipeline /tmp/temp_pipeline
# Parse output file from previous step to create OSI pipeline input
python3 osi_data_migration.py /tmp/temp_pipeline /tmp/osi_pipeline.yaml
cd ..
# TODO Figure out why log publishing config via CLI fails
#aws osis create-pipeline --pipeline-name osi-fetch-migration --min-units 1 --max-units 1 --pipeline-configuration-body file:///tmp/osi_pipeline.yaml --log-publishing-options IsLoggingEnabled=true,CloudWatchLogDestination={LogGroup=/aws/vendedlogs/OpenSearchService/pipelines/osi-fetch-migration} --vpc-options $OSIS_PIPELINE_VPC_OPTIONS
aws osis create-pipeline --pipeline-name osi-fetch-migration --min-units 1 --max-units 1 --pipeline-configuration-body file:///tmp/osi_pipeline.yaml --vpc-options $OSIS_PIPELINE_VPC_OPTIONS

# Clean up state
if [ -e /tmp/user_pipeline ]; then
    rm /tmp/user_pipeline
fi
if [ -e /tmp/temp_pipeline.yaml ]; then
    rm /tmp/temp_pipeline.yaml
fi
