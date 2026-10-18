# Code Index (`query_clojure`)

Status: draft LLD for slice A of issue #3 (markaddleman/clj-surgeon). The HLD
section is "Code index: answer read questions from fresh var facets" in
`docs/high-level-design.md`. The specification is
[code-index-specs.md](code-index-specs.md) (MCP-OP-QUERY-001 through
MCP-OP-QUERY-090, draft).

## Context

Agents orient in a Clojure module by reading all of it. In the median
code-work session of the measured corpus (issue #3), 33% of the cost goes to
tool results that read or search Clojure source, and most of that is whole-file
or large-range reads of one module. A result stays in context for each later
call, so its cost grows with the length of the session.

A localization quiz on 30 merged PRs measured a view that replaces the
orientation read. It is one line for each var, made only from clj-kondo facts
and docstrings. With it, a model found the changed vars with recall 0.586
against 0.603 for full source, equal precision, and 25% of the input tokens.

`query_clojure` serves that view, and the reads that follow it, from an index
that is never stale.

## What the tool is, and what it is not

- It is a **read** tool. It grants no mutation authority and writes no file in
  the workspace.
- It answers from an index of the workspace source, made by clj-kondo. Facts
  that clj-kondo cannot see (a var that a macro defines without a `:lint-as`
  entry, a call through `resolve`, a multimethod dispatch) are not in the index.
  The tool does not claim that a var has no other uses.
- It is not a search over the text. A string, a comment or a quoted symbol is
  not a use.
- It does not replace `inspect_clojure`. A write still starts there.

## Surface

### Tool

`query_clojure`, registered in the `:full` profile, outcome classes
`#{:read-success :typed-refusal}`, annotations read-only, idempotent, not open
world.

Input (JSON object):

| Field | Type | Required | Meaning |
|---|---|---|---|
| `expression` | string | yes | One Clojure form. It may call the functions below, compose them with pure `clojure.core`, and `def` values for later calls of the same session. |
| `workspace_root` | string | no | Canonical absolute directory, as for the other tools. The default is the server project root. |

Output (structured content), on success:

| Field | Meaning |
|---|---|
| `ok` | `true` |
| `operation` | `"query-clojure"` |
| `value` | The value of the expression, rendered as text (see Rendering). |
| `value_chars` | The length of `value`. |
| `session` | `{defs, reset}`: the names that the session holds, and `true` when the server dropped an earlier context of this session (see Sessions). |
| `files` | The files that the answer read, each with its SHA-256 at ingest. |
| `index` | `{files, vars, skipped, built_ms}` of the workspace index. |
| `reingested` | The files that the stale check ingested again for this request. |
| `read_complete` | `false` when discovery skipped a file (too large, unreadable), with `skipped` evidence. |
| `workspace_root`, `elapsed_ms` | As for the other tools. |

On refusal: `ok=false`, `error_type`, `error`, and either a `next_call` (a
narrower call that the server computed) or a `remedy`. A `next_call` never
equals the call just made.

### A REPL over the index

The tool gives the agent a REPL-like experience over the code base (owner
decision). The functions return **data**, so that an expression can filter,
sort, group and join before anything is rendered, and a session can keep
intermediate values with `def`:

```clojure
(def s (skeleton 'modernization.mcp.tools.scope))
(->> s (filter :throws?) (map :name))
(->> (refs 'modernization.mcp.tools.scope/scope-value) (map :ns) frequencies)
(form (map :qname (take 2 (filter :private? s))))
```

Only the final value of an expression is rendered (see Rendering). Each kind
of value from the functions has a compact rendering, so `(skeleton 'a.b)` on
its own prints the one-line-per-var view.

### Functions

The SCI context binds these functions in `user`, and nothing else from the
host. Each returns plain data: maps, vectors, strings, numbers, booleans. No
function returns a host object, the index, or a reference into it.

| Function | Returns |
|---|---|
| `(namespaces)` | A vector of `{:kind :ns, :ns, :file, :vars, :doc}`, one for each namespace in the index, sorted by name. |
| `(skeleton ns)` | A vector of var maps (below), one for each var of `ns`, in source order, with `:meta {:kind :skeleton, :ns, :doc}`. |
| `(form sym)`, `(form [sym …])` | A vector of `{:kind :form, :qname, :file, :start, :end, :source}`. |
| `(refs sym)`, `(refs sym opts)` | A vector of `{:kind :use, :file, :line, :ns, :from, :use-kind}`. `opts`: `{:include-tests true\|false}`, default `true`. |
| `(pull pattern eid)` | The pull result (maps and vectors of attribute values). |
| `(help)`, `(help fn-name)` | A string: the functions, their arguments, and examples. |

A **var map** is:

```clojure
{:kind :var, :qname "a.b/f", :ns "a.b", :name "f", :file "src/a/b.clj",
 :start 12, :end 30, :arglists ["[x]" "[x y]"], :private? false,
 :intent "First sentence.", :calls ["a.b/g" "c.d/h"], :fx ["swap!"], :throws? true}
```

`ns` and `sym` may be symbols or strings. `sym` is qualified (`ns/name`).

#### Var line

The rendering of a var map, and so of a skeleton:

```
ns modernization.mcp.tools.scope — <first sentence of the ns docstring>
λ scope-value([{:keys [db application version case-class]}]) | calls item/error-item,versions-of | throws — The scope value of `:application` …
λ shown([{:keys [x]}])
```

A var line is:

`λ <name>(<arglists joined by " | ">)` then, each only when present:

- ` | calls <c1>,<c2>,…` — the vars that it calls, in sorted order, relative to
  `ns` (a bare name inside `ns`, else the last segment of the other namespace
  and the name). Calls into `clojure.*` are left out.
- ` | fx <f1>,…` — calls of the side-effect functions in a fixed list
  (`swap!`, `reset!`, `spit`, `transact`, `transact!`, `sh`, `shell`,
  `process`, `delete-file`, `send`, `put!`, `>!`, `future`, `println`).
- ` | throws` — the body has `(throw` or `(ex-info`.
- ` | private` — the var is private.
- ` — <first sentence of the docstring>` — the text up to the first `.`, `!`
  or `?` followed by a space or the end, at most 200 characters.

A rendered skeleton has no result cap below the transport ceiling (see
Bounds). It is the default answer to "what is in this module".

#### Other renderings

- **Form map:** a header line `;; ns/name  path:start-end`, then the exact
  source text of the top-level form, from its first line to its last line.
- **Use map:** `<path>:<line> <ns>/<from> <use-kind>`, where use-kind is
  `call`, `alias-call`, `value` or `refer`, as in the CLI `:refs`. A vector of
  use maps ends with a total line.
- **Namespace map:** `ns <name> (<vars> vars) — <first sentence of the doc>`.
- **Pull result:** see Rendering.

#### `pull`

`pattern`: a pull pattern. `eid`: `[:var/qname "ns/name"]`, `[:ns/name "ns"]`
or `[:kw/name "name"]`. Recursion depth is at most 3 (`{:var/_calls 3}`). The
unbounded `...` is refused.

### Sessions

A session keeps its `def`s between calls, as a REPL does.

- **Identity.** One SCI context for each MCP session id and workspace root. A
  call with no session id gets a fresh context, used once.
- **What a context holds.** The values that the expressions `def`. They are
  plain data from the functions, never the index itself. A function always
  reads the latest index, so a value held from an earlier call can be older
  than the index: the agent asks again to get fresh data.
- **Bounds.** At most 32 contexts, least recently used first out, and an idle
  time of 2 hours. At most 64 `def`s in one context; a `def` over the limit is
  a refusal `session-full`. When the server drops a context and the session
  calls again, the call gets a fresh context, and `session.reset` is `true`
  in the output.
- **No state between sessions.** One session cannot see the `def`s of
  another.

## Index

### Shape

Plain Clojure data, not a database (owner decision on issue #3). One atom for
each workspace root holds one immutable map:

```clojure
{:files      {"src/a/b.clj" {:mtime 1792… :size 4211 :sha256 "…"
                             :ns "a.b" :vars ["a.b/f" "a.b/g"] :uses [<use> …]}}
 :namespaces {"a.b" {:name "a.b" :file "src/a/b.clj" :doc "…"}}
 :vars       {"a.b/f" <var record>}
 ;; derived from :vars and :files, rebuilt after each ingest
 :callers    {"a.b/g" #{"a.b/f"}}          ; reverse of :calls
 :kw-users   {"scope" #{"a.b/f"}}          ; reverse of :uses-kw
 :uses-by-target {"a.b/g" [<use> …]}       ; for refs
 :skipped    [{:file "…" :reason :too-large :size …}]
 :read-complete? true
 :built-ms   812}
```

A **var record** holds the facts of one top-level var:

| Key | Meaning |
|---|---|
| `:qname`, `:ns`, `:name` | Identity. `:qname` is `ns/name`. |
| `:file`, `:start`, `:end` | Path relative to the root, line range of the top-level form. |
| `:kind` | The defining macro, for example `clojure.core/defn`. |
| `:private?` | |
| `:arglists` | Arglist strings. |
| `:doc`, `:intent` | Docstring, and its first sentence. |
| `:source` | Exact text of the top-level form. |
| `:calls` | Qnames of the vars it calls, `clojure.*` left out. |
| `:uses-kw`, `:reads-kw` | Keywords it uses, and keywords it destructures. |
| `:throws?` | Body has `(throw` or `(ex-info`. |

A **use** is `{:file :line :from :target :kind}` for one site of `refs`.

A var that is called but defined in no indexed file has no record. It appears
only as a qname in `:calls` and `:callers`, and the renderings mark it
`external`.

`:fx` is computed at ingest from all var uses of the form, `clojure.*` included, because most names of the side-effect list are `clojure.core` functions that `:calls` leaves out.

### Discovery

The source files of a workspace are the files that the CLI `:refs` searches:
the `:paths` of each `deps.edn` and `bb.edn`, the `:source-paths` of each
`project.clj` (default `src`), and the `test` and `tests` directories next to
each build file. Extensions: `.clj`, `.cljs`, `.cljc`.

- Each path is confined to the workspace root after realpath. A symlink that
  leaves the root, and a `:paths` entry that relocates outside it, are
  dropped.
- The walk stops at a file-count cap (see Bounds). A cap hit makes
  `read_complete=false`.
- A file over the byte cap is not read. It is listed in `skipped` with its
  size, and `read_complete=false`.

### Ingest of one file

1. Stat the file (mtime, size). Read its bytes. Stat again. If the two stats
   differ, read again (at most 3 times, then skip with reason `unstable`).
2. Hash the bytes.
3. Run clj-kondo in process on the text (`with-in-str`, `:lint ["-"]`,
   `:filename` the relative path), with analysis `{:arglists true :keywords
   true :locals false}`, all linters off, hooks off, and the `:lint-as` of the
   project config.
4. Make the file entry and the var records of the file. The step is a pure
   function from (path, bytes, analysis) to data, so it is tested with
   literals.

### Update of the index

A pure function `apply-ingest` takes the index map and a set of file results
(new, changed, removed), and returns the next index map:

- It replaces the entries and var records of the changed files, adds the new
  files, and removes the removed files and their var records.
- It rebuilds `:callers`, `:kw-users` and `:uses-by-target` from the var
  records. This is a `group-by` over all vars (about 14,000 on
  pyze/modernization-mcp), a few milliseconds.
- The order of ingest does not matter: a call to a var of another file is a
  qname, resolved at read time.

The atom changes with one `reset!` of the new map, under the workspace lock.

### Freshness

Each request runs discovery and the stale check before it evaluates the
expression:

- A new file is ingested. A removed file is removed. A file whose mtime or
  size differs from its last ingest is ingested again.
- The expression then runs against the one index map that the atom held at
  that point. It cannot see a partial ingest, because the map is immutable.
- A per-workspace lock makes ingest single-writer. Reads deref the atom and
  need no lock.

No file watcher. Discovery plus one stat for each file is a few milliseconds
on about 1,330 files. A watcher is a later optimisation, behind the same
interface.

### Cold build

The first request for a workspace ingests all files. It uses a bounded pool
(like `census-pool`), and it reports `built_ms`.

## `pull`

`pull` is an EQL-style interpreter over the index map. It does not use a
database.

- **Entity ids.** `[:var/qname "a.b/f"]`, `[:ns/name "a.b"]`,
  `[:kw/name "scope"]`.
- **Attributes.** A var exposes `:var/qname`, `:var/name`, `:var/ns`,
  `:var/file`, `:var/start`, `:var/end`, `:var/kind`, `:var/private?`,
  `:var/arglists`, `:var/doc`, `:var/intent`, `:var/source`, `:var/throws?`,
  `:var/fx`, `:var/external?`. A namespace exposes `:ns/name`, `:ns/doc`,
  `:ns/file`. A keyword exposes `:kw/name`.
- **Joins.** `{:var/calls [...]}` follows calls. `{:var/_calls [...]}` follows
  callers. `{:var/uses-kw [...]}`, `{:var/_uses-kw [...]}` and
  `{:var/reads-kw [...]}` follow keywords. `{:var/in-ns [...]}` goes to the
  namespace, and `{:ns/_in-ns [...]}` (or `{:var/_in-ns [...]}`) goes to its vars.
- **Recursion.** A number in place of the sub-pattern (`{:var/_calls 2}`)
  repeats the parent pattern to that depth, at most 3. The unbounded `...` is
  a refusal `pull-too-deep`.
- **Unknown attributes.** An unknown attribute is left out of the result, as
  in Datomic pull. An unknown entity id is a refusal (`unknown-var`,
  `unknown-namespace`).
- **Cycles.** A var that is already on the path from the root is returned as
  its id only, so a recursive pull over a cycle ends.

The interpreter may use `edn-query-language/eql` to parse the pattern into an
AST. It is a small, pure library. The decision is made at implementation.

## Evaluation fence

- **Reader.** The expression is read with the SCI reader, one form only
  (trailing input is refused), as in `edit_dsl`. No `#=`, no reader eval.
- **Admission.** Before evaluation, each symbol of the form must pass an
  allowlist decision like `edit_dsl` (`sandbox-admission`): a local, one of the
  bound functions, a symbol of the pure core list, a var that the session has
  defined, or `def` / `defn` / `fn` / `let` and the other macros of the
  expansion list. Anything else is a typed refusal `denied-symbol` that names
  the symbol. This includes interop (`.method`, `Class/field`, `new`),
  `require`, `in-ns`, `ns`, `eval`, `load-string`, `slurp`, `spit`, `future`,
  `agent`, `atom`, `alter-var-root`, and dynamic vars.
- **`def`.** Allowed only for a simple symbol, into the session namespace.
  `def` of a name that is bound in the context (a function, a core symbol) is
  a refusal.
- **Context.** `sci/init` with `:classes {}`, no `:allow` of host namespaces,
  and the functions in `user`. One context for each session (see Sessions).
- **Time.** The evaluation and the rendering run on one worker thread with a
  time limit (default 10 s). At the limit the worker is interrupted, and the
  result is a refusal `timeout`. This also bounds an endless lazy sequence.
- **Size.** Rendering writes into a writer that stops at the ceiling. A value
  over the ceiling is never realised past it.
- **Data access.** The functions read the index map. They take no path from
  the expression: a namespace, var or keyword name is looked up in the
  index, never opened as a file. The values that they return are copies of
  index data, with no host objects.

## Bounds

| Bound | Default | On hit |
|---|---|---|
| Source files discovered | 10,000 | `read_complete=false`, the cap in `skipped` |
| Bytes of one file | 2 MiB | file in `skipped`, `read_complete=false` |
| Expression length | 4,000 characters | refusal `expression-too-large` |
| Evaluation time | 10 s | refusal `timeout` |
| Result (not a skeleton) | 30,000 characters | refusal `result-too-large`, with a `next_call` that narrows the request |
| Result (skeleton) and transport ceiling | 200,000 characters | refusal `result-too-large`, with a `remedy` |
| Pull recursion depth | 3 | refusal `pull-too-deep` |
| Session contexts | 32, least recently used out | the dropped session gets a fresh context and `session.reset=true` |
| Session idle time | 2 hours | as above |
| `def`s in one context | 64 | refusal `session-full` |

The defaults go in one configuration map, as for `relation_census`.

## Rendering

Only the final value of an expression is rendered. The renderer chooses by
the shape of the value:

- A string: as it is.
- A var map (`:kind :var`), or a vector of them: one var line each (see Var
  line). A skeleton vector starts with its namespace line from `:meta`.
- A form map, a use map, a namespace map, or a vector of one of these: their
  renderings (see Other renderings).
- A pull result: one line for each entity. The id first (`:var/qname`,
  `:var/name`, `:kw/name` or `:ns/name`), then the scalar attributes in a fixed
  order (kind, private, line range, arglists, `— intent`, docstring when there
  is no intent). A join that holds only ids is one line `<label>: a, b, c`. A
  join that holds more is indented lines under its parent. `:var/source` is a
  code block. Labels: `calls`, `called by`, `uses`, `used by`, `reads`,
  `read by`, `in`, `vars`.
- A `def`: the line `#'user/<name>` and a one-line shape of the value (its
  kind and count).
- Any other value: `pr-str`, under the same ceiling.

## Refusals

| `error_type` | When | Continuation |
|---|---|---|
| `invalid-workspace-root` | as for the other tools | `remedy` |
| `invalid-expression` | no form, more than one form, or a read error | `remedy` with the reader message |
| `expression-too-large` | over the expression bound | `remedy` |
| `denied-symbol` | admission refuses a symbol | `remedy` naming the symbol and the bound functions |
| `unknown-namespace` | `skeleton` or `pull` names a namespace that is not in the index | `next_call` with the nearest names, when there are any |
| `unknown-var` | `form`, `refs` or `pull` names a var that is not in the index | `next_call` with `skeleton` of its namespace, when the namespace exists |
| `pull-too-deep` | recursion over the bound, or `...` | `next_call` with depth 1 |
| `session-full` | a `def` over the bound | `remedy` |
| `timeout` | over the time bound | `remedy` |
| `result-too-large` | over the result bound | `next_call` that narrows (for example `skeleton` instead of `form` of many vars, or `refs` with `:include-tests false`) when one exists, else `remedy` |
| `index-unavailable` | the cold build failed | `remedy` with the cause |

An exception that a function or the expression throws (for example a
`ClassCastException` in user code) is a refusal `evaluation-error` with the
message, under the caller-text ceiling.

## Dependencies

- `clj-kondo/clj-kondo` (JVM library) is a new dependency of the MCP aliases.
  It does not change the babashka CLI, which keeps the binary.
- Possibly `edn-query-language/eql`, a small pure library, for pull pattern
  parsing (decided at implementation).
- No database dependency.

## Fence review items

| Item (CLAUDE.md "Fence review scope") | How this design meets it |
|---|---|
| Subprocesses | None. clj-kondo runs in process. |
| Readers on repo bytes | clj-kondo parses source. Build files are read with `clojure.edn` only. The expression is read with the SCI reader, one form, no reader eval. |
| Paths that a walk produces | Realpath inside the root, symlinks and relocated `:paths` dropped (the discovery rules above). |
| Bounds before parsing | File-count and file-byte caps apply before a file is read. |
| `next_call` | Never equal to the call just made; tested for each refusal. |
| Repo-controlled code | clj-kondo hooks are off (owner decision). `:lint-as` is data. |
| Session state | `def` only into the session namespace; bounded count; no host objects in values; contexts bounded and isolated per session. |

## Testing

The specification phase defines the cases. The layers:

- **Pure:** the var line and the other renderings; the first-sentence rule;
  `apply-ingest` on literal file results; the pull interpreter on a literal
  index; admission decisions; bound checks; refusal construction.
- **Ingest:** clj-kondo analysis of literal sources to var records.
- **Freshness:** a changed mtime causes a re-ingest before the answer; a
  removed file disappears.
- **Sessions:** a `def` survives to the next call of the same session, is
  absent in another session, and is gone after eviction with
  `session.reset=true`.
- **Fence:** each denied form class, `def` rules, the time bound on an endless
  sequence, the size ceiling.
- **Contract:** schema, outcome classes, refusal shapes, `next_call` never
  equal to the call.
- **Real program:** a frozen copy of one pyze/modernization-mcp namespace as a
  fixture, for the skeleton text and the latency bound.

## Decisions on the open questions (owner, 2026-10-18)

1. **clj-kondo hooks:** off.
2. **Data results:** the functions return data, for a REPL-like experience,
   and sessions keep `def`s.
3. **Vars with no docstring:** no intent clause in slice A.
4. **Skeleton of a very large namespace:** deferred. Issue #5 (namespace
   split) is the structural answer.
