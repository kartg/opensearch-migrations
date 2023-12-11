#
# Copyright OpenSearch Contributors
# SPDX-License-Identifier: Apache-2.0
#
# The OpenSearch Contributors require contributions made to
# this file be licensed under the Apache-2.0 license or a
# compatible open source license.
#

import argparse
import logging
import os
import yaml
import endpoint_utils

if __name__ == '__main__':
    # Set log level
    logging.basicConfig(level=logging.INFO)
    # Set up parsing for command line arguments
    arg_parser = argparse.ArgumentParser(
        prog="python osi_data_migration.py",
        description="Launches an AWS Opensearch Ingestion pipeline given an input pipeline"
    )
    # Required positional argument
    arg_parser.add_argument(
        "input",
        help="Path to the input Data Prepper pipeline YAML file"
    )
    arg_parser.add_argument(
        "output",
        help="Path to which the output OSI pipeline YAML will be written"
    )
    namespace = arg_parser.parse_args()
    with open(namespace.input, 'r') as input:
        dp_config = yaml.safe_load(input)
    # We expect the Data Prepper pipeline to only have a single top-level value
    pipeline_config = next(iter(dp_config.values()))
    # Remove options that are unsupported by OSI
    if "workers" in pipeline_config:
        del pipeline_config["workers"]
    if "delay" in pipeline_config:
        del pipeline_config["delay"]
    # Add OSI version spec to the top-level dict
    dp_config["version"] = "2"

    # Remove options from source and sink that are unsupported by OSI
    # and add AWS config instead
    config = endpoint_utils.get_supported_endpoint_config(pipeline_config, endpoint_utils.SOURCE_KEY)[1]
    # Fargate stores the current region in the AWS_REGION env var
    region: str = os.environ.get("AWS_REGION")
    pipeline_role_arn: str = os.environ.get("OSIS_PIPELINE_ROLE_ARN")
    if "disable_authentication" in config:
        del config["disable_authentication"]
    config["aws"] = {"region": region, "sts_role_arn": pipeline_role_arn}

    config = endpoint_utils.get_supported_endpoint_config(pipeline_config, endpoint_utils.SINK_KEY)[1]
    if "disable_authentication" in config:
        del config["disable_authentication"]
    config["aws"] = {"region": region, "sts_role_arn": pipeline_role_arn}

    # Write OSI pipeline config to output file
    with open(namespace.output, 'w') as out_file:
        yaml.dump(dp_config, out_file)

