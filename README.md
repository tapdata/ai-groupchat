# AI Group Chat

AI Group Chat is a local web app for running several AI agents in one shared conversation. It supports API-backed agents, CLI-backed coding agents, multi-agent review, persistent sessions, markdown context files, and device-based remote access control.

The project is a single Java/Javalin application. Build it with Maven, run one jar, and open the browser at `http://localhost:7860`.

## Features

- Multi-agent chat with `@agentId` mentions and `@all` group review.
- Pluggable providers: `openai`, `anthropic`, and local `cli` subprocesses.
- Agent roster configured from `config/agents.json`.
- Per-session transcripts under `logs/`.
- Markdown context sessions from `docs/*.md`.
- `/merge` support to save important findings back into a context file.
- `/merge compress` support to condense context files with backup and over-compression protection.
- Model discovery with `/modellist <agentId>`.
- Local web UI with streaming output and tool-call markers for CLI agents.
- Optional device trust flow for remote access.
- Self-maintenance commands such as `/install`, `/restart`, and `/optimize`.

## Requirements

- Java 17 or newer. Java 21 is recommended.
- Maven 3.8 or newer.
- Git.
- API keys or local CLI tools for the agents you want to use.

The bootstrap scripts in `scripts/` can install Git, Java, and Maven on common systems.

## Quick Start

### Use The Bootstrap Script

Linux or macOS:

```bash
curl -fsSL https://raw.githubusercontent.com/tapdata/ai-groupchat/main/scripts/bootstrap.sh -o bootstrap-ai-groupchat.sh
bash bootstrap-ai-groupchat.sh
```

Windows:

```bat
curl -L https://raw.githubusercontent.com/tapdata/ai-groupchat/main/scripts/bootstrap.bat -o bootstrap-ai-groupchat.bat
bootstrap-ai-groupchat.bat
```

### Manual Run

```bash
git clone --branch main https://github.com/tapdata/ai-groupchat.git
cd ai-groupchat
mvn -q -DskipTests package
java -jar target/ai-groupchat.jar
```

Then open:

```text
http://localhost:7860
```

To run without opening a browser automatically:

```bash
java -Dgroupchat.noBrowser=true -jar target/ai-groupchat.jar
```

## Project Layout

```text
ai-groupchat/
├── config/
│   ├── agents.json              # Local agent configuration and API keys
│   ├── access.json              # Trusted-device access control state
│   └── session-contexts.json    # Session-to-context mapping
├── docs/                        # Curated markdown context files
├── logs/                        # Chat transcripts
├── scripts/
│   ├── bootstrap.sh             # Linux/macOS one-command setup
│   └── bootstrap.bat            # Windows one-command setup
├── src/main/java/io/groupchat/
│   ├── chat/                    # ChatRoom, SessionManager, CommandHandler
│   ├── config/                  # AppConfig, ConfigStore
│   ├── model/                   # Agent and Message models
│   ├── orchestrate/             # @all review orchestration
│   ├── provider/                # OpenAI, Anthropic, CLI providers
│   ├── security/                # Device trust and access guard
│   └── web/                     # Javalin server and roster API
├── src/main/resources/web/      # Browser UI
└── pom.xml
```

## Configuration

On first run, the app creates default configuration in `config/agents.json` if it does not exist. Each agent looks like this:

```json
{
  "id": "codex",
  "name": "Codex",
  "provider": "openai",
  "model": "gpt-4o",
  "apiKey": "YOUR_API_KEY",
  "baseUrl": "https://api.openai.com",
  "enabled": true
}
```

Supported provider types:

| Provider | Use case | Required fields |
|---|---|---|
| `openai` | OpenAI and OpenAI-compatible APIs | `apiKey`, `model`; optional `baseUrl` |
| `anthropic` | Anthropic Messages API or compatible gateways | `apiKey`, `model`; optional `baseUrl` |
| `cli` | Local tools such as Auggie, Claude Code, Codex CLI, Ollama wrappers | `command`; optional `cwd`, `env`, `timeoutSeconds` |

Do not publish real API keys. For public GitHub repositories, prefer committing an example config and keeping your real `config/agents.json` local.

## Common Chat Commands

```text
/help                                  Show command reference
/agents                                Show configured agents and readiness
/config <agent> <key>=<value>          Set agent config
/env <agent>                           Show masked CLI environment variables
/modellist <agent>                     Query available models
/free <agent|all> on|off               Toggle free-mode config
/synthesizer <agent>                   Set the @all final-answer agent
/merge <summary>                       Append a finding to the current context file
/merge compress                        Auto-merge recent chat and condense context
/device pair <name>                    Generate a remote-device pairing code
/device list                           List trusted devices
/access on|off|status                  Manage remote access control
/install [agent]                       Run configured CLI install command
/restart                               Restart the service
```

## Agent Examples

Configure an OpenAI-compatible agent:

```text
/config codex provider=openai
/config codex baseUrl=https://api.openai.com
/config codex apiKey=sk-...
/config codex model=gpt-4o
```

Configure an Anthropic-compatible agent:

```text
/config claude provider=anthropic
/config claude apiKey=sk-ant-...
/config claude model=claude-3-5-sonnet-latest
```

Configure a CLI agent:

```text
/add auggie cli Auggie
/config auggie command=auggie --print
/config auggie cwd=/path/to/project
/config auggie timeoutSeconds=900
```

Configure a headless CLI agent with environment variables:

```text
/config claudecli provider=cli
/config claudecli command=claude --print --verbose --output-format stream-json --include-partial-messages
/config claudecli env.ANTHROPIC_BASE_URL=https://api.example.com/anthropic
/config claudecli env.ANTHROPIC_AUTH_TOKEN=...
/config claudecli env.ANTHROPIC_MODEL=claude-3-5-sonnet-latest
```

## Sessions And Context Files

The app separates two concepts:

- Context files: curated markdown files under `docs/`. Their content is injected into AI requests.
- Transcripts: append-only chat logs under `logs/`.

Start with a specific context file:

```bash
java -jar target/ai-groupchat.jar --context docs/fix-chat.md
```

Inside that session:

```text
/merge Fixed the startup path issue and verified Maven build.
/merge compress
```

`/merge compress` creates a `.bak` backup before writing. If the compressed result is suspiciously short, the original file is not overwritten and a `.compressed-preview` file is saved for review.

## Remote Access

Localhost is allowed by default. For remote browsers, use the device pairing flow:

```text
/access on
/device pair my-phone
```

Open the remote browser, enter the pairing code, and the app stores a trusted-device token in an HttpOnly cookie. Revoke access with:

```text
/device list
/device revoke <deviceId|name>
```

If exposing this app outside your machine, put it behind HTTPS and keep access control enabled.

## Build And Test

Build:

```bash
mvn -q -DskipTests package
```

Run:

```bash
java -jar target/ai-groupchat.jar
```

Clean build:

```bash
mvn clean package
```

## Troubleshooting

`Address already in use`

Another process is using port `7860`. Stop it or change `port` in `config/agents.json`.

`Missing apiKey`

Set the key for the agent:

```text
/config <agentId> apiKey=...
```

CLI agent times out

Increase its timeout:

```text
/config <agentId> timeoutSeconds=1200
```

CLI agent cannot see your project

Set its working directory:

```text
/config <agentId> cwd=/absolute/path/to/project
```

Model returns 503 or unavailable

The configured upstream API or model route is unavailable. Use:

```text
/modellist <agentId>
/config <agentId> model=<another-model>
```

## Security Notes

- `config/agents.json` may contain real API keys. Do not commit your personal copy.
- CLI agents can run local commands depending on the tool you configure. Only use trusted CLI tools.
- Remote access should use HTTPS at the proxy/tunnel layer.
- Pairing codes are one-time credentials; revoke devices you no longer use.

## License

Add your preferred open-source license before publishing.
