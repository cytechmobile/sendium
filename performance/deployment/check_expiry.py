"""Read-only, live expiry prerequisites for the protected execution controller."""

import argparse
import json
import sys

from aws_cli import AwsCli


def verify_expiry(aws, outputs, bootstrap_stack):
    def require(condition):
        if not condition:
            raise RuntimeError("EXPIRY_NOT_OPERATIONAL")

    function = outputs["ExpiryFunctionName"]
    schedule = outputs["ExpiryScheduleName"]
    config = aws.call("lambda", "get-function-configuration", function_name=function)
    require(config.get("State") == "Active" and config.get("LastUpdateStatus") == "Successful"
            and config.get("CodeSha256") == outputs["ExpiryExpectedCodeSha256"]
            and config.get("FunctionArn") == outputs["ExpiryFunctionArn"]
            and config.get("Role") == outputs["ExpiryRoleArn"]
            and config.get("Handler") == "expiry.handler" and config.get("Runtime") == "python3.13"
            and config.get("Timeout") == 840)
    environment = config.get("Environment", {}).get("Variables", {})
    require(environment.get("BOOTSTRAP_STACK") == bootstrap_stack and environment.get("REPOSITORY") == outputs["RepositoryTag"]
            and environment.get("ARTIFACT_BUCKET") == outputs["ArtifactBucketName"]
            and environment.get("RUN_TTL_MINUTES") == str(outputs["RunResourceTtlMinutes"]))
    require(aws.call("lambda", "get-function-concurrency", function_name=function).get("ReservedConcurrentExecutions") != 0)
    asynchronous = aws.call("lambda", "get-function-event-invoke-config", function_name=function, qualifier="$LATEST")
    require(asynchronous.get("MaximumRetryAttempts") == 0 and asynchronous.get("MaximumEventAgeInSeconds") == 900)
    rule = aws.call("events", "describe-rule", name=schedule)
    require(rule.get("State") == "ENABLED" and rule.get("ScheduleExpression") == "rate(15 minutes)"
            and rule.get("Arn") == outputs["ExpiryScheduleArn"])
    targets = aws.call("events", "list-targets-by-rule", rule=schedule)
    require(not targets.get("NextToken") and targets.get("Targets") == [
        {"Id": "Expiry", "Arn": outputs["ExpiryFunctionArn"],
         "RetryPolicy": {"MaximumRetryAttempts": 0, "MaximumEventAgeInSeconds": 900}}])
    policy = json.loads(aws.call("lambda", "get-policy", function_name=function)["Policy"])
    account = outputs["ExpiryFunctionArn"].split(":")[4]
    require(any(statement.get("Effect") == "Allow" and statement.get("Action") == "lambda:InvokeFunction"
                and statement.get("Principal") == {"Service": "events.amazonaws.com"}
                and statement.get("Resource") == outputs["ExpiryFunctionArn"]
                and statement.get("Condition", {}).get("ArnLike", {}).get("AWS:SourceArn") == outputs["ExpiryScheduleArn"]
                and statement.get("Condition", {}).get("StringEquals", {}).get("AWS:SourceAccount") == account
                for statement in policy.get("Statement", [])))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--region", required=True)
    parser.add_argument("--bootstrap-stack", required=True)
    args = parser.parse_args()
    try:
        aws = AwsCli(args.region)
        boot, = aws.call("cloudformation", "describe-stacks", stack_name=args.bootstrap_stack)["Stacks"]
        outputs = {item["OutputKey"]: item["OutputValue"] for item in boot["Outputs"]}
        if boot["StackStatus"] not in ("CREATE_COMPLETE", "UPDATE_COMPLETE") or outputs.get("ExpiryHandlerInstalled") != "true":
            raise RuntimeError("Expiry is not installed")
        verify_expiry(aws, outputs, args.bootstrap_stack)
        print("EXPIRY_CONFIGURATION_VERIFIED")
        return 0
    except (RuntimeError, ValueError, KeyError, TypeError, OSError):
        print("EXPIRY_CONFIGURATION_NOT_VERIFIED", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
