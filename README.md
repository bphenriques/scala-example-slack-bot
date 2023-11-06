# Slack Bot Example

Example project on how to integrate [Slack's Bolt Java SDK](https://github.com/slackapi/java-slack-sdk) with [`http4s`](https://http4s.org/).

Disclaimer: I am no expert, but I hope this helps you out.

The scope:
- Handling slack slash command to open new views ([`view.open`](https://api.slack.com/methods/views.open)).
- Handle view submissions, including parsing the view's state and answering back:
  - Positively if the values in the form are valid.
  - Negatively if the values in the form are invalid and feedback to the user is sent using a DM.
- Send IM messages containing actions.
- Handle block actions events and respond accordingly.

## Setup

Local install:
- `sbt`
- JDK 17
- ngrok

Install a Slack App (https://api.slack.com/start/quickstart):
1. Create an App and enter its settings.
2. Setup a Bot Token Scopes under "OAuth & Permissions": 
   1. `chat:write`
   2. `commands` to support slash `/` commands
3. Create a slash command, for example, `/interactive-form`. Leave the link empty for now.
4. Go to "Interactivity & Shortcuts" and enable it. Leave the link empty for now.
5. Go to `Install App` and install to a workspace of your choice. If you are not the administrator, you might need wait for an approval.
6. Invite the app to a channel (use the `@<Name>` followed by clicking on the prompt to invite).
7. Create a local file named `local.dev.env` with the credentials [official-docs](https://api.slack.com/start/building/bolt-java#credentials) with:
   1. Bot token by copying from "Bot User OAuth Access Token" under the "OAuth & Permissions"
   2. Signing secret by copying from "Basic Information", "App Credentials", then "Signing Secret".
   3. Create a `local.dev.env`

      ```shell
      SLACK_BOT_TOKEN=
      SLACK_SIGNING_SECRET=
      ```

### Run

1. Run ngrok: `ngrok http 8080` and copy the `https` returned.
2. Run the app: `./run.sh`
3. Review the slash command and the interactivity webhooks in the Slack application:
   1. Slash Command: ngrok's URL followed by `/slash/events`.
   2. interact Command: ngrok's URL followed by `/slash/events`.
4. Enter the Slack's slash command and test it!

## Format

`sbt scalafmtAll` to format all files (except SBT).
