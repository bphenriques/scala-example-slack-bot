# Slack Bot Example

Example project to test Slack Apps.

## Development

Dependencies:
- Install `sbt` and JDK 17

### Run

TODO

### Test

TODO

### Architecture

- `http`: Entry-point to handle HTTP requests. Has no business logic nor interacts with 3rd party services.
- `services`: Handles business logic and essentially orchestrates different 3rd party services. Does not know anything about HTTP.

Note: this project attempts to follow existing Scala standards and its example projects.

## Format

`sbt scalafmtAll` to format all files (except SBT).
