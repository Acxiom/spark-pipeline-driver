[Documentation Home](../../docs/readme.md) | [GCP Home](../readme.md)

# PubSubPipelineDriver
This driver provides basic support for streaming data from [PubSub](https://cloud.google.com/pubsub/docs/overview) 
streams. As data is consumed, the RDD will be converted into a DataFrame with three columns:

* **key** - the partitionKey
* **value** - the data
* **topic** - The appName

## Command line Parameters
*Required parameters:*
* **driverSetupClass** - This class will handle all of the initial setup such as building out pipelines, creating the PipelineContext.
* **projectId** - The projectId the Pub/Sub subscription may be found.
* **subscription** - The Pub/Sub subscription to listen for messages.

*Optional Parameters:*
* **duration-type** - [minutes | **seconds**] Corresponds to the *duration* parameter.
* **duration** - [number] How long the driver should wait before processing the next batch of data. Default is 10 seconds.
* **terminationPeriod** - [number] The number of ms the system should run and then shut down. 
* **maxRetryAttempts** - [number] The number of times data will attempt to process before failing. Default is 0.
* **terminateAfterFailures** - [boolean] After processing has been retried, fail the process. Default is false.
* **processEmptyRDD** - [boolean] When true, will trigger executions for each window interval
 regardless of whether any messages have been received.
 
### Authorization
The _DriverSetup_ is responsible for providing a [CredentialProvider](../../docs/credentialprovider.md) which may be 
used to locate the "GCPCredential". The system default will be used if none can be found.
