# PromptFuzzer

**Fuzz testing for LLM applications.** Describe a task, give a few example inputs with their expected outputs,
and PromptFuzzer generates hundreds of adversarial variations (paraphrases, typos, slang, negations,
contradictions, ambiguity, irrelevant context, edge cases, prompt injection), runs them against real models,
grades every answer with evidence, and clusters the failures into actionable groups with prompt-fix suggestions.

The first supported use case is **natural language → structured JSON** (for example "turn off the kitchen lights"
→ `{"action": "turn_off", "device": "lights", "room": "kitchen"}`), which is where LLM features most often break
silently in production. The engine is task-agnostic: anything with a system prompt and a JSON contract can be tested.

![Report dashboard](docs/screenshots/demo.png)

## Why it is more than an API wrapper

- **Deterministic grading first.** Output is extracted and parsed, validated against a JSON Schema, and diffed field
  by field against the expectation. An AI judge is only consulted for fields that genuinely need semantic
  understanding (a free-text `question` or `title`), and every verdict records the *weakest evidence it relied on*:
  `DETERMINISTIC`, `AI_JUDGE` or `HEURISTIC`. The dashboard never presents an AI opinion as a verified fact.
- **Expected behavior is part of the test.** Each case is labeled `MATCH_EXPECTED`, `ASK_CLARIFICATION` or
  `REFUSE`. "Turn on the lights" (no room) should produce a clarification, not a confident guess, and the tool
  tests for that separately from meaning-preserving variations.
- **Statistics that respect small samples.** Pass rates come with 95% Wilson intervals, tests can be repeated to
  measure consistency, and latency is reported as p50/p95.
- **Failure taxonomy.** Every failure is classified by a priority-ordered rule set (unexpected refusal, truncation,
  invalid structure, prompt injection, ambiguity handling, negation, missing or hallucinated information,
  misinterpretation, semantic mismatch, formatting) and grouped into clusters with rule-based suggestions.
  An optional AI analyst adds a narrative summary, clearly labeled as AI-generated.
- **Adversarial inputs are data, not instructions.** Generated inputs and model outputs are wrapped in randomly
  tagged fences before they reach the generator, judge or analyst, and the fence cannot be closed from inside.
- **Cost and rate safeguards.** A per-run budget (requests and dollars) is checked before every call, requests
  are rate-limited per provider, and 408/409/429/5xx are retried with jittered exponential backoff that honors
  `retry-after`. Every attempt counts as a request, and the per-run request ceiling is sized for the retry
  policy, with `PF_MAX_REQUESTS` as the hard limit. The dashboard shows a typical and worst-case estimate before
  a run starts.
- **Run problems are not model failures.** Every result is `PASS`, `FAIL` (graded), `ERROR` (the call or the
  grading failed) or `SKIPPED` (never sent: request limit, cost limit, unreachable endpoint, cancellation), and
  each error or skip records its cause. Only graded results enter a pass rate. When nothing was graded the pass
  rate and its interval are shown as N/A, not 0%, and a run with errors or skipped calls finishes as
  "completed with errors" (or "no gradable results"), never as a plain success.
- **Fail fast on unreachable servers.** Before a run or generation, local OpenAI-compatible servers such as
  Ollama are asked for their model list (`GET /models`, no prompt or test data), so a stopped server or a model
  that has not been pulled is reported in seconds with the fix. Refused connections are not retried, and after
  three in a row the endpoint is no longer called for the rest of the run.

## Quick start

Requirements: **Java 21+**. Maven is not needed; the wrapper downloads it.

```sh
export GEMINI_API_KEY=...   # free, see below; or ANTHROPIC_API_KEY / GROQ_API_KEY; or none for Ollama
./run.sh                    # Windows: run.cmd
```

Open <http://127.0.0.1:7070>. Without any key, choose **Open demo report** to explore a full sample report, or
use a local Ollama model.

### Free and local models

You do not need a paid API. PromptFuzzer picks the first provider whose key is set (Anthropic, then Gemini, then
Groq) as the default generator, judge and target, and every provider can be chosen per model in the dashboard.

| Option | Cost | Setup | Model spec |
|---|---|---|---|
| **Google Gemini** | Free tier, no card | Create a key at <https://aistudio.google.com/apikey>, then `export GEMINI_API_KEY=...` | `gemini:gemini-2.5-flash` |
| **Groq** | Free tier, no card | Create a key at <https://console.groq.com/keys>, then `export GROQ_API_KEY=...` | `groq:llama-3.3-70b-versatile` |
| **Ollama** | Free, runs locally | Install from <https://ollama.com>, then `ollama pull qwen2.5:7b` | `ollama:qwen2.5:7b` |
| Anthropic | Paid | `export ANTHROPIC_API_KEY=...` | `claude-sonnet-5-5` |
| Any OpenAI-compatible server | Varies | Base URL plus a key variable, or `none` | `openai:<model>@<base-url>#<KEY_ENV>` |

A complete free run from the command line, with Gemini generating the tests and acting as the judge:

```sh
export GEMINI_API_KEY=...
java -jar target/promptfuzzer.jar generate --task src/main/resources/examples/smart-home-commands.json \
     --out suite.json --model gemini:gemini-2.5-flash --per-category 3
java -jar target/promptfuzzer.jar run --suite suite.json --model gemini:gemini-2.5-flash \
     --judge gemini:gemini-2.5-flash --out report.json
```

And fully offline with Ollama (no key, nothing leaves your computer):

```sh
ollama pull qwen2.5:7b
java -jar target/promptfuzzer.jar run --suite suite.json --model ollama:qwen2.5:7b --judge ollama:qwen2.5:7b
```

Notes:
- Free tiers allow only a few requests per minute. PromptFuzzer slows down to 10 requests per minute for Gemini
  and 30 for Groq automatically, and retries the provider's 429 responses, so a 60-test run on Gemini takes a
  few minutes. Model names and free-tier limits change over time; check the provider's model list if a model id
  is rejected.
- Free-tier models have no built-in price, so the cost estimate shows them as unpriced; the request ceiling
  (`PF_MAX_REQUESTS`) still applies. Local servers are priced at zero.
- Small local models make weaker generators and judges than hosted ones. For better test generation, use Gemini
  or Groq as the generator and judge and Ollama as the target. If Ollama truncates long prompts, raise its context
  window with `OLLAMA_CONTEXT_LENGTH=8192 ollama serve`.
- Each preset sends its key only to its own URL, and the Ollama preset sends no key at all.

The equivalent manual steps:

```sh
./mvnw package          # builds target/promptfuzzer.jar and runs the 90 tests
java -jar target/promptfuzzer.jar
```

### The workflow

1. **Task**: load an example (smart-home commands or calendar events) or describe your own: system prompt,
   output JSON Schema, the clarification and refusal shapes, free-text fields, and seed examples.
2. **Generate**: pick categories and how many AI cases per category. Rule-based mutations (typos, casual
   rewriting, irrelevant context) are added deterministically from a seed, so they cost nothing and reproduce
   exactly. Review the suite: cases whose expectation the AI proposed (rather than copied from a seed) are flagged
   for review, and you can delete or keep them.
3. **Run**: choose up to four target models (Anthropic, any OpenAI-compatible server, or your own HTTP endpoint),
   repeats, concurrency, an optional AI judge and a budget. The estimate updates live.
4. **Report**: KPIs with confidence intervals, outcomes by evidence type, per-category pass rates, model
   comparison, latency distribution, failure clusters with suggestions, and a filterable results explorer. Each
   test opens a drawer with the input, expected vs actual output (differences highlighted), every check that ran
   and the raw model response. Reports export as JSON.

| Generate | Test detail |
|---|---|
| ![Task](docs/screenshots/task.png) | ![Detail drawer](docs/screenshots/drawer.png) |

## CLI and CI gating

```sh
java -jar target/promptfuzzer.jar generate --task src/main/resources/examples/smart-home-commands.json \
     --out suite.json --per-category 4 --categories PARAPHRASE,NEGATION,AMBIGUITY,INJECTION
java -jar target/promptfuzzer.jar run --suite suite.json --model claude-sonnet-5-5,claude-haiku-5-5 \
     --repeats 2 --judge claude-haiku-5-5 --max-cost 1.50 --min-pass-rate 0.9 --out report.json
```

`run` exits with status 1 when any model falls below `--min-pass-rate`, so a suite can gate a prompt change in
CI. Model specs work as in [Free and local models](#free-and-local-models); any other OpenAI-compatible server
(vLLM, LM Studio, OpenRouter, OpenAI) uses `openai:<model>@<base-url>`, with `#KEY_ENV` or `#none` to choose its key.
`--per-category 0` produces a rule-based suite with no API calls. `java -jar target/promptfuzzer.jar help` lists
every option.

## Configuration

| Variable | Default | Purpose |
|---|---|---|
| `ANTHROPIC_API_KEY` | | Anthropic models |
| `GEMINI_API_KEY` | | Google Gemini through its OpenAI-compatible endpoint (free tier) |
| `GROQ_API_KEY` | | Groq (free tier) |
| `OPENAI_API_KEY` | | Other OpenAI-compatible servers (any name matching `*_API_KEY`, `*_TOKEN` or `*_AUTH` can be chosen per target) |
| `PF_ENDPOINT_AUTH` | | Bearer token for a custom HTTP endpoint target |
| `PF_HOST` / `PF_PORT` | `127.0.0.1` / `7070` | Dashboard address |
| `PF_DATA_DIR` | `promptfuzzer-data` | Saved suites and reports (plain JSON) |
| `PF_MAX_COST_USD` | `2.00` | Ceiling on any single run's budget |
| `PF_MAX_REQUESTS` | `600` | Ceiling on API calls per run or generation |
| `PF_MAX_TESTS` | `150` | Maximum suite size |
| `PF_REQUESTS_PER_MINUTE` | `50` | Rate limit per provider |
| `PF_DEFAULT_MODEL` | first provider with a key | Default generator, judge and target, as a model spec such as `ollama:qwen2.5:7b` |

Keys are read only from environment variables. The browser never sees them: it receives only whether each
provider is configured, and a target can name a different variable but only one that looks like a key variable.

### Custom endpoints

To test your own application rather than a raw model, choose the **HTTP endpoint** provider. PromptFuzzer sends
`POST {"input": "<test input>"}` to your URL and grades the response body, or the field selected with a JSON
pointer such as `/result`.

## Architecture

```
io.promptfuzzer
├── core         TaskSpec, TestCase, TestSuite, categories, DataFence, JSON mapper
├── llm          ChatModel + Anthropic (official Java SDK), OpenAI-compatible and HTTP endpoint clients;
│                Budget, RateLimiter, ResilientChatModel (retries), Pricing
├── generation   RuleBasedMutator (seeded), AiTestGenerator, SuiteBuilder (parallel, dedupe, cap)
├── execution    TestRunner (virtual threads, bounded concurrency, repeats, cancellation)
├── evaluation   JsonExtractor, SchemaValidator, JsonDiff, Evaluator, AiJudge, FailureType
├── analysis     ReportBuilder (Wilson CI, consistency, percentiles, clusters), Suggestions, AiFailureAnalyst
├── app          GenerationService, RunService (live progress), CostEstimator, FileStore, DemoBuilder
├── web          Javalin REST API + static dashboard (vanilla JS modules, inline SVG charts, no build step)
└── cli          generate / run / demo / serve
```

The evaluator runs in a fixed order: refusal and truncation checks, output extraction (pure JSON, fenced, embedded
or none), a raw-JSON formatting check, schema validation, then behavior checks. Structural differences are typed
(`MISSING`, `UNEXPECTED`, `MISMATCH`, `TYPE_MISMATCH`) and drive the failure classification. Comparison options per
task control case sensitivity, numeric tolerance, array order and whether `null` equals a missing field.

The local web server only answers requests whose `Host` is a loopback address (against DNS rebinding), rejects
cross-origin `Origin` headers, and requires `application/json` on every write, so other websites cannot drive it
from your browser. Stored ids are validated against a strict pattern before they touch the filesystem.

## Demo mode

`/api/demo`, the **Open demo report** button and `promptfuzzer demo` show a report built from a bundled,
hand-written recording of two **fictional** models (`sample-model-large`, `sample-model-small`). The recorded
outputs are replayed through the real evaluator and analyzer, but latency, tokens and costs are synthetic. The
report is labeled as sample data everywhere it appears. The real workflow never uses recorded or simulated
results.

## Testing

`./mvnw verify` runs 90 tests: evaluator, JSON tools, safeguards (budget, rate limiter, retry and backoff,
retry-after parsing, key-variable allowlist), generation, report statistics, the CLI, and an end-to-end test that
drives the whole HTTP API against a local OpenAI-compatible mock server. The mock returns a deliberately naive
"model" that ignores negation and wraps some answers in code fences, plus an injected 429, and the test asserts
the run retries the 429 and produces the expected `NEGATION_MISUNDERSTANDING` and `FORMATTING` clusters. A second
end-to-end test runs `generate` and `run` from the CLI with no API key at all against a keyless local server,
and checks that no Authorization header is ever sent. Run-outcome tests cover runs made only of connection errors,
only of request-limit skips, and a mix of all four verdicts; an unreachable loopback port (a real refused
connection, no Ollama needed) that must end the run in seconds; the dollar limit capping spend independently of
the request ceiling; the pre-run model check against a stub model list; and reports saved by earlier versions,
which are reclassified when opened.

## Limitations

- **LLM behavior is probabilistic.** A pass rate is a measurement on a sample, not a guarantee; that is why
  intervals and repeats exist. Newer Anthropic models such as Claude Opus 5.5 do not accept a temperature
  setting, so runs are not bit-for-bit reproducible. Rule-based mutations are reproducible from their seed; AI
  generation is not.
- **The AI judge can be wrong.** Judged passes are labeled and reported separately from verified passes. Without
  a judge, free-text fields fall back to a word-overlap heuristic that is crude and is labeled `HEURISTIC`.
- **AI-proposed expectations need review.** When the generator changes the expected output (for example for a
  negation), the case is flagged; a wrong expectation produces a wrong verdict.
- **Cost figures are estimates.** Estimates assume about 3.5 characters per token and a typical output length;
  actual cost uses the provider's token counts and a built-in price table, which may lag price changes. Unknown
  models can be given prices manually. With high concurrency a run can overshoot its budget by at most the calls
  already in flight.
- **Scope.** JSON-output tasks only for now, and a practical subset of JSON Schema (types, `required`,
  `properties`, `additionalProperties`, `enum`, `const`, `items`, numeric, length and item-count bounds, `pattern`), not
  `$ref` or `oneOf`. No streaming, tool-use or multi-turn tests yet.
- **Not yet exercised against live APIs.** The Anthropic client is written against the official Java SDK and the
  Gemini, Groq and Ollama presets against their documented OpenAI-compatible endpoints, and the full workflow is
  tested end to end against an OpenAI-compatible mock, but no API key was available while this was built.
- Single-user and local by design: there is no authentication, and the server binds to `127.0.0.1`.
