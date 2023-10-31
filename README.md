# Slack Bot Example

Example project to test Slack Apps. Disclaimer: I am no expert...

## Setup

Local install:
- `sbt`
- JDK 17
- ngrok

Install a Slack App (https://api.slack.com/start/quickstart):
1. Create an App and go to it
2. Setup a Bot Token Scopes under "OAuth & Permissions": 
   1. `chat:write` (obvious)
   2. `commands` to support slash `/` commands
   3. FIXME `incoming-webhook` so that we can listen and act on webhooks. This is similar to older API but now is limited to bots.
3. Create a slash command, for example, `/interactive-form` and for now, copy the link returned when running `ngrok http 8080` following by `/slash/events`.
4. Go to "Interactivity & Shortcuts" and enable it. Copy the link returned when running `ngrok http 8080` following by `/slash/interactivity`.
4. Go to `Install App` and install to a workspace of your choice. If you are not the administrator, you might need wait for an approval.
5. Invite the app to a channel (use the `@<Name>` followed by clicking on the prompt to invite).
6. Create a local file named `local.dev.env` with the credentials [official-docs](https://api.slack.com/start/building/bolt-java#credentials) with:
   1. Bot token by copying from "Bot User OAuth Access Token" under the "OAuth & Permissions"
   2. Signing secret by copying from "Basic Information", "App Credentials", then "Signing Secret".
   3. Create a `local.dev.env`

      ```shell
      SLACK_BOT_TOKEN=
      SLACK_SIGNING_SECRET=
      ```

### Run

1. Run ngrok: `ngrok http 8080`
2. Run the app: `./run.sh`
3. On the slack workspace, update the application to point to the ngrok's URL followed by `/slack/events/`
4. Enter the Slack's slash command.

### Test

TODO

### Architecture

- `http`: Entry-point to handle HTTP requests. Has no business logic nor interacts with 3rd party services.
- `services`: Handles business logic and essentially orchestrates different 3rd party services. Does not know anything about HTTP.

## Format

`sbt scalafmtAll` to format all files (except SBT).
