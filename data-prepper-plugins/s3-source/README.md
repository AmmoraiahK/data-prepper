# S3 Source

This source allows Data Prepper to use S3 as a source. It uses SQS for notifications
of which S3 objects are new and loads those new objects to parse out events.

## Basic Usage

This source requires an SQS queue which receives 
[S3 Event Notifications](https://docs.aws.amazon.com/AmazonS3/latest/userguide/NotificationHowTo.html).
The S3 Source will load S3 objects that have Event notifications for Create events.
A user-specified codec parses the S3 Object and creates Events from them.

Currently, there are three codecs:

* `newline` - Parses files where each single line is a log event.
* `json` - Parses the file for a JSON array. Each object in the JSON array is a log event.
* `csv` - Parses a character separated file. Each line of data is a log event.



The `compression` property defines how to handle compressed S3 objects. It has the following options.

* `none` - The file is not compressed.
* `gzip` - Apply GZip de-compression on the S3 object.
* `automatic` - Attempts to automatically determine the compression. If the S3 object key name ends in`.gz`, then perform `gzip` compression. Otherwise, it is treated as `none`.

### Example: Un-Compressed Logs 

The following configuration shows a minimum configuration for reading newline-delimited logs which
are not compressed.

```
source:
  s3:
    notification_type: "sqs"
    codec:
      newline:
    compression: none
    sqs:
      queue_url: "https://sqs.us-east-1.amazonaws.com/123456789012/MyQueue"
    aws:
      region: "us-east-1"
      sts_role_arn: "arn:aws:iam::123456789012:role/Data-Prepper"
```

## Configuration Options

All Duration values are a string that represents a duration. They support ISO_8601 notation string ("PT20.345S", "PT15M", etc.) as well as simple notation Strings for seconds ("60s") and milliseconds ("1500ms").


* `notification_type` : Must be `sqs`.

* `compression` (Optional) : The compression algorithm to apply. May be one of: `none`, `gzip`, or `automatic`. Defaults to `none`.

* `codec` (Required) : The codec to apply. Must be either `newline`, `csv` or `json`.

* `sqs` (Required) : The SQS configuration. See [SQS Configuration](#sqs_configuration) for details.

* `aws` (Optional) : AWS configurations. See [AWS Configuration](#aws_configuration) for details.

* `on_error` (Optional) : Determines how to handle errors in SQS. Can be either `retain_messages` or `delete_messages`. If `retain_messages`, then Data Prepper will leave the message in the SQS queue and try again. This is recommended for dead-letter queues. If `delete_messages`, then Data Prepper will delete failed messages. Defaults to `retain_messages`.

* `buffer_timeout` (Optional) : Duration - The timeout for writing events to the Data Prepper buffer. Any Events which the S3 Source cannot write to the Buffer in this time will be discarded. Defaults to 10 seconds.

* `records_to_accumulate` (Optional) : The number of messages to write to accumulate before writing to the Buffer. Defaults to 100.

* `metadata_root_key` (Optional) : String - Sets the base key for adding S3 metadata to each Event. The metadata includes the `key` and `bucket` for each S3 object. Defaults to `s3/`.

* `disable_bucket_ownership_validation` (Optional) : Boolean - If set to true, then the S3 Source will not attempt to validate that the bucket is owned by the expected account. The only expected account is the same account which owns the SQS queue. Defaults to `false`.

### <a name="sqs_configuration">SQS Configuration</a>

* `queue_url` (Required) : The SQS queue URL of the queue to read from.
* `maximum_messages` (Optional) : Duration - The maximum number of messages to read from the queue in any request to the SQS queue. Defaults to 10.
* `visibility_timeout` (Optional) : Duration - The visibility timeout to apply to messages read from the SQS queue. This should be set to the amount of time that Data Prepper may take to read all the S3 objects in a batch. Defaults to 30 seconds.
* `wait_time` (Optional) : Duration - The time to wait for long-polling on the SQS API. Defaults to 20 seconds.
* `poll_delay` (Optional) : Duration - A delay to place between reading and processing a batch of SQS messages and making a subsequent request. Defaults to 0 seconds.

### <a name="aws_configuration">AWS Configuration</a>

The AWS configuration is the same for both SQS and S3.

* `region` (Optional) : The AWS region to use for credentials. Defaults to [standard SDK behavior to determine the region](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/region-selection.html).
* `sts_role_arn` (Optional) : The AWS STS role to assume for requests to SQS and S3. Defaults to null, which will use the [standard SDK behavior for credentials](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/credentials.html).
* `aws_sts_header_overrides` (Optional): A map of header overrides to make when assuming the IAM role for the sink plugin.

## Metrics

### Counters

* `s3ObjectsFailed` - The number of S3 objects that the S3 Source failed to read.
* `s3ObjectsNotFound` - The number of S3 objects that the S3 Source failed to read due to a Not Found error from S3. These are also counted toward `s3ObjectsFailed`.
* `s3ObjectsAccessDenied` - The number of S3 objects that the S3 Source failed to read due to an Access Denied or Forbidden error. These are also counted toward `s3ObjectsFailed`. 
* `s3ObjectsSucceeded` - The number of S3 objects that the S3 Source successfully read.
* `sqsMessagesReceived` - The number of SQS messages received from the queue by the S3 Source.
* `sqsMessagesDeleted` - The number of SQS messages deleted from the queue by the S3 Source.
* `sqsMessagesFailed` - The number of SQS messages that the S3 Source failed to parse.


### Timers

* `s3ObjectReadTimeElapsed` - Measures the time the S3 Source takes to perform a request to GET an S3 object, parse it, and write Events to the buffer.
* `sqsMessageDelay` - Measures the time from when S3 records an event time for the creation of an object to when it was fully parsed.

### Distribution Summaries

* `s3ObjectSizeBytes` - Measures the size of S3 objects as reported by the S3 `Content-Length`. For compressed objects, this is the compressed size.
* `s3ObjectProcessedBytes` - Measures the bytes processed by the S3 source for a given object. For compressed objects, this is the un-compressed size.
* `s3ObjectsEvents` - Measures the number of events (sometimes called records) produced by an S3 object.

## Developer Guide

The integration tests for this plugin do not run as part of the Data Prepper build.

The following command runs the integration tests:

```
./gradlew :data-prepper-plugins:s3-source:integrationTest -Dtests.s3source.region=<your-aws-region> -Dtests.s3source.bucket=<your-bucket> -Dtests.s3source.queue.url=<your-queue-url>
```
