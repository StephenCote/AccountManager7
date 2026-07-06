

================================================================================
# PAGE: hooks
# source: https://docs.claude.com/en/docs/claude-code/hooks.md
================================================================================

> ## Documentation Index
> Fetch the complete documentation index at: https://code.claude.com/docs/llms.txt
> Use this file to discover all available pages before exploring further.

# Hooks reference

> Reference for Claude Code hook events, configuration schema, JSON input/output formats, exit codes, async hooks, HTTP hooks, prompt hooks, and MCP tool hooks.

<Tip>
  For a quickstart guide with examples, see [Automate actions with hooks](/en/hooks-guide).
</Tip>

Hooks are user-defined shell commands, HTTP endpoints, or LLM prompts that execute automatically at specific points in Claude Code's lifecycle. Use this reference to look up event schemas, configuration options, JSON input/output formats, and advanced features like async hooks, HTTP hooks, and MCP tool hooks. If you're setting up hooks for the first time, start with the [guide](/en/hooks-guide) instead.

## Hook lifecycle

Hooks fire at specific points during a Claude Code session. When an event fires and a matcher matches, Claude Code passes JSON context about the event to your hook handler. For command hooks, input arrives on stdin. For HTTP hooks, it arrives as the POST request body. Your handler can then inspect the input, take action, and optionally return a decision. Events fall into three cadences: once per session (`SessionStart`, `SessionEnd`), once per turn (`UserPromptSubmit`, `Stop`, `StopFailure`), and on every tool call inside the agentic loop (`PreToolUse`, `PostToolUse`):

<div style={{maxWidth: "500px", margin: "0 auto"}}>
  <Frame>
    <img src="https://mintcdn.com/claude-code/uLsR38F1U_5zPppm/images/hooks-lifecycle.svg?fit=max&auto=format&n=uLsR38F1U_5zPppm&q=85&s=fbdbd78ad9f474da7d344879341341f0" alt="Hook lifecycle diagram showing optional Setup feeding into SessionStart, then a per-turn loop containing UserPromptSubmit, UserPromptExpansion for slash commands, the nested agentic loop (PreToolUse, PermissionRequest, PostToolUse, PostToolUseFailure, PostToolBatch, SubagentStart/Stop, TaskCreated, TaskCompleted), and Stop or StopFailure, followed by TeammateIdle, PreCompact, PostCompact, and SessionEnd, with Elicitation and ElicitationResult nested inside MCP tool execution, PermissionDenied as a side branch from PermissionRequest for auto-mode denials, WorktreeCreate, WorktreeRemove, Notification, ConfigChange, InstructionsLoaded, CwdChanged, and FileChanged as standalone async events, and MessageDisplay as a display-only event that runs while assistant message text streams" width="520" height="1228" data-path="images/hooks-lifecycle.svg" />
  </Frame>
</div>

The table below summarizes when each event fires. The [Hook events](#hook-events) section documents the full input schema and decision control options for each one.

| Event                 | When it fires                                                                                                                                          |
| :-------------------- | :----------------------------------------------------------------------------------------------------------------------------------------------------- |
| `SessionStart`        | When a session begins or resumes                                                                                                                       |
| `Setup`               | When you start Claude Code with `--init-only`, or with `--init` or `--maintenance` in `-p` mode. For one-time preparation in CI or scripts             |
| `UserPromptSubmit`    | When you submit a prompt, before Claude processes it                                                                                                   |
| `UserPromptExpansion` | When a user-typed command expands into a prompt, before it reaches Claude. Can block the expansion                                                     |
| `PreToolUse`          | Before a tool call executes. Can block it                                                                                                              |
| `PermissionRequest`   | When a permission dialog appears                                                                                                                       |
| `PermissionDenied`    | When a tool call is denied by the auto mode classifier. Return `{retry: true}` to tell the model it may retry the denied tool call                     |
| `PostToolUse`         | After a tool call succeeds                                                                                                                             |
| `PostToolUseFailure`  | After a tool call fails                                                                                                                                |
| `PostToolBatch`       | After a full batch of parallel tool calls resolves, before the next model call                                                                         |
| `Notification`        | When Claude Code sends a notification                                                                                                                  |
| `MessageDisplay`      | While assistant message text is displayed                                                                                                              |
| `SubagentStart`       | When a subagent is spawned                                                                                                                             |
| `SubagentStop`        | When a subagent finishes                                                                                                                               |
| `TaskCreated`         | When a task is being created via `TaskCreate`                                                                                                          |
| `TaskCompleted`       | When a task is being marked as completed                                                                                                               |
| `Stop`                | When Claude finishes responding                                                                                                                        |
| `StopFailure`         | When the turn ends due to an API error. Output and exit code are ignored                                                                               |
| `TeammateIdle`        | When an [agent team](/en/agent-teams) teammate is about to go idle                                                                                     |
| `InstructionsLoaded`  | When a CLAUDE.md or `.claude/rules/*.md` file is loaded into context. Fires at session start and when files are lazily loaded during a session         |
| `ConfigChange`        | When a configuration file changes during a session                                                                                                     |
| `CwdChanged`          | When the working directory changes, for example when Claude executes a `cd` command. Useful for reactive environment management with tools like direnv |
| `FileChanged`         | When a watched file changes on disk. The `matcher` field specifies which filenames to watch                                                            |
| `WorktreeCreate`      | When a worktree is being created via `--worktree` or `isolation: "worktree"`. Replaces default git behavior                                            |
| `WorktreeRemove`      | When a worktree is being removed, either at session exit or when a subagent finishes                                                                   |
| `PreCompact`          | Before context compaction                                                                                                                              |
| `PostCompact`         | After context compaction completes                                                                                                                     |
| `Elicitation`         | When an MCP server requests user input during a tool call                                                                                              |
| `ElicitationResult`   | After a user responds to an MCP elicitation, before the response is sent back to the server                                                            |
| `SessionEnd`          | When a session terminates                                                                                                                              |

### How a hook resolves

To see how these pieces fit together, consider this `PreToolUse` hook that blocks destructive shell commands. The `matcher` narrows to Bash tool calls and the `if` condition narrows further to Bash subcommands matching `rm *`, so `block-rm.sh` only spawns when both filters match:

```json theme={null}
{
  "hooks": {
    "PreToolUse": [
      {
        "matcher": "Bash",
        "hooks": [
          {
            "type": "command",
            "if": "Bash(rm *)",
            "command": "${CLAUDE_PROJECT_DIR}/.claude/hooks/block-rm.sh",
            "args": []
          }
        ]
      }
    ]
  }
}
```

The script reads the JSON input from stdin, extracts the command, and returns a `permissionDecision` of `"deny"` if it contains `rm -rf`:

```bash theme={null}
#!/bin/bash
# .claude/hooks/block-rm.sh
COMMAND=$(jq -r '.tool_input.command')

if echo "$COMMAND" | grep -q 'rm -rf'; then
  jq -n '{
    hookSpecificOutput: {
      hookEventName: "PreToolUse",
      permissionDecision: "deny",
      permissionDecisionReason: "Destructive command blocked by hook"
    }
  }'
else
  exit 0  # no decision; normal permission flow applies
fi
```

Now suppose Claude Code decides to run `Bash "rm -rf /tmp/build"`. Here's what happens:

<Frame>
  <img src="https://mintcdn.com/claude-code/ikqp3_70mqIahteV/images/hook-resolution.svg?fit=max&auto=format&n=ikqp3_70mqIahteV&q=85&s=be0bf3053550c26de5f54cd64674c197" alt="Diagram of hook resolution: PreToolUse fires, the matcher checks for a Bash match, then the if condition checks for a Bash(rm *) match. If both match, the hook command runs and returns permissionDecision deny, so the tool call is blocked and Claude Code continues. If either check fails to match, the hook is skipped and the tool call is allowed to proceed." width="930" height="270" data-path="images/hook-resolution.svg" />
</Frame>

<Steps>
  <Step title="Event fires">
    The `PreToolUse` event fires. Claude Code sends the tool input as JSON on stdin to the hook:

    ```json theme={null}
    { "tool_name": "Bash", "tool_input": { "command": "rm -rf /tmp/build" }, ... }
    ```
  </Step>

  <Step title="Matcher checks">
    The matcher `"Bash"` matches the tool name, so this hook group activates. If you omit the matcher or use `"*"`, the group activates on every occurrence of the event.
  </Step>

  <Step title="If condition checks">
    The `if` condition `"Bash(rm *)"` matches because `rm -rf /tmp/build` is a subcommand matching `rm *`, so this handler spawns. If the command had been `npm test`, the `if` check would fail and `block-rm.sh` would never run, avoiding the process spawn overhead. The `if` field is optional; without it, every handler in the matched group runs.
  </Step>

  <Step title="Hook handler runs">
    The script inspects the full command and finds `rm -rf`, so it prints a decision to stdout:

    ```json theme={null}
    {
      "hookSpecificOutput": {
        "hookEventName": "PreToolUse",
        "permissionDecision": "deny",
        "permissionDecisionReason": "Destructive command blocked by hook"
      }
    }
    ```

    If the command had been a safer `rm` variant like `rm file.txt`, the script would hit `exit 0` instead. Exit code 0 with no output means the hook has no decision to report, so the tool call continues through the normal [permission flow](/en/permissions). The hook can deny the call, but staying silent doesn't approve it.
  </Step>

  <Step title="Claude Code acts on the result">
    Claude Code reads the JSON decision, blocks the tool call, and shows Claude the reason.
  </Step>
</Steps>

The [Configuration](#configuration) section below documents the full schema, and each [hook event](#hook-events) section documents what input your command receives and what output it can return.

## Configuration

Hooks are defined in JSON settings files. The configuration has three levels of nesting:

1. Choose a [hook event](#hook-events) to respond to, like `PreToolUse` or `Stop`
2. Add a [matcher group](#matcher-patterns) to filter when it fires, like "only for the Bash tool"
3. Define one or more [hook handlers](#hook-handler-fields) to run when matched

See [How a hook resolves](#how-a-hook-resolves) above for a complete walkthrough with an annotated example.

<Note>
  This page uses specific terms for each level: **hook event** for the lifecycle point, **matcher group** for the filter, and **hook handler** for the shell command, HTTP endpoint, MCP tool, prompt, or agent that runs. "Hook" on its own refers to the general feature.
</Note>

### Hook locations

Where you define a hook determines its scope:

| Location                                                   | Scope                         | Shareable                                  |
| :--------------------------------------------------------- | :---------------------------- | :----------------------------------------- |
| `~/.claude/settings.json`                                  | All your projects             | No, local to your machine                  |
| `.claude/settings.json`                                    | Single project                | Yes, can be committed to the repo          |
| `.claude/settings.local.json`                              | Single project                | No, gitignored when Claude Code creates it |
| Managed policy settings                                    | Organization-wide             | Yes, admin-controlled                      |
| [Plugin](/en/plugins) `hooks/hooks.json`                   | When plugin is enabled        | Yes, bundled with the plugin               |
| [Skill](/en/skills) or [agent](/en/sub-agents) frontmatter | While the component is active | Yes, defined in the component file         |

For details on settings file resolution, see [settings](/en/settings). Enterprise administrators can use `allowManagedHooksOnly` to block user, project, and plugin hooks. Hooks from plugins force-enabled in managed settings `enabledPlugins` are exempt, so administrators can distribute vetted hooks through an organization marketplace. See [Hook configuration](/en/settings#hook-configuration).

### Matcher patterns

The `matcher` field filters when hooks fire. How a matcher is evaluated depends on the characters it contains:

| Matcher value                                         | Evaluated as                                                                                         | Example                                                                                                                                         |
| :---------------------------------------------------- | :--------------------------------------------------------------------------------------------------- | :---------------------------------------------------------------------------------------------------------------------------------------------- |
| `"*"`, `""`, or omitted                               | Match all                                                                                            | fires on every occurrence of the event                                                                                                          |
| Only letters, digits, `_`, `-`, spaces, `,`, and `\|` | Exact string, or list of exact strings separated by `\|` or `,` with optional surrounding whitespace | `Bash` matches only the Bash tool; `Edit\|Write` and `Edit, Write` each match either tool exactly; `code-reviewer` matches only that agent type |
| Contains any other character                          | JavaScript regular expression, unanchored                                                            | `^Notebook` matches any tool whose name starts with `Notebook`; `mcp__memory__.*` matches every tool from the `memory` server                   |

A matcher on the regular-expression path is tested with JavaScript's `RegExp.prototype.test`, which succeeds on a match anywhere in the value. `Edit.*` matches both `Edit` and `NotebookEdit`; wrap the pattern in `^` and `$`, as in `^Edit$`, when you need a whole-string match.

Comma separators and the surrounding whitespace tolerance require Claude Code v2.1.191 or later.

Hyphens in the exact-match set require Claude Code v2.1.195 or later. On earlier versions a hyphenated name like `code-reviewer` is evaluated as an unanchored regular expression, so it also fires for `senior-code-reviewer`; anchor it as `^code-reviewer$` on those versions to match only that name.

`FileChanged` and `StopFailure` use a narrower exact-match set of letters, digits, `_`, and `|` only. A hyphen, space, or comma in a matcher for those two events keeps it on the regular-expression path, and only `|` separates alternatives. Every other event with matcher support in the table that follows accepts `|` or `,`.

The `FileChanged` event does not follow these rules when building its watch list. See [FileChanged](#filechanged).

Each event type matches on a different field:

| Event                                                                                                                                             | What the matcher filters                                     | Example matcher values                                                                                                                                                              |
| :------------------------------------------------------------------------------------------------------------------------------------------------ | :----------------------------------------------------------- | :---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `PreToolUse`, `PostToolUse`, `PostToolUseFailure`, `PermissionRequest`, `PermissionDenied`                                                        | tool name                                                    | `Bash`, `Edit\|Write`, `mcp__.*`                                                                                                                                                    |
| `SessionStart`                                                                                                                                    | how the session started                                      | `startup`, `resume`, `clear`, `compact`                                                                                                                                             |
| `Setup`                                                                                                                                           | which CLI flag triggered setup                               | `init`, `maintenance`                                                                                                                                                               |
| `SessionEnd`                                                                                                                                      | why the session ended                                        | `clear`, `resume`, `logout`, `prompt_input_exit`, `bypass_permissions_disabled`, `other`                                                                                            |
| `Notification`                                                                                                                                    | notification type                                            | `permission_prompt`, `idle_prompt`, `auth_success`, `elicitation_dialog`, `elicitation_complete`, `elicitation_response`                                                            |
| `SubagentStart`                                                                                                                                   | agent type                                                   | `general-purpose`, `Explore`, `Plan`, custom agent names, or plugin-scoped names like `^my-plugin:reviewer$`                                                                        |
| `PreCompact`, `PostCompact`                                                                                                                       | what triggered compaction                                    | `manual`, `auto`                                                                                                                                                                    |
| `SubagentStop`                                                                                                                                    | agent type                                                   | same values as `SubagentStart`                                                                                                                                                      |
| `ConfigChange`                                                                                                                                    | configuration source                                         | `user_settings`, `project_settings`, `local_settings`, `policy_settings`, `skills`                                                                                                  |
| `CwdChanged`                                                                                                                                      | no matcher support                                           | always fires on every directory change                                                                                                                                              |
| `FileChanged`                                                                                                                                     | literal filenames to watch (see [FileChanged](#filechanged)) | `.envrc\|.env`                                                                                                                                                                      |
| `StopFailure`                                                                                                                                     | error type                                                   | `rate_limit`, `overloaded`, `authentication_failed`, `oauth_org_not_allowed`, `billing_error`, `invalid_request`, `model_not_found`, `server_error`, `max_output_tokens`, `unknown` |
| `InstructionsLoaded`                                                                                                                              | load reason                                                  | `session_start`, `nested_traversal`, `path_glob_match`, `include`, `compact`                                                                                                        |
| `UserPromptExpansion`                                                                                                                             | command name                                                 | your skill or command names                                                                                                                                                         |
| `Elicitation`                                                                                                                                     | MCP server name                                              | your configured MCP server names                                                                                                                                                    |
| `ElicitationResult`                                                                                                                               | MCP server name                                              | same values as `Elicitation`                                                                                                                                                        |
| `UserPromptSubmit`, `PostToolBatch`, `Stop`, `TeammateIdle`, `TaskCreated`, `TaskCompleted`, `WorktreeCreate`, `WorktreeRemove`, `MessageDisplay` | no matcher support                                           | always fires on every occurrence                                                                                                                                                    |

The matcher runs against a field from the [JSON input](#hook-input-and-output) that Claude Code sends to your hook on stdin. For tool events, that field is `tool_name`. Each [hook event](#hook-events) section lists the full set of matcher values and the input schema for that event.

This example runs a linting script only when Claude writes or edits a file:

```json theme={null}
{
  "hooks": {
    "PostToolUse": [
      {
        "matcher": "Edit|Write",
        "hooks": [
          {
            "type": "command",
            "command": "/path/to/lint-check.sh"
          }
        ]
      }
    ]
  }
}
```

`UserPromptSubmit`, `PostToolBatch`, `Stop`, `TeammateIdle`, `TaskCreated`, `TaskCompleted`, `WorktreeCreate`, `WorktreeRemove`, `MessageDisplay`, and `CwdChanged` don't support matchers and always fire on every occurrence. If you add a `matcher` field to these events, it is silently ignored.

For tool events, you can filter more narrowly by setting the [`if` field](#common-fields) on individual hook handlers. `if` uses [permission rule syntax](/en/permissions) to match against the tool name and arguments together, so `"Bash(git *)"` runs when any subcommand of the Bash input matches `git *` and `"Edit(*.ts)"` runs only for TypeScript files.

#### Match MCP tools

[MCP](/en/mcp) server tools appear as regular tools in tool events (`PreToolUse`, `PostToolUse`, `PostToolUseFailure`, `PermissionRequest`, `PermissionDenied`), so you can match them the same way you match any other tool name.

MCP tools follow the naming pattern `mcp__<server>__<tool>`, for example:

* `mcp__memory__create_entities`: Memory server's create entities tool
* `mcp__filesystem__read_file`: Filesystem server's read file tool
* `mcp__github__search_repositories`: GitHub server's search tool

To match every tool from a server, append `.*` to the server prefix. The `.*` is required: a matcher like `mcp__memory` or `mcp__brave-search` contains only exact-match characters, so it is compared as an exact string and matches no tool.

* `mcp__memory__.*` matches all tools from the `memory` server
* `mcp__brave-search__.*` matches all tools from a server whose name contains a hyphen
* `mcp__.*__write.*` matches any tool whose name starts with `write` from any server

Hyphens in the exact-match set require Claude Code v2.1.195 or later. On earlier versions a bare hyphenated prefix like `mcp__brave-search` is evaluated as an unanchored regular expression and matches every tool from that server. The `mcp__brave-search__.*` form works on every version.

This example logs all memory server operations and validates write operations from any MCP server:

```json theme={null}
{
  "hooks": {
    "PreToolUse": [
      {
        "matcher": "mcp__memory__.*",
        "hooks": [
          {
            "type": "command",
            "command": "echo 'Memory operation initiated' >> ~/mcp-operations.log"
          }
        ]
      },
      {
        "matcher": "mcp__.*__write.*",
        "hooks": [
          {
            "type": "command",
            "command": "/home/user/scripts/validate-mcp-write.py"
          }
        ]
      }
    ]
  }
}
```

### Hook handler fields

Each object in the inner `hooks` array is a hook handler: the shell command, HTTP endpoint, MCP tool, LLM prompt, or agent that runs when the matcher matches. There are five types:

* **[Command hooks](#command-hook-fields)** (`type: "command"`): run a shell command. Your script receives the event's [JSON input](#hook-input-and-output) on stdin and communicates results back through exit codes and stdout.
* **[HTTP hooks](#http-hook-fields)** (`type: "http"`): send the event's JSON input as an HTTP POST request to a URL. The endpoint communicates results back through the response body using the same [JSON output format](#json-output) as command hooks.
* **[MCP tool hooks](#mcp-tool-hook-fields)** (`type: "mcp_tool"`): call a tool on an already-connected [MCP server](/en/mcp). The tool's text output is treated like command-hook stdout.
* **[Prompt hooks](#prompt-and-agent-hook-fields)** (`type: "prompt"`): send a prompt to a Claude model for single-turn evaluation. The model returns a yes/no decision as JSON. See [Prompt-based hooks](#prompt-based-hooks).
* **[Agent hooks](#prompt-and-agent-hook-fields)** (`type: "agent"`): spawn a subagent that can use tools like Read, Grep, and Glob to verify conditions before returning a decision. Agent hooks are experimental and may change. See [Agent-based hooks](#agent-based-hooks).

All matching hooks run in parallel, and identical handlers are deduplicated automatically. Command hooks are deduplicated by command string and `args`, and HTTP hooks are deduplicated by URL.

Handlers run in the current directory with Claude Code's environment. The `$CLAUDE_CODE_REMOTE` environment variable is set to `"true"` in remote web environments and not set in the local CLI.

#### Common fields

These fields apply to all hook types:

| Field           | Required | Description                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                     |
| :-------------- | :------- | :---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `type`          | yes      | `"command"`, `"http"`, `"mcp_tool"`, `"prompt"`, or `"agent"`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                   |
| `if`            | no       | Permission rule syntax to filter when this hook runs, such as `"Bash(git *)"` or `"Edit(*.ts)"`. The hook command only runs if the tool call matches the pattern. See the [Bash matching table](#bash-if-matching) below for how Bash patterns evaluate against subcommands, `$()`, and backticks. Only evaluated on tool events: `PreToolUse`, `PostToolUse`, `PostToolUseFailure`, `PermissionRequest`, and `PermissionDenied`. On other events, a hook with `if` set never runs. Uses the same syntax as [permission rules](/en/permissions) |
| `timeout`       | no       | Seconds before canceling. Defaults: 600 for `command`, `http`, and `mcp_tool`; 30 for `prompt`; 60 for `agent`. [`UserPromptSubmit`](#userpromptsubmit) lowers the `command`, `http`, and `mcp_tool` default to 30, and [`MessageDisplay`](#messagedisplay) lowers it to 10                                                                                                                                                                                                                                                                     |
| `statusMessage` | no       | Custom spinner message displayed while the hook runs                                                                                                                                                                                                                                                                                                                                                                                                                                                                                            |
| `once`          | no       | If `true`, runs once per session then is removed. Only honored for hooks declared in [skill frontmatter](#hooks-in-skills-and-agents); ignored in settings files and agent frontmatter                                                                                                                                                                                                                                                                                                                                                          |

The `if` field holds exactly one permission rule. There is no `&&`, `||`, or list syntax for combining rules; to apply multiple conditions, define a separate hook handler for each.

<span id="bash-if-matching" />For Bash patterns, whether your hook command runs depends on the shape of the pattern and the Bash command Claude is invoking. Leading `VAR=value` assignments are stripped before matching.

| `if` pattern       | Bash command           | Hook runs? | Why                                                                                                 |
| :----------------- | :--------------------- | :--------- | :-------------------------------------------------------------------------------------------------- |
| `Bash(git *)`      | `FOO=bar git push`     | yes        | leading assignments are stripped; `git push` matches                                                |
| `Bash(git *)`      | `npm test && git push` | yes        | each subcommand is checked; `git push` matches                                                      |
| `Bash(rm *)`       | `echo $(rm -rf /)`     | yes        | commands inside `$()` and backticks are checked; `rm -rf /` matches                                 |
| `Bash(rm *)`       | `echo $(date)`         | no         | no subcommand matches `rm *`                                                                        |
| `Bash(git push *)` | `echo $(date)`         | yes        | patterns that specify more than the command name run the hook anyway on `$()`, backticks, or `$VAR` |

The filter also fails open, running your hook regardless of pattern, when the Bash command cannot be parsed. Because the `if` filter is best-effort, use the [permission system](/en/permissions) rather than a hook to enforce a hard allow or deny.

#### Command hook fields

In addition to the [common fields](#common-fields), command hooks accept these fields:

| Field         | Required | Description                                                                                                                                                                                                                                                                                                                                  |
| :------------ | :------- | :------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `command`     | yes      | Shell command to execute. With `args`, the executable to spawn directly. See [Exec form and shell form](#exec-form-and-shell-form)                                                                                                                                                                                                           |
| `args`        | no       | Argument list. When present, `command` is resolved as an executable and spawned directly with `args` as the argument vector, with no shell involved. See [Exec form and shell form](#exec-form-and-shell-form)                                                                                                                               |
| `async`       | no       | If `true`, runs in the background without blocking. See [Run hooks in the background](#run-hooks-in-the-background)                                                                                                                                                                                                                          |
| `asyncRewake` | no       | If `true`, runs in the background and wakes Claude on exit code 2. Implies `async`. The hook's stderr, or stdout if stderr is empty, is shown to Claude as a system reminder so it can react to a long-running background failure                                                                                                            |
| `shell`       | no       | Shell to use for this hook. Accepts `"bash"` or `"powershell"`. Defaults to `"bash"`, or to `"powershell"` on Windows when Git Bash isn't installed. Setting `"powershell"` runs the command via PowerShell on Windows. Does not require `CLAUDE_CODE_USE_POWERSHELL_TOOL` since hooks spawn PowerShell directly. Ignored when `args` is set |

<a id="exec-form-and-shell-form" />

##### Exec form and shell form

A command hook runs as exec form when `args` is set, and shell form when `args` is omitted. Set `args` whenever the hook references a [path placeholder](#reference-scripts-by-path), since each element is passed as one argument with no quoting. Omit `args` when you need shell features like pipes or `&&`, or when neither concern applies.

**Exec form** runs when `args` is present. Claude Code resolves `command` as an executable on `PATH` and spawns it directly with `args` as the argument vector. There is no shell, so each `args` element is one argument exactly as written, and path placeholders like `${CLAUDE_PLUGIN_ROOT}` are substituted into `command` and into each `args` element as plain strings. Special characters such as apostrophes, `$`, and backticks pass through verbatim because there is no shell to interpret them. No shell tokenization happens on any platform.

**Shell form** runs when `args` is absent. The `command` string is passed to a shell: `sh -c` on macOS and Linux, Git Bash on Windows, or PowerShell when Git Bash isn't installed. Set the `shell` field to choose explicitly. The shell tokenizes the string, expands variables, and interprets pipes, `&&`, redirects, and globs.

<Note>
  On Windows, exec form requires `command` to resolve to a real executable such as a `.exe`. The `.cmd` and `.bat` shims that npm, npx, eslint, and other tools install in `node_modules/.bin` are not executables and cannot be spawned without a shell. To run them in exec form, invoke the underlying script with `node` directly, for example `"command": "node", "args": ["${CLAUDE_PLUGIN_ROOT}/node_modules/eslint/bin/eslint.js"]`. The `node` plus script-path pattern works on every platform because `node.exe` is a real binary. To run a `.cmd` or `.bat` shim by name, use shell form.
</Note>

This example runs a Node script bundled with a plugin. Exec form passes the resolved script path as one argument with no quoting:

```json theme={null}
{
  "type": "command",
  "command": "node",
  "args": ["${CLAUDE_PLUGIN_ROOT}/scripts/format.js", "--fix"]
}
```

The equivalent shell form needs quoting to handle paths with spaces or special characters:

```json theme={null}
{
  "type": "command",
  "command": "node \"${CLAUDE_PLUGIN_ROOT}\"/scripts/format.js --fix"
}
```

Both forms support the same [path placeholders](#reference-scripts-by-path), and both export them as the environment variables `CLAUDE_PROJECT_DIR`, `CLAUDE_PLUGIN_ROOT`, and `CLAUDE_PLUGIN_DATA` on the spawned process, so a script can read `process.env.CLAUDE_PLUGIN_ROOT` regardless of how it was launched. Plugin hooks additionally substitute `${user_config.*}` values; see [User configuration](/en/plugins-reference#user-configuration).

<Note>
  In exec form, `command` is the executable name or path only. If `command` is a bare name with no path separator and contains whitespace alongside `args`, Claude Code logs a warning because the spawn will fail: there is no executable named `node script.js`. Move the extra tokens into `args`. Absolute paths with spaces, such as `C:\Program Files\nodejs\node.exe`, are a single valid executable and do not trigger the warning.
</Note>

#### HTTP hook fields

In addition to the [common fields](#common-fields), HTTP hooks accept these fields:

| Field            | Required | Description                                                                                                                                                                                      |
| :--------------- | :------- | :----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `url`            | yes      | URL to send the POST request to                                                                                                                                                                  |
| `headers`        | no       | Additional HTTP headers as key-value pairs. Values support environment variable interpolation using `$VAR_NAME` or `${VAR_NAME}` syntax. Only variables listed in `allowedEnvVars` are resolved  |
| `allowedEnvVars` | no       | List of environment variable names that may be interpolated into header values. References to unlisted variables are replaced with empty strings. Required for any env var interpolation to work |

Claude Code sends the hook's [JSON input](#hook-input-and-output) as the POST request body with `Content-Type: application/json`. The response body uses the same [JSON output format](#json-output) as command hooks.

Error handling differs from command hooks: non-2xx responses, connection failures, and timeouts all produce non-blocking errors that allow execution to continue. To block a tool call or deny a permission, return a 2xx response with a JSON body containing `decision: "block"` or a `hookSpecificOutput` with `permissionDecision: "deny"`.

This example sends `PreToolUse` events to a local validation service, authenticating with a token from the `MY_TOKEN` environment variable:

```json theme={null}
{
  "hooks": {
    "PreToolUse": [
      {
        "matcher": "Bash",
        "hooks": [
          {
            "type": "http",
            "url": "http://localhost:8080/hooks/pre-tool-use",
            "timeout": 30,
            "headers": {
              "Authorization": "Bearer $MY_TOKEN"
            },
            "allowedEnvVars": ["MY_TOKEN"]
          }
        ]
      }
    ]
  }
}
```

#### MCP tool hook fields

In addition to the [common fields](#common-fields), MCP tool hooks accept these fields:

| Field    | Required | Description                                                                                                                                                          |
| :------- | :------- | :------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `server` | yes      | Name of a configured MCP server. The server must already be connected; the hook never triggers an OAuth or connection flow                                           |
| `tool`   | yes      | Name of the tool to call on that server                                                                                                                              |
| `input`  | no       | Arguments passed to the tool. String values support `${path}` substitution from the hook's [JSON input](#hook-input-and-output), such as `"${tool_input.file_path}"` |

The tool's text content is treated like command-hook stdout: if it parses as valid [JSON output](#json-output) it is processed as a decision, otherwise it is shown as plain text. If the named server is not connected, or the tool returns `isError: true`, the hook produces a non-blocking error and execution continues.

MCP tool hooks are available on every hook event once Claude Code has connected to your MCP servers. `SessionStart` and `Setup` typically fire before servers finish connecting, so hooks on those events should expect the "not connected" error on first run.

This example calls the `security_scan` tool on the `my_server` MCP server after each `Write` or `Edit`, passing the edited file's path:

```json theme={null}
{
  "hooks": {
    "PostToolUse": [
      {
        "matcher": "Write|Edit",
        "hooks": [
          {
            "type": "mcp_tool",
            "server": "my_server",
            "tool": "security_scan",
            "input": { "file_path": "${tool_input.file_path}" }
          }
        ]
      }
    ]
  }
}
```

#### Prompt and agent hook fields

In addition to the [common fields](#common-fields), prompt and agent hooks accept these fields:

| Field    | Required | Description                                                                                                                                                               |
| :------- | :------- | :------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `prompt` | yes      | Prompt text to send to the model. Use `$ARGUMENTS` as a placeholder for the hook input JSON. Escape with a backslash to include literal text: `\$1.00` renders as `$1.00` |
| `model`  | no       | Model to use for evaluation. Defaults to a fast model                                                                                                                     |

### Reference scripts by path

Use these placeholders to reference hook scripts relative to the project or plugin root, regardless of the working directory when the hook runs:

* `${CLAUDE_PROJECT_DIR}`: the project root. Claude Code also sets this variable in the environment of [stdio MCP servers](/en/mcp#option-3-add-a-local-stdio-server) and plugin LSP servers.
* `${CLAUDE_PLUGIN_ROOT}`: the plugin's installation directory, for scripts bundled with a [plugin](/en/plugins). Changes on each plugin update.
* `${CLAUDE_PLUGIN_DATA}`: the plugin's [persistent data directory](/en/plugins-reference#persistent-data-directory), for dependencies and state that should survive plugin updates.

Prefer [exec form](#exec-form-and-shell-form) for any hook that references a path placeholder. Exec form passes each `args` element as one argument with no shell tokenization, so paths with spaces or special characters need no quoting. In shell form, wrap each placeholder in double quotes.

<Tabs>
  <Tab title="Project scripts">
    This example uses `${CLAUDE_PROJECT_DIR}` to run a style checker from the project's `.claude/hooks/` directory after any `Write` or `Edit` tool call:

    ```json theme={null}
    {
      "hooks": {
        "PostToolUse": [
          {
            "matcher": "Write|Edit",
            "hooks": [
              {
                "type": "command",
                "command": "${CLAUDE_PROJECT_DIR}/.claude/hooks/check-style.sh",
                "args": []
              }
            ]
          }
        ]
      }
    }
    ```
  </Tab>

  <Tab title="Plugin scripts">
    Define plugin hooks in `hooks/hooks.json` with an optional top-level `description` field. When a plugin is enabled, its hooks merge with your user and project hooks.

    This example runs a formatting script bundled with the plugin:

    ```json theme={null}
    {
      "description": "Automatic code formatting",
      "hooks": {
        "PostToolUse": [
          {
            "matcher": "Write|Edit",
            "hooks": [
              {
                "type": "command",
                "command": "${CLAUDE_PLUGIN_ROOT}/scripts/format.sh",
                "args": [],
                "timeout": 30
              }
            ]
          }
        ]
      }
    }
    ```

    See the [plugin components reference](/en/plugins-reference#hooks) for details on creating plugin hooks.
  </Tab>
</Tabs>

### Hooks in skills and agents

In addition to settings files and plugins, hooks can be defined directly in [skills](/en/skills) and [subagents](/en/sub-agents) using frontmatter. These hooks are scoped to the component's lifecycle and only run when that component is active.

All hook events are supported. For subagents, `Stop` hooks are automatically converted to `SubagentStop` since that is the event that fires when a subagent completes.

Hooks use the same configuration format as settings-based hooks but are scoped to the component's lifetime and cleaned up when it finishes.

This skill defines a `PreToolUse` hook that runs a security validation script before each `Bash` command:

```yaml theme={null}
---
name: secure-operations
description: Perform operations with security checks
hooks:
  PreToolUse:
    - matcher: "Bash"
      hooks:
        - type: command
          command: "./scripts/security-check.sh"
---
```

Agents use the same format in their YAML frontmatter.

### The `/hooks` menu

Type `/hooks` in Claude Code to open a read-only browser for your configured hooks. The menu shows every hook event with a count of configured hooks, lets you drill into matchers, and shows the full details of each hook handler. Use it to verify configuration, check which settings file a hook came from, or inspect a hook's command, prompt, or URL.

The menu displays all five hook types: `command`, `prompt`, `agent`, `http`, and `mcp_tool`. Each hook is labeled with a `[type]` prefix and a source indicating where it was defined:

* `User`: from `~/.claude/settings.json`
* `Project`: from `.claude/settings.json`
* `Local`: from `.claude/settings.local.json`
* `Plugin`: from a plugin's `hooks/hooks.json`
* `Session`: registered in memory for the current session
* `Built-in`: registered internally by Claude Code

Selecting a hook opens a detail view showing its event, matcher, type, source file, and the full command, prompt, or URL. The menu is read-only: to add, modify, or remove hooks, edit the settings JSON directly or ask Claude to make the change.

### Disable or remove hooks

To remove a hook, delete its entry from the settings JSON file.

To temporarily disable all hooks without removing them, set `"disableAllHooks": true` in your settings file. There is no way to disable an individual hook while keeping it in the configuration.

The `disableAllHooks` setting respects the managed settings hierarchy. If an administrator has configured hooks through managed policy settings, `disableAllHooks` set in user, project, or local settings cannot disable those managed hooks. Only `disableAllHooks` set at the managed settings level can disable managed hooks.

Direct edits to hooks in settings files are normally picked up automatically by the file watcher.

## Hook input and output

Command hooks receive JSON data via stdin and communicate results through exit codes, stdout, and stderr. HTTP hooks receive the same JSON as the POST request body and communicate results through the HTTP response body. This section covers fields and behavior common to all events. Each event's section under [Hook events](#hook-events) includes its specific input schema and decision control options.

On macOS and Linux, command hooks run in their own session without a controlling terminal as of v2.1.139. The hook process and any child processes cannot open `/dev/tty` or send escape sequences directly to the Claude Code interface. Windows has no `/dev/tty`. To surface a message to the user on any platform, return [`systemMessage`](#json-output) in JSON output. To trigger a desktop notification, set a window title, or ring the bell, return [`terminalSequence`](#emit-terminal-notifications) instead.

### Common input fields

Hook events receive these fields as JSON, in addition to event-specific fields documented in each [hook event](#hook-events) section. For command hooks, this JSON arrives via stdin. For HTTP hooks, it arrives as the POST request body.

| Field             | Description                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                      |
| :---------------- | :----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `session_id`      | Current session identifier                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                       |
| `prompt_id`       | UUID identifying the user prompt currently being processed. Matches the [`prompt.id` attribute on OpenTelemetry events](/en/monitoring-usage#event-correlation-attributes), so you can correlate hook output with telemetry for a single prompt. Absent until the first user input. {/* min-version: 2.1.196 */}Requires Claude Code v2.1.196 or later                                                                                                                                                                                                                                                                                                                                                                                           |
| `transcript_path` | Path to conversation JSON                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                        |
| `cwd`             | Current working directory when the hook is invoked                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                               |
| `permission_mode` | Current [permission mode](/en/permissions#permission-modes): `"default"`, `"plan"`, `"acceptEdits"`, `"auto"`, `"dontAsk"`, or `"bypassPermissions"`. Not all events receive this field: see each event's JSON example below to check                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                            |
| `effort`          | Object with a `level` field holding the active [effort level](/en/model-config#adjust-effort-level) for the turn: `"low"`, `"medium"`, `"high"`, `"xhigh"`, or `"max"`. If the requested model effort exceeds what the current model supports, this is the downgraded level the model actually used. Ultracode is not a distinct level and reports as `"xhigh"`. The object matches the [status line](/en/statusline#available-data) `effort` field. Present for events that fire within a tool-use context, such as `PreToolUse`, `PostToolUse`, `Stop`, and `SubagentStop`, when the current model supports the effort parameter. The level is also available to hook commands and the Bash tool as the `$CLAUDE_EFFORT` environment variable. |
| `hook_event_name` | Name of the event that fired                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                     |

When running with `--agent` or inside a subagent, two additional fields are included:

| Field        | Description                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                            |
| :----------- | :----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `agent_id`   | Unique identifier for the subagent. Present only when the hook fires inside a subagent call. Use this to distinguish subagent hook calls from main-thread calls.                                                                                                                                                                                                                                                                                                                                                                                                                                       |
| `agent_type` | Agent name (for example, `"Explore"` or `"security-reviewer"`). Present when the session uses `--agent` or the hook fires inside a subagent. For subagents, the subagent's type takes precedence over the session's `--agent` value. For [custom subagents](/en/sub-agents), this is the `name` field from the agent's frontmatter, not the filename. For subagents shipped by a [plugin](/en/plugins), this is the plugin-scoped identifier such as `my-plugin:reviewer`, not the bare frontmatter name. See [SubagentStart](#subagentstart) for how to write a matcher against a plugin-scoped name. |

Only [`SessionStart`](#sessionstart) hooks can receive a `model` field, and it is not guaranteed to be present. There is no `$CLAUDE_MODEL` environment variable. A hook process inherits the parent environment, so it can read `$ANTHROPIC_MODEL` if you set it in your shell, but that value does not change when you switch models with `/model` during a session.

For example, a `PreToolUse` hook for a Bash command receives this on stdin:

```json theme={null}
{
  "session_id": "abc123",
  "prompt_id": "550e8400-e29b-41d4-a716-446655440000",
  "transcript_path": "/home/user/.claude/projects/.../transcript.jsonl",
  "cwd": "/home/user/my-project",
  "permission_mode": "default",
  "hook_event_name": "PreToolUse",
  "tool_name": "Bash",
  "tool_input": {
    "command": "npm test"
  }
}
```

The `tool_name` and `tool_input` fields are event-specific. Each [hook event](#hook-events) section documents the additional fields for that event.

### Exit code output

The exit code from your hook command tells Claude Code whether the action should proceed, be blocked, or be ignored.

**Exit 0** means success. Claude Code parses stdout for [JSON output fields](#json-output). JSON output is only processed on exit 0. For most events, stdout is written to the debug log but not shown in the transcript. The exceptions are `UserPromptSubmit`, `UserPromptExpansion`, and `SessionStart`, where stdout is added as context that Claude can see and act on.

**Exit 2** means a blocking error. Claude Code ignores stdout and any JSON in it. Instead, stderr text is fed back to Claude as an error message. The effect depends on the event: `PreToolUse` blocks the tool call, `UserPromptSubmit` rejects the prompt, and so on. See [exit code 2 behavior](#exit-code-2-behavior-per-event) for the full list.

**Any other exit code** is a non-blocking error for most hook events. The transcript shows a `<hook name> hook error` notice followed by the first line of stderr, so you can identify the cause without `--debug`. Execution continues and the full stderr is written to the debug log.

For example, a hook command script that blocks dangerous Bash commands:

```bash theme={null}
#!/bin/bash
# Reads JSON input from stdin, checks the command
command=$(jq -r '.tool_input.command' < /dev/stdin)

if [[ "$command" == rm* ]]; then
  echo "Blocked: rm commands are not allowed" >&2
  exit 2  # Blocking error: tool call is prevented
fi

exit 0  # No decision: the normal permission flow applies
```

<Warning>
  For most hook events, only exit code 2 blocks the action. Claude Code treats exit code 1 as a non-blocking error and proceeds with the action, even though 1 is the conventional Unix failure code. If your hook is meant to enforce a policy, use `exit 2`. The exception is `WorktreeCreate`, where any non-zero exit code aborts worktree creation.
</Warning>

#### Exit code 2 behavior per event

Exit code 2 is the way a hook signals "stop, don't do this." The effect depends on the event, because some events represent actions that can be blocked (like a tool call that hasn't happened yet) and others represent things that already happened or can't be prevented.

| Hook event            | Can block? | What happens on exit 2                                                                                                               |
| :-------------------- | :--------- | :----------------------------------------------------------------------------------------------------------------------------------- |
| `PreToolUse`          | Yes        | Blocks the tool call                                                                                                                 |
| `PermissionRequest`   | Yes        | Denies the permission                                                                                                                |
| `UserPromptSubmit`    | Yes        | Blocks prompt processing and erases the prompt                                                                                       |
| `UserPromptExpansion` | Yes        | Blocks the expansion                                                                                                                 |
| `Stop`                | Yes        | Prevents Claude from stopping, continues the conversation                                                                            |
| `SubagentStop`        | Yes        | Prevents the subagent from stopping                                                                                                  |
| `TeammateIdle`        | Yes        | Prevents the teammate from going idle (teammate continues working)                                                                   |
| `TaskCreated`         | Yes        | Rolls back the task creation                                                                                                         |
| `TaskCompleted`       | Yes        | Prevents the task from being marked as completed                                                                                     |
| `ConfigChange`        | Yes        | Blocks the configuration change from taking effect (except `policy_settings`)                                                        |
| `StopFailure`         | No         | Output and exit code are ignored                                                                                                     |
| `PostToolUse`         | No         | Shows stderr to Claude (tool already ran)                                                                                            |
| `PostToolUseFailure`  | No         | Shows stderr to Claude (tool already failed)                                                                                         |
| `PostToolBatch`       | Yes        | Stops the agentic loop before the next model call                                                                                    |
| `PermissionDenied`    | No         | Exit code and stderr are ignored (denial already occurred). Use JSON `hookSpecificOutput.retry: true` to tell the model it may retry |
| `Notification`        | No         | Shows stderr to user only                                                                                                            |
| `SubagentStart`       | No         | Shows stderr to user only                                                                                                            |
| `SessionStart`        | No         | Shows stderr to user only                                                                                                            |
| `Setup`               | No         | Shows stderr to user only                                                                                                            |
| `SessionEnd`          | No         | Shows stderr to user only                                                                                                            |
| `CwdChanged`          | No         | Shows stderr to user only                                                                                                            |
| `FileChanged`         | No         | Shows stderr to user only                                                                                                            |
| `PreCompact`          | Yes        | Blocks compaction                                                                                                                    |
| `PostCompact`         | No         | Shows stderr to user only                                                                                                            |
| `Elicitation`         | Yes        | Denies the elicitation                                                                                                               |
| `ElicitationResult`   | Yes        | Blocks the response (action becomes decline)                                                                                         |
| `WorktreeCreate`      | Yes        | Any non-zero exit code causes worktree creation to fail                                                                              |
| `WorktreeRemove`      | No         | Failures are logged in debug mode only                                                                                               |
| `InstructionsLoaded`  | No         | Exit code is ignored                                                                                                                 |
| `MessageDisplay`      | No         | The original text is displayed                                                                                                       |

### HTTP response handling

HTTP hooks use HTTP status codes and response bodies instead of exit codes and stdout:

* **2xx with an empty body**: success, equivalent to exit code 0 with no output
* **2xx with a plain text body**: success, the text is added as context
* **2xx with a JSON body**: success, parsed using the same [JSON output](#json-output) schema as command hooks
* **Non-2xx status**: non-blocking error, execution continues
* **Connection failure or timeout**: non-blocking error, execution continues

Unlike command hooks, HTTP hooks cannot signal a blocking error through status codes alone. To block a tool call or deny a permission, return a 2xx response with a JSON body containing the appropriate decision fields.

### JSON output

Exit codes only let you block or stay silent, but JSON output gives you finer-grained control. Instead of exiting with code 2 to block, exit 0 and print a JSON object to stdout. Claude Code reads specific fields from that JSON to control behavior, including [decision control](#decision-control) for blocking, allowing, or escalating to the user.

<Note>
  You must choose one approach per hook, not both: either use exit codes alone for signaling, or exit 0 and print JSON for structured control. Claude Code only processes JSON on exit 0. If you exit 2, any JSON is ignored.
</Note>

Your hook's stdout must contain only the JSON object. If your shell profile prints text on startup, it can interfere with JSON parsing. See [JSON validation failed](/en/hooks-guide#json-validation-failed) in the troubleshooting guide.

Hook output strings, including `additionalContext`, `systemMessage`, and plain stdout, are capped at 10,000 characters. Output that exceeds this limit is saved to a file and replaced with a preview and file path, the same way large tool results are handled.

The JSON object supports three kinds of fields:

* **Universal fields** like `continue` work across all events. These are listed in the table below.
* **Top-level `decision` and `reason`** are used by some events to block or provide feedback.
* **`hookSpecificOutput`** is a nested object for events that need richer control. It requires a `hookEventName` field set to the event name.

| Field              | Default | Description                                                                                                                                                                                                                                                                                                                          |
| :----------------- | :------ | :----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `continue`         | `true`  | If `false`, Claude stops processing entirely after the hook runs. Takes precedence over any event-specific decision fields                                                                                                                                                                                                           |
| `stopReason`       | none    | Message shown to the user when `continue` is `false`. Not shown to Claude                                                                                                                                                                                                                                                            |
| `suppressOutput`   | `false` | If `true`, hides the hook's stdout from the transcript. Stdout still appears in the debug log                                                                                                                                                                                                                                        |
| `systemMessage`    | none    | Warning message shown to the user                                                                                                                                                                                                                                                                                                    |
| `terminalSequence` | none    | A terminal escape sequence for Claude Code to emit on your behalf, such as a desktop notification, window title, or bell. Restricted to OSC `0`/`1`/`2`/`9`/`99`/`777` and BEL. If the value contains anything outside the allowlist, the field is ignored. Use this instead of writing to `/dev/tty`, which is unavailable to hooks |

To stop Claude entirely regardless of event type:

```json theme={null}
{ "continue": false, "stopReason": "Build failed, fix errors before continuing" }
```

#### Emit terminal notifications

The `terminalSequence` field requires Claude Code v2.1.141 or later.

Hooks run without a controlling terminal, so writing escape sequences directly to `/dev/tty` fails. Instead, return the escape sequence in the `terminalSequence` field and Claude Code emits it for you through its own terminal write path. This is race-free, works inside tmux and GNU screen, and works on Windows where there is no `/dev/tty`.

The field accepts a string of one or more allowlisted escape sequences:

* OSC `0`, `1`, `2`: window and icon titles
* OSC `9`: iTerm2, ConEmu, Windows Terminal, and WezTerm notifications, including `9;4` taskbar progress
* OSC `99`: Kitty notifications
* OSC `777`: urxvt, Ghostty, and Warp notifications
* Bare BEL

Sequences may be terminated with BEL or with ST. Anything outside the allowlist, including CSI cursor and color sequences, OSC palette sequences, OSC 8 hyperlinks, OSC 52 clipboard writes, and OSC 1337, is rejected and the field is ignored.

The example below fires a desktop notification from a `Notification` hook. The escape sequence is built with `printf` octal escapes so the control bytes never appear on the shell command line, and `jq -n --arg` builds the JSON output so quotes, backslashes, and newlines in the notification message are escaped correctly:

```bash theme={null}
#!/bin/bash
# Notification hook: ping the desktop when Claude Code needs attention.
input=$(cat)
title="Claude Code"
body=$(jq -r '.message // "Needs your attention"' <<<"$input")
seq=$(printf '\033]777;notify;%s;%s\007' "$title" "$body")
jq -nc --arg seq "$seq" '{terminalSequence: $seq}'
```

The `{ "terminalSequence": "..." }` shape is the same from any shell or language. On Windows, build the escape string in PowerShell or a script and emit the same JSON object.

<Note>
  `terminalSequence` is the supported replacement for hooks that previously wrote escape sequences directly to `/dev/tty`. The allowlist is restricted to sequences that cannot move the cursor or alter colors, so a hook can never corrupt an on-screen prompt.
</Note>

#### Add context for Claude

The `additionalContext` field passes a string from your hook into Claude's context window. Claude Code wraps the string in a system reminder and inserts it into the conversation at the point where the hook fired. Claude reads the reminder on the next model request, but it does not appear as a chat message in the interface.

Return `additionalContext` inside `hookSpecificOutput` alongside the event name:

```json theme={null}
{
  "hookSpecificOutput": {
    "hookEventName": "PostToolUse",
    "additionalContext": "This file is generated. Edit src/schema.ts and run `bun generate` instead."
  }
}
```

Where the reminder appears depends on the event:

* [SessionStart](#sessionstart), [Setup](#setup), and [SubagentStart](#subagentstart): at the start of the conversation, before the first prompt
* [UserPromptSubmit](#userpromptsubmit) and [UserPromptExpansion](#userpromptexpansion): alongside the submitted prompt
* [PreToolUse](#pretooluse), [PostToolUse](#posttooluse), [PostToolUseFailure](#posttoolusefailure), and [PostToolBatch](#posttoolbatch): next to the tool result
* [Stop](#stop) and [SubagentStop](#subagentstop): at the end of the turn. The conversation continues so Claude can act on the feedback. See [Stop decision control](#stop-decision-control)

When several hooks return `additionalContext` for the same event, Claude receives all of the values. If a value exceeds 10,000 characters, Claude Code writes the full text to a file in the session directory and passes Claude the file path with a short preview instead.

Use `additionalContext` for information Claude should know about the current state of your environment or the operation that just ran:

* **Environment state**: the current branch, deployment target, or active feature flags
* **Conditional project rules**: which test command applies to the file just edited, which directories are read-only in this worktree
* **External data**: open issues assigned to you, recent CI results, content fetched from an internal service

For instructions that never change, prefer [CLAUDE.md](/en/memory). It loads without running a script and is the standard place for static project conventions.

Write the text as factual statements rather than imperative system instructions. Phrasing such as "The deployment target is production" or "This repo uses `bun test`" reads as project information. Text framed as out-of-band system commands can trigger Claude's prompt-injection defenses, which causes Claude to surface the text to you instead of treating it as context.

Once injected, the text is saved in the session transcript. For mid-session events like `PostToolUse` or `UserPromptSubmit`, resuming with `--continue` or `--resume` replays the saved text rather than re-running the hook for past turns, so values like timestamps or commit SHAs become stale on resume. `SessionStart` hooks run again on resume with `source` set to `"resume"`, so they can refresh their context.

#### Decision control

Not every event supports blocking or controlling behavior through JSON. The events that do each use a different set of fields to express that decision. Use this table as a quick reference before writing a hook:

| Events                                                                                                                              | Decision pattern               | Key fields                                                                                                                                                                                                                          |
| :---------------------------------------------------------------------------------------------------------------------------------- | :----------------------------- | :---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| UserPromptSubmit, UserPromptExpansion, PostToolUse, PostToolUseFailure, PostToolBatch, Stop, SubagentStop, ConfigChange, PreCompact | Top-level `decision`           | `decision: "block"`, `reason`. Stop and SubagentStop also accept `hookSpecificOutput.additionalContext` for [non-error feedback that continues the conversation](#stop-decision-control)                                            |
| TeammateIdle, TaskCreated, TaskCompleted                                                                                            | Exit code or `continue: false` | Exit code 2 blocks the action with stderr feedback. JSON `{"continue": false, "stopReason": "..."}` also stops the teammate entirely, matching `Stop` hook behavior                                                                 |
| PreToolUse                                                                                                                          | `hookSpecificOutput`           | `permissionDecision` (allow/deny/ask/defer), `permissionDecisionReason`                                                                                                                                                             |
| PermissionRequest                                                                                                                   | `hookSpecificOutput`           | `decision.behavior` (allow/deny)                                                                                                                                                                                                    |
| PermissionDenied                                                                                                                    | `hookSpecificOutput`           | `retry: true` tells the model it may retry the denied tool call                                                                                                                                                                     |
| WorktreeCreate                                                                                                                      | path return                    | Command hook prints path on stdout; HTTP hook returns `hookSpecificOutput.worktreePath`. Hook failure or missing path fails creation                                                                                                |
| Elicitation                                                                                                                         | `hookSpecificOutput`           | `action` (accept/decline/cancel), `content` (form field values for accept)                                                                                                                                                          |
| ElicitationResult                                                                                                                   | `hookSpecificOutput`           | `action` (accept/decline/cancel), `content` (form field values override)                                                                                                                                                            |
| MessageDisplay                                                                                                                      | `hookSpecificOutput`           | `displayContent` replaces the displayed text on screen. Display-only: the transcript and what Claude sees keep the original                                                                                                         |
| SessionStart, Setup, SubagentStart                                                                                                  | Context only                   | `hookSpecificOutput.additionalContext` adds context for Claude. SessionStart also accepts [`initialUserMessage`, `watchPaths`, `sessionTitle`, and `reloadSkills`](#sessionstart-decision-control). No blocking or decision control |
| WorktreeRemove, Notification, SessionEnd, PostCompact, InstructionsLoaded, StopFailure, CwdChanged, FileChanged                     | None                           | No decision control. Used for side effects like logging or cleanup                                                                                                                                                                  |

A few events can also rewrite content rather than only allow or block it:

* `PreToolUse`: `updatedInput` directly under `hookSpecificOutput` replaces a tool's arguments before it runs. See [PreToolUse decision control](#pretooluse-decision-control)
* `PermissionRequest`: `updatedInput` inside the `decision` object. See [PermissionRequest decision control](#permissionrequest-decision-control)
* `PostToolUse`: `updatedToolOutput` replaces the tool's result. See [PostToolUse decision control](#posttooluse-decision-control)
* `UserPromptSubmit`: cannot replace the prompt; it only injects `additionalContext` alongside it

For redaction or transformation use cases, intercept at `PreToolUse` for outbound tool inputs and `PostToolUse` for inbound tool results.

Here are examples of each pattern in action:

<Tabs>
  <Tab title="Top-level decision">
    Used by `UserPromptSubmit`, `UserPromptExpansion`, `PostToolUse`, `PostToolUseFailure`, `PostToolBatch`, `Stop`, `SubagentStop`, `ConfigChange`, and `PreCompact`. The only value is `"block"`. To allow the action to proceed, omit `decision` from your JSON, or exit 0 without any JSON at all:

    ```json theme={null}
    {
      "decision": "block",
      "reason": "Test suite must pass before proceeding"
    }
    ```
  </Tab>

  <Tab title="PreToolUse">
    Uses `hookSpecificOutput` for richer control: allow, deny, or escalate to the user. You can also modify tool input before it runs or inject additional context for Claude. See [PreToolUse decision control](#pretooluse-decision-control) for the full set of options.

    ```json theme={null}
    {
      "hookSpecificOutput": {
        "hookEventName": "PreToolUse",
        "permissionDecision": "deny",
        "permissionDecisionReason": "Database writes are not allowed"
      }
    }
    ```
  </Tab>

  <Tab title="PermissionRequest">
    Uses `hookSpecificOutput` to allow or deny a permission request on behalf of the user. When allowing, you can also modify the tool's input or apply permission rules so the user isn't prompted again. See [PermissionRequest decision control](#permissionrequest-decision-control) for the full set of options.

    ```json theme={null}
    {
      "hookSpecificOutput": {
        "hookEventName": "PermissionRequest",
        "decision": {
          "behavior": "allow",
          "updatedInput": {
            "command": "npm run lint"
          }
        }
      }
    }
    ```
  </Tab>
</Tabs>

For extended examples including Bash command validation, prompt filtering, and auto-approval scripts, see [What you can automate](/en/hooks-guide#what-you-can-automate) in the guide and the [Bash command validator reference implementation](https://github.com/anthropics/claude-code/blob/main/examples/hooks/bash_command_validator_example.py).

## Hook events

Each event corresponds to a point in Claude Code's lifecycle where hooks can run. The sections below are ordered to match the lifecycle: from session setup through the agentic loop to session end. Each section describes when the event fires, what matchers it supports, the JSON input it receives, and how to control behavior through output.

### SessionStart

Runs when Claude Code starts a new session or resumes an existing session. Useful for loading development context like existing issues or recent changes to your codebase, or setting up environment variables. For static context that does not require a script, use [CLAUDE.md](/en/memory) instead.

SessionStart runs on every session, so keep these hooks fast. Only `type: "command"` and `type: "mcp_tool"` hooks are supported.

The matcher value corresponds to how the session was initiated:

| Matcher   | When it fires                          |
| :-------- | :------------------------------------- |
| `startup` | New session                            |
| `resume`  | `--resume`, `--continue`, or `/resume` |
| `clear`   | `/clear`                               |
| `compact` | Auto or manual compaction              |

#### SessionStart input

In addition to the [common input fields](#common-input-fields), SessionStart hooks receive `source` and optionally `model`, `agent_type`, and `session_title`. The `source` field indicates how the session started: `"startup"` for new sessions, `"resume"` for resumed sessions, `"clear"` after `/clear`, or `"compact"` after compaction. The `model` field contains the active model identifier. It can be omitted, for example after `/clear` or when a session is restored through conversation recovery, so check for the field before reading it. If you start Claude Code with `claude --agent <name>`, an `agent_type` field contains the agent name. The `session_title` field carries the current session title if one is already set, for example via `--name` or `/rename`. A hook that emits `sessionTitle` can check `session_title` first to avoid overwriting a title the user set explicitly.

```json theme={null}
{
  "session_id": "abc123",
  "transcript_path": "/Users/.../.claude/projects/.../00893aaf-19fa-41d2-8238-13269b9b3ca0.jsonl",
  "cwd": "/Users/...",
  "hook_event_name": "SessionStart",
  "source": "startup",
  "model": "claude-sonnet-5"
}
```

#### SessionStart decision control

Any text your hook script prints to stdout is added as context for Claude. In addition to the [JSON output fields](#json-output) available to all hooks, you can return these event-specific fields:

| Field                | Description                                                                                                                                                                                                                                                                                                              |
| :------------------- | :----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `additionalContext`  | String added to Claude's context at the start of the conversation, before the first prompt. See [Add context for Claude](#add-context-for-claude) for how the text is delivered and what to put in it                                                                                                                    |
| `initialUserMessage` | String used as the first user message of the session. Applies in [non-interactive mode](/en/headless) (`-p`), where it becomes the first turn even if no prompt is provided. If a prompt is provided, it follows as the next turn. Unlike `additionalContext`, which attaches to an existing turn, this creates the turn |
| `sessionTitle`       | Sets the session title, with the same effect as `/rename`. Use to name sessions automatically from the launch folder, git branch, or worktree name. Applies only when `source` is `"startup"` or `"resume"`; ignored on `"clear"` and `"compact"`                                                                        |
| `watchPaths`         | Array of absolute paths to watch for [FileChanged](#filechanged) events during this session                                                                                                                                                                                                                              |
| `reloadSkills`       | Boolean. When `true`, Claude Code re-scans the [skill](/en/skills) and command directories after the SessionStart hooks complete, so skills the hook installed are available in the same session, starting with the first prompt                                                                                         |

```json theme={null}
{
  "hookSpecificOutput": {
    "hookEventName": "SessionStart",
    "additionalContext": "Current branch: feat/auth-refactor\nUncommitted changes: src/auth.ts, src/login.tsx\nActive issue: #4211 Migrate to OAuth2",
    "sessionTitle": "auth-refactor"
  }
}
```

Since plain stdout already reaches Claude for this event, a hook that only loads context can print to stdout directly without building JSON. Use the JSON form when you need to combine context with other fields such as `suppressOutput` or `sessionTitle`.

Use `reloadSkills` when a SessionStart hook installs or updates skills. Skill discovery normally runs before SessionStart hooks finish, so files the hook writes into `~/.claude/skills/` or `.claude/skills/` would otherwise only appear in the next session. This example syncs a shared skills repository and requests the re-scan:

```bash theme={null}
#!/bin/bash

git -C ~/.claude/skills/team-skills pull --quiet 2>/dev/null || \
  git clone --quiet https://git.example.com/your-org/team-skills.git ~/.claude/skills/team-skills

echo '{"hookSpecificOutput": {"hookEventName": "SessionStart", "reloadSkills": true}}'
```

#### Persist environment variables

SessionStart hooks have access to the `CLAUDE_ENV_FILE` environment variable, which provides a file path where you can persist environment variables for subsequent Bash commands.

To set individual environment variables, write `export` statements to `CLAUDE_ENV_FILE`. Use append (`>>`) to preserve variables set by other hooks:

```bash theme={null}
#!/bin/bash

if [ -n "$CLAUDE_ENV_FILE" ]; then
  echo 'export NODE_ENV=production' >> "$CLAUDE_ENV_FILE"
  echo 'export DEBUG_LOG=true' >> "$CLAUDE_ENV_FILE"
  echo 'export PATH="$PATH:./node_modules/.bin"' >> "$CLAUDE_ENV_FILE"
fi

exit 0
```

To capture all environment changes from setup commands, compare the exported variables before and after:

```bash theme={null}
#!/bin/bash

ENV_BEFORE=$(export -p | sort)

# Run your setup commands that modify the environment
source ~/.nvm/nvm.sh
nvm use 20

if [ -n "$CLAUDE_ENV_FILE" ]; then
  ENV_AFTER=$(export -p | sort)
  comm -13 <(echo "$ENV_BEFORE") <(echo "$ENV_AFTER") >> "$CLAUDE_ENV_FILE"
fi

exit 0
```

Any variables written to this file will be available in all subsequent Bash commands that Claude Code executes during the session.

<Note>
  `CLAUDE_ENV_FILE` is available for SessionStart, [Setup](#setup), [CwdChanged](#cwdchanged), and [FileChanged](#filechanged) hooks. Other hook types do not have access to this variable.
</Note>

### Setup

Fires only when you launch Claude Code with `--init-only`, or with `--init` or `--maintenance` in print mode (`-p`). It does not fire on normal startup. Use it for one-time dependency installation or scheduled cleanup that you trigger explicitly from CI or scripts, separate from normal session startup. For per-session initialization, use [SessionStart](#sessionstart) instead.

The matcher value corresponds to the CLI flag that triggered the hook:

| Matcher       | When it fires                              |
| :------------ | :----------------------------------------- |
| `init`        | `claude --init-only` or `claude -p --init` |
| `maintenance` | `claude -p --maintenance`                  |

`--init-only` runs Setup hooks and `SessionStart` hooks with the `startup` matcher, then exits without starting a conversation. `--init` and `--maintenance` fire Setup hooks only when combined with `-p` (print mode); in an interactive session those two flags do not currently fire Setup hooks.

Because Setup does not fire on every launch, a plugin that needs a dependency installed cannot rely on Setup alone. The practical pattern is to check for the dependency on first use and install on miss, for example a hook or skill that tests for `${CLAUDE_PLUGIN_DATA}/node_modules` and runs `npm install` if absent. See the [persistent data directory](/en/plugins-reference#persistent-data-directory) for where to store installed dependencies.

#### Setup input

In addition to the [common input fields](#common-input-fields), Setup hooks receive a `trigger` field set to either `"init"` or `"maintenance"`:

```json theme={null}
{
  "session_id": "abc123",
  "transcript_path": "/Users/.../.claude/projects/.../00893aaf-19fa-41d2-8238-13269b9b3ca0.jsonl",
  "cwd": "/Users/...",
  "hook_event_name": "Setup",
  "trigger": "init"
}
```

#### Setup decision control

Setup hooks cannot block. On exit code 2, stderr is shown to the user; on any other non-zero exit code, stderr appears only when you launch with `--verbose`. In both cases execution continues. To pass information into Claude's context, return `additionalContext` in JSON output; plain stdout is written to the debug log only. In addition to the [JSON output fields](#json-output) available to all hooks, you can return these event-specific fields:

| Field               | Description                                                               |
| :------------------ | :------------------------------------------------------------------------ |
| `additionalContext` | String added to Claude's context. Multiple hooks' values are concatenated |

```json theme={null}
{
  "hookSpecificOutput": {
    "hookEventName": "Setup",
    "additionalContext": "Dependencies installed: node_modules, .venv"
  }
}
```

Setup hooks have access to `CLAUDE_ENV_FILE`. Variables written to that file persist into subsequent Bash commands for the session, just as in [SessionStart hooks](#persist-environment-variables). Only `type: "command"` and `type: "mcp_tool"` hooks are supported.

### InstructionsLoaded

Fires when a `CLAUDE.md` or `.claude/rules/*.md` file is loaded into context. This event fires at session start for eagerly-loaded files and again later when files are lazily loaded, for example when Claude accesses a subdirectory that contains a nested `CLAUDE.md` or when conditional rules with `paths:` frontmatter match. The hook does not support blocking or decision control. It runs asynchronously for observability purposes.

The matcher runs against `load_reason`. For example, use `"matcher": "session_start"` to fire only for files loaded at session start, or `"matcher": "path_glob_match|nested_traversal"` to fire only for lazy loads.

#### InstructionsLoaded input

In addition to the [common input fields](#common-input-fields), InstructionsLoaded hooks receive these fields:

| Field               | Description                                                                                                                                                                                                   |
| :------------------ | :------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `file_path`         | Absolute path to the instruction file that was loaded                                                                                                                                                         |
| `memory_type`       | Scope of the file: `"User"`, `"Project"`, `"Local"`, or `"Managed"`                                                                                                                                           |
| `load_reason`       | Why the file was loaded: `"session_start"`, `"nested_traversal"`, `"path_glob_match"`, `"include"`, or `"compact"`. The `"compact"` value fires when instruction files are re-loaded after a compaction event |
| `globs`             | Path glob patterns from the file's `paths:` frontmatter, if any. Present only for `path_glob_match` loads                                                                                                     |
| `trigger_file_path` | Path to the file whose access triggered this load, for lazy loads                                                                                                                                             |
| `parent_file_path`  | Path to the parent instruction file that included this one, for `include` loads                                                                                                                               |

```json theme={null}
{
  "session_id": "abc123",
  "transcript_path": "/Users/.../.claude/projects/.../transcript.jsonl",
  "cwd": "/Users/my-project",
  "hook_event_name": "InstructionsLoaded",
  "file_path": "/Users/my-project/CLAUDE.md",
  "memory_type": "Project",
  "load_reason": "session_start"
}
```

#### InstructionsLoaded decision control

InstructionsLoaded hooks have no decision control. They cannot block or modify instruction loading. Use this event for audit logging, compliance tracking, or observability.

### UserPromptSubmit

Runs when the user submits a prompt, before Claude processes it. This allows you
to add additional context based on the prompt/conversation, validate prompts, or
block certain types of prompts.

`UserPromptSubmit` hooks have a default timeout of 30 seconds for `command`, `http`, and `mcp_tool` types, shorter than the 600-second default for those types on most other events. Because this hook runs before every prompt and blocks model processing until it completes, a stuck hook stalls the session. If your hook needs more time, set the `timeout` field in the hook entry.

A `UserPromptSubmit` hook that reaches its timeout is canceled and its output, including any `additionalContext`, is discarded. The prompt still reaches Claude without that context. As of v2.1.196, the transcript shows a notice naming the hook, the timeout that fired, and that the output was discarded. Earlier versions cancel the hook with no notice.

#### UserPromptSubmit input

In addition to the [common input fields](#common-input-fields), UserPromptSubmit hooks receive the `prompt` field containing the text the user submitted.

```json theme={null}
{
  "session_id": "abc123",
  "transcript_path": "/Users/.../.claude/projects/.../00893aaf-19fa-41d2-8238-13269b9b3ca0.jsonl",
  "cwd": "/Users/...",
  "permission_mode": "default",
  "hook_event_name": "UserPromptSubmit",
  "prompt": "Write a function to calculate the factorial of a number"
}
```

#### UserPromptSubmit decision control

`UserPromptSubmit` hooks can control whether a user prompt is processed and add context. All [JSON output fields](#json-output) are available.

There are two ways to add context to the conversation on exit code 0:

* **Plain text stdout**: any non-JSON text written to stdout is added as context
* **JSON with `additionalContext`**: use the JSON format below for more control. The `additionalContext` field is added as context

Plain stdout is shown as hook output in the transcript. The `additionalContext` field is added more discretely.

To block a prompt, return a JSON object with `decision` set to `"block"`:

| Field                    | Description                                                                                                            |
| :----------------------- | :--------------------------------------------------------------------------------------------------------------------- |
| `decision`               | `"block"` prevents the prompt from being processed and erases it from context. Omit to allow the prompt to proceed     |
| `reason`                 | Shown to the user when `decision` is `"block"`. Not added to context                                                   |
| `additionalContext`      | String added to Claude's context alongside the submitted prompt. See [Add context for Claude](#add-context-for-claude) |
| `sessionTitle`           | Sets the session title. Use to name sessions automatically based on the prompt content                                 |
| `suppressOriginalPrompt` | If `true` when `decision` is `"block"`, omits the original prompt text from the block message shown to the user        |

```json theme={null}
{
  "decision": "block",
  "reason": "Explanation for decision",
  "hookSpecificOutput": {
    "hookEventName": "UserPromptSubmit",
    "additionalContext": "My additional context here",
    "sessionTitle": "My session title"
  }
}
```

<Note>
  The JSON format isn't required for simple use cases. To add context, you can print plain text to stdout with exit code 0. Use JSON when you need to
  block prompts or want more structured control.
</Note>

### UserPromptExpansion

Runs when a user-typed slash command expands into a prompt before reaching Claude. Use this to block specific commands from direct invocation, inject context for a particular skill, or log which commands users invoke. For example, a hook matching `deploy` can block `/deploy` unless an approval file is present, or a hook matching a review skill can append the team's review checklist as `additionalContext`.

This event covers the path `PreToolUse` does not: a `PreToolUse` hook matching the `Skill` tool fires only when Claude calls the tool, but typing `/skillname` directly bypasses `PreToolUse`. `UserPromptExpansion` fires on that direct path.

Matches on `command_name`. Leave the matcher empty to fire on every prompt-type slash command.

#### UserPromptExpansion input

In addition to the [common input fields](#common-input-fields), UserPromptExpansion hooks receive `expansion_type`, `command_name`, `command_args`, `command_source`, and the original `prompt` string. The `expansion_type` field is `slash_command` for skill and custom commands, or `mcp_prompt` for MCP server prompts.

```json theme={null}
{
  "session_id": "abc123",
  "transcript_path": "/Users/.../00893aaf.jsonl",
  "cwd": "/Users/...",
  "permission_mode": "default",
  "hook_event_name": "UserPromptExpansion",
  "expansion_type": "slash_command",
  "command_name": "example-skill",
  "command_args": "arg1 arg2",
  "command_source": "plugin",
  "prompt": "/example-skill arg1 arg2"
}
```

#### UserPromptExpansion decision control

`UserPromptExpansion` hooks can block the expansion or add context. All [JSON output fields](#json-output) are available.

| Field               | Description                                                                                                           |
| :------------------ | :-------------------------------------------------------------------------------------------------------------------- |
| `decision`          | `"block"` prevents the slash command from expanding. Omit to allow it to proceed                                      |
| `reason`            | Shown to the user when `decision` is `"block"`                                                                        |
| `additionalContext` | String added to Claude's context alongside the expanded prompt. See [Add context for Claude](#add-context-for-claude) |

```json theme={null}
{
  "decision": "block",
  "reason": "This slash command is not available",
  "hookSpecificOutput": {
    "hookEventName": "UserPromptExpansion",
    "additionalContext": "Additional context for this expansion"
  }
}
```

### MessageDisplay

Runs while an assistant message streams to the screen. Claude Code displays the message in increments: each time a batch of newly completed lines is ready to render, the hook runs once with those lines and Claude Code renders the hook's replacement text in their place. A long message produces several calls; a short message may produce only one.

Use MessageDisplay to:

* strip markdown for a minimal display
* transform the text an Agent SDK application shows its users
* redact API keys or internal hostnames from Claude's responses

Claude Code holds each batch until your hook returns, so keep the hook fast. If the hook fails or times out, Claude Code displays the original text. The default timeout for this event is 10 seconds; if your hook needs more time, set the `timeout` field in the hook entry.

MessageDisplay is display-only: the replacement text changes only what is rendered on screen. The transcript and what Claude sees keep the original text, so Claude never sees the replacement, and verbose mode shows the original. The hook receives assistant message text only, so tool results and the text you type render unchanged.

MessageDisplay does not support matchers and fires for every assistant message that streams text; messages with no text, such as tool-call-only responses, do not trigger it.

In non-interactive runs, including Agent SDK queries and `claude -p`, MessageDisplay runs once per assistant message instead of once per batch of lines. The single call arrives after the message completes and carries the full message text: `index` is `0`, `final` is `true`, and `delta` holds the entire message. A hook that collects the `delta` text for each message receives the same total text in both modes.

#### MessageDisplay input

In addition to the [common input fields](#common-input-fields), MessageDisplay hooks receive identifiers for the turn and message, the position of this call within the message, and the new text in `delta`. Batch boundaries depend on how the text streams, so use `index` and `final` to track progress through a message rather than expecting lines to be grouped a particular way.

| Field        | Description                                                                                                                                                                                                                                                                                                                                                                                       |
| :----------- | :------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `turn_id`    | UUID of the current turn                                                                                                                                                                                                                                                                                                                                                                          |
| `message_id` | UUID of the assistant message being displayed. Stable across every batch of the same message. This is not the API `msg_…` id, so it cannot be correlated with transcript message ids                                                                                                                                                                                                              |
| `index`      | Zero-based index of this batch within the message                                                                                                                                                                                                                                                                                                                                                 |
| `final`      | `true` on the message's last batch. Each message has exactly one final batch                                                                                                                                                                                                                                                                                                                      |
| `delta`      | The newly completed lines since the prior batch, terminating newlines included. Always whole lines, except the final batch which may end mid-line. In interactive runs, the final batch's delta is empty when the message ends on a newline, so treat `final`, not a non-empty delta, as the end-of-message signal. In Agent SDK and `claude -p` runs, the single call carries the entire message |

```json theme={null}
{
  "session_id": "abc123",
  "transcript_path": "/Users/.../.claude/projects/.../transcript.jsonl",
  "cwd": "/Users/my-project",
  "hook_event_name": "MessageDisplay",
  "turn_id": "0c9e6a2f-7d41-4f4e-9a15-3f4f7c2b8d10",
  "message_id": "5b2a9c8e-1f63-4d8a-b7c4-9e0d2a6f1c3b",
  "index": 0,
  "final": false,
  "delta": "Here is the plan:\n"
}
```

#### MessageDisplay output

In addition to the [JSON output fields](#json-output) available to all hooks, MessageDisplay hooks can return `displayContent` to replace the delta on screen:

| Field            | Description                                                           |
| :--------------- | :-------------------------------------------------------------------- |
| `displayContent` | Text displayed in place of the delta. Omit it to display the original |

MessageDisplay hooks have no decision control. They cannot block the message or change what is stored in the transcript or sent to Claude.

This example strips markdown formatting from Claude's responses for a plain-text display. The script reads each batch from stdin, removes bold markers and inline code backticks from `delta`, and returns the result as `displayContent`.

<Tabs>
  <Tab title="macOS/Linux">
    Register a command hook for the event in your settings file:

    ```json theme={null}
    {
      "hooks": {
        "MessageDisplay": [
          {
            "hooks": [
              {
                "type": "command",
                "command": "${CLAUDE_PROJECT_DIR}/.claude/hooks/plain-display.sh",
                "args": []
              }
            ]
          }
        ]
      }
    }
    ```

    Save this script to `.claude/hooks/plain-display.sh` in your project and make it executable with `chmod +x`:

    ```bash theme={null}
    #!/bin/bash
    jq '{hookSpecificOutput: {hookEventName: "MessageDisplay", displayContent: (.delta | gsub("\\*\\*"; "") | gsub("`"; ""))}}'
    ```

    The script needs `jq` on your `PATH`.
  </Tab>

  <Tab title="Windows (PowerShell)">
    Register a command hook that runs the script through PowerShell:

    ```json theme={null}
    {
      "hooks": {
        "MessageDisplay": [
          {
            "hooks": [
              {
                "type": "command",
                "command": "powershell.exe",
                "args": [
                  "-NoProfile",
                  "-ExecutionPolicy",
                  "Bypass",
                  "-File",
                  "${CLAUDE_PROJECT_DIR}/.claude/hooks/plain-display.ps1"
                ]
              }
            ]
          }
        ]
      }
    }
    ```

    The `-NoProfile` flag skips loading your PowerShell profile so the hook starts fast, and `-ExecutionPolicy Bypass` lets PowerShell run the local script file.

    Save this script to `.claude/hooks/plain-display.ps1` in your project:

    ```powershell theme={null}
    $batch = [Console]::In.ReadToEnd() | ConvertFrom-Json
    $text = $batch.delta -replace '\*\*', '' -replace '`', ''
    @{
      hookSpecificOutput = @{
        hookEventName = "MessageDisplay"
        displayContent = $text
      }
    } | ConvertTo-Json
    ```
  </Tab>
</Tabs>

Batches with no markdown pass through unchanged. If the script fails, for example because `jq` is missing, Claude Code displays the original text and notes the failure only in [debug output](#debug-hooks), not in the session.

### PreToolUse

Runs after Claude creates tool parameters and before processing the tool call. Matches on tool name: `Bash`, `Edit`, `Write`, `Read`, `Glob`, `Grep`, `Agent`, `WebFetch`, `WebSearch`, `AskUserQuestion`, `ExitPlanMode`, and any [MCP tool names](#match-mcp-tools).

<Warning>
  PreToolUse runs only when Claude calls a tool. Files you [reference with `@` in your prompt](/en/common-workflows#reference-files-and-directories) are added without any tool call: Claude Code inserts their contents while building the prompt, so no PreToolUse hook fires for them, including hooks matching `Read`. To block specific paths from `@` references, use a [`Read` deny rule](/en/permissions#read-and-edit) instead.
</Warning>

Use [PreToolUse decision control](#pretooluse-decision-control) to allow, deny, ask, or defer the tool call.

#### PreToolUse input

In addition to the [common input fields](#common-input-fields), PreToolUse hooks receive `tool_name`, `tool_input`, and `tool_use_id`. The `tool_input` fields depend on the tool:

##### Bash

Executes shell commands.

| Field               | Type    | Example            | Description                                   |
| :------------------ | :------ | :----------------- | :-------------------------------------------- |
| `command`           | string  | `"npm test"`       | The shell command to execute                  |
| `description`       | string  | `"Run test suite"` | Optional description of what the command does |
| `timeout`           | number  | `120000`           | Optional timeout in milliseconds              |
| `run_in_background` | boolean | `false`            | Whether to run the command in background      |

##### Write

Creates or overwrites a file.

| Field       | Type   | Example               | Description                        |
| :---------- | :----- | :-------------------- | :--------------------------------- |
| `file_path` | string | `"/path/to/file.txt"` | Absolute path to the file to write |
| `content`   | string | `"file content"`      | Content to write to the file       |

##### Edit

Replaces a string in an existing file.

| Field         | Type    | Example               | Description                        |
| :------------ | :------ | :-------------------- | :--------------------------------- |
| `file_path`   | string  | `"/path/to/file.txt"` | Absolute path to the file to edit  |
| `old_string`  | string  | `"original text"`     | Text to find and replace           |
| `new_string`  | string  | `"replacement text"`  | Replacement text                   |
| `replace_all` | boolean | `false`               | Whether to replace all occurrences |

##### Read

Reads file contents.

| Field       | Type   | Example               | Description                                |
| :---------- | :----- | :-------------------- | :----------------------------------------- |
| `file_path` | string | `"/path/to/file.txt"` | Absolute path to the file to read          |
| `offset`    | number | `10`                  | Optional line number to start reading from |
| `limit`     | number | `50`                  | Optional number of lines to read           |

##### Glob

Finds files matching a glob pattern.

| Field     | Type   | Example          | Description                                                            |
| :-------- | :----- | :--------------- | :--------------------------------------------------------------------- |
| `pattern` | string | `"**/*.ts"`      | Glob pattern to match files against                                    |
| `path`    | string | `"/path/to/dir"` | Optional directory to search in. Defaults to current working directory |

##### Grep

Searches file contents with regular expressions.

| Field         | Type    | Example          | Description                                                                           |
| :------------ | :------ | :--------------- | :------------------------------------------------------------------------------------ |
| `pattern`     | string  | `"TODO.*fix"`    | Regular expression pattern to search for                                              |
| `path`        | string  | `"/path/to/dir"` | Optional file or directory to search in                                               |
| `glob`        | string  | `"*.ts"`         | Optional glob pattern to filter files                                                 |
| `output_mode` | string  | `"content"`      | `"content"`, `"files_with_matches"`, or `"count"`. Defaults to `"files_with_matches"` |
| `-i`          | boolean | `true`           | Case insensitive search                                                               |
| `multiline`   | boolean | `false`          | Enable multiline matching                                                             |

##### WebFetch

Fetches and processes web content.

| Field    | Type   | Example                       | Description                          |
| :------- | :----- | :---------------------------- | :----------------------------------- |
| `url`    | string | `"https://example.com/api"`   | URL to fetch content from            |
| `prompt` | string | `"Extract the API endpoints"` | Prompt to run on the fetched content |

##### WebSearch

Searches the web.

| Field             | Type   | Example                        | Description                                       |
| :---------------- | :----- | :----------------------------- | :------------------------------------------------ |
| `query`           | string | `"react hooks best practices"` | Search query                                      |
| `allowed_domains` | array  | `["docs.example.com"]`         | Optional: only include results from these domains |
| `blocked_domains` | array  | `["spam.example.com"]`         | Optional: exclude results from these domains      |

##### Agent

Spawns a [subagent](/en/sub-agents).

| Field           | Type   | Example                    | Description                                  |
| :-------------- | :----- | :------------------------- | :------------------------------------------- |
| `prompt`        | string | `"Find all API endpoints"` | The task for the agent to perform            |
| `description`   | string | `"Find API endpoints"`     | Short description of the task                |
| `subagent_type` | string | `"Explore"`                | Type of specialized agent to use             |
| `model`         | string | `"sonnet"`                 | Optional model alias to override the default |

In `PostToolUse`, `tool_response` for a completed Agent call carries the subagent's final text along with usage telemetry. Read these fields to record per-subagent cost from a hook:

| Field               | Type   | Example                                               | Description                                                                                                                              |
| :------------------ | :----- | :---------------------------------------------------- | :--------------------------------------------------------------------------------------------------------------------------------------- |
| `status`            | string | `"completed"`                                         | `"completed"` for synchronous calls, `"async_launched"` for `run_in_background: true`                                                    |
| `agentId`           | string | `"a4d2c8f1e0b3a297"`                                  | Identifier for the subagent run                                                                                                          |
| `content`           | array  | `[{"type": "text", "text": "Found 12 endpoints..."}]` | The subagent's final text blocks                                                                                                         |
| `resolvedModel`     | string | `"claude-sonnet-4-5"`                                 | Model the subagent ran on, which may differ from the requested model. {/* min-version: 2.1.174 */}Requires Claude Code v2.1.174 or later |
| `totalTokens`       | number | `12450`                                               | Total tokens billed across the subagent's turns                                                                                          |
| `totalDurationMs`   | number | `48211`                                               | Wall-clock duration of the subagent run                                                                                                  |
| `totalToolUseCount` | number | `7`                                                   | Count of tool calls the subagent made                                                                                                    |
| `usage`             | object | `{"input_tokens": 8320, ...}`                         | Per-type token breakdown: `input_tokens`, `output_tokens`, `cache_creation_input_tokens`, `cache_read_input_tokens`                      |

For `run_in_background: true` calls, the tool returns immediately after launching the subagent, so `tool_response` carries no usage fields. It has `status: "async_launched"`, `agentId`, `description`, `prompt`, `outputFile`, and `resolvedModel`.

The `resolvedModel` field names the model the subagent actually runs on, which can differ from the `model` value in `tool_input`, such as when `availableModels` or another override applies. It requires Claude Code v2.1.174 or later.

<a id="askuserquestion" />

##### AskUserQuestion

Asks the user one to four multiple-choice questions.

| Field       | Type   | Example                                                                                                            | Description                                                                                                                                                                                      |
| :---------- | :----- | :----------------------------------------------------------------------------------------------------------------- | :----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `questions` | array  | `[{"question": "Which framework?", "header": "Framework", "options": [{"label": "React"}], "multiSelect": false}]` | Questions to present, each with a `question` string, short `header`, `options` array, and optional `multiSelect` flag                                                                            |
| `answers`   | object | `{"Which framework?": "React"}`                                                                                    | Optional. Maps question text to the selected option label. Multi-select answers join labels with commas. Claude does not set this field; supply it via `updatedInput` to answer programmatically |

##### ExitPlanMode

Presents a plan and asks the user to approve it before Claude leaves [plan mode](/en/permission-modes#analyze-before-you-edit-with-plan-mode). Claude writes the plan to a file on disk before calling the tool, so the literal `tool_input` from the model only carries `allowedPrompts`. Claude Code injects the plan content and file path before passing the input to hooks.

| Field            | Type   | Example                                     | Description                                                                                                                                             |
| :--------------- | :----- | :------------------------------------------ | :------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `plan`           | string | `"## Refactor auth\n1. Extract..."`         | Plan content in Markdown. Injected from the plan file on disk                                                                                           |
| `planFilePath`   | string | `"/Users/.../plans/refactor-auth.md"`       | Path to the plan file. Injected                                                                                                                         |
| `allowedPrompts` | array  | `[{"tool": "Bash", "prompt": "run tests"}]` | Optional. Prompt-based permissions Claude is requesting to implement the plan, each with a `tool` name and a `prompt` describing the category of action |

In `PostToolUse`, `tool_response` is an object with `plan` and `filePath` fields holding the approved plan, plus internal status flags. Read `tool_response.plan` for the plan content rather than re-reading the file from disk.

#### PreToolUse decision control

`PreToolUse` hooks can control whether a tool call proceeds. Unlike other hooks that use a top-level `decision` field, PreToolUse returns its decision inside a `hookSpecificOutput` object. This gives it richer control: four outcomes (allow, deny, ask, or defer) plus the ability to modify tool input before execution.

| Field                      | Description                                                                                                                                                                                                                                                                                |
| :------------------------- | :----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `permissionDecision`       | `"allow"` skips the permission prompt. `"deny"` prevents the tool call. `"ask"` prompts the user to confirm. `"defer"` exits gracefully so the tool can be resumed later. [Deny and ask rules](/en/permissions#manage-permissions) are still evaluated regardless of what the hook returns |
| `permissionDecisionReason` | For `"allow"` and `"ask"`, shown to the user but not Claude. For `"deny"`, shown to Claude. For `"defer"`, ignored                                                                                                                                                                         |
| `updatedInput`             | Modifies the tool's input parameters before execution. Replaces the entire input object, so include unchanged fields alongside modified ones. Combine with `"allow"` to auto-approve, or `"ask"` to show the modified input to the user. For `"defer"`, ignored                            |
| `additionalContext`        | String added to Claude's context alongside the tool result. Ignored when `permissionDecision` is `"defer"`. See [Add context for Claude](#add-context-for-claude)                                                                                                                          |

When multiple PreToolUse hooks return different decisions, precedence is `deny` > `defer` > `ask` > `allow`.

When a hook returns `"ask"`, the permission prompt displayed to the user includes a label identifying where the hook came from: for example, `[User]`, `[Project]`, `[Plugin]`, or `[Local]`. This helps users understand which configuration source is requesting confirmation.

```json theme={null}
{
  "hookSpecificOutput": {
    "hookEventName": "PreToolUse",
    "permissionDecision": "allow",
    "permissionDecisionReason": "My reason here",
    "updatedInput": {
      "field_to_modify": "new value"
    },
    "additionalContext": "Current environment: production. Proceed with caution."
  }
}
```

`AskUserQuestion` and `ExitPlanMode` require user interaction and normally block in [non-interactive mode](/en/headless) with the `-p` flag. Returning `permissionDecision: "allow"` together with `updatedInput` satisfies that requirement: the hook reads the tool's input from stdin, collects the answer through your own UI, and returns it in `updatedInput` so the tool runs without prompting. Returning `"allow"` alone is not sufficient for these tools. For `AskUserQuestion`, echo back the original `questions` array and add an [`answers`](#askuserquestion) object mapping each question's text to the chosen answer.

<Note>
  PreToolUse previously used top-level `decision` and `reason` fields, but these are deprecated for this event. Use `hookSpecificOutput.permissionDecision` and `hookSpecificOutput.permissionDecisionReason` instead. The deprecated values `"approve"` and `"block"` map to `"allow"` and `"deny"` respectively. Other events like PostToolUse and Stop continue to use top-level `decision` and `reason` as their current format.
</Note>

#### Defer a tool call for later

`"defer"` is for integrations that run `claude -p` as a subprocess and read its JSON output, such as an Agent SDK app or a custom UI built on top of Claude Code. It lets that calling process pause Claude at a tool call, collect input through its own interface, and resume where it left off. Claude Code honors this value only in [non-interactive mode](/en/headless) with the `-p` flag. In interactive sessions it logs a warning and ignores the hook result.

<Note>
  The `defer` value requires Claude Code v2.1.89 or later. Earlier versions do not recognize it and the tool proceeds through the normal permission flow.
</Note>

The `AskUserQuestion` tool is the typical case: Claude wants to ask the user something, but there is no terminal to answer in. The round trip works like this:

1. Claude calls `AskUserQuestion`. The `PreToolUse` hook fires.
2. The hook returns `permissionDecision: "defer"`. The tool does not execute. The process exits with `stop_reason: "tool_deferred"` and the pending tool call preserved in the transcript.
3. The calling process reads `deferred_tool_use` from the SDK result, surfaces the question in its own UI, and waits for an answer.
4. The calling process runs `claude -p --resume <session-id>`. The same tool call fires `PreToolUse` again.
5. The hook returns `permissionDecision: "allow"` with the answer in `updatedInput`. The tool executes and Claude continues.

The `deferred_tool_use` field carries the tool's `id`, `name`, and `input`. The `input` is the parameters Claude generated for the tool call, captured before execution:

```json theme={null}
{
  "type": "result",
  "subtype": "success",
  "stop_reason": "tool_deferred",
  "session_id": "abc123",
  "deferred_tool_use": {
    "id": "toolu_01abc",
    "name": "AskUserQuestion",
    "input": { "questions": [{ "question": "Which framework?", "header": "Framework", "options": [{"label": "React"}, {"label": "Vue"}], "multiSelect": false }] }
  }
}
```

There is no timeout or retry limit. The session remains on disk until you resume it, subject to the [`cleanupPeriodDays`](/en/settings#available-settings) retention sweep that deletes session files after 30 days by default. If the answer is not ready when you resume, the hook can return `"defer"` again and the process exits the same way. The calling process controls when to break the loop by eventually returning `"allow"` or `"deny"` from the hook.

`"defer"` only works when Claude makes a single tool call in the turn. If Claude makes several tool calls at once, `"defer"` is ignored with a warning and the tool proceeds through the normal permission flow. The constraint exists because resume can only re-run one tool: there is no way to defer one call from a batch without leaving the others unresolved.

If the deferred tool is no longer available when you resume, the process exits with `stop_reason: "tool_deferred_unavailable"` and `is_error: true` before the hook fires. This happens when an MCP server that provided the tool is not connected for the resumed session. The `deferred_tool_use` payload is still included so you can identify which tool went missing.

<Note>
  `--resume` restores the permission mode that was active when the tool was deferred, so you do not need to pass `--permission-mode` again. The exceptions are `plan` and `bypassPermissions`, which are never carried over. Passing `--permission-mode` explicitly on resume overrides the restored value.
</Note>

### PermissionRequest

Runs when the user is shown a permission dialog.
Use [PermissionRequest decision control](#permissionrequest-decision-control) to allow or deny on behalf of the user.

Matches on tool name, same values as PreToolUse.

#### PermissionRequest input

PermissionRequest hooks receive `tool_name` and `tool_input` fields like PreToolUse hooks, but without `tool_use_id`. An optional `permission_suggestions` array contains the "always allow" options the user would normally see in the permission dialog. The difference is when the hook fires: PermissionRequest hooks run when a permission dialog is about to be shown to the user, while PreToolUse hooks run before tool execution regardless of permission status.

```json theme={null}
{
  "session_id": "abc123",
  "transcript_path": "/Users/.../.claude/projects/.../00893aaf-19fa-41d2-8238-13269b9b3ca0.jsonl",
  "cwd": "/Users/...",
  "permission_mode": "default",
  "hook_event_name": "PermissionRequest",
  "tool_name": "Bash",
  "tool_input": {
    "command": "rm -rf node_modules",
    "description": "Remove node_modules directory"
  },
  "permission_suggestions": [
    {
      "type": "addRules",
      "rules": [{ "toolName": "Bash", "ruleContent": "rm -rf node_modules" }],
      "behavior": "allow",
      "destination": "localSettings"
    }
  ]
}
```

#### PermissionRequest decision control

`PermissionRequest` hooks can allow or deny permission requests. In addition to the [JSON output fields](#json-output) available to all hooks, your hook script can return a `decision` object with these event-specific fields:

| Field                | Description                                                                                                                                                                                                                     |
| :------------------- | :------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `behavior`           | `"allow"` grants the permission, `"deny"` denies it. [Deny and ask rules](/en/permissions#manage-permissions) are still evaluated, so a hook returning `"allow"` does not override a matching deny rule                         |
| `updatedInput`       | For `"allow"` only: modifies the tool's input parameters before execution. Replaces the entire input object, so include unchanged fields alongside modified ones. The modified input is re-evaluated against deny and ask rules |
| `updatedPermissions` | For `"allow"` only: array of [permission update entries](#permission-update-entries) to apply, such as adding an allow rule or changing the session permission mode                                                             |
| `message`            | For `"deny"` only: tells Claude why the permission was denied                                                                                                                                                                   |
| `interrupt`          | For `"deny"` only: if `true`, stops Claude                                                                                                                                                                                      |

```json theme={null}
{
  "hookSpecificOutput": {
    "hookEventName": "PermissionRequest",
    "decision": {
      "behavior": "allow",
      "updatedInput": {
        "command": "npm run lint"
      }
    }
  }
}
```

#### Permission update entries

The `updatedPermissions` output field and the [`permission_suggestions` input field](#permissionrequest-input) both use the same array of entry objects. Each entry has a `type` that determines its other fields, and a `destination` that controls where the change is written.

| `type`              | Fields                             | Effect                                                                                                                                                                      |
| :------------------ | :--------------------------------- | :-------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `addRules`          | `rules`, `behavior`, `destination` | Adds permission rules. `rules` is an array of `{toolName, ruleContent?}` objects. Omit `ruleContent` to match the whole tool. `behavior` is `"allow"`, `"deny"`, or `"ask"` |
| `replaceRules`      | `rules`, `behavior`, `destination` | Replaces all rules of the given `behavior` at the `destination` with the provided `rules`                                                                                   |
| `removeRules`       | `rules`, `behavior`, `destination` | Removes matching rules of the given `behavior`                                                                                                                              |
| `setMode`           | `mode`, `destination`              | Changes the permission mode. Valid modes are `default`, `auto`, `acceptEdits`, `dontAsk`, `bypassPermissions`, and `plan`                                                   |
| `addDirectories`    | `directories`, `destination`       | Adds working directories. `directories` is an array of path strings                                                                                                         |
| `removeDirectories` | `directories`, `destination`       | Removes working directories                                                                                                                                                 |

<Note>
  `setMode` with `bypassPermissions` only takes effect if the session was launched with bypass mode already available: `--dangerously-skip-permissions`, `--permission-mode bypassPermissions`, `--allow-dangerously-skip-permissions`, or `permissions.defaultMode: "bypassPermissions"` in settings, and the mode is not disabled by [`permissions.disableBypassPermissionsMode`](/en/permissions#managed-settings). Otherwise the update is a no-op. `bypassPermissions` is never persisted as `defaultMode` regardless of `destination`.
</Note>

The `destination` field on every entry determines whether the change stays in memory or persists to a settings file.

| `destination`     | Writes to                                       |
| :---------------- | :---------------------------------------------- |
| `session`         | in-memory only, discarded when the session ends |
| `localSettings`   | `.claude/settings.local.json`                   |
| `projectSettings` | `.claude/settings.json`                         |
| `userSettings`    | `~/.claude/settings.json`                       |

A hook can echo one of the `permission_suggestions` it received as its own `updatedPermissions` output, which is equivalent to the user selecting that "always allow" option in the dialog.

### PostToolUse

Runs immediately after a tool completes successfully.

Matches on tool name, same values as PreToolUse.

#### PostToolUse input

`PostToolUse` hooks fire after a tool has already executed successfully. The input includes both `tool_input`, the arguments sent to the tool, and `tool_response`, the result it returned. The exact schema for both depends on the tool.

```json theme={null}
{
  "session_id": "abc123",
  "transcript_path": "/Users/.../.claude/projects/.../00893aaf-19fa-41d2-8238-13269b9b3ca0.jsonl",
  "cwd": "/Users/...",
  "permission_mode": "default",
  "hook_event_name": "PostToolUse",
  "tool_name": "Write",
  "tool_input": {
    "file_path": "/path/to/file.txt",
    "content": "file content"
  },
  "tool_response": {
    "filePath": "/path/to/file.txt",
    "success": true
  },
  "tool_use_id": "toolu_01ABC123...",
  "duration_ms": 12
}
```

| Field         | Description                                                                                                   |
| :------------ | :------------------------------------------------------------------------------------------------------------ |
| `duration_ms` | Optional. Tool execution time in milliseconds. Excludes time spent in permission prompts and PreToolUse hooks |

#### PostToolUse decision control

`PostToolUse` hooks can provide feedback to Claude after tool execution. In addition to the [JSON output fields](#json-output) available to all hooks, your hook script can return these event-specific fields:

| Field                  | Description                                                                                                                        |
| :--------------------- | :--------------------------------------------------------------------------------------------------------------------------------- |
| `decision`             | `"block"` adds the `reason` next to the tool result. Claude still sees the original output; to replace it, use `updatedToolOutput` |
| `reason`               | Explanation shown to Claude when `decision` is `"block"`                                                                           |
| `additionalContext`    | String added to Claude's context alongside the tool result. See [Add context for Claude](#add-context-for-claude)                  |
| `updatedToolOutput`    | Replaces the tool's output with the provided value before it is sent to Claude. The value must match the tool's output shape       |
| `updatedMCPToolOutput` | Replaces the output for [MCP tools](#match-mcp-tools) only. Prefer `updatedToolOutput`, which works for all tools                  |

The example below replaces the output of a `Bash` call. The replacement value matches the `Bash` tool's output shape:

```json theme={null}
{
  "hookSpecificOutput": {
    "hookEventName": "PostToolUse",
    "additionalContext": "Additional information for Claude",
    "updatedToolOutput": {
      "stdout": "[redacted]",
      "stderr": "",
      "interrupted": false,
      "isImage": false
    }
  }
}
```

<Warning>
  `updatedToolOutput` only changes what Claude sees. The tool has already run by the time the hook fires, so any files written, commands executed, or network requests sent have already taken effect. Telemetry such as OpenTelemetry tool spans and analytics events also captures the original output before the hook runs. To prevent or modify a tool call before it runs, use a [PreToolUse](#pretooluse) hook instead.

  The replacement value must match the tool's output shape. Built-in tools return structured objects rather than plain strings. For example, `Bash` returns an object with `stdout`, `stderr`, `interrupted`, and `isImage` fields. For built-in tools, a value that does not match the tool's output schema is ignored and the original output is used. MCP tool output is passed through without schema validation. Stripping error details that Claude needs can cause it to proceed on a false assumption.
</Warning>

### PostToolUseFailure

Runs when a tool execution fails. This event fires for tool calls that throw errors or return failure results. Use this to log failures, send alerts, or provide corrective feedback to Claude.

Matches on tool name, same values as PreToolUse.

#### PostToolUseFailure input

PostToolUseFailure hooks receive the same `tool_name` and `tool_input` fields as PostToolUse, along with error information as top-level fields:

```json theme={null}
{
  "session_id": "abc123",
  "transcript_path": "/Users/.../.claude/projects/.../00893aaf-19fa-41d2-8238-13269b9b3ca0.jsonl",
  "cwd": "/Users/...",
  "permission_mode": "default",
  "hook_event_name": "PostToolUseFailure",
  "tool_name": "Bash",
  "tool_input": {
    "command": "npm test",
    "description": "Run test suite"
  },
  "tool_use_id": "toolu_01ABC123...",
  "error": "Command exited with non-zero status code 1",
  "is_interrupt": false,
  "duration_ms": 4187
}
```

| Field          | Description                                                                                                   |
| :------------- | :------------------------------------------------------------------------------------------------------------ |
| `error`        | String describing what went wrong                                                                             |
| `is_interrupt` | Optional boolean indicating whether the failure was caused by user interruption                               |
| `duration_ms`  | Optional. Tool execution time in milliseconds. Excludes time spent in permission prompts and PreToolUse hooks |

#### PostToolUseFailure decision control

`PostToolUseFailure` hooks can provide context to Claude after a tool failure. In addition to the [JSON output fields](#json-output) available to all hooks, your hook script can return these event-specific fields:

| Field               | Description                                                                                                 |
| :------------------ | :---------------------------------------------------------------------------------------------------------- |
| `additionalContext` | String added to Claude's context alongside the error. See [Add context for Claude](#add-context-for-claude) |

```json theme={null}
{
  "hookSpecificOutput": {
    "hookEventName": "PostToolUseFailure",
    "additionalContext": "Additional information about the failure for Claude"
  }
}
```

### PostToolBatch

Runs once after every tool call in a batch has resolved, before Claude Code sends the next request to the model. `PostToolUse` fires once per tool, which means it fires concurrently when Claude makes parallel tool calls. `PostToolBatch` fires exactly once with the full batch, so it is the right place to inject context that depends on the set of tools that ran rather than on any single tool. There is no matcher for this event.

#### PostToolBatch input

In addition to the [common input fields](#common-input-fields), PostToolBatch hooks receive `tool_calls`, an array describing every tool call in the batch:

```json theme={null}
{
  "session_id": "abc123",
  "transcript_path": "/Users/.../.claude/projects/.../00893aaf-19fa-41d2-8238-13269b9b3ca0.jsonl",
  "cwd": "/Users/...",
  "permission_mode": "default",
  "hook_event_name": "PostToolBatch",
  "tool_calls": [
    {
      "tool_name": "Read",
      "tool_input": {"file_path": "/.../ledger/accounts.py"},
      "tool_use_id": "toolu_01...",
      "tool_response": "     1\tfrom __future__ import annotations\n     2\t..."
    },
    {
      "tool_name": "Read",
      "tool_input": {"file_path": "/.../ledger/transactions.py"},
      "tool_use_id": "toolu_02...",
      "tool_response": "     1\tfrom __future__ import annotations\n     2\t..."
    }
  ]
}
```

`tool_response` contains the same content the model receives in the corresponding `tool_result` block. The value is a serialized string or content-block array, exactly as the tool emitted it. For `Read`, that means line-number-prefixed text rather than raw file contents. Responses can be large, so parse only the fields you need.

<Note>
  The `tool_response` shape differs from `PostToolUse`'s. `PostToolUse` passes the tool's structured `Output` object, such as `{filePath: "...", success: true}` for `Write`; `PostToolBatch` passes the serialized `tool_result` content the model sees.
</Note>

#### PostToolBatch decision control

`PostToolBatch` hooks can inject context for Claude. In addition to the [JSON output fields](#json-output) available to all hooks, your hook script can return these event-specific fields:

| Field               | Description                                                                                                                                                                                         |
| :------------------ | :-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `additionalContext` | Context string injected once before the next model call. See [Add context for Claude](#add-context-for-claude) for delivery details, what to put in it, and how resumed sessions handle past values |

```json theme={null}
{
  "hookSpecificOutput": {
    "hookEventName": "PostToolBatch",
    "additionalContext": "These files are part of the ledger module. Run pytest before marking the task complete."
  }
}
```

Returning `decision: "block"` or `continue: false` stops the agentic loop before the next model call.

### PermissionDenied

Runs when the [auto mode](/en/permission-modes#eliminate-prompts-with-auto-mode) classifier denies a tool call. This hook only fires in auto mode: it does not run when you manually deny a permission dialog, when a `PreToolUse` hook blocks a call, or when a `deny` rule matches. Use it to log classifier denials, adjust configuration, or tell the model it may retry the tool call.

Matches on tool name, same values as PreToolUse.

#### PermissionDenied input

In addition to the [common input fields](#common-input-fields), PermissionDenied hooks receive `tool_name`, `tool_input`, `tool_use_id`, and `reason`.

```json theme={null}
{
  "session_id": "abc123",
  "transcript_path": "/Users/.../.claude/projects/.../00893aaf-19fa-41d2-8238-13269b9b3ca0.jsonl",
  "cwd": "/Users/...",
  "permission_mode": "auto",
  "hook_event_name": "PermissionDenied",
  "tool_name": "Bash",
  "tool_input": {
    "command": "rm -rf /tmp/build",
    "description": "Clean build directory"
  },
  "tool_use_id": "toolu_01ABC123...",
  "reason": "Auto mode denied: command targets a path outside the project"
}
```

| Field    | Description                                                   |
| :------- | :------------------------------------------------------------ |
| `reason` | The classifier's explanation for why the tool call was denied |

#### PermissionDenied decision control

PermissionDenied hooks can tell the model it may retry the denied tool call. Return a JSON object with `hookSpecificOutput.retry` set to `true`:

```json theme={null}
{
  "hookSpecificOutput": {
    "hookEventName": "PermissionDenied",
    "retry": true
  }
}
```

When `retry` is `true`, Claude Code adds a message to the conversation telling the model it may retry the tool call. The denial itself is not reversed. If your hook does not return JSON, or returns `retry: false`, the denial stands and the model receives the original rejection message.

### Notification

Runs when Claude Code sends notifications. Matches on notification type: `permission_prompt`, `idle_prompt`, `auth_success`, `elicitation_dialog`, `elicitation_complete`, `elicitation_response`. Omit the matcher to run hooks for all notification types.

Use separate matchers to run different handlers depending on the notification type. This configuration triggers a permission-specific alert script when Claude needs permission approval and a different notification when Claude has been idle:

```json theme={null}
{
  "hooks": {
    "Notification": [
      {
        "matcher": "permission_prompt",
        "hooks": [
          {
            "type": "command",
            "command": "/path/to/permission-alert.sh"
          }
        ]
      },
      {
        "matcher": "idle_prompt",
        "hooks": [
          {
            "type": "command",
            "command": "/path/to/idle-notification.sh"
          }
        ]
      }
    ]
  }
}
```

#### Notification input

In addition to the [common input fields](#common-input-fields), Notification hooks receive `message` with the notification text, an optional `title`, and `notification_type` indicating which type fired.

```json theme={null}
{
  "session_id": "abc123",
  "transcript_path": "/Users/.../.claude/projects/.../00893aaf-19fa-41d2-8238-13269b9b3ca0.jsonl",
  "cwd": "/Users/...",
  "hook_event_name": "Notification",
  "message": "Claude needs your permission",
  "title": "Permission needed",
  "notification_type": "permission_prompt"
}
```

Notification hooks cannot block or modify notifications. They are intended for side effects such as forwarding the notification to an external service. The [common JSON output fields](#json-output) such as `systemMessage` apply.

### SubagentStart

Runs when a Claude Code subagent is spawned via the Agent tool. Supports matchers to filter by agent type name. For built-in agents, this is the agent name like `general-purpose`, `Explore`, or `Plan`. For [custom subagents](/en/sub-agents), this is the `name` field from the agent's frontmatter, not the filename.

For subagents shipped by a [plugin](/en/plugins), the agent type is the plugin-scoped identifier such as `my-plugin:reviewer`, not the bare frontmatter name. The colon places a plugin-scoped name on the regular-expression path, so anchor the matcher with `^` and `$` for an exact match: `^my-plugin:reviewer$`.

#### SubagentStart input

In addition to the [common input fields](#common-input-fields), SubagentStart hooks receive `agent_id` with the unique identifier for the subagent and `agent_type` with the agent name (built-in agents like `"general-purpose"`, `"Explore"`, `"Plan"`, or custom agent names).

```json theme={null}
{
  "session_id": "abc123",
  "transcript_path": "/Users/.../.claude/projects/.../00893aaf-19fa-41d2-8238-13269b9b3ca0.jsonl",
  "cwd": "/Users/...",
  "hook_event_name": "SubagentStart",
  "agent_id": "agent-abc123",
  "agent_type": "Explore"
}
```

SubagentStart hooks cannot block subagent creation, but they can inject context into the subagent. In addition to the [JSON output fields](#json-output) available to all hooks, you can return:

| Field               | Description                                                                                                                                             |
| :------------------ | :------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `additionalContext` | String added to the subagent's context at the start of its conversation, before its first prompt. See [Add context for Claude](#add-context-for-claude) |

```json theme={null}
{
  "hookSpecificOutput": {
    "hookEventName": "SubagentStart",
    "additionalContext": "Follow security guidelines for this task"
  }
}
```

### SubagentStop

Runs when a Claude Code subagent has finished responding. Matches on agent type, same values as SubagentStart.

#### SubagentStop input

In addition to the [common input fields](#common-input-fields), SubagentStop hooks receive `stop_hook_active`, `agent_id`, `agent_type`, `agent_transcript_path`, and `last_assistant_message`. The `agent_type` field is the value used for matcher filtering. The `transcript_path` is the main session's transcript, while `agent_transcript_path` is the subagent's own transcript stored in a nested `subagents/` folder. The `last_assistant_message` field contains the text content of the subagent's final response, so hooks can access it without parsing the transcript file.

SubagentStop hooks also receive the `background_tasks` and `session_crons` arrays described under [Stop input](#stop-input), available in Claude Code v2.1.145 or later. Both arrays are scoped to the parent session, not the subagent.

```json theme={null}
{
  "session_id": "abc123",
  "transcript_path": "~/.claude/projects/.../abc123.jsonl",
  "cwd": "/Users/...",
  "permission_mode": "default",
  "hook_event_name": "SubagentStop",
  "stop_hook_active": false,
  "agent_id": "def456",
  "agent_type": "Explore",
  "agent_transcript_path": "~/.claude/projects/.../abc123/subagents/agent-def456.jsonl",
  "last_assistant_message": "Analysis complete. Found 3 potential issues...",
  "background_tasks": [],
  "session_crons": []
}
```

SubagentStop hooks use the same decision control format as [Stop hooks](#stop-decision-control), including `hookSpecificOutput.additionalContext` with `hookEventName` set to `"SubagentStop"`, for non-error feedback that keeps the subagent running. Returning `decision: "block"` with a `reason` keeps the subagent running and delivers `reason` to the subagent as its next instruction. To inject context into the parent session after a subagent returns, use a [`PostToolUse`](#posttooluse) hook on the `Agent` tool instead.

### TaskCreated

Runs when a task is being created via the `TaskCreate` tool. Use this to enforce naming conventions, require task descriptions, or prevent certain tasks from being created.

When a `TaskCreated` hook exits with code 2, the task is not created and the stderr message is fed back to the model as feedback. To stop the teammate entirely instead of re-running it, return JSON with `{"continue": false, "stopReason": "..."}`. TaskCreated hooks do not support matchers and fire on every occurrence.

#### TaskCreated input

In addition to the [common input fields](#common-input-fields), TaskCreated hooks receive `task_id`, `task_subject`, and optionally `task_description`, `teammate_name`, and `team_name`.

```json theme={null}
{
  "session_id": "abc123",
  "transcript_path": "/Users/.../.claude/projects/.../00893aaf-19fa-41d2-8238-13269b9b3ca0.jsonl",
  "cwd": "/Users/...",
  "permission_mode": "default",
  "hook_event_name": "TaskCreated",
  "task_id": "task-001",
  "task_subject": "Implement user authentication",
  "task_description": "Add login and signup endpoints",
  "teammate_name": "implementer",
  "team_name": "session-a1b2c3d4"
}
```

| Field              | Description                                                                |
| :----------------- | :------------------------------------------------------------------------- |
| `task_id`          | Identifier of the task being created                                       |
| `task_subject`     | Title of the task                                                          |
| `task_description` | Detailed description of the task. May be absent                            |
| `teammate_name`    | Name of the teammate creating the task. May be absent                      |
| `team_name`        | Deprecated. Session-derived team name; will be removed in a future release |

#### TaskCreated decision control

TaskCreated hooks support two ways to control task creation:

* **Exit code 2**: the task is not created and the stderr message is fed back to the model as feedback.
* **JSON `{"continue": false, "stopReason": "..."}`**: stops the teammate entirely, matching `Stop` hook behavior. The `stopReason` is shown to the user.

This example blocks tasks whose subjects don't follow the required format:

```bash theme={null}
#!/bin/bash
INPUT=$(cat)
TASK_SUBJECT=$(echo "$INPUT" | jq -r '.task_subject')

if [[ ! "$TASK_SUBJECT" =~ ^\[TICKET-[0-9]+\] ]]; then
  echo "Task subject must start with a ticket number, e.g. '[TICKET-123] Add feature'" >&2
  exit 2
fi

exit 0
```

### TaskCompleted

Runs when a task is being marked as completed. This fires in two situations: when any agent explicitly marks a task as completed through the TaskUpdate tool, or when an [agent team](/en/agent-teams) teammate finishes its turn with in-progress tasks. Use this to enforce completion criteria like passing tests or lint checks before a task can close.

When a `TaskCompleted` hook exits with code 2, the task is not marked as completed and the stderr message is fed back to the model as feedback. To stop the teammate entirely instead of re-running it, return JSON with `{"continue": false, "stopReason": "..."}`. TaskCompleted hooks do not support matchers and fire on every occurrence.

#### TaskCompleted input

In addition to the [common input fields](#common-input-fields), TaskCompleted hooks receive `task_id`, `task_subject`, and optionally `task_description`, `teammate_name`, and `team_name`.

```json theme={null}
{
  "session_id": "abc123",
  "transcript_path": "/Users/.../.claude/projects/.../00893aaf-19fa-41d2-8238-13269b9b3ca0.jsonl",
  "cwd": "/Users/...",
  "permission_mode": "default",
  "hook_event_name": "TaskCompleted",
  "task_id": "task-001",
  "task_subject": "Implement user authentication",
  "task_description": "Add login and signup endpoints",
  "teammate_name": "implementer",
  "team_name": "session-a1b2c3d4"
}
```

| Field              | Description                                                                |
| :----------------- | :------------------------------------------------------------------------- |
| `task_id`          | Identifier of the task being completed                                     |
| `task_subject`     | Title of the task                                                          |
| `task_description` | Detailed description of the task. May be absent                            |
| `teammate_name`    | Name of the teammate completing the task. May be absent                    |
| `team_name`        | Deprecated. Session-derived team name; will be removed in a future release |

#### TaskCompleted decision control

TaskCompleted hooks support two ways to control task completion:

* **Exit code 2**: the task is not marked as completed and the stderr message is fed back to the model as feedback.
* **JSON `{"continue": false, "stopReason": "..."}`**: stops the teammate entirely, matching `Stop` hook behavior. The `stopReason` is shown to the user.

This example runs tests and blocks task completion if they fail:

```bash theme={null}
#!/bin/bash
INPUT=$(cat)
TASK_SUBJECT=$(echo "$INPUT" | jq -r '.task_subject')

# Run the test suite
if ! npm test 2>&1; then
  echo "Tests not passing. Fix failing tests before completing: $TASK_SUBJECT" >&2
  exit 2
fi

exit 0
```

### Stop

Runs when the main Claude Code agent has finished responding. Does not run if
the stoppage occurred due to a user interrupt. API errors fire
[StopFailure](#stopfailure) instead.

<Tip>
  The [`/goal`](/en/goal) command is a built-in shortcut for a session-scoped prompt-based Stop hook. Use it when you want Claude to keep working until a condition holds without writing hook configuration.
</Tip>

#### Stop input

In addition to the [common input fields](#common-input-fields), Stop hooks receive `stop_hook_active`, `last_assistant_message`, `background_tasks`, and `session_crons`. The `stop_hook_active` field is `true` when Claude Code is already continuing as a result of a stop hook. Check this value or process the transcript to avoid blocking on a condition that will never resolve. Claude Code overrides the hook and ends the turn after 8 consecutive blocks.

The `last_assistant_message` field contains the text content of Claude's final response, so hooks can access it without parsing the transcript file.

The `background_tasks` and `session_crons` arrays, available in Claude Code v2.1.145 or later, let hooks distinguish "session is done" from "session is paused waiting for background work to wake it back up". Both arrays are present when the task registry is reachable and are empty when nothing is in flight or scheduled.

Each entry in `background_tasks` describes one in-flight task and uses these fields:

| Field         | Description                                                                                                                                                                                                                                          |
| :------------ | :--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `id`          | Task identifier                                                                                                                                                                                                                                      |
| `type`        | Friendly task-type label such as `shell`, `subagent`, `monitor`, `workflow`, `teammate`, `cloud session`, or `MCP task`. Each label identifies which Claude Code feature created the task. Falls back to the raw discriminant for unrecognized types |
| `status`      | Current task status                                                                                                                                                                                                                                  |
| `description` | Free-text description, capped at 1000 characters with an in-string `… [+N chars]` marker when clipped                                                                                                                                                |
| `command`     | Shell command line, capped at 1000 characters. Present only for `shell` tasks                                                                                                                                                                        |
| `agent_type`  | Subagent type name. Present only for `subagent` tasks                                                                                                                                                                                                |
| `server`      | MCP server name. Present only for `monitor` and `MCP task` tasks                                                                                                                                                                                     |
| `tool`        | MCP tool name. Present only for `monitor` and `MCP task` tasks                                                                                                                                                                                       |
| `name`        | Workflow name. Present only for `workflow` tasks                                                                                                                                                                                                     |

Each entry in `session_crons` describes one session-scoped scheduled wakeup, sourced from `CronCreate`, `ScheduleWakeup`, and `/loop`:

| Field       | Description                                                                                                          |
| :---------- | :------------------------------------------------------------------------------------------------------------------- |
| `id`        | Cron task identifier                                                                                                 |
| `schedule`  | Cron expression, for example `0 9 * * 1-5`                                                                           |
| `recurring` | `false` for one-shot wakeups whose schedule encodes a single fire time, `true` for tasks that re-fire on every match |
| `prompt`    | Prompt submitted when the cron fires, capped at 1000 characters with the same `… [+N chars]` marker                  |

This example shows a Stop input with one in-flight shell task and one recurring cron:

```json theme={null}
{
  "session_id": "abc123",
  "transcript_path": "~/.claude/projects/.../00893aaf-19fa-41d2-8238-13269b9b3ca0.jsonl",
  "cwd": "/Users/...",
  "permission_mode": "default",
  "hook_event_name": "Stop",
  "stop_hook_active": true,
  "last_assistant_message": "I've completed the refactoring. Here's a summary...",
  "background_tasks": [
    {
      "id": "task-001",
      "type": "shell",
      "status": "running",
      "description": "tail logs",
      "command": "tail -f /var/log/syslog"
    }
  ],
  "session_crons": [
    {
      "id": "cron-001",
      "schedule": "0 9 * * 1-5",
      "recurring": true,
      "prompt": "check the build"
    }
  ]
}
```

#### Stop decision control

`Stop` and `SubagentStop` hooks can control whether Claude continues. In addition to the [JSON output fields](#json-output) available to all hooks, your hook script can return these event-specific fields:

| Field                                  | Description                                                                                                                                                                               |
| :------------------------------------- | :---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `decision`                             | `"block"` prevents Claude from stopping. Omit to allow Claude to stop                                                                                                                     |
| `reason`                               | Required when `decision` is `"block"`. Tells Claude why it should continue                                                                                                                |
| `hookSpecificOutput.additionalContext` | Non-error feedback for Claude. The conversation continues so Claude can act on it, but unlike `decision: "block"` it is shown in the transcript as hook feedback rather than a hook error |

```json theme={null}
{
  "decision": "block",
  "reason": "Must be provided when Claude is blocked from stopping"
}
```

Use `additionalContext` when the hook is working as designed and giving Claude guidance, such as "run the test suite before finishing". It keeps the conversation going through the same loop protections as `decision: "block"`, namely the `stop_hook_active` input and the 8-consecutive-continuation cap, but the transcript labels it `Stop hook feedback` and no hook error notification is shown:

```json theme={null}
{
  "hookSpecificOutput": {
    "hookEventName": "Stop",
    "additionalContext": "Please run the test suite before finishing"
  }
}
```

### StopFailure

Runs instead of [Stop](#stop) when the turn ends due to an API error. Output and exit code are ignored. Use this to log failures, send alerts, or take recovery actions when Claude cannot complete a response due to rate limits, authentication problems, or other API errors.

#### StopFailure input

In addition to the [common input fields](#common-input-fields), StopFailure hooks receive `error`, optional `error_details`, and optional `last_assistant_message`. The `error` field identifies the error type and is used for matcher filtering.

| Field                    | Description                                                                                                                                                                                                                                      |
| :----------------------- | :----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `error`                  | Error type: `rate_limit`, `overloaded`, `authentication_failed`, `oauth_org_not_allowed`, `billing_error`, `invalid_request`, `model_not_found`, `server_error`, `max_output_tokens`, or `unknown`                                               |
| `error_details`          | Additional details about the error, when available                                                                                                                                                                                               |
| `last_assistant_message` | The rendered error text shown in the conversation. Unlike `Stop` and `SubagentStop`, where this field holds Claude's conversational output, for `StopFailure` it contains the API error string itself, such as `"API Error: Rate limit reached"` |

```json theme={null}
{
  "session_id": "abc123",
  "transcript_path": "/Users/.../.claude/projects/.../00893aaf-19fa-41d2-8238-13269b9b3ca0.jsonl",
  "cwd": "/Users/...",
  "hook_event_name": "StopFailure",
  "error": "rate_limit",
  "error_details": "429 Too Many Requests",
  "last_assistant_message": "API Error: Rate limit reached"
}
```

StopFailure hooks have no decision control. They run for notification and logging purposes only.

### TeammateIdle

Runs when an [agent team](/en/agent-teams) teammate is about to go idle after finishing its turn. Use this to enforce quality gates before a teammate stops working, such as requiring passing lint checks or verifying that output files exist.

When a `TeammateIdle` hook exits with code 2, the teammate receives the stderr message as feedback and continues working instead of going idle. To stop the teammate entirely instead of re-running it, return JSON with `{"continue": false, "stopReason": "..."}`. TeammateIdle hooks do not support matchers and fire on every occurrence.

#### TeammateIdle input

In addition to the [common input fields](#common-input-fields), TeammateIdle hooks receive `teammate_name` and `team_name`.

```json theme={null}
{
  "session_id": "abc123",
  "transcript_path": "/Users/.../.claude/projects/.../00893aaf-19fa-41d2-8238-13269b9b3ca0.jsonl",
  "cwd": "/Users/...",
  "permission_mode": "default",
  "hook_event_name": "TeammateIdle",
  "teammate_name": "researcher",
  "team_name": "session-a1b2c3d4"
}
```

| Field           | Description                                                                |
| :-------------- | :------------------------------------------------------------------------- |
| `teammate_name` | Name of the teammate that is about to go idle                              |
| `team_name`     | Deprecated. Session-derived team name; will be removed in a future release |

#### TeammateIdle decision control

TeammateIdle hooks support two ways to control teammate behavior:

* **Exit code 2**: the teammate receives the stderr message as feedback and continues working instead of going idle.
* **JSON `{"continue": false, "stopReason": "..."}`**: stops the teammate entirely, matching `Stop` hook behavior. The `stopReason` is shown to the user.

This example checks that a build artifact exists before allowing a teammate to go idle:

```bash theme={null}
#!/bin/bash

if [ ! -f "./dist/output.js" ]; then
  echo "Build artifact missing. Run the build before stopping." >&2
  exit 2
fi

exit 0
```

### ConfigChange

Runs when a configuration file changes during a session. Use this to audit settings changes, enforce security policies, or block unauthorized modifications to configuration files.

ConfigChange hooks fire for changes to settings files, managed policy settings, and skill files. The `source` field in the input tells you which type of configuration changed, and the optional `file_path` field provides the path to the changed file.

The matcher filters on the configuration source:

| Matcher            | When it fires                             |
| :----------------- | :---------------------------------------- |
| `user_settings`    | `~/.claude/settings.json` changes         |
| `project_settings` | `.claude/settings.json` changes           |
| `local_settings`   | `.claude/settings.local.json` changes     |
| `policy_settings`  | Managed policy settings change            |
| `skills`           | A skill file in `.claude/skills/` changes |

This example logs all configuration changes for security auditing:

```json theme={null}
{
  "hooks": {
    "ConfigChange": [
      {
        "hooks": [
          {
            "type": "command",
            "command": "${CLAUDE_PROJECT_DIR}/.claude/hooks/audit-config-change.sh",
            "args": []
          }
        ]
      }
    ]
  }
}
```

#### ConfigChange input

In addition to the [common input fields](#common-input-fields), ConfigChange hooks receive `source` and optionally `file_path`. The `source` field indicates which configuration type changed, and `file_path` provides the path to the specific file that was modified.

```json theme={null}
{
  "session_id": "abc123",
  "transcript_path": "/Users/.../.claude/projects/.../00893aaf-19fa-41d2-8238-13269b9b3ca0.jsonl",
  "cwd": "/Users/...",
  "hook_event_name": "ConfigChange",
  "source": "project_settings",
  "file_path": "/Users/.../my-project/.claude/settings.json"
}
```

#### ConfigChange decision control

ConfigChange hooks can block configuration changes from taking effect. Use exit code 2 or a JSON `decision` to prevent the change. When blocked, the new settings are not applied to the running session.

| Field      | Description                                                                              |
| :--------- | :--------------------------------------------------------------------------------------- |
| `decision` | `"block"` prevents the configuration change from being applied. Omit to allow the change |
| `reason`   | Explanation shown to the user when `decision` is `"block"`                               |

```json theme={null}
{
  "decision": "block",
  "reason": "Configuration changes to project settings require admin approval"
}
```

`policy_settings` changes cannot be blocked. Hooks still fire for `policy_settings` sources, so you can use them for audit logging, but any blocking decision is ignored. This ensures enterprise-managed settings always take effect.

### CwdChanged

Runs when the working directory changes during a session, for example when Claude executes a `cd` command. Use this to react to directory changes: reload environment variables, activate project-specific toolchains, or run setup scripts automatically. Pairs with [FileChanged](#filechanged) for tools like [direnv](https://direnv.net/) that manage per-directory environment.

CwdChanged hooks have access to `CLAUDE_ENV_FILE`. Variables written to that file persist into subsequent Bash commands for the session, just as in [SessionStart hooks](#persist-environment-variables).

CwdChanged does not support matchers and fires on every directory change.

#### CwdChanged input

In addition to the [common input fields](#common-input-fields), CwdChanged hooks receive `old_cwd` and `new_cwd`.

```json theme={null}
{
  "session_id": "abc123",
  "transcript_path": "/Users/.../.claude/projects/.../transcript.jsonl",
  "cwd": "/Users/my-project/src",
  "hook_event_name": "CwdChanged",
  "old_cwd": "/Users/my-project",
  "new_cwd": "/Users/my-project/src"
}
```

#### CwdChanged output

In addition to the [JSON output fields](#json-output) available to all hooks, CwdChanged hooks can return `watchPaths` to dynamically set which file paths [FileChanged](#filechanged) watches:

| Field        | Description                                                                                                                                                                                                                     |
| :----------- | :------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `watchPaths` | Array of absolute paths. Replaces the current dynamic watch list (paths from your `matcher` configuration are always watched). Returning an empty array clears the dynamic list, which is typical when entering a new directory |

CwdChanged hooks have no decision control. They cannot block the directory change.

### FileChanged

Runs when a watched file changes on disk. Useful for reloading environment variables when project configuration files are modified.

The `matcher` for this event serves two roles:

* **Build the watch list**: the value is split on `|` and each segment is registered as a literal filename in the working directory, so `".envrc|.env"` watches exactly those two files. Regex patterns are not useful here: a value like `^\.env` would watch a file literally named `^\.env`.
* **Filter which hooks run**: when a watched file changes, the same value filters which hook groups run using the standard [matcher rules](#matcher-patterns) against the changed file's basename.

FileChanged hooks have access to `CLAUDE_ENV_FILE`. Variables written to that file persist into subsequent Bash commands for the session, just as in [SessionStart hooks](#persist-environment-variables).

#### FileChanged input

In addition to the [common input fields](#common-input-fields), FileChanged hooks receive `file_path` and `event`.

| Field       | Description                                                                                     |
| :---------- | :---------------------------------------------------------------------------------------------- |
| `file_path` | Absolute path to the file that changed                                                          |
| `event`     | What happened: `"change"` (file modified), `"add"` (file created), or `"unlink"` (file deleted) |

```json theme={null}
{
  "session_id": "abc123",
  "transcript_path": "/Users/.../.claude/projects/.../transcript.jsonl",
  "cwd": "/Users/my-project",
  "hook_event_name": "FileChanged",
  "file_path": "/Users/my-project/.envrc",
  "event": "change"
}
```

#### FileChanged output

In addition to the [JSON output fields](#json-output) available to all hooks, FileChanged hooks can return `watchPaths` to dynamically update which file paths are watched:

| Field        | Description                                                                                                                                                                                                                 |
| :----------- | :-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `watchPaths` | Array of absolute paths. Replaces the current dynamic watch list (paths from your `matcher` configuration are always watched). Use this when your hook script discovers additional files to watch based on the changed file |

FileChanged hooks have no decision control. They cannot block the file change from occurring.

### WorktreeCreate

When you run `claude --worktree` or a [subagent uses `isolation: "worktree"`](/en/sub-agents#choose-the-subagent-scope), Claude Code creates an isolated working copy using `git worktree`. If you configure a WorktreeCreate hook, it replaces the default git behavior, letting you use a different version control system like SVN, Perforce, or Mercurial.

Because the hook replaces the default behavior entirely, [`.worktreeinclude`](/en/worktrees#copy-gitignored-files-into-worktrees) is not processed. If you need to copy local configuration files like `.env` into the new worktree, do it inside your hook script.

The hook must return the absolute path to the created worktree directory. Claude Code uses this path as the working directory for the isolated session. Command hooks print it on stdout; HTTP hooks return it via `hookSpecificOutput.worktreePath`.

This example creates an SVN working copy and prints the path for Claude Code to use. Replace the repository URL with your own:

```json theme={null}
{
  "hooks": {
    "WorktreeCreate": [
      {
        "hooks": [
          {
            "type": "command",
            "command": "bash -c 'NAME=$(jq -r .name); DIR=\"$HOME/.claude/worktrees/$NAME\"; svn checkout https://svn.example.com/repo/trunk \"$DIR\" >&2 && echo \"$DIR\"'"
          }
        ]
      }
    ]
  }
}
```

The hook reads the worktree `name` from the JSON input on stdin, checks out a fresh copy into a new directory, and prints the directory path. The `echo` on the last line is what Claude Code reads as the worktree path. Redirect any other output to stderr so it doesn't interfere with the path.

#### WorktreeCreate input

In addition to the [common input fields](#common-input-fields), WorktreeCreate hooks receive the `name` field. This is a slug identifier for the new worktree, either specified by the user or auto-generated (for example, `bold-oak-a3f2`).

```json theme={null}
{
  "session_id": "abc123",
  "transcript_path": "/Users/.../.claude/projects/.../00893aaf-19fa-41d2-8238-13269b9b3ca0.jsonl",
  "cwd": "/Users/...",
  "hook_event_name": "WorktreeCreate",
  "name": "feature-auth"
}
```

#### WorktreeCreate output

WorktreeCreate hooks do not use the standard allow/block decision model. Instead, the hook's success or failure determines the outcome. The hook must return the absolute path to the created worktree directory:

* **Command hooks** (`type: "command"`): print the path on stdout.
* **HTTP hooks** (`type: "http"`): return `{ "hookSpecificOutput": { "hookEventName": "WorktreeCreate", "worktreePath": "/absolute/path" } }` in the response body.

If the hook fails or produces no path, worktree creation fails with an error.

### WorktreeRemove

The cleanup counterpart to [WorktreeCreate](#worktreecreate). This hook fires when a worktree is being removed, either when you exit a `--worktree` session and choose to remove it, or when a subagent with `isolation: "worktree"` finishes. For git-based worktrees, Claude handles cleanup automatically with `git worktree remove`. If you configured a WorktreeCreate hook for a non-git version control system, pair it with a WorktreeRemove hook to handle cleanup. Without one, the worktree directory is left on disk.

Claude Code passes the path returned by WorktreeCreate as `worktree_path` in the hook input. This example reads that path and removes the directory:

```json theme={null}
{
  "hooks": {
    "WorktreeRemove": [
      {
        "hooks": [
          {
            "type": "command",
            "command": "bash -c 'jq -r .worktree_path | xargs rm -rf'"
          }
        ]
      }
    ]
  }
}
```

#### WorktreeRemove input

In addition to the [common input fields](#common-input-fields), WorktreeRemove hooks receive the `worktree_path` field, which is the absolute path to the worktree being removed.

```json theme={null}
{
  "session_id": "abc123",
  "transcript_path": "/Users/.../.claude/projects/.../00893aaf-19fa-41d2-8238-13269b9b3ca0.jsonl",
  "cwd": "/Users/...",
  "hook_event_name": "WorktreeRemove",
  "worktree_path": "/Users/.../my-project/.claude/worktrees/feature-auth"
}
```

WorktreeRemove hooks have no decision control. They cannot block worktree removal but can perform cleanup tasks like removing version control state or archiving changes. Hook failures are logged in debug mode only.

### PreCompact

Runs before Claude Code is about to run a compact operation.

The matcher value indicates whether compaction was triggered manually or automatically:

| Matcher  | When it fires                                |
| :------- | :------------------------------------------- |
| `manual` | `/compact`                                   |
| `auto`   | Auto-compact when the context window is full |

Exit with code 2 to block compaction. For a manual `/compact`, the stderr message is shown to the user. You can also block by returning JSON with `"decision": "block"`.

Blocking automatic compaction has different effects depending on when it fires. If compaction was triggered proactively before the context limit, Claude Code skips it and the conversation continues uncompacted. If compaction was triggered to recover from a context-limit error already returned by the API, the underlying error surfaces and the current request fails.

#### PreCompact input

In addition to the [common input fields](#common-input-fields), PreCompact hooks receive `trigger` and `custom_instructions`. For `manual`, `custom_instructions` contains what the user passes into `/compact`. For `auto`, `custom_instructions` is empty.

```json theme={null}
{
  "session_id": "abc123",
  "transcript_path": "/Users/.../.claude/projects/.../00893aaf-19fa-41d2-8238-13269b9b3ca0.jsonl",
  "cwd": "/Users/...",
  "hook_event_name": "PreCompact",
  "trigger": "manual",
  "custom_instructions": ""
}
```

### PostCompact

Runs after Claude Code completes a compact operation. Use this event to react to the new compacted state, for example to log the generated summary or update external state.

The same matcher values apply as for `PreCompact`:

| Matcher  | When it fires                                      |
| :------- | :------------------------------------------------- |
| `manual` | After `/compact`                                   |
| `auto`   | After auto-compact when the context window is full |

#### PostCompact input

In addition to the [common input fields](#common-input-fields), PostCompact hooks receive `trigger` and `compact_summary`. The `compact_summary` field contains the conversation summary generated by the compact operation.

```json theme={null}
{
  "session_id": "abc123",
  "transcript_path": "/Users/.../.claude/projects/.../00893aaf-19fa-41d2-8238-13269b9b3ca0.jsonl",
  "cwd": "/Users/...",
  "hook_event_name": "PostCompact",
  "trigger": "manual",
  "compact_summary": "Summary of the compacted conversation..."
}
```

PostCompact hooks have no decision control. They cannot affect the compaction result but can perform follow-up tasks.

### SessionEnd

Runs when a Claude Code session ends. Useful for cleanup tasks, logging session
statistics, or saving session state. Supports matchers to filter by exit reason.

The `reason` field in the hook input indicates why the session ended:

| Reason                        | Description                                |
| :---------------------------- | :----------------------------------------- |
| `clear`                       | Session cleared with `/clear` command      |
| `resume`                      | Session switched via interactive `/resume` |
| `logout`                      | User logged out                            |
| `prompt_input_exit`           | User exited while prompt input was visible |
| `bypass_permissions_disabled` | Bypass permissions mode was disabled       |
| `other`                       | Other exit reasons                         |

#### SessionEnd input

In addition to the [common input fields](#common-input-fields), SessionEnd hooks receive a `reason` field indicating why the session ended. See the [reason table](#sessionend) above for all values.

```json theme={null}
{
  "session_id": "abc123",
  "transcript_path": "/Users/.../.claude/projects/.../00893aaf-19fa-41d2-8238-13269b9b3ca0.jsonl",
  "cwd": "/Users/...",
  "hook_event_name": "SessionEnd",
  "reason": "other"
}
```

SessionEnd hooks have no decision control. They cannot block session termination but can perform cleanup tasks.

SessionEnd hooks have a default timeout of 1.5 seconds. This applies to session exit, `/clear`, and switching sessions via interactive `/resume`. If a hook needs more time, set a per-hook `timeout` in the hook configuration. The overall budget is automatically raised to the highest per-hook timeout configured in settings files, up to 60 seconds. Timeouts set on plugin-provided hooks do not raise the budget. To override the budget explicitly, set the `CLAUDE_CODE_SESSIONEND_HOOKS_TIMEOUT_MS` environment variable in milliseconds.

```bash theme={null}
CLAUDE_CODE_SESSIONEND_HOOKS_TIMEOUT_MS=5000 claude
```

### Elicitation

Runs when an MCP server requests user input mid-task. By default, Claude Code shows an interactive dialog for the user to respond. Hooks can intercept this request and respond programmatically, skipping the dialog entirely.

The matcher field matches against the MCP server name.

#### Elicitation input

In addition to the [common input fields](#common-input-fields), Elicitation hooks receive `mcp_server_name`, `message`, and optional `mode`, `url`, `elicitation_id`, and `requested_schema` fields.

For form-mode elicitation (the most common case):

```json theme={null}
{
  "session_id": "abc123",
  "transcript_path": "/Users/.../.claude/projects/.../00893aaf-19fa-41d2-8238-13269b9b3ca0.jsonl",
  "cwd": "/Users/...",
  "permission_mode": "default",
  "hook_event_name": "Elicitation",
  "mcp_server_name": "my-mcp-server",
  "message": "Please provide your credentials",
  "mode": "form",
  "requested_schema": {
    "type": "object",
    "properties": {
      "username": { "type": "string", "title": "Username" }
    }
  }
}
```

For URL-mode elicitation (browser-based authentication):

```json theme={null}
{
  "session_id": "abc123",
  "transcript_path": "/Users/.../.claude/projects/.../00893aaf-19fa-41d2-8238-13269b9b3ca0.jsonl",
  "cwd": "/Users/...",
  "permission_mode": "default",
  "hook_event_name": "Elicitation",
  "mcp_server_name": "my-mcp-server",
  "message": "Please authenticate",
  "mode": "url",
  "url": "https://auth.example.com/login"
}
```

#### Elicitation output

To respond programmatically without showing the dialog, return a JSON object with `hookSpecificOutput`:

```json theme={null}
{
  "hookSpecificOutput": {
    "hookEventName": "Elicitation",
    "action": "accept",
    "content": {
      "username": "alice"
    }
  }
}
```

| Field     | Values                        | Description                                                      |
| :-------- | :---------------------------- | :--------------------------------------------------------------- |
| `action`  | `accept`, `decline`, `cancel` | Whether to accept, decline, or cancel the request                |
| `content` | object                        | Form field values to submit. Only used when `action` is `accept` |

Exit code 2 denies the elicitation and shows stderr to the user.

### ElicitationResult

Runs after a user responds to an MCP elicitation. Hooks can observe, modify, or block the response before it is sent back to the MCP server.

The matcher field matches against the MCP server name.

#### ElicitationResult input

In addition to the [common input fields](#common-input-fields), ElicitationResult hooks receive `mcp_server_name`, `action`, and optional `mode`, `elicitation_id`, and `content` fields.

```json theme={null}
{
  "session_id": "abc123",
  "transcript_path": "/Users/.../.claude/projects/.../00893aaf-19fa-41d2-8238-13269b9b3ca0.jsonl",
  "cwd": "/Users/...",
  "permission_mode": "default",
  "hook_event_name": "ElicitationResult",
  "mcp_server_name": "my-mcp-server",
  "action": "accept",
  "content": { "username": "alice" },
  "mode": "form",
  "elicitation_id": "elicit-123"
}
```

#### ElicitationResult output

To override the user's response, return a JSON object with `hookSpecificOutput`:

```json theme={null}
{
  "hookSpecificOutput": {
    "hookEventName": "ElicitationResult",
    "action": "decline",
    "content": {}
  }
}
```

| Field     | Values                        | Description                                                            |
| :-------- | :---------------------------- | :--------------------------------------------------------------------- |
| `action`  | `accept`, `decline`, `cancel` | Overrides the user's action                                            |
| `content` | object                        | Overrides form field values. Only meaningful when `action` is `accept` |

Exit code 2 blocks the response, changing the effective action to `decline`.

## Prompt-based hooks

In addition to command, HTTP, and MCP tool hooks, Claude Code supports prompt-based hooks (`type: "prompt"`) that use an LLM to evaluate whether to allow or block an action, and agent hooks (`type: "agent"`) that spawn an agentic verifier with tool access. Not all events support every hook type.

Events that support all five hook types (`command`, `http`, `mcp_tool`, `prompt`, and `agent`):

* `PermissionDenied`
* `PermissionRequest`
* `PostToolBatch`
* `PostToolUse`
* `PostToolUseFailure`
* `PreToolUse`
* `Stop`
* `SubagentStop`
* `TaskCompleted`
* `TaskCreated`
* `TeammateIdle`
* `UserPromptExpansion`
* `UserPromptSubmit`

Events that support `command`, `http`, and `mcp_tool` hooks but not `prompt` or `agent`:

* `ConfigChange`
* `CwdChanged`
* `Elicitation`
* `ElicitationResult`
* `FileChanged`
* `InstructionsLoaded`
* `Notification`
* `PostCompact`
* `PreCompact`
* `SessionEnd`
* `StopFailure`
* `SubagentStart`
* `WorktreeCreate`
* `WorktreeRemove`

`SessionStart` and `Setup` support `command` and `mcp_tool` hooks. They do not support `http`, `prompt`, or `agent` hooks.

### How prompt-based hooks work

Instead of executing a Bash command, prompt-based hooks:

1. Send the hook input and your prompt to a Claude model, Haiku by default
2. The LLM responds with structured JSON containing a decision
3. Claude Code processes the decision automatically

### Prompt hook configuration

Set `type` to `"prompt"` and provide a `prompt` string instead of a `command`. Use the `$ARGUMENTS` placeholder to inject the hook's JSON input data into your prompt text. Claude Code sends the combined prompt and input to a fast Claude model, which returns a JSON decision.

This `Stop` hook asks the LLM to evaluate whether all tasks are complete before allowing Claude to finish:

```json theme={null}
{
  "hooks": {
    "Stop": [
      {
        "hooks": [
          {
            "type": "prompt",
            "prompt": "Evaluate if Claude should stop: $ARGUMENTS. Check if all tasks are complete."
          }
        ]
      }
    ]
  }
}
```

| Field             | Required | Description                                                                                                                                                                                                                                                           |
| :---------------- | :------- | :-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `type`            | yes      | Must be `"prompt"`                                                                                                                                                                                                                                                    |
| `prompt`          | yes      | The prompt text to send to the LLM. Use `$ARGUMENTS` as a placeholder for the hook input JSON. If `$ARGUMENTS` is not present, input JSON is appended to the prompt                                                                                                   |
| `model`           | no       | Model to use for evaluation. Defaults to a fast model                                                                                                                                                                                                                 |
| `timeout`         | no       | Timeout in seconds. Default: 30                                                                                                                                                                                                                                       |
| `continueOnBlock` | no       | When the prompt returns `ok: false`, feed the reason back to Claude and continue the turn instead of stopping. Default: `false`. Implemented as `continue: true` on the resulting `decision: "block"`. See [Response schema](#response-schema) for per-event behavior |

### Response schema

The LLM must respond with JSON containing:

```json theme={null}
{
  "ok": true | false,
  "reason": "Explanation for the decision"
}
```

| Field    | Description                                                                               |
| :------- | :---------------------------------------------------------------------------------------- |
| `ok`     | `true` to allow. `false` produces a `decision: "block"`. See the per-event behavior below |
| `reason` | Required when `ok` is `false`. Used as the block reason                                   |

What happens on `ok: false` depends on the event:

* `Stop` and `SubagentStop`: the reason is fed back to Claude as its next instruction and the turn continues
* `PreToolUse`: the tool call is denied and the reason is returned to Claude as the tool error, equivalent to a command hook's `permissionDecision: "deny"`
* `PostToolUse`: by default the turn ends and the reason appears in the chat as a warning line. Set `continueOnBlock: true` to feed the reason back to Claude and continue the turn instead
* `PostToolBatch`, `UserPromptSubmit`, and `UserPromptExpansion`: the turn ends and the reason appears as a warning line. These events end the turn on `decision: "block"` regardless of `continue`
* `PostToolUseFailure`, `TaskCreated`, and `TaskCompleted`: the reason is returned to Claude as a tool error, similar to `PreToolUse`
* `TeammateIdle`: by default the teammate stops and the reason appears as a warning line. Set `continueOnBlock: true` to feed the reason back to the teammate and keep it working instead
* `PermissionRequest`: `ok: false` has no effect. To deny an approval from a hook, use a [command hook](#command-hook-fields) returning `hookSpecificOutput.decision.behavior: "deny"`
* `PermissionDenied`: `ok: false` has no effect because the denial already happened. The only output this event reads is `hookSpecificOutput.retry`, which prompt and agent hooks cannot set. They run on this event, but their output is discarded. Use a [command hook](#command-hook-fields) to return `retry`

If you need finer control on any event, use a [command hook](#command-hook-fields) with the per-event fields described in [Decision control](#decision-control).

### Example: Multi-criteria Stop hook

This `Stop` hook uses a detailed prompt to check three conditions before allowing Claude to stop. If `"ok"` is `false`, Claude continues working with the provided reason as its next instruction. `SubagentStop` hooks use the same format to evaluate whether a [subagent](/en/sub-agents) should stop:

```json theme={null}
{
  "hooks": {
    "Stop": [
      {
        "hooks": [
          {
            "type": "prompt",
            "prompt": "You are evaluating whether Claude should stop working. Context: $ARGUMENTS\n\nAnalyze the conversation and determine if:\n1. All user-requested tasks are complete\n2. Any errors need to be addressed\n3. Follow-up work is needed\n\nRespond with JSON: {\"ok\": true} to allow stopping, or {\"ok\": false, \"reason\": \"your explanation\"} to continue working.",
            "timeout": 30
          }
        ]
      }
    ]
  }
}
```

## Agent-based hooks

<Warning>
  Agent hooks are experimental. Behavior and configuration may change in future releases. For production workflows, prefer [command hooks](#command-hook-fields).
</Warning>

Agent-based hooks (`type: "agent"`) are like prompt-based hooks but with multi-turn tool access. Instead of a single LLM call, an agent hook spawns a subagent that can read files, search code, and inspect the codebase to verify conditions. Agent hooks support the same events as prompt-based hooks.

### How agent hooks work

When an agent hook fires:

1. Claude Code spawns a subagent with your prompt and the hook's JSON input
2. The subagent can use tools like Read, Grep, and Glob to investigate
3. After up to 50 turns, the subagent returns a structured `{ "ok": true/false }` decision
4. Claude Code processes the decision the same way as a prompt hook

Agent hooks are useful when verification requires inspecting actual files or test output, not just evaluating the hook input data alone.

### Agent hook configuration

Set `type` to `"agent"` and provide a `prompt` string. The configuration fields are the same as [prompt hooks](#prompt-hook-configuration), with a longer default timeout:

| Field     | Required | Description                                                                                 |
| :-------- | :------- | :------------------------------------------------------------------------------------------ |
| `type`    | yes      | Must be `"agent"`                                                                           |
| `prompt`  | yes      | Prompt describing what to verify. Use `$ARGUMENTS` as a placeholder for the hook input JSON |
| `model`   | no       | Model to use. Defaults to a fast model                                                      |
| `timeout` | no       | Timeout in seconds. Default: 60                                                             |

The response schema is the same as prompt hooks: `{ "ok": true }` to allow or `{ "ok": false, "reason": "..." }` to block.

This `Stop` hook verifies that all unit tests pass before allowing Claude to finish:

```json theme={null}
{
  "hooks": {
    "Stop": [
      {
        "hooks": [
          {
            "type": "agent",
            "prompt": "Verify that all unit tests pass. Run the test suite and check the results. $ARGUMENTS",
            "timeout": 120
          }
        ]
      }
    ]
  }
}
```

## Run hooks in the background

By default, hooks block Claude's execution until they complete. For long-running tasks like deployments, test suites, or external API calls, set `"async": true` to run the hook in the background while Claude continues working. Async hooks cannot block or control Claude's behavior: response fields like `decision`, `permissionDecision`, and `continue` have no effect, because the action they would have controlled has already completed.

### Configure an async hook

Add `"async": true` to a command hook's configuration to run it in the background without blocking Claude. This field is only available on `type: "command"` hooks.

This hook runs a test script after every `Write` tool call. Claude continues working immediately while `run-tests.sh` executes for up to 120 seconds. When the script finishes, its output is delivered on the next conversation turn:

```json theme={null}
{
  "hooks": {
    "PostToolUse": [
      {
        "matcher": "Write",
        "hooks": [
          {
            "type": "command",
            "command": "/path/to/run-tests.sh",
            "async": true,
            "timeout": 120
          }
        ]
      }
    ]
  }
}
```

The `timeout` field sets the maximum time in seconds for the background process. If not specified, async hooks use the same 10-minute default as sync hooks.

### How async hooks execute

When an async hook fires, Claude Code starts the hook process and immediately continues without waiting for it to finish. The hook receives the same JSON input via stdin as a synchronous hook.

After the background process exits, if the hook produced a JSON response with an `additionalContext` field, that content is delivered to Claude as context on the next conversation turn. A `systemMessage` field is shown to you, not to Claude.

Async hook completion notifications are suppressed by default. To see them, enable verbose mode with `Ctrl+O` or start Claude Code with `--verbose`.

### Example: run tests after file changes

This hook starts a test suite in the background whenever Claude writes a file, then reports the results back to Claude when the tests finish. Save this script to `.claude/hooks/run-tests-async.sh` in your project and make it executable with `chmod +x`:

```bash theme={null}
#!/bin/bash
# run-tests-async.sh

# Read hook input from stdin
INPUT=$(cat)
FILE_PATH=$(echo "$INPUT" | jq -r '.tool_input.file_path // empty')

# Only run tests for source files
if [[ "$FILE_PATH" != *.ts && "$FILE_PATH" != *.js ]]; then
  exit 0
fi

# Run tests and report results to Claude via additionalContext
RESULT=$(npm test 2>&1)
EXIT_CODE=$?

if [ $EXIT_CODE -eq 0 ]; then
  MSG="Tests passed after editing $FILE_PATH"
else
  MSG="Tests failed after editing $FILE_PATH: $RESULT"
fi
jq -nc --arg msg "$MSG" '{hookSpecificOutput: {hookEventName: "PostToolUse", additionalContext: $msg}}'
```

Then add this configuration to `.claude/settings.json` in your project root. The `async: true` flag lets Claude keep working while tests run:

```json theme={null}
{
  "hooks": {
    "PostToolUse": [
      {
        "matcher": "Write|Edit",
        "hooks": [
          {
            "type": "command",
            "command": "${CLAUDE_PROJECT_DIR}/.claude/hooks/run-tests-async.sh",
            "args": [],
            "async": true,
            "timeout": 300
          }
        ]
      }
    ]
  }
}
```

### Limitations

Async hooks have several constraints compared to synchronous hooks:

* Only `type: "command"` hooks support `async`. Prompt-based hooks cannot run asynchronously.
* Async hooks cannot block tool calls or return decisions. By the time the hook completes, the triggering action has already proceeded.
* Hook output is delivered on the next conversation turn. If the session is idle, the response waits until the next user interaction. Exception: an `asyncRewake` hook that exits with code 2 wakes Claude immediately even when the session is idle.
* Each execution creates a separate background process. There is no deduplication across multiple firings of the same async hook.

## Security considerations

### Disclaimer

Command hooks run with your system user's full permissions.

<Warning>
  Command hooks execute shell commands with your full user permissions. They can modify, delete, or access any files your user account can access. Review and test all hook commands before adding them to your configuration.
</Warning>

### Security best practices

Keep these practices in mind when writing hooks:

* **Validate and sanitize inputs**: never trust input data blindly
* **Always quote shell variables**: use `"$VAR"` not `$VAR`
* **Block path traversal**: check for `..` in file paths
* **Use absolute paths**: specify full paths for scripts. In exec form, use `${CLAUDE_PROJECT_DIR}` and the path needs no quoting. In shell form, wrap it in double quotes
* **Skip sensitive files**: avoid `.env`, `.git/`, keys, etc.

## Windows PowerShell tool

On Windows, you can run individual hooks in PowerShell by setting `"shell": "powershell"` on a command hook. Hooks spawn PowerShell directly, so this works regardless of whether `CLAUDE_CODE_USE_POWERSHELL_TOOL` is set. Claude Code auto-detects `pwsh.exe` (PowerShell 7+) with a fallback to `powershell.exe` (5.1).

```json theme={null}
{
  "hooks": {
    "PostToolUse": [
      {
        "matcher": "Write",
        "hooks": [
          {
            "type": "command",
            "shell": "powershell",
            "command": "Write-Host 'File written'"
          }
        ]
      }
    ]
  }
}
```

To reference the project root from a PowerShell shell-form command, read it as an environment variable with `$env:CLAUDE_PROJECT_DIR`. PowerShell treats the bare `${CLAUDE_PROJECT_DIR}` form as a local variable, not an environment lookup, and Claude Code substitutes that placeholder in shell form only for [plugin hooks](#reference-scripts-by-path). For a hook defined in `settings.json`, either use the `$env:` form or switch to [exec form](#exec-form-and-shell-form), where `${CLAUDE_PROJECT_DIR}` is substituted in each `args` element regardless of where the hook is defined.

The example below shows a `settings.json` hook that runs a project script with the `$env:` form:

```json theme={null}
{
  "type": "command",
  "shell": "powershell",
  "command": "& \"$env:CLAUDE_PROJECT_DIR\\.claude\\hooks\\check.ps1\""
}
```

## Debug hooks

Hook execution details, including which hooks matched, their exit codes, and full stdout and stderr, are written to the debug log file. Start Claude Code with `claude --debug-file <path>` to write the log to a known location, or run `claude --debug` and read the log at `~/.claude/debug/<session-id>.txt`. The `--debug` flag does not print to the terminal.

```text theme={null}
[DEBUG] Executing hooks for PostToolUse:Write
[DEBUG] Found 1 hook commands to execute
[DEBUG] Executing hook command: <Your command> with timeout 600000ms
[DEBUG] Hook command completed with status 0: <Your stdout>
```

For more granular hook matching details, set `CLAUDE_CODE_DEBUG_LOG_LEVEL=verbose` to see additional log lines such as hook matcher counts and query matching.

For troubleshooting common issues like hooks not firing, Stop hooks that keep blocking, or configuration errors, see [Limitations and troubleshooting](/en/hooks-guide#limitations-and-troubleshooting) in the guide. For a broader diagnostic walkthrough covering `/context`, `/doctor`, and settings precedence, see [Debug your config](/en/debug-your-config).


================================================================================
# PAGE: hooks-guide
# source: https://docs.claude.com/en/docs/claude-code/hooks-guide.md
================================================================================

> ## Documentation Index
> Fetch the complete documentation index at: https://code.claude.com/docs/llms.txt
> Use this file to discover all available pages before exploring further.

# Automate actions with hooks

> Run shell commands automatically when Claude Code edits files, finishes tasks, or needs input. Format code, send notifications, validate commands, and enforce project rules.

Hooks are user-defined shell commands that execute at specific points in Claude Code's lifecycle. They provide deterministic control over Claude Code's behavior, ensuring certain actions always happen rather than relying on the LLM to choose to run them. Use hooks to enforce project rules, automate repetitive tasks, and integrate Claude Code with your existing tools.

For decisions that require judgment rather than deterministic rules, you can also use [prompt-based hooks](#prompt-based-hooks) or [agent-based hooks](#agent-based-hooks) that use a Claude model to evaluate conditions.

For other ways to extend Claude Code, see [skills](/en/skills) for giving Claude additional instructions and executable commands, [subagents](/en/sub-agents) for running tasks in isolated contexts, and [plugins](/en/plugins) for packaging extensions to share across projects.

<Tip>
  This guide covers common use cases and how to get started. For full event schemas, JSON input/output formats, and advanced features like async hooks and MCP tool hooks, see the [Hooks reference](/en/hooks).
</Tip>

## Set up your first hook

To create a hook, add a `hooks` block to a [settings file](#configure-hook-location). This walkthrough creates a desktop notification hook, so you get alerted whenever Claude is waiting for your input instead of watching the terminal.

<Steps>
  <Step title="Add the hook to your settings">
    Open `~/.claude/settings.json` and add a `Notification` hook. The example below uses `osascript` for macOS; see [Get notified when Claude needs input](#get-notified-when-claude-needs-input) for Linux and Windows commands.

    ```json theme={null}
    {
      "hooks": {
        "Notification": [
          {
            "matcher": "",
            "hooks": [
              {
                "type": "command",
                "command": "osascript -e 'display notification \"Claude Code needs your attention\" with title \"Claude Code\"'"
              }
            ]
          }
        ]
      }
    }
    ```

    If your settings file already has a `hooks` key, add `Notification` as a sibling of the existing event keys rather than replacing the whole object. Each event name is a key inside the single `hooks` object:

    ```json theme={null}
    {
      "hooks": {
        "PostToolUse": [
          {
            "matcher": "Edit|Write",
            "hooks": [{ "type": "command", "command": "jq -r '.tool_input.file_path' | xargs npx prettier --write" }]
          }
        ],
        "Notification": [
          {
            "matcher": "",
            "hooks": [{ "type": "command", "command": "osascript -e 'display notification \"Claude Code needs your attention\" with title \"Claude Code\"'" }]
          }
        ]
      }
    }
    ```

    You can also ask Claude to write the hook for you by describing what you want in the CLI.
  </Step>

  <Step title="Verify the configuration">
    Type `/hooks` to open the hooks browser. You'll see a list of all available hook events, with a count next to each event that has hooks configured. Select `Notification` to confirm your new hook appears in the list. Selecting the hook shows its details: the event, matcher, type, source file, and command.
  </Step>

  <Step title="Test the hook">
    Press `Esc` to return to the CLI. Ask Claude to do something that requires permission, then switch away from the terminal. You should receive a desktop notification.
  </Step>
</Steps>

<Tip>
  The `/hooks` menu is read-only. To add, modify, or remove hooks, edit your settings JSON directly or ask Claude to make the change.
</Tip>

## What you can automate

Hooks let you run code at key points in Claude Code's lifecycle: format files after edits, block commands before they execute, send notifications when Claude needs input, inject context at session start, and more. For the full list of hook events, see the [Hooks reference](/en/hooks#hook-lifecycle).

Each example includes a ready-to-use configuration block that you add to a [settings file](#configure-hook-location). The most common patterns:

* [Get notified when Claude needs input](#get-notified-when-claude-needs-input)
* [Auto-format code after edits](#auto-format-code-after-edits)
* [Block edits to protected files](#block-edits-to-protected-files)
* [Re-inject context after compaction](#re-inject-context-after-compaction)
* [Audit configuration changes](#audit-configuration-changes)
* [Reload environment when directory or files change](#reload-environment-when-directory-or-files-change)
* [Auto-approve specific permission prompts](#auto-approve-specific-permission-prompts)

For a production example of hooks that run a separate model review and feed findings back into the session, see [how the `security-guidance` plugin integrates with Claude Code](/en/security-guidance#how-the-plugin-integrates-with-claude-code).

### Get notified when Claude needs input

Get a desktop notification whenever Claude finishes working and needs your input, so you can switch to other tasks without checking the terminal.

This hook uses the `Notification` event, which fires when Claude is waiting for input or permission. Each tab below uses the platform's native notification command. Add this to `~/.claude/settings.json`:

<Tabs>
  <Tab title="macOS">
    ```json theme={null}
    {
      "hooks": {
        "Notification": [
          {
            "matcher": "",
            "hooks": [
              {
                "type": "command",
                "command": "osascript -e 'display notification \"Claude Code needs your attention\" with title \"Claude Code\"'"
              }
            ]
          }
        ]
      }
    }
    ```

    <Accordion title="If no notification appears">
      `osascript` routes notifications through the built-in Script Editor app. If Script Editor doesn't have notification permission, the command fails silently, and macOS won't prompt you to grant it. Run this in Terminal once to make Script Editor appear in your notification settings:

      ```bash theme={null}
      osascript -e 'display notification "test"'
      ```

      Nothing will appear yet. Open **System Settings > Notifications**, find **Script Editor** in the list, and turn on **Allow Notifications**. Run the command again to confirm the test notification appears.
    </Accordion>
  </Tab>

  <Tab title="Linux">
    ```json theme={null}
    {
      "hooks": {
        "Notification": [
          {
            "matcher": "",
            "hooks": [
              {
                "type": "command",
                "command": "notify-send 'Claude Code' 'Claude Code needs your attention'"
              }
            ]
          }
        ]
      }
    }
    ```
  </Tab>

  <Tab title="Windows (PowerShell)">
    ```json theme={null}
    {
      "hooks": {
        "Notification": [
          {
            "matcher": "",
            "hooks": [
              {
                "type": "command",
                "command": "powershell.exe -Command \"[System.Reflection.Assembly]::LoadWithPartialName('System.Windows.Forms'); [System.Windows.Forms.MessageBox]::Show('Claude Code needs your attention', 'Claude Code')\""
              }
            ]
          }
        ]
      }
    }
    ```
  </Tab>
</Tabs>

The empty `matcher` fires on all notification types. To fire only on specific events, set it to one of these values:

| Matcher                | Fires when                                             |
| :--------------------- | :----------------------------------------------------- |
| `permission_prompt`    | Claude needs you to approve a tool use                 |
| `idle_prompt`          | Claude is done and waiting for your next prompt        |
| `auth_success`         | Authentication completes                               |
| `elicitation_dialog`   | An MCP server opens an elicitation form                |
| `elicitation_complete` | An MCP elicitation form is submitted or dismissed      |
| `elicitation_response` | An MCP elicitation response is sent back to the server |

Type `/hooks` and select `Notification` to confirm the hook is registered. For the full event schema, see the [Notification reference](/en/hooks#notification).

### Auto-format code after edits

Automatically run [Prettier](https://prettier.io/) on every file Claude edits, so formatting stays consistent without manual intervention.

This hook uses the `PostToolUse` event with an `Edit|Write` matcher, so it runs only after file-editing tools. {/* min-version: 2.1.191 */}On Claude Code v2.1.191 or later you can also write the matcher as `Edit,Write`, since `|` and `,` are interchangeable list separators for tool-name matchers on those versions. The command extracts the edited file path with [`jq`](https://jqlang.github.io/jq/) and passes it to Prettier. Add this to `.claude/settings.json` in your project root:

```json theme={null}
{
  "hooks": {
    "PostToolUse": [
      {
        "matcher": "Edit|Write",
        "hooks": [
          {
            "type": "command",
            "command": "jq -r '.tool_input.file_path' | xargs npx prettier --write"
          }
        ]
      }
    ]
  }
}
```

<Note>
  The Bash examples on this page use `jq` for JSON parsing. Install it with `brew install jq` (macOS), `apt-get install jq` (Debian/Ubuntu), or see [`jq` downloads](https://jqlang.github.io/jq/download/).
</Note>

### Block edits to protected files

Prevent Claude from modifying sensitive files like `.env`, `package-lock.json`, or anything in `.git/`. Claude receives feedback explaining why the edit was blocked, so it can adjust its approach.

This example uses a separate script file that the hook calls. The script checks the target file path against a list of protected patterns and exits with code 2 to block the edit.

<Steps>
  <Step title="Create the hook script">
    Save this to `.claude/hooks/protect-files.sh`:

    ```bash theme={null}
    #!/bin/bash
    # protect-files.sh

    INPUT=$(cat)
    FILE_PATH=$(echo "$INPUT" | jq -r '.tool_input.file_path // empty')

    PROTECTED_PATTERNS=(".env" "package-lock.json" ".git/")

    for pattern in "${PROTECTED_PATTERNS[@]}"; do
      if [[ "$FILE_PATH" == *"$pattern"* ]]; then
        echo "Blocked: $FILE_PATH matches protected pattern '$pattern'" >&2
        exit 2
      fi
    done

    exit 0
    ```
  </Step>

  <Step title="Make the script executable (macOS/Linux)">
    Hook scripts must be executable for Claude Code to run them:

    ```bash theme={null}
    chmod +x .claude/hooks/protect-files.sh
    ```
  </Step>

  <Step title="Register the hook">
    Add a `PreToolUse` hook to `.claude/settings.json` that runs the script before any `Edit` or `Write` tool call:

    ```json theme={null}
    {
      "hooks": {
        "PreToolUse": [
          {
            "matcher": "Edit|Write",
            "hooks": [
              {
                "type": "command",
                "command": "\"$CLAUDE_PROJECT_DIR\"/.claude/hooks/protect-files.sh"
              }
            ]
          }
        ]
      }
    }
    ```
  </Step>
</Steps>

### Re-inject context after compaction

When Claude's context window fills up, compaction summarizes the conversation to free space. This can lose important details. Use a `SessionStart` hook with a `compact` matcher to re-inject critical context after every compaction.

Any text your command writes to stdout is added to Claude's context. This example reminds Claude of project conventions and recent work. Add this to `.claude/settings.json` in your project root:

```json theme={null}
{
  "hooks": {
    "SessionStart": [
      {
        "matcher": "compact",
        "hooks": [
          {
            "type": "command",
            "command": "echo 'Reminder: use Bun, not npm. Run bun test before committing. Current sprint: auth refactor.'"
          }
        ]
      }
    ]
  }
}
```

You can replace the `echo` with any command that produces dynamic output, like `git log --oneline -5` to show recent commits. For injecting context on every session start, consider using [CLAUDE.md](/en/memory) instead. For environment variables, see [`CLAUDE_ENV_FILE`](/en/hooks#persist-environment-variables) in the reference.

### Audit configuration changes

Track when settings or skills files change during a session. The `ConfigChange` event fires when an external process or editor modifies a configuration file, so you can log changes for compliance or block unauthorized modifications.

This example appends each change to an audit log. Add this to `~/.claude/settings.json`:

```json theme={null}
{
  "hooks": {
    "ConfigChange": [
      {
        "matcher": "",
        "hooks": [
          {
            "type": "command",
            "command": "jq -c '{timestamp: now | todate, source: .source, file: .file_path}' >> ~/claude-config-audit.log"
          }
        ]
      }
    ]
  }
}
```

The matcher filters by configuration type: `user_settings`, `project_settings`, `local_settings`, `policy_settings`, or `skills`. To block a change from taking effect, exit with code 2 or return `{"decision": "block"}`. See the [ConfigChange reference](/en/hooks#configchange) for the full input schema.

### Reload environment when directory or files change

Some projects set different environment variables depending on which directory you are in. Tools like [direnv](https://direnv.net/) do this automatically in your shell, but Claude's Bash tool does not pick up those changes on its own.

Pairing a `SessionStart` hook with a `CwdChanged` hook fixes this. `SessionStart` loads the variables for the directory you launch in, and `CwdChanged` reloads them each time Claude changes directory. Both write to `CLAUDE_ENV_FILE`, which Claude Code runs as a script preamble before each Bash command. Add this to `~/.claude/settings.json`:

```json theme={null}
{
  "hooks": {
    "SessionStart": [
      {
        "hooks": [
          {
            "type": "command",
            "command": "direnv export bash > \"$CLAUDE_ENV_FILE\""
          }
        ]
      }
    ],
    "CwdChanged": [
      {
        "hooks": [
          {
            "type": "command",
            "command": "direnv export bash > \"$CLAUDE_ENV_FILE\""
          }
        ]
      }
    ]
  }
}
```

Run `direnv allow` once in each directory that has an `.envrc` so direnv is permitted to load it. If you use devbox or nix instead of direnv, the same pattern works with `devbox shellenv` or `devbox global shellenv` in place of `direnv export bash`.

To react to specific files instead of every directory change, use `FileChanged` with a `matcher` listing the filenames to watch, separated by `|`. To build the watch list, this value is split into literal filenames rather than evaluated as a regex. See [FileChanged](/en/hooks#filechanged) for how the same value also filters which hook groups run when a file changes. This example watches `.envrc` and `.env` in the working directory:

```json theme={null}
{
  "hooks": {
    "FileChanged": [
      {
        "matcher": ".envrc|.env",
        "hooks": [
          {
            "type": "command",
            "command": "direnv export bash > \"$CLAUDE_ENV_FILE\""
          }
        ]
      }
    ]
  }
}
```

See the [CwdChanged](/en/hooks#cwdchanged) and [FileChanged](/en/hooks#filechanged) reference entries for input schemas, `watchPaths` output, and `CLAUDE_ENV_FILE` details.

### Auto-approve specific permission prompts

Skip the approval dialog for tool calls you always allow. This example auto-approves `ExitPlanMode`, the tool Claude calls when it finishes presenting a plan and asks to proceed, so you aren't prompted every time a plan is ready.

Unlike the exit-code examples above, auto-approval requires your hook to write a JSON decision to stdout. A `PermissionRequest` hook fires when Claude Code is about to show a permission dialog, and returning `"behavior": "allow"` answers it on your behalf.

The matcher scopes the hook to `ExitPlanMode` only, so no other prompts are affected. Add this to `~/.claude/settings.json`:

```json theme={null}
{
  "hooks": {
    "PermissionRequest": [
      {
        "matcher": "ExitPlanMode",
        "hooks": [
          {
            "type": "command",
            "command": "echo '{\"hookSpecificOutput\": {\"hookEventName\": \"PermissionRequest\", \"decision\": {\"behavior\": \"allow\"}}}'"
          }
        ]
      }
    ]
  }
}
```

When the hook approves, Claude Code exits plan mode and restores whatever permission mode was active before you entered plan mode. The transcript shows "Allowed by PermissionRequest hook" where the dialog would have appeared. The hook path always keeps the current conversation: it cannot clear context and start a fresh implementation session the way the dialog can.

To set a specific permission mode instead, your hook's output can include an `updatedPermissions` array with a `setMode` entry. The `mode` value is any permission mode like `default`, `acceptEdits`, or `bypassPermissions`, and `destination: "session"` applies it for the current session only.

<Note>
  `bypassPermissions` only applies if the session was launched with bypass mode already available: `--dangerously-skip-permissions`, `--permission-mode bypassPermissions`, `--allow-dangerously-skip-permissions`, or `permissions.defaultMode: "bypassPermissions"` in settings, and not disabled by [`permissions.disableBypassPermissionsMode`](/en/permissions#managed-settings). It is never persisted as `defaultMode`.
</Note>

To switch the session to `acceptEdits`, your hook writes this JSON to stdout:

```json theme={null}
{
  "hookSpecificOutput": {
    "hookEventName": "PermissionRequest",
    "decision": {
      "behavior": "allow",
      "updatedPermissions": [
        { "type": "setMode", "mode": "acceptEdits", "destination": "session" }
      ]
    }
  }
}
```

Keep the matcher as narrow as possible. Matching on `.*` or leaving the matcher empty would auto-approve every permission prompt, including file writes and shell commands. See the [PermissionRequest reference](/en/hooks#permissionrequest-decision-control) for the full set of decision fields.

## How hooks work

Hook events fire at specific lifecycle points in Claude Code. When an event fires, all matching hooks run in parallel, and identical hook commands are automatically deduplicated. The table below shows each event and when it triggers:

| Event                 | When it fires                                                                                                                                          |
| :-------------------- | :----------------------------------------------------------------------------------------------------------------------------------------------------- |
| `SessionStart`        | When a session begins or resumes                                                                                                                       |
| `Setup`               | When you start Claude Code with `--init-only`, or with `--init` or `--maintenance` in `-p` mode. For one-time preparation in CI or scripts             |
| `UserPromptSubmit`    | When you submit a prompt, before Claude processes it                                                                                                   |
| `UserPromptExpansion` | When a user-typed command expands into a prompt, before it reaches Claude. Can block the expansion                                                     |
| `PreToolUse`          | Before a tool call executes. Can block it                                                                                                              |
| `PermissionRequest`   | When a permission dialog appears                                                                                                                       |
| `PermissionDenied`    | When a tool call is denied by the auto mode classifier. Return `{retry: true}` to tell the model it may retry the denied tool call                     |
| `PostToolUse`         | After a tool call succeeds                                                                                                                             |
| `PostToolUseFailure`  | After a tool call fails                                                                                                                                |
| `PostToolBatch`       | After a full batch of parallel tool calls resolves, before the next model call                                                                         |
| `Notification`        | When Claude Code sends a notification                                                                                                                  |
| `MessageDisplay`      | While assistant message text is displayed                                                                                                              |
| `SubagentStart`       | When a subagent is spawned                                                                                                                             |
| `SubagentStop`        | When a subagent finishes                                                                                                                               |
| `TaskCreated`         | When a task is being created via `TaskCreate`                                                                                                          |
| `TaskCompleted`       | When a task is being marked as completed                                                                                                               |
| `Stop`                | When Claude finishes responding                                                                                                                        |
| `StopFailure`         | When the turn ends due to an API error. Output and exit code are ignored                                                                               |
| `TeammateIdle`        | When an [agent team](/en/agent-teams) teammate is about to go idle                                                                                     |
| `InstructionsLoaded`  | When a CLAUDE.md or `.claude/rules/*.md` file is loaded into context. Fires at session start and when files are lazily loaded during a session         |
| `ConfigChange`        | When a configuration file changes during a session                                                                                                     |
| `CwdChanged`          | When the working directory changes, for example when Claude executes a `cd` command. Useful for reactive environment management with tools like direnv |
| `FileChanged`         | When a watched file changes on disk. The `matcher` field specifies which filenames to watch                                                            |
| `WorktreeCreate`      | When a worktree is being created via `--worktree` or `isolation: "worktree"`. Replaces default git behavior                                            |
| `WorktreeRemove`      | When a worktree is being removed, either at session exit or when a subagent finishes                                                                   |
| `PreCompact`          | Before context compaction                                                                                                                              |
| `PostCompact`         | After context compaction completes                                                                                                                     |
| `Elicitation`         | When an MCP server requests user input during a tool call                                                                                              |
| `ElicitationResult`   | After a user responds to an MCP elicitation, before the response is sent back to the server                                                            |
| `SessionEnd`          | When a session terminates                                                                                                                              |

Each hook has a `type` that determines how it runs. Most hooks use `"type": "command"`, which runs a shell command. Four other types are available:

* `"type": "http"`: POST event data to a URL. See [HTTP hooks](#http-hooks).
* `"type": "mcp_tool"`: call a tool on an already-connected MCP server. See [MCP tool hooks](/en/hooks#mcp-tool-hook-fields).
* `"type": "prompt"`: single-turn LLM evaluation. See [Prompt-based hooks](#prompt-based-hooks).
* `"type": "agent"`: multi-turn verification with tool access. Agent hooks are experimental and may change. See [Agent-based hooks](#agent-based-hooks).

### Combine results from multiple hooks

When multiple hooks match the same event, every hook's command runs to completion before Claude Code merges the results. One hook returning `deny` does not stop sibling hooks from executing. Don't rely on one hook's `deny` to suppress side effects in another hook.

After all matching hooks finish, Claude Code combines their outputs. For `PreToolUse` permission decisions, the most restrictive answer wins, in the order `deny`, `defer`, `ask`, `allow`. Text from `additionalContext` is kept from every hook and passed to Claude together.

The example below registers two `PreToolUse` hooks on `Bash`. The first appends every command to a log file and exits 0. The second runs a script that exits 2 to deny when the command contains `rm -rf`:

```json theme={null}
{
  "hooks": {
    "PreToolUse": [
      {
        "matcher": "Bash",
        "hooks": [
          {
            "type": "command",
            "command": "jq -r .tool_input.command >> ~/.claude/bash.log"
          },
          {
            "type": "command",
            "command": "\"$CLAUDE_PROJECT_DIR\"/.claude/hooks/block-rm-rf.sh"
          }
        ]
      }
    ]
  }
}
```

When Claude tries to run `rm -rf /tmp/build`, both hooks execute in parallel. The logging hook writes the command to `~/.claude/bash.log` and exits 0, which reports no decision. The guardrail hook exits 2, which denies the tool call. The deny wins, so Claude Code blocks the command and shows Claude the guardrail's stderr. The log entry is still written because the logging hook already ran.

### Read input and return output

Hooks communicate with Claude Code through stdin, stdout, stderr, and exit codes. When an event fires, Claude Code passes event-specific data as JSON to your script's stdin. Your script reads that data, does its work, and tells Claude Code what to do next via the exit code.

#### Hook input

Every event includes common fields like `session_id` and `cwd`, but each event type adds different data. For example, when Claude runs a Bash command, a `PreToolUse` hook receives something like this on stdin:

```json theme={null}
{
  "session_id": "abc123",          // unique ID for this session
  "cwd": "/Users/sarah/myproject", // working directory when the event fired
  "hook_event_name": "PreToolUse", // which event triggered this hook
  "tool_name": "Bash",             // the tool Claude is about to use
  "tool_input": {                  // the arguments Claude passed to the tool
    "command": "npm test"          // for Bash, this is the shell command
  }
}
```

Your script can parse that JSON and act on any of those fields. `UserPromptSubmit` hooks get the `prompt` text instead, `SessionStart` hooks get the `source` (startup, resume, clear, compact), and so on. See [Common input fields](/en/hooks#common-input-fields) in the reference for shared fields, and each event's section for event-specific schemas.

#### Hook output

Your script tells Claude Code what to do next by writing to stdout or stderr and exiting with a specific code. For example, a `PreToolUse` hook that wants to block a command:

```bash theme={null}
#!/bin/bash
INPUT=$(cat)
COMMAND=$(echo "$INPUT" | jq -r '.tool_input.command')

if echo "$COMMAND" | grep -q "drop table"; then
  echo "Blocked: dropping tables is not allowed" >&2  # stderr becomes Claude's feedback
  exit 2                                               # exit 2 = block the action
fi

exit 0  # exit 0 = no decision; the normal permission flow applies
```

The exit code determines what happens next:

* **Exit 0**: the hook reports no objection and the action proceeds normally. For a `PreToolUse` hook this doesn't approve the tool call: the normal [permission flow](/en/permissions) still applies. For `UserPromptSubmit`, `UserPromptExpansion`, and `SessionStart` hooks, anything you write to stdout is added to Claude's context.
* **Exit 2**: the action is blocked. Write a reason to stderr, and Claude receives it as feedback so it can adjust. Some events cannot be blocked: for `SessionStart`, `Setup`, `Notification`, and others, exit 2 shows stderr to the user and execution continues. See [exit code 2 behavior per event](/en/hooks#exit-code-2-behavior-per-event) for the full list.
* **Any other exit code**: the action proceeds. The transcript shows a `<hook name> hook error` notice followed by the first line of stderr; the full stderr goes to the [debug log](/en/hooks#debug-hooks).

#### Structured JSON output

Exit codes only let you block or stay silent. For more control, exit 0 and print a JSON object to stdout instead.

<Note>
  Use exit 2 to block with a stderr message, or exit 0 with JSON for structured control. Don't mix them: Claude Code ignores JSON when you exit 2.
</Note>

For example, a `PreToolUse` hook can deny a tool call and tell Claude why, or escalate it to the user for approval:

```json theme={null}
{
  "hookSpecificOutput": {
    "hookEventName": "PreToolUse",
    "permissionDecision": "deny",
    "permissionDecisionReason": "Use rg instead of grep for better performance"
  }
}
```

With `"deny"`, Claude Code cancels the tool call and feeds `permissionDecisionReason` back to Claude. These `permissionDecision` values are specific to `PreToolUse`:

* `"allow"`: skip the interactive permission prompt. Deny and ask rules, including enterprise managed deny lists, still apply
* `"deny"`: cancel the tool call and send the reason to Claude
* `"ask"`: show the permission prompt to the user as normal

A fourth value, `"defer"`, is available in [non-interactive mode](/en/headless) with the `-p` flag. It exits the process with the tool call preserved so an Agent SDK wrapper can collect input and resume. See [Defer a tool call for later](/en/hooks#defer-a-tool-call-for-later) in the reference.

Returning `"allow"` skips the interactive prompt but does not override [permission rules](/en/permissions#manage-permissions). If a deny rule matches the tool call, the call is blocked even when your hook returns `"allow"`. If an ask rule matches, the user is still prompted. This means deny rules from any settings scope, including [managed settings](/en/settings#settings-files), always take precedence over hook approvals.

Other events use different decision patterns. For example, `PostToolUse` and `Stop` hooks use a top-level `decision: "block"` field, while `PermissionRequest` uses `hookSpecificOutput.decision.behavior`. See the [summary table](/en/hooks#decision-control) in the reference for a full breakdown by event.

For `UserPromptSubmit` hooks, use `additionalContext` instead to inject text into Claude's context. Prompt-based hooks (`type: "prompt"`) handle output differently: see [Prompt-based hooks](#prompt-based-hooks).

### Filter hooks with matchers

Without a matcher, a hook fires on every occurrence of its event. Matchers let you narrow that down. For example, if you want to run a formatter only after file edits (not after every tool call), add a matcher to your `PostToolUse` hook:

```json theme={null}
{
  "hooks": {
    "PostToolUse": [
      {
        "matcher": "Edit|Write",
        "hooks": [
          { "type": "command", "command": "prettier --write ..." }
        ]
      }
    ]
  }
}
```

The `"Edit|Write"` matcher fires only when Claude uses the `Edit` or `Write` tool, not when it uses `Bash`, `Read`, or any other tool. See [Matcher patterns](/en/hooks#matcher-patterns) for how plain names and regular expressions are evaluated.

<Note>
  Claude can also create or modify files by running shell commands through the `Bash` tool. If your hook must see every file change, such as for compliance scanning or audit logging, add a [`Stop`](/en/hooks#stop) hook that scans the working tree once per turn. For per-call coverage instead, also match `Bash` and have your script list modified and untracked files with `git status --porcelain`.
</Note>

Each event type matches on a specific field:

| Event                                                                                                                                                           | What the matcher filters                                              | Example matcher values                                                                                                                                                              |
| :-------------------------------------------------------------------------------------------------------------------------------------------------------------- | :-------------------------------------------------------------------- | :---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `PreToolUse`, `PostToolUse`, `PostToolUseFailure`, `PermissionRequest`, `PermissionDenied`                                                                      | tool name                                                             | `Bash`, `Edit\|Write`, `mcp__.*`                                                                                                                                                    |
| `SessionStart`                                                                                                                                                  | how the session started                                               | `startup`, `resume`, `clear`, `compact`                                                                                                                                             |
| `Setup`                                                                                                                                                         | which CLI flag triggered setup                                        | `init`, `maintenance`                                                                                                                                                               |
| `SessionEnd`                                                                                                                                                    | why the session ended                                                 | `clear`, `resume`, `logout`, `prompt_input_exit`, `bypass_permissions_disabled`, `other`                                                                                            |
| `Notification`                                                                                                                                                  | notification type                                                     | `permission_prompt`, `idle_prompt`, `auth_success`, `elicitation_dialog`, `elicitation_complete`, `elicitation_response`                                                            |
| `SubagentStart`                                                                                                                                                 | agent type                                                            | `general-purpose`, `Explore`, `Plan`, or custom agent names                                                                                                                         |
| `PreCompact`, `PostCompact`                                                                                                                                     | what triggered compaction                                             | `manual`, `auto`                                                                                                                                                                    |
| `SubagentStop`                                                                                                                                                  | agent type                                                            | same values as `SubagentStart`                                                                                                                                                      |
| `ConfigChange`                                                                                                                                                  | configuration source                                                  | `user_settings`, `project_settings`, `local_settings`, `policy_settings`, `skills`                                                                                                  |
| `StopFailure`                                                                                                                                                   | error type                                                            | `rate_limit`, `overloaded`, `authentication_failed`, `oauth_org_not_allowed`, `billing_error`, `invalid_request`, `model_not_found`, `server_error`, `max_output_tokens`, `unknown` |
| `InstructionsLoaded`                                                                                                                                            | load reason                                                           | `session_start`, `nested_traversal`, `path_glob_match`, `include`, `compact`                                                                                                        |
| `Elicitation`                                                                                                                                                   | MCP server name                                                       | your configured MCP server names                                                                                                                                                    |
| `ElicitationResult`                                                                                                                                             | MCP server name                                                       | same values as `Elicitation`                                                                                                                                                        |
| `FileChanged`                                                                                                                                                   | literal filenames to watch (see [FileChanged](/en/hooks#filechanged)) | `.envrc\|.env`                                                                                                                                                                      |
| `UserPromptExpansion`                                                                                                                                           | command name                                                          | your skill or command names                                                                                                                                                         |
| `UserPromptSubmit`, `PostToolBatch`, `Stop`, `TeammateIdle`, `TaskCreated`, `TaskCompleted`, `WorktreeCreate`, `WorktreeRemove`, `CwdChanged`, `MessageDisplay` | no matcher support                                                    | always fires on every occurrence                                                                                                                                                    |

A few more examples showing matchers on different event types:

<Tabs>
  <Tab title="Log every Bash command">
    Match only `Bash` tool calls and log each command to a file. The `PostToolUse` event fires after the command completes, so `tool_input.command` contains what ran. The hook receives the event data as JSON on stdin, and `jq -r '.tool_input.command'` extracts just the command string, which `>>` appends to the log file:

    ```json theme={null}
    {
      "hooks": {
        "PostToolUse": [
          {
            "matcher": "Bash",
            "hooks": [
              {
                "type": "command",
                "command": "jq -r '.tool_input.command' >> ~/.claude/command-log.txt"
              }
            ]
          }
        ]
      }
    }
    ```
  </Tab>

  <Tab title="Match MCP tools">
    MCP tools use a different naming convention than built-in tools: `mcp__<server>__<tool>`, where `<server>` is the MCP server name and `<tool>` is the tool it provides. For example, `mcp__github__search_repositories` or `mcp__filesystem__read_file`. Use a regex matcher to target all tools from a specific server, or match across servers with a pattern like `mcp__.*__write.*`. See [Match MCP tools](/en/hooks#match-mcp-tools) in the reference for the full list of examples.

    The command below extracts the tool name from the hook's JSON input with `jq` and writes it to stderr. Writing to stderr keeps stdout clean for JSON output and sends the message to the [debug log](/en/hooks#debug-hooks):

    ```json theme={null}
    {
      "hooks": {
        "PreToolUse": [
          {
            "matcher": "mcp__github__.*",
            "hooks": [
              {
                "type": "command",
                "command": "echo \"GitHub tool called: $(jq -r '.tool_name')\" >&2"
              }
            ]
          }
        ]
      }
    }
    ```
  </Tab>

  <Tab title="Clean up on session end">
    The `SessionEnd` event supports matchers on the reason the session ended. This hook only fires on `clear` (when you run `/clear`), not on normal exits:

    ```json theme={null}
    {
      "hooks": {
        "SessionEnd": [
          {
            "matcher": "clear",
            "hooks": [
              {
                "type": "command",
                "command": "rm -f /tmp/claude-scratch-*.txt"
              }
            ]
          }
        ]
      }
    }
    ```
  </Tab>
</Tabs>

For full matcher syntax, see the [Hooks reference](/en/hooks#configuration).

#### Filter by tool name and arguments with the `if` field

<Note>
  The `if` field requires Claude Code v2.1.85 or later. Earlier versions ignore it and run the hook on every matched call.
</Note>

The `if` field uses [permission rule syntax](/en/permissions) to filter hooks by tool name and arguments together, so the hook process only spawns when the tool call matches. This goes beyond `matcher`, which filters at the group level by tool name only.

For example, to run a hook only when Claude uses `git` commands rather than all Bash commands:

```json theme={null}
{
  "hooks": {
    "PreToolUse": [
      {
        "matcher": "Bash",
        "hooks": [
          {
            "type": "command",
            "if": "Bash(git *)",
            "command": "\"$CLAUDE_PROJECT_DIR\"/.claude/hooks/check-git-policy.sh"
          }
        ]
      }
    ]
  }
}
```

Whether your hook command runs depends on the shape of your `if` pattern and the Bash command Claude is invoking:

| `if` pattern       | Bash command           | Hook runs? | Why                                                                                                 |
| :----------------- | :--------------------- | :--------- | :-------------------------------------------------------------------------------------------------- |
| `Bash(git *)`      | `git push`             | yes        | command name matches                                                                                |
| `Bash(git *)`      | `npm test && git push` | yes        | each subcommand is checked; `git push` matches                                                      |
| `Bash(git *)`      | `echo $(git log)`      | yes        | commands inside `$()` and backticks are checked; `git log` matches                                  |
| `Bash(git *)`      | `echo $(date)`         | no         | no subcommand matches `git *`                                                                       |
| `Bash(git push *)` | `echo $(date)`         | yes        | patterns that specify more than the command name run the hook anyway on `$()`, backticks, or `$VAR` |

The filter also fails open, running your hook regardless of pattern, when the Bash command cannot be parsed. Because the filter is best-effort, use the [permission system](/en/permissions) rather than a hook to enforce a hard allow or deny.

The `if` field accepts the same patterns as permission rules: `"Bash(git *)"`, `"Edit(*.ts)"`, and so on. To match multiple tool names, use separate handlers each with its own `if` value, or match at the `matcher` level where pipe alternation is supported.

`if` only works on tool events: `PreToolUse`, `PostToolUse`, `PostToolUseFailure`, `PermissionRequest`, and `PermissionDenied`. Adding it to any other event prevents the hook from running.

### Configure hook location

Where you add a hook determines its scope:

| Location                                                   | Scope                              | Shareable                                  |
| :--------------------------------------------------------- | :--------------------------------- | :----------------------------------------- |
| `~/.claude/settings.json`                                  | All your projects                  | No, local to your machine                  |
| `.claude/settings.json`                                    | Single project                     | Yes, can be committed to the repo          |
| `.claude/settings.local.json`                              | Single project                     | No, gitignored when Claude Code creates it |
| Managed policy settings                                    | Organization-wide                  | Yes, admin-controlled                      |
| [Plugin](/en/plugins) `hooks/hooks.json`                   | When plugin is enabled             | Yes, bundled with the plugin               |
| [Skill](/en/skills) or [agent](/en/sub-agents) frontmatter | While the skill or agent is active | Yes, defined in the component file         |

Run [`/hooks`](/en/hooks#the-%2Fhooks-menu) in Claude Code to browse all configured hooks grouped by event. To disable hooks, set `"disableAllHooks": true` in your settings file. Hooks configured in managed settings still run unless `disableAllHooks` is also set there.

If you edit settings files directly while Claude Code is running, the file watcher normally picks up hook changes automatically.

## Prompt-based hooks

For decisions that require judgment rather than deterministic rules, use `type: "prompt"` hooks. Instead of running a shell command, Claude Code sends your prompt and the hook's input data to a Claude model (Haiku by default) to make the decision. You can specify a different model with the `model` field if you need more capability.

The model's only job is to return a yes/no decision as JSON:

* `"ok": true`: the action proceeds
* `"ok": false`: what happens depends on the event:
  * `Stop` and `SubagentStop`: the `reason` is fed back to Claude so it keeps working
  * `PreToolUse`: the tool call is denied and the `reason` is returned to Claude as the tool error, so it can adjust and continue
  * `PostToolUse`, `PostToolBatch`, `UserPromptSubmit`, and `UserPromptExpansion`: the turn ends and the `reason` appears in the chat as a warning line

This example uses a `Stop` hook to ask the model whether all requested tasks are complete. If the model returns `"ok": false`, Claude keeps working and uses the `reason` as its next instruction:

```json theme={null}
{
  "hooks": {
    "Stop": [
      {
        "hooks": [
          {
            "type": "prompt",
            "prompt": "Check if all tasks are complete. If not, respond with {\"ok\": false, \"reason\": \"what remains to be done\"}."
          }
        ]
      }
    ]
  }
}
```

For full configuration options, see [Prompt-based hooks](/en/hooks#prompt-based-hooks) in the reference.

## Agent-based hooks

<Warning>
  Agent hooks are experimental. Behavior and configuration may change in future releases. For production workflows, prefer [command hooks](/en/hooks#command-hook-fields).
</Warning>

When verification requires inspecting files or running commands, use `type: "agent"` hooks. Unlike prompt hooks which make a single LLM call, agent hooks spawn a subagent that can read files, search code, and use other tools to verify conditions before returning a decision.

Agent hooks use the same `"ok"` / `"reason"` response format as prompt hooks, but with a longer default timeout of 60 seconds and up to 50 tool-use turns.

This example verifies that tests pass before allowing Claude to stop:

```json theme={null}
{
  "hooks": {
    "Stop": [
      {
        "hooks": [
          {
            "type": "agent",
            "prompt": "Verify that all unit tests pass. Run the test suite and check the results. $ARGUMENTS",
            "timeout": 120
          }
        ]
      }
    ]
  }
}
```

Use prompt hooks when the hook input data alone is enough to make a decision. Use agent hooks when you need to verify something against the actual state of the codebase.

For full configuration options, see [Agent-based hooks](/en/hooks#agent-based-hooks) in the reference.

## HTTP hooks

Use `type: "http"` hooks to POST event data to an HTTP endpoint instead of running a shell command. The endpoint receives the same JSON that a command hook would receive on stdin, and returns results through the HTTP response body using the same JSON format.

HTTP hooks are useful when you want a web server, cloud function, or external service to handle hook logic: for example, a shared audit service that logs tool use events across a team.

This example posts every tool use to a local logging service:

```json theme={null}
{
  "hooks": {
    "PostToolUse": [
      {
        "hooks": [
          {
            "type": "http",
            "url": "http://localhost:8080/hooks/tool-use",
            "headers": {
              "Authorization": "Bearer $MY_TOKEN"
            },
            "allowedEnvVars": ["MY_TOKEN"]
          }
        ]
      }
    ]
  }
}
```

The endpoint should return a JSON response body using the same [output format](/en/hooks#json-output) as command hooks. To block a tool call, return a 2xx response with the appropriate `hookSpecificOutput` fields. HTTP status codes alone cannot block actions.

Header values support environment variable interpolation using `$VAR_NAME` or `${VAR_NAME}` syntax. Only variables listed in the `allowedEnvVars` array are resolved; all other `$VAR` references remain empty.

For full configuration options and response handling, see [HTTP hooks](/en/hooks#http-hook-fields) in the reference.

## Limitations and troubleshooting

### Limitations

* Command hooks communicate through stdout, stderr, and exit codes only. They cannot trigger `/` commands or tool calls. Text returned via `additionalContext` is injected as a system reminder that Claude reads as plain text. HTTP hooks communicate through the response body instead.
* Hook timeouts vary by type. Override per hook with the `timeout` field in seconds.
  * `command`, `http`, `mcp_tool`: 10 minutes. `UserPromptSubmit` lowers these to 30 seconds, and `MessageDisplay` lowers them to 10 seconds.
  * `prompt`: 30 seconds.
  * `agent`: 60 seconds.
* `PostToolUse` hooks cannot undo actions since the tool has already executed.
* `PermissionRequest` hooks do not fire in [non-interactive mode](/en/headless) (`-p`). Use `PreToolUse` hooks for automated permission decisions.
* `Stop` hooks fire whenever Claude finishes responding, not only at task completion. They do not fire on user interrupts. API errors fire [StopFailure](/en/hooks#stopfailure) instead.
* When multiple PreToolUse hooks return [`updatedInput`](/en/hooks#pretooluse) to rewrite a tool's arguments, the last one to finish wins. Since hooks run in parallel, the order is non-deterministic. Avoid having more than one hook modify the same tool's input.

### Hooks and permission modes

PreToolUse hooks fire before any permission-mode check. A hook that returns `permissionDecision: "deny"` blocks the tool even in `bypassPermissions` mode or with `--dangerously-skip-permissions`. This lets you enforce policy that users cannot bypass by changing their permission mode.

The reverse is not true: a hook returning `"allow"` does not bypass deny rules from settings. Hooks can tighten restrictions but not loosen them past what permission rules allow.

### Hook not firing

The hook is configured but never executes.

* Run `/hooks` and confirm the hook appears under the correct event
* Check that the matcher pattern matches the tool name exactly (matchers are case-sensitive)
* Verify you're triggering the right event type (e.g., `PreToolUse` fires before tool execution, `PostToolUse` fires after)
* If using `PermissionRequest` hooks in non-interactive mode (`-p`), switch to `PreToolUse` instead

### Hook error in output

You see a message like "PreToolUse hook error: ..." in the transcript.

* Your script exited with a non-zero code unexpectedly. Test it manually by piping sample JSON:
  ```bash theme={null}
  echo '{"tool_name":"Bash","tool_input":{"command":"ls"}}' | ./my-hook.sh
  echo $?  # Check the exit code
  ```
* If you see "command not found", use absolute paths or `${CLAUDE_PROJECT_DIR}` to reference scripts. To avoid shell quoting entirely, add `"args": []` to switch to [exec form](/en/hooks#exec-form-and-shell-form), which spawns the script directly without a shell
* If you see "jq: command not found", install `jq` or use Python/Node.js for JSON parsing
* If the script isn't running at all, make it executable: `chmod +x ./my-hook.sh`

### `/hooks` shows no hooks configured

You edited a settings file but the hooks don't appear in the menu.

* File edits are normally picked up automatically. If they haven't appeared after a few seconds, the file watcher may have missed the change: restart your session to force a reload.
* Verify your JSON is valid (trailing commas and comments are not allowed)
* Confirm the settings file is in the correct location: `.claude/settings.json` for project hooks, `~/.claude/settings.json` for global hooks

### Stop hook hits the block cap

Claude keeps working instead of stopping, then ends the turn with a warning that the Stop hook blocked too many consecutive times.

Claude Code overrides a Stop hook after it blocks 8 times in a row without progress. Your hook script needs to check whether it already triggered a continuation. Parse the `stop_hook_active` field from the JSON input and exit early if it's `true`:

```bash theme={null}
#!/bin/bash
INPUT=$(cat)
if [ "$(echo "$INPUT" | jq -r '.stop_hook_active')" = "true" ]; then
  exit 0  # Allow Claude to stop
fi
# ... rest of your hook logic
```

If your hook legitimately needs more than eight iterations to converge, raise the cap with [`CLAUDE_CODE_STOP_HOOK_BLOCK_CAP`](/en/env-vars).

### JSON validation failed

Claude Code shows a JSON parsing error even though your hook script outputs valid JSON.

When Claude Code runs a shell-form command hook (one without `args`), it spawns `sh -c` on macOS and Linux or Git Bash on Windows by default. This shell is non-interactive, but Git Bash and some configurations (such as `BASH_ENV` pointing at `~/.bashrc`) still source your profile. If that profile contains unconditional `echo` statements, the output gets prepended to your hook's JSON:

```text theme={null}
Shell ready on arm64
{"decision": "block", "reason": "Not allowed"}
```

Claude Code tries to parse this as JSON and fails. To fix this, wrap echo statements in your shell profile so they only run in interactive shells:

```bash theme={null}
# In ~/.zshrc or ~/.bashrc
if [[ $- == *i* ]]; then
  echo "Shell ready"
fi
```

The `$-` variable contains shell flags, and `i` means interactive. Hooks run in non-interactive shells, so the echo is skipped.

### Debug techniques

The transcript view, toggled with `Ctrl+O`, shows a one-line summary for each hook that fired: success is silent, blocking errors show stderr, and non-blocking errors show a `<hook name> hook error` notice followed by the first line of stderr.

For full execution details including which hooks matched, their exit codes, stdout, and stderr, read the debug log. Start Claude Code with `claude --debug-file /tmp/claude.log` to write to a known path, then `tail -f /tmp/claude.log` in another terminal. If you started without that flag, run `/debug` mid-session to enable logging and find the log path.

## Learn more

* [Hooks reference](/en/hooks): full event schemas, JSON output format, async hooks, and MCP tool hooks
* [Security considerations](/en/hooks#security-considerations): review before deploying hooks in shared or production environments
* [Bash command validator example](https://github.com/anthropics/claude-code/blob/main/examples/hooks/bash_command_validator_example.py): complete reference implementation


================================================================================
# PAGE: cli-reference
# source: https://docs.claude.com/en/docs/claude-code/cli-reference.md
================================================================================

> ## Documentation Index
> Fetch the complete documentation index at: https://code.claude.com/docs/llms.txt
> Use this file to discover all available pages before exploring further.

# CLI reference

> Complete reference for Claude Code command-line interface, including commands and flags.

## CLI commands

You can start sessions, pipe content, resume conversations, and manage updates with these commands:

| Command                         | Description                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                              | Example                                                     |
| :------------------------------ | :----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | :---------------------------------------------------------- |
| `claude`                        | Start interactive session                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                | `claude`                                                    |
| `claude "query"`                | Start interactive session with initial prompt                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                            | `claude "explain this project"`                             |
| `claude -p "query"`             | Query via SDK, then exit                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                 | `claude -p "explain this function"`                         |
| `cat file \| claude -p "query"` | Process piped content                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                    | `cat logs.txt \| claude -p "explain"`                       |
| `claude -c`                     | Continue most recent conversation in current directory                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                   | `claude -c`                                                 |
| `claude -c -p "query"`          | Continue via SDK                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                         | `claude -c -p "Check for type errors"`                      |
| `claude -r "<session>" "query"` | Resume session by ID or name                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                             | `claude -r "auth-refactor" "Finish this PR"`                |
| `claude update`                 | Update to latest version                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                 | `claude update`                                             |
| `claude gateway`                | Start the self-hosted [Claude apps gateway](/en/claude-apps-gateway) server, for administrators deploying SSO and policy in front of Claude Code on Bedrock, Vertex AI, or Foundry. Requires `--config` pointing at a [`gateway.yaml`](/en/claude-apps-gateway-config). Available in Claude Code v2.1.195 and later.                                                                                                                                                                                                                                                                                                     | `claude gateway --config gateway.yaml`                      |
| `claude install [version]`      | Install or reinstall the native binary. Accepts a version like `2.1.118`, or `stable` or `latest`. See [Install a specific version](/en/setup#install-a-specific-version)                                                                                                                                                                                                                                                                                                                                                                                                                                                | `claude install stable`                                     |
| `claude auth login`             | Sign in to your Anthropic account. Use `--email` to pre-fill your email address, `--sso` to force SSO authentication, and `--console` to sign in with Anthropic Console for API usage billing instead of a Claude subscription                                                                                                                                                                                                                                                                                                                                                                                           | `claude auth login --console`                               |
| `claude auth logout`            | Log out from your Anthropic account                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                      | `claude auth logout`                                        |
| `claude auth status`            | Show authentication status as JSON. Use `--text` for human-readable output. Exits with code 0 if logged in, 1 if not                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                     | `claude auth status`                                        |
| `claude agents`                 | Open [agent view](/en/agent-view) to monitor and dispatch parallel background sessions. Use `--cwd <path>` to show only sessions started under that directory, or `--json` to print active sessions as a JSON array for scripting (`--json --all` also includes completed background sessions). Pass `--permission-mode`, `--model`, `--effort`, or `--agent` to set [defaults for dispatched sessions](/en/agent-view#permission-mode-model-and-effort). Accepts `--settings`, `--add-dir`, `--plugin-dir`, and `--mcp-config` like the top-level `claude` command. Opening agent view requires an interactive terminal | `claude agents --json`                                      |
| `claude attach <id>`            | Attach to a [background session](/en/agent-view#manage-sessions-from-the-shell) in this terminal                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                         | `claude attach 7c5dcf5d`                                    |
| `claude auto-mode defaults`     | Print the built-in [auto mode](/en/permission-modes#eliminate-prompts-with-auto-mode) classifier rules as JSON. Use `claude auto-mode config` to see your effective config with settings applied                                                                                                                                                                                                                                                                                                                                                                                                                         | `claude auto-mode defaults > rules.json`                    |
| `claude daemon status`          | Print the background-session [supervisor's](/en/agent-view#the-supervisor-process) state, version, socket directory, and worker count for diagnostics. Exits 1 if the supervisor isn't running                                                                                                                                                                                                                                                                                                                                                                                                                           | `claude daemon status`                                      |
| `claude daemon stop --any`      | Stop the background-session [supervisor](/en/agent-view#the-supervisor-process) and the sessions it hosts. Pass `--keep-workers` to leave background sessions running so the next supervisor reconnects to them. `--any` confirms stopping an on-demand supervisor, which is the default. Use this to recover from an [unresponsive supervisor](/en/agent-view#agent-view-says-the-background-service-did-not-respond)                                                                                                                                                                                                   | `claude daemon stop --any --keep-workers`                   |
| `claude logs <id>`              | Print recent output from a [background session](/en/agent-view#manage-sessions-from-the-shell)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                           | `claude logs 7c5dcf5d`                                      |
| `claude mcp`                    | Configure Model Context Protocol (MCP) servers                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                           | See the [Claude Code MCP documentation](/en/mcp).           |
| `claude mcp login <name>`       | {/* min-version: 2.1.186 */}Run a configured MCP server's OAuth flow without opening the interactive `/mcp` panel. Works for HTTP, SSE, and claude.ai connector servers. Add `--no-browser` over SSH to print the authorization URL instead of opening a browser, then paste the redirect URL back at the prompt. Requires Claude Code v2.1.186 or later. See [Authenticate from the command line](/en/mcp#authenticate-from-the-command-line)                                                                                                                                                                           | `claude mcp login sentry`                                   |
| `claude mcp logout <name>`      | {/* min-version: 2.1.186 */}Clear stored OAuth credentials for an MCP server. Requires Claude Code v2.1.186 or later                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                     | `claude mcp logout sentry`                                  |
| `claude plugin`                 | Manage Claude Code [plugins](/en/plugins). Alias: `claude plugins`. See [plugin reference](/en/plugins-reference#cli-commands-reference) for subcommands                                                                                                                                                                                                                                                                                                                                                                                                                                                                 | `claude plugin install code-review@claude-plugins-official` |
| `claude project purge [path]`   | Delete all local Claude Code state for a project: transcripts, task lists, debug logs, file-edit history, prompt history lines, and the project's entry in `~/.claude.json`. Omit `[path]` to pick from an interactive list. Flags: `--dry-run` to preview, `-y`/`--yes` to skip confirmation, `-i`/`--interactive` to confirm each item, `--all` for every project. See [Clear local data](/en/claude-directory#clear-local-data)                                                                                                                                                                                       | `claude project purge ~/work/repo --dry-run`                |
| `claude remote-control`         | Start a [Remote Control](/en/remote-control) server to control Claude Code from Claude.ai or the Claude app. Runs in server mode (no local interactive session). See [Server mode flags](/en/remote-control#start-a-remote-control-session)                                                                                                                                                                                                                                                                                                                                                                              | `claude remote-control --name "My Project"`                 |
| `claude respawn <id>`           | Restart a [background session](/en/agent-view#manage-sessions-from-the-shell), running or stopped, with its conversation intact. Use `--all` to restart every running session, e.g. to pick up an updated Claude Code binary                                                                                                                                                                                                                                                                                                                                                                                             | `claude respawn 7c5dcf5d`                                   |
| `claude rm <id>`                | Remove a [background session](/en/agent-view#manage-sessions-from-the-shell) from the list. The conversation transcript stays on your local machine, available through `claude --resume`                                                                                                                                                                                                                                                                                                                                                                                                                                 | `claude rm 7c5dcf5d`                                        |
| `claude setup-token`            | Generate a long-lived OAuth token for CI and scripts. Prints the token to the terminal without saving it. Requires a Claude subscription. See [Generate a long-lived token](/en/authentication#generate-a-long-lived-token)                                                                                                                                                                                                                                                                                                                                                                                              | `claude setup-token`                                        |
| `claude stop <id>`              | Stop a [background session](/en/agent-view#manage-sessions-from-the-shell). Also accepts `claude kill`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                   | `claude stop 7c5dcf5d`                                      |
| `claude ultrareview [target]`   | Run [ultrareview](/en/ultrareview#run-ultrareview-non-interactively) non-interactively. Prints findings to stdout and exits 0 on success or 1 on failure. Use `--json` for the raw payload and `--timeout <minutes>` to override the 30-minute default                                                                                                                                                                                                                                                                                                                                                                   | `claude ultrareview 1234 --json`                            |

If you mistype a subcommand, Claude Code suggests the closest match and exits without starting a session. For example, `claude udpate` prints `Did you mean claude update?`.

## CLI flags

Customize Claude Code's behavior with these command-line flags. `claude --help` does not list every flag, so a flag's absence from `--help` does not mean it is unavailable.

| Flag                                            | Description                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                 | Example                                                                                             |
| :---------------------------------------------- | :---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | :-------------------------------------------------------------------------------------------------- |
| `--add-dir`                                     | Add additional working directories for Claude to read and edit files. Grants file access; most `.claude/` configuration is [not discovered](/en/permissions#additional-directories-grant-file-access-not-configuration) from these directories. Validates each path exists as a directory. To persist these directories across sessions, set [`permissions.additionalDirectories`](/en/settings#permission-settings) in settings                                                                                                                                                                                                                                                                                                                                                                                                                                                                            | `claude --add-dir ../apps ../lib`                                                                   |
| `--advisor <model>`                             | {/* min-version: 2.1.98 */}Enable the server-side [advisor tool](/en/advisor) for this session with a model alias: `opus`, `sonnet`, or `fable` ({/* min-version: 2.1.170 */}v2.1.170+), or a full model ID. Takes precedence over the `advisorModel` setting for the session. Requires Claude Code v2.1.98 or later                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                        | `claude --advisor opus`                                                                             |
| `--agent`                                       | Specify an agent for the current session (overrides the `agent` setting)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                    | `claude --agent my-custom-agent`                                                                    |
| `--agents`                                      | Define custom subagents dynamically via JSON. Uses the same field names as subagent [frontmatter](/en/sub-agents#supported-frontmatter-fields), plus a `prompt` field for the agent's instructions                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                          | `claude --agents '{"reviewer":{"description":"Reviews code","prompt":"You are a code reviewer"}}'`  |
| `--allow-dangerously-skip-permissions`          | Add `bypassPermissions` to the `Shift+Tab` mode cycle without starting in it. Lets you begin in a different mode like `plan` and switch to `bypassPermissions` later. See [permission modes](/en/permission-modes#skip-all-checks-with-bypasspermissions-mode)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                              | `claude --permission-mode plan --allow-dangerously-skip-permissions`                                |
| `--allowedTools`, `--allowed-tools`             | Tools that execute without prompting for permission. See [permission rule syntax](/en/settings#permission-rule-syntax) for pattern matching. To restrict which tools are available, use `--tools` instead                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                   | `"Bash(git log *)" "Bash(git diff *)" "Read"`                                                       |
| `--append-system-prompt`                        | Append custom text to the end of the default system prompt                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                  | `claude --append-system-prompt "Always use TypeScript"`                                             |
| `--append-system-prompt-file`                   | Load additional system prompt text from a file and append to the default prompt                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                             | `claude --append-system-prompt-file ./extra-rules.txt`                                              |
| `--ax-screen-reader`                            | {/* min-version: 2.1.181 */}Render screen-reader friendly output: flat text without decorative borders or animations. Forces the classic renderer, so the [`tui`](/en/settings#available-settings) setting has no effect for the session. Takes precedence over [`CLAUDE_AX_SCREEN_READER`](/en/env-vars) and the [`axScreenReader`](/en/settings#available-settings) setting. Requires Claude Code v2.1.181 or later                                                                                                                                                                                                                                                                                                                                                                                                                                                                                       | `claude --ax-screen-reader`                                                                         |
| `--bare`                                        | Minimal mode: skip auto-discovery of hooks, skills, plugins, MCP servers, auto memory, and CLAUDE.md so scripted calls start faster. Claude has access to Bash, file read, and file edit tools. Sets [`CLAUDE_CODE_SIMPLE`](/en/env-vars). See [bare mode](/en/headless#start-faster-with-bare-mode)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                        | `claude --bare -p "query"`                                                                          |
| `--betas`                                       | Beta headers to include in API requests (API key users only)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                | `claude --betas interleaved-thinking`                                                               |
| `--bg`, `--background`                          | Start the session as a [background agent](/en/agent-view) and return immediately. Prints the session ID and management commands. Combine with `--exec` to run a shell command as a background job instead of a Claude session, or with `--agent` to run a specific subagent                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                 | `claude --bg "investigate the flaky test"`                                                          |
| `--channels`                                    | (Research preview) MCP servers whose [channel](/en/channels) notifications Claude should listen for in this session. Space-separated list of `plugin:<name>@<marketplace>` entries. Requires Claude.ai authentication                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                       | `claude --channels plugin:my-notifier@my-marketplace`                                               |
| `--chrome`                                      | Enable [Chrome browser integration](/en/chrome) for web automation and testing                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                              | `claude --chrome`                                                                                   |
| `--continue`, `-c`                              | Load the most recent conversation in the current directory. Includes sessions that added this directory with `/add-dir`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                     | `claude --continue`                                                                                 |
| `--dangerously-load-development-channels`       | Enable [channels](/en/channels-reference#test-during-the-research-preview) that are not on the approved allowlist, for local development. Accepts `plugin:<name>@<marketplace>` and `server:<name>` entries. Prompts for confirmation                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                       | `claude --dangerously-load-development-channels server:webhook`                                     |
| `--dangerously-skip-permissions`                | Skip permission prompts. Equivalent to `--permission-mode bypassPermissions`. See [permission modes](/en/permission-modes#skip-all-checks-with-bypasspermissions-mode) for what this does and does not skip                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                 | `claude --dangerously-skip-permissions`                                                             |
| `--debug`                                       | Enable debug mode with optional category filtering (for example, `"api,hooks"` or `"!statsig,!file"`)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                       | `claude --debug "api,mcp"`                                                                          |
| `--debug-file <path>`                           | Write debug logs to a specific file path. Implicitly enables debug mode. Takes precedence over `CLAUDE_CODE_DEBUG_LOGS_DIR`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                 | `claude --debug-file /tmp/claude-debug.log`                                                         |
| `--disable-slash-commands`                      | Disable all skills and commands for this session                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                            | `claude --disable-slash-commands`                                                                   |
| `--disallowedTools`, `--disallowed-tools`       | Deny rules. A bare tool name removes the matching tools from the model's context: `"Edit"` removes Edit, `"*"` removes every tool, and `"mcp__*"` removes every MCP tool. A scoped rule such as `Bash(rm *)` leaves the tool available and denies only matching calls                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                       | `"Bash(git log *)" "Bash(git diff *)" "Edit"`                                                       |
| `--effort`                                      | Set the [effort level](/en/model-config#adjust-effort-level) for the current session. Options: `low`, `medium`, `high`, `xhigh`, `max`; available levels depend on the model. Overrides the [`effortLevel`](/en/settings#available-settings) setting for this session and does not persist                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                  | `claude --effort high`                                                                              |
| `--enable-auto-mode`                            | {/* max-version: 2.1.110 */}Removed in v2.1.111. Auto mode is now in the `Shift+Tab` cycle by default; use `--permission-mode auto` to start in it                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                          | `claude --permission-mode auto`                                                                     |
| `--exclude-dynamic-system-prompt-sections`      | Move per-machine sections from the system prompt (working directory, environment info, memory paths, git-repo flag) into the first user message. Improves prompt-cache reuse across different users and machines running the same task. Only applies with the default system prompt; ignored when `--system-prompt` or `--system-prompt-file` is set. Use with `-p` for scripted, multi-user workloads                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                      | `claude -p --exclude-dynamic-system-prompt-sections "query"`                                        |
| `--exec`                                        | Run a shell command as a PTY-backed background job instead of starting a Claude session. Use with `--bg` to launch from the shell                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                           | `claude --bg --exec 'pytest -x'`                                                                    |
| `--fallback-model`                              | Enable automatic fallback to the specified model(s) when the primary model is overloaded or not available, for example a retired model. Accepts a comma-separated list tried in order. See [Fallback model chains](/en/model-config#fallback-model-chains). To persist a chain across sessions, use the [`fallbackModel` setting](/en/settings#available-settings), which this flag overrides                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                               | `claude --fallback-model sonnet,haiku`                                                              |
| `--fork-session`                                | When resuming, create a new session ID instead of reusing the original (use with `--resume` or `--continue`)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                | `claude --resume abc123 --fork-session`                                                             |
| `--from-pr`                                     | Resume sessions linked to a specific pull request. Accepts a PR number, a GitHub or GitHub Enterprise PR URL, a GitLab merge request URL, or a Bitbucket pull request URL. Sessions are linked automatically when Claude creates the pull request                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                           | `claude --from-pr 123`                                                                              |
| `--ide`                                         | Automatically connect to IDE on startup if exactly one valid IDE is available                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                               | `claude --ide`                                                                                      |
| `--init`                                        | Run [Setup hooks](/en/hooks#setup) with the `init` matcher before the session (print mode only)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                             | `claude -p --init "query"`                                                                          |
| `--init-only`                                   | Run [Setup](/en/hooks#setup) and `SessionStart` hooks, then exit without starting a conversation                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                            | `claude --init-only`                                                                                |
| `--include-hook-events`                         | Include all hook lifecycle events in the output stream. Requires `--output-format stream-json`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                              | `claude -p --output-format stream-json --verbose --include-hook-events "query"`                     |
| `--include-partial-messages`                    | Include partial streaming events in output. Requires `--print` and `--output-format stream-json`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                            | `claude -p --output-format stream-json --verbose --include-partial-messages "query"`                |
| `--input-format`                                | Specify input format for print mode (options: `text`, `stream-json`)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                        | `claude -p --output-format json --input-format stream-json`                                         |
| `--json-schema`                                 | Get validated JSON output matching a JSON Schema after agent completes its workflow (print mode only, see [structured outputs](/en/agent-sdk/structured-outputs))                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                           | `claude -p --json-schema '{"type":"object","properties":{...}}' "query"`                            |
| `--maintenance`                                 | Run [Setup hooks](/en/hooks#setup) with the `maintenance` matcher before the session (print mode only)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                      | `claude -p --maintenance "query"`                                                                   |
| `--max-budget-usd`                              | Maximum dollar amount to spend on API calls before stopping (print mode only)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                               | `claude -p --max-budget-usd 5.00 "query"`                                                           |
| `--max-turns`                                   | Limit the number of agentic turns (print mode only). Exits with an error when the limit is reached. No limit by default                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                     | `claude -p --max-turns 3 "query"`                                                                   |
| `--mcp-config`                                  | Load MCP servers from JSON files or strings (space-separated)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                               | `claude --mcp-config ./mcp.json`                                                                    |
| `--model`                                       | Sets the model for the current session with an alias for the latest model (`sonnet`, `opus`, `haiku`, or `fable`) or a model's full name. Overrides the [`model`](/en/settings#available-settings) setting and [`ANTHROPIC_MODEL`](/en/model-config#environment-variables)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                  | `claude --model claude-sonnet-5`                                                                    |
| `--name`, `-n`                                  | Set a display name for the session, shown in `/resume` and the terminal title. You can resume a named session with `claude --resume <name>`. <br /><br />[`/rename`](/en/commands) changes the name mid-session and also shows it on the prompt bar                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                         | `claude -n "my-feature-work"`                                                                       |
| `--no-chrome`                                   | Disable [Chrome browser integration](/en/chrome) for this session                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                           | `claude --no-chrome`                                                                                |
| `--no-session-persistence`                      | Disable session persistence so sessions are not saved to disk and cannot be resumed. Print mode only. The [`CLAUDE_CODE_SKIP_PROMPT_HISTORY`](/en/env-vars) environment variable does the same in any mode                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                  | `claude -p --no-session-persistence "query"`                                                        |
| `--output-format`                               | Specify output format for print mode (options: `text`, `json`, `stream-json`)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                               | `claude -p "query" --output-format json`                                                            |
| `--permission-mode`                             | Begin in a specified [permission mode](/en/permission-modes). Accepts `default`, `acceptEdits`, `plan`, `auto`, `dontAsk`, or `bypassPermissions`. Overrides `defaultMode` from settings files                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                              | `claude --permission-mode plan`                                                                     |
| `--permission-prompt-tool`                      | Specify an MCP tool to handle permission prompts in non-interactive mode                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                    | `claude -p --permission-prompt-tool mcp_auth_tool "query"`                                          |
| `--plugin-dir`                                  | Load a plugin from a directory or `.zip` archive for this session only. Each flag takes one path. Repeat the flag for multiple plugins: `--plugin-dir A --plugin-dir B.zip`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                 | `claude --plugin-dir ./my-plugin`                                                                   |
| `--plugin-url`                                  | Fetch a plugin `.zip` archive from a URL for this session only. Repeat the flag for multiple plugins, or pass space-separated URLs in a single quoted value                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                 | `claude --plugin-url https://example.com/plugin.zip`                                                |
| `--print`, `-p`                                 | Print response without interactive mode (see [Agent SDK documentation](/en/agent-sdk/overview) for programmatic usage details)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                              | `claude -p "query"`                                                                                 |
| `--prompt-suggestions`                          | Emit a `prompt_suggestion` message after each turn with a predicted next user prompt. Requires `--print`, `--output-format stream-json`, and `--verbose`. See [Prompt suggestions](/en/interactive-mode#prompt-suggestions)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                 | `claude -p --prompt-suggestions --output-format stream-json --verbose "query"`                      |
| `--remote`                                      | Create a new [web session](/en/claude-code-on-the-web) on claude.ai with the provided task description                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                      | `claude --remote "Fix the login bug"`                                                               |
| `--remote-control`, `--rc`                      | Start an interactive session with [Remote Control](/en/remote-control#start-a-remote-control-session) enabled so you can also control it from claude.ai or the Claude app. Optionally pass a name for the session                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                           | `claude --remote-control "My Project"`                                                              |
| `--remote-control-session-name-prefix <prefix>` | Prefix for auto-generated [Remote Control](/en/remote-control) session names when no explicit name is set. Defaults to your machine's hostname, producing names like `myhost-graceful-unicorn`. Set `CLAUDE_REMOTE_CONTROL_SESSION_NAME_PREFIX` for the same effect                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                         | `claude remote-control --remote-control-session-name-prefix dev-box`                                |
| `--replay-user-messages`                        | Re-emit user messages from stdin back on stdout for acknowledgment. Requires `--input-format stream-json` and `--output-format stream-json`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                 | `claude -p --input-format stream-json --output-format stream-json --verbose --replay-user-messages` |
| `--resume`, `-r`                                | Resume a specific session by ID or name, or show an interactive picker to choose a session. The picker and name search include sessions that added this directory with `/add-dir`; passing a session ID searches only the current project directory and its git worktrees. As of v2.1.144, [background sessions](/en/agent-view) appear in the picker marked with `bg`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                      | `claude --resume auth-refactor`                                                                     |
| `--safe-mode`                                   | {/* min-version: 2.1.169 */}Start with all customizations disabled to troubleshoot a broken configuration: CLAUDE.md, skills, plugins, hooks, MCP servers, custom commands and agents, output styles, workflows, custom themes, custom keybindings, status line and file-suggestion commands, LSP servers, and auto-memory do not load. Authentication, model selection, built-in tools, and permissions work normally, which differs from [`--bare`](/en/headless#start-faster-with-bare-mode). Managed settings policy still applies, including policy-configured hooks, status line, and file-suggestion commands; managed plugins, managed skills, managed CLAUDE.md, and policy-configured MCP servers do not. Useful for checking whether a customization is what triggers [automatic fallback from Fable 5](/en/model-config#automatic-model-fallback). Sets [`CLAUDE_CODE_SAFE_MODE`](/en/env-vars) | `claude --safe-mode`                                                                                |
| `--session-id`                                  | Use a specific session ID for the conversation (must be a valid UUID)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                       | `claude --session-id "550e8400-e29b-41d4-a716-446655440000"`                                        |
| `--setting-sources`                             | Comma-separated list of setting sources to load (`user`, `project`, `local`)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                | `claude --setting-sources user,project`                                                             |
| `--settings`                                    | Path to a settings JSON file or an inline JSON string. Values you set here override the same keys in your `settings.json` files for this session. Keys you omit keep their file-based values. See [settings precedence](/en/settings#settings-precedence)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                   | `claude --settings ./settings.json`                                                                 |
| `--strict-mcp-config`                           | Only use MCP servers from `--mcp-config`, ignoring all other MCP configurations                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                             | `claude --strict-mcp-config --mcp-config ./mcp.json`                                                |
| `--system-prompt`                               | Replace the entire system prompt with custom text                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                           | `claude --system-prompt "You are a Python expert"`                                                  |
| `--system-prompt-file`                          | Load system prompt from a file, replacing the default prompt                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                | `claude --system-prompt-file ./custom-prompt.txt`                                                   |
| `--teleport`                                    | Resume a [web session](/en/claude-code-on-the-web) in your local terminal                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                   | `claude --teleport`                                                                                 |
| `--teammate-mode`                               | Set how [agent team](/en/agent-teams) teammates display: `in-process` (default), `auto`, `tmux`, or {/* min-version: 2.1.186 */}`iterm2` (added in v2.1.186). The default changed from `auto` in v2.1.179. Overrides the [`teammateMode`](/en/settings#available-settings) setting for this session. See [Choose a display mode](/en/agent-teams#choose-a-display-mode)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                     | `claude --teammate-mode auto`                                                                       |
| `--tmux`                                        | Create a tmux session for the worktree. Requires `--worktree`. Uses iTerm2 native panes when available; pass `--tmux=classic` for traditional tmux                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                          | `claude -w feature-auth --tmux`                                                                     |
| `--tools`                                       | Restrict which built-in tools Claude can use. Use `""` to disable all, `"default"` for all, or tool names like `"Bash,Edit,Read"`. MCP tools are not affected; to deny those too, use `--disallowedTools "mcp__*"`, or pass `--strict-mcp-config` without `--mcp-config` so no MCP servers load                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                             | `claude --tools "Bash,Edit,Read"`                                                                   |
| `--verbose`                                     | Enable verbose logging, shows full turn-by-turn output. Overrides the [`viewMode`](/en/settings#available-settings) setting for this session                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                | `claude --verbose`                                                                                  |
| `--version`, `-v`                               | Output the version number                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                   | `claude -v`                                                                                         |
| `--worktree`, `-w`                              | Start Claude in an isolated [git worktree](/en/worktrees) at `<repo>/.claude/worktrees/<name>`. If no name is given, one is auto-generated. Pass `#<number>` or a GitHub pull request URL to fetch that PR from `origin` and branch the worktree from it                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                    | `claude -w feature-auth`                                                                            |

### System prompt flags

Claude Code provides four flags for customizing the system prompt. All four work in both interactive and non-interactive modes.

| Flag                          | Behavior                                    | Example                                                 |
| :---------------------------- | :------------------------------------------ | :------------------------------------------------------ |
| `--system-prompt`             | Replaces the entire default prompt          | `claude --system-prompt "You are a Python expert"`      |
| `--system-prompt-file`        | Replaces with file contents                 | `claude --system-prompt-file ./prompts/review.txt`      |
| `--append-system-prompt`      | Appends to the default prompt               | `claude --append-system-prompt "Always use TypeScript"` |
| `--append-system-prompt-file` | Appends file contents to the default prompt | `claude --append-system-prompt-file ./style-rules.txt`  |

`--system-prompt` and `--system-prompt-file` are mutually exclusive. The append flags can be combined with either replacement flag.

Choose based on whether Claude Code's default identity still fits your task. Use an append flag when Claude should remain a coding assistant that also follows your extra rules: per-invocation instructions, output formatting, or domain context for a `-p` script. Appending preserves the default tool guidance, safety instructions, and coding conventions, so you only supply what differs. Use a replacement flag when the surface, identity, or permission model differs from Claude Code's, like a non-coding agent in a pipeline that no human watches. Replacing drops all of the default prompt, including tool guidance and safety instructions, so you take responsibility for whatever your task still needs.

These flags apply only to the current invocation. For persistent personas you can switch between and share across a project, use [output styles](/en/output-styles). For project conventions Claude should always follow, use [CLAUDE.md](/en/memory). The [Agent SDK guide on system prompts](/en/agent-sdk/modifying-system-prompts#decide-on-a-starting-point) covers the same decision in more depth.

## See also

* [Chrome extension](/en/chrome) - Browser automation and web testing
* [Interactive mode](/en/interactive-mode) - Shortcuts, input modes, and interactive features
* [Quickstart guide](/en/quickstart) - Getting started with Claude Code
* [Common workflows](/en/common-workflows) - Advanced workflows and patterns
* [Settings](/en/settings) - Configuration options
* [Agent SDK documentation](/en/agent-sdk/overview) - Programmatic usage and integrations


================================================================================
# PAGE: slash-commands
# source: https://docs.claude.com/en/docs/claude-code/slash-commands.md
================================================================================

> ## Documentation Index
> Fetch the complete documentation index at: https://code.claude.com/docs/llms.txt
> Use this file to discover all available pages before exploring further.

# Extend Claude with skills

> Create, manage, and share skills to extend Claude's capabilities in Claude Code. Includes custom commands and bundled skills.

Skills extend what Claude can do. Create a `SKILL.md` file with instructions, and Claude adds it to its toolkit. Claude uses skills when relevant, or you can invoke one directly with `/skill-name`.

Create a skill when you keep pasting the same instructions, checklist, or multi-step procedure into chat, or when a section of CLAUDE.md has grown into a procedure rather than a fact. Unlike CLAUDE.md content, a skill's body loads only when it's used, so long reference material costs almost nothing until you need it.

<Note>
  For built-in commands like `/help` and `/compact`, and bundled skills like `/debug` and `/code-review`, see the [commands reference](/en/commands).

  **Custom commands have been merged into skills.** A file at `.claude/commands/deploy.md` and a skill at `.claude/skills/deploy/SKILL.md` both create `/deploy` and work the same way. Your existing `.claude/commands/` files keep working. Skills add optional features: a directory for supporting files, frontmatter to [control whether you or Claude invokes them](#control-who-invokes-a-skill), and the ability for Claude to load them automatically when relevant.
</Note>

Claude Code skills follow the [Agent Skills](https://agentskills.io) open standard, which works across multiple AI tools. Claude Code extends the standard with additional features like [invocation control](#control-who-invokes-a-skill), [subagent execution](#run-skills-in-a-subagent), and [dynamic context injection](#inject-dynamic-context).

## Bundled skills

Claude Code includes a set of bundled skills that are available in every session unless disabled with the [`disableBundledSkills`](/en/settings#available-settings) setting, including `/code-review`, `/batch`, `/debug`, `/loop`, and `/claude-api`. Unlike most built-in commands, which execute fixed logic directly, bundled skills are prompt-based: they give Claude detailed instructions and let it orchestrate the work using its tools. You invoke them the same way as any other skill, by typing `/` followed by the skill name.

Bundled skills are listed alongside built-in commands in the [commands reference](/en/commands), marked **Skill** in the Purpose column.

### Run and verify your app

Three bundled skills work together to launch your app and confirm changes against the running app instead of just tests:

| Skill                  | Purpose                                                                                                           |
| :--------------------- | :---------------------------------------------------------------------------------------------------------------- |
| `/run`                 | Launch and drive your app to see a change working                                                                 |
| `/verify`              | Build and run your app to confirm a code change does what it should, without falling back to tests or type checks |
| `/run-skill-generator` | Teach `/run` and `/verify` how to build and launch your project                                                   |

{/* min-version: 2.1.145 */}All three skills require Claude Code v2.1.145 or later.

`/run` and `/verify` work without setup. They infer the launch from your project type (CLI, server, TUI, browser-driven) and from what's in your README, `package.json`, or `Makefile`. That inference gets unreliable for projects that need anything beyond a standard launch: a database, an env file, a graphical session, a multi-step build.

`/run-skill-generator` records the recipe instead. It gets your app running from a clean environment, captures what worked (the install commands, the env vars, the launch script), and commits it as a per-project skill at `.claude/skills/run-<name>/`. After that, `/run`, `/verify`, and any other agent in the repo follow the recorded recipe instead of rediscovering it. Run `/run-skill-generator` once per project, and again if the build or launch process changes.

## Getting started

### Create your first skill

This example creates a skill that summarizes the uncommitted changes in your git repository and flags anything risky. It pulls the live diff into the prompt before Claude reads it, so the response is grounded in your actual working tree rather than what Claude can guess from open files. Claude loads the skill automatically when you ask about your changes, or you can invoke it directly with `/summarize-changes`.

<Steps>
  <Step title="Create the skill directory">
    Create a directory for the skill in your personal skills folder. Personal skills are available across all your projects.

    ```bash theme={null}
    mkdir -p ~/.claude/skills/summarize-changes
    ```
  </Step>

  <Step title="Write SKILL.md">
    Every skill needs a `SKILL.md` file with two parts: YAML frontmatter between `---` markers that tells Claude when to use the skill, and markdown content with the instructions Claude follows when the skill runs. The directory name becomes the command you type, and the `description` helps Claude decide when to load the skill automatically.

    Save this to `~/.claude/skills/summarize-changes/SKILL.md`:

    ```yaml theme={null}
    ---
    description: Summarizes uncommitted changes and flags anything risky. Use when the user asks what changed, wants a commit message, or asks to review their diff.
    ---

    ## Current changes

    !`git diff HEAD`

    ## Instructions

    Summarize the changes above in two or three bullet points, then list any risks you notice such as missing error handling, hardcoded values, or tests that need updating. If the diff is empty, say there are no uncommitted changes.
    ```

    The `` !`git diff HEAD` `` line uses [dynamic context injection](#inject-dynamic-context): Claude Code runs the command and replaces the line with its output before Claude sees the skill content, so the instructions arrive with the current diff already inlined.
  </Step>

  <Step title="Test the skill">
    Open a git project, make a small edit to any file, and start Claude Code by running `claude`. You can test the skill two ways.

    **Let Claude invoke it automatically** by asking something that matches the description:

    ```text theme={null}
    What did I change?
    ```

    **Or invoke it directly** with the skill name:

    ```text theme={null}
    /summarize-changes
    ```

    Either way, Claude should respond with a short summary of your edit and a list of risks.
  </Step>
</Steps>

### Where skills live

Where you store a skill determines who can use it:

| Location   | Path                                                | Applies to                     |
| :--------- | :-------------------------------------------------- | :----------------------------- |
| Enterprise | See [managed settings](/en/settings#settings-files) | All users in your organization |
| Personal   | `~/.claude/skills/<skill-name>/SKILL.md`            | All your projects              |
| Project    | `.claude/skills/<skill-name>/SKILL.md`              | This project only              |
| Plugin     | `<plugin>/skills/<skill-name>/SKILL.md`             | Where plugin is enabled        |

When skills share the same name across levels, enterprise overrides personal, and personal overrides project. A skill at any of these levels also overrides a bundled skill with the same name. For example, a `code-review` skill in your project's `.claude/skills/` replaces the bundled `/code-review`. Plugin skills use a `plugin-name:skill-name` namespace, so they cannot conflict with other levels. If you have files in `.claude/commands/`, those work the same way, but if a skill and a command share the same name, the skill takes precedence.

Skills also load from nested `.claude/skills/` directories below your working directory. When Claude reads or edits a file in a subdirectory, skills from that subdirectory's `.claude/skills/` become available. This lets a monorepo package provide its own skills that apply when working on that package, even if the session started at the repo root.

If a nested skill shares a name with another skill, both stay available. For example, with a `deploy` skill at the project root and another in `apps/web/.claude/skills/`:

* The nested one appears under a directory-qualified name, `apps/web:deploy`.
* Its description says which directory it applies to.
* Claude picks the variant that matches the files it is working on.

Typing `/deploy` runs the project-root skill. Type the qualified name `/apps/web:deploy` to run the nested variant explicitly.

A `<skill-name>` entry in the enterprise, personal, or project locations can be a symlink to a directory elsewhere on disk. Claude Code follows the symlink and reads `SKILL.md` from the target directory, and if the same target is reachable from more than one location, Claude Code loads the skill once. Plugin skills handle symlinks differently; see [Share files within a marketplace with symlinks](/en/plugins-reference#share-files-within-a-marketplace-with-symlinks).

<Note>
  Add a `.claude-plugin/plugin.json` to a skill folder and it loads as a [plugin](/en/plugins-reference#skills-directory-plugins) named `<name>@skills-dir`, so it can bundle agents, hooks, and MCP servers. In a project's `.claude/skills/`, this requires accepting the workspace trust dialog first.
</Note>

#### Live change detection

Claude Code watches skill directories for file changes. Adding, editing, or removing a skill under `~/.claude/skills/`, the project `.claude/skills/`, or a `.claude/skills/` inside an `--add-dir` directory takes effect within the current session without restarting. Creating a top-level skills directory that did not exist when the session started requires restarting Claude Code so the new directory can be watched.

<Note>
  Live change detection covers `SKILL.md` text only. For a skill folder that is also a [plugin](/en/plugins-reference#skills-directory-plugins), changes to `hooks/`, `.mcp.json`, `agents/`, and `output-styles/` need `/reload-plugins` to take effect.
</Note>

#### Automatic discovery from parent and nested directories

Project skills load from `.claude/skills/` in your starting directory and in every parent directory up to the repository root, so starting Claude in a subdirectory still picks up skills defined at the root. When you work with files in subdirectories below your starting directory, Claude Code also discovers skills from nested `.claude/skills/` directories on demand. For example, if you're editing a file in `packages/frontend/`, Claude Code also looks for skills in `packages/frontend/.claude/skills/`. This supports monorepo setups where packages have their own skills.

Each skill is a directory with `SKILL.md` as the entrypoint:

```text theme={null}
my-skill/
├── SKILL.md           # Main instructions (required)
├── template.md        # Template for Claude to fill in
├── examples/
│   └── sample.md      # Example output showing expected format
└── scripts/
    └── validate.sh    # Script Claude can execute
```

The `SKILL.md` contains the main instructions and is required. Other files are optional and let you build more powerful skills: templates for Claude to fill in, example outputs showing the expected format, scripts Claude can execute, or detailed reference documentation. Reference these files from your `SKILL.md` so Claude knows what they contain and when to load them. See [Add supporting files](#add-supporting-files) for more details.

<Note>
  Files in `.claude/commands/` still work and support the same [frontmatter](#frontmatter-reference). Skills are recommended since they support additional features like supporting files.
</Note>

#### Skills from additional directories

The `--add-dir` flag and `/add-dir` command [grant file access](/en/permissions#additional-directories-grant-file-access-not-configuration) rather than configuration discovery, but skills are an exception: `.claude/skills/` within an added directory is loaded automatically. This exception applies only to `--add-dir` and `/add-dir`. The `permissions.additionalDirectories` setting in `settings.json` grants file access only and does not load skills. See [Live change detection](#live-change-detection) for how edits are picked up during a session.

Other `.claude/` configuration such as commands and output styles is not loaded from additional directories. See the [exceptions table](/en/permissions#additional-directories-grant-file-access-not-configuration) for the complete list of what is and isn't loaded, and the recommended ways to share configuration across projects.

<Note>
  CLAUDE.md files from `--add-dir` directories are not loaded by default. To load them, set `CLAUDE_CODE_ADDITIONAL_DIRECTORIES_CLAUDE_MD=1`. See [Load from additional directories](/en/memory#load-from-additional-directories).
</Note>

## Configure skills

Skills are configured through YAML frontmatter at the top of `SKILL.md` and the markdown content that follows.

### Types of skill content

Skill files can contain any instructions, but thinking about how you want to invoke them helps guide what to include:

**Reference content** adds knowledge Claude applies to your current work. Conventions, patterns, style guides, domain knowledge. This content runs inline so Claude can use it alongside your conversation context.

```yaml theme={null}
---
name: api-conventions
description: API design patterns for this codebase
---

When writing API endpoints:
- Use RESTful naming conventions
- Return consistent error formats
- Include request validation
```

**Task content** gives Claude step-by-step instructions for a specific action, like deployments, commits, or code generation. These are often actions you want to invoke directly with `/skill-name` rather than letting Claude decide when to run them. Add `disable-model-invocation: true` to prevent Claude from triggering it automatically.

```yaml theme={null}
---
name: deploy
description: Deploy the application to production
context: fork
disable-model-invocation: true
---

Deploy the application:
1. Run the test suite
2. Build the application
3. Push to the deployment target
```

Your `SKILL.md` can contain anything, but thinking through how you want the skill invoked (by you, by Claude, or both) and where you want it to run (inline or in a subagent) helps guide what to include. For complex skills, you can also [add supporting files](#add-supporting-files) to keep the main skill focused.

Keep the body itself concise. Once a skill loads, its content [stays in context across turns](#skill-content-lifecycle), so every line is a recurring token cost. State what to do rather than narrating how or why, and apply the same conciseness test you would for [CLAUDE.md content](/en/best-practices#write-an-effective-claude-md).

### Frontmatter reference

Beyond the markdown content, you can configure skill behavior using YAML frontmatter fields between `---` markers at the top of your `SKILL.md` file:

```yaml theme={null}
---
name: my-skill
description: What this skill does
disable-model-invocation: true
allowed-tools: Read Grep
---

Your skill instructions here...
```

All fields are optional. Only `description` is recommended so Claude knows when to use the skill.

| Field                      | Required    | Description                                                                                                                                                                                                                                                                                                                                                                                                                                      |
| :------------------------- | :---------- | :----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `name`                     | No          | Display name shown in skill listings. Defaults to the directory name. See [How a skill gets its command name](#how-a-skill-gets-its-command-name) for how this differs from the name you type to invoke the skill.                                                                                                                                                                                                                               |
| `description`              | Recommended | What the skill does and when to use it. Claude uses this to decide when to apply the skill. If omitted, uses the first paragraph of markdown content. Put the key use case first: the combined `description` and `when_to_use` text is truncated at 1,536 characters in the skill listing to reduce context usage.                                                                                                                               |
| `when_to_use`              | No          | Additional context for when Claude should invoke the skill, such as trigger phrases or example requests. Appended to `description` in the skill listing and counts toward the 1,536-character cap.                                                                                                                                                                                                                                               |
| `argument-hint`            | No          | Hint shown during autocomplete to indicate expected arguments. Example: `[issue-number]` or `[filename] [format]`.                                                                                                                                                                                                                                                                                                                               |
| `arguments`                | No          | Named positional arguments for [`$name` substitution](#available-string-substitutions) in the skill content. Accepts a space-separated string or a YAML list. Names map to argument positions in order.                                                                                                                                                                                                                                          |
| `disable-model-invocation` | No          | Set to `true` to prevent Claude from automatically loading this skill. Use for workflows you want to trigger manually with `/name`. Also prevents the skill from being [preloaded into subagents](/en/sub-agents#preload-skills-into-subagents). {/* min-version: 2.1.196 */}As of v2.1.196, also prevents the skill from running when a [scheduled task](/en/scheduled-tasks) fires with the skill as its prompt. Default: `false`.             |
| `user-invocable`           | No          | Set to `false` to hide from the `/` menu. Use for background knowledge users shouldn't invoke directly. Default: `true`.                                                                                                                                                                                                                                                                                                                         |
| `allowed-tools`            | No          | Tools Claude can use without asking permission when this skill is active. Accepts a space- or comma-separated string, or a YAML list.                                                                                                                                                                                                                                                                                                            |
| `disallowed-tools`         | No          | Tools removed from Claude's available pool while this skill is active. Use for autonomous skills that should never call certain tools, such as `AskUserQuestion` for a background loop. Accepts a space- or comma-separated string, or a YAML list. The restriction clears when you send your next message.                                                                                                                                      |
| `model`                    | No          | Model to use when this skill is active. The override applies for the rest of the current turn and is not saved to settings; the session model resumes on your next prompt. Accepts the same values as [`/model`](/en/model-config), or `inherit` to keep the active model. A value excluded by your organization's [`availableModels`](/en/model-config#restrict-model-selection) allowlist is not used and the session keeps its current model. |
| `effort`                   | No          | [Effort level](/en/model-config#adjust-effort-level) when this skill is active. Overrides the session effort level. Default: inherits from session. Options: `low`, `medium`, `high`, `xhigh`, `max`; available levels depend on the model.                                                                                                                                                                                                      |
| `context`                  | No          | Set to `fork` to run in a forked subagent context.                                                                                                                                                                                                                                                                                                                                                                                               |
| `agent`                    | No          | Which subagent type to use when `context: fork` is set.                                                                                                                                                                                                                                                                                                                                                                                          |
| `hooks`                    | No          | Hooks scoped to this skill's lifecycle. See [Hooks in skills and agents](/en/hooks#hooks-in-skills-and-agents) for configuration format.                                                                                                                                                                                                                                                                                                         |
| `paths`                    | No          | Glob patterns that limit when this skill is activated. Accepts a comma-separated string or a YAML list. When set, Claude loads the skill automatically only when working with files matching the patterns. Uses the same format as [path-specific rules](/en/memory#path-specific-rules).                                                                                                                                                        |
| `shell`                    | No          | Shell to use for `` !`command` `` and ` ```! ` blocks in this skill. Accepts `bash` (default) or `powershell`. Setting `powershell` runs inline shell commands via PowerShell on Windows. Requires `CLAUDE_CODE_USE_POWERSHELL_TOOL=1`.                                                                                                                                                                                                          |

#### How a skill gets its command name

The command you type to invoke a skill comes from where the skill file lives. The frontmatter `name` field sets the display label shown in skill listings and, except for a plugin-root `SKILL.md`, does not change what you type after `/`.

The table below shows where the command name comes from for each layout:

| Skill location                                                                                     | Command name source                                                                | Example                                                                                                                              |
| :------------------------------------------------------------------------------------------------- | :--------------------------------------------------------------------------------- | :----------------------------------------------------------------------------------------------------------------------------------- |
| Skill directory under `~/.claude/skills/` or `.claude/skills/`                                     | Directory name                                                                     | `.claude/skills/deploy-staging/SKILL.md` → `/deploy-staging`                                                                         |
| [Nested](#where-skills-live) `.claude/skills/` directory, when the name clashes with another skill | Subdirectory path relative to the working directory, then the skill directory name | `apps/web/.claude/skills/deploy/SKILL.md` → `/apps/web:deploy`                                                                       |
| File under `.claude/commands/`                                                                     | File name without extension                                                        | `.claude/commands/deploy.md` → `/deploy`                                                                                             |
| Plugin `skills/` subdirectory                                                                      | Directory name, namespaced by plugin                                               | `my-plugin/skills/review/SKILL.md` → `/my-plugin:review`                                                                             |
| Plugin root `SKILL.md`                                                                             | Frontmatter `name`, with the plugin directory name as a fallback                   | `my-plugin/SKILL.md` with `name: review` → `/my-plugin:review`. See [Path behavior rules](/en/plugins-reference#path-behavior-rules) |

The plugin-root case is the one place where `name` does set the command name, because there is no skill directory to take it from. If `name` is not set in the frontmatter, the plugin's directory name is used instead.

#### Available string substitutions

Skills support string substitution for dynamic values in the skill content:

| Variable                | Description                                                                                                                                                                                                                                                                                                 |
| :---------------------- | :---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `$ARGUMENTS`            | All arguments passed when invoking the skill. If `$ARGUMENTS` is not present in the content, arguments are appended as `ARGUMENTS: <value>`.                                                                                                                                                                |
| `$ARGUMENTS[N]`         | Access a specific argument by 0-based index, such as `$ARGUMENTS[0]` for the first argument.                                                                                                                                                                                                                |
| `$N`                    | Shorthand for `$ARGUMENTS[N]`, such as `$0` for the first argument or `$1` for the second.                                                                                                                                                                                                                  |
| `$name`                 | Named argument declared in the [`arguments`](#frontmatter-reference) frontmatter list. Names map to positions in order, so with `arguments: [issue, branch]` the placeholder `$issue` expands to the first argument and `$branch` to the second.                                                            |
| `${CLAUDE_SESSION_ID}`  | The current session ID. Useful for logging, creating session-specific files, or correlating skill output with sessions.                                                                                                                                                                                     |
| `${CLAUDE_EFFORT}`      | The current effort level: `low`, `medium`, `high`, `xhigh`, or `max`. Ultracode is not a distinct level and reports as `xhigh`. Use this to adapt skill instructions to the active effort setting.                                                                                                          |
| `${CLAUDE_SKILL_DIR}`   | The directory containing the skill's `SKILL.md` file. For plugin skills, this is the skill's subdirectory within the plugin, not the plugin root. Use this in bash injection commands to reference scripts or files bundled with the skill, regardless of the current working directory.                    |
| `${CLAUDE_PROJECT_DIR}` | The project root directory. This is the same path [hooks](/en/hooks#reference-scripts-by-path) and MCP servers receive as `CLAUDE_PROJECT_DIR`. Use this to reference project-local scripts or files, such as `${CLAUDE_PROJECT_DIR}/.claude/hooks/helper.sh`, independent of where the skill is installed. |

The `${CLAUDE_PROJECT_DIR}` substitution requires Claude Code v2.1.196 or later. It applies to both the skill body and the [`allowed-tools`](#frontmatter-reference) frontmatter, so a permission rule like `Bash(${CLAUDE_PROJECT_DIR}/scripts/lint.sh *)` resolves to the same path the skill body uses.

Indexed arguments use shell-style quoting, so wrap multi-word values in quotes to pass them as a single argument. For example, `/my-skill "hello world" second` makes `$0` expand to `hello world` and `$1` to `second`. The `$ARGUMENTS` placeholder always expands to the full argument string as typed.

To include a literal `$` before a digit, `ARGUMENTS`, or a declared argument name, such as `$1.00` in prose, escape it with a backslash: `\$1.00`. A backslash before any other `$` is left unchanged. Only a single backslash directly before the token escapes it. A doubled backslash such as `\\$1` leaves both backslashes in place, and `$1` still expands to the argument value.

**Example using substitutions:**

```yaml theme={null}
---
name: session-logger
description: Log activity for this session
---

Log the following to logs/${CLAUDE_SESSION_ID}.log:

$ARGUMENTS
```

### Add supporting files

Skills can include multiple files in their directory. This keeps `SKILL.md` focused on the essentials while letting Claude access detailed reference material only when needed. Large reference docs, API specifications, or example collections don't need to load into context every time the skill runs.

```text theme={null}
my-skill/
├── SKILL.md (required - overview and navigation)
├── reference.md (detailed API docs - loaded when needed)
├── examples.md (usage examples - loaded when needed)
└── scripts/
    └── helper.py (utility script - executed, not loaded)
```

Reference supporting files from `SKILL.md` so Claude knows what each file contains and when to load it:

```markdown theme={null}
## Additional resources

- For complete API details, see [reference.md](reference.md)
- For usage examples, see [examples.md](examples.md)
```

<Tip>Keep `SKILL.md` under 500 lines. Move detailed reference material to separate files.</Tip>

### Control who invokes a skill

By default, both you and Claude can invoke any skill. You can type `/skill-name` to invoke it directly, and Claude can load it automatically when relevant to your conversation. Two frontmatter fields let you restrict this:

* **`disable-model-invocation: true`**: Only you can invoke the skill. Use this for workflows with side effects or that you want to control timing, like `/commit`, `/deploy`, or `/send-slack-message`. You don't want Claude deciding to deploy because your code looks ready.

* **`user-invocable: false`**: Only Claude can invoke the skill. Use this for background knowledge that isn't actionable as a command. A `legacy-system-context` skill explains how an old system works. Claude should know this when relevant, but `/legacy-system-context` isn't a meaningful action for users to take.

This example creates a deploy skill that only you can trigger. The `disable-model-invocation: true` field prevents Claude from running it automatically:

```yaml theme={null}
---
name: deploy
description: Deploy the application to production
disable-model-invocation: true
---

Deploy $ARGUMENTS to production:

1. Run the test suite
2. Build the application
3. Push to the deployment target
4. Verify the deployment succeeded
```

Here's how the two fields affect invocation and context loading:

| Frontmatter                      | You can invoke | Claude can invoke | When loaded into context                                     |
| :------------------------------- | :------------- | :---------------- | :----------------------------------------------------------- |
| (default)                        | Yes            | Yes               | Description always in context, full skill loads when invoked |
| `disable-model-invocation: true` | Yes            | No                | Description not in context, full skill loads when you invoke |
| `user-invocable: false`          | No             | Yes               | Description always in context, full skill loads when invoked |

<Note>
  In a regular session, skill descriptions are loaded into context so Claude knows what's available, but full skill content only loads when invoked. [Subagents with preloaded skills](/en/sub-agents#preload-skills-into-subagents) work differently: the full skill content is injected at startup.
</Note>

### Skill content lifecycle

When you or Claude invoke a skill, the rendered `SKILL.md` content enters the conversation as a single message and stays there for the rest of the session. Claude Code does not re-read the skill file on later turns, so write guidance that should apply throughout a task as standing instructions rather than one-time steps.

[Auto-compaction](/en/how-claude-code-works#when-context-fills-up) carries invoked skills forward within a token budget. When the conversation is summarized to free context, Claude Code re-attaches the most recent invocation of each skill after the summary, keeping the first 5,000 tokens of each. Re-attached skills share a combined budget of 25,000 tokens. Claude Code fills this budget starting from the most recently invoked skill, so older skills can be dropped entirely after compaction if you have invoked many in one session.

If a skill seems to stop influencing behavior after the first response, the content is usually still present and the model is choosing other tools or approaches. Strengthen the skill's `description` and instructions so the model keeps preferring it, or use [hooks](/en/hooks) to enforce behavior deterministically. If the skill is large or you invoked several others after it, re-invoke it after compaction to restore the full content.

### Pre-approve tools for a skill

The `allowed-tools` field grants permission for the listed tools while the skill is active, so Claude can use them without prompting you for approval. It does not restrict which tools are available: every tool remains callable, and your [permission settings](/en/permissions) still govern tools that are not listed.

For skills checked into a project's `.claude/skills/` directory, `allowed-tools` takes effect after you accept the workspace trust dialog for that folder, the same as permission rules in `.claude/settings.json`. Review project skills before trusting a repository, since a skill can grant itself broad tool access.

This skill lets Claude run git commands without per-use approval whenever you invoke it:

```yaml theme={null}
---
name: commit
description: Stage and commit the current changes
disable-model-invocation: true
allowed-tools: Bash(git add *) Bash(git commit *) Bash(git status *)
---
```

To remove tools from Claude's available pool while a skill is active, list them in `disallowed-tools` in the skill's frontmatter. The restriction clears when you send your next message. To block tools across all skills and prompts, add deny rules in your [permission settings](/en/permissions).

### Pass arguments to skills

Both you and Claude can pass arguments when invoking a skill. Arguments are available via the `$ARGUMENTS` placeholder.

This skill fixes a GitHub issue by number. The `$ARGUMENTS` placeholder gets replaced with whatever follows the skill name:

```yaml theme={null}
---
name: fix-issue
description: Fix a GitHub issue
disable-model-invocation: true
---

Fix GitHub issue $ARGUMENTS following our coding standards.

1. Read the issue description
2. Understand the requirements
3. Implement the fix
4. Write tests
5. Create a commit
```

When you run `/fix-issue 123`, Claude receives "Fix GitHub issue 123 following our coding standards..."

If you invoke a skill with arguments but the skill doesn't include `$ARGUMENTS`, Claude Code appends `ARGUMENTS: <your input>` to the end of the skill content so Claude still sees what you typed.

To access individual arguments by position, use `$ARGUMENTS[N]` or the shorter `$N`:

```yaml theme={null}
---
name: migrate-component
description: Migrate a component from one framework to another
---

Migrate the $ARGUMENTS[0] component from $ARGUMENTS[1] to $ARGUMENTS[2].
Preserve all existing behavior and tests.
```

Running `/migrate-component SearchBar React Vue` replaces `$ARGUMENTS[0]` with `SearchBar`, `$ARGUMENTS[1]` with `React`, and `$ARGUMENTS[2]` with `Vue`. The same skill using the `$N` shorthand:

```yaml theme={null}
---
name: migrate-component
description: Migrate a component from one framework to another
---

Migrate the $0 component from $1 to $2.
Preserve all existing behavior and tests.
```

## Advanced patterns

### Inject dynamic context

The `` !`<command>` `` syntax runs shell commands before the skill content is sent to Claude. The command output replaces the placeholder, so Claude receives actual data, not the command itself.

This skill summarizes a pull request by fetching live PR data with the GitHub CLI. The `` !`gh pr diff` `` and other commands run first, and their output gets inserted into the prompt:

```yaml theme={null}
---
name: pr-summary
description: Summarize changes in a pull request
context: fork
agent: Explore
allowed-tools: Bash(gh *)
---

## Pull request context
- PR diff: !`gh pr diff`
- PR comments: !`gh pr view --comments`
- Changed files: !`gh pr diff --name-only`

## Your task
Summarize this pull request...
```

When this skill runs:

1. Each `` !`<command>` `` executes immediately (before Claude sees anything)
2. The output replaces the placeholder in the skill content
3. Claude receives the fully-rendered prompt with actual PR data

This is preprocessing, not something Claude executes. Claude only sees the final result.

Substitution runs once over the original file. Command output is inserted as plain text and is not re-scanned for further `` !`<command>` `` placeholders, so a command cannot emit a placeholder for a later pass to expand.

The inline form is only recognized when `!` appears at the start of a line or immediately after whitespace. If `!` follows another character, as in `` KEY=!`cmd` ``, the placeholder is left as literal text and the command does not run.

For multi-line commands, use a fenced code block opened with ` ```! ` instead of the inline form:

````markdown theme={null}
## Environment
```!
node --version
npm --version
git status --short
```
````

To disable this behavior for skills and custom commands from user, project, plugin, or [additional-directory](#skills-from-additional-directories) sources, set `"disableSkillShellExecution": true` in [settings](/en/settings). Each command is replaced with `[shell command execution disabled by policy]` instead of being run. Bundled and managed skills are not affected. This setting is most useful in [managed settings](/en/permissions#managed-settings), where users cannot override it.

<Tip>
  To request deeper reasoning when a skill runs, include `ultrathink` anywhere in the skill content. See [Use ultrathink for one-off deep reasoning](/en/model-config#use-ultrathink-for-one-off-deep-reasoning).
</Tip>

### Run skills in a subagent

Add `context: fork` to your frontmatter when you want a skill to run in isolation. The skill content becomes the prompt that drives the subagent. It won't have access to your conversation history.

<Warning>
  `context: fork` only makes sense for skills with explicit instructions. If your skill contains guidelines like "use these API conventions" without a task, the subagent receives the guidelines but no actionable prompt, and returns without meaningful output.
</Warning>

Skills and [subagents](/en/sub-agents) work together in two directions:

| Approach                     | System prompt            | Task                        | Also loads                                          |
| :--------------------------- | :----------------------- | :-------------------------- | :-------------------------------------------------- |
| Skill with `context: fork`   | From agent type          | SKILL.md content            | CLAUDE.md, except when the agent is Explore or Plan |
| Subagent with `skills` field | Subagent's markdown body | Claude's delegation message | Preloaded skills + CLAUDE.md                        |

With `context: fork`, you write the task in your skill and pick an agent type to execute it. The built-in Explore and Plan agents [skip CLAUDE.md and git status](/en/sub-agents#what-loads-at-startup) to keep their context small, so a forked skill using `agent: Explore` sees only the SKILL.md content and the agent's own system prompt. For the inverse, where you define a custom subagent that uses skills as reference material, see [Subagents](/en/sub-agents#preload-skills-into-subagents).

#### Example: Research skill using Explore agent

This skill runs research in a forked Explore agent. The skill content becomes the task, and the agent provides read-only tools optimized for codebase exploration:

```yaml theme={null}
---
name: deep-research
description: Research a topic thoroughly
context: fork
agent: Explore
---

Research $ARGUMENTS thoroughly:

1. Find relevant files using Glob and Grep
2. Read and analyze the code
3. Summarize findings with specific file references
```

When this skill runs:

1. A new isolated context is created
2. The subagent receives the skill content as its prompt ("Research \$ARGUMENTS thoroughly...")
3. The `agent` field determines the execution environment (model, tools, and permissions)
4. Results are summarized and returned to your main conversation

The `agent` field specifies which subagent configuration to use. Options include built-in agents (`Explore`, `Plan`, `general-purpose`) or any custom subagent from `.claude/agents/`. If omitted, uses `general-purpose`.

### Restrict Claude's skill access

By default, Claude can invoke any skill that doesn't have `disable-model-invocation: true` set. Skills that define `allowed-tools` grant Claude access to those tools without per-use approval when the skill is active. Your [permission settings](/en/permissions) still govern baseline approval behavior for all other tools. A few built-in commands are also available through the Skill tool, including `/init`, `/review`, and `/security-review`. Other built-in commands such as `/compact` are not.

Three ways to control which skills Claude can invoke:

**Disable all skills** by denying the Skill tool in `/permissions`:

```text theme={null}
# Add to deny rules:
Skill
```

**Allow or deny specific skills** using [permission rules](/en/permissions):

```text theme={null}
# Allow only specific skills
Skill(commit)
Skill(review-pr *)

# Deny specific skills
Skill(deploy *)
```

Permission syntax: `Skill(name)` for exact match, `Skill(name *)` for prefix match with any arguments.

**Hide individual skills** by adding `disable-model-invocation: true` to their frontmatter. This removes the skill from Claude's context entirely.

<Note>
  The `user-invocable` field only controls menu visibility, not Skill tool access. Use `disable-model-invocation: true` to block programmatic invocation.
</Note>

### Override skill visibility from settings

The `skillOverrides` setting controls skill visibility from your [settings](/en/settings) instead of the skill's own frontmatter. Use it for skills whose SKILL.md you don't want to edit, such as ones checked into a shared project repo or provided by an MCP server. The `/skills` menu writes it for you: highlight a skill and press `Space` to cycle states, then `Enter` to save to `.claude/settings.local.json`.

Each key is a skill name and each value is one of four states:

| Value                   | Listed to Claude     | In `/` menu |
| :---------------------- | :------------------- | :---------- |
| `"on"`                  | Name and description | Yes         |
| `"name-only"`           | Name only            | Yes         |
| `"user-invocable-only"` | Hidden               | Yes         |
| `"off"`                 | Hidden               | Hidden      |

A skill that is absent from `skillOverrides` is treated as `"on"`. The example below collapses one skill to its name and turns another off entirely:

```json theme={null}
{
  "skillOverrides": {
    "legacy-context": "name-only",
    "deploy": "off"
  }
}
```

Plugin skills are not affected by `skillOverrides`. Manage those through `/plugin` instead.

## Evaluate and iterate on a skill

Seeing a skill trigger tells you Claude found it, not that it did what you intended. To know a skill is working, measure two things separately: whether Claude invokes it on the prompts it should, and whether the output matches what you expect when it does.

The check for both is a baseline comparison. Collect a few realistic prompts, run each one in a fresh session with the skill available and again with it [disabled](#override-skill-visibility-from-settings), and compare the results. A fresh session matters because leftover context from authoring the skill will mask gaps in the written instructions.

### Run evals with skill-creator

The [`skill-creator` plugin](https://github.com/anthropics/claude-plugins-official/tree/main/plugins/skill-creator) automates the comparison loop inside Claude Code. Install it from the official marketplace:

```text theme={null}
/plugin install skill-creator@claude-plugins-official
```

If Claude Code reports that the plugin is not found in any marketplace, your marketplace is either missing or outdated. Run `/plugin marketplace update claude-plugins-official` to refresh it, or `/plugin marketplace add anthropics/claude-plugins-official` if you haven't added it before. Then retry the install.

After installing, run `/reload-plugins` to make the plugin's skills available in the current session. Then ask Claude to evaluate an existing skill, for example `evaluate my summarize-changes skill with skill-creator`. The plugin walks you through writing test cases and runs the loop:

* **Test cases**: stores prompts, input files, and expected behavior in `evals/evals.json` inside the skill directory
* **Isolated runs**: spawns a [subagent](/en/sub-agents) per test case so each run starts with a clean context, and records token count and duration
* **Grading**: checks each assertion against the output and writes pass or fail with evidence to `grading.json`
* **Benchmark**: aggregates pass rate, time, and tokens for with-skill versus without-skill into `benchmark.json` so you can compare the pass-rate improvement against the token and time overhead
* **Version comparison**: runs a blind A/B between two versions of the skill so you can confirm an edit is an improvement before committing it
* **Description tuning**: generates should-trigger and should-not-trigger prompts, measures the hit rate, and proposes description edits when the skill activates on the wrong requests
* **Review viewer**: opens an HTML report where you inspect each output and record qualitative feedback that the next iteration reads

For the eval file format and the full iteration workflow, see [Evaluating skill output quality](https://agentskills.io/skill-creation/evaluating-skills) on agentskills.io. For background on the benchmark and comparison modes, see the [skill-creator announcement](https://claude.com/blog/improving-skill-creator-test-measure-and-refine-agent-skills).

## Share skills

Skills can be distributed at different scopes depending on your audience:

* **Project skills**: Commit `.claude/skills/` to version control
* **Plugins**: Create a `skills/` directory in your [plugin](/en/plugins)
* **Managed**: Deploy organization-wide through [managed settings](/en/settings#settings-files)

### Generate visual output

Skills can bundle and run scripts in any language, giving Claude capabilities beyond what's possible in a single prompt. One powerful pattern is generating visual output: interactive HTML files that open in your browser for exploring data, debugging, or creating reports.

This example creates a codebase explorer: an interactive tree view where you can expand and collapse directories, see file sizes at a glance, and identify file types by color.

Create the Skill directory:

```bash theme={null}
mkdir -p ~/.claude/skills/codebase-visualizer/scripts
```

Save this to `~/.claude/skills/codebase-visualizer/SKILL.md`. The description tells Claude when to activate this Skill, and the instructions tell Claude to run the bundled script. The script path uses [`${CLAUDE_SKILL_DIR}`](#available-string-substitutions) so it resolves correctly whether the skill is installed at the personal, project, or plugin level:

````yaml theme={null}
---
name: codebase-visualizer
description: Generate an interactive collapsible tree visualization of your codebase. Use when exploring a new repo, understanding project structure, or identifying large files.
allowed-tools: Bash(python3 *)
---

# Codebase Visualizer

Generate an interactive HTML tree view that shows your project's file structure with collapsible directories.

## Usage

Run the visualization script from your project root:

```bash
python3 ${CLAUDE_SKILL_DIR}/scripts/visualize.py .
```

This creates `codebase-map.html` in the current directory and opens it in your default browser.

## What the visualization shows

- **Collapsible directories**: Click folders to expand/collapse
- **File sizes**: Displayed next to each file
- **Colors**: Different colors for different file types
- **Directory totals**: Shows aggregate size of each folder
````

Save this to `~/.claude/skills/codebase-visualizer/scripts/visualize.py`. This script scans a directory tree and generates a self-contained HTML file with:

* A **summary sidebar** showing file count, directory count, total size, and number of file types
* A **bar chart** breaking down the codebase by file type (top 8 by size)
* A **collapsible tree** where you can expand and collapse directories, with color-coded file type indicators

The script requires Python 3 but uses only built-in libraries, so there are no packages to install:

```python expandable theme={null}
#!/usr/bin/env python3
"""Generate an interactive collapsible tree visualization of a codebase."""

import json
import sys
import webbrowser
from html import escape
from pathlib import Path
from collections import Counter

IGNORE = {'.git', 'node_modules', '__pycache__', '.venv', 'venv', 'dist', 'build'}

def scan(path: Path, stats: dict) -> dict:
    result = {"name": path.name, "children": [], "size": 0}
    try:
        for item in sorted(path.iterdir()):
            if item.name in IGNORE or item.name.startswith('.'):
                continue
            if item.is_file():
                size = item.stat().st_size
                ext = item.suffix.lower() or '(no ext)'
                result["children"].append({"name": item.name, "size": size, "ext": ext})
                result["size"] += size
                stats["files"] += 1
                stats["extensions"][ext] += 1
                stats["ext_sizes"][ext] += size
            elif item.is_dir():
                stats["dirs"] += 1
                child = scan(item, stats)
                if child["children"]:
                    result["children"].append(child)
                    result["size"] += child["size"]
    except PermissionError:
        pass
    return result

def generate_html(data: dict, stats: dict, output: Path) -> None:
    ext_sizes = stats["ext_sizes"]
    total_size = sum(ext_sizes.values()) or 1
    sorted_exts = sorted(ext_sizes.items(), key=lambda x: -x[1])[:8]
    colors = {
        '.js': '#f7df1e', '.ts': '#3178c6', '.py': '#3776ab', '.go': '#00add8',
        '.rs': '#dea584', '.rb': '#cc342d', '.css': '#264de4', '.html': '#e34c26',
        '.json': '#6b7280', '.md': '#083fa1', '.yaml': '#cb171e', '.yml': '#cb171e',
        '.mdx': '#083fa1', '.tsx': '#3178c6', '.jsx': '#61dafb', '.sh': '#4eaa25',
    }
    lang_bars = "".join(
        f'<div class="bar-row"><span class="bar-label">{ext}</span>'
        f'<div class="bar" style="width:{(size/total_size)*100}%;background:{colors.get(ext,"#6b7280")}"></div>'
        f'<span class="bar-pct">{(size/total_size)*100:.1f}%</span></div>'
        for ext, size in sorted_exts
    )
    def fmt(b):
        if b < 1024: return f"{b} B"
        if b < 1048576: return f"{b/1024:.1f} KB"
        return f"{b/1048576:.1f} MB"

    html = f'''<!DOCTYPE html>
<html><head>
  <meta charset="utf-8"><title>Codebase Explorer</title>
  <style>
    body {{ font: 14px/1.5 system-ui, sans-serif; margin: 0; background: #1a1a2e; color: #eee; }}
    .container {{ display: flex; height: 100vh; }}
    .sidebar {{ width: 280px; background: #252542; padding: 20px; border-right: 1px solid #3d3d5c; overflow-y: auto; flex-shrink: 0; }}
    .main {{ flex: 1; padding: 20px; overflow-y: auto; }}
    h1 {{ margin: 0 0 10px 0; font-size: 18px; }}
    h2 {{ margin: 20px 0 10px 0; font-size: 14px; color: #888; text-transform: uppercase; }}
    .stat {{ display: flex; justify-content: space-between; padding: 8px 0; border-bottom: 1px solid #3d3d5c; }}
    .stat-value {{ font-weight: bold; }}
    .bar-row {{ display: flex; align-items: center; margin: 6px 0; }}
    .bar-label {{ width: 55px; font-size: 12px; color: #aaa; }}
    .bar {{ height: 18px; border-radius: 3px; }}
    .bar-pct {{ margin-left: 8px; font-size: 12px; color: #666; }}
    .tree {{ list-style: none; padding-left: 20px; }}
    details {{ cursor: pointer; }}
    summary {{ padding: 4px 8px; border-radius: 4px; }}
    summary:hover {{ background: #2d2d44; }}
    .folder {{ color: #ffd700; }}
    .file {{ display: flex; align-items: center; padding: 4px 8px; border-radius: 4px; }}
    .file:hover {{ background: #2d2d44; }}
    .size {{ color: #888; margin-left: auto; font-size: 12px; }}
    .dot {{ width: 8px; height: 8px; border-radius: 50%; margin-right: 8px; }}
  </style>
</head><body>
  <div class="container">
    <div class="sidebar">
      <h1>📊 Summary</h1>
      <div class="stat"><span>Files</span><span class="stat-value">{stats["files"]:,}</span></div>
      <div class="stat"><span>Directories</span><span class="stat-value">{stats["dirs"]:,}</span></div>
      <div class="stat"><span>Total size</span><span class="stat-value">{fmt(data["size"])}</span></div>
      <div class="stat"><span>File types</span><span class="stat-value">{len(stats["extensions"])}</span></div>
      <h2>By file type</h2>
      {lang_bars}
    </div>
    <div class="main">
      <h1>📁 {escape(data["name"])}</h1>
      <ul class="tree" id="root"></ul>
    </div>
  </div>
  <script>
    const data = {json.dumps(data)};
    const colors = {json.dumps(colors)};
    function fmt(b) {{ if (b < 1024) return b + ' B'; if (b < 1048576) return (b/1024).toFixed(1) + ' KB'; return (b/1048576).toFixed(1) + ' MB'; }}
    function esc(s) {{ return s.replace(/[&<>"']/g, c => ({{"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}}[c])); }}
    function render(node, parent) {{
      if (node.children) {{
        const det = document.createElement('details');
        det.open = parent === document.getElementById('root');
        det.innerHTML = `<summary><span class="folder">📁 ${{esc(node.name)}}</span><span class="size">${{fmt(node.size)}}</span></summary>`;
        const ul = document.createElement('ul'); ul.className = 'tree';
        node.children.sort((a,b) => (b.children?1:0)-(a.children?1:0) || a.name.localeCompare(b.name));
        node.children.forEach(c => render(c, ul));
        det.appendChild(ul);
        const li = document.createElement('li'); li.appendChild(det); parent.appendChild(li);
      }} else {{
        const li = document.createElement('li'); li.className = 'file';
        li.innerHTML = `<span class="dot" style="background:${{colors[node.ext]||'#6b7280'}}"></span>${{esc(node.name)}}<span class="size">${{fmt(node.size)}}</span>`;
        parent.appendChild(li);
      }}
    }}
    data.children.forEach(c => render(c, document.getElementById('root')));
  </script>
</body></html>'''
    output.write_text(html)

if __name__ == '__main__':
    target = Path(sys.argv[1] if len(sys.argv) > 1 else '.').resolve()
    stats = {"files": 0, "dirs": 0, "extensions": Counter(), "ext_sizes": Counter()}
    data = scan(target, stats)
    out = Path('codebase-map.html')
    generate_html(data, stats, out)
    print(f'Generated {out.absolute()}')
    webbrowser.open(f'file://{out.absolute()}')
```

To test, open Claude Code in any project and ask "Visualize this codebase." Claude runs the script, generates `codebase-map.html`, and opens it in your browser.

This pattern works for any visual output: dependency graphs, test coverage reports, API documentation, or database schema visualizations. The bundled script does the work while Claude handles orchestration.

## Troubleshooting

### Skill not triggering

If Claude doesn't use your skill when expected:

1. Check the description includes keywords users would naturally say
2. Verify the skill appears in `What skills are available?`
3. Try rephrasing your request to match the description more closely
4. Invoke it directly with `/skill-name` if the skill is user-invocable

If the frontmatter YAML is malformed, Claude Code loads the skill body with empty metadata, so `/skill-name` still works but Claude has no `description` to match against. Run with `--debug` to see the parse error.

### Skill triggers too often

If Claude uses your skill when you don't want it:

1. Make the description more specific
2. Add `disable-model-invocation: true` if you only want manual invocation

### Skill descriptions are cut short

Skill descriptions are loaded into context so Claude knows what's available. All skill names are always included, but if you have many skills, descriptions are shortened to fit the character budget, which can strip the keywords Claude needs to match your request. The budget scales at 1% of the model's context window. When it overflows, descriptions for the skills you invoke least are dropped first, so the skills you actually use keep their full text. Run `/doctor` to see how many skill descriptions are being shortened or dropped and which skills are affected.

As of v2.1.196, the Skills row in `/context` reports the size of the listing after the budget is applied, so it matches what the model receives. Earlier versions counted the full text of every description, so the row could show a value several times larger than the budget `/doctor` reports.

To raise the budget, set the [`skillListingBudgetFraction`](/en/settings#available-settings) setting (e.g. `0.02` = 2%) or the `SLASH_COMMAND_TOOL_CHAR_BUDGET` environment variable to a fixed character count. To free budget for other skills, set low-priority entries to `"name-only"` in [`skillOverrides`](#override-skill-visibility-from-settings) so they list without a description. You can also trim the `description` and `when_to_use` text at the source: put the key use case first, since each entry's combined text is capped at 1,536 characters regardless of budget. The cap is configurable with [`skillListingMaxDescChars`](/en/settings#available-settings).

## Related resources

* **[Debug your configuration](/en/debug-your-config)**: diagnose why a skill isn't appearing or triggering
* **[Evaluating skill output quality](https://agentskills.io/skill-creation/evaluating-skills)**: the eval file format and iteration workflow on agentskills.io
* **[Skill authoring best practices](https://platform.claude.com/docs/en/agents-and-tools/agent-skills/best-practices)**: writing guidance that applies across Claude products
* **[Subagents](/en/sub-agents)**: delegate tasks to specialized agents
* **[Plugins](/en/plugins)**: package and distribute skills with other extensions
* **[Hooks](/en/hooks)**: automate workflows around tool events
* **[Memory](/en/memory)**: manage CLAUDE.md files for persistent context
* **[Commands](/en/commands)**: reference for built-in commands and bundled skills
* **[Permissions](/en/permissions)**: control tool and skill access
* **[Claude Tag skills](https://claude.com/docs/claude-tag/admins/skills-repo)**: project skills committed to a repo also load when that repo is used in a Claude Tag channel


================================================================================
# PAGE: sub-agents
# source: https://docs.claude.com/en/docs/claude-code/sub-agents.md
================================================================================

> ## Documentation Index
> Fetch the complete documentation index at: https://code.claude.com/docs/llms.txt
> Use this file to discover all available pages before exploring further.

# Create custom subagents

> Create and use specialized AI subagents in Claude Code for task-specific workflows and improved context management.

Subagents are specialized AI assistants that handle specific types of tasks. Use one when a side task would flood your main conversation with search results, logs, or file contents you won't reference again: the subagent does that work in its own context and returns only the summary. Define a custom subagent when you keep spawning the same kind of worker with the same instructions.

Each subagent runs in its own context window with a custom system prompt, specific tool access, and independent permissions. When Claude encounters a task that matches a subagent's description, it delegates to that subagent, which works independently and returns results. To see the context savings in practice, the [context window visualization](/en/context-window) walks through a session where a subagent handles research in its own separate window.

<Note>
  Subagents work within a single session. To run many independent sessions in parallel and monitor them from one place, see [background agents](/en/agent-view). For sessions that communicate with each other, see [agent teams](/en/agent-teams).
</Note>

Subagents help you:

* **Preserve context** by keeping exploration and implementation out of your main conversation
* **Enforce constraints** by limiting which tools a subagent can use
* **Reuse configurations** across projects with user-level subagents
* **Specialize behavior** with focused system prompts for specific domains
* **Control costs** by routing tasks to faster, cheaper models like Haiku

Claude uses each subagent's description to decide when to delegate tasks. When you create a subagent, write a clear description so Claude knows when to use it.

Claude Code includes several built-in subagents like **Explore**, **Plan**, and **general-purpose**. You can also create custom subagents to handle specific tasks.

## Built-in subagents

Claude Code includes built-in subagents that Claude automatically uses when appropriate. Each inherits the parent conversation's permissions with additional tool restrictions.

Explore and Plan skip your CLAUDE.md files and the parent session's git status to keep research fast and inexpensive. Every other built-in and [custom subagent](#configure-subagents) loads both. For the full breakdown of what reaches a subagent, see [what loads at startup](#what-loads-at-startup).

<Tabs>
  <Tab title="Explore">
    A fast, read-only agent optimized for searching and analyzing codebases.

    * **Model**: Haiku, which is fast and low-latency
    * **Tools**: read-only tools; Write and Edit are denied
    * **Purpose**: file discovery, code search, codebase exploration

    Claude delegates to Explore when it needs to search or understand a codebase without making changes. This keeps exploration results out of your main conversation context.

    When invoking Explore, Claude specifies a thoroughness level: **quick** for targeted lookups, **medium** for balanced exploration, or **very thorough** for comprehensive analysis.
  </Tab>

  <Tab title="Plan">
    A research agent used during [plan mode](/en/permission-modes#analyze-before-you-edit-with-plan-mode) to gather context before presenting a plan.

    * **Model**: inherits from the main conversation
    * **Tools**: read-only tools; Write and Edit are denied
    * **Purpose**: codebase research for planning

    When you're in plan mode and Claude needs to understand your codebase, it delegates research to the Plan subagent so that exploration output stays in a separate context window while the main conversation remains read-only.
  </Tab>

  <Tab title="General-purpose">
    A capable agent for complex, multi-step tasks that require both exploration and action.

    * **Model**: inherits from the main conversation
    * **Tools**: all tools
    * **Purpose**: complex research, multi-step operations, code modifications

    Claude delegates to general-purpose when the task requires both exploration and modification, complex reasoning to interpret results, or multiple dependent steps.
  </Tab>

  <Tab title="Other">
    Claude Code includes additional helper agents for specific tasks. These are typically invoked automatically, so you don't need to use them directly.

    | Agent             | Model  | When Claude uses it                                      |
    | :---------------- | :----- | :------------------------------------------------------- |
    | statusline-setup  | Sonnet | When you run `/statusline` to configure your status line |
    | claude-code-guide | Haiku  | When you ask questions about Claude Code features        |
  </Tab>
</Tabs>

Built-in subagents are always registered in interactive sessions. To restrict them:

* To block a specific built-in type, add it to `permissions.deny` as shown in [Disable specific subagents](#disable-specific-subagents).
* To prevent Claude from delegating to any subagent, deny the `Agent` tool itself with [`permissions.deny`](/en/permissions#tool-specific-permission-rules).
* In [non-interactive mode](/en/headless) and the [Agent SDK](/en/agent-sdk/overview), set [`CLAUDE_AGENT_SDK_DISABLE_BUILTIN_AGENTS=1`](/en/env-vars) to remove all built-in types and supply only your own.

Beyond these built-in subagents, you can create your own with custom prompts, tool restrictions, permission modes, hooks, and skills. The following sections show how to get started and customize subagents.

## Quickstart: create your first subagent

Subagents are defined in Markdown files with YAML frontmatter. You can [create them manually](#write-subagent-files) or use the `/agents` command.

This walkthrough guides you through creating a user-level subagent with the `/agents` command. The subagent reviews code and suggests improvements for the codebase.

<Steps>
  <Step title="Open the subagents interface">
    In Claude Code, run:

    ```text wrap theme={null}
    /agents
    ```
  </Step>

  <Step title="Choose a location">
    Switch to the **Library** tab, select **Create new agent**, then choose **Personal**. This saves the subagent to `~/.claude/agents/` so it's available in all your projects.
  </Step>

  <Step title="Generate with Claude">
    Select **Generate with Claude**. When prompted, describe the subagent:

    ```text wrap theme={null}
    A code improvement agent that scans files and suggests improvements
    for readability, performance, and best practices. It should explain
    each issue, show the current code, and provide an improved version.
    ```

    Claude generates the identifier, description, and system prompt for you.
  </Step>

  <Step title="Select tools">
    For a read-only reviewer, deselect everything except **Read-only tools**. If you keep all tools selected, the subagent inherits all tools available to the main conversation.
  </Step>

  <Step title="Select model">
    Choose which model the subagent uses. For this example agent, select **Sonnet**, which balances capability and speed for analyzing code patterns.
  </Step>

  <Step title="Choose a color">
    Pick a background color for the subagent. This helps you identify which subagent is running in the UI.
  </Step>

  <Step title="Configure memory">
    Select **User scope** to give the subagent a [persistent memory directory](#enable-persistent-memory) at `~/.claude/agent-memory/`. The subagent uses this to accumulate insights across conversations, such as codebase patterns and recurring issues. Select **None** if you don't want the subagent to persist learnings.
  </Step>

  <Step title="Save and try it out">
    Review the configuration summary. Press `s` or `Enter` to save, or press `e` to save and edit the file in your editor. The subagent is available immediately. Try it:

    ```text wrap theme={null}
    Use the code-improver agent to suggest improvements in this project
    ```

    Claude delegates to your new subagent, which scans the codebase and returns improvement suggestions.
  </Step>
</Steps>

You now have a subagent you can use in any project on your machine to analyze codebases and suggest improvements.

You can also create subagents manually as Markdown files, define them via CLI flags, or distribute them through plugins. The following sections cover all configuration options.

## Configure subagents

### Use the /agents command

The `/agents` command opens a tabbed interface for managing subagents. The **Running** tab lists live and recently finished subagents and lets you open or stop them. The **Library** tab lets you:

* View all available subagents (built-in, user, project, and plugin)
* Create new subagents with guided setup or Claude generation
* Edit existing subagent configuration and tool access
* Delete custom subagents
* See which subagents are active when duplicates exist

This is the recommended way to create and manage subagents. For manual creation or automation, you can also add subagent files directly.

### Choose the subagent scope

Subagents are Markdown files with YAML frontmatter. Store them in different locations depending on scope. When multiple subagents share the same name, Claude Code uses the one from the higher-priority location.

| Location                     | Scope                   | Priority    | How to create                                 |
| :--------------------------- | :---------------------- | :---------- | :-------------------------------------------- |
| Managed settings             | Organization-wide       | 1 (highest) | Deployed via [managed settings](/en/settings) |
| `--agents` CLI flag          | Current session         | 2           | Pass JSON when launching Claude Code          |
| `.claude/agents/`            | Current project         | 3           | Interactive or manual                         |
| `~/.claude/agents/`          | All your projects       | 4           | Interactive or manual                         |
| Plugin's `agents/` directory | Where plugin is enabled | 5 (lowest)  | Installed with [plugins](/en/plugins)         |

**Project subagents** (`.claude/agents/`) are ideal for subagents specific to a codebase. Check them into version control so your team can use and improve them collaboratively.

Project subagents are discovered by walking up from the current working directory, so every `.claude/agents/` between there and the repository root is scanned. {/* min-version: 2.1.178 */}As of v2.1.178, when more than one of these nested directories defines the same `name`, Claude Code uses the definition closest to the working directory.

Directories added with `--add-dir` are also scanned: a `.claude/agents/` folder inside an added directory loads alongside project subagents. See [Additional directories](/en/permissions#additional-directories-grant-file-access-not-configuration) for which other configuration types load from `--add-dir`. To share subagents across projects without `--add-dir`, use `~/.claude/agents/` or a [plugin](/en/plugins).

**User subagents** (`~/.claude/agents/`) are personal subagents available in all your projects.

Claude Code scans `.claude/agents/` and `~/.claude/agents/` recursively, so you can organize definitions into subfolders such as `agents/review/` or `agents/research/`. The subdirectory path doesn't affect how a subagent is identified or invoked, because identity comes only from the `name` frontmatter field.

Keep `name` values unique across the whole tree: if two files within one scope declare the same name, Claude Code loads only one of them. {/* min-version: 2.1.196 */}As of v2.1.196, running `/doctor` reports same-scope duplicate agent names and shows which definition is active.

Plugin `agents/` directories are also scanned recursively. Unlike project and user scopes, a subfolder inside a plugin's `agents/` directory becomes part of the [scoped identifier](#invoke-subagents-explicitly): a file at `agents/review/security.md` in plugin `my-plugin` registers as `my-plugin:review:security`.

**CLI-defined subagents** are passed as JSON when launching Claude Code. They exist only for that session and aren't saved to disk, making them useful for quick testing or automation scripts. You can define multiple subagents in a single `--agents` call:

<Tabs>
  <Tab title="macOS, Linux, WSL">
    ```bash theme={null}
    claude --agents '{
      "code-reviewer": {
        "description": "Expert code reviewer. Use proactively after code changes.",
        "prompt": "You are a senior code reviewer. Focus on code quality, security, and best practices.",
        "tools": ["Read", "Grep", "Glob", "Bash"],
        "model": "sonnet"
      },
      "debugger": {
        "description": "Debugging specialist for errors and test failures.",
        "prompt": "You are an expert debugger. Analyze errors, identify root causes, and provide fixes."
      }
    }'
    ```
  </Tab>

  <Tab title="Windows PowerShell">
    ```powershell theme={null}
    claude --agents @'
    {
      "code-reviewer": {
        "description": "Expert code reviewer. Use proactively after code changes.",
        "prompt": "You are a senior code reviewer. Focus on code quality, security, and best practices.",
        "tools": ["Read", "Grep", "Glob", "Bash"],
        "model": "sonnet"
      },
      "debugger": {
        "description": "Debugging specialist for errors and test failures.",
        "prompt": "You are an expert debugger. Analyze errors, identify root causes, and provide fixes."
      }
    }
    '@
    ```
  </Tab>
</Tabs>

The `--agents` flag accepts JSON with the same [frontmatter](#supported-frontmatter-fields) fields as file-based subagents: `description`, `prompt`, `tools`, `disallowedTools`, `model`, `permissionMode`, `mcpServers`, `hooks`, `maxTurns`, `skills`, `initialPrompt`, `memory`, `effort`, `background`, `isolation`, and `color`. Use `prompt` for the system prompt, equivalent to the markdown body in file-based subagents.

**Managed subagents** are deployed by organization administrators. Place markdown files in `.claude/agents/` inside the [managed settings directory](/en/settings#settings-files), using the same frontmatter format as project and user subagents. Managed definitions take precedence over project and user subagents with the same name.

**Plugin subagents** come from [plugins](/en/plugins) you've installed. They appear in `/agents` alongside your custom subagents. See the [plugin components reference](/en/plugins-reference#agents) for details on creating plugin subagents.

<Note>
  For security reasons, plugin subagents don't support the `hooks`, `mcpServers`, or `permissionMode` frontmatter fields. These fields are ignored when loading agents from a plugin. If you need them, copy the agent file into `.claude/agents/` or `~/.claude/agents/`. You can also add rules to [`permissions.allow`](/en/settings#permission-settings) in `settings.json` or `settings.local.json`, but these rules apply to the entire session, not only the plugin subagent.
</Note>

Subagent definitions from any of these scopes are also available to [agent teams](/en/agent-teams#use-subagent-definitions-for-teammates): when spawning a teammate, you can reference a subagent type and the teammate uses its `tools` and `model`, with the definition's body appended to the teammate's system prompt as additional instructions. See [agent teams](/en/agent-teams#use-subagent-definitions-for-teammates) for which frontmatter fields apply on that path.

### Write subagent files

Subagent files use YAML frontmatter for configuration, followed by the system prompt in Markdown:

<Note>
  Subagents are loaded at session start. If you add or edit a subagent file directly on disk, restart your session to load it. Subagents created through the `/agents` interface take effect immediately without a restart.
</Note>

```markdown theme={null}
---
name: code-reviewer
description: Reviews code for quality and best practices
tools: Read, Glob, Grep
model: sonnet
---

You are a code reviewer. When invoked, analyze the code and provide
specific, actionable feedback on quality, security, and best practices.
```

The frontmatter defines the subagent's metadata and configuration. The body becomes the system prompt that guides the subagent's behavior. Subagents receive only this system prompt plus basic environment details like the working directory, not the full Claude Code system prompt.

A subagent starts in the main conversation's current working directory. Within a subagent, `cd` commands don't persist between Bash or PowerShell tool calls and don't affect the main conversation's working directory. To give the subagent an isolated copy of the repository instead, set [`isolation: worktree`](#supported-frontmatter-fields).

#### Supported frontmatter fields

The following fields can be used in the YAML frontmatter. Only `name` and `description` are required.

| Field             | Required | Description                                                                                                                                                                                                                                                                                                                              |
| :---------------- | :------- | :--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `name`            | Yes      | Unique identifier using lowercase letters and hyphens. [Hooks](/en/hooks#subagentstart) receive this value as `agent_type`. The filename doesn't have to match                                                                                                                                                                           |
| `description`     | Yes      | When Claude should delegate to this subagent                                                                                                                                                                                                                                                                                             |
| `tools`           | No       | [Tools](#available-tools) the subagent can use. Inherits all tools if omitted. To preload Skills into context, use the `skills` field rather than listing `Skill` here                                                                                                                                                                   |
| `disallowedTools` | No       | Tools to deny, removed from inherited or specified list                                                                                                                                                                                                                                                                                  |
| `model`           | No       | [Model](#choose-a-model) to use: `sonnet`, `opus`, `haiku`, `fable`, a full model ID (for example, `claude-opus-4-8`), or `inherit`. Defaults to `inherit`                                                                                                                                                                               |
| `permissionMode`  | No       | [Permission mode](#permission-modes): `default`, `acceptEdits`, `auto`, `dontAsk`, `bypassPermissions`, or `plan`. Ignored for [plugin subagents](#choose-the-subagent-scope)                                                                                                                                                            |
| `maxTurns`        | No       | Maximum number of agentic turns before the subagent stops                                                                                                                                                                                                                                                                                |
| `skills`          | No       | [Skills](/en/skills) to preload into the subagent's context at startup. The full skill content is injected, not only the description. Subagents can still invoke unlisted project, user, and plugin skills through the Skill tool                                                                                                        |
| `mcpServers`      | No       | [MCP servers](/en/mcp) available to this subagent. Each entry is either a server name referencing an already-configured server (e.g., `"slack"`) or an inline definition with the server name as key and a full [MCP server config](/en/mcp#installing-mcp-servers) as value. Ignored for [plugin subagents](#choose-the-subagent-scope) |
| `hooks`           | No       | [Lifecycle hooks](#define-hooks-for-subagents) scoped to this subagent. Ignored for [plugin subagents](#choose-the-subagent-scope)                                                                                                                                                                                                       |
| `memory`          | No       | [Persistent memory scope](#enable-persistent-memory): `user`, `project`, or `local`. Enables cross-session learning                                                                                                                                                                                                                      |
| `background`      | No       | Set to `true` to always run this subagent as a [background task](#run-subagents-in-foreground-or-background). Default: `false`                                                                                                                                                                                                           |
| `effort`          | No       | Effort level when this subagent is active. Overrides the session effort level. Default: inherits from session. Options: `low`, `medium`, `high`, `xhigh`, `max`; available levels depend on the model                                                                                                                                    |
| `isolation`       | No       | Set to `worktree` to run the subagent in a temporary [git worktree](/en/worktrees), giving it an isolated copy of the repository branched by default from your [default branch](/en/worktrees#choose-the-base-branch) rather than the parent session's `HEAD`. The worktree is automatically cleaned up if the subagent makes no changes |
| `color`           | No       | Display color for the subagent in the task list and transcript. Accepts `red`, `blue`, `green`, `yellow`, `purple`, `orange`, `pink`, or `cyan`                                                                                                                                                                                          |
| `initialPrompt`   | No       | Auto-submitted as the first user turn when this agent runs as the main session agent (via `--agent` or the `agent` setting). [Commands](/en/commands) and [skills](/en/skills) are processed. Prepended to any user-provided prompt                                                                                                      |

### Choose a model

The `model` field controls which [AI model](/en/model-config) the subagent uses:

* **Model alias**: use one of the available aliases: `sonnet`, `opus`, `haiku`, or `fable`
* **Full model ID**: use a full model ID such as `claude-opus-4-8` or `claude-sonnet-5`. Accepts the same values as the `--model` flag
* **inherit**: use the same model as the main conversation
* **Omitted**: defaults to `inherit` and uses the same model as the main conversation

When Claude invokes a subagent, it can also pass a `model` parameter for that specific invocation. Claude Code resolves the subagent's model in this order:

1. The [`CLAUDE_CODE_SUBAGENT_MODEL`](/en/model-config#environment-variables) environment variable, when set to a model alias or model ID
2. The per-invocation `model` parameter
3. The subagent definition's `model` frontmatter
4. The main conversation's model

{/* min-version: 2.1.196 */}As of v2.1.196, setting `CLAUDE_CODE_SUBAGENT_MODEL` to `inherit` is the same as leaving it unset: resolution continues with the per-invocation `model` parameter, then the frontmatter. In earlier versions, `inherit` forced subagents onto the main conversation's model and ignored both of those sources.

The environment variable, per-invocation parameter, and frontmatter values are checked against your organization's [`availableModels`](/en/model-config#restrict-model-selection) allowlist. A value that resolves to an excluded model is not used and the subagent runs on the inherited model instead.

### Control subagent capabilities

You can control what subagents can do through tool access, permission modes, and conditional rules.

#### Available tools

Subagents inherit the [internal tools](/en/tools-reference) and MCP tools available in the main conversation by default. The following tools depend on the main conversation's UI or session state and are not available to subagents, even when listed in the `tools` field:

* `AskUserQuestion`
* `EnterPlanMode`
* `ExitPlanMode`, unless the subagent's [`permissionMode`](#permission-modes) is `plan`
* `ScheduleWakeup`
* `WaitForMcpServers`

To restrict tools, use either the `tools` field (allowlist) or the `disallowedTools` field (denylist). This example uses `tools` to exclusively allow Read, Grep, Glob, and Bash. The subagent can't edit files, write files, or use any MCP tools:

```yaml theme={null}
---
name: safe-researcher
description: Research agent with restricted capabilities
tools: Read, Grep, Glob, Bash
---
```

This example uses `disallowedTools` to inherit every tool from the main conversation except Write and Edit. The subagent keeps Bash, MCP tools, and everything else:

```yaml theme={null}
---
name: no-writes
description: Inherits every tool except file writes
disallowedTools: Write, Edit
---
```

If both are set, `disallowedTools` is applied first, then `tools` is resolved against the remaining pool. A tool listed in both is removed.

Both fields accept MCP server-level patterns in addition to exact tool names: `mcp__<server>` or `mcp__<server>__*` grants or removes every tool from the named server. In `disallowedTools`, `mcp__*` also removes every MCP tool from any server. This example removes every tool from the `github` MCP server while keeping tools from other servers and every built-in tool:

```yaml theme={null}
---
name: local-only
description: Inherits every tool except those from the github MCP server
disallowedTools: mcp__github
---
```

#### Restrict which subagents can be spawned

When an agent runs as the main thread with `claude --agent`, it can spawn subagents using the Agent tool. To restrict which subagent types it can spawn, use `Agent(agent_type)` syntax in the `tools` field.

<Note>In version 2.1.63, the Task tool was renamed to Agent. Existing `Task(...)` references in settings and agent definitions still work as aliases.</Note>

```yaml theme={null}
---
name: coordinator
description: Coordinates work across specialized agents
tools: Agent(worker, researcher), Read, Bash
---
```

This is an allowlist: only the `worker` and `researcher` subagents can be spawned. If the agent tries to spawn any other type, the request fails and the agent sees only the allowed types in its prompt. To block specific agents while allowing all others, use [`permissions.deny`](#disable-specific-subagents) instead.

To allow spawning any subagent without restrictions, use `Agent` without parentheses:

```yaml theme={null}
tools: Agent, Read, Bash
```

If `Agent` is omitted from the `tools` list entirely, the agent can't spawn any subagents.

The `Agent(agent_type)` allowlist syntax applies only to an agent running as the main thread with `claude --agent`. In a subagent definition, listing `Agent` in `tools` lets that subagent [spawn nested subagents](#spawn-nested-subagents), but any type list inside the parentheses is ignored.

#### Scope MCP servers to a subagent

Use the `mcpServers` field to give a subagent access to [MCP](/en/mcp) servers that aren't available in the main conversation. Inline servers defined here are connected when the subagent starts and disconnected when it finishes. String references share the parent session's connection.

<Note>
  The `mcpServers` field applies in both contexts where an agent file can run:

  * As a subagent, spawned through the Agent tool or an @-mention
  * As the main session, launched with [`--agent`](#invoke-subagents-explicitly) or the `agent` setting

  When the agent is the main session, inline server definitions connect at startup alongside servers from [`.mcp.json`](/en/mcp) and settings files.
</Note>

Each entry in the list is either an inline server definition or a string referencing an MCP server already configured in your session:

```yaml theme={null}
---
name: browser-tester
description: Tests features in a real browser using Playwright
mcpServers:
  # Inline definition: scoped to this subagent only
  - playwright:
      type: stdio
      command: npx
      args: ["-y", "@playwright/mcp@latest"]
  # Reference by name: reuses an already-configured server
  - github
---

Use the Playwright tools to navigate, screenshot, and interact with pages.
```

Inline definitions use the same schema as `.mcp.json` server entries (`stdio`, `http`, `sse`, `ws`), keyed by the server name.

To keep an MCP server out of the main conversation entirely and avoid its tool descriptions consuming context there, define it inline here rather than in `.mcp.json`. The subagent gets the tools; the parent conversation doesn't.

As of v2.1.153, the MCP restrictions that apply to the main session also cover servers declared in subagent frontmatter:

* [`--strict-mcp-config`](/en/cli-reference) and [`--bare`](/en/cli-reference)
* [Enterprise managed MCP configuration](/en/managed-mcp)
* [`allowedMcpServers` and `deniedMcpServers` policies](/en/managed-mcp#policy-based-control-with-allowlists-and-denylists)

When one of these blocks a server, Claude Code skips it and shows a warning naming the blocked servers.

Managed-settings restrictions apply to every subagent regardless of how it is defined. `--strict-mcp-config` doesn't filter servers you pass inline via `--agents` or the SDK `agents` option, since those are explicit caller input.

#### Permission modes

The `permissionMode` field controls how the subagent handles permission prompts. Subagents inherit the permission context from the main conversation and can override the mode, except when the parent mode takes precedence as described below.

| Mode                | Behavior                                                                                                                                    |
| :------------------ | :------------------------------------------------------------------------------------------------------------------------------------------ |
| `default`           | Standard permission checking with prompts                                                                                                   |
| `acceptEdits`       | Auto-accept file edits and common filesystem commands for paths in the working directory or `additionalDirectories`                         |
| `auto`              | [Auto mode](/en/permission-modes#eliminate-prompts-with-auto-mode): a background classifier reviews commands and protected-directory writes |
| `dontAsk`           | Auto-deny permission prompts (explicitly allowed tools still work)                                                                          |
| `bypassPermissions` | Skip permission prompts                                                                                                                     |
| `plan`              | Plan mode (read-only exploration)                                                                                                           |

<Warning>
  Use `bypassPermissions` with caution. It skips permission prompts, allowing the subagent to execute operations without approval, including writes to `.git`, `.config/git`, `.claude`, `.vscode`, `.idea`, `.husky`, `.cargo`, `.devcontainer`, `.yarn`, and `.mvn`. Explicit [`ask` rules](/en/permissions#manage-permissions) and root and home directory removals such as `rm -rf /` still prompt. See [permission modes](/en/permission-modes#skip-all-checks-with-bypasspermissions-mode) for details.
</Warning>

If the parent uses `bypassPermissions` or `acceptEdits`, this takes precedence and can't be overridden. If the parent uses [auto mode](/en/permission-modes#eliminate-prompts-with-auto-mode), the subagent inherits auto mode and any `permissionMode` in its frontmatter is ignored: the classifier evaluates the subagent's tool calls with the same block and allow rules as the parent session.

#### Preload skills into subagents

Use the `skills` field to inject skill content into a subagent's context at startup. This gives the subagent domain knowledge without requiring it to discover and load skills during execution.

```yaml theme={null}
---
name: api-developer
description: Implement API endpoints following team conventions
skills:
  - api-conventions
  - error-handling-patterns
---

Implement API endpoints. Follow the conventions and patterns from the preloaded skills.
```

The full content of each listed skill is injected into the subagent's context at startup. This field controls which skills are preloaded, not which skills the subagent can access: without it, the subagent can still discover and invoke project, user, and plugin skills through the Skill tool during execution. To prevent a subagent from invoking skills entirely, omit `Skill` from the [`tools`](#available-tools) list or add it to `disallowedTools`.

You can't preload skills that set [`disable-model-invocation: true`](/en/skills#control-who-invokes-a-skill), since preloading draws from the same set of skills Claude can invoke. If a listed skill is missing or disabled, Claude Code skips it and logs a warning to the debug log.

<Note>
  This is the inverse of [running a skill in a subagent](/en/skills#run-skills-in-a-subagent). With `skills` in a subagent, the subagent controls the system prompt and loads skill content. With `context: fork` in a skill, the skill content is injected into the agent you specify. Both use the same underlying system.
</Note>

#### Enable persistent memory

The `memory` field gives the subagent a persistent directory that survives across conversations. The subagent uses this directory to build up knowledge over time, such as codebase patterns, debugging insights, and architectural decisions.

```yaml theme={null}
---
name: code-reviewer
description: Reviews code for quality and best practices
memory: user
---

You are a code reviewer. As you review code, update your agent memory with
patterns, conventions, and recurring issues you discover.
```

Choose a scope based on how broadly the memory should apply:

| Scope     | Location                                      | Use when                                                                                    |
| :-------- | :-------------------------------------------- | :------------------------------------------------------------------------------------------ |
| `user`    | `~/.claude/agent-memory/<name-of-agent>/`     | the subagent should remember learnings across all projects                                  |
| `project` | `.claude/agent-memory/<name-of-agent>/`       | the subagent's knowledge is project-specific and shareable via version control              |
| `local`   | `.claude/agent-memory-local/<name-of-agent>/` | the subagent's knowledge is project-specific but should not be checked into version control |

When memory is enabled:

* The subagent's system prompt includes instructions for reading and writing to the memory directory.
* The subagent's system prompt also includes the first 200 lines or 25KB of `MEMORY.md` in the memory directory, whichever comes first, with instructions to curate `MEMORY.md` if it exceeds that limit.
* Read, Write, and Edit tools are automatically enabled so the subagent can manage its memory files.

##### Persistent memory tips

* `project` is the recommended default scope. It makes subagent knowledge shareable via version control. Use `user` when the subagent's knowledge is broadly applicable across projects, or `local` when the knowledge should not be checked into version control.
* Ask the subagent to consult its memory before starting work: "Review this PR, and check your memory for patterns you've seen before."
* Ask the subagent to update its memory after completing a task: "Now that you're done, save what you learned to your memory." Over time, this builds a knowledge base that makes the subagent more effective.
* Include memory instructions directly in the subagent's markdown file so it proactively maintains its own knowledge base:

  ```markdown theme={null}
  Update your agent memory as you discover codepaths, patterns, library
  locations, and key architectural decisions. This builds up institutional
  knowledge across conversations. Write concise notes about what you found
  and where.
  ```

#### Conditional rules with hooks

For more dynamic control over tool usage, use `PreToolUse` hooks to validate operations before they execute. This is useful when you need to allow some operations of a tool while blocking others.

This example creates a subagent that only allows read-only database queries. The `PreToolUse` hook runs the script specified in `command` before each Bash command executes:

```yaml theme={null}
---
name: db-reader
description: Execute read-only database queries
tools: Bash
hooks:
  PreToolUse:
    - matcher: "Bash"
      hooks:
        - type: command
          command: "./scripts/validate-readonly-query.sh"
---
```

Claude Code [passes hook input as JSON](/en/hooks#pretooluse-input) via stdin to hook commands. The validation script reads this JSON, extracts the Bash command, and [exits with code 2](/en/hooks#exit-code-2-behavior-per-event) to block write operations:

```bash theme={null}
#!/bin/bash
# ./scripts/validate-readonly-query.sh

INPUT=$(cat)
COMMAND=$(echo "$INPUT" | jq -r '.tool_input.command // empty')

# Block SQL write operations (case-insensitive)
if echo "$COMMAND" | grep -iE '\b(INSERT|UPDATE|DELETE|DROP|CREATE|ALTER|TRUNCATE)\b' > /dev/null; then
  echo "Blocked: Only SELECT queries are allowed" >&2
  exit 2
fi

exit 0
```

See [Hook input](/en/hooks#pretooluse-input) for the complete input schema and [exit codes](/en/hooks#exit-code-output) for how exit codes affect behavior. On Windows, write hook scripts in PowerShell and add `shell: powershell` to the hook entry as shown in [running hooks in PowerShell](/en/hooks#windows-powershell-tool).

#### Disable specific subagents

You can prevent Claude from using specific subagents by adding them to the `deny` array in your [settings](/en/settings#permission-settings). Use the format `Agent(subagent-name)` where `subagent-name` matches the subagent's name field.

```json theme={null}
{
  "permissions": {
    "deny": ["Agent(Explore)", "Agent(my-custom-agent)"]
  }
}
```

This works for both built-in and custom subagents. You can also use the `--disallowedTools` CLI flag:

```bash theme={null}
claude --disallowedTools "Agent(Explore)"
```

See [Permissions documentation](/en/permissions#tool-specific-permission-rules) for more details on permission rules.

### Define hooks for subagents

Subagents can define [hooks](/en/hooks) that run during the subagent's lifecycle. There are two ways to configure hooks:

* **In the subagent's frontmatter**: define hooks that run only while that subagent is active
* **In `settings.json`**: define hooks that run in the main session when subagents start or stop

#### Hooks in subagent frontmatter

Define hooks directly in the subagent's markdown file. These hooks only run while that specific subagent is active and are cleaned up when it finishes.

<Note>
  Frontmatter hooks fire when the agent is spawned as a subagent through the Agent tool or an @-mention, and when the agent runs as the main session via [`--agent`](#invoke-subagents-explicitly) or the `agent` setting. In the main-session case they run alongside any hooks defined in [`settings.json`](/en/hooks).
</Note>

All [hook events](/en/hooks#hook-events) are supported. The most common events for subagents are:

| Event         | Matcher input | When it fires                                                       |
| :------------ | :------------ | :------------------------------------------------------------------ |
| `PreToolUse`  | Tool name     | Before the subagent uses a tool                                     |
| `PostToolUse` | Tool name     | After the subagent uses a tool                                      |
| `Stop`        | (none)        | When the subagent finishes (converted to `SubagentStop` at runtime) |

This example validates Bash commands with the `PreToolUse` hook and runs a linter after file edits with `PostToolUse`:

```yaml theme={null}
---
name: code-reviewer
description: Review code changes with automatic linting
hooks:
  PreToolUse:
    - matcher: "Bash"
      hooks:
        - type: command
          command: "./scripts/validate-command.sh $TOOL_INPUT"
  PostToolUse:
    - matcher: "Edit|Write"
      hooks:
        - type: command
          command: "./scripts/run-linter.sh"
---
```

When the agent is invoked as a subagent, `Stop` hooks in frontmatter are automatically converted to `SubagentStop` events.

#### Project-level hooks for subagent events

Configure hooks in `settings.json` that respond to subagent lifecycle events in the main session.

| Event           | Matcher input   | When it fires                    |
| :-------------- | :-------------- | :------------------------------- |
| `SubagentStart` | Agent type name | When a subagent begins execution |
| `SubagentStop`  | Agent type name | When a subagent completes        |

Both events support matchers to target specific agent types by name. The matcher value is the agent's frontmatter `name` for project-level and user-level subagents, or the plugin-scoped identifier such as `my-plugin:db-agent` for [plugin subagents](/en/plugins). A scoped name contains a colon, so it is evaluated as an [unanchored regular expression](/en/hooks#matcher-patterns); anchor it with `^` and `$`, as in `^my-plugin:db-agent$`, to match only that agent.

This example runs a setup script only when the `db-agent` subagent starts, and a cleanup script when any subagent stops:

```json theme={null}
{
  "hooks": {
    "SubagentStart": [
      {
        "matcher": "db-agent",
        "hooks": [
          { "type": "command", "command": "./scripts/setup-db-connection.sh" }
        ]
      }
    ],
    "SubagentStop": [
      {
        "hooks": [
          { "type": "command", "command": "./scripts/cleanup-db-connection.sh" }
        ]
      }
    ]
  }
}
```

A hyphenated matcher like `db-agent` matches exactly on Claude Code v2.1.195 or later. On earlier versions it is evaluated as an unanchored regular expression and also fires for any agent type that contains it, such as `prod-db-agent`; anchor it as `^db-agent$` on those versions.

See [Hooks](/en/hooks) for the complete hook configuration format.

## Work with subagents

### Understand automatic delegation

Claude automatically delegates tasks based on the task description in your request, the `description` field in subagent configurations, and current context. To encourage proactive delegation, include phrases like "use proactively" in your subagent's description field.

### Invoke subagents explicitly

When automatic delegation isn't enough, you can request a subagent yourself. Three patterns escalate from a one-off suggestion to a session-wide default:

* **Natural language**: name the subagent in your prompt; Claude decides whether to delegate
* **@-mention**: guarantees the subagent runs for one task
* **Session-wide**: the whole session uses that subagent's system prompt, tool restrictions, and model via the `--agent` flag or the `agent` setting

For natural language, there's no special syntax. Name the subagent and Claude typically delegates:

```text wrap theme={null}
Use the test-runner subagent to fix failing tests
Have the code-reviewer subagent look at my recent changes
```

**@-mention the subagent.** Type `@` and pick the subagent from the typeahead, the same way you @-mention files. This ensures that specific subagent runs rather than leaving the choice to Claude:

```text wrap theme={null}
@"code-reviewer (agent)" look at the auth changes
```

Your full message still goes to Claude, which writes the subagent's task prompt based on what you asked. The @-mention controls which subagent Claude invokes, not what prompt it receives.

Subagents provided by an enabled [plugin](/en/plugins) appear in the typeahead under their scoped name, such as `my-plugin:code-reviewer` or `my-plugin:review:security` when the plugin [organizes agents into subfolders](#choose-the-subagent-scope). Named background subagents currently running in the session also appear in the typeahead, showing their status next to the name.

You can also type the mention manually without using the picker: `@agent-<name>` for local subagents, or `@agent-` followed by the scoped name for plugin subagents, for example `@agent-my-plugin:code-reviewer`.

**Run the whole session as a subagent.** Pass [`--agent <name>`](/en/cli-reference) to start a session where the main thread itself takes on that subagent's system prompt, tool restrictions, and model:

```bash theme={null}
claude --agent code-reviewer
```

The subagent's system prompt replaces the default Claude Code system prompt entirely, the same way [`--system-prompt`](/en/cli-reference) does. `CLAUDE.md` files and project memory still load through the normal message flow. The agent name appears as `@<name>` in the startup header so you can confirm it's active.

This works with built-in and custom subagents, and the choice persists when you resume the session.

For a plugin-provided subagent, you can pass only the agent name and Claude Code finds it:

```bash theme={null}
claude --agent security-reviewer
```

If multiple plugins provide agents with the same name, pass the scoped name to disambiguate:

```bash theme={null}
claude --agent my-plugin:security-reviewer
```

If the plugin places the agent in a subfolder of its `agents/` directory, include the subfolder in the scoped name, for example `claude --agent my-plugin:review:security`.

To make it the default for every session in a project, set `agent` in `.claude/settings.json`:

```json theme={null}
{
  "agent": "code-reviewer"
}
```

The CLI flag overrides the setting if both are present.

### Run subagents in foreground or background

Subagents can run in the foreground or the background:

* **Foreground subagents** block the main conversation until complete. Permission prompts are passed through to you as they come up.
* **Background subagents** run concurrently while you continue working. {/* min-version: 2.1.186 */}As of v2.1.186, when a background subagent reaches a tool call that needs permission, the prompt surfaces in your main session and names the subagent that is asking. Approve to let the subagent continue, or press Esc to deny that one tool call without stopping the subagent. Before v2.1.186, background subagents auto-denied any tool call that would have prompted.

Claude decides whether to run subagents in the foreground or background based on the task. You can also:

* Ask Claude to "run this in the background"
* Press **Ctrl+B** to background a running task

To disable all background task functionality, set the `CLAUDE_CODE_DISABLE_BACKGROUND_TASKS` environment variable to `1`. See [Environment variables](/en/env-vars).

When [`CLAUDE_CODE_FORK_SUBAGENT`](#fork-the-current-conversation) is set to `1`, every subagent spawn runs in the background regardless of the `background` field. Permission prompts from these background subagents surface in your main session as described above.

### Common patterns

#### Isolate high-volume operations

One of the most effective uses for subagents is isolating operations that produce large amounts of output. Running tests, fetching documentation, or processing log files can consume significant context. By delegating these to a subagent, the verbose output stays in the subagent's context while only the relevant summary returns to your main conversation.

```text wrap theme={null}
Use a subagent to run the test suite and report only the failing tests with their error messages
```

#### Run parallel research

For independent investigations, spawn multiple subagents to work simultaneously:

```text wrap theme={null}
Research the authentication, database, and API modules in parallel using separate subagents
```

Each subagent explores its area independently, then Claude synthesizes the findings. This works best when the research paths don't depend on each other.

<Warning>
  When subagents complete, their results return to your main conversation. Running many subagents that each return detailed results can consume significant context.
</Warning>

For tasks that need sustained parallelism or exceed your context window, [agent teams](/en/agent-teams) give each worker its own independent context.

#### Chain subagents

For multi-step workflows, ask Claude to use subagents in sequence. Each subagent completes its task and returns results to Claude, which then passes relevant context to the next subagent.

```text wrap theme={null}
Use the code-reviewer subagent to find performance issues, then use the optimizer subagent to fix them
```

### Choose between subagents and main conversation

Use the **main conversation** when:

* The task needs frequent back-and-forth or iterative refinement
* Multiple phases share significant context, such as planning, implementation, and testing
* You're making a quick, targeted change
* Latency matters. Subagents start fresh and may need time to gather context

Use **subagents** when:

* The task produces verbose output you don't need in your main context
* You want to enforce specific tool restrictions or permissions
* The work is self-contained and can return a summary

Consider [Skills](/en/skills) instead when you want reusable prompts or workflows that run in the main conversation context rather than isolated subagent context.

For a quick question about something already in your conversation, use [`/btw`](/en/interactive-mode#side-questions-with-%2Fbtw) instead of a subagent. It sees your full context but has no tool access, and the answer is discarded rather than added to history.

### Spawn nested subagents

{/* min-version: 2.1.172 */}As of Claude Code v2.1.172, a subagent can spawn its own subagents. Use this when a delegated task itself splits into parallel subtasks, such as a reviewer subagent that dispatches a verifier per finding, so the intermediate output never reaches your main conversation. Only the top-level subagent's summary returns to you.

A nested subagent is configured the same way as a top-level one and resolves from the same [scopes](#choose-the-subagent-scope).

The subagent panel below the prompt input shows the full tree: each row displays a `(+N)` count of descendants, and {/* min-version: 2.1.193 */}as of v2.1.193, opening a row shows that subagent's siblings and direct children with a path back to `main`. The Running tab in [`/agents`](#use-the-%2Fagents-command) lists running subagents as a flat list.

Depth is counted as the number of subagent levels below the main conversation, regardless of whether each level runs in the [foreground or background](#run-subagents-in-foreground-or-background). A subagent at depth five doesn't receive the Agent tool and can't spawn further. The limit is fixed and not configurable.

As of Claude Code v2.1.187, a background subagent's depth is fixed when it is first spawned, and [resuming](#resume-subagents) it later doesn't change that depth. For example, if your main conversation spawns subagent A, and A spawns a background subagent B at depth two, B is still at depth two when you resume it directly from the main conversation. Resuming a subagent from a shallower context doesn't let it spawn additional levels that the depth limit already prevented.

To prevent a specific subagent from spawning others, omit `Agent` from its [`tools`](#available-tools) list or add it to `disallowedTools`.

A [fork](#fork-the-current-conversation) still can't spawn another fork. It can spawn other subagent types, and those count toward the depth limit.

### Manage subagent context

#### What loads at startup

Each subagent starts with a fresh, isolated context window. It doesn't see your conversation history, the skills you've already invoked, or the files Claude has already read. Claude composes a delegation message that summarizes the task, and the subagent works from there. The exception is a [fork](#fork-the-current-conversation), which inherits the parent conversation instead of starting fresh.

A non-fork subagent's initial context contains:

* **System prompt**: the agent's own prompt plus environment details that Claude Code appends, not the full Claude Code system prompt. Custom subagents define theirs in the [markdown body](#write-subagent-files) or `prompt` field. Built-in agents have predefined prompts.
* **Task message**: the delegation prompt Claude writes when it hands off the work.
* **CLAUDE.md and memory**: every level of the [memory hierarchy](/en/memory#how-claude-md-files-load) the main conversation loads, including `~/.claude/CLAUDE.md`, project rules, `CLAUDE.local.md`, and managed policy files. The built-in Explore and Plan agents skip this.
* **Git status**: a snapshot taken at the start of the parent session. Absent when the working directory isn't a Git repository or when [`includeGitInstructions`](/en/settings#available-settings) is `false`. Explore and Plan skip it regardless.
* **Preloaded skills**: full content of any skill named in the agent's [`skills` field](#preload-skills-into-subagents). Built-in agents don't preload skills.

Explore and Plan are the only subagents that omit CLAUDE.md and git status. There is no frontmatter field or per-agent setting to change which agents skip them.

The main conversation reads Explore and Plan results with full CLAUDE.md context, so most rules don't need to reach the subagent itself. If a rule must, such as "ignore the `vendor/` directory," restate it in the prompt you give Claude when delegating.

#### Resume subagents

Each subagent invocation creates a new instance with fresh context. To continue an existing subagent's work instead of starting over, ask Claude to resume it.

Resumed subagents retain their full conversation history, including all previous tool calls, results, and reasoning. The subagent picks up exactly where it stopped rather than starting fresh.

When a subagent completes, Claude receives its agent ID. The built-in Explore and Plan agents are one-shot and return no agent ID, so they can't be resumed; use `general-purpose` or a custom subagent when you need to continue the work.

Claude uses the `SendMessage` tool with the agent's ID as the `to` field to resume it. The `SendMessage` tool is always available for resuming subagents by agent ID or name. Structured team-protocol messages such as `shutdown_request` and `plan_approval_response` require [agent teams](/en/agent-teams) to be enabled.

To resume a subagent, ask Claude to continue the previous work:

```text wrap theme={null}
Use the code-reviewer subagent to review the authentication module
[Agent completes]

Continue that code review and now analyze the authorization logic
[Claude resumes the subagent with full context from previous conversation]
```

If a stopped subagent receives a `SendMessage`, it auto-resumes in the background without requiring a new `Agent` invocation.

You can also ask Claude for the agent ID if you want to reference it explicitly, or find IDs in the transcript files at `~/.claude/projects/{project}/{sessionId}/subagents/`. Each transcript is stored as `agent-{agentId}.jsonl`.

Subagent transcripts persist independently of the main conversation:

* **Main conversation compaction**: when the main conversation compacts, subagent transcripts are unaffected. They're stored in separate files.
* **Session persistence**: subagent transcripts persist within their session. You can [resume a subagent](#resume-subagents) after restarting Claude Code by resuming the same session.
* **Automatic cleanup**: transcripts are cleaned up based on the `cleanupPeriodDays` setting, which defaults to 30 days.

#### Auto-compaction

Subagents support automatic compaction using the same logic as the main conversation. Compaction triggers under the same conditions, and `CLAUDE_AUTOCOMPACT_PCT_OVERRIDE` applies to subagents as well. See [environment variables](/en/env-vars) for when the override takes effect.

Compaction events are logged in subagent transcript files:

```json theme={null}
{
  "type": "system",
  "subtype": "compact_boundary",
  "compactMetadata": {
    "trigger": "auto",
    "preTokens": 167189
  }
}
```

The `preTokens` value shows how many tokens were used before compaction occurred.

## Fork the current conversation

<Note>
  Forked subagents require Claude Code v2.1.117 or later. {/* min-version: 2.1.161 */}From v2.1.161 the `/fork` command is enabled by default; on earlier versions it requires setting the [`CLAUDE_CODE_FORK_SUBAGENT`](/en/env-vars) environment variable to `1`. Letting Claude itself spawn forks is experimental and may change in future releases. This capability may also be enabled in interactive sessions as part of a staged rollout.
</Note>

A fork is a subagent that inherits the entire conversation so far instead of starting fresh. This drops the input isolation that subagents otherwise provide: a fork sees the same system prompt, tools, model, and message history as the main session, so you can hand it a side task without re-explaining the situation. The fork's own tool calls still stay out of your conversation and only its final result comes back, so your main context window stays clean. Use a fork when a named subagent would need too much background to be useful, or when you want to try several approaches in parallel from the same starting point.

To control fork mode regardless of the staged rollout, set [`CLAUDE_CODE_FORK_SUBAGENT`](/en/env-vars) to `1` to enable it explicitly or to `0` to disable it. The variable is honored in interactive mode and via the SDK or `claude -p`.

Enabling fork mode changes Claude Code in two ways:

* Claude can spawn a fork by requesting the `fork` subagent type explicitly. Spawns without a subagent type still use the [general-purpose](#built-in-subagents) subagent, and named subagents such as Explore still spawn as before.
* Every subagent spawn runs in the [background](#run-subagents-in-foreground-or-background), whether it is a fork or a named subagent. Set `CLAUDE_CODE_DISABLE_BACKGROUND_TASKS` to `1` to keep spawns synchronous.

You can start a fork yourself with `/fork` followed by a directive, with or without the variable set. Claude Code names the fork from the first words of the directive. The following example forks the conversation to draft test cases while you continue with the implementation in the main session:

```text wrap theme={null}
/fork draft unit tests for the parser changes so far
```

The fork appears in a panel below your prompt and runs in the background while you keep working. When it finishes, its result arrives as a message in your main conversation. The next section covers the panel controls for watching and steering forks while they run.

### Observe and steer running forks

Running forks appear in a panel below the prompt input, with one row for the main session and one for each fork. Use these keys to interact with the panel:

| Key       | Action                                                             |
| :-------- | :----------------------------------------------------------------- |
| `↑` / `↓` | Move between rows                                                  |
| `Enter`   | Open the selected fork's transcript and send it follow-up messages |
| `x`       | Dismiss a finished fork or stop a running one                      |
| `Esc`     | Return focus to the prompt input                                   |

### How forks differ from named subagents

A fork inherits everything the main session has at the moment it spawns. A named subagent starts from its own definition.

|                         | Fork                             | Named subagent                                                                                                    |
| :---------------------- | :------------------------------- | :---------------------------------------------------------------------------------------------------------------- |
| Context                 | Full conversation history        | Fresh context with the prompt you pass                                                                            |
| System prompt and tools | Same as main session             | From the subagent's [definition file](#write-subagent-files)                                                      |
| Model                   | Same as main session             | From the subagent's `model` field                                                                                 |
| Permissions             | Prompts surface in your terminal | [Prompts surface in your main session](#run-subagents-in-foreground-or-background) when running in the background |
| Prompt cache            | Shared with main session         | Separate cache                                                                                                    |

Because a fork's system prompt and tool definitions are identical to the parent, its first request reuses the parent's [prompt cache](/en/prompt-caching#subagents-and-the-cache). This makes forking cheaper than spawning a fresh subagent for tasks that need the same context.

When Claude spawns a fork through the Agent tool, it can pass `isolation: "worktree"` so the fork's file edits are written to a separate git worktree instead of your checkout.

### Limitations

Setting `CLAUDE_CODE_FORK_SUBAGENT=1` enables fork mode in interactive sessions, [non-interactive mode](/en/headless), and the Agent SDK; setting it to `0` disables fork mode everywhere, including any server-side rollout. A fork can't spawn further forks.

## Example subagents

These examples demonstrate effective patterns for building subagents. Use them as starting points, or generate a customized version with Claude.

<Tip>
  **Best practices:**

  * **Design focused subagents:** each subagent should excel at one specific task
  * **Write detailed descriptions:** Claude uses the description to decide when to delegate
  * **Limit tool access:** grant only necessary permissions for security and focus
  * **Check into version control:** share project subagents with your team
</Tip>

### Code reviewer

A read-only subagent that reviews code without modifying it. This example shows how to design a focused subagent with limited tool access (no Edit or Write) and a detailed prompt that specifies exactly what to look for and how to format output.

```markdown theme={null}
---
name: code-reviewer
description: Expert code review specialist. Proactively reviews code for quality, security, and maintainability. Use immediately after writing or modifying code.
tools: Read, Grep, Glob, Bash
model: inherit
---

You are a senior code reviewer ensuring high standards of code quality and security.

When invoked:
1. Run git diff to see recent changes
2. Focus on modified files
3. Begin review immediately

Review checklist:
- Code is clear and readable
- Functions and variables are well-named
- No duplicated code
- Proper error handling
- No exposed secrets or API keys
- Input validation implemented
- Good test coverage
- Performance considerations addressed

Provide feedback organized by priority:
- Critical issues (must fix)
- Warnings (should fix)
- Suggestions (consider improving)

Include specific examples of how to fix issues.
```

### Debugger

A subagent that can both analyze and fix issues. Unlike the code reviewer, this one includes Edit because fixing bugs requires modifying code. The prompt provides a clear workflow from diagnosis to verification.

```markdown theme={null}
---
name: debugger
description: Debugging specialist for errors, test failures, and unexpected behavior. Use proactively when encountering any issues.
tools: Read, Edit, Bash, Grep, Glob
---

You are an expert debugger specializing in root cause analysis.

When invoked:
1. Capture error message and stack trace
2. Identify reproduction steps
3. Isolate the failure location
4. Implement minimal fix
5. Verify solution works

Debugging process:
- Analyze error messages and logs
- Check recent code changes
- Form and test hypotheses
- Add strategic debug logging
- Inspect variable states

For each issue, provide:
- Root cause explanation
- Evidence supporting the diagnosis
- Specific code fix
- Testing approach
- Prevention recommendations

Focus on fixing the underlying issue, not the symptoms.
```

### Data scientist

A domain-specific subagent for data analysis work. This example shows how to create subagents for specialized workflows outside of typical coding tasks. It explicitly sets `model: sonnet` for more capable analysis.

```markdown theme={null}
---
name: data-scientist
description: Data analysis expert for SQL queries, BigQuery operations, and data insights. Use proactively for data analysis tasks and queries.
tools: Bash, Read, Write
model: sonnet
---

You are a data scientist specializing in SQL and BigQuery analysis.

When invoked:
1. Understand the data analysis requirement
2. Write efficient SQL queries
3. Use BigQuery command line tools (bq) when appropriate
4. Analyze and summarize results
5. Present findings clearly

Key practices:
- Write optimized SQL queries with proper filters
- Use appropriate aggregations and joins
- Include comments explaining complex logic
- Format results for readability
- Provide data-driven recommendations

For each analysis:
- Explain the query approach
- Document any assumptions
- Highlight key findings
- Suggest next steps based on data

Always ensure queries are efficient and cost-effective.
```

### Database query validator

A subagent that allows Bash access but validates commands to permit only read-only SQL queries. This example shows how to use `PreToolUse` hooks for conditional validation when you need finer control than the `tools` field provides.

```markdown theme={null}
---
name: db-reader
description: Execute read-only database queries. Use when analyzing data or generating reports.
tools: Bash
hooks:
  PreToolUse:
    - matcher: "Bash"
      hooks:
        - type: command
          command: "./scripts/validate-readonly-query.sh"
---

You are a database analyst with read-only access. Execute SELECT queries to answer questions about the data.

When asked to analyze data:
1. Identify which tables contain the relevant data
2. Write efficient SELECT queries with appropriate filters
3. Present results clearly with context

You cannot modify data. If asked to INSERT, UPDATE, DELETE, or modify schema, explain that you only have read access.
```

Claude Code [passes hook input as JSON](/en/hooks#pretooluse-input) via stdin to hook commands. The validation script reads this JSON, extracts the command being executed, and checks it against a list of SQL write operations. If a write operation is detected, the script [exits with code 2](/en/hooks#exit-code-2-behavior-per-event) to block execution and returns an error message to Claude via stderr.

Create the validation script anywhere in your project. The path must match the `command` field in your hook configuration:

```bash theme={null}
#!/bin/bash
# Blocks SQL write operations, allows SELECT queries

# Read JSON input from stdin
INPUT=$(cat)

# Extract the command field from tool_input using jq
COMMAND=$(echo "$INPUT" | jq -r '.tool_input.command // empty')

if [ -z "$COMMAND" ]; then
  exit 0
fi

# Block write operations (case-insensitive)
if echo "$COMMAND" | grep -iE '\b(INSERT|UPDATE|DELETE|DROP|CREATE|ALTER|TRUNCATE|REPLACE|MERGE)\b' > /dev/null; then
  echo "Blocked: Write operations not allowed. Use SELECT queries only." >&2
  exit 2
fi

exit 0
```

On macOS and Linux, make the script executable:

```bash theme={null}
chmod +x ./scripts/validate-readonly-query.sh
```

On Windows, write the validation script in PowerShell and add `shell: powershell` to the hook entry. See [running hooks in PowerShell](/en/hooks#windows-powershell-tool).

The hook receives JSON via stdin with the Bash command in `tool_input.command`. Exit code 2 blocks the operation and feeds the error message back to Claude. See [Hooks](/en/hooks#exit-code-output) for details on exit codes and [Hook input](/en/hooks#pretooluse-input) for the complete input schema.

## Next steps

Now that you understand subagents, explore these related features:

* [Distribute subagents with plugins](/en/plugins) to share subagents across teams or projects
* [Run Claude Code programmatically](/en/headless) with the Agent SDK for CI/CD and automation
* [Use MCP servers](/en/mcp) to give subagents access to external tools and data


================================================================================
# PAGE: settings
# source: https://docs.claude.com/en/docs/claude-code/settings.md
================================================================================

> ## Documentation Index
> Fetch the complete documentation index at: https://code.claude.com/docs/llms.txt
> Use this file to discover all available pages before exploring further.

# Claude Code settings

> Configure Claude Code with global and project-level settings, and environment variables.

Claude Code offers a variety of settings to configure its behavior to meet your needs. You can configure Claude Code by running the `/config` command, which opens a tabbed Settings interface where you can view status information and modify configuration options. {/* min-version: 2.1.181 */}From v2.1.181, you can change a single option without opening the interface by passing `key=value` to `/config`, for example `/config verbose=true`.

## Configuration scopes

Claude Code uses a scope system to determine where configurations apply and who they're shared with. Understanding scopes helps you decide how to configure Claude Code for personal use, team collaboration, or enterprise deployment.

### Available scopes

| Scope       | Location                                                                           | Who it affects                                                                                                                                                          | Shared with team?                           |
| :---------- | :--------------------------------------------------------------------------------- | :---------------------------------------------------------------------------------------------------------------------------------------------------------------------- | :------------------------------------------ |
| **Managed** | Server-managed settings, plist / registry, or system-level `managed-settings.json` | All organization members for server-managed delivery; all users on the machine for plist, HKLM registry, and file delivery; the current user for HKCU registry delivery | Yes (deployed by IT)                        |
| **User**    | `~/.claude/` directory                                                             | You, across all projects                                                                                                                                                | No                                          |
| **Project** | `.claude/` in repository                                                           | All collaborators on this repository                                                                                                                                    | Yes (committed to git)                      |
| **Local**   | `.claude/settings.local.json`                                                      | You, in this repository only                                                                                                                                            | No (gitignored when Claude Code creates it) |

### When to use each scope

**Managed scope** is for:

* Security policies that must be enforced organization-wide
* Compliance requirements that can't be overridden
* Standardized configurations deployed by IT/DevOps

**User scope** is best for:

* Personal preferences you want everywhere (themes, editor settings)
* Tools and plugins you use across all projects
* API keys and authentication (stored securely)

**Project scope** is best for:

* Team-shared settings (permissions, hooks, MCP servers)
* Plugins the whole team should have
* Standardizing tooling across collaborators

**Local scope** is best for:

* Personal overrides for a specific project
* Testing configurations before sharing with the team
* Machine-specific settings that won't work for others

### How scopes interact

When the same setting appears in multiple scopes, Claude Code applies them in priority order:

1. **Managed** (highest): can't be overridden by anything
2. **Command line arguments**: temporary session overrides
3. **Local**: overrides project and user settings
4. **Project**: overrides user settings
5. **User** (lowest): applies when nothing else specifies the setting

For example, if your user settings set `spinnerTipsEnabled` to `true` and project settings set it to `false`, the project value applies. Permission rules behave differently because they merge across scopes rather than override. See [Settings precedence](#settings-precedence).

### What uses scopes

Scopes apply to many Claude Code features:

| Feature         | User location             | Project location                   | Local location                 |
| :-------------- | :------------------------ | :--------------------------------- | :----------------------------- |
| **Settings**    | `~/.claude/settings.json` | `.claude/settings.json`            | `.claude/settings.local.json`  |
| **Subagents**   | `~/.claude/agents/`       | `.claude/agents/`                  | None                           |
| **MCP servers** | `~/.claude.json`          | `.mcp.json`                        | `~/.claude.json` (per-project) |
| **Plugins**     | `~/.claude/settings.json` | `.claude/settings.json`            | `.claude/settings.local.json`  |
| **CLAUDE.md**   | `~/.claude/CLAUDE.md`     | `CLAUDE.md` or `.claude/CLAUDE.md` | `CLAUDE.local.md`              |

On Windows, paths shown as `~/.claude` resolve to `%USERPROFILE%\.claude`.

***

## Settings files

The `settings.json` file is the official mechanism for configuring Claude
Code through hierarchical settings:

* **User settings** are defined in `~/.claude/settings.json` and apply to all
  projects.
* **Project settings** are saved in your project directory:
  * `.claude/settings.json` for settings that are checked into source control and shared with your team
  * `.claude/settings.local.json` for settings that are not checked in, useful for personal preferences and experimentation. When Claude Code creates `.claude/settings.local.json`, it configures git to ignore the file. If you create the file yourself, add it to your gitignore manually.
* **Managed settings**: For organizations that need centralized control, Claude Code supports multiple delivery mechanisms for managed settings. All use the same JSON format and cannot be overridden by user or project settings:

  * **Server-managed settings**: delivered remotely at sign-in, either from Anthropic's servers via the claude.ai admin console or from a self-hosted [Claude apps gateway](/en/claude-apps-gateway). See [server-managed settings](/en/server-managed-settings).
  * **MDM/OS-level policies**: delivered through native device management on macOS and Windows:
    * macOS: `com.anthropic.claudecode` managed preferences domain. The plist's top-level keys mirror `managed-settings.json`, with nested settings as dictionaries and arrays as plist arrays. Deploy via configuration profiles in Jamf, Iru (Kandji), or similar MDM tools.
    * Windows: `HKLM\SOFTWARE\Policies\ClaudeCode` registry key with a `Settings` value (REG\_SZ or REG\_EXPAND\_SZ) containing JSON (deployed via Group Policy or Intune)
    * Windows (user-level): `HKCU\SOFTWARE\Policies\ClaudeCode` (lowest policy priority, only used when no admin-level source exists)
  * **File-based**: `managed-settings.json` and `managed-mcp.json` deployed to system directories:

    * macOS: `/Library/Application Support/ClaudeCode/`
    * Linux and WSL: `/etc/claude-code/`
    * Windows: `C:\Program Files\ClaudeCode\`

    <Warning>
      The legacy Windows path `C:\ProgramData\ClaudeCode\managed-settings.json` is no longer supported as of v2.1.75. Administrators who deployed settings to that location must migrate files to `C:\Program Files\ClaudeCode\managed-settings.json`.
    </Warning>

    File-based managed settings also support a drop-in directory at `managed-settings.d/` in the same system directory alongside `managed-settings.json`. This lets separate teams deploy independent policy fragments without coordinating edits to a single file.

    Following the systemd convention, `managed-settings.json` is merged first as the base, then all `*.json` files in the drop-in directory are sorted alphabetically and merged on top. Later files override earlier ones for scalar values, arrays are concatenated and de-duplicated, and objects are deep-merged. Hidden files starting with `.` are ignored.

    Use numeric prefixes to control merge order, for example `10-telemetry.json` and `20-security.json`.

  See [managed settings](/en/permissions#managed-only-settings) and [Managed MCP configuration](/en/managed-mcp) for details.

  This [repository](https://github.com/anthropics/claude-code/tree/main/examples/mdm) includes starter deployment templates for Jamf, Iru (Kandji), Intune, and Group Policy. Use these as starting points and adjust them to fit your needs.

  <Note>
    Managed deployments can also restrict **plugin marketplace additions** using
    `strictKnownMarketplaces`. For more information, see [Managed marketplace restrictions](/en/plugin-marketplaces#managed-marketplace-restrictions).
  </Note>
* **Other configuration** is stored in `~/.claude.json`. This file contains your OAuth session, [MCP server](/en/mcp) configurations for user and local scopes, per-project state (allowed tools, trust settings), and various caches. Project-scoped MCP servers are stored separately in `.mcp.json`.

<Note>
  Claude Code automatically creates timestamped backups of configuration files and retains the five most recent backups to prevent data loss.
</Note>

```JSON Example settings.json theme={null}
{
  "$schema": "https://json.schemastore.org/claude-code-settings.json",
  "permissions": {
    "allow": [
      "Bash(npm run lint)",
      "Bash(npm run test *)",
      "Read(~/.zshrc)"
    ],
    "deny": [
      "Bash(curl *)",
      "Read(./.env)",
      "Read(./.env.*)",
      "Read(./secrets/**)"
    ]
  },
  "env": {
    "CLAUDE_CODE_ENABLE_TELEMETRY": "1",
    "OTEL_METRICS_EXPORTER": "otlp"
  },
  "companyAnnouncements": [
    "Welcome to Acme Corp! Review our code guidelines at docs.acme.com",
    "Reminder: Code reviews required for all PRs",
    "New security policy in effect"
  ]
}
```

The `$schema` line in the example above points to the [official JSON schema](https://json.schemastore.org/claude-code-settings.json) for Claude Code settings. Adding it to your `settings.json` enables autocomplete and inline validation in VS Code, Cursor, and any other editor that supports JSON schema validation.

The published schema is updated periodically and may not include settings added in the most recent CLI releases, so a validation warning on a recently documented field does not necessarily mean your configuration is invalid.

### When edits take effect

Claude Code watches your settings files and reloads them when they change, so edits to most keys apply to the running session without a restart. This includes `permissions`, `hooks`, and credential helpers like `apiKeyHelper`. The reload covers user, project, local, and managed settings, and the [`ConfigChange` hook](/en/hooks#configchange) fires for each detected change.

A few keys are read once at session start and apply on the next restart instead:

* `model`: use [`/model`](/en/model-config#setting-your-model) to switch mid-session
* [`outputStyle`](/en/output-styles): part of the system prompt, which is rebuilt on `/clear` or restart

### Invalid entries in managed settings

Managed settings parse tolerantly. When a managed configuration contains an entry that fails schema validation, Claude Code strips that entry, records a warning, and enforces every remaining valid policy. A single typo cannot disable the rest of your organization's policy.

This behavior is consistent across all three delivery mechanisms: [server-managed settings](/en/server-managed-settings), plist and registry policies deployed through MDM, and `managed-settings.json` files. Requires Claude Code v2.1.169 or later.

Security-enforcement fields are handled per field instead of being stripped wholesale when they are present but invalid:

| Field                        | Behavior when present but invalid                                                                                                                                                                                                                                                     |
| :--------------------------- | :------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `allowedMcpServers`          | Enforced as an empty allowlist, so no MCP servers are admitted until the value is fixed. An individual invalid entry is stripped and the valid subset is enforced.                                                                                                                    |
| `allowManagedMcpServersOnly` | Treated as `true`.                                                                                                                                                                                                                                                                    |
| `availableModels`            | {/* min-version: 2.1.175 */}Enforced as an empty allowlist, so only the Default model is available until the value is fixed. An individual non-string entry is stripped and the valid subset is enforced. Applies in v2.1.175 and later.                                              |
| `enforceAvailableModels`     | {/* min-version: 2.1.175 */}Treated as `true`. Applies in v2.1.175 and later.                                                                                                                                                                                                         |
| `forceLoginOrgUUID`          | No organization is permitted to log in until the value is fixed.                                                                                                                                                                                                                      |
| `deniedMcpServers`           | An individual invalid entry is stripped and the valid subset is enforced. A wholly invalid value is dropped with a warning, since denying every server would block servers the policy never named.                                                                                    |
| `sandbox.credentials`        | {/* min-version: 2.1.191 */}An individual invalid entry in `files` or `envVars` is stripped with a warning and the valid subset is enforced. A wholly invalid `credentials` value is dropped with a warning while the rest of `sandbox` still applies. Applies in v2.1.191 and later. |

`requiredMinimumVersion` and `requiredMaximumVersion` fail open by design: an invalid value is stripped rather than enforced, so a bad policy push cannot prevent Claude Code from starting.

Validation errors surface in three places:

* Interactive sessions show a dialog at startup listing the invalid entries.
* Headless runs with `-p` print a summary to stderr.
* [`claude doctor`](/en/debug-your-config) lists each invalid entry with its source and field.

Validate policy changes by running `claude doctor` on a test machine before deploying them fleet-wide.

This tolerance applies only to managed settings. User, project, and local settings files remain strict: a file that fails validation is rejected as a whole and reported.

### Available settings

`settings.json` supports a number of options:

| Key                               | Description                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                       | Example                                                                                                                         |
| :-------------------------------- | :------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | :------------------------------------------------------------------------------------------------------------------------------ |
| `advisorModel`                    | {/* min-version: 2.1.98 */}Model for the server-side [advisor tool](/en/advisor). Accepts a model alias such as `"opus"`, `"sonnet"`, or `"fable"` ({/* min-version: 2.1.170 */}v2.1.170+), or a full model ID. Written automatically when you run `/advisor`. Unset to disable the advisor. Requires Claude Code v2.1.98 or later                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                | `"opus"`                                                                                                                        |
| `agent`                           | Run the main thread as a named subagent, and set the default agent for sessions dispatched from `claude agents`. Applies that subagent's system prompt, tool restrictions, and model. See [Invoke subagents explicitly](/en/sub-agents#invoke-subagents-explicitly)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                               | `"code-reviewer"`                                                                                                               |
| `agentPushNotifEnabled`           | {/* min-version: 2.1.119 */}**Default**: `false`. When [Remote Control](/en/remote-control) is connected, allow Claude to send proactive push notifications to your phone, for example when a long task finishes. Appears in `/config` as **Push when Claude decides**. See [Mobile push notifications](/en/remote-control#mobile-push-notifications). Requires Claude Code v2.1.119 or later                                                                                                                                                                                                                                                                                                                                                                                                                                                     | `true`                                                                                                                          |
| `allowAllClaudeAiMcps`            | (Managed settings only) Load claude.ai connectors alongside a deployed `managed-mcp.json`, which otherwise takes exclusive control and suppresses them. See [Managed MCP configuration](/en/managed-mcp)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                          | `true`                                                                                                                          |
| `allowedChannelPlugins`           | (Managed settings only) Allowlist of channel plugins that may push messages. Replaces the default Anthropic allowlist when set. Undefined = fall back to the default, empty array = block all channel plugins. Requires `channelsEnabled: true`. See [Restrict which channel plugins can run](/en/channels#restrict-which-channel-plugins-can-run)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                | `[{ "marketplace": "claude-plugins-official", "plugin": "telegram" }]`                                                          |
| `allowedHttpHookUrls`             | Allowlist of URL patterns that HTTP hooks may target. Supports `*` as a wildcard. When set, hooks with non-matching URLs are blocked. Undefined = no restrictions, empty array = block all HTTP hooks. Arrays merge across settings sources. See [Hook configuration](#hook-configuration)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                        | `["https://hooks.example.com/*"]`                                                                                               |
| `allowedMcpServers`               | When set in managed-settings.json, allowlist of MCP servers users can configure. Undefined = no restrictions, empty array = lockdown. Applies to all scopes. Denylist takes precedence. See [Managed MCP configuration](/en/managed-mcp)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                          | `[{ "serverName": "github" }]`                                                                                                  |
| `allowManagedHooksOnly`           | (Managed settings only) Only managed hooks, SDK hooks, and hooks from plugins force-enabled in managed settings `enabledPlugins` are loaded. User, project, and all other plugin hooks are blocked. See [Hook configuration](#hook-configuration)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                 | `true`                                                                                                                          |
| `allowManagedMcpServersOnly`      | (Managed settings only) Only `allowedMcpServers` from managed settings are respected. `deniedMcpServers` still merges from all sources. Users can still add MCP servers, but only the admin-defined allowlist applies. See [Managed MCP configuration](/en/managed-mcp)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                           | `true`                                                                                                                          |
| `allowManagedPermissionRulesOnly` | (Managed settings only) Prevent user and project settings from defining `allow`, `ask`, or `deny` permission rules. Only rules in managed settings apply. See [Managed-only settings](/en/permissions#managed-only-settings)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                      | `true`                                                                                                                          |
| `alwaysThinkingEnabled`           | Enable [extended thinking](/en/model-config#extended-thinking) by default for all sessions. Typically configured via the `/config` command rather than editing directly. To force thinking off regardless of this setting, set [`MAX_THINKING_TOKENS=0`](/en/env-vars) in `env`, which disables thinking on the Anthropic API except on Fable 5, which cannot have thinking turned off. On [third-party providers](/en/third-party-integrations) this omits the `thinking` parameter instead, and adaptive-reasoning models may still think                                                                                                                                                                                                                                                                                                       | `true`                                                                                                                          |
| `apiKeyHelper`                    | Custom command, run through the system shell (`/bin/sh` on macOS and Linux, `cmd` on Windows), to generate an auth value. This value will be sent as `X-Api-Key` and `Authorization: Bearer` headers for model requests. Set the refresh interval with [`CLAUDE_CODE_API_KEY_HELPER_TTL_MS`](/en/env-vars)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                        | `/bin/generate_temp_api_key.sh`                                                                                                 |
| `attribution`                     | Customize attribution for git commits and pull requests. See [Attribution settings](#attribution-settings)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                        | `{"commit": "🤖 Generated with Claude Code", "pr": ""}`                                                                         |
| `autoCompactEnabled`              | {/* min-version: 2.1.119 */}**Default**: `true`. Automatically compact the conversation when context approaches the limit. Appears in `/config` as **Auto-compact**. To disable via environment variable, set [`DISABLE_AUTO_COMPACT`](/en/env-vars) in `env`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                     | `false`                                                                                                                         |
| `autoMemoryDirectory`             | Custom directory for [auto memory](/en/memory#storage-location) storage. Accepts an absolute path or a `~/`-prefixed path. From project or local settings, this is honored only after you accept the workspace trust dialog, since a cloned repository can supply this file                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                       | `"~/my-memory-dir"`                                                                                                             |
| `autoMemoryEnabled`               | **Default**: `true`. Enable [auto memory](/en/memory#enable-or-disable-auto-memory). When `false`, Claude does not read from or write to the auto memory directory. You can also toggle this with `/memory` during a session. To disable via environment variable, set [`CLAUDE_CODE_DISABLE_AUTO_MEMORY`](/en/env-vars) in `env`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                 | `false`                                                                                                                         |
| `autoMode`                        | Customize what the [auto mode](/en/permission-modes#eliminate-prompts-with-auto-mode) classifier blocks and allows. Contains `environment`, `allow`, `soft_deny`, and `hard_deny` arrays of prose rules. Include the literal string `"$defaults"` in an array to inherit the built-in rules at that position. See [Configure auto mode](/en/auto-mode-config). Not read from shared project settings                                                                                                                                                                                                                                                                                                                                                                                                                                              | `{"soft_deny": ["$defaults", "Never run terraform apply"]}`                                                                     |
| `autoMode.classifyAllShell`       | {/* min-version: 2.1.193 */}**Default**: `false`. When `true`, suspends every Bash and PowerShell allow rule while auto mode is active so all shell commands route through the classifier, not only rules that match arbitrary-code-execution patterns. See [Route all shell commands through the classifier](/en/auto-mode-config#route-all-shell-commands-through-the-classifier). Requires Claude Code v2.1.193 or later                                                                                                                                                                                                                                                                                                                                                                                                                       | `true`                                                                                                                          |
| `autoScrollEnabled`               | **Default**: `true`. In [fullscreen rendering](/en/fullscreen), follow new output to the bottom of the conversation. Appears in `/config` as **Auto-scroll**. Permission prompts still scroll into view when this is off                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                          | `false`                                                                                                                         |
| `autoUpdatesChannel`              | **Default**: `"latest"`. Release channel to follow for updates. Use `"stable"` for a version that is typically about one week old and skips versions with major regressions, or `"latest"` for the most recent release. To disable auto-updates entirely, set [`DISABLE_AUTOUPDATER`](/en/setup#disable-auto-updates) in `env`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                    | `"stable"`                                                                                                                      |
| `availableModels`                 | Restrict which models users can select for the main session, [subagents](/en/sub-agents), [skills](/en/skills), and the [advisor](/en/advisor). Does not affect the Default option unless `enforceAvailableModels` is also set. See [Restrict model selection](/en/model-config#restrict-model-selection)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                         | `["sonnet", "haiku"]`                                                                                                           |
| `awaySummaryEnabled`              | Show a one-line session recap when you return to the terminal after a few minutes away. Set to `false` or turn off Session recap in `/config` to disable. Same as [`CLAUDE_CODE_ENABLE_AWAY_SUMMARY`](/en/env-vars)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                               | `true`                                                                                                                          |
| `awsAuthRefresh`                  | Custom script that modifies the `.aws` directory (see [advanced credential configuration](/en/amazon-bedrock#advanced-credential-configuration))                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                  | `aws sso login --profile myprofile`                                                                                             |
| `awsCredentialExport`             | Custom script that outputs JSON with AWS credentials (see [advanced credential configuration](/en/amazon-bedrock#advanced-credential-configuration))                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                              | `/bin/generate_aws_grant.sh`                                                                                                    |
| `axScreenReader`                  | {/* min-version: 2.1.181 */}Render screen-reader friendly output: flat text without decorative borders or animations. Screen-reader mode always uses the classic renderer, so the `tui` setting has no effect while it is active. The [`CLAUDE_AX_SCREEN_READER`](/en/env-vars) environment variable and the [`--ax-screen-reader`](/en/cli-reference#cli-flags) flag take precedence. Requires Claude Code v2.1.181 or later                                                                                                                                                                                                                                                                                                                                                                                                                     | `true`                                                                                                                          |
| `blockedMarketplaces`             | (Managed settings only) Blocklist of marketplace sources. Enforced on marketplace add and on plugin install, update, refresh, and auto-update, so a marketplace added before the policy was set cannot be used to fetch plugins. Blocked sources are checked before downloading, so they never touch the filesystem. See [Managed marketplace restrictions](/en/plugin-marketplaces#managed-marketplace-restrictions)                                                                                                                                                                                                                                                                                                                                                                                                                             | `[{ "source": "github", "repo": "untrusted/plugins" }]`                                                                         |
| `channelsEnabled`                 | (Managed settings only) Allow [channels](/en/channels) for the organization. On claude.ai Team and Enterprise plans, channels are blocked when this is unset or `false`. For [Anthropic Console](/en/authentication#claude-console-authentication) accounts using API key authentication, channels are allowed by default unless your organization deploys managed settings, in which case this key must be set to `true`                                                                                                                                                                                                                                                                                                                                                                                                                         | `true`                                                                                                                          |
| `claudeMd`                        | (Managed settings only) CLAUDE.md-style instructions injected as organization-managed memory. Only honored when set in managed or policy settings and ignored in user, project, and local settings. See [organization-wide CLAUDE.md](/en/memory#deploy-organization-wide-claude-md)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                              | `"Always run make lint before committing."`                                                                                     |
| `claudeMdExcludes`                | Glob patterns or absolute paths of `CLAUDE.md` files to skip when loading [memory](/en/memory). Patterns match against absolute file paths. Only applies to user, project, and local memory; managed policy files cannot be excluded                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                              | `["**/vendor/**/CLAUDE.md"]`                                                                                                    |
| `cleanupPeriodDays`               | **Default**: `30` days, minimum `1`. Session files older than this period are deleted at startup. Setting to `0` is rejected with a validation error. Also controls the age cutoff for automatic removal of [orphaned subagent worktrees](/en/worktrees#clean-up-worktrees) at startup. To disable transcript writes entirely, set the [`CLAUDE_CODE_SKIP_PROMPT_HISTORY`](/en/env-vars) environment variable, or in non-interactive mode (`-p`) use the `--no-session-persistence` flag or the `persistSession: false` SDK option.                                                                                                                                                                                                                                                                                                               | `20`                                                                                                                            |
| `companyAnnouncements`            | Announcement to display to users at startup. If multiple announcements are provided, they will be cycled through at random.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                       | `["Welcome to Acme Corp! Review our code guidelines at docs.acme.com"]`                                                         |
| `defaultShell`                    | **Default**: `"bash"`, or `"powershell"` on Windows when Bash isn't available. Default shell for input-box `!` commands. Accepts `"bash"` or `"powershell"`. Setting `"powershell"` routes interactive `!` commands through PowerShell on Windows. Requires `CLAUDE_CODE_USE_POWERSHELL_TOOL=1`. See [PowerShell tool](/en/tools-reference#powershell-tool)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                       | `"powershell"`                                                                                                                  |
| `deniedMcpServers`                | When set in managed-settings.json, denylist of MCP servers that are explicitly blocked. Applies to all scopes including managed servers. Denylist takes precedence over allowlist. See [Managed MCP configuration](/en/managed-mcp)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                               | `[{ "serverName": "filesystem" }]`                                                                                              |
| `disableAgentView`                | Set to `true` to turn off [background agents and agent view](/en/agent-view): `claude agents`, `--bg`, `/background`, and the on-demand supervisor. Typically set in [managed settings](/en/permissions#managed-settings). Equivalent to setting `CLAUDE_CODE_DISABLE_AGENT_VIEW` to `1`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                          | `true`                                                                                                                          |
| `disableAllHooks`                 | Disable all [hooks](/en/hooks) and any custom [status line](/en/statusline)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                       | `true`                                                                                                                          |
| `disableArtifact`                 | Set to `true` to disable the [Artifact](/en/artifacts) tool, which publishes session output as a private web page on claude.ai. Equivalent to setting `CLAUDE_CODE_DISABLE_ARTIFACT` to `1`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                       | `true`                                                                                                                          |
| `disableAutoMode`                 | Set to `"disable"` to prevent [auto mode](/en/permission-modes#eliminate-prompts-with-auto-mode) from being activated. Removes `auto` from the `Shift+Tab` cycle and rejects `--permission-mode auto` at startup. Most useful in [managed settings](/en/permissions#managed-settings) where users cannot override it                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                              | `"disable"`                                                                                                                     |
| `disableBundledSkills`            | Set to `true` to disable the [skills](/en/skills) and workflows that ship with Claude Code: bundled skills and workflows are removed entirely, while built-in slash commands like `/init` stay typable but are hidden from the model. Skills from plugins, `.claude/skills/`, and `.claude/commands/` are unaffected. Equivalent to setting `CLAUDE_CODE_DISABLE_BUNDLED_SKILLS` to `1`                                                                                                                                                                                                                                                                                                                                                                                                                                                           | `true`                                                                                                                          |
| `disableClaudeAiConnectors`       | {/* min-version: 2.1.182 */}Disable [claude.ai MCP connectors](/en/mcp#use-mcp-servers-from-claude-ai) so they are not auto-fetched or connected. Set in any settings scope. `true` in any source takes precedence, so a checked-in project `.claude/settings.json` can opt a repo out of cloud connectors, but a project-level `false` cannot override a user- or policy-level `true`. Servers passed explicitly via `--mcp-config` are unaffected. To deny individual connectors instead of all of them, use [`deniedMcpServers`](/en/managed-mcp). Requires Claude Code v2.1.182 or later                                                                                                                                                                                                                                                      | `true`                                                                                                                          |
| `disableDeepLinkRegistration`     | Set to `"disable"` to prevent Claude Code from registering the `claude-cli://` protocol handler with the operating system on startup. [Deep links](/en/deep-links) let external tools open a Claude Code session with a pre-filled prompt. Useful in environments where protocol handler registration is restricted or managed separately                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                         | `"disable"`                                                                                                                     |
| `disabledMcpjsonServers`          | List of specific MCP servers from `.mcp.json` files to reject                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                     | `["filesystem"]`                                                                                                                |
| `disableRemoteControl`            | {/* min-version: 2.1.128 */}Disable [Remote Control](/en/remote-control): blocks `claude remote-control`, the `--remote-control` flag, auto-start, and the in-session toggle. Typically placed in [managed settings](/en/permissions#managed-settings) for per-device MDM enforcement, but works from any scope. Requires Claude Code v2.1.128 or later                                                                                                                                                                                                                                                                                                                                                                                                                                                                                           | `true`                                                                                                                          |
| `disableSideloadFlags`            | {/* min-version: 2.1.193 */}(Managed settings only) Reject the `--plugin-dir`, `--plugin-url`, `--agents`, and `--mcp-config` CLI flags at startup, which users could otherwise pass to bypass [`strictKnownMarketplaces`](#strictknownmarketplaces) for a single run. Also rejects these flags from any surface that spawns the CLI with them internally, currently [Cowork](/en/desktop) local sessions in the desktop app. A `--mcp-config` whose servers are all in-process `type: "sdk"` entries is still accepted, so the Agent SDK and VS Code extension keep working. Doesn't block `claude mcp add`, `.mcp.json`, or SDK `setMcpServers()`; pair with [`allowedMcpServers`](/en/managed-mcp) for per-server MCP control. Requires Claude Code v2.1.193 or later                                                                          | `true`                                                                                                                          |
| `disableSkillShellExecution`      | Disable inline shell execution for `` !`...` `` and ` ```! ` blocks in [skills](/en/skills) and custom commands from user, project, plugin, or additional-directory sources. Commands are replaced with `[shell command execution disabled by policy]` instead of being run. Bundled and managed skills are not affected. Most useful in [managed settings](/en/permissions#managed-settings) where users cannot override it                                                                                                                                                                                                                                                                                                                                                                                                                      | `true`                                                                                                                          |
| `disableWorkflows`                | **Default**: `false`. Disable [dynamic workflows](/en/workflows#turn-workflows-off) and the bundled workflow commands. Equivalent to setting `CLAUDE_CODE_DISABLE_WORKFLOWS` to `1`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                               | `true`                                                                                                                          |
| `editorMode`                      | **Default**: `"normal"`. Key binding mode for the input prompt: `"normal"` or `"vim"`. Appears in `/config` as **Editor mode**                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                    | `"vim"`                                                                                                                         |
| `effortLevel`                     | Persist the [effort level](/en/model-config#adjust-effort-level) across sessions. Accepts `"low"`, `"medium"`, `"high"`, or `"xhigh"`. Written automatically when you run `/effort` with one of those values. `--effort` and [`CLAUDE_CODE_EFFORT_LEVEL`](/en/env-vars) override this for one session. See [Adjust effort level](/en/model-config#adjust-effort-level) for supported models                                                                                                                                                                                                                                                                                                                                                                                                                                                       | `"xhigh"`                                                                                                                       |
| `enableAllProjectMcpServers`      | Automatically approve all MCP servers defined in project `.mcp.json` files. {/* min-version: 2.1.196 */}As of v2.1.196, `claude mcp list` and `claude mcp get` honor this key in an untrusted folder only from [settings files that aren't checked into the repository](/en/mcp#managing-your-servers)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                            | `true`                                                                                                                          |
| `enableArtifact`                  | {/* min-version: 2.1.196 */}Enable or disable the [Artifact](/en/artifacts) tool for this user. When unset, the default follows the feature's [availability](/en/artifacts#availability) for your account. The **Artifacts** row in `/config` writes this key. A managed `disableArtifact` and your organization's [admin setting](/en/artifacts#manage-artifacts-for-your-organization) take precedence, and the key is ignored in project and local settings (`.claude/settings.json`, `.claude/settings.local.json`), which a repository could otherwise commit. Requires Claude Code v2.1.196 or later                                                                                                                                                                                                                                        | `true`                                                                                                                          |
| `enabledMcpjsonServers`           | List of specific MCP servers from `.mcp.json` files to approve. {/* min-version: 2.1.196 */}As of v2.1.196, `claude mcp list` and `claude mcp get` honor this key in an untrusted folder only from [settings files that aren't checked into the repository](/en/mcp#managing-your-servers)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                        | `["memory", "github"]`                                                                                                          |
| `enforceAvailableModels`          | {/* min-version: 2.1.175 */}Extend the `availableModels` allowlist to the Default model. When `true` in managed settings and `availableModels` is a non-empty array, the Default option falls back to the first allowlisted entry that is available, but only when the default model for the user's account type is not in the allowlist; an allowlisted default is kept as-is. Has no effect when `availableModels` is unset or empty. See [Enforce the allowlist for the Default model](/en/model-config#enforce-the-allowlist-for-the-default-model). Requires Claude Code v2.1.175 or later                                                                                                                                                                                                                                                   | `true`                                                                                                                          |
| `env`                             | Environment variables applied to every session and to subprocesses Claude Code spawns from it. {/* min-version: 2.1.143 */}As of v2.1.143, `NO_COLOR` and `FORCE_COLOR` set here are passed to subprocesses but do not change Claude Code's own interface colors. Set those in your shell before launching `claude` to change interface colors. {/* min-version: 2.1.195 */}As of v2.1.195, identity variables that Claude Code's hosting environments set, for example `CLAUDE_CODE_REMOTE` and `CLAUDE_CODE_ACCOUNT_UUID`, are ignored when set here                                                                                                                                                                                                                                                                                            | `{"FOO": "bar"}`                                                                                                                |
| `fallbackModel`                   | Fallback model(s) to try in order when the primary model is overloaded or unavailable. Claude Code switches to the next available model in the chain for the rest of the turn and shows a notice. `"default"` expands to the default model. Chains are capped at three models; extra entries are ignored. Unlike most array settings, this key does not merge across settings files: the highest-precedence file that defines it supplies the entire chain. The [`--fallback-model`](/en/cli-reference#cli-flags) flag overrides this for one session. See [Fallback model chains](/en/model-config#fallback-model-chains)                                                                                                                                                                                                                        | `["claude-sonnet-5", "claude-haiku-4-5"]`                                                                                       |
| `fastModePerSessionOptIn`         | When `true`, fast mode does not persist across sessions. Each session starts with fast mode off, requiring users to enable it with `/fast`. The user's fast mode preference is still saved. See [Require per-session opt-in](/en/fast-mode#require-per-session-opt-in)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                            | `true`                                                                                                                          |
| `feedbackSurveyRate`              | Probability (0–1) that the [session quality survey](/en/data-usage#session-quality-surveys) appears when eligible. Set to `0` to suppress entirely, or set [`CLAUDE_CODE_DISABLE_FEEDBACK_SURVEY`](/en/env-vars) in `env`. Useful when using Bedrock, Vertex, or Foundry where the default sample rate does not apply                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                             | `0.05`                                                                                                                          |
| `fileCheckpointingEnabled`        | {/* min-version: 2.1.119 */}**Default**: `true`. Snapshot files before each edit so [`/rewind`](/en/checkpointing) can restore them. Appears in `/config` as **Rewind code (checkpoints)**. To disable via environment variable, set [`CLAUDE_CODE_DISABLE_FILE_CHECKPOINTING`](/en/env-vars) in `env`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                            | `false`                                                                                                                         |
| `fileSuggestion`                  | Configure a custom script for `@` file autocomplete. See [File suggestion settings](#file-suggestion-settings)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                    | `{"type": "command", "command": "~/.claude/file-suggestion.sh"}`                                                                |
| `footerLinksRegexes`              | {/* min-version: 2.1.176 */}Render extra clickable badges in the footer when a regex matches turn output. Each entry has a `pattern`, a `url` template with `{name}` placeholders filled from named capture groups, and an optional `label`. Read from user, `--settings` flag, and managed settings only. See [Footer link badges](#footer-link-badges) for URL constraints, scheme allowlist, and limits. Requires Claude Code v2.1.176 or later                                                                                                                                                                                                                                                                                                                                                                                                | `[{"type": "regex", "pattern": "\\b(?<key>PROJ-\\d+)\\b", "url": "https://issues.example.com/browse/{key}", "label": "{key}"}]` |
| `forceLoginMethod`                | Use `claudeai` to restrict login to Claude.ai accounts, `console` to restrict login to Claude Console accounts, or `gateway` to restrict login to a cloud gateway; see [Claude apps gateway](/en/claude-apps-gateway). When set to any value in managed settings, sessions authenticated by `ANTHROPIC_API_KEY`, `ANTHROPIC_AUTH_TOKEN`, or `apiKeyHelper` are blocked at startup, since an environment credential cannot satisfy the required login method. Third-party provider sessions such as Bedrock, Vertex, and Foundry are not blocked: they authenticate against your cloud provider rather than Anthropic                                                                                                                                                                                                                              | `claudeai`                                                                                                                      |
| `forceLoginGatewayUrl`            | Pre-fills and locks the gateway URL on the `/login` Cloud gateway screen. Either this key or `forceLoginMethod: "gateway"` surfaces that screen; set both so the URL is filled in. Honored only at the managed policy tier; ignored in user and project settings. See [Claude apps gateway](/en/claude-apps-gateway#set-the-gateway-url)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                          | `"https://claude-gateway.example.com"`                                                                                          |
| `forceLoginOrgUUID`               | Require login to belong to a specific Anthropic organization. Accepts a single UUID string, which also pre-selects that organization during login, or an array of UUIDs where any listed organization is accepted without pre-selection. When set in managed settings, login fails if the authenticated account does not belong to a listed organization, and sessions authenticated by `ANTHROPIC_API_KEY`, `ANTHROPIC_AUTH_TOKEN`, or `apiKeyHelper` are blocked at startup since organization membership cannot be verified for them. Third-party provider sessions such as Bedrock, Vertex, and Foundry are not blocked: use your cloud IAM to restrict which cloud accounts can be used. An empty array fails closed and blocks login with a misconfiguration message                                                                        | `"xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"` or `["xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx", "yyyyyyyy-yyyy-yyyy-yyyy-yyyyyyyyyyyy"]`  |
| `forceRemoteSettingsRefresh`      | (Managed settings only) Block CLI startup until remote managed settings are freshly fetched from the server. If the fetch fails, the CLI exits rather than continuing with cached or no settings. When not set, startup continues without waiting for remote settings. See [fail-closed enforcement](/en/server-managed-settings#enforce-fail-closed-startup)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                     | `true`                                                                                                                          |
| `gcpAuthRefresh`                  | Custom script that refreshes GCP Application Default Credentials when they expire or cannot be loaded. See [advanced credential configuration](/en/google-vertex-ai#advanced-credential-configuration)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                            | `gcloud auth application-default login`                                                                                         |
| `hooks`                           | Configure custom commands to run at lifecycle events. See [hooks documentation](/en/hooks) for format                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                             | See [hooks](/en/hooks)                                                                                                          |
| `httpHookAllowedEnvVars`          | Allowlist of environment variable names HTTP hooks may interpolate into headers. When set, each hook's effective `allowedEnvVars` is the intersection with this list. Undefined = no restriction. Arrays merge across settings sources. See [Hook configuration](#hook-configuration)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                             | `["MY_TOKEN", "HOOK_SECRET"]`                                                                                                   |
| `includeGitInstructions`          | **Default**: `true`. Include built-in commit and PR workflow instructions and the git status snapshot in Claude's system prompt. Set to `false` to remove both, for example when using your own git workflow skills. The `CLAUDE_CODE_DISABLE_GIT_INSTRUCTIONS` environment variable takes precedence over this setting when set                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                  | `false`                                                                                                                         |
| `inputNeededNotifEnabled`         | {/* min-version: 2.1.119 */}**Default**: `false`. When [Remote Control](/en/remote-control) is connected, send a push notification to your phone when a permission prompt or question is waiting for your input. Appears in `/config` as **Push when actions required**. See [Mobile push notifications](/en/remote-control#mobile-push-notifications). Requires Claude Code v2.1.119 or later                                                                                                                                                                                                                                                                                                                                                                                                                                                    | `true`                                                                                                                          |
| `language`                        | Configure Claude's preferred response language (e.g., `"japanese"`, `"spanish"`, `"french"`). Claude will respond in this language by default. Also sets the language for [voice dictation](/en/voice-dictation#change-the-dictation-language) and auto-generated session titles. {/* min-version: 2.1.176 */}As of v2.1.176, when not set, session titles match the language of your conversation                                                                                                                                                                                                                                                                                                                                                                                                                                                | `"japanese"`                                                                                                                    |
| `minimumVersion`                  | Floor that prevents background auto-updates and `claude update` from installing a version below this one. Switching from the `"latest"` channel to `"stable"` via `/config` prompts you to stay on the current version or allow the downgrade. Choosing to stay sets this value. Also useful in [managed settings](/en/permissions#managed-settings) to pin an organization-wide minimum. For a hard floor that blocks startup entirely, see `requiredMinimumVersion`                                                                                                                                                                                                                                                                                                                                                                             | `"2.1.100"`                                                                                                                     |
| `model`                           | Override the default model to use for Claude Code. `--model` and [`ANTHROPIC_MODEL`](/en/model-config#environment-variables) override this for one session                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                        | `"claude-sonnet-5"`                                                                                                             |
| `modelOverrides`                  | Map Anthropic model IDs to provider-specific model IDs such as Bedrock inference profile ARNs. Each model picker entry uses its mapped value when calling the provider API. See [Override model IDs per version](/en/model-config#override-model-ids-per-version)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                 | `{"claude-opus-4-6": "arn:aws:bedrock:..."}`                                                                                    |
| `otelHeadersHelper`               | Script to generate dynamic OpenTelemetry headers. Runs at startup and periodically. Set the refresh interval with [`CLAUDE_CODE_OTEL_HEADERS_HELPER_DEBOUNCE_MS`](/en/env-vars). See [Dynamic headers](/en/monitoring-usage#dynamic-headers)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                      | `/bin/generate_otel_headers.sh`                                                                                                 |
| `outputStyle`                     | Configure an output style to adjust the system prompt. See [output styles documentation](/en/output-styles)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                       | `"Explanatory"`                                                                                                                 |
| `parentSettingsBehavior`          | {/* min-version: 2.1.133 */}(Managed settings only) **Default**: `"first-wins"`. Controls whether managed settings supplied programmatically by an embedding host process, such as the Agent SDK or an IDE extension, apply when an admin-deployed managed tier is also present. `"first-wins"`: the parent-supplied settings are dropped and only the admin tier applies. `"merge"`: the parent-supplied settings apply under the admin tier, filtered so they can tighten policy but not loosen it. Has no effect when no admin tier is deployed. Requires Claude Code v2.1.133 or later                                                                                                                                                                                                                                                        | `"merge"`                                                                                                                       |
| `permissions`                     | See table below for structure of permissions.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                     |                                                                                                                                 |
| `plansDirectory`                  | **Default**: `~/.claude/plans`. Customize where plan files are stored. Path is relative to project root.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                          | `"./plans"`                                                                                                                     |
| `pluginSuggestionMarketplaces`    | (Managed settings only) Marketplace names whose plugins can appear as contextual install suggestions. No marketplace-declared suggestions surface without this allowlist; the built-in first-party frontend-design tip is unaffected. Suggestions come from each plugin's `relevance` declaration in its marketplace entry. A name only takes effect when the marketplace is registered on the machine and its registered source is also declared in managed settings, either as the `extraKnownMarketplaces` entry for that name or as an entry of `strictKnownMarketplaces`. A marketplace registered from a different source under an allowlisted name is ignored. The official marketplace is exempt from the source requirement: allowlisting its name alone suffices, since that name can only register from the official Anthropic source. | `["acme-corp-plugins"]`                                                                                                         |
| `pluginTrustMessage`              | (Managed settings only) Custom message appended to the plugin trust warning shown before installation. Use this to add organization-specific context, for example to confirm that plugins from your internal marketplace are vetted.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                              | `"All plugins from our marketplace are approved by IT"`                                                                         |
| `policyHelper`                    | {/* min-version: 2.1.136 */}Admin-deployed executable that computes managed settings dynamically at startup. Only honored from MDM or a system `managed-settings.json` file. See [Compute managed settings with a policy helper](#compute-managed-settings-with-a-policy-helper). Requires Claude Code v2.1.136 or later                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                          | `{"path": "/usr/local/bin/claude-policy"}`                                                                                      |
| `preferredNotifChannel`           | **Default**: `"auto"`. Method for task-complete and permission-prompt notifications: `"auto"`, `"terminal_bell"`, `"iterm2"`, `"iterm2_with_bell"`, `"kitty"`, `"ghostty"`, or `"notifications_disabled"`. `"auto"` sends a desktop notification in iTerm2, Ghostty, and Kitty and does nothing in other terminals. Set `"terminal_bell"` to ring the bell character in any terminal. Appears in `/config` as **Notifications**. See [Get a terminal bell or notification](/en/terminal-config#get-a-terminal-bell-or-notification)                                                                                                                                                                                                                                                                                                               | `"terminal_bell"`                                                                                                               |
| `prefersReducedMotion`            | Reduce or disable UI animations (spinners, shimmer, flash effects) for accessibility                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                              | `true`                                                                                                                          |
| `prUrlTemplate`                   | URL template for the PR badge shown in the footer and in tool-result summaries. Substitutes `{host}`, `{owner}`, `{repo}`, `{number}`, and `{url}` from the `gh`-reported PR URL. Use to point PR links at an internal code-review tool instead of `github.com`. Does not affect `#123` autolinks in Claude's prose                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                               | `"https://reviews.example.com/{owner}/{repo}/pull/{number}"`                                                                    |
| `remoteControlAtStartup`          | {/* min-version: 2.1.119 */}Connect [Remote Control](/en/remote-control) automatically when each interactive session starts, instead of waiting for `/remote-control`. Set to `true` to always auto-connect, `false` to never auto-connect, or leave unset to follow your organization's default. Appears in `/config` as **Enable Remote Control for all sessions**. See [Enable Remote Control for all sessions](/en/remote-control#enable-remote-control-for-all-sessions)                                                                                                                                                                                                                                                                                                                                                                     | `false`                                                                                                                         |
| `requiredMaximumVersion`          | Managed settings only. Maximum Claude Code version allowed to start. If the running version is newer, Claude Code exits at startup and instructs the user to install an approved version through the organization's approved method; `claude install <version>` may also work. Background auto-updates and `claude update` skip versions above the ceiling, so an in-range installation stays in range. `claude update`, `claude install`, and `claude doctor` keep working above the ceiling so users can recover. Versions that predate this setting ignore it                                                                                                                                                                                                                                                                                  | `"2.1.150"`                                                                                                                     |
| `requiredMinimumVersion`          | Managed settings only. Minimum Claude Code version required to start. If the running version is older, Claude Code exits at startup and instructs the user to update through the organization's approved method. `claude update`, `claude install`, and `claude doctor` keep working below the floor so users can recover. Differs from `minimumVersion`, which prevents downgrades but never blocks startup. Versions that predate this setting ignore it                                                                                                                                                                                                                                                                                                                                                                                        | `"2.1.150"`                                                                                                                     |
| `respectGitignore`                | **Default**: `true`. Control whether the `@` file picker respects `.gitignore` patterns. When `true`, files matching `.gitignore` patterns are excluded from suggestions                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                          | `false`                                                                                                                         |
| `respondToBashCommands`           | {/* min-version: 2.1.186 */}**Default**: `true`. Whether Claude responds after an input-box `!` shell command runs. Set to `false` to add the command output to context without a response. See [Shell mode with `!` prefix](/en/interactive-mode#shell-mode-with-prefix). Requires Claude Code v2.1.186 or later                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                 | `false`                                                                                                                         |
| `showClearContextOnPlanAccept`    | **Default**: `false`. Show the "clear context" option on the plan accept screen. Set to `true` to restore the option                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                              | `true`                                                                                                                          |
| `showThinkingSummaries`           | **Default**: `false`. Show [extended thinking](/en/model-config#extended-thinking) summaries in interactive sessions. When unset or `false`, thinking blocks are redacted by the API and shown as a collapsed stub. Redaction only changes what you see, not what the model generates: to reduce thinking spend, [lower the budget or disable thinking](/en/model-config#extended-thinking) instead. This setting has no effect in non-interactive mode (`-p`), the Agent SDK, or IDE extensions such as VS Code                                                                                                                                                                                                                                                                                                                                  | `true`                                                                                                                          |
| `showTurnDuration`                | **Default**: `true`. Show turn duration messages after responses, e.g. "Cooked for 1m 6s". Appears in `/config` as **Show turn duration**                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                         | `false`                                                                                                                         |
| `skillListingBudgetFraction`      | {/* min-version: 2.1.105 */}**Default**: `0.01` (1%). Fraction of the model's context window reserved for the [skill listing](/en/skills#skill-descriptions-are-cut-short) Claude sees each turn. When the listing exceeds the budget, descriptions for the least-used skills are collapsed to bare names so Claude can still invoke them but won't see why. Raise to keep more descriptions visible at the cost of more context per turn. `/doctor` shows the current truncation count and which skills are affected. Requires Claude Code v2.1.105 or later                                                                                                                                                                                                                                                                                     | `0.02`                                                                                                                          |
| `skillListingMaxDescChars`        | {/* min-version: 2.1.105 */}**Default**: `1536`. Per-skill character cap on the combined `description` and `when_to_use` text in the [skill listing](/en/skills#skill-descriptions-are-cut-short) Claude sees each turn. Text longer than this is truncated. Raise to keep long descriptions intact at the cost of more context per turn; lower to fit more skills under [`skillListingBudgetFraction`](#available-settings). Requires Claude Code v2.1.105 or later                                                                                                                                                                                                                                                                                                                                                                              | `2048`                                                                                                                          |
| `skillOverrides`                  | {/* min-version: 2.1.129 */}Per-skill visibility overrides keyed by skill name. Value is `"on"`, `"name-only"`, `"user-invocable-only"`, or `"off"`. Lets you hide or collapse a skill without editing its SKILL.md. Does not apply to plugin skills, which are managed through `/plugin`. The `/skills` menu writes these to `.claude/settings.local.json`. See [Override skill visibility from settings](/en/skills#override-skill-visibility-from-settings). Requires Claude Code v2.1.129 or later                                                                                                                                                                                                                                                                                                                                            | `{"legacy-context": "name-only", "deploy": "off"}`                                                                              |
| `skipWebFetchPreflight`           | Skip the [WebFetch domain safety check](/en/data-usage#webfetch-domain-safety-check) that sends each requested hostname to `api.anthropic.com` before fetching. Set to `true` in environments that block traffic to Anthropic, such as Bedrock, Vertex AI, or Foundry deployments with restrictive egress. When skipped, WebFetch attempts any URL without consulting the blocklist                                                                                                                                                                                                                                                                                                                                                                                                                                                               | `true`                                                                                                                          |
| `spinnerTipsEnabled`              | **Default**: `true`. Show tips in the spinner while Claude is working. Set to `false` to disable tips                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                             | `false`                                                                                                                         |
| `spinnerTipsOverride`             | Override spinner tips with custom strings. `tips`: array of tip strings. `excludeDefault`: if `true`, only show custom tips; if `false` or absent, custom tips are merged with built-in tips                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                      | `{ "excludeDefault": true, "tips": ["Use our internal tool X"] }`                                                               |
| `spinnerVerbs`                    | Customize the action verbs shown while a turn is in progress. Set `mode` to `"replace"` to use only your verbs, or `"append"` to add them to the defaults                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                         | `{"mode": "append", "verbs": ["Pondering", "Crafting"]}`                                                                        |
| `sshConfigs`                      | SSH connections to show in the [Desktop](/en/desktop#pre-configure-ssh-connections-for-your-team) environment dropdown. Each entry requires `id`, `name`, and `sshHost`; `sshPort`, `sshIdentityFile`, and `startDirectory` are optional. When set in managed settings, connections are read-only for users. Read from managed and user settings only                                                                                                                                                                                                                                                                                                                                                                                                                                                                                             | `[{"id": "dev-vm", "name": "Dev VM", "sshHost": "user@dev.example.com"}]`                                                       |
| `statusLine`                      | Configure a custom status line to display context. The object's optional `padding`, `refreshInterval`, and `hideVimModeIndicator` fields control spacing, periodic re-runs, and whether the built-in vim mode indicator below the prompt is hidden. See [`statusLine` documentation](/en/statusline#manually-configure-a-status-line)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                             | `{"type": "command", "command": "~/.claude/statusline.sh"}`                                                                     |
| `strictKnownMarketplaces`         | (Managed settings only) Allowlist of plugin marketplace sources. Undefined = no restrictions, empty array = lockdown. Enforced on marketplace add and on plugin install, update, refresh, and auto-update, so a marketplace added before the policy was set cannot be used to fetch plugins. See [Managed marketplace restrictions](/en/plugin-marketplaces#managed-marketplace-restrictions)                                                                                                                                                                                                                                                                                                                                                                                                                                                     | `[{ "source": "github", "repo": "acme-corp/plugins" }]`                                                                         |
| `strictPluginOnlyCustomization`   | (Managed settings only) Block skills, agents, hooks, and MCP servers from user and project sources, so they can only come from plugins or managed settings. `true` locks all four surfaces; an array locks only the named ones. See [`strictPluginOnlyCustomization`](#strictpluginonlycustomization)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                             | `["skills", "hooks"]`                                                                                                           |
| `syntaxHighlightingDisabled`      | Disable syntax highlighting in diffs, code blocks, and file previews                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                              | `true`                                                                                                                          |
| `teammateMode`                    | **Default**: `in-process`. How [agent team](/en/agent-teams) teammates display: `in-process`, `auto` (split panes when running inside tmux, or inside iTerm2 with `it2` on your `PATH`; in-process otherwise), `tmux` (split panes using tmux or iTerm2, detected from your terminal), or {/* min-version: 2.1.186 */}`iterm2` (iTerm2 native split panes via the `it2` CLI, added in v2.1.186). The default changed from `auto` in v2.1.179. `--teammate-mode` overrides this for one session. See [choose a display mode](/en/agent-teams#choose-a-display-mode)                                                                                                                                                                                                                                                                                | `"auto"`                                                                                                                        |
| `terminalProgressBarEnabled`      | **Default**: `true`. Show the terminal progress bar in supported terminals: ConEmu, Ghostty 1.2.0+, and iTerm2 3.6.6+. Appears in `/config` as **Terminal progress bar**                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                          | `false`                                                                                                                         |
| `theme`                           | {/* min-version: 2.1.119 */}**Default**: `"dark"`. Color theme for the interface: `"auto"`, `"dark"`, `"light"`, `"dark-daltonized"`, `"light-daltonized"`, `"dark-ansi"`, `"light-ansi"`, or a custom theme reference such as `"custom:<slug>"` or `"custom:<plugin-name>:<slug>"`. See [Create a custom theme](/en/terminal-config#create-a-custom-theme). Appears in `/config` as **Theme**                                                                                                                                                                                                                                                                                                                                                                                                                                                    | `"dark"`                                                                                                                        |
| `tui`                             | Terminal UI renderer. Use `"fullscreen"` for the flicker-free [alt-screen renderer](/en/fullscreen) with virtualized scrollback. Use `"default"` for the classic main-screen renderer. Set via `/tui`. You can also set the [`CLAUDE_CODE_NO_FLICKER`](/en/env-vars) environment variable. Background sessions opened from [agent view](/en/agent-view) always use the fullscreen renderer regardless of this setting                                                                                                                                                                                                                                                                                                                                                                                                                             | `"fullscreen"`                                                                                                                  |
| `ultracode`                       | Turn on [ultracode](/en/workflows#let-claude-decide-with-ultracode) for the session. Session-only and not read from `settings.json`. Set through `/effort ultracode`, `--settings`, or an Agent SDK control request                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                               | `true`                                                                                                                          |
| `useAutoModeDuringPlan`           | **Default**: `true`. Whether plan mode uses auto mode semantics when auto mode is available. Not read from shared project settings. Appears in `/config` as "Use auto mode during plan"                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                           | `false`                                                                                                                         |
| `verbose`                         | {/* min-version: 2.1.119 */}**Default**: `false`. Show full tool output instead of truncated summaries. Appears in `/config` as **Verbose output**. The `--verbose` flag overrides this for one session                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                           | `true`                                                                                                                          |
| `viewMode`                        | Default transcript view mode on startup: `"default"`, `"verbose"`, or `"focus"`. Overrides the sticky `/focus` selection when set. The `--verbose` flag overrides this for one session                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                            | `"verbose"`                                                                                                                     |
| `voice`                           | [Voice dictation](/en/voice-dictation) settings: `enabled` turns dictation on, `mode` selects `"hold"` or `"tap"`, and `autoSubmit` sends the prompt on key release in hold mode. Written automatically when you run `/voice`. Requires a Claude.ai account                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                       | `{ "enabled": true, "mode": "tap" }`                                                                                            |
| `voiceEnabled`                    | Legacy alias for `voice.enabled`. Prefer the `voice` object                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                       | `true`                                                                                                                          |
| `wheelScrollAccelerationEnabled`  | {/* min-version: 2.1.174 */}**Default**: `true`. In [fullscreen rendering](/en/fullscreen#mouse-wheel-scrolling), accelerate mouse-wheel scroll speed during fast scrolls. Set to `false` for a constant scroll rate per wheel notch. Requires Claude Code v2.1.174 or later                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                      | `false`                                                                                                                         |
| `workflowKeywordTriggerEnabled`   | {/* min-version: 2.1.157 */}**Default**: `true`. Whether the keyword `ultracode` in a prompt triggers a [dynamic workflow](/en/workflows#ask-for-a-workflow-in-your-prompt). Set to `false` to type the word without triggering one. The `ultracode` effort setting, `/workflows`, and saved workflow commands are unaffected. Appears in `/config` as **Ultracode keyword trigger**. Added in v2.1.157; before v2.1.160 the trigger keyword was `workflow`                                                                                                                                                                                                                                                                                                                                                                                       | `false`                                                                                                                         |
| `wslInheritsWindowsSettings`      | (Windows managed settings only) When `true`, Claude Code on WSL reads managed settings from the Windows policy chain in addition to `/etc/claude-code`, with Windows sources taking priority. Only honored when set in the HKLM registry key or `C:\Program Files\ClaudeCode\managed-settings.json`, both of which require Windows admin to write. For HKCU policy to also apply on WSL, the flag must additionally be set in HKCU itself. Has no effect on native Windows                                                                                                                                                                                                                                                                                                                                                                        | `true`                                                                                                                          |

### Global config settings

These settings are stored in `~/.claude.json` rather than `settings.json`. Adding them to `settings.json` will trigger a schema validation error.

<Note>
  Versions before v2.1.119 also store a number of `/config` preference keys here instead of in `settings.json`, including `theme`, `verbose`, `editorMode`, `autoCompactEnabled`, and `preferredNotifChannel`.
</Note>

| Key                       | Description                                                                                                                                                                                                                                                                                                                               | Example    |
| :------------------------ | :---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | :--------- |
| `autoConnectIde`          | **Default**: `false`. Automatically connect to a running IDE when Claude Code starts from an external terminal. Appears in `/config` as **Auto-connect to IDE (external terminal)** when running outside a VS Code or JetBrains terminal. The [`CLAUDE_CODE_AUTO_CONNECT_IDE`](/en/env-vars) environment variable overrides this when set | `true`     |
| `autoInstallIdeExtension` | **Default**: `true`. Automatically install the Claude Code IDE extension when running from a VS Code terminal. Appears in `/config` as **Auto-install IDE extension** when running inside a VS Code or JetBrains terminal. You can also set the [`CLAUDE_CODE_IDE_SKIP_AUTO_INSTALL`](/en/env-vars) environment variable to `1`           | `false`    |
| `externalEditorContext`   | **Default**: `false`. Prepend Claude's previous response as `#`-commented context when you open the external editor with `Ctrl+G`. Appears in `/config` as **Show last response in external editor**                                                                                                                                      | `true`     |
| `teammateDefaultModel`    | Default model for [agent team](/en/agent-teams) teammates when the spawn prompt doesn't specify one. Set to a model alias such as `"sonnet"`, or `null` to inherit the lead's current `/model` selection. Appears in `/config` as **Default teammate model**                                                                              | `"sonnet"` |

### Worktree settings

Configure how `--worktree` creates and manages git worktrees.

| Key                           | Description                                                                                                                                                                                                                                                                                                                                  | Example                               |
| :---------------------------- | :------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | :------------------------------------ |
| `worktree.baseRef`            | Which ref new worktrees branch from. `"fresh"` (default) branches from `origin/<default-branch>` for a clean tree matching the remote. `"head"` branches from your current local `HEAD`, so unpushed commits and feature-branch state are present in the worktree. Applies to `--worktree`, the `EnterWorktree` tool, and subagent isolation | `"head"`                              |
| `worktree.symlinkDirectories` | Directories to symlink from the main repository into each worktree to avoid duplicating large directories on disk. No directories are symlinked by default                                                                                                                                                                                   | `["node_modules", ".cache"]`          |
| `worktree.sparsePaths`        | Directories to check out in each worktree via git sparse-checkout. Only the listed directories plus root-level files are written to disk, which is faster in large monorepos                                                                                                                                                                 | `["packages/my-app", "shared/utils"]` |
| `worktree.bgIsolation`        | {/* min-version: 2.1.143 */}Isolation mode for [background sessions](/en/agent-view#how-file-edits-are-isolated). `"worktree"` (default) blocks `Edit`/`Write` in the main checkout until `EnterWorktree` is called. `"none"` lets background jobs edit the working copy directly. Requires Claude Code v2.1.143 or later                    | `"none"`                              |

To copy gitignored files like `.env` into new worktrees, use a [`.worktreeinclude` file](/en/worktrees#copy-gitignored-files-into-worktrees) in your project root instead of a setting.

### Permission settings

| Keys                                | Description                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                         | Example                                                                |
| :---------------------------------- | :------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | :--------------------------------------------------------------------- |
| `allow`                             | Array of permission rules to allow tool use. Tool-name globs are supported only in the tool position after a literal `mcp__<server>__` prefix, such as `mcp__github__get_*`; the server segment must be glob-free. See [Permission rule syntax](#permission-rule-syntax) below for pattern matching details                                                                                                                                                                                                         | `[ "Bash(git diff *)" ]`                                               |
| `ask`                               | Array of permission rules to ask for confirmation upon tool use. See [Permission rule syntax](#permission-rule-syntax) below                                                                                                                                                                                                                                                                                                                                                                                        | `[ "Bash(git push *)" ]`                                               |
| `deny`                              | Array of permission rules to deny tool use. Use this to exclude sensitive files from Claude Code access. Tool names accept glob patterns: `"*"` denies every tool and `"mcp__*"` denies all MCP tools. See [Permission rule syntax](#permission-rule-syntax) and [Bash permission limitations](/en/permissions#tool-specific-permission-rules)                                                                                                                                                                      | `[ "WebFetch", "Bash(curl *)", "Read(./.env)", "Read(./secrets/**)" ]` |
| `additionalDirectories`             | Additional [working directories](/en/permissions#working-directories) for file access. Most `.claude/` configuration is [not discovered](/en/permissions#additional-directories-grant-file-access-not-configuration) from these directories                                                                                                                                                                                                                                                                         | `[ "../docs/" ]`                                                       |
| `defaultMode`                       | Default [permission mode](/en/permission-modes) when opening Claude Code. Valid values: `default`, `acceptEdits`, `plan`, `auto`, `dontAsk`, `bypassPermissions`. {/* min-version: 2.1.142 */}As of Claude Code v2.1.142, `auto` is ignored when set in project or local settings (`.claude/settings.json`, `.claude/settings.local.json`) so a repository cannot grant itself auto mode. Set it in `~/.claude/settings.json` instead. The `--permission-mode` CLI flag overrides this setting for a single session | `"acceptEdits"`                                                        |
| `disableBypassPermissionsMode`      | Set to `"disable"` to prevent `bypassPermissions` mode from being activated. This disables the `--dangerously-skip-permissions` command-line flag. Typically placed in [managed settings](/en/permissions#managed-settings) to enforce organizational policy, but works from any scope                                                                                                                                                                                                                              | `"disable"`                                                            |
| `skipDangerousModePermissionPrompt` | Skip the confirmation prompt shown before entering bypass permissions mode via `--dangerously-skip-permissions` or `defaultMode: "bypassPermissions"`. Ignored when set in project settings (`.claude/settings.json`) to prevent untrusted repositories from auto-bypassing the prompt                                                                                                                                                                                                                              | `true`                                                                 |

### Permission rule syntax

Permission rules follow the format `Tool` or `Tool(specifier)`. Rules are evaluated in order: deny rules first, then ask, then allow. The first match determines the outcome regardless of rule specificity. See the [permission rule evaluation order](/en/permissions#manage-permissions) for details.

Quick examples:

| Rule                           | Effect                                   |
| :----------------------------- | :--------------------------------------- |
| `Bash`                         | Matches all Bash commands                |
| `Bash(npm run *)`              | Matches commands starting with `npm run` |
| `Read(./.env)`                 | Matches reading the `.env` file          |
| `WebFetch(domain:example.com)` | Matches fetch requests to example.com    |

For the complete rule syntax reference, including wildcard behavior, tool-specific patterns for Read, Edit, WebFetch, MCP, and Agent rules, and security limitations of Bash patterns, see [Permission rule syntax](/en/permissions#permission-rule-syntax).

### Sandbox settings

Configure advanced sandboxing behavior. Sandboxing isolates bash commands from your filesystem and network. See [Sandboxing](/en/sandboxing) for details.

| Keys                                   | Description                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                      | Example                                              |
| :------------------------------------- | :----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | :--------------------------------------------------- |
| `enabled`                              | Enable bash sandboxing (macOS, Linux, and WSL2). Default: false                                                                                                                                                                                                                                                                                                                                                                                                                                                                                  | `true`                                               |
| `failIfUnavailable`                    | Exit with an error at startup if `sandbox.enabled` is true but the sandbox cannot start (missing dependencies or unsupported platform). When false (default), a warning is shown and commands run unsandboxed. Intended for managed settings deployments that require sandboxing as a hard gate                                                                                                                                                                                                                                                  | `true`                                               |
| `autoAllowBashIfSandboxed`             | Auto-approve bash commands when sandboxed. Default: true                                                                                                                                                                                                                                                                                                                                                                                                                                                                                         | `true`                                               |
| `excludedCommands`                     | Commands that should run outside of the sandbox                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                  | `["docker *"]`                                       |
| `allowUnsandboxedCommands`             | Allow commands to run outside the sandbox via the `dangerouslyDisableSandbox` parameter. When set to `false`, the `dangerouslyDisableSandbox` escape hatch is completely disabled and all commands must run sandboxed (or be in `excludedCommands`). Useful for enterprise policies that require strict sandboxing. Default: true                                                                                                                                                                                                                | `false`                                              |
| `filesystem.allowWrite`                | Additional paths where sandboxed commands can write. Arrays are merged across all settings scopes: user, project, and managed paths are combined, not replaced. Also merged with paths from `Edit(...)` allow permission rules. See [path prefixes](#sandbox-path-prefixes) below.                                                                                                                                                                                                                                                               | `["/tmp/build", "~/.kube"]`                          |
| `filesystem.denyWrite`                 | Paths where sandboxed commands cannot write. Arrays are merged across all settings scopes. Also merged with paths from `Edit(...)` deny permission rules.                                                                                                                                                                                                                                                                                                                                                                                        | `["/etc", "/usr/local/bin"]`                         |
| `filesystem.denyRead`                  | Paths where sandboxed commands cannot read. Arrays are merged across all settings scopes. Also merged with paths from `Read(...)` deny permission rules.                                                                                                                                                                                                                                                                                                                                                                                         | `["~/.aws/credentials"]`                             |
| `filesystem.allowRead`                 | Paths to re-allow reading within `denyRead` regions. Takes precedence over `denyRead`. Arrays are merged across all settings scopes. Use this to create workspace-only read access patterns.                                                                                                                                                                                                                                                                                                                                                     | `["."]`                                              |
| `filesystem.allowManagedReadPathsOnly` | (Managed settings only) Only `filesystem.allowRead` paths from managed settings are respected. `denyRead` still merges from all sources. Default: false                                                                                                                                                                                                                                                                                                                                                                                          | `true`                                               |
| `credentials.files`                    | Credential files or directories that sandboxed commands cannot read. Applies the same read block as `filesystem.denyRead`; the separate key keeps credential paths grouped with `credentials.envVars` and apart from general filesystem rules. Each entry is `{ "path": "...", "mode": "deny" }`. Paths use the same [prefixes](#sandbox-path-prefixes) as `filesystem.*` settings. Arrays are merged across all settings scopes. Only `deny` is supported. Requires Claude Code v2.1.187 or later.                                              | `[{ "path": "~/.aws/credentials", "mode": "deny" }]` |
| `credentials.envVars`                  | Environment variables to unset before running sandboxed commands. Each entry is `{ "name": "...", "mode": "deny" }`. Arrays are merged across all settings scopes. Only `deny` is supported. Requires Claude Code v2.1.187 or later.                                                                                                                                                                                                                                                                                                             | `[{ "name": "GITHUB_TOKEN", "mode": "deny" }]`       |
| `network.allowUnixSockets`             | (macOS only) Unix socket paths accessible in sandbox. Ignored on Linux and WSL2, where the seccomp filter cannot inspect socket paths; use `allowAllUnixSockets` instead.                                                                                                                                                                                                                                                                                                                                                                        | `["~/.ssh/agent-socket"]`                            |
| `network.allowAllUnixSockets`          | Allow all Unix socket connections in sandbox. On Linux and WSL2 this is the only way to permit Unix sockets, since it skips the seccomp filter that otherwise blocks `socket(AF_UNIX, ...)` calls. Default: false                                                                                                                                                                                                                                                                                                                                | `true`                                               |
| `network.allowLocalBinding`            | Allow binding to localhost ports (macOS only). Default: false                                                                                                                                                                                                                                                                                                                                                                                                                                                                                    | `true`                                               |
| `network.allowMachLookup`              | Additional XPC/Mach service names the sandbox may look up (macOS only). Supports a single trailing `*` for prefix matching. Needed for tools that communicate via XPC such as the iOS Simulator or Playwright.                                                                                                                                                                                                                                                                                                                                   | `["com.apple.coresimulator.*"]`                      |
| `network.allowedDomains`               | Array of domains to allow for outbound network traffic. Supports wildcards (e.g., `*.example.com`).                                                                                                                                                                                                                                                                                                                                                                                                                                              | `["github.com", "*.npmjs.org"]`                      |
| `network.deniedDomains`                | Array of domains to block for outbound network traffic. Supports the same wildcard syntax as `allowedDomains`. Takes precedence over `allowedDomains` when both match. Merged from all settings sources regardless of `allowManagedDomainsOnly`.                                                                                                                                                                                                                                                                                                 | `["sensitive.cloud.example.com"]`                    |
| `network.allowManagedDomainsOnly`      | (Managed settings only) Only `allowedDomains` and `WebFetch(domain:...)` allow rules from managed settings are respected. Domains from user, project, and local settings are ignored. Non-allowed domains are blocked automatically without prompting the user. Denied domains are still respected from all sources. Default: false                                                                                                                                                                                                              | `true`                                               |
| `network.httpProxyPort`                | HTTP proxy port used if you wish to bring your own proxy. If not specified, Claude will run its own proxy.                                                                                                                                                                                                                                                                                                                                                                                                                                       | `8080`                                               |
| `network.socksProxyPort`               | SOCKS5 proxy port used if you wish to bring your own proxy. If not specified, Claude will run its own proxy.                                                                                                                                                                                                                                                                                                                                                                                                                                     | `8081`                                               |
| `enableWeakerNestedSandbox`            | Enable weaker sandbox for unprivileged Docker environments (Linux and WSL2 only). **Reduces security.** Default: false                                                                                                                                                                                                                                                                                                                                                                                                                           | `true`                                               |
| `enableWeakerNetworkIsolation`         | (macOS only) Allow access to the system TLS trust service (`com.apple.trustd.agent`) in the sandbox. Required for Go-based tools like `gh`, `gcloud`, and `terraform` to verify TLS certificates when using `httpProxyPort` with a MITM proxy and custom CA. **Reduces security** by opening a potential data exfiltration path. Default: false                                                                                                                                                                                                  | `true`                                               |
| `allowAppleEvents`                     | (macOS only) Allow sandboxed commands to send Apple Events. Required for `open`, `osascript`, and tools that open URLs in a browser, which otherwise fail with error `-600`. **Removes code-execution isolation.** Sandboxed commands can launch other applications unsandboxed with no user prompt; they can also send AppleScript commands to running applications such as Terminal, subject to the per-app macOS automation-consent prompt (TCC). Only honored from user, managed, or CLI settings, not from project settings. Default: false | `true`                                               |
| `bwrapPath`                            | (Managed settings only, Linux/WSL2) Absolute path to the bubblewrap (`bwrap`) binary. Overrides automatic detection via `PATH`. Only honored from [managed settings](/en/settings#settings-precedence), not from user or project settings. Useful when `bwrap` is installed at a non-standard location in managed environments.                                                                                                                                                                                                                  | `/opt/admin/bwrap`                                   |
| `socatPath`                            | (Managed settings only, Linux/WSL2) Absolute path to the `socat` binary used for the sandbox network proxy. Overrides automatic detection via `PATH`. Only honored from managed settings.                                                                                                                                                                                                                                                                                                                                                        | `/opt/admin/socat`                                   |

#### Sandbox path prefixes

Paths in `filesystem.allowWrite`, `filesystem.denyWrite`, `filesystem.denyRead`, `filesystem.allowRead`, and `credentials.files` support these prefixes:

| Prefix            | Meaning                                                                                | Example                                                                   |
| :---------------- | :------------------------------------------------------------------------------------- | :------------------------------------------------------------------------ |
| `/`               | Absolute path from filesystem root                                                     | `/tmp/build` stays `/tmp/build`                                           |
| `~/`              | Relative to home directory                                                             | `~/.kube` becomes `$HOME/.kube`                                           |
| `./` or no prefix | Relative to the project root for project settings, or to `~/.claude` for user settings | `./output` in `.claude/settings.json` resolves to `<project-root>/output` |

The older `//path` prefix for absolute paths still works. If you previously used single-slash `/path` expecting project-relative resolution, switch to `./path`.

This syntax differs from [Read and Edit permission rules](/en/permissions#read-and-edit), which use `//path` for absolute and `/path` for project-relative. Sandbox filesystem paths use standard conventions: `/tmp/build` is an absolute path.

**Configuration example:**

```json theme={null}
{
  "sandbox": {
    "enabled": true,
    "autoAllowBashIfSandboxed": true,
    "excludedCommands": ["docker *"],
    "filesystem": {
      "allowWrite": ["/tmp/build", "~/.kube"],
      "denyRead": ["~/.aws/credentials"]
    },
    "network": {
      "allowedDomains": ["github.com", "*.npmjs.org", "registry.yarnpkg.com"],
      "deniedDomains": ["uploads.github.com"],
      "allowUnixSockets": [
        "/var/run/docker.sock"
      ],
      "allowLocalBinding": true
    }
  }
}
```

**Filesystem and network restrictions** can be configured in two ways that are merged together:

* **`sandbox.filesystem` settings** (shown above): Control paths at the OS-level sandbox boundary. These restrictions apply to all subprocess commands (e.g., `kubectl`, `terraform`, `npm`), not just Claude's file tools.
* **Permission rules**: Use `Edit` allow/deny rules to control Claude's file tool access, `Read` deny rules to block reads, and `WebFetch` allow/deny rules to control network domains. Paths from these rules are also merged into the sandbox configuration.

### Attribution settings

Claude Code adds attribution to git commits and pull requests. These are configured separately:

* Commits use [git trailers](https://git-scm.com/docs/git-interpret-trailers) (like `Co-Authored-By`) by default,  which can be customized or disabled
* Pull request descriptions are plain text

| Keys         | Description                                                                                                                                                                                                                          |
| :----------- | :----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `commit`     | Attribution for git commits, including any trailers. Empty string hides commit attribution                                                                                                                                           |
| `pr`         | Attribution for pull request descriptions. Empty string hides pull request attribution                                                                                                                                               |
| `sessionUrl` | Whether to append the claude.ai session link as a `Claude-Session` trailer on commits and a link in pull request descriptions when running from a web or Remote Control session. Defaults to `true`. Set to `false` to omit the link |

**Default commit attribution:**

```text theme={null}
Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
```

The model name in the trailer reflects the active model for the session.

**Default pull request attribution:**

```text theme={null}
🤖 Generated with [Claude Code](https://claude.com/claude-code)
```

**Example:**

```json theme={null}
{
  "attribution": {
    "commit": "Generated with AI\n\nCo-Authored-By: AI <ai@example.com>",
    "pr": ""
  }
}
```

<Note>
  The `attribution` setting takes precedence over the deprecated `includeCoAuthoredBy` setting. To hide all attribution, set `commit` and `pr` to empty strings and `sessionUrl` to `false`.
</Note>

### File suggestion settings

Configure a custom command for `@` file path autocomplete. The built-in file suggestion uses fast filesystem traversal, but large monorepos may benefit from project-specific indexing such as a pre-built file index or custom tooling.

```json theme={null}
{
  "fileSuggestion": {
    "type": "command",
    "command": "~/.claude/file-suggestion.sh"
  }
}
```

The command runs with the same environment variables as [hooks](/en/hooks), including `CLAUDE_PROJECT_DIR`. It receives JSON via stdin with a `query` field:

```json theme={null}
{"query": "src/comp"}
```

Output newline-separated file paths to stdout (currently limited to 15):

```text theme={null}
src/components/Button.tsx
src/components/Modal.tsx
src/components/Form.tsx
```

**Example:**

```bash theme={null}
#!/bin/bash
query=$(cat | jq -r '.query')
# Replace your-repo-file-index with your own file search command
your-repo-file-index --query "$query" | head -20
```

### Footer link badges

The `footerLinksRegexes` setting renders extra clickable badges in the footer below the input box. Use it to turn IDs printed by project CLIs, such as review tools and issue trackers, into session links.

Each entry's `pattern` regex is matched against turn output: tool results, including file contents and fetched pages, and Claude's own responses. `{name}` placeholders in `url` and `label` are filled from named capture groups in the pattern.

The following example renders a badge whenever an issue key like `PROJ-1234` appears in turn output. The `(?<key>...)` named group captures the key, and `{key}` substitutes it into the URL and label:

```json ~/.claude/settings.json theme={null}
{
  "footerLinksRegexes": [
    {
      "type": "regex",
      "pattern": "\\b(?<key>PROJ-\\d+)\\b",
      "url": "https://issues.example.com/browse/{key}",
      "label": "{key}"
    }
  ]
}
```

With this configured, when `PROJ-1234` appears in a tool result or in Claude's reply, a `PROJ-1234` badge appears in the footer linking to `https://issues.example.com/browse/PROJ-1234`.

The following constraints apply to each entry:

| Constraint     | Behavior                                                                                                                                                                                           |
| :------------- | :------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| URL origin     | Captured values are URL-encoded and the constructed URL must share the template's literal origin. A capture can fill a path segment or query value but cannot change where the link points         |
| URL length     | Constructed URLs longer than 2048 characters are dropped                                                                                                                                           |
| URL scheme     | Must be `https`, `http`, or a recognized editor or workspace deep-link scheme: `vscode`, `vscode-insiders`, `cursor`, `windsurf`, `zed`, `jetbrains`, `idea`, `slack`, `linear`, `notion`, `figma` |
| Label          | Defaults to the matched text and is truncated to 28 display columns                                                                                                                                |
| Badge count    | At most 5 badges render. The oldest is displaced by newer matches and `/clear` removes them                                                                                                        |
| Settings scope | Read from user settings, the `--settings` flag, and managed settings only. Ignored in project `.claude/settings.json` and local `.claude/settings.local.json`                                      |

When a turn completes, Claude Code matches each entry's `pattern` regex against the turn output on the main thread, so a slow regex blocks the UI until it finishes. Nested quantifiers such as `(a+)+$` can take exponentially long against certain inputs and freeze the session, so keep each `pattern` linear and avoid nesting `+` or `*`.

Footer badges render alongside a [custom status line](/en/statusline) when one is configured; neither replaces the other. Use a status line for a script-driven row that computes its own content from session data, and footer badges to turn IDs from the conversation into links without a script.

### Hook configuration

These settings control which hooks are allowed to run and what HTTP hooks can access. The `allowManagedHooksOnly` setting can only be configured in [managed settings](#settings-files). The URL and env var allowlists can be set at any settings level and merge across sources.

**Behavior when `allowManagedHooksOnly` is `true`:**

* Managed hooks and SDK hooks are loaded
* Hooks from plugins force-enabled in managed settings `enabledPlugins` are loaded. This lets administrators distribute vetted hooks through an organization marketplace while blocking everything else. Trust is granted by full `plugin@marketplace` ID, so a plugin with the same name from a different marketplace stays blocked
* User hooks, project hooks, and all other plugin hooks are blocked

**Restrict HTTP hook URLs:**

Limit which URLs HTTP hooks can target. Supports `*` as a wildcard for matching. When the array is defined, HTTP hooks targeting non-matching URLs are silently blocked. Hostname matching is case-insensitive and ignores a trailing FQDN dot, matching DNS semantics.

```json theme={null}
{
  "allowedHttpHookUrls": ["https://hooks.example.com/*", "http://localhost:*"]
}
```

**Restrict HTTP hook environment variables:**

Limit which environment variable names HTTP hooks can interpolate into header values. Each hook's effective `allowedEnvVars` is the intersection of its own list and this setting.

```json theme={null}
{
  "httpHookAllowedEnvVars": ["MY_TOKEN", "HOOK_SECRET"]
}
```

### Compute managed settings with a policy helper

The `policyHelper` setting points at an executable that computes managed settings at startup, so admins can derive policy from device posture, identity, or a remote service instead of a static file. Configure it from MDM or a system `managed-settings.json` file. Claude Code ignores `policyHelper` when it appears in any other scope, including user settings, project settings, the HKCU registry hive, and [server-managed settings](/en/server-managed-settings).

The setting accepts these keys:

| Key                 | Type   | Description                                                                                             |
| ------------------- | ------ | ------------------------------------------------------------------------------------------------------- |
| `path`              | string | Absolute path to the helper executable                                                                  |
| `timeoutMs`         | number | How long to wait for the helper before treating the run as failed                                       |
| `refreshIntervalMs` | number | How often to re-run the helper in the background. Set to `0` to disable refresh, or to at least `60000` |

The helper writes a JSON envelope to stdout. Put the settings under a `managedSettings` key rather than at the top level, since a bare settings object parses with `managedSettings` undefined and applies nothing:

```json theme={null}
{
  "managedSettings": {
    "permissions": { "deny": ["Read(//etc/secrets/**)"] }
  },
  "claudeMd": "# Organization context\n...",
  "appendSystemPrompt": "Always cite the internal style guide."
}
```

When the helper emits `managedSettings`, that object becomes the only managed settings source for the run, taking precedence over remote, MDM, and file-based sources. When the helper exits non-zero at startup, Claude Code prints the error and refuses to start, so a helper that needs outage resilience should serve from its own cache and exit `0`.

### Settings precedence

Settings apply in order of precedence. From highest to lowest:

1. **Managed settings** ([server-managed](/en/server-managed-settings), [MDM/OS-level policies](#configuration-scopes), or [managed settings](/en/settings#settings-files))
   * Policies deployed by IT through server delivery, MDM configuration profiles, registry policies, or managed settings files
   * Cannot be overridden by any other level, including command line arguments
   * Within the managed tier, precedence is: [`policyHelper`](#compute-managed-settings-with-a-policy-helper) output, which when configured is the only managed source used > remote (claude.ai [server-managed](/en/server-managed-settings) or [Claude apps gateway](/en/claude-apps-gateway)-delivered) > MDM/OS-level policies > file-based (`managed-settings.d/*.json` + `managed-settings.json`) > HKCU registry (Windows only). Only one managed source is used; sources do not merge across tiers, with one exception: the sandbox lock keys `sandbox.network.allowManagedDomainsOnly` and `sandbox.filesystem.allowManagedReadPathsOnly`, with their associated allowlists, `allowAllClaudeAiMcps`, and the sandbox binary paths `sandbox.bwrapPath` and `sandbox.socatPath` are honored when any admin-controlled managed source sets them; the user-writable HKCU tier is excluded. Within the file-based tier, drop-in files and the base file are merged together.
   * Embedding hosts such as Claude Desktop can supply policy via the SDK `managedSettings` option. By default this is ignored when an admin-deployed managed source is present: server-managed settings, an MDM or OS-level policy, or a managed settings file. The user-writable HKCU registry fallback does not count as an admin-deployed source. Administrators can opt in by setting [`parentSettingsBehavior`](#available-settings) to `"merge"`. The embedder's values are filtered so they can tighten managed policy but not loosen it.

2. **Command line arguments**
   * Temporary overrides for a specific session. JSON passed via `--settings <file-or-json>` merges with file-based settings using the same rules as the other layers: a key set here overrides the same key in local, project, or user settings, and omitting a key leaves the lower-layer value in place

3. **Local project settings** (`.claude/settings.local.json`)
   * Personal project-specific settings

4. **Shared project settings** (`.claude/settings.json`)
   * Team-shared project settings in source control

5. **User settings** (`~/.claude/settings.json`)
   * Personal global settings

This hierarchy ensures that organizational policies are always enforced while still allowing teams and individuals to customize their experience. The same precedence applies whether you run Claude Code from the CLI, the [VS Code extension](/en/vs-code), or a [JetBrains IDE](/en/jetbrains).

For example, if your user settings set `permissions.defaultMode` to `acceptEdits` and a project's shared settings set it to `default`, the project value applies. The example below covers how array-valued settings such as permission rules combine instead.

<Note>
  **Array settings merge across scopes.** When the same array-valued setting (such as `sandbox.filesystem.allowWrite` or `permissions.allow`) appears in multiple scopes, the arrays are **concatenated and deduplicated**, not replaced. This means lower-priority scopes can add entries without overriding those set by higher-priority scopes, and vice versa. For example, if managed settings set `allowWrite` to `["/opt/company-tools"]` and a user adds `["~/.kube"]`, both paths are included in the final configuration.

  Two array settings do not merge this way:

  * [`fallbackModel`](#available-settings) is an ordered chain where position carries meaning: the highest-precedence file that defines it supplies the entire value.
  * [`availableModels`](#available-settings): {/* min-version: 2.1.175 */}when the [highest-precedence managed source](/en/server-managed-settings#settings-precedence) defines it, that list applies as-is and user, project, and local entries cannot extend it. Across non-managed scopes the arrays merge as usual. See [Merge behavior](/en/model-config#merge-behavior).
</Note>

### Verify active settings

Run `/status` inside Claude Code to see which settings sources are active. Inside the menu, the **Status** tab includes a `Setting sources` line that lists each layer Claude Code loaded for the current session, such as `User settings` or `Project local settings`. When [managed settings](/en/admin-setup#decide-how-settings-reach-devices) are in effect, the entry shows the delivery channel in parentheses, for example `Enterprise managed settings (remote)`, `(plist)`, `(HKLM)`, `(HKCU)`, or `(file)`. The `remote` channel covers both claude.ai server-managed settings and [Claude apps gateway](/en/claude-apps-gateway)-delivered policies. A layer appears in the list only when that source is loaded with at least one key, so an empty list means no settings sources were found.

The `Setting sources` line confirms which sources are being read. It does not show which layer supplied each individual key. The **Config** tab in the same dialog is an editor for a fixed set of toggles such as theme and verbose output, not a view of your `settings.json` contents.

If a settings file contains errors, such as invalid JSON or a value that fails validation, `/status` lists the affected files. Run `/doctor` to see the details for each error.

### Key points about the configuration system

* **Memory files (`CLAUDE.md`)**: Contain instructions and context that Claude loads at startup
* **Settings files (JSON)**: Configure permissions, environment variables, and tool behavior
* **Skills**: Custom prompts that can be invoked with `/skill-name` or loaded by Claude automatically
* **MCP servers**: Extend Claude Code with additional tools and integrations
* **Precedence**: Higher-level configurations (Managed) override lower-level ones (User/Project)
* **Inheritance**: Settings merge across scopes; scalar values from higher-priority scopes override, and arrays concatenate, with two exceptions described in the [array-merge Note](#settings-precedence)

### System prompt

Claude Code's internal system prompt is not published. To add custom instructions, use `CLAUDE.md` files or the `--append-system-prompt` flag.

### Exclude sensitive files

To prevent Claude Code from accessing files containing sensitive information like API keys, secrets, and environment files, use the `permissions.deny` setting in your `.claude/settings.json` file:

```json theme={null}
{
  "permissions": {
    "deny": [
      "Read(./.env)",
      "Read(./.env.*)",
      "Read(./secrets/**)",
      "Read(./config/credentials.json)",
      "Read(./build)"
    ]
  }
}
```

This replaces the deprecated `ignorePatterns` configuration. Files matching these patterns are excluded from file discovery and search results, and read operations on these files are denied.

## Subagent configuration

Claude Code supports custom AI subagents that can be configured at both user and project levels. These subagents are stored as Markdown files with YAML frontmatter:

* **User subagents**: `~/.claude/agents/`, available across all your projects
* **Project subagents**: `.claude/agents/`, specific to your project and shareable with your team

Subagent files define specialized AI assistants with custom prompts and tool permissions. Learn more about creating and using subagents in the [subagents documentation](/en/sub-agents).

## Plugin configuration

Claude Code supports a plugin system that lets you extend functionality with skills, agents, hooks, and MCP servers. Plugins are distributed through marketplaces and can be configured at both user and repository levels.

### Plugin settings

Plugin-related settings in `settings.json`:

```json theme={null}
{
  "enabledPlugins": {
    "formatter@acme-tools": true,
    "deployer@acme-tools": true,
    "analyzer@security-plugins": false
  },
  "extraKnownMarketplaces": {
    "acme-tools": {
      "source": {
        "source": "github",
        "repo": "acme-corp/claude-plugins"
      }
    }
  }
}
```

#### `enabledPlugins`

Controls which plugins are enabled. Format: `"plugin-name@marketplace-name": true/false`. A plugin with no entry at any scope falls back to its [`defaultEnabled`](/en/plugins-reference#default-enablement) value.

**Scopes**:

* **User settings** (`~/.claude/settings.json`): Personal plugin preferences
* **Project settings** (`.claude/settings.json`): Project-specific plugins shared with team
* **Local settings** (`.claude/settings.local.json`): Per-machine overrides, gitignored when Claude Code creates it
* **Managed settings** (`managed-settings.json`): Organization-wide policy overrides that block installation at all scopes and hide the plugin from the marketplace

<Note>
  Project settings take precedence over user settings, so setting a plugin to `false` in `~/.claude/settings.json` does not disable a plugin that the project's `.claude/settings.json` enables. To opt out of a project-enabled plugin on your machine, set it to `false` in `.claude/settings.local.json` instead.

  Plugins force-enabled by managed settings cannot be disabled this way, since managed settings override local settings.

  Enabling a plugin from an external source such as a GitHub repository or npm package in a project's `.claude/settings.json` doesn't install it for other people. As of Claude Code v2.1.195, every path that loads plugins asks each user to [install and trust the plugin](/en/discover-plugins#configure-team-marketplaces) before it runs.
</Note>

**Example**:

```json theme={null}
{
  "enabledPlugins": {
    "code-formatter@team-tools": true,
    "deployment-tools@team-tools": true,
    "experimental-features@personal": false
  }
}
```

#### `extraKnownMarketplaces`

Defines additional marketplaces that should be made available for the repository. Typically used in repository-level settings to ensure team members have access to required plugin sources.

**When a repository includes `extraKnownMarketplaces`**:

1. Team members are prompted to install the marketplace when they trust the folder
2. Team members are then prompted to install plugins from that marketplace
3. Users can skip unwanted marketplaces or plugins (stored in user settings)
4. Installation respects trust boundaries and requires explicit consent

**Example**:

```json theme={null}
{
  "extraKnownMarketplaces": {
    "acme-tools": {
      "source": {
        "source": "github",
        "repo": "acme-corp/claude-plugins"
      }
    },
    "security-plugins": {
      "source": {
        "source": "git",
        "url": "https://git.example.com/security/plugins.git"
      }
    }
  }
}
```

**Marketplace source types**:

* `github`: GitHub repository (uses `repo`)
* `git`: Any git URL (uses `url`)
* `directory`: Local filesystem path (uses `path`, for development only)
* `hostPattern`: regex pattern to match marketplace hosts (uses `hostPattern`)
* `settings`: inline marketplace declared directly in settings.json without a separate hosted repository (uses `name` and `plugins`)

The `git` source type works with any git hosting service, including self-hosted GitLab and Bitbucket. Claude Code clones the repository with the same authentication that `git clone` would use on that machine: configured credential helpers, SSH keys, or a host-specific token environment variable. See [Private repositories](/en/plugin-marketplaces#private-repositories) for setup details.

For `github` and `git` sources, set `"skipLfs": true` inside the `source` object (alongside `repo` or `url`) to skip Git LFS downloads when Claude Code clones or updates the marketplace repository. LFS pointer files remain as pointers instead of downloading their content. Use this when the repository contains large LFS objects unrelated to plugin content. {/* min-version: 2.1.153 */}Requires Claude Code v2.1.153 or later.

Each marketplace entry also accepts an optional `autoUpdate` Boolean. Set `"autoUpdate": true` alongside `source` to make Claude Code refresh that marketplace and update its installed plugins at startup. When omitted, official Anthropic marketplaces default to `true` and all other marketplaces default to `false`. See [Configure auto-updates](/en/discover-plugins#configure-auto-updates).

Use `source: 'settings'` to declare a small set of plugins inline without setting up a hosted marketplace repository. Plugins listed here must reference external sources such as GitHub or npm. You still need to enable each plugin separately in `enabledPlugins`.

```json theme={null}
{
  "extraKnownMarketplaces": {
    "team-tools": {
      "source": {
        "source": "settings",
        "name": "team-tools",
        "plugins": [
          {
            "name": "code-formatter",
            "source": {
              "source": "github",
              "repo": "acme-corp/code-formatter"
            }
          }
        ]
      }
    }
  }
}
```

#### `strictKnownMarketplaces`

**Managed settings only**: Controls which plugin marketplaces users are allowed to add and install plugins from. This setting can only be configured in [managed settings](/en/settings#settings-files) and provides administrators with strict control over marketplace sources.

**Managed settings file locations**:

* **macOS**: `/Library/Application Support/ClaudeCode/managed-settings.json`
* **Linux and WSL**: `/etc/claude-code/managed-settings.json`
* **Windows**: `C:\Program Files\ClaudeCode\managed-settings.json`

**Key characteristics**:

* Only available in managed settings (`managed-settings.json`)
* Cannot be overridden by user or project settings (highest precedence)
* Enforced before network and filesystem operations, so blocked sources never run
* Uses exact matching for source specifications (including `ref`, `path` for git sources), except `hostPattern` and `pathPattern`, which use regex matching

**Allowlist behavior**:

* `undefined` (default): no restrictions, so users can add any marketplace
* Empty array `[]`: complete lockdown, so users can't add any new marketplaces
* List of sources: users can only add marketplaces that match exactly

**All supported source types**:

The allowlist supports multiple marketplace source types. Most sources use exact matching, while `hostPattern` and `pathPattern` use regex matching against the marketplace host and filesystem path respectively.

1. **GitHub repositories**:

```json theme={null}
{ "source": "github", "repo": "acme-corp/approved-plugins" }
{ "source": "github", "repo": "acme-corp/security-tools", "ref": "v2.0" }
{ "source": "github", "repo": "acme-corp/plugins", "ref": "main", "path": "marketplace" }
```

Fields: `repo` (required), `ref` (optional: branch or tag), `path` (optional: subdirectory)

2. **Git repositories**:

```json theme={null}
{ "source": "git", "url": "https://gitlab.example.com/tools/plugins.git" }
{ "source": "git", "url": "https://bitbucket.org/acme-corp/plugins.git", "ref": "production" }
{ "source": "git", "url": "ssh://git@git.example.com/plugins.git", "ref": "v3.1", "path": "approved" }
```

Fields: `url` (required), `ref` (optional: branch or tag), `path` (optional: subdirectory)

3. **URL-based marketplaces**:

```json theme={null}
{ "source": "url", "url": "https://plugins.example.com/marketplace.json" }
{ "source": "url", "url": "https://cdn.example.com/marketplace.json", "headers": { "Authorization": "Bearer ${TOKEN}" } }
```

Fields: `url` (required), `headers` (optional: HTTP headers for authenticated access)

<Note>
  URL-based marketplaces only download the `marketplace.json` file. They do not download plugin files from the server. Plugins in URL-based marketplaces must use external sources (GitHub, npm, or git URLs) rather than relative paths. For plugins with relative paths, use a Git-based marketplace instead. See [Troubleshooting](/en/plugin-marketplaces#plugins-with-relative-paths-fail-in-url-based-marketplaces) for details.
</Note>

4. **NPM packages**:

```json theme={null}
{ "source": "npm", "package": "@acme-corp/claude-plugins" }
{ "source": "npm", "package": "@acme-corp/approved-marketplace" }
```

Fields: `package` (required, supports scoped packages)

5. **File paths**:

```json theme={null}
{ "source": "file", "path": "/usr/local/share/claude/acme-marketplace.json" }
{ "source": "file", "path": "/opt/acme-corp/plugins/marketplace.json" }
```

Fields: `path` (required: absolute path to marketplace.json file)

6. **Directory paths**:

```json theme={null}
{ "source": "directory", "path": "/usr/local/share/claude/acme-plugins" }
{ "source": "directory", "path": "/opt/acme-corp/approved-marketplaces" }
```

Fields: `path` (required: absolute path to directory containing `.claude-plugin/marketplace.json`)

7. **Host pattern matching**:

```json theme={null}
{ "source": "hostPattern", "hostPattern": "^github\\.example\\.com$" }
{ "source": "hostPattern", "hostPattern": "^gitlab\\.internal\\.example\\.com$" }
```

Fields: `hostPattern` (required: regex pattern to match against the marketplace host)

Use host pattern matching when you want to allow all marketplaces from a specific host without enumerating each repository individually. This is useful for organizations with internal GitHub Enterprise or GitLab servers where developers create their own marketplaces.

Host extraction by source type:

* `github`: always matches against `github.com`
* `git`: extracts hostname from the URL (supports both HTTPS and SSH formats)
* `url`: extracts hostname from the URL
* `npm`, `file`, `directory`: not supported for host pattern matching

8. **Path pattern matching**:

```json theme={null}
{ "source": "pathPattern", "pathPattern": "^/opt/approved/" }
{ "source": "pathPattern", "pathPattern": ".*" }
```

Fields: `pathPattern` (required: regex pattern matched against the `path` field of `file` and `directory` sources)

Use path pattern matching to allow filesystem-based marketplaces alongside `hostPattern` restrictions for network sources. Set `".*"` to allow all local paths, or a narrower pattern to restrict to specific directories.

**Configuration examples**:

Example: allow specific marketplaces only:

```json theme={null}
{
  "strictKnownMarketplaces": [
    {
      "source": "github",
      "repo": "acme-corp/approved-plugins"
    },
    {
      "source": "github",
      "repo": "acme-corp/security-tools",
      "ref": "v2.0"
    },
    {
      "source": "url",
      "url": "https://plugins.example.com/marketplace.json"
    },
    {
      "source": "npm",
      "package": "@acme-corp/compliance-plugins"
    }
  ]
}
```

Example: disable all marketplace additions:

```json theme={null}
{
  "strictKnownMarketplaces": []
}
```

Example: allow all marketplaces from an internal git server:

```json theme={null}
{
  "strictKnownMarketplaces": [
    {
      "source": "hostPattern",
      "hostPattern": "^github\\.example\\.com$"
    }
  ]
}
```

**Exact matching requirements**:

Marketplace sources must match exactly for a user's addition to be allowed. For git-based sources (`github` and `git`), this includes all optional fields:

* The `repo` or `url` must match exactly
* The `ref` field must match exactly (or both be undefined)
* The `path` field must match exactly (or both be undefined)

Examples of sources that don't match:

```json theme={null}
// These are DIFFERENT sources:
{ "source": "github", "repo": "acme-corp/plugins" }
{ "source": "github", "repo": "acme-corp/plugins", "ref": "main" }

// These are also DIFFERENT:
{ "source": "github", "repo": "acme-corp/plugins", "path": "marketplace" }
{ "source": "github", "repo": "acme-corp/plugins" }
```

**Comparison with `extraKnownMarketplaces`**:

| Aspect                | `strictKnownMarketplaces`            | `extraKnownMarketplaces`             |
| --------------------- | ------------------------------------ | ------------------------------------ |
| **Purpose**           | Organizational policy enforcement    | Team convenience                     |
| **Settings file**     | `managed-settings.json` only         | Any settings file                    |
| **Behavior**          | Blocks non-allowlisted additions     | Auto-installs missing marketplaces   |
| **When enforced**     | Before network/filesystem operations | After user trust prompt              |
| **Can be overridden** | No (highest precedence)              | Yes (by higher precedence settings)  |
| **Source format**     | Direct source object                 | Named marketplace with nested source |
| **Use case**          | Compliance, security restrictions    | Onboarding, standardization          |

**Format difference**:

`strictKnownMarketplaces` uses direct source objects:

```json theme={null}
{
  "strictKnownMarketplaces": [
    { "source": "github", "repo": "acme-corp/plugins" }
  ]
}
```

`extraKnownMarketplaces` requires named marketplaces:

```json theme={null}
{
  "extraKnownMarketplaces": {
    "acme-tools": {
      "source": { "source": "github", "repo": "acme-corp/plugins" }
    }
  }
}
```

**Using both together**:

`strictKnownMarketplaces` is a policy gate: it controls what users may add but does not register any marketplaces. To both restrict and pre-register a marketplace for all users, set both in `managed-settings.json`:

```json theme={null}
{
  "strictKnownMarketplaces": [
    { "source": "github", "repo": "acme-corp/plugins" }
  ],
  "extraKnownMarketplaces": {
    "acme-tools": {
      "source": { "source": "github", "repo": "acme-corp/plugins" }
    }
  }
}
```

With only `strictKnownMarketplaces` set, users can still add the allowed marketplace manually via `/plugin marketplace add`, but it is not available automatically.

**Important notes**:

* Restrictions are checked before any network requests or filesystem operations
* When blocked, users see clear error messages indicating the source is blocked by managed policy
* The restriction is enforced on marketplace add and on plugin install, update, refresh, and auto-update. A marketplace added before the policy was set cannot be used to install or update plugins once its source no longer matches the allowlist
* Managed settings have the highest precedence and cannot be overridden

See [Managed marketplace restrictions](/en/plugin-marketplaces#managed-marketplace-restrictions) for user-facing documentation.

#### `strictPluginOnlyCustomization`

**Managed settings only**: blocks skills, agents, hooks, and MCP servers from user and project sources, so they can only come from plugins or managed settings. Combine it with `strictKnownMarketplaces` to control the full customization supply chain: the marketplace allowlist controls which plugins users can install, and this setting blocks everything that doesn't come from a plugin or from managed settings.

<Note>
  `strictPluginOnlyCustomization` requires Claude Code v2.1.82 or later. Earlier versions ignore the key and keep loading user and project customizations, so the lockdown isn't enforced until clients update.
</Note>

The value is either `true` to lock all four surfaces, or an array naming the surfaces to lock:

```json theme={null}
{
  "strictPluginOnlyCustomization": ["skills", "hooks"]
}
```

For each locked surface, Claude Code skips user-level and project-level sources and loads only plugin-provided and managed sources:

| Surface  | Blocked when locked                               | Still loads                                                            |
| :------- | :------------------------------------------------ | :--------------------------------------------------------------------- |
| `skills` | `~/.claude/skills/`, `.claude/skills/`            | Plugin skills, bundled skills, skills in the managed policy directory  |
| `agents` | `~/.claude/agents/`, `.claude/agents/`            | Plugin agents, built-in agents, agents in the managed policy directory |
| `hooks`  | Hooks in user, project, and local `settings.json` | Plugin hooks, hooks in managed settings                                |
| `mcp`    | Servers in `~/.claude.json` and `.mcp.json`       | Plugin MCP servers, [`managed-mcp.json`](/en/managed-mcp) servers      |

Surface names that a Claude Code version doesn't recognize are ignored rather than failing the settings file, so you can add new surface names before all clients have updated.

### Manage plugins

Use the `/plugin` command to manage plugins interactively:

* Browse available plugins from marketplaces
* Install/uninstall plugins
* Enable/disable plugins
* View plugin details (skills, agents, hooks provided)
* Add/remove marketplaces

Learn more about the plugin system in the [plugins documentation](/en/plugins).

## Environment variables

Environment variables let you control Claude Code behavior without editing settings files. Any variable can also be configured in [`settings.json`](#available-settings) under the `env` key to apply it to every session or roll it out to your team.

See the [environment variables reference](/en/env-vars) for the full list.

## Tools available to Claude

Claude Code has access to a set of tools for reading, editing, searching, running commands, and orchestrating subagents. Tool names are the exact strings you use in permission rules and hook matchers.

See the [tools reference](/en/tools-reference) for the full list and Bash tool behavior details.

## See also

* [Permissions](/en/permissions): permission system, rule syntax, tool-specific patterns, and managed policies
* [Authentication](/en/authentication): set up user access to Claude Code
* [Debug your configuration](/en/debug-your-config): diagnose why a setting, hook, or MCP server isn't taking effect
* [Troubleshoot installation and login](/en/troubleshoot-install): installation, authentication, and platform issues


================================================================================
# PAGE: iam
# source: https://docs.claude.com/en/docs/claude-code/iam.md
================================================================================

> ## Documentation Index
> Fetch the complete documentation index at: https://code.claude.com/docs/llms.txt
> Use this file to discover all available pages before exploring further.

# Authentication

> Log in to Claude Code and configure authentication for individuals, teams, and organizations.

Claude Code supports multiple authentication methods depending on your setup. Individual users can log in with a Claude.ai account, while teams can use Claude for Teams or Enterprise, the Claude Console, or a cloud provider like Amazon Bedrock, Google Vertex AI, or Microsoft Foundry.

## Log in to Claude Code

After [installing Claude Code](/en/setup#install-claude-code), run `claude` in your terminal. On first launch, Claude Code opens a browser window for you to log in.

If the browser doesn't open automatically, press `c` to copy the login URL to your clipboard, then paste it into your browser.

If your browser shows a login code instead of redirecting back after you sign in, paste it into the terminal at the `Paste code here if prompted` prompt. This happens when the browser can't reach Claude Code's local callback server, which is common in WSL2, SSH sessions, and containers.

You can authenticate with any of these account types:

* **Claude Pro or Max subscription**: log in with your Claude.ai account. Subscribe at [claude.com/pricing](https://claude.com/pricing?utm_source=claude_code\&utm_medium=docs\&utm_content=authentication_pro_max).
* **Claude for Teams or Enterprise**: log in with the Claude.ai account your team admin invited you to.
* **Claude Console**: log in with your Console credentials. Your admin must have [invited you](#claude-console-authentication) first.
* **Cloud providers**: if your organization uses [Amazon Bedrock](/en/amazon-bedrock), [Google Vertex AI](/en/google-vertex-ai), or [Microsoft Foundry](/en/microsoft-foundry), set the required environment variables before running `claude`. No browser login is needed.
* **Cloud gateway**: if your organization runs a self-hosted [Claude apps gateway](/en/claude-apps-gateway), sign in with corporate SSO through `/login`. The gateway-issued token is the session's only credential.

To log out and re-authenticate, type `/logout` at the Claude Code prompt.

If you're having trouble logging in, see [authentication troubleshooting](/en/troubleshoot-install#login-and-authentication).

## Set up team authentication

For teams and organizations, you can configure Claude Code access in one of these ways:

* [Claude for Teams or Enterprise](#claude-for-teams-or-enterprise), recommended for most teams
* [Claude Console](#claude-console-authentication)
* [Claude apps gateway](/en/claude-apps-gateway), a self-hosted gateway that signs developers in with your IdP and routes inference to the cloud provider you configure
* [Amazon Bedrock](/en/amazon-bedrock)
* [Google Vertex AI](/en/google-vertex-ai)
* [Microsoft Foundry](/en/microsoft-foundry)

### Claude for Teams or Enterprise

[Claude for Teams](https://claude.com/pricing?utm_source=claude_code\&utm_medium=docs\&utm_content=authentication_teams#team-&-enterprise) and [Claude for Enterprise](https://anthropic.com/contact-sales?utm_source=claude_code\&utm_medium=docs\&utm_content=authentication_enterprise) provide the best experience for organizations using Claude Code. Team members get access to both Claude Code and Claude on the web with centralized billing and team management.

* **Claude for Teams**: self-service plan with collaboration features, admin tools, and billing management. Best for smaller teams.
* **Claude for Enterprise**: adds SSO, domain capture, role-based permissions, compliance API, and managed policy settings for organization-wide Claude Code configurations. Best for larger organizations with security and compliance requirements.

<Steps>
  <Step title="Subscribe">
    Subscribe to [Claude for Teams](https://claude.com/pricing?utm_source=claude_code\&utm_medium=docs\&utm_content=authentication_teams_step#team-&-enterprise) or contact sales for [Claude for Enterprise](https://anthropic.com/contact-sales?utm_source=claude_code\&utm_medium=docs\&utm_content=authentication_enterprise_step).
  </Step>

  <Step title="Invite team members">
    Invite team members from the admin dashboard.
  </Step>

  <Step title="Install and log in">
    Team members install Claude Code and log in with their Claude.ai accounts.
  </Step>
</Steps>

### Claude Console authentication

For organizations that prefer API-based billing, you can set up access through the Claude Console.

<Steps>
  <Step title="Create or use a Console account">
    Use your existing Claude Console account or create a new one.
  </Step>

  <Step title="Add users">
    You can add users through either method:

    * Bulk invite users from within the Console: Settings -> Members -> Invite
    * [Set up SSO](https://support.claude.com/en/articles/13132885-setting-up-single-sign-on-sso)
  </Step>

  <Step title="Assign roles">
    When inviting users, assign one of:

    * **Claude Code** role: users can only create Claude Code API keys
    * **Developer** role: users can create any kind of API key
  </Step>

  <Step title="Users complete setup">
    Each invited user needs to:

    * Accept the Console invite
    * [Check system requirements](/en/setup#system-requirements)
    * [Install Claude Code](/en/setup#install-claude-code)
    * Log in with Console account credentials
  </Step>
</Steps>

### Cloud provider authentication

For teams using Amazon Bedrock, Google Vertex AI, or Microsoft Foundry:

<Steps>
  <Step title="Follow provider setup">
    Follow the [Bedrock docs](/en/amazon-bedrock), [Vertex docs](/en/google-vertex-ai), or [Microsoft Foundry docs](/en/microsoft-foundry).
  </Step>

  <Step title="Distribute configuration">
    Distribute the environment variables and instructions for generating cloud credentials to your users. Read more about how to [manage configuration here](/en/settings).
  </Step>

  <Step title="Install Claude Code">
    Users can [install Claude Code](/en/setup#install-claude-code).
  </Step>
</Steps>

## Credential management

Claude Code securely manages your authentication credentials:

* **Storage location**:
  * On macOS, credentials are stored in the encrypted macOS Keychain.
  * On Linux, credentials are stored in `~/.claude/.credentials.json` with file mode `0600`.
  * On Windows, credentials are stored in `%USERPROFILE%\.claude\.credentials.json` and inherit the access controls of your user profile directory, which restricts the file to your user account by default.
  * If you've set the `CLAUDE_CONFIG_DIR` environment variable on Linux or Windows, the `.credentials.json` file lives under that directory instead.
  * Claude Code manages `.credentials.json` through `/login` and `/logout`. To route requests through a custom API endpoint, set the [`ANTHROPIC_BASE_URL`](/en/env-vars) environment variable instead.
* **Supported authentication types**: Claude.ai credentials, Claude API credentials, Azure Auth, Bedrock Auth, Vertex Auth, and [Claude apps gateway](/en/claude-apps-gateway) session tokens.
* **Custom credential scripts**: the [`apiKeyHelper`](/en/settings#available-settings) setting can be configured to run a shell script that returns an API key.
* **Refresh intervals**: by default, `apiKeyHelper` is called after 5 minutes or on HTTP 401 response. Set `CLAUDE_CODE_API_KEY_HELPER_TTL_MS` environment variable for custom refresh intervals.
* **Slow helper notice**: if `apiKeyHelper` takes longer than 10 seconds to return a key, Claude Code displays a warning notice in the prompt bar showing the elapsed time. If you see this notice regularly, check whether your credential script can be optimized.

`apiKeyHelper`, `ANTHROPIC_API_KEY`, and `ANTHROPIC_AUTH_TOKEN` apply to the CLI and the surfaces that wrap it, including the VS Code extension, the Agent SDK, and GitHub Actions. Claude Desktop and cloud sessions do not call `apiKeyHelper` or read these environment variables: they use OAuth, except desktop sessions running an [organization-distributed third-party inference configuration](/en/llm-gateway-connect#desktop-app), which authenticate with that configuration's credential.

### Authentication precedence

When multiple credentials are present, Claude Code chooses one in this order:

1. Cloud provider credentials, when `CLAUDE_CODE_USE_BEDROCK`, `CLAUDE_CODE_USE_VERTEX`, or `CLAUDE_CODE_USE_FOUNDRY` is set. See [third-party integrations](/en/third-party-integrations) for setup.
2. `ANTHROPIC_AUTH_TOKEN` environment variable. Sent as the `Authorization: Bearer` header. Use this when routing through an [LLM gateway or proxy](/en/llm-gateway) that authenticates with bearer tokens rather than Anthropic API keys.
3. `ANTHROPIC_API_KEY` environment variable. Sent as the `X-Api-Key` header. Use this for direct Anthropic API access with a key from the [Claude Console](https://platform.claude.com). In interactive mode, you are prompted once to approve or decline the key, and your choice is remembered. To change it later, use the "Use custom API key" toggle in `/config`. In non-interactive mode (`-p`), the key is always used when present.
4. [`apiKeyHelper`](/en/settings#available-settings) script output. Use this for dynamic or rotating credentials, such as short-lived tokens fetched from a vault.
5. `CLAUDE_CODE_OAUTH_TOKEN` environment variable. A long-lived OAuth token generated by [`claude setup-token`](#generate-a-long-lived-token). Use this for CI pipelines and scripts where browser login isn't available.
6. Subscription OAuth credentials from `/login`. This is the default for Claude Pro, Max, Team, and Enterprise users.

A signed-in [Claude apps gateway](/en/claude-apps-gateway) session sits outside this list: it is a provider selection like Bedrock or Vertex, and it outranks them. When a gateway session exists, the CLI authenticates with the gateway token even if `CLAUDE_CODE_USE_BEDROCK`, `CLAUDE_CODE_USE_VERTEX`, or `CLAUDE_CODE_USE_FOUNDRY` is set, and the bearer token, API key, and `apiKeyHelper` entries above are not used.

If you have an active Claude subscription but also have `ANTHROPIC_API_KEY` set in your environment, the API key takes precedence once approved. This can cause authentication failures if the key belongs to a disabled or expired organization. Run `unset ANTHROPIC_API_KEY` to fall back to your subscription, and check `/status` to confirm which method is active.

[Claude Code on the Web](/en/claude-code-on-the-web) always uses your subscription credentials. `ANTHROPIC_API_KEY` and `ANTHROPIC_AUTH_TOKEN` in the sandbox environment do not override them.

### Generate a long-lived token

For CI pipelines, scripts, or other environments where interactive browser login isn't available, generate a one-year OAuth token with `claude setup-token`:

```bash theme={null}
claude setup-token
```

The command walks you through OAuth authorization and prints a token to the terminal. It does not save the token anywhere; copy it and set it as the `CLAUDE_CODE_OAUTH_TOKEN` environment variable wherever you want to authenticate:

```bash theme={null}
export CLAUDE_CODE_OAUTH_TOKEN=your-token
```

This token authenticates with your Claude subscription and requires a Pro, Max, Team, or Enterprise plan. It is scoped to inference only and cannot establish [Remote Control](/en/remote-control) sessions.

[Bare mode](/en/headless#start-faster-with-bare-mode) does not read `CLAUDE_CODE_OAUTH_TOKEN`. If your script passes `--bare`, authenticate with `ANTHROPIC_API_KEY` or an `apiKeyHelper` instead.


================================================================================
# PAGE: memory
# source: https://docs.claude.com/en/docs/claude-code/memory.md
================================================================================

> ## Documentation Index
> Fetch the complete documentation index at: https://code.claude.com/docs/llms.txt
> Use this file to discover all available pages before exploring further.

# How Claude remembers your project

> Give Claude persistent instructions with CLAUDE.md files, and let Claude accumulate learnings automatically with auto memory.

Each Claude Code session begins with a fresh context window. Two mechanisms carry knowledge across sessions:

* **CLAUDE.md files**: instructions you write to give Claude persistent context
* **Auto memory**: notes Claude writes itself based on your corrections and preferences

This page covers how to:

* [Write and organize CLAUDE.md files](#claude-md-files)
* [Scope rules to specific file types](#organize-rules-with-claude/rules/) with `.claude/rules/`
* [Configure auto memory](#auto-memory) so Claude takes notes automatically
* [Troubleshoot](#troubleshoot-memory-issues) when instructions aren't being followed

## CLAUDE.md vs auto memory

Claude Code has two complementary memory systems. Both are loaded at the start of every conversation. Claude treats them as context, not enforced configuration. To block an action regardless of what Claude decides, use a [PreToolUse hook](/en/hooks-guide) instead. The more specific and concise your instructions, the more consistently Claude follows them.

|                      | CLAUDE.md files                                   | Auto memory                                                      |
| :------------------- | :------------------------------------------------ | :--------------------------------------------------------------- |
| **Who writes it**    | You                                               | Claude                                                           |
| **What it contains** | Instructions and rules                            | Learnings and patterns                                           |
| **Scope**            | Project, user, or org                             | Per repository, shared across worktrees                          |
| **Loaded into**      | Every session                                     | Every session (first 200 lines or 25KB)                          |
| **Use for**          | Coding standards, workflows, project architecture | Build commands, debugging insights, preferences Claude discovers |

Use CLAUDE.md files when you want to guide Claude's behavior. Auto memory lets Claude learn from your corrections without manual effort.

Subagents can also maintain their own auto memory. See [subagent configuration](/en/sub-agents#enable-persistent-memory) for details.

## CLAUDE.md files

CLAUDE.md files are markdown files that give Claude persistent instructions for a project, your personal workflow, or your entire organization. You write these files in plain text; Claude reads them at the start of every session.

### When to add to CLAUDE.md

Treat CLAUDE.md as the place you write down what you'd otherwise re-explain. Add to it when:

* Claude makes the same mistake a second time
* A code review catches something Claude should have known about this codebase
* You type the same correction or clarification into chat that you typed last session
* A new teammate would need the same context to be productive

Keep it to facts Claude should hold in every session: build commands, conventions, project layout, "always do X" rules. If an entry is a multi-step procedure or only matters for one part of the codebase, move it to a [skill](/en/skills) or a [path-scoped rule](#organize-rules-with-claude/rules/) instead. The [extension overview](/en/features-overview#build-your-setup-over-time) covers when to use each mechanism.

### Choose where to put CLAUDE.md files

CLAUDE.md files can live in several locations, each with a different scope. The table below lists them in load order, from broadest scope to most specific, so a project instruction appears in context after a user instruction.

| Scope                    | Location                                                                                                                                                                | Purpose                                                    | Use case examples                                                    | Shared with                     |
| ------------------------ | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------- | -------------------------------------------------------------------- | ------------------------------- |
| **Managed policy**       | • macOS: `/Library/Application Support/ClaudeCode/CLAUDE.md`<br />• Linux and WSL: `/etc/claude-code/CLAUDE.md`<br />• Windows: `C:\Program Files\ClaudeCode\CLAUDE.md` | Organization-wide instructions managed by IT/DevOps        | Company coding standards, security policies, compliance requirements | All users in organization       |
| **User instructions**    | `~/.claude/CLAUDE.md`                                                                                                                                                   | Personal preferences for all projects                      | Code styling preferences, personal tooling shortcuts                 | Just you (all projects)         |
| **Project instructions** | `./CLAUDE.md` or `./.claude/CLAUDE.md`                                                                                                                                  | Team-shared instructions for the project                   | Project architecture, coding standards, common workflows             | Team members via source control |
| **Local instructions**   | `./CLAUDE.local.md`                                                                                                                                                     | Personal project-specific preferences; add to `.gitignore` | Your sandbox URLs, preferred test data                               | Just you (current project)      |

CLAUDE.md and CLAUDE.local.md files in the directory hierarchy above the working directory are loaded in full at launch. Files in subdirectories load on demand when Claude reads files in those directories. See [How CLAUDE.md files load](#how-claude-md-files-load) for the full resolution order.

For large projects, you can break instructions into topic-specific files using [project rules](#organize-rules-with-claude/rules/). Rules let you scope instructions to specific file types or subdirectories.

### Set up a project CLAUDE.md

A project CLAUDE.md can be stored in either `./CLAUDE.md` or `./.claude/CLAUDE.md`. Create this file and add instructions that apply to anyone working on the project: build and test commands, coding standards, architectural decisions, naming conventions, and common workflows. These instructions are shared with your team through version control, so focus on project-level standards rather than personal preferences.

<Tip>
  Run `/init` to generate a starting CLAUDE.md automatically. Claude analyzes your codebase and creates a file with build commands, test instructions, and project conventions it discovers. If a CLAUDE.md already exists, `/init` suggests improvements rather than overwriting it. Refine from there with instructions Claude wouldn't discover on its own.

  Set `CLAUDE_CODE_NEW_INIT=1` to enable an interactive multi-phase flow. `/init` asks which artifacts to set up: CLAUDE.md files, skills, and hooks. It then explores your codebase with a subagent, fills in gaps via follow-up questions, and presents a reviewable proposal before writing any files.
</Tip>

### Write effective instructions

CLAUDE.md files are loaded into the context window at the start of every session, consuming tokens alongside your conversation. The [context window visualization](/en/context-window) shows where CLAUDE.md loads relative to the rest of the startup context. Because they're context rather than enforced configuration, how you write instructions affects how reliably Claude follows them. Specific, concise, well-structured instructions work best.

**Size**: target under 200 lines per CLAUDE.md file. Longer files consume more context and reduce adherence. If your instructions are growing large, use [path-scoped rules](#path-specific-rules) so instructions load only when Claude works with matching files. You can also split content into [imports](#import-additional-files) for organization, though imported files still load and enter the context window at launch.

**Structure**: use markdown headers and bullets to group related instructions. Claude scans structure the same way readers do: organized sections are easier to follow than dense paragraphs.

**Specificity**: write instructions that are concrete enough to verify. For example:

* "Use 2-space indentation" instead of "Format code properly"
* "Run `npm test` before committing" instead of "Test your changes"
* "API handlers live in `src/api/handlers/`" instead of "Keep files organized"

**Consistency**: if two rules contradict each other, Claude may pick one arbitrarily. Review your CLAUDE.md files, nested CLAUDE.md files in subdirectories, and [`.claude/rules/`](#organize-rules-with-claude/rules/) periodically to remove outdated or conflicting instructions. In monorepos, use [`claudeMdExcludes`](#exclude-specific-claude-md-files) to skip CLAUDE.md files from other teams that aren't relevant to your work.

### Import additional files

CLAUDE.md files can import additional files using `@path/to/import` syntax. Imported files are expanded and loaded into context at launch alongside the CLAUDE.md that references them.

Both relative and absolute paths are allowed. Relative paths resolve relative to the file containing the import, not the working directory. Imported files can recursively import other files, with a maximum depth of four hops.

Import parsing skips Markdown code spans and fenced code blocks. To mention a path in your CLAUDE.md without importing it, wrap it in backticks: writing `` `@README` `` keeps the text literal, while `@README` outside backticks imports the file.

To pull in a README, package.json, and a workflow guide, reference them with `@` syntax anywhere in your CLAUDE.md:

```text theme={null}
See @README for project overview and @package.json for available npm commands for this project.

# Additional Instructions
- git workflow @docs/git-instructions.md
```

For private per-project preferences that shouldn't be checked into version control, create a `CLAUDE.local.md` at the project root. It loads alongside `CLAUDE.md` and is treated the same way. Add `CLAUDE.local.md` to your `.gitignore` so it isn't committed; running `/init` and choosing the personal option does this for you.

If you work across multiple git worktrees of the same repository, a gitignored `CLAUDE.local.md` only exists in the worktree where you created it. To share personal instructions across worktrees, import a file from your home directory instead:

```text theme={null}
# Individual Preferences
- @~/.claude/my-project-instructions.md
```

<Warning>
  The first time Claude Code encounters external imports in a project, it shows an approval dialog listing the files. If you decline, the imports stay disabled and the dialog does not appear again.
</Warning>

For a more structured approach to organizing instructions, see [`.claude/rules/`](#organize-rules-with-claude/rules/).

### AGENTS.md

Claude Code reads `CLAUDE.md`, not `AGENTS.md`. If your repository already uses `AGENTS.md` for other coding agents, create a `CLAUDE.md` that imports it so both tools read the same instructions without duplicating them. You can also add Claude-specific instructions below the import. Claude loads the imported file at session start, then appends the rest:

```markdown CLAUDE.md theme={null}
@AGENTS.md

## Claude Code

Use plan mode for changes under `src/billing/`.
```

A symlink also works if you don't need to add Claude-specific content:

```bash theme={null}
ln -s AGENTS.md CLAUDE.md
```

On Windows, creating a symlink requires Administrator privileges or Developer Mode, so use the `@AGENTS.md` import instead.

Running [`/init`](/en/commands) in a repo that already has an `AGENTS.md` reads it and incorporates the relevant parts into the generated `CLAUDE.md`. It also reads other tool configs like `.cursorrules`, `.devin/rules/`, and `.windsurfrules`.

### How CLAUDE.md files load

Claude Code reads CLAUDE.md files by walking up the directory tree from your current working directory, checking each directory along the way for `CLAUDE.md` and `CLAUDE.local.md` files. This means if you run Claude Code in `foo/bar/`, it loads instructions from `foo/bar/CLAUDE.md`, `foo/CLAUDE.md`, and any `CLAUDE.local.md` files alongside them.

All discovered files are concatenated into context rather than overriding each other. Across the directory tree, content is ordered from the filesystem root down to your working directory. For the `foo/bar/` example, `foo/CLAUDE.md` appears in context before `foo/bar/CLAUDE.md`, so instructions closer to where you launched Claude are read last. Within each directory, `CLAUDE.local.md` is appended after `CLAUDE.md`, so your personal notes are the last thing Claude reads at that level.

Claude also discovers `CLAUDE.md` and `CLAUDE.local.md` files in subdirectories under your current working directory. Instead of loading them at launch, they are included when Claude reads files in those subdirectories.

If you work in a large monorepo where other teams' CLAUDE.md files get picked up, use [`claudeMdExcludes`](#exclude-specific-claude-md-files) to skip them. For the full layout of root and per-directory CLAUDE.md files and rules, see [Monorepos and large repos](/en/large-codebases).

Block-level HTML comments (`<!-- maintainer notes -->`) in CLAUDE.md files are stripped before the content is injected into Claude's context. Use them to leave notes for human maintainers without spending context tokens on them. Comments inside code blocks are preserved. When you open a CLAUDE.md file directly with the Read tool, comments remain visible.

#### Load from additional directories

The `--add-dir` flag gives Claude access to additional directories outside your main working directory. By default, CLAUDE.md files from these directories are not loaded.

To also load memory files from additional directories, set the `CLAUDE_CODE_ADDITIONAL_DIRECTORIES_CLAUDE_MD` environment variable:

```bash theme={null}
CLAUDE_CODE_ADDITIONAL_DIRECTORIES_CLAUDE_MD=1 claude --add-dir ../shared-config
```

This loads `CLAUDE.md`, `.claude/CLAUDE.md`, `.claude/rules/*.md`, and `CLAUDE.local.md` from the additional directory. `CLAUDE.local.md` is skipped if you exclude `local` from [`--setting-sources`](/en/cli-reference).

### Organize rules with `.claude/rules/`

For larger projects, you can organize instructions into multiple files using the `.claude/rules/` directory. This keeps instructions modular and easier for teams to maintain. Rules can also be [scoped to specific file paths](#path-specific-rules), so they only load into context when Claude works with matching files, reducing noise and saving context space.

<Note>
  Rules load into context every session or when matching files are opened. For task-specific instructions that don't need to be in context all the time, use [skills](/en/skills) instead, which only load when you invoke them or when Claude determines they're relevant to your prompt.
</Note>

#### Set up rules

Place markdown files in your project's `.claude/rules/` directory. Each file should cover one topic, with a descriptive filename like `testing.md` or `api-design.md`. All `.md` files are discovered recursively, so you can organize rules into subdirectories like `frontend/` or `backend/`:

```text theme={null}
your-project/
├── .claude/
│   ├── CLAUDE.md           # Main project instructions
│   └── rules/
│       ├── code-style.md   # Code style guidelines
│       ├── testing.md      # Testing conventions
│       └── security.md     # Security requirements
```

Rules without [`paths` frontmatter](#path-specific-rules) are loaded at launch with the same priority as `.claude/CLAUDE.md`.

#### Path-specific rules

Rules can be scoped to specific files using YAML frontmatter with the `paths` field. These conditional rules only apply when Claude is working with files matching the specified patterns.

```markdown theme={null}
---
paths:
  - "src/api/**/*.ts"
---

# API Development Rules

- All API endpoints must include input validation
- Use the standard error response format
- Include OpenAPI documentation comments
```

Rules without a `paths` field are loaded unconditionally and apply to all files. Path-scoped rules trigger when Claude reads files matching the pattern, not on every tool use.

Use glob patterns in the `paths` field to match files by extension, directory, or any combination:

| Pattern                | Matches                                  |
| ---------------------- | ---------------------------------------- |
| `**/*.ts`              | All TypeScript files in any directory    |
| `src/**/*`             | All files under `src/` directory         |
| `*.md`                 | Markdown files in the project root       |
| `src/components/*.tsx` | React components in a specific directory |

You can specify multiple patterns and use brace expansion to match multiple extensions in one pattern:

```markdown theme={null}
---
paths:
  - "src/**/*.{ts,tsx}"
  - "lib/**/*.ts"
  - "tests/**/*.test.ts"
---
```

#### Share rules across projects with symlinks

The `.claude/rules/` directory supports symlinks, so you can maintain a shared set of rules and link them into multiple projects. Symlinks are resolved and loaded normally, and circular symlinks are detected and handled gracefully.

This example links both a shared directory and an individual file:

```bash theme={null}
ln -s ~/shared-claude-rules .claude/rules/shared
ln -s ~/company-standards/security.md .claude/rules/security.md
```

#### User-level rules

Personal rules in `~/.claude/rules/` apply to every project on your machine. Use them for preferences that aren't project-specific:

```text theme={null}
~/.claude/rules/
├── preferences.md    # Your personal coding preferences
└── workflows.md      # Your preferred workflows
```

User-level rules are loaded before project rules, giving project rules higher priority.

### Manage CLAUDE.md for large teams

For organizations deploying Claude Code across teams, you can centralize instructions and control which CLAUDE.md files are loaded.

#### Deploy organization-wide CLAUDE.md

Organizations can deploy a centrally managed CLAUDE.md that applies to all users on a machine. This file cannot be excluded by individual settings.

<Steps>
  <Step title="Create the file at the managed policy location">
    * macOS: `/Library/Application Support/ClaudeCode/CLAUDE.md`
    * Linux and WSL: `/etc/claude-code/CLAUDE.md`
    * Windows: `C:\Program Files\ClaudeCode\CLAUDE.md`
  </Step>

  <Step title="Deploy with your configuration management system">
    Use MDM, Group Policy, Ansible, or similar tools to distribute the file across developer machines. See [managed settings](/en/permissions#managed-settings) for other organization-wide configuration options.
  </Step>
</Steps>

The `claudeMd` key lets you put managed CLAUDE.md content directly inside `managed-settings.json` instead of deploying a separate file.

**Scope**: every Claude Code session on the machine, in every repository. For repository-specific guidance, commit a project CLAUDE.md instead.

**Precedence**: same as a managed CLAUDE.md file. Loads before user and project CLAUDE.md.

**Where it's honored**: managed and policy settings only. Setting `claudeMd` in user, project, or local settings has no effect.

The example below adds behavioral instructions directly in a managed settings file:

```json theme={null}
{
  "claudeMd": "Always run `make lint` before committing.\nNever push directly to main."
}
```

A managed CLAUDE.md and [managed settings](/en/settings#settings-files) serve different purposes. Use settings for technical enforcement and CLAUDE.md for behavioral guidance:

| Concern                                        | Configure in                                              |
| :--------------------------------------------- | :-------------------------------------------------------- |
| Block specific tools, commands, or file paths  | Managed settings: `permissions.deny`                      |
| Enforce sandbox isolation                      | Managed settings: `sandbox.enabled`                       |
| Environment variables and API provider routing | Managed settings: `env`                                   |
| Authentication method and organization lock    | Managed settings: `forceLoginMethod`, `forceLoginOrgUUID` |
| Code style and quality guidelines              | Managed CLAUDE.md                                         |
| Data handling and compliance reminders         | Managed CLAUDE.md                                         |
| Behavioral instructions for Claude             | Managed CLAUDE.md                                         |

Settings rules are enforced by the client regardless of what Claude decides to do. CLAUDE.md instructions shape Claude's behavior but are not a hard enforcement layer.

#### Exclude specific CLAUDE.md files

In large monorepos, ancestor CLAUDE.md files may contain instructions that aren't relevant to your work. The `claudeMdExcludes` setting lets you skip specific files by path or glob pattern.

This example excludes a top-level CLAUDE.md and a rules directory from a parent folder. Add it to `.claude/settings.local.json` so the exclusion stays local to your machine:

```json theme={null}
{
  "claudeMdExcludes": [
    "**/monorepo/CLAUDE.md",
    "/home/user/monorepo/other-team/.claude/rules/**"
  ]
}
```

Patterns are matched against absolute file paths using glob syntax. You can configure `claudeMdExcludes` at any [settings layer](/en/settings#settings-files): user, project, local, or managed policy. Arrays merge across layers.

Managed policy CLAUDE.md files cannot be excluded. This ensures organization-wide instructions always apply regardless of individual settings.

## Auto memory

Auto memory lets Claude accumulate knowledge across sessions without you writing anything. Claude saves notes for itself as it works: build commands, debugging insights, architecture notes, code style preferences, and workflow habits. Claude doesn't save something every session. It decides what's worth remembering based on whether the information would be useful in a future conversation.

<Note>
  Auto memory requires Claude Code v2.1.59 or later. Check your version with `claude --version`.
</Note>

### Enable or disable auto memory

Auto memory is on by default. To toggle it, open `/memory` in a session and use the auto memory toggle, or set `autoMemoryEnabled` in your project settings:

```json theme={null}
{
  "autoMemoryEnabled": false
}
```

To disable auto memory via environment variable, set `CLAUDE_CODE_DISABLE_AUTO_MEMORY=1`.

### Storage location

Each project gets its own memory directory at `~/.claude/projects/<project>/memory/`. The `<project>` path is derived from the git repository, so all worktrees and subdirectories within the same repo share one auto memory directory. Outside a git repo, the project root is used instead.

To store auto memory in a different location, set `autoMemoryDirectory` in your `settings.json`. It is read from any [settings scope](/en/settings#settings-precedence): user, project, local, policy, or `--settings`.

```json theme={null}
{
  "autoMemoryDirectory": "~/my-custom-memory-dir"
}
```

The value must be an absolute path or start with `~/`. When set in a project's `.claude/settings.json` or `.claude/settings.local.json`, the value is honored only after you accept the workspace trust dialog for that folder, the same gate that governs hooks.

The directory contains a `MEMORY.md` entrypoint and optional topic files:

```text theme={null}
~/.claude/projects/<project>/memory/
├── MEMORY.md          # Concise index, loaded into every session
├── debugging.md       # Detailed notes on debugging patterns
├── api-conventions.md # API design decisions
└── ...                # Any other topic files Claude creates
```

`MEMORY.md` acts as an index of the memory directory. Claude reads and writes files in this directory throughout your session, using `MEMORY.md` to keep track of what's stored where.

Auto memory is machine-local. All worktrees and subdirectories within the same git repository share one auto memory directory. Files are not shared across machines or cloud environments.

### How it works

The first 200 lines of `MEMORY.md`, or the first 25KB, whichever comes first, are loaded at the start of every conversation. Content beyond that threshold is not loaded at session start. Claude keeps `MEMORY.md` concise by moving detailed notes into separate topic files.

This limit applies only to `MEMORY.md`. CLAUDE.md files are loaded in full regardless of length, though shorter files produce better adherence.

Topic files like `debugging.md` or `patterns.md` are not loaded at startup. Claude reads them on demand using its standard file tools when it needs the information.

Claude reads and writes memory files during your session. When you see "Writing memory" or "Recalled memory" in the Claude Code interface, Claude is actively updating or reading from `~/.claude/projects/<project>/memory/`.

### Audit and edit your memory

Auto memory files are plain markdown you can edit or delete at any time. Run [`/memory`](#view-and-edit-with-%2Fmemory) to browse and open memory files from within a session.

## View and edit with `/memory`

The `/memory` command lists all CLAUDE.md, CLAUDE.local.md, and rules files loaded in your current session, lets you toggle auto memory on or off, and provides a link to open the auto memory folder. Select any file to open it in your editor.

When you ask Claude to remember something, like "always use pnpm, not npm" or "remember that the API tests require a local Redis instance," Claude saves it to auto memory. To add instructions to CLAUDE.md instead, ask Claude directly, like "add this to CLAUDE.md," or edit the file yourself via `/memory`.

## Troubleshoot memory issues

These are the most common issues with CLAUDE.md and auto memory, along with steps to debug them.

### Claude isn't following my CLAUDE.md

CLAUDE.md content is delivered as a user message after the system prompt, not as part of the system prompt itself. Claude reads it and tries to follow it, but there's no guarantee of strict compliance, especially for vague or conflicting instructions.

To debug:

* Run `/memory` to verify your CLAUDE.md and CLAUDE.local.md files are being loaded. If a file isn't listed, Claude can't see it.
* Check that the relevant CLAUDE.md is in a location that gets loaded for your session (see [Choose where to put CLAUDE.md files](#choose-where-to-put-claude-md-files)).
* Make instructions more specific. "Use 2-space indentation" works better than "format code nicely."
* Look for conflicting instructions across CLAUDE.md files. If two files give different guidance for the same behavior, Claude may pick one arbitrarily.

If the instruction is something that must run at a specific point, such as before every commit or after each file edit, write it as a [hook](/en/hooks-guide) instead. Hooks execute as shell commands at fixed lifecycle events and apply regardless of what Claude decides to do.

For instructions you want at the system prompt level, use [`--append-system-prompt`](/en/cli-reference#system-prompt-flags). This must be passed every invocation, so it's better suited to scripts and automation than interactive use.

<Tip>
  Use the [`InstructionsLoaded` hook](/en/hooks#instructionsloaded) to log exactly which instruction files are loaded, when they load, and why. This is useful for debugging path-specific rules or lazy-loaded files in subdirectories.
</Tip>

### I don't know what auto memory saved

Run `/memory` and select the auto memory folder to browse what Claude has saved. Everything is plain markdown you can read, edit, or delete.

### My CLAUDE.md is too large

Files over 200 lines consume more context and may reduce adherence. Use [path-scoped rules](#path-specific-rules) to load instructions only when Claude works with matching files, or trim content that isn't needed in every session. Splitting into [`@path` imports](#import-additional-files) helps organization but does not reduce context, since imported files load at launch.

### Instructions seem lost after `/compact`

Project-root CLAUDE.md survives compaction: after `/compact`, Claude re-reads it from disk and re-injects it into the session. Nested CLAUDE.md files in subdirectories are not re-injected automatically; they reload the next time Claude reads a file in that subdirectory.

If an instruction disappeared after compaction, it was either given only in conversation or lives in a nested CLAUDE.md that hasn't reloaded yet. Add conversation-only instructions to CLAUDE.md to make them persist. See [What survives compaction](/en/context-window#what-survives-compaction) for the full breakdown.

See [Write effective instructions](#write-effective-instructions) for guidance on size, structure, and specificity.

## Related resources

* [Debug your configuration](/en/debug-your-config): diagnose why CLAUDE.md or settings aren't taking effect
* [Skills](/en/skills): package repeatable workflows that load on demand
* [Settings](/en/settings): configure Claude Code behavior with settings files
* [Subagent memory](/en/sub-agents#enable-persistent-memory): let subagents maintain their own auto memory


================================================================================
# PAGE: headless
# source: https://docs.claude.com/en/docs/claude-code/headless.md
================================================================================

> ## Documentation Index
> Fetch the complete documentation index at: https://code.claude.com/docs/llms.txt
> Use this file to discover all available pages before exploring further.

# Run Claude Code programmatically

> Use the Agent SDK to run Claude Code programmatically from the CLI, Python, or TypeScript.

The [Agent SDK](/en/agent-sdk/overview) gives you the same tools, agent loop, and context management that power Claude Code. It's available as a CLI for scripts and CI/CD, or as [Python](/en/agent-sdk/python) and [TypeScript](/en/agent-sdk/typescript) packages for full programmatic control.

To run Claude Code in non-interactive mode, pass `-p` with your prompt and any [CLI options](/en/cli-reference):

```bash theme={null}
claude -p "Find and fix the bug in auth.py" --allowedTools "Read,Edit,Bash"
```

This page covers using the Agent SDK via the CLI (`claude -p`). For the Python and TypeScript SDK packages with structured outputs, tool approval callbacks, and native message objects, see the [full Agent SDK documentation](/en/agent-sdk/overview).

## Basic usage

Add the `-p` (or `--print`) flag to any `claude` command to run it non-interactively. All [CLI options](/en/cli-reference) work with `-p`, including:

* `--continue` for [continuing conversations](#continue-conversations)
* `--allowedTools` for [auto-approving tools](#auto-approve-tools)
* `--output-format` for [structured output](#get-structured-output)

This example asks Claude a question about your codebase and prints the response:

```bash theme={null}
claude -p "What does the auth module do?"
```

### Start faster with bare mode

Add `--bare` to reduce startup time by skipping auto-discovery of hooks, skills, plugins, MCP servers, auto memory, and CLAUDE.md. Without it, `claude -p` loads the same [context](/en/how-claude-code-works#the-context-window) an interactive session would, including anything configured in the working directory or `~/.claude`.

Bare mode is useful for CI and scripts where you need the same result on every machine. A hook in a teammate's `~/.claude` or an MCP server in the project's `.mcp.json` won't run, because bare mode never reads them. Only flags you pass explicitly take effect.

This example runs a one-off summarize task in bare mode and pre-approves the Read tool so the call completes without a permission prompt:

```bash theme={null}
claude --bare -p "Summarize this file" --allowedTools "Read"
```

In bare mode Claude has access to the Bash, file read, and file edit tools. Pass any context you need with a flag:

| To load                 | Use                                                     |
| ----------------------- | ------------------------------------------------------- |
| System prompt additions | `--append-system-prompt`, `--append-system-prompt-file` |
| Settings                | `--settings <file-or-json>`                             |
| MCP servers             | `--mcp-config <file-or-json>`                           |
| Custom agents           | `--agents <json>`                                       |
| A plugin                | `--plugin-dir <path>`, `--plugin-url <url>`             |

Bare mode skips OAuth and keychain reads. Anthropic authentication must come from `ANTHROPIC_API_KEY` or an `apiKeyHelper` in the JSON passed to `--settings`. Bedrock, Vertex, and Foundry use their usual provider credentials.

<Note>
  `--bare` is the recommended mode for scripted and SDK calls, and will become the default for `-p` in a future release.
</Note>

### Background tasks at exit

If Claude starts a [background Bash task](/en/tools-reference#bash-tool-behavior) during a `claude -p` run, for example a dev server or a watch build, that shell is terminated about five seconds after Claude has returned its final result and stdin has closed. The grace period lets a task that finishes right after the result still deliver its output. Before v2.1.163, a never-exiting background process would hold the `claude -p` invocation open indefinitely.

Background [subagents](/en/sub-agents) and workflows are exempt from the five-second grace because their result is part of the final output, so `claude -p` waits for them to complete. From v2.1.182, that wait is capped at ten minutes by default so a stuck background agent cannot hold the process open indefinitely. Adjust the cap with [`CLAUDE_CODE_PRINT_BG_WAIT_CEILING_MS`](/en/env-vars), or set it to `0` to wait without a limit.

## Examples

These examples highlight common CLI patterns. For CI and other scripted calls, add [`--bare`](#start-faster-with-bare-mode) so they don't pick up whatever happens to be configured locally.

### Pipe data through Claude

Non-interactive mode reads stdin, so you can pipe data in and redirect the response out like any other command-line tool.

This example pipes a build log into Claude and writes the explanation to a file:

```bash theme={null}
cat build-error.txt | claude -p 'concisely explain the root cause of this build error' > output.txt
```

With `--output-format json`, the response payload includes `total_cost_usd` and a per-model cost breakdown, so scripted callers can track spend per invocation without consulting the [usage dashboard](/en/costs).

<Note>
  As of Claude Code v2.1.128, piped stdin is capped at 10MB. If you exceed the cap, Claude Code exits with a clear error and a non-zero status. To work with larger inputs, write the content to a file and reference the file path in your prompt instead of piping it.
</Note>

### Add Claude to a build script

You can wrap a non-interactive call in a script to use Claude as a project-specific linter or reviewer.

This `package.json` script pipes the diff against `main` into Claude and asks it to report typos. Piping the diff means Claude doesn't need Bash permission to read it, and the escaped double quotes keep the script portable to Windows:

```json theme={null}
{
  "scripts": {
    "lint:claude": "git diff main | claude -p \"you are a typo linter. for each typo in this diff, report filename:line on one line and the issue on the next. return nothing else.\""
  }
}
```

### Get structured output

Use `--output-format` to control how responses are returned:

* `text` (default): plain text output
* `json`: structured JSON with result, session ID, and metadata
* `stream-json`: newline-delimited JSON for real-time streaming

This example returns a project summary as JSON with session metadata, with the text result in the `result` field:

```bash theme={null}
claude -p "Summarize this project" --output-format json
```

To get output conforming to a specific schema, use `--output-format json` with `--json-schema` and a [JSON Schema](https://json-schema.org/) definition. The response includes metadata about the request (session ID, usage, etc.) with the structured output in the `structured_output` field.

This example extracts function names and returns them as an array of strings:

```bash theme={null}
claude -p "Extract the main function names from auth.py" \
  --output-format json \
  --json-schema '{"type":"object","properties":{"functions":{"type":"array","items":{"type":"string"}}},"required":["functions"]}'
```

<Tip>
  Use a tool like [jq](https://jqlang.github.io/jq/) to parse the response and extract specific fields:

  ```bash theme={null}
  # Extract the text result
  claude -p "Summarize this project" --output-format json | jq -r '.result'

  # Extract structured output
  claude -p "Extract function names from auth.py" \
    --output-format json \
    --json-schema '{"type":"object","properties":{"functions":{"type":"array","items":{"type":"string"}}},"required":["functions"]}' \
    | jq '.structured_output'
  ```
</Tip>

### Stream responses

Use `--output-format stream-json` with `--verbose` and `--include-partial-messages` to receive tokens as they're generated. Each line is a JSON object representing an event:

```bash theme={null}
claude -p "Explain recursion" --output-format stream-json --verbose --include-partial-messages
```

The following example uses [jq](https://jqlang.github.io/jq/) to filter for text deltas and display just the streaming text. The `-r` flag outputs raw strings (no quotes) and `-j` joins without newlines so tokens stream continuously:

```bash theme={null}
claude -p "Write a poem" --output-format stream-json --verbose --include-partial-messages | \
  jq -rj 'select(.type == "stream_event" and .event.delta.type? == "text_delta") | .event.delta.text'
```

When an API request fails with a retryable error, Claude Code emits a `system/api_retry` event before retrying. You can use this to surface retry progress or implement custom backoff logic.

| Field            | Type            | Description                                                                                                                                                                                            |
| ---------------- | --------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `type`           | `"system"`      | message type                                                                                                                                                                                           |
| `subtype`        | `"api_retry"`   | identifies this as a retry event                                                                                                                                                                       |
| `attempt`        | integer         | current attempt number, starting at 1                                                                                                                                                                  |
| `max_retries`    | integer         | total retries permitted                                                                                                                                                                                |
| `retry_delay_ms` | integer         | milliseconds until the next attempt                                                                                                                                                                    |
| `error_status`   | integer or null | HTTP status code, or `null` for connection errors with no HTTP response                                                                                                                                |
| `error`          | string          | error category: `authentication_failed`, `oauth_org_not_allowed`, `billing_error`, `rate_limit`, `overloaded`, `invalid_request`, `model_not_found`, `server_error`, `max_output_tokens`, or `unknown` |
| `uuid`           | string          | unique event identifier                                                                                                                                                                                |
| `session_id`     | string          | session the event belongs to                                                                                                                                                                           |

The `system/init` event reports session metadata including the model, tools, MCP servers, and loaded plugins. It is the first event in the stream unless [`CLAUDE_CODE_SYNC_PLUGIN_INSTALL`](/en/env-vars) is set, in which case `plugin_install` events precede it. Use the plugin fields to fail CI when a plugin did not load:

| Field           | Type  | Description                                                                                                                                                                                                                                                                                  |
| --------------- | ----- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `plugins`       | array | plugins that loaded successfully, each with `name` and `path`                                                                                                                                                                                                                                |
| `plugin_errors` | array | plugin load-time errors, each with `plugin`, `type`, and `message`. Includes unsatisfied dependency versions and `--plugin-dir` load failures such as a missing path or invalid archive. Affected plugins are demoted and absent from `plugins`. The key is omitted when there are no errors |

When [`CLAUDE_CODE_SYNC_PLUGIN_INSTALL`](/en/env-vars) is set, Claude Code emits `system/plugin_install` events while marketplace plugins install before the first turn. Use these to surface install progress in your own UI.

| Field        | Type                                                     | Description                                                                                                    |
| ------------ | -------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------- |
| `type`       | `"system"`                                               | message type                                                                                                   |
| `subtype`    | `"plugin_install"`                                       | identifies this as a plugin install event                                                                      |
| `status`     | `"started"`, `"installed"`, `"failed"`, or `"completed"` | `started` and `completed` bracket the overall install; `installed` and `failed` report individual marketplaces |
| `name`       | string, optional                                         | marketplace name, present on `installed` and `failed`                                                          |
| `error`      | string, optional                                         | failure message, present on `failed`                                                                           |
| `uuid`       | string                                                   | unique event identifier                                                                                        |
| `session_id` | string                                                   | session the event belongs to                                                                                   |

For programmatic streaming with callbacks and message objects, see [Stream responses in real-time](/en/agent-sdk/streaming-output) in the Agent SDK documentation.

### Auto-approve tools

Use `--allowedTools` to let Claude use certain tools without prompting. This example runs a test suite and fixes failures, allowing Claude to execute Bash commands and read/edit files without asking for permission:

```bash theme={null}
claude -p "Run the test suite and fix any failures" \
  --allowedTools "Bash,Read,Edit"
```

To set a baseline for the whole session instead of listing individual tools, pass a [permission mode](/en/permission-modes). `dontAsk` denies anything not in your `permissions.allow` rules or the [read-only command set](/en/permissions#read-only-commands), which is useful for locked-down CI runs. `acceptEdits` lets Claude write files without prompting and also auto-approves common filesystem commands such as `mkdir`, `touch`, `mv`, and `cp`. Other shell commands and network requests still need an `--allowedTools` entry or a `permissions.allow` rule, otherwise the run aborts when one is attempted:

```bash theme={null}
claude -p "Apply the lint fixes" --permission-mode acceptEdits
```

### Create a commit

This example reviews staged changes and creates a commit with an appropriate message:

```bash theme={null}
claude -p "Look at my staged changes and create an appropriate commit" \
  --allowedTools "Bash(git diff *),Bash(git log *),Bash(git status *),Bash(git commit *)"
```

The `--allowedTools` flag uses [permission rule syntax](/en/settings#permission-rule-syntax). The trailing ` *` enables prefix matching, so `Bash(git diff *)` allows any command starting with `git diff`. The space before `*` is important: without it, `Bash(git diff*)` would also match `git diff-index`.

<Note>
  User-invoked [skills](/en/skills) and custom commands work in `-p` mode: include `/skill-name` in the prompt string and Claude Code expands it before running. Built-in commands that open an interactive dialog, such as `/login`, are not available in `-p` mode. {/* min-version: 2.1.181 */}To change a setting from a `-p` invocation, pass `key=value` to `/config`, for example `/config thinking=false`.
</Note>

### Customize the system prompt

Use `--append-system-prompt` to add instructions while keeping Claude Code's default behavior. This example pipes a PR diff to Claude and instructs it to review for security vulnerabilities:

```bash theme={null}
gh pr diff "$1" | claude -p \
  --append-system-prompt "You are a security engineer. Review for vulnerabilities." \
  --output-format json
```

See [system prompt flags](/en/cli-reference#system-prompt-flags) for more options including `--system-prompt` to fully replace the default prompt.

### Continue conversations

Use `--continue` to continue the most recent conversation, or `--resume` with a session ID to continue a specific conversation. This example runs a review, then sends follow-up prompts:

```bash theme={null}
# First request
claude -p "Review this codebase for performance issues"

# Continue the most recent conversation
claude -p "Now focus on the database queries" --continue
claude -p "Generate a summary of all issues found" --continue
```

If you're running multiple conversations, capture the session ID to resume a specific one:

```bash theme={null}
session_id=$(claude -p "Start a review" --output-format json | jq -r '.session_id')
claude -p "Continue that review" --resume "$session_id"
```

Run both commands from the same directory: session ID lookup is scoped to the current project directory and its git worktrees. See [Resume a session](/en/sessions#resume-a-session) for the full scope rules.

## Next steps

* [Agent SDK quickstart](/en/agent-sdk/quickstart): build your first agent with Python or TypeScript
* [CLI reference](/en/cli-reference): all CLI flags and options
* [GitHub Actions](/en/github-actions): use the Agent SDK in GitHub workflows
* [GitLab CI/CD](/en/gitlab-ci-cd): use the Agent SDK in GitLab pipelines


================================================================================
# PAGE: sdk
# source: https://docs.claude.com/en/docs/claude-code/sdk
================================================================================

> ## Documentation Index
> Fetch the complete documentation index at: https://code.claude.com/docs/llms.txt
> Use this file to discover all available pages before exploring further.

# Agent SDK overview

> Build production AI agents with Claude Code as a library

Build AI agents that autonomously read files, run commands, search the web, edit code, and more. The Agent SDK gives you the same tools, agent loop, and context management that power Claude Code, programmable in Python and TypeScript.

<CodeGroup>
  ```python Python theme={null}
  import asyncio
  from claude_agent_sdk import query, ClaudeAgentOptions


  async def main():
      async for message in query(
          prompt="Find and fix the bug in auth.py",
          options=ClaudeAgentOptions(allowed_tools=["Read", "Edit", "Bash"]),
      ):
          print(message)  # Claude reads the file, finds the bug, edits it


  asyncio.run(main())
  ```

  ```typescript TypeScript theme={null}
  import { query } from "@anthropic-ai/claude-agent-sdk";

  for await (const message of query({
    prompt: "Find and fix the bug in auth.ts",
    options: { allowedTools: ["Read", "Edit", "Bash"] }
  })) {
    console.log(message); // Claude reads the file, finds the bug, edits it
  }
  ```
</CodeGroup>

The Agent SDK includes built-in tools for reading files, running commands, and editing code, so your agent can start working immediately without you implementing tool execution. Dive into the quickstart or explore real agents built with the SDK:

<CardGroup cols={2}>
  <Card title="Quickstart" icon="play" href="/en/agent-sdk/quickstart">
    Build a bug-fixing agent in minutes
  </Card>

  <Card title="Example agents" icon="star" href="https://github.com/anthropics/claude-agent-sdk-demos">
    Email assistant, research agent, and more
  </Card>
</CardGroup>

## Get started

<Steps>
  <Step title="Install the SDK">
    <Tabs>
      <Tab title="TypeScript">
        ```bash theme={null}
        npm install @anthropic-ai/claude-agent-sdk
        ```
      </Tab>

      <Tab title="Python">
        ```bash theme={null}
        pip install claude-agent-sdk
        ```

        The Python package requires Python 3.10 or later. If pip reports `No matching distribution found for claude-agent-sdk`, your interpreter is older than 3.10. Run `python3 --version` on macOS or Linux, or `py --version` on Windows, to check.
      </Tab>
    </Tabs>

    <Note>
      The TypeScript SDK bundles a native Claude Code binary for your platform as an optional dependency, so you don't need to install Claude Code separately.
    </Note>
  </Step>

  <Step title="Set your API key">
    Get an API key from the [Console](https://platform.claude.com/), then set it as an environment variable:

    ```bash theme={null}
    export ANTHROPIC_API_KEY=your-api-key
    ```

    The SDK also supports authentication via third-party API providers:

    * **Amazon Bedrock**: set `CLAUDE_CODE_USE_BEDROCK=1` environment variable and configure AWS credentials
    * **Claude Platform on AWS**: set `CLAUDE_CODE_USE_ANTHROPIC_AWS=1` and `ANTHROPIC_AWS_WORKSPACE_ID`, then configure AWS credentials
    * **Google Vertex AI**: set `CLAUDE_CODE_USE_VERTEX=1` environment variable and configure Google Cloud credentials
    * **Microsoft Azure**: set `CLAUDE_CODE_USE_FOUNDRY=1` environment variable and configure Azure credentials

    See the setup guides for [Bedrock](/en/amazon-bedrock), [Claude Platform on AWS](/en/claude-platform-on-aws), [Vertex AI](/en/google-vertex-ai), or [Azure AI Foundry](/en/microsoft-foundry) for details.

    <Note>
      Unless previously approved, Anthropic does not allow third party developers to offer claude.ai login or rate limits for their products, including agents built on the Claude Agent SDK. Please use the API key authentication methods described in this document instead.
    </Note>
  </Step>

  <Step title="Run your first agent">
    This example creates an agent that lists files in your current directory using built-in tools.

    <CodeGroup>
      ```python Python theme={null}
      import asyncio
      from claude_agent_sdk import query, ClaudeAgentOptions


      async def main():
          async for message in query(
              prompt="What files are in this directory?",
              options=ClaudeAgentOptions(allowed_tools=["Bash", "Glob"]),
          ):
              if hasattr(message, "result"):
                  print(message.result)


      asyncio.run(main())
      ```

      ```typescript TypeScript theme={null}
      import { query } from "@anthropic-ai/claude-agent-sdk";

      for await (const message of query({
        prompt: "What files are in this directory?",
        options: { allowedTools: ["Bash", "Glob"] }
      })) {
        if ("result" in message) console.log(message.result);
      }
      ```
    </CodeGroup>
  </Step>
</Steps>

**Ready to build?** Follow the [Quickstart](/en/agent-sdk/quickstart) to create an agent that finds and fixes bugs in minutes.

## Capabilities

Everything that makes Claude Code powerful is available in the SDK:

<Tabs>
  <Tab title="Built-in tools">
    Your agent can read files, run commands, and search codebases out of the box. Key tools include:

    | Tool                                                                        | What it does                                                        |
    | --------------------------------------------------------------------------- | ------------------------------------------------------------------- |
    | **Read**                                                                    | Read any file in the working directory                              |
    | **Write**                                                                   | Create new files                                                    |
    | **Edit**                                                                    | Make precise edits to existing files                                |
    | **Bash**                                                                    | Run terminal commands, scripts, git operations                      |
    | **Monitor**                                                                 | Watch a background script and react to each output line as an event |
    | **Glob**                                                                    | Find files by pattern (`**/*.ts`, `src/**/*.py`)                    |
    | **Grep**                                                                    | Search file contents with regex                                     |
    | **WebSearch**                                                               | Search the web for current information                              |
    | **WebFetch**                                                                | Fetch and parse web page content                                    |
    | **[AskUserQuestion](/en/agent-sdk/user-input#handle-clarifying-questions)** | Ask the user clarifying questions with multiple choice options      |

    This example creates an agent that searches your codebase for TODO comments:

    <CodeGroup>
      ```python Python theme={null}
      import asyncio
      from claude_agent_sdk import query, ClaudeAgentOptions


      async def main():
          async for message in query(
              prompt="Find all TODO comments and create a summary",
              options=ClaudeAgentOptions(allowed_tools=["Read", "Glob", "Grep"]),
          ):
              if hasattr(message, "result"):
                  print(message.result)


      asyncio.run(main())
      ```

      ```typescript TypeScript theme={null}
      import { query } from "@anthropic-ai/claude-agent-sdk";

      for await (const message of query({
        prompt: "Find all TODO comments and create a summary",
        options: { allowedTools: ["Read", "Glob", "Grep"] }
      })) {
        if ("result" in message) console.log(message.result);
      }
      ```
    </CodeGroup>
  </Tab>

  <Tab title="Hooks">
    Run custom code at key points in the agent lifecycle. SDK hooks use callback functions to validate, log, block, or transform agent behavior.

    **Available hooks:** `PreToolUse`, `PostToolUse`, `Stop`, `SessionStart`, `SessionEnd`, `UserPromptSubmit`, and more.

    This example logs all file changes to an audit file:

    <CodeGroup>
      ```python Python theme={null}
      import asyncio
      from datetime import datetime
      from claude_agent_sdk import query, ClaudeAgentOptions, HookMatcher


      async def log_file_change(input_data, tool_use_id, context):
          file_path = input_data.get("tool_input", {}).get("file_path", "unknown")
          with open("./audit.log", "a") as f:
              f.write(f"{datetime.now()}: modified {file_path}\n")
          return {}


      async def main():
          async for message in query(
              prompt="Refactor utils.py to improve readability",
              options=ClaudeAgentOptions(
                  permission_mode="acceptEdits",
                  hooks={
                      "PostToolUse": [
                          HookMatcher(matcher="Edit|Write", hooks=[log_file_change])
                      ]
                  },
              ),
          ):
              if hasattr(message, "result"):
                  print(message.result)


      asyncio.run(main())
      ```

      ```typescript TypeScript theme={null}
      import { query, HookCallback } from "@anthropic-ai/claude-agent-sdk";
      import { appendFile } from "fs/promises";

      const logFileChange: HookCallback = async (input) => {
        const filePath = (input as any).tool_input?.file_path ?? "unknown";
        await appendFile("./audit.log", `${new Date().toISOString()}: modified ${filePath}\n`);
        return {};
      };

      for await (const message of query({
        prompt: "Refactor utils.py to improve readability",
        options: {
          permissionMode: "acceptEdits",
          hooks: {
            PostToolUse: [{ matcher: "Edit|Write", hooks: [logFileChange] }]
          }
        }
      })) {
        if ("result" in message) console.log(message.result);
      }
      ```
    </CodeGroup>

    [Learn more about hooks →](/en/agent-sdk/hooks)
  </Tab>

  <Tab title="Subagents">
    Spawn specialized agents to handle focused subtasks. Your main agent delegates work, and subagents report back with results.

    Define custom agents with specialized instructions. Subagents are invoked via the Agent tool, so include `Agent` in `allowedTools` to auto-approve those invocations:

    <CodeGroup>
      ```python Python theme={null}
      import asyncio
      from claude_agent_sdk import query, ClaudeAgentOptions, AgentDefinition


      async def main():
          async for message in query(
              prompt="Use the code-reviewer agent to review this codebase",
              options=ClaudeAgentOptions(
                  allowed_tools=["Read", "Glob", "Grep", "Agent"],
                  agents={
                      "code-reviewer": AgentDefinition(
                          description="Expert code reviewer for quality and security reviews.",
                          prompt="Analyze code quality and suggest improvements.",
                          tools=["Read", "Glob", "Grep"],
                      )
                  },
              ),
          ):
              if hasattr(message, "result"):
                  print(message.result)


      asyncio.run(main())
      ```

      ```typescript TypeScript theme={null}
      import { query } from "@anthropic-ai/claude-agent-sdk";

      for await (const message of query({
        prompt: "Use the code-reviewer agent to review this codebase",
        options: {
          allowedTools: ["Read", "Glob", "Grep", "Agent"],
          agents: {
            "code-reviewer": {
              description: "Expert code reviewer for quality and security reviews.",
              prompt: "Analyze code quality and suggest improvements.",
              tools: ["Read", "Glob", "Grep"]
            }
          }
        }
      })) {
        if ("result" in message) console.log(message.result);
      }
      ```
    </CodeGroup>

    Messages from within a subagent's context include a `parent_tool_use_id` field, letting you track which messages belong to which subagent execution.

    [Learn more about subagents →](/en/agent-sdk/subagents)
  </Tab>

  <Tab title="MCP">
    Connect to external systems via the Model Context Protocol: databases, browsers, APIs, and [hundreds more](https://github.com/modelcontextprotocol/servers).

    This example connects the [Playwright MCP server](https://github.com/microsoft/playwright-mcp) to give your agent browser automation capabilities:

    <CodeGroup>
      ```python Python theme={null}
      import asyncio
      from claude_agent_sdk import query, ClaudeAgentOptions


      async def main():
          async for message in query(
              prompt="Open example.com and describe what you see",
              options=ClaudeAgentOptions(
                  mcp_servers={
                      "playwright": {"command": "npx", "args": ["@playwright/mcp@latest"]}
                  }
              ),
          ):
              if hasattr(message, "result"):
                  print(message.result)


      asyncio.run(main())
      ```

      ```typescript TypeScript theme={null}
      import { query } from "@anthropic-ai/claude-agent-sdk";

      for await (const message of query({
        prompt: "Open example.com and describe what you see",
        options: {
          mcpServers: {
            playwright: { command: "npx", args: ["@playwright/mcp@latest"] }
          }
        }
      })) {
        if ("result" in message) console.log(message.result);
      }
      ```
    </CodeGroup>

    [Learn more about MCP →](/en/agent-sdk/mcp)
  </Tab>

  <Tab title="Permissions">
    Control exactly which tools your agent can use. Allow safe operations, block dangerous ones, or require approval for sensitive actions.

    <Note>
      For interactive approval prompts and the `AskUserQuestion` tool, see [Handle approvals and user input](/en/agent-sdk/user-input).
    </Note>

    This example creates a read-only agent that can analyze but not modify code. `allowed_tools` pre-approves `Read`, `Glob`, and `Grep`.

    <CodeGroup>
      ```python Python theme={null}
      import asyncio
      from claude_agent_sdk import query, ClaudeAgentOptions


      async def main():
          async for message in query(
              prompt="Review this code for best practices",
              options=ClaudeAgentOptions(
                  allowed_tools=["Read", "Glob", "Grep"],
              ),
          ):
              if hasattr(message, "result"):
                  print(message.result)


      asyncio.run(main())
      ```

      ```typescript TypeScript theme={null}
      import { query } from "@anthropic-ai/claude-agent-sdk";

      for await (const message of query({
        prompt: "Review this code for best practices",
        options: {
          allowedTools: ["Read", "Glob", "Grep"]
        }
      })) {
        if ("result" in message) console.log(message.result);
      }
      ```
    </CodeGroup>

    [Learn more about permissions →](/en/agent-sdk/permissions)
  </Tab>

  <Tab title="Sessions">
    Maintain context across multiple exchanges. Claude remembers files read, analysis done, and conversation history. Resume sessions later, or fork them to explore different approaches.

    This example captures the session ID from the first query, then resumes to continue with full context:

    <CodeGroup>
      ```python Python theme={null}
      import asyncio
      from claude_agent_sdk import query, ClaudeAgentOptions, SystemMessage, ResultMessage


      async def main():
          session_id = None

          # First query: capture the session ID
          async for message in query(
              prompt="Read the authentication module",
              options=ClaudeAgentOptions(allowed_tools=["Read", "Glob"]),
          ):
              if isinstance(message, SystemMessage) and message.subtype == "init":
                  session_id = message.data["session_id"]

          # Resume with full context from the first query
          async for message in query(
              prompt="Now find all places that call it",  # "it" = auth module
              options=ClaudeAgentOptions(resume=session_id),
          ):
              if isinstance(message, ResultMessage):
                  print(message.result)


      asyncio.run(main())
      ```

      ```typescript TypeScript theme={null}
      import { query } from "@anthropic-ai/claude-agent-sdk";

      let sessionId: string | undefined;

      // First query: capture the session ID
      for await (const message of query({
        prompt: "Read the authentication module",
        options: { allowedTools: ["Read", "Glob"] }
      })) {
        if (message.type === "system" && message.subtype === "init") {
          sessionId = message.session_id;
        }
      }

      // Resume with full context from the first query
      for await (const message of query({
        prompt: "Now find all places that call it", // "it" = auth module
        options: { resume: sessionId }
      })) {
        if ("result" in message) console.log(message.result);
      }
      ```
    </CodeGroup>

    [Learn more about sessions →](/en/agent-sdk/sessions)
  </Tab>
</Tabs>

### Claude Code features

The SDK also supports Claude Code's filesystem-based configuration. With default options the SDK loads these from `.claude/` in your working directory and `~/.claude/`. To restrict which sources load, set `setting_sources` (Python) or `settingSources` (TypeScript) in your options.

| Feature                                          | Description                                                                   | Location                           |
| ------------------------------------------------ | ----------------------------------------------------------------------------- | ---------------------------------- |
| [Skills](/en/agent-sdk/skills)                   | Specialized capabilities Claude uses automatically or you invoke with `/name` | `.claude/skills/*/SKILL.md`        |
| [Commands](/en/agent-sdk/slash-commands)         | Custom commands in the legacy format. Use skills for new custom commands      | `.claude/commands/*.md`            |
| [Memory](/en/agent-sdk/modifying-system-prompts) | Project context and instructions                                              | `CLAUDE.md` or `.claude/CLAUDE.md` |
| [Plugins](/en/agent-sdk/plugins)                 | Extend with skills, agents, hooks, and MCP servers                            | Programmatic via `plugins` option  |

## Compare the Agent SDK to other Claude tools

The Claude Platform offers multiple ways to build with Claude. Here's how the Agent SDK fits in:

<Tabs>
  <Tab title="Agent SDK vs Client SDK">
    The [Anthropic Client SDK](https://platform.claude.com/docs/en/api/client-sdks) gives you direct API access: you send prompts and implement tool execution yourself. The **Agent SDK** gives you Claude with built-in tool execution.

    With the Client SDK, you implement a tool loop. With the Agent SDK, Claude handles it:

    <CodeGroup>
      ```python Python theme={null}
      # Client SDK: You implement the tool loop
      response = client.messages.create(...)
      while response.stop_reason == "tool_use":
          result = your_tool_executor(response.tool_use)
          response = client.messages.create(tool_result=result, **params)

      # Agent SDK: Claude handles tools autonomously
      async for message in query(prompt="Fix the bug in auth.py"):
          print(message)
      ```

      ```typescript TypeScript theme={null}
      // Client SDK: You implement the tool loop
      let response = await client.messages.create({ ...params });
      while (response.stop_reason === "tool_use") {
        const result = yourToolExecutor(response.tool_use);
        response = await client.messages.create({ tool_result: result, ...params });
      }

      // Agent SDK: Claude handles tools autonomously
      for await (const message of query({ prompt: "Fix the bug in auth.ts" })) {
        console.log(message);
      }
      ```
    </CodeGroup>
  </Tab>

  <Tab title="Agent SDK vs Claude Code CLI">
    Same capabilities, different interface:

    | Use case                | Best choice |
    | ----------------------- | ----------- |
    | Interactive development | CLI         |
    | CI/CD pipelines         | SDK         |
    | Custom applications     | SDK         |
    | One-off tasks           | CLI         |
    | Production automation   | SDK         |

    Many teams use both: CLI for daily development, SDK for production. Workflows translate directly between them.
  </Tab>

  <Tab title="Agent SDK vs Managed Agents">
    [Managed Agents](https://platform.claude.com/docs/en/managed-agents/overview) is a hosted REST API: Anthropic runs the agent and the sandbox, and your application sends events and streams back results. The **Agent SDK** is a library that runs the agent loop inside your own process.

    |                    | Agent SDK                                                                    | Managed Agents                                                                                                |
    | ------------------ | ---------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------- |
    | **Runs in**        | Your process, your infrastructure                                            | Anthropic-managed infrastructure                                                                              |
    | **Interface**      | Python or TypeScript library                                                 | REST API                                                                                                      |
    | **Agent works on** | Files on your infrastructure                                                 | A managed sandbox per session                                                                                 |
    | **Session state**  | JSONL on your filesystem                                                     | Anthropic-hosted event log                                                                                    |
    | **Custom tools**   | In-process Python or TypeScript functions                                    | Claude triggers the tool; you execute and return results                                                      |
    | **Best for**       | Local prototyping, agents that work directly on your filesystem and services | Production agents without operating sandbox or session infrastructure, long-running and asynchronous sessions |

    A common path is to prototype with the Agent SDK locally, then move to Managed Agents for production.
  </Tab>
</Tabs>

## Changelog

View the full changelog for SDK updates, bug fixes, and new features:

* **TypeScript SDK**: [view CHANGELOG.md](https://github.com/anthropics/claude-agent-sdk-typescript/blob/main/CHANGELOG.md)
* **Python SDK**: [view CHANGELOG.md](https://github.com/anthropics/claude-agent-sdk-python/blob/main/CHANGELOG.md)

## Reporting bugs

If you encounter bugs or issues with the Agent SDK:

* **TypeScript SDK**: [report issues on GitHub](https://github.com/anthropics/claude-agent-sdk-typescript/issues)
* **Python SDK**: [report issues on GitHub](https://github.com/anthropics/claude-agent-sdk-python/issues)

## Branding guidelines

For partners integrating the Claude Agent SDK, use of Claude branding is optional. When referencing Claude in your product:

**Allowed:**

* "Claude Agent" (preferred for dropdown menus)
* "Claude" (when within a menu already labeled "Agents")
* "{YourAgentName} Powered by Claude" (if you have an existing agent name)

**Not permitted:**

* "Claude Code" or "Claude Code Agent"
* Claude Code-branded ASCII art or visual elements that mimic Claude Code

Your product should maintain its own branding and not appear to be Claude Code or any Anthropic product. For questions about branding compliance, contact the Anthropic [sales team](https://www.anthropic.com/contact-sales).

## License and terms

Use of the Claude Agent SDK is governed by [Anthropic's Commercial Terms of Service](https://www.anthropic.com/legal/commercial-terms), including when you use it to power products and services that you make available to your own customers and end users, except to the extent a specific component or dependency is covered by a different license as indicated in that component's LICENSE file.

## Next steps

<CardGroup cols={2}>
  <Card title="Quickstart" icon="play" href="/en/agent-sdk/quickstart">
    Build an agent that finds and fixes bugs in minutes
  </Card>

  <Card title="Example agents" icon="star" href="https://github.com/anthropics/claude-agent-sdk-demos">
    Email assistant, research agent, and more
  </Card>

  <Card title="TypeScript SDK" icon="code" href="/en/agent-sdk/typescript">
    Full TypeScript API reference and examples
  </Card>

  <Card title="Python SDK" icon="code" href="/en/agent-sdk/python">
    Full Python API reference and examples
  </Card>
</CardGroup>


================================================================================
# PAGE: github-actions
# source: https://docs.claude.com/en/docs/claude-code/github-actions.md
================================================================================

> ## Documentation Index
> Fetch the complete documentation index at: https://code.claude.com/docs/llms.txt
> Use this file to discover all available pages before exploring further.

# Claude Code GitHub Actions

> Learn about integrating Claude Code into your development workflow with Claude Code GitHub Actions

Claude Code GitHub Actions brings AI-powered automation to your GitHub workflow. With a simple `@claude` mention in any PR or issue, Claude can analyze your code, create pull requests, implement features, and fix bugs - all while following your project's standards. For automatic reviews posted on every PR without a trigger, see [GitHub Code Review](/en/code-review).

<Note>
  Claude Code GitHub Actions is built on top of the [Claude Agent SDK](/en/agent-sdk/overview), which enables programmatic integration of Claude Code into your applications. You can use the SDK to build custom automation workflows beyond GitHub Actions.
</Note>

## Why use Claude Code GitHub Actions?

* **Instant PR creation**: Describe what you need, and Claude creates a complete PR with all necessary changes
* **Automated code implementation**: Turn issues into working code with a single command
* **Follows your standards**: Claude respects your `CLAUDE.md` guidelines and existing code patterns
* **Simple setup**: Get started in minutes with our installer and API key
* **Secure by default**: Your code stays on Github's runners

## What can Claude do?

Claude Code provides a powerful GitHub Action that transforms how you work with code:

### Claude Code Action

This GitHub Action allows you to run Claude Code within your GitHub Actions workflows. You can use this to build any custom workflow on top of Claude Code.

[View repository →](https://github.com/anthropics/claude-code-action)

## Setup

## Quick setup

Run `/install-github-app` in the Claude Code terminal to set up the integration interactively. The command installs the Claude GitHub App on your repository and then walks you through adding the GitHub Actions workflows and the API key secret.

After the GitHub App is installed, the command asks whether to continue with GitHub Actions setup. In Claude Code v2.1.187 and later you can choose **Skip for now** to stop with only the App installed and return to the workflow and secret steps by running `/install-github-app` again. Earlier versions proceed straight to workflow selection.

<Note>
  * You must be a repository admin to install the GitHub app and add secrets
  * The GitHub app will request read & write permissions for Contents, Issues, and Pull requests
  * This quickstart method is only available for direct Claude API users. If
    you're using Amazon Bedrock or Google Vertex AI, see the [Using with Amazon
    Bedrock & Google Vertex AI](#using-with-amazon-bedrock-%26-google-vertex-ai)
    section.
</Note>

## Manual setup

If the `/install-github-app` command fails or you prefer manual setup, please follow these manual setup instructions:

1. **Install the Claude GitHub app** to your repository: [https://github.com/apps/claude](https://github.com/apps/claude)

   The Claude GitHub app requires the following repository permissions:

   * **Contents**: Read & write (to modify repository files)
   * **Issues**: Read & write (to respond to issues)
   * **Pull requests**: Read & write (to create PRs and push changes)

   For more details on security and permissions, see the [security documentation](https://github.com/anthropics/claude-code-action/blob/main/docs/security.md).
2. **Add ANTHROPIC\_API\_KEY** to your repository secrets ([Learn how to use secrets in GitHub Actions](https://docs.github.com/en/actions/security-guides/using-secrets-in-github-actions))
3. **Copy the workflow file** from [examples/claude.yml](https://github.com/anthropics/claude-code-action/blob/main/examples/claude.yml) into your repository's `.github/workflows/`

<Tip>
  After completing either the quickstart or manual setup, test the action by tagging `@claude` in an issue or PR comment.
</Tip>

## Upgrading from Beta

<Warning>
  Claude Code GitHub Actions v1.0 introduces breaking changes that require updating your workflow files in order to upgrade to v1.0 from the beta version.
</Warning>

If you're currently using the beta version of Claude Code GitHub Actions, we recommend that you update your workflows to use the GA version. The new version simplifies configuration while adding powerful new features like automatic mode detection.

### Essential changes

All beta users must make these changes to their workflow files in order to upgrade:

1. **Update the action version**: Change `@beta` to `@v1`
2. **Remove mode configuration**: Delete `mode: "tag"` or `mode: "agent"` (now auto-detected)
3. **Update prompt inputs**: Replace `direct_prompt` with `prompt`
4. **Move CLI options**: Convert `max_turns`, `model`, `custom_instructions`, etc. to `claude_args`

### Breaking Changes Reference

| Old Beta Input        | New v1.0 Input                        |
| --------------------- | ------------------------------------- |
| `mode`                | *(Removed - auto-detected)*           |
| `direct_prompt`       | `prompt`                              |
| `override_prompt`     | `prompt` with GitHub variables        |
| `custom_instructions` | `claude_args: --append-system-prompt` |
| `max_turns`           | `claude_args: --max-turns`            |
| `model`               | `claude_args: --model`                |
| `allowed_tools`       | `claude_args: --allowedTools`         |
| `disallowed_tools`    | `claude_args: --disallowedTools`      |
| `claude_env`          | `settings` JSON format                |

### Before and After Example

**Beta version:**

```yaml theme={null}
- uses: anthropics/claude-code-action@beta
  with:
    mode: "tag"
    direct_prompt: "Review this PR for security issues"
    anthropic_api_key: ${{ secrets.ANTHROPIC_API_KEY }}
    custom_instructions: "Follow our coding standards"
    max_turns: "10"
    model: "claude-sonnet-5"
```

**GA version (v1.0):**

```yaml theme={null}
- uses: anthropics/claude-code-action@v1
  with:
    prompt: "Review this PR for security issues"
    anthropic_api_key: ${{ secrets.ANTHROPIC_API_KEY }}
    claude_args: |
      --append-system-prompt "Follow our coding standards"
      --max-turns 10
      --model claude-sonnet-5
```

<Tip>
  The action now automatically detects whether to run in interactive mode (responds to `@claude` mentions) or automation mode (runs immediately with a prompt) based on your configuration.
</Tip>

## Example use cases

Claude Code GitHub Actions can help you with a variety of tasks. The [examples directory](https://github.com/anthropics/claude-code-action/tree/main/examples) contains ready-to-use workflows for different scenarios.

### Basic workflow

```yaml theme={null}
name: Claude Code
on:
  issue_comment:
    types: [created]
  pull_request_review_comment:
    types: [created]
jobs:
  claude:
    runs-on: ubuntu-latest
    steps:
      - uses: anthropics/claude-code-action@v1
        with:
          anthropic_api_key: ${{ secrets.ANTHROPIC_API_KEY }}
          # Responds to @claude mentions in comments
```

### Using skills

The `prompt` input accepts a [skill](/en/skills) invocation as well as plain text:

* For a skill in your repository's `.claude/skills/` directory, run `actions/checkout` before the action step and pass `/skill-name`.
* For a skill packaged in a plugin, install the plugin with the `plugin_marketplaces` and `plugins` inputs and pass the namespaced `/plugin-name:skill-name`.

The following workflow installs the `code-review` plugin and runs its skill on each new or updated pull request:

```yaml theme={null}
name: Code Review
on:
  pull_request:
    types: [opened, synchronize]
jobs:
  review:
    runs-on: ubuntu-latest
    steps:
      - uses: anthropics/claude-code-action@v1
        with:
          anthropic_api_key: ${{ secrets.ANTHROPIC_API_KEY }}
          plugin_marketplaces: "https://github.com/anthropics/claude-code.git"
          plugins: "code-review@claude-code-plugins"
          prompt: "/code-review:code-review ${{ github.repository }}/pull/${{ github.event.pull_request.number }}"
```

### Custom automation with prompts

```yaml theme={null}
name: Daily Report
on:
  schedule:
    - cron: "0 9 * * *"
jobs:
  report:
    runs-on: ubuntu-latest
    steps:
      - uses: anthropics/claude-code-action@v1
        with:
          anthropic_api_key: ${{ secrets.ANTHROPIC_API_KEY }}
          prompt: "Generate a summary of yesterday's commits and open issues"
          claude_args: "--model opus"
```

### Common use cases

In issue or PR comments:

```text wrap theme={null}
@claude implement this feature based on the issue description
@claude how should I implement user authentication for this endpoint?
@claude fix the TypeError in the user dashboard component
```

Claude will automatically analyze the context and respond appropriately.

## Best practices

### CLAUDE.md configuration

Create a `CLAUDE.md` file in your repository root to define code style guidelines, review criteria, project-specific rules, and preferred patterns. This file guides Claude's understanding of your project standards.

### Security considerations

<Warning>Never commit API keys directly to your repository.</Warning>

For comprehensive security guidance including permissions, authentication, and best practices, see the [Claude Code Action security documentation](https://github.com/anthropics/claude-code-action/blob/main/docs/security.md).

Always use GitHub Secrets for API keys:

* Add your API key as a repository secret named `ANTHROPIC_API_KEY`
* Reference it in workflows: `anthropic_api_key: ${{ secrets.ANTHROPIC_API_KEY }}`
* Limit action permissions to only what's necessary
* Review Claude's suggestions before merging

Always use GitHub Secrets (for example, `${{ secrets.ANTHROPIC_API_KEY }}`) rather than hardcoding API keys directly in your workflow files.

### Optimizing performance

Use issue templates to provide context, keep your `CLAUDE.md` concise and focused, and configure appropriate timeouts for your workflows.

### CI costs

When using Claude Code GitHub Actions, be aware of the associated costs:

**GitHub Actions costs:**

* Claude Code runs on GitHub-hosted runners, which consume your GitHub Actions minutes
* See [GitHub's billing documentation](https://docs.github.com/en/billing/managing-billing-for-your-products/managing-billing-for-github-actions/about-billing-for-github-actions) for detailed pricing and minute limits

**API costs:**

* Each Claude interaction consumes API tokens based on the length of prompts and responses
* Token usage varies by task complexity and codebase size
* See [Claude's pricing page](https://claude.com/platform/api) for current token rates

**Cost optimization tips:**

* Use specific `@claude` commands to reduce unnecessary API calls
* Configure appropriate `--max-turns` in `claude_args` to prevent excessive iterations
* Set workflow-level timeouts to avoid runaway jobs
* Consider using GitHub's concurrency controls to limit parallel runs

## Configuration examples

The Claude Code Action v1 simplifies configuration with unified parameters:

```yaml theme={null}
- uses: anthropics/claude-code-action@v1
  with:
    anthropic_api_key: ${{ secrets.ANTHROPIC_API_KEY }}
    prompt: "Your instructions here" # Optional
    claude_args: "--max-turns 5" # Optional CLI arguments
```

Key features:

* **Unified prompt interface** - Use `prompt` for all instructions
* **Skills** - Invoke installed [skills](/en/skills) directly from the prompt
* **CLI passthrough** - Any Claude Code CLI argument via `claude_args`
* **Flexible triggers** - Works with any GitHub event

Visit the [examples directory](https://github.com/anthropics/claude-code-action/tree/main/examples) for complete workflow files.

<Tip>
  When responding to issue or PR comments, Claude automatically responds to @claude mentions. For other events, use the `prompt` parameter to provide instructions.
</Tip>

## Using with Amazon Bedrock & Google Vertex AI

For enterprise environments, you can use Claude Code GitHub Actions with your own cloud infrastructure. This approach gives you control over data residency and billing while maintaining the same functionality.

### Prerequisites

Before setting up Claude Code GitHub Actions with cloud providers, you need:

#### For Google Cloud Vertex AI:

1. A Google Cloud Project with Vertex AI enabled
2. Workload Identity Federation configured for GitHub Actions
3. A service account with the required permissions
4. A GitHub App (recommended) or use the default GITHUB\_TOKEN

#### For Amazon Bedrock:

1. An AWS account with Amazon Bedrock enabled
2. GitHub OIDC Identity Provider configured in AWS
3. An IAM role with Bedrock permissions
4. A GitHub App (recommended) or use the default GITHUB\_TOKEN

<Steps>
  <Step title="Create a custom GitHub App (Recommended for 3P Providers)">
    For best control and security when using 3P providers like Vertex AI or Bedrock, we recommend creating your own GitHub App:

    1. Go to [https://github.com/settings/apps/new](https://github.com/settings/apps/new)
    2. Fill in the basic information:
       * **GitHub App name**: Choose a unique name (e.g., "YourOrg Claude Assistant")
       * **Homepage URL**: Your organization's website or the repository URL
    3. Configure the app settings:
       * **Webhooks**: Uncheck "Active" (not needed for this integration)
    4. Set the required permissions:
       * **Repository permissions**:
         * Contents: Read & Write
         * Issues: Read & Write
         * Pull requests: Read & Write
    5. Click "Create GitHub App"
    6. After creation, click "Generate a private key" and save the downloaded `.pem` file
    7. Note your App ID from the app settings page
    8. Install the app to your repository:
       * From your app's settings page, click "Install App" in the left sidebar
       * Select your account or organization
       * Choose "Only select repositories" and select the specific repository
       * Click "Install"
    9. Add the private key as a secret to your repository:
       * Go to your repository's Settings → Secrets and variables → Actions
       * Create a new secret named `APP_PRIVATE_KEY` with the contents of the `.pem` file
    10. Add the App ID as a secret:

    * Create a new secret named `APP_ID` with your GitHub App's ID

    <Note>
      This app will be used with the [actions/create-github-app-token](https://github.com/actions/create-github-app-token) action to generate authentication tokens in your workflows.
    </Note>

    **Alternative for Claude API or if you don't want to setup your own Github app**: Use the official Anthropic app:

    1. Install from: [https://github.com/apps/claude](https://github.com/apps/claude)
    2. No additional configuration needed for authentication
  </Step>

  <Step title="Configure cloud provider authentication">
    Choose your cloud provider and set up secure authentication:

    <AccordionGroup>
      <Accordion title="Amazon Bedrock">
        **Configure AWS to allow GitHub Actions to authenticate securely without storing credentials.**

        > **Security Note**: Use repository-specific configurations and grant only the minimum required permissions.

        **Required Setup**:

        1. **Enable Amazon Bedrock**:
           * Request access to Claude models in Amazon Bedrock
           * For cross-region models, request access in all required regions

        2. **Set up GitHub OIDC Identity Provider**:
           * Provider URL: `https://token.actions.githubusercontent.com`
           * Audience: `sts.amazonaws.com`

        3. **Create IAM Role for GitHub Actions**:
           * Trusted entity type: Web identity
           * Identity provider: `token.actions.githubusercontent.com`
           * Permissions: `AmazonBedrockFullAccess` policy
           * Configure trust policy for your specific repository

        **Required Values**:

        After setup, you'll need:

        * **AWS\_ROLE\_TO\_ASSUME**: The ARN of the IAM role you created

        <Tip>
          OIDC is more secure than using static AWS access keys because credentials are temporary and automatically rotated.
        </Tip>

        See [AWS documentation](https://docs.aws.amazon.com/IAM/latest/UserGuide/id_roles_providers_create_oidc.html) for detailed OIDC setup instructions.
      </Accordion>

      <Accordion title="Google Vertex AI">
        **Configure Google Cloud to allow GitHub Actions to authenticate securely without storing credentials.**

        > **Security Note**: Use repository-specific configurations and grant only the minimum required permissions.

        **Required Setup**:

        1. **Enable APIs** in your Google Cloud project:
           * IAM Credentials API
           * Security Token Service (STS) API
           * Vertex AI API

        2. **Create Workload Identity Federation resources**:
           * Create a Workload Identity Pool
           * Add a GitHub OIDC provider with:
             * Issuer: `https://token.actions.githubusercontent.com`
             * Attribute mappings for repository and owner
             * **Security recommendation**: Use repository-specific attribute conditions

        3. **Create a Service Account**:
           * Grant only `Vertex AI User` role
           * **Security recommendation**: Create a dedicated service account per repository

        4. **Configure IAM bindings**:
           * Allow the Workload Identity Pool to impersonate the service account
           * **Security recommendation**: Use repository-specific principal sets

        **Required Values**:

        After setup, you'll need:

        * **GCP\_WORKLOAD\_IDENTITY\_PROVIDER**: The full provider resource name
        * **GCP\_SERVICE\_ACCOUNT**: The service account email address

        <Tip>
          Workload Identity Federation eliminates the need for downloadable service account keys, improving security.
        </Tip>

        For detailed setup instructions, consult the [Google Cloud Workload Identity Federation documentation](https://cloud.google.com/iam/docs/workload-identity-federation).
      </Accordion>
    </AccordionGroup>
  </Step>

  <Step title="Add Required Secrets">
    Add the following secrets to your repository (Settings → Secrets and variables → Actions):

    #### For Claude API (Direct):

    1. **For API Authentication**:
       * `ANTHROPIC_API_KEY`: Your Claude API key from [console.anthropic.com](https://console.anthropic.com)

    2. **For GitHub App (if using your own app)**:
       * `APP_ID`: Your GitHub App's ID
       * `APP_PRIVATE_KEY`: The private key (.pem) content

    #### For Google Cloud Vertex AI

    1. **For GCP Authentication**:
       * `GCP_WORKLOAD_IDENTITY_PROVIDER`
       * `GCP_SERVICE_ACCOUNT`

    2. **For GitHub App (if using your own app)**:
       * `APP_ID`: Your GitHub App's ID
       * `APP_PRIVATE_KEY`: The private key (.pem) content

    #### For Amazon Bedrock

    1. **For AWS Authentication**:
       * `AWS_ROLE_TO_ASSUME`

    2. **For GitHub App (if using your own app)**:
       * `APP_ID`: Your GitHub App's ID
       * `APP_PRIVATE_KEY`: The private key (.pem) content
  </Step>

  <Step title="Create workflow files">
    Create GitHub Actions workflow files that integrate with your cloud provider. The examples below show complete configurations for both Amazon Bedrock and Google Vertex AI:

    <AccordionGroup>
      <Accordion title="Amazon Bedrock workflow">
        **Prerequisites:**

        * Amazon Bedrock access enabled with Claude model permissions
        * GitHub configured as an OIDC identity provider in AWS
        * IAM role with Bedrock permissions that trusts GitHub Actions

        **Required GitHub secrets:**

        | Secret Name          | Description                                       |
        | -------------------- | ------------------------------------------------- |
        | `AWS_ROLE_TO_ASSUME` | ARN of the IAM role for Bedrock access            |
        | `APP_ID`             | Your GitHub App ID (from app settings)            |
        | `APP_PRIVATE_KEY`    | The private key you generated for your GitHub App |

        ```yaml theme={null}
        name: Claude PR Action

        permissions:
          contents: write
          pull-requests: write
          issues: write
          id-token: write

        on:
          issue_comment:
            types: [created]
          pull_request_review_comment:
            types: [created]
          issues:
            types: [opened, assigned]

        jobs:
          claude-pr:
            if: |
              (github.event_name == 'issue_comment' && contains(github.event.comment.body, '@claude')) ||
              (github.event_name == 'pull_request_review_comment' && contains(github.event.comment.body, '@claude')) ||
              (github.event_name == 'issues' && contains(github.event.issue.body, '@claude'))
            runs-on: ubuntu-latest
            env:
              AWS_REGION: us-west-2
            steps:
              - name: Checkout repository
                uses: actions/checkout@v4

              - name: Generate GitHub App token
                id: app-token
                uses: actions/create-github-app-token@v2
                with:
                  app-id: ${{ secrets.APP_ID }}
                  private-key: ${{ secrets.APP_PRIVATE_KEY }}

              - name: Configure AWS Credentials (OIDC)
                uses: aws-actions/configure-aws-credentials@v4
                with:
                  role-to-assume: ${{ secrets.AWS_ROLE_TO_ASSUME }}
                  aws-region: us-west-2

              - uses: anthropics/claude-code-action@v1
                with:
                  github_token: ${{ steps.app-token.outputs.token }}
                  use_bedrock: "true"
                  claude_args: '--model us.anthropic.claude-sonnet-4-6 --max-turns 10'
        ```

        <Tip>
          The model ID format for Bedrock includes a region prefix (for example, `us.anthropic.claude-sonnet-4-6`).
        </Tip>
      </Accordion>

      <Accordion title="Google Vertex AI workflow">
        **Prerequisites:**

        * Vertex AI API enabled in your GCP project
        * Workload Identity Federation configured for GitHub
        * Service account with Vertex AI permissions

        **Required GitHub secrets:**

        | Secret Name                      | Description                                       |
        | -------------------------------- | ------------------------------------------------- |
        | `GCP_WORKLOAD_IDENTITY_PROVIDER` | Workload identity provider resource name          |
        | `GCP_SERVICE_ACCOUNT`            | Service account email with Vertex AI access       |
        | `APP_ID`                         | Your GitHub App ID (from app settings)            |
        | `APP_PRIVATE_KEY`                | The private key you generated for your GitHub App |

        ```yaml theme={null}
        name: Claude PR Action

        permissions:
          contents: write
          pull-requests: write
          issues: write
          id-token: write

        on:
          issue_comment:
            types: [created]
          pull_request_review_comment:
            types: [created]
          issues:
            types: [opened, assigned]

        jobs:
          claude-pr:
            if: |
              (github.event_name == 'issue_comment' && contains(github.event.comment.body, '@claude')) ||
              (github.event_name == 'pull_request_review_comment' && contains(github.event.comment.body, '@claude')) ||
              (github.event_name == 'issues' && contains(github.event.issue.body, '@claude'))
            runs-on: ubuntu-latest
            steps:
              - name: Checkout repository
                uses: actions/checkout@v4

              - name: Generate GitHub App token
                id: app-token
                uses: actions/create-github-app-token@v2
                with:
                  app-id: ${{ secrets.APP_ID }}
                  private-key: ${{ secrets.APP_PRIVATE_KEY }}

              - name: Authenticate to Google Cloud
                id: auth
                uses: google-github-actions/auth@v2
                with:
                  workload_identity_provider: ${{ secrets.GCP_WORKLOAD_IDENTITY_PROVIDER }}
                  service_account: ${{ secrets.GCP_SERVICE_ACCOUNT }}

              - uses: anthropics/claude-code-action@v1
                with:
                  github_token: ${{ steps.app-token.outputs.token }}
                  trigger_phrase: "@claude"
                  use_vertex: "true"
                  claude_args: '--model claude-sonnet-4-5@20250929 --max-turns 10'
                env:
                  ANTHROPIC_VERTEX_PROJECT_ID: ${{ steps.auth.outputs.project_id }}
                  CLOUD_ML_REGION: us-east5
                  VERTEX_REGION_CLAUDE_4_5_SONNET: us-east5
        ```

        <Tip>
          The project ID is automatically retrieved from the Google Cloud authentication step, so you don't need to hardcode it.
        </Tip>
      </Accordion>
    </AccordionGroup>
  </Step>
</Steps>

## Troubleshooting

### Claude not responding to @claude commands

Verify the GitHub App is installed correctly, check that workflows are enabled, ensure API key is set in repository secrets, and confirm the comment contains `@claude` (not `/claude`).

### CI not running on Claude's commits

Ensure you're using the GitHub App or custom app (not Actions user), check workflow triggers include the necessary events, and verify app permissions include CI triggers.

### Authentication errors

Confirm API key is valid and has sufficient permissions. For Bedrock/Vertex, check credentials configuration and ensure secrets are named correctly in workflows.

## Advanced configuration

### Action parameters

The Claude Code Action v1 uses a simplified configuration:

| Parameter             | Description                                                        | Required |
| --------------------- | ------------------------------------------------------------------ | -------- |
| `prompt`              | Instructions for Claude (plain text or a [skill](/en/skills) name) | No\*     |
| `claude_args`         | CLI arguments passed to Claude Code                                | No       |
| `plugin_marketplaces` | Newline-separated list of plugin marketplace Git URLs              | No       |
| `plugins`             | Newline-separated list of plugin names to install before execution | No       |
| `anthropic_api_key`   | Claude API key                                                     | Yes\*\*  |
| `github_token`        | GitHub token for API access                                        | No       |
| `trigger_phrase`      | Custom trigger phrase (default: "@claude")                         | No       |
| `use_bedrock`         | Use Amazon Bedrock instead of Claude API                           | No       |
| `use_vertex`          | Use Google Vertex AI instead of Claude API                         | No       |

\*Prompt is optional - when omitted for issue/PR comments, Claude responds to trigger phrase\
\*\*Required for direct Claude API, not for Bedrock/Vertex

#### Pass CLI arguments

The `claude_args` parameter accepts any Claude Code CLI arguments:

```yaml theme={null}
claude_args: "--max-turns 5 --model claude-sonnet-5 --mcp-config /path/to/config.json"
```

Common arguments:

* `--max-turns`: Maximum conversation turns (default: 10)
* `--model`: Model to use (for example, `claude-sonnet-5`)
* `--mcp-config`: Path to MCP configuration
* `--allowedTools`: Comma-separated list of allowed tools. The `--allowed-tools` alias also works.
* `--debug`: Enable debug output

### Alternative integration methods

While the `/install-github-app` command is the recommended approach, you can also:

* **Custom GitHub App**: For organizations needing branded usernames or custom authentication flows. Create your own GitHub App with required permissions (contents, issues, pull requests) and use the actions/create-github-app-token action to generate tokens in your workflows.
* **Manual GitHub Actions**: Direct workflow configuration for maximum flexibility
* **MCP Configuration**: Dynamic loading of Model Context Protocol servers

See the [Claude Code Action documentation](https://github.com/anthropics/claude-code-action/blob/main/docs) for detailed guides on authentication, security, and advanced configuration.

### Customizing Claude's behavior

You can configure Claude's behavior in two ways:

1. **CLAUDE.md**: Define coding standards, review criteria, and project-specific rules in a `CLAUDE.md` file at the root of your repository. Claude will follow these guidelines when creating PRs and responding to requests. Check out our [Memory documentation](/en/memory) for more details.
2. **Custom prompts**: Use the `prompt` parameter in the workflow file to provide workflow-specific instructions. This allows you to customize Claude's behavior for different workflows or tasks.

Claude will follow these guidelines when creating PRs and responding to requests.


================================================================================
# PAGE: common-workflows
# source: https://docs.claude.com/en/docs/claude-code/common-workflows.md
================================================================================

> ## Documentation Index
> Fetch the complete documentation index at: https://code.claude.com/docs/llms.txt
> Use this file to discover all available pages before exploring further.

# Common workflows

> Step-by-step guides for exploring codebases, fixing bugs, refactoring, testing, and other everyday tasks with Claude Code.

This page collects short recipes for everyday development. For higher-level guidance on prompting and context management, see [Best practices](/en/best-practices).

This page covers:

* [Prompt recipes](#prompt-recipes) for exploring code, fixing bugs, refactoring, testing, PRs, and documentation
* [Resume previous conversations](#resume-previous-conversations) so a task can span multiple sittings
* [Run parallel sessions with worktrees](#run-parallel-sessions-with-worktrees) so concurrent edits don't collide
* [Plan before editing](#plan-before-editing) to review changes before they touch disk
* [Delegate research to subagents](#delegate-research-to-subagents) to keep your main context clean
* [Pipe Claude into scripts](#pipe-claude-into-scripts) for CI and batch processing

## Prompt recipes

These are prompt patterns for everyday tasks like exploring unfamiliar code, debugging, refactoring, writing tests, and creating PRs. Each works in any Claude Code surface; adapt the wording to your project.

### Understand new codebases

For configuring Claude Code in a monorepo or large codebase, see [Monorepos and large repos](/en/large-codebases).

#### Get a quick codebase overview

Suppose you've just joined a new project and need to understand its structure quickly.

<Steps>
  <Step title="Navigate to the project root directory">
    ```bash theme={null}
    cd /path/to/project 
    ```
  </Step>

  <Step title="Start Claude Code">
    ```bash theme={null}
    claude 
    ```
  </Step>

  <Step title="Ask for a high-level overview">
    ```text theme={null}
    give me an overview of this codebase
    ```
  </Step>

  <Step title="Dive deeper into specific components">
    ```text theme={null}
    explain the main architecture patterns used here
    ```

    ```text theme={null}
    what are the key data models?
    ```

    ```text theme={null}
    how is authentication handled?
    ```
  </Step>
</Steps>

<Tip>
  Tips:

  * Start with broad questions, then narrow down to specific areas
  * Ask about coding conventions and patterns used in the project
  * Request a glossary of project-specific terms
</Tip>

#### Find relevant code

Suppose you need to locate code related to a specific feature or functionality.

<Steps>
  <Step title="Ask Claude to find relevant files">
    ```text theme={null}
    find the files that handle user authentication
    ```
  </Step>

  <Step title="Get context on how components interact">
    ```text theme={null}
    how do these authentication files work together?
    ```
  </Step>

  <Step title="Understand the execution flow">
    ```text theme={null}
    trace the login process from front-end to database
    ```
  </Step>
</Steps>

<Tip>
  Tips:

  * Be specific about what you're looking for
  * Use domain language from the project
  * Install a [code intelligence plugin](/en/discover-plugins#code-intelligence) for your language to give Claude precise "go to definition" and "find references" navigation
</Tip>

***

### Fix bugs efficiently

Suppose you've encountered an error message and need to find and fix its source.

<Steps>
  <Step title="Share the error with Claude">
    ```text theme={null}
    I'm seeing an error when I run npm test
    ```
  </Step>

  <Step title="Ask for fix recommendations">
    ```text theme={null}
    suggest a few ways to fix the @ts-ignore in user.ts
    ```
  </Step>

  <Step title="Apply the fix">
    ```text theme={null}
    update user.ts to add the null check you suggested
    ```
  </Step>
</Steps>

<Tip>
  Tips:

  * Tell Claude the command to reproduce the issue and get a stack trace
  * Mention any steps to reproduce the error
  * Let Claude know if the error is intermittent or consistent
</Tip>

***

### Refactor code

Suppose you need to update old code to use modern patterns and practices.

<Steps>
  <Step title="Identify legacy code for refactoring">
    ```text theme={null}
    find deprecated API usage in our codebase
    ```
  </Step>

  <Step title="Get refactoring recommendations">
    ```text theme={null}
    suggest how to refactor utils.js to use modern JavaScript features
    ```
  </Step>

  <Step title="Apply the changes safely">
    ```text theme={null}
    refactor utils.js to use ES2024 features while maintaining the same behavior
    ```
  </Step>

  <Step title="Verify the refactoring">
    ```text theme={null}
    run tests for the refactored code
    ```
  </Step>
</Steps>

<Tip>
  Tips:

  * Ask Claude to explain the benefits of the modern approach
  * Request that changes maintain backward compatibility when needed
  * Do refactoring in small, testable increments
</Tip>

***

### Work with tests

Suppose you need to add tests for uncovered code.

<Steps>
  <Step title="Identify untested code">
    ```text theme={null}
    find functions in NotificationsService.swift that are not covered by tests
    ```
  </Step>

  <Step title="Generate test scaffolding">
    ```text theme={null}
    add tests for the notification service
    ```
  </Step>

  <Step title="Add meaningful test cases">
    ```text theme={null}
    add test cases for edge conditions in the notification service
    ```
  </Step>

  <Step title="Run and verify tests">
    ```text theme={null}
    run the new tests and fix any failures
    ```
  </Step>
</Steps>

Claude can generate tests that follow your project's existing patterns and conventions. When asking for tests, be specific about what behavior you want to verify. Claude examines your existing test files to match the style, frameworks, and assertion patterns already in use.

For comprehensive coverage, ask Claude to identify edge cases you might have missed. Claude can analyze your code paths and suggest tests for error conditions, boundary values, and unexpected inputs that are easy to overlook.

***

### Create pull requests

You can create pull requests by asking Claude directly ("create a pr for my changes"), or guide Claude through it step-by-step:

<Steps>
  <Step title="Summarize your changes">
    ```text theme={null}
    summarize the changes I've made to the authentication module
    ```
  </Step>

  <Step title="Generate a pull request">
    ```text theme={null}
    create a pr
    ```
  </Step>

  <Step title="Review and refine">
    ```text theme={null}
    enhance the PR description with more context about the security improvements
    ```
  </Step>
</Steps>

When you create a PR using `gh pr create`, the session is automatically linked to that PR. To return to it later, run `claude --from-pr <number>` or paste the PR URL into the [`/resume` picker](/en/sessions#use-the-session-picker) search.

<Tip>
  Review Claude's generated PR before submitting and ask Claude to highlight potential risks or considerations.
</Tip>

### Handle documentation

Suppose you need to add or update documentation for your code.

<Steps>
  <Step title="Identify undocumented code">
    ```text theme={null}
    find functions without proper JSDoc comments in the auth module
    ```
  </Step>

  <Step title="Generate documentation">
    ```text theme={null}
    add JSDoc comments to the undocumented functions in auth.js
    ```
  </Step>

  <Step title="Review and enhance">
    ```text theme={null}
    improve the generated documentation with more context and examples
    ```
  </Step>

  <Step title="Verify documentation">
    ```text theme={null}
    check if the documentation follows our project standards
    ```
  </Step>
</Steps>

<Tip>
  Tips:

  * Specify the documentation style you want (JSDoc, docstrings, etc.)
  * Ask for examples in the documentation
  * Request documentation for public APIs, interfaces, and complex logic
</Tip>

***

### Work in notes and non-code folders

Claude Code works in any directory. Run it inside a notes vault, a documentation folder, or any collection of markdown files to search, edit, and reorganize content the same way you would code.

The `.claude/` directory and `CLAUDE.md` sit alongside other tools' config directories without conflict. Claude reads files fresh on each tool call, so it sees edits you make in another application the next time it reads that file.

***

### Work with images

Suppose you need to work with images in your codebase, and you want Claude's help analyzing image content.

<Steps>
  <Step title="Add an image to the conversation">
    You can use any of these methods:

    1. Drag and drop an image into the Claude Code window
    2. Copy an image and paste it into the CLI with ctrl+v (Do not use cmd+v)
    3. Provide an image path to Claude. E.g., "Analyze this image: /path/to/your/image.png"
  </Step>

  <Step title="Ask Claude to analyze the image">
    ```text theme={null}
    What does this image show?
    ```

    ```text theme={null}
    Describe the UI elements in this screenshot
    ```

    ```text theme={null}
    Are there any problematic elements in this diagram?
    ```
  </Step>

  <Step title="Use images for context">
    ```text theme={null}
    Here's a screenshot of the error. What's causing it?
    ```

    ```text theme={null}
    This is our current database schema. How should we modify it for the new feature?
    ```
  </Step>

  <Step title="Get code suggestions from visual content">
    ```text theme={null}
    Generate CSS to match this design mockup
    ```

    ```text theme={null}
    What HTML structure would recreate this component?
    ```
  </Step>
</Steps>

<Tip>
  Tips:

  * Use images when text descriptions would be unclear or cumbersome
  * Include screenshots of errors, UI designs, or diagrams for better context
  * You can work with multiple images in a conversation
  * Image analysis works with diagrams, screenshots, mockups, and more
  * When Claude references images (for example, `[Image #1]`), `Cmd+Click` (Mac) or `Ctrl+Click` (Windows/Linux) the link to open the image in your default viewer
</Tip>

***

### Reference files and directories

Use @ to quickly include files or directories without waiting for Claude to read them.

<Steps>
  <Step title="Reference a single file">
    ```text theme={null}
    Explain the logic in @src/utils/auth.js
    ```

    This includes the full content of the file in the conversation.
  </Step>

  <Step title="Reference a directory">
    ```text theme={null}
    What's the structure of @src/components?
    ```

    This provides a directory listing with file information.
  </Step>

  <Step title="Reference MCP resources">
    ```text theme={null}
    Show me the data from @github:repos/owner/repo/issues
    ```

    This fetches data from connected MCP servers using the format @server:resource. See [MCP resources](/en/mcp#use-mcp-resources) for details.
  </Step>
</Steps>

<Tip>
  Tips:

  * File paths can be relative or absolute
  * @ file references add `CLAUDE.md` in the file's directory and parent directories to context
  * Directory references show file listings, not contents
  * You can reference multiple files in a single message (for example, "@file1.js and @file2.js")
</Tip>

***

### Run Claude on a schedule

Suppose you want Claude to handle a task automatically on a recurring basis, like reviewing open PRs every morning, auditing dependencies weekly, or checking for CI failures overnight.

Pick a scheduling option based on where you want the task to run:

| Option                                                 | Where it runs                     | Best for                                                                                                                                                                                                 |
| :----------------------------------------------------- | :-------------------------------- | :------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| [Routines](/en/routines)                               | Anthropic-managed infrastructure  | Tasks that should run even when your computer is off. Can also trigger on API calls or GitHub events in addition to a schedule. Configure at [claude.ai/code/routines](https://claude.ai/code/routines). |
| [Desktop scheduled tasks](/en/desktop-scheduled-tasks) | Your machine, via the desktop app | Tasks that need direct access to local files, tools, or uncommitted changes.                                                                                                                             |
| [GitHub Actions](/en/github-actions)                   | Your CI pipeline                  | Tasks tied to repo events like opened PRs, or cron schedules that should live alongside your workflow config.                                                                                            |
| [`/loop`](/en/scheduled-tasks)                         | The current CLI session           | Quick polling while a session is open. Tasks stop when you start a new conversation; `--resume` and `--continue` restore unexpired ones.                                                                 |

<Tip>
  When writing prompts for scheduled tasks, be explicit about what success looks like and what to do with results. The task runs autonomously, so it can't ask clarifying questions. For example: "Review open PRs labeled `needs-review`, leave inline comments on any issues, and post a summary in the `#eng-reviews` Slack channel."
</Tip>

***

### Ask Claude about its capabilities

Claude has built-in access to its documentation and can answer questions about its own features and limitations.

#### Example questions

```text theme={null}
can Claude Code create pull requests?
```

```text theme={null}
how does Claude Code handle permissions?
```

```text theme={null}
what skills are available?
```

```text theme={null}
how do I use MCP with Claude Code?
```

```text theme={null}
how do I configure Claude Code for Amazon Bedrock?
```

```text theme={null}
what are the limitations of Claude Code?
```

<Note>
  Claude provides documentation-based answers to these questions. For hands-on demonstrations, run `/powerup` for interactive lessons with animated demos, or refer to the specific workflow sections above.
</Note>

<Tip>
  Tips:

  * Claude always has access to the latest Claude Code documentation, regardless of the version you're using
  * Ask specific questions to get detailed answers
  * Claude can explain complex features like MCP integration, enterprise configurations, and advanced workflows
</Tip>

***

## Resume previous conversations

When a task spans multiple sittings, pick up where you left off instead of re-explaining context. Claude Code saves every conversation locally.

```bash theme={null}
claude --continue
```

This resumes the most recent session in the current directory; if there isn't one yet, it prints `No conversation found to continue` and exits. Use `claude --resume` to choose from a list, or `/resume` from inside a running session. See [Manage sessions](/en/sessions) for naming, branching, and the full picker reference.

## Run parallel sessions with worktrees

Work on a feature in one terminal while Claude fixes a bug in another, without the edits colliding. Each worktree is a separate checkout on its own branch.

```bash theme={null}
claude --worktree feature-auth
```

Run the same command with a different name in a second terminal to start an isolated parallel session. See [Worktrees](/en/worktrees) for cleanup, `.worktreeinclude`, and non-git VCS support. To monitor parallel sessions from one screen instead of separate terminals, see [background agents](/en/agent-view).

## Plan before editing

For changes you want to review before they touch disk, switch to plan mode. Claude reads files and proposes a plan but makes no edits until you approve.

```bash theme={null}
claude --permission-mode plan
```

You can also press `Shift+Tab` mid-session to toggle into plan mode. See [Plan mode](/en/permission-modes#analyze-before-you-edit-with-plan-mode) for the approval flow and editing the plan in your text editor.

## Delegate research to subagents

Exploring a large codebase fills your context with file reads. Delegate the exploration so only the findings come back.

```text theme={null}
use a subagent to investigate how our auth system handles token refresh
```

The subagent reads files in its own context window and reports a summary. See [Subagents](/en/sub-agents) for defining custom agents with their own tools and prompts.

## Pipe Claude into scripts

Run Claude non-interactively for CI, pre-commit hooks, or batch processing. Stdin and stdout work like any Unix tool.

```bash theme={null}
git log --oneline -20 | claude -p "summarize these recent commits"
```

See [Non-interactive mode](/en/headless) for output formats, permission flags, and fan-out patterns.

## Next steps

<CardGroup cols={2}>
  <Card title="Best practices" icon="lightbulb" href="/en/best-practices">
    Patterns for getting the most out of Claude Code
  </Card>

  <Card title="Manage sessions" icon="rotate-left" href="/en/sessions">
    Resume, name, and branch conversations
  </Card>

  <Card title="Worktrees" icon="code-branch" href="/en/worktrees">
    Run isolated parallel sessions
  </Card>

  <Card title="Extend Claude Code" icon="puzzle-piece" href="/en/features-overview">
    Add skills, hooks, MCP, subagents, and plugins
  </Card>
</CardGroup>
