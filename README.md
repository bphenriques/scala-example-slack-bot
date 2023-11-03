# Slack Bot Example

Example project to test Slack Apps on the Christmas theme. I am no expert, but I hope this helps you out.

The flow setup here:
1. User calls `/interactive-form`, in turn, the server will:
   1. Submit an [`view.open`](https://api.slack.com/methods/views.open) API call to display a form with a pre-computed
      set of options.
   2. Acknowledge the response back to slack.
2. User fills the form and presses submit:
   1. To test form validations, a text field has to match exactly the String "Hello there!".
   2. If everything is alright, the form is accepted.
3. The server will receive a `view_submission` with the content of the form:
   1. For now, the final result is just logged.

Limitations:
- Issues when returning an updated view as part of the `view_submission` webhook.

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
3. Review the slash command and the interactivity webhooks in the Slack application.
4. Enter the Slack's slash command.

### Test

TODO

### Architecture

- `http`: Entry-point to handle HTTP requests. Has no business logic nor interacts with 3rd party services.
- `services`: Handles business logic and essentially orchestrates different 3rd party services. Does not know anything about HTTP.

## Format

`sbt scalafmtAll` to format all files (except SBT).
