"""Small AWS CLI transport shared by the protected controller and private nodes."""

import json
from pathlib import Path
import subprocess


class AwsError(RuntimeError):
    pass


class AwsCli:
    def __init__(self, region):
        self.region = region

    def call(self, service, operation, **parameters):
        command = ["aws", service, operation, "--region", self.region, "--output", "json", "--no-cli-pager"]
        for key, value in parameters.items():
            command.append("--" + key.replace("_", "-"))
            command.append(json.dumps(value) if isinstance(value, (dict, list)) else str(value))
        try:
            result = subprocess.run(command, capture_output=True, text=True, timeout=120, check=True)
            return json.loads(result.stdout) if result.stdout.strip() else {}
        except (OSError, ValueError, subprocess.SubprocessError):
            # Do not publish AWS stderr, command arguments, or secret values.
            raise AwsError(service + ":" + operation + " failed") from None

    def put(self, bucket, key, path, immutable=False):
        parameters = {"bucket": bucket, "key": key, "body": str(path)}
        if immutable:
            parameters["if_none_match"] = "*"
        return self.call("s3api", "put-object", **parameters)

    def get(self, bucket, key, path):
        # get-object's destination is positional, unlike the JSON API parameters.
        command = ["aws", "s3api", "get-object", "--region", self.region, "--no-cli-pager",
                   "--bucket", bucket, "--key", key, str(path)]
        try:
            subprocess.run(command, capture_output=True, timeout=120, check=True)
        except (OSError, subprocess.SubprocessError):
            Path(path).unlink(missing_ok=True)
            raise AwsError("s3api:get-object failed") from None
