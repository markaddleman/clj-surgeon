# Code Index (`query_clojure`)

Status: draft LLD for slice A of issue #3 (markaddleman/clj-surgeon). The HLD
section is "Code index: answer read questions from fresh var facets" in
`docs/high-level-design.md`. The specification is
[code-index-specs.md](code-index-specs.md) (MCP-OP-QUERY-001 through
MCP-OP-QUERY-120, draft, with fix round 1).

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

On refusal: `ok=false`, `problems`, `error_type`, `error`, and either a
`next_call` (a narrower call that the server computed) or a `remedy`.
`problems` is a non-empty list. Each item holds `error_type`, `error` and one
of `next_call` or `remedy`. The top-level `error_type`, `error` and
continuation are those of the first problem. A `next_call` never equals the
call just made, at the top level or in a problem. See Refusals.

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
| `(skeleton ns)` | A vector of var maps (below), one for each version of each var of `ns`, in source order, with `:meta {:kind :skeleton, :ns, :doc}`. |
| `(form sym)`, `(form [sym …])` | A vector of `{:kind :form, :qname, :platform, :file, :start, :end, :source}`, one for each version of each var. |
| `(refs sym)`, `(refs sym opts)` | A vector of `{:kind :use, :file, :line, :ns, :from, :use-kind}`. `opts`: `{:include-tests true\|false}`, default `true`. |
| `(pull pattern eid)` | The pull result (maps and vectors of attribute values). |
| `(help)`, `(help fn-name)` | A string: the functions, their arguments, and examples. |

A **var map** is:

```clojure
{:kind :var, :qname "a.b/f", :ns "a.b", :name "f", :platform :clj, :file "src/a/b.clj",
 :start 12, :end 30, :arglists ["[x]" "[x y]"], :private? false,
 :intent "First sentence.", :calls ["a.b/g" "c.d/h"], :fx ["swap!"], :throws? true}
```

`ns` and `sym` may be symbols or strings. `sym` is qualified (`ns/name`).

`:platform` is `:clj`, `:cljs` or `:cljc`. A var of a `.cljc` file with a
`:clj` branch and a `:cljs` branch that differ has two records and so two var
maps, the `:clj` one first. A `.cljc` var that is the same on both platforms
has one record with `:platform :cljc`.

#### Var line

The rendering of a var map, and so of a skeleton:

```
ns modernization.mcp.tools.scope — <first sentence of the ns docstring>
λ scope-value([{:keys [db application version case-class]}]) | calls item/error-item,versions-of | throws — The scope value of `:application` …
λ shown([{:keys [x]}])
```

A var line is:

`λ <name>(<arglists joined by " | ">)` then, each only when present:

- ` | platform <p>` — `clj` or `cljs`, only for a var of a `.cljc` file that
  has a version for each platform. A reader sees which version the line is.
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
  Where the platform clause rule applies, the header ends with `  [clj]` or
  `  [cljs]`, and the lines and the source are those of that platform's branch.
- **Use map:** `<path>:<line> <ns>/<from> <use-kind>`, where use-kind is
  `call`, `alias-call`, `value` or `refer`, as in the CLI `:refs`. A vector of
  use maps ends with a total line.
- **Namespace map:** `ns <name> (<vars> vars) — <first sentence of the doc>`.
- **Pull result:** see Rendering.

#### `pull`

`pattern`: a pull pattern. `eid`: `[:var/qname "ns/name"]`, `[:ns/name "ns"]`
or `[:kw/name "name"]`. The depth of a pull is at most 3 (see Recursion). The
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
- **Atomic definitions.** The requests of one session and root run one after
  another. The server checks the limit before it evaluates. A request that
  throws, times out or is refused after the evaluation started leaves the
  context and its names as they were. A worker that outlived its request
  cannot change a session. A request that adds no name is never refused with
  `session-full`.
- **Memory.** The values of a context hold at most 100,000 items in all
  (counted through nested collections). A `def` over the bound is a refusal
  `session-full`.
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
 :vars       {"a.b/f" [<var record> …]}   ; one record, or one for each platform
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
| `:platform` | `:clj`, `:cljs` or `:cljc` (see Platforms). |
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

### Platforms

A var is identified by its qualified name and its platform. The file
extension sets the platform: `.clj` gives `:clj`, `.cljs` gives `:cljs`. For a
`.cljc` file, clj-kondo analyzes both platforms. When the two analyses of a var
are the same, the index holds one record with `:platform :cljc`. When they
differ (arglists, calls, side effects, keywords, throws, kind, privacy, doc, or
the defining form), the index holds one record for each platform. For a var
that a reader conditional defines, `:start`, `:end` and `:source` are those of
the defining form of that platform. `skeleton`, `form` and `pull` return each
version with its platform. A join of `pull` that leaves a version reaches the
versions of the target that apply to the platform of the source.

A namespace lives in one file (owner decision on issue #3). Shared code of
`clj` and `cljs` goes in one `.cljc` file. If two files declare one namespace,
the index keeps the file with the smaller path and lists the other in
`skipped` with the reason `duplicate-namespace`.

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
- A refresh stops to read files when the bytes read reach the total-byte cap
  (128 MiB). The files not read are listed in `skipped` with the reason
  `total-bytes`, in order of path, and `read_complete=false`. The index keeps
  `:source` for every var, so this cap also bounds memory.
- A build file or `.clj-kondo/config.edn` over the config-byte cap (1 MiB) is
  not read. It is listed in `skipped` with the reason `too-large`. A build file
  then adds only its `test` and `tests` directories. The config adds no
  `:lint-as` entry.

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

The stale check, the reads and the analysis run under a refresh time bound
(60 s). The bound is checked before each file. At the bound, the files that
are done go into the index in one step, the request is refused with `timeout`,
and a new call continues where this one stopped. No worker keeps running.

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
- **Platform.** A var attribute `:var/platform` holds the platform. The pull
  of a var with two versions returns one entity for each version (a vector, in
  the order `:clj`, `:cljs`). A join returns the versions that apply to the
  platform of its source.
- **Joins.** `{:var/calls [...]}` follows calls. `{:var/_calls [...]}` follows
  callers. `{:var/uses-kw [...]}`, `{:var/_uses-kw [...]}` and
  `{:var/reads-kw [...]}` follow keywords. `{:var/_reads-kw [...]}` goes from a
  keyword to the versions of the vars that read it, sorted by qname (133, issue
  #25). `{:var/in-ns [...]}` goes to the
  namespace, and `{:ns/_in-ns [...]}` (or `{:var/_in-ns [...]}`) goes to its vars.
- **Recursion.** A number in place of the sub-pattern (`{:var/_calls 2}`)
  repeats the parent pattern to that depth. The unbounded `...` is a refusal
  `pull-too-deep`.
- **Depth.** The depth of a pull is the greatest number of joins on any path
  of the pattern. A numeric join `n` counts as `n`, an explicit join counts as
  1. The depth is at most 3. The server computes it from the pattern before it
  visits the index, so that nested explicit joins (`{:var/calls [{:var/calls
  [...]}]}`) are bounded as numeric ones are.
- **Node budget.** A pull returns at most 2,000 entities, cycle maps
  included. At the budget it stops and the request is refused with
  `result-too-large`.
- **Unknown attributes.** An unknown attribute is left out of the result, as
  in Datomic pull. An unknown entity id is a refusal (`unknown-var`,
  `unknown-namespace`).
- **Cycles.** A var that is already on the path from the root (same qualified
  name and platform) is not entered again, for any join, explicit or numeric.
  It is returned as a map `{:var/qname q :pull/cycle-to q}`, where `q` is the
  var on the path that the map returns to. The var whose join holds this map
  is the var that closes the cycle. The cycle is visible to the agent and is
  not cut without a mark. The rendering is
  `<qname> cycle: returns to <qname> (closed by <parent qname>)`.

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
  `agent`, `atom`, `alter-var-root`, and dynamic vars. Admission also follows
  these rules:
  - It treats as code every part that evaluation evaluates. This includes the
    reader metadata of symbols and collections (`^{:x (eval 1)} [1]`,
    `(def ^{:x (atom 1)} y 1)`).
  - It never skips a part of a form because the form is malformed. A special
    form that it cannot walk (`let*` or `loop*` with a binding form that is
    not a vector of even length, an `fn` arity without a parameter vector) is
    a refusal `invalid-expression` that names the form.
  - **Allowlist of def shapes (130, 131; fix round 1c, issue #24).** The
    blocklist of marker shapes (104) is superseded. Admission accepts a `def`
    or `defn`, at any depth, only as `(def name)`, `(def name value)`,
    `(def name "doc" value)`, `(defn name "doc"? [params] body...)` or
    `(defn name "doc"? ([params] body...)+)`. A `defn` has no attr-map. The
    metadata of the name (reader `^` or `with-meta`) may hold only `:doc`
    (a string literal) and `:private` (a boolean literal). Any other shape is
    a refusal `invalid-expression` with reason `def-shape` and a `remedy`
    that gives the shapes. `defn-`, `defmacro`, `definline`, `defmulti`,
    `defmethod`, `defonce`, `deftype`, `defrecord`, `defprotocol`, `intern`
    and `alter-meta!` are not on the allowlist, and 077 does not name any of
    them, so they are `denied-symbol` through the allowlist. A readable
    `:macro` or `:sci/macro` marker keeps the `denied-symbol` that names it
    (104). The authoritative check (122) and the `:deny` set (123) stay as
    defense in depth.
  - **Sub-forms of a top-level `do` (132).** SCI evaluates a top-level `do`
    one sub-form at a time, so a macro that a sub-form defines can expand in
    the next sub-form, and a later `def` can replace the var before the check
    after the evaluation looks at it (issue #24). The session code therefore
    splits a top-level `do`, and each `do` that is a direct sub-form of it,
    and evaluates the sub-forms in order, as Clojure does. The macro check of
    122 runs after each sub-form. A failed check, or a throw or a refusal in a
    sub-form, restores the snapshot (113) for the whole request, and the later
    sub-forms do not run. Admission walks the whole form first.
  - The early check cannot see a computed marker. SCI evaluates the metadata
    of a `def` name, and it reads the marker from the var and from the root
    value, so `^{:sci/macro (= 1 1)} m` and `(def m (with-meta (fn ...)
    {:sci/macro true}))` pass admission. The authoritative check is therefore
    made after the evaluation (see Macros).
  - It reports every denied symbol, not only the first (see Refusals).
- **Macros (issues #18, 121 to 123).** A session cannot define a macro, so no
  expansion can hide a symbol that admission did not walk. SCI 0.15.58 treats
  a var as a macro by these rules (all with a truthy value):
  - `sci.lang.Var/isMacro` (`sci/lang.cljc`, line 112): the metadata of the
    var has `:macro`, or the metadata of the root value has `:sci/macro`.
  - `sci.impl.utils/macro?` (`sci/impl/utils.cljc`, line 74): the metadata of
    the value at the head of a call has `:sci/macro` or `:macro`. The analyzer
    uses it to expand a call (`sci/impl/analyzer.cljc`, line 2049).
  - `analyze-fn*` (`sci/impl/analyzer.cljc`, line 441) reads `:macro` from the
    `:sci.impl/fn` metadata of an `fn` form, which `defmacro` sets.
  - `analyze-def` (`sci/impl/analyzer.cljc`, line 823) evaluates the metadata
    of the name, so a computed marker works.
  After the evaluation, and whether it returned or threw, the session code
  asks the context about each var that it holds (at most 64), with the same
  predicates. A macro means rollback to the snapshot (113) and the refusal
  `denied-symbol` with the var and the marker. The refusal has priority over
  `evaluation-error`. The context is also created with `:deny` set to the
  denied symbols of 077. A macro that a request defines and calls in the same
  request then cannot expand to a denied symbol. The two barriers are
  independent.
- **Failure before the worker (issue #19, 124 to 127).** Reading, admission and
  the `def` check run on the request thread, and they recurse. Two guards
  protect them:
  - A nesting-depth cap (default 256, `:max-expression-depth`). One iterative
    scan of the text counts open delimiters and form prefixes (skipping
    strings, comments and character literals) and refuses
    `expression-too-deep` before any recursive code runs. The cap is far
    below the depth at which the reader or the walks overflow the stack, and
    the 4,000-character cap alone does not give that (about 1,900 nested `[`
    fit in it).
  - A `Throwable` guard around each step. A `StackOverflowError` is
    `expression-too-deep`, with a `remedy`, the same as the depth cap. This
    holds in every step, the evaluation included (fix round 1c, owner
    decision 2026-10-18; it replaces `invalid-expression` with reason
    `too-deep`). Any other `Throwable` is
    `internal-error`. The scan is the main guard. The `Throwable` guard is the
    backstop, because the stack depth at which a walk fails depends on the JVM
    state. `mcp-operation/invoke!` has a last guard: no `Throwable` leaves the
    tool handler.
- **`def`.** Allowed only for a simple symbol, into the session namespace.
  `def` of a name that is bound in the context (a function, a core symbol) is
  a refusal.
- **Context.** `sci/init` with `:classes {}`, no `:allow` of host namespaces,
  and the functions in `user`. One context for each session (see Sessions).
- **Time.** The evaluation and the rendering run on one worker thread with a
  deadline (default 10 s). An interrupt flag alone does not stop SCI, a lazy
  sequence or a pure Java call, so the deadline is checked in four places:
  - the SCI context, through its `:interrupt-fn` option, which reads the
    deadline at each evaluation step;
  - the lazy and looping functions of the pure core list, which come from the
    interruptible core of SCI (`sci.interrupt/clojure-core`);
  - the loops of the bound functions (including the search for the nearest
    names, which reads at most 64 characters of the name) and of the renderer;
  - the refresh (stale check and ingest), with its own bound of 60 s.
  At the limit the evaluation ends, the worker stops within the stop grace
  (1 s), and the result is a refusal `timeout`. A late worker cannot change
  the session. If 2 workers did not end after their timeouts, new requests
  that need an evaluation are refused with `busy` until a worker ends. This
  also bounds an endless lazy sequence.
- **Size.** Rendering writes into a writer that stops at the ceiling. A value
  over the ceiling is never realised past it. This holds for every kind of
  value (var, form, use, namespace, pull, skeleton, `pr-str`). The renderer
  streams its lines. It realizes at most `limit + 1` elements of a sequence
  and decides the kind of the sequence from them.
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
| Expression nesting depth | 256 | refusal `expression-too-deep` |
| Evaluation time | 10 s | refusal `timeout` |
| Result (not a skeleton) | 30,000 characters | refusal `result-too-large`, with a `next_call` that narrows the request |
| Result (skeleton) and transport ceiling | 200,000 characters | refusal `result-too-large`, with a `remedy` |
| Session contexts | 32, least recently used out | the dropped session gets a fresh context and `session.reset=true` |
| Session idle time | 2 hours | as above |
| `def`s in one context | 64 | refusal `session-full` |
| Items held by the values of one context | 100,000 (a session bound: it reaches the sessions object from the config, 134) | refusal `session-full` |
| Pull depth (every join counts) | 3 | refusal `pull-too-deep` |
| Pull nodes | 2,000 | refusal `result-too-large`, with a `remedy` |
| Refresh time (stale check and ingest) | 60 s | files done are kept, refusal `timeout` |
| Stop grace of a timed-out worker | 1 s | |
| Workers that did not end | 2 | refusal `busy` |
| Total bytes read in one refresh | 128 MiB | files in `skipped` with reason `total-bytes`, `read_complete=false` |
| Bytes of a build file or kondo config | 1 MiB | file in `skipped`, treated as unreadable |
| Characters of a name for the nearest-name search | 64 | the name is cut |

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

A refusal carries `problems`, the list of all problems that the server found
before it evaluated the expression (owner decision on C25). The agent fixes
them in one pass. The server runs these checks in order and does not stop at
the first problem: each field of the request, the length of the expression, the
workspace root, the reading of the expression, admission, and the check of the
`def` names. A check runs unless its input is missing. A problem with the
workspace root does not stop the checks of the expression. Admission reports
each distinct denied symbol in the order of its first occurrence. The `def`
check reports each refused name and `session-full`. A problem that arises
during the refresh, the evaluation or the rendering ends the request, and
`problems` then holds it alone.

| `error_type` | When | Continuation |
|---|---|---|
| `invalid-workspace-root` | as for the other tools | `remedy` |
| `invalid-expression` | no form, more than one form, a read error, a special form that admission cannot walk, or a `def` or `defn` outside the allowlist of shapes (reason `def-shape`, 130, 131) | `remedy` with the reader message, the form or the allowed shapes |
| `expression-too-large` | over the expression bound | `remedy` |
| `expression-too-deep` | nesting depth over the depth bound, or a `StackOverflowError` in any step (125) | `remedy` with the bound and the depth found |
| `denied-symbol` | admission refuses a symbol (one problem for each), or a `def` of a macro (early check by shape, authoritative check after evaluation) | `remedy` naming the symbol (or the macro marker) and the bound functions |
| `internal-error` | a `Throwable` in a step that is not the fault of the expression | `remedy`: send the request again, report it if it comes again |
| `unknown-namespace` | `skeleton` or `pull` names a namespace that is not in the index | `next_call` with the nearest names, when there are any |
| `unknown-var` | `form`, `refs` or `pull` names a var that is not in the index | `next_call` with `skeleton` of its namespace, when the namespace exists |
| `pull-too-deep` | depth over the bound (explicit or numeric joins), or `...` | `next_call` with depth 1 |
| `session-full` | a `def` over the bound of names or items | `remedy` |
| `busy` | 2 workers did not end after a timeout | `remedy` |
| `timeout` | over the evaluation or refresh time bound | `remedy` (for the refresh: call again) |
| `result-too-large` | over the result bound | `next_call` that narrows (for example `skeleton` instead of `form` of many vars, or `refs` with `:include-tests false`) when one exists, else `remedy` |
| `index-unavailable` | the cold build failed | `remedy` with the cause |

An exception that the expression throws (for example a `ClassCastException`
in user code) is a refusal `evaluation-error` with the message, under the
caller-text ceiling. A typed refusal from a bound function, the renderer or the
worker pool (`result-too-large`, `busy`, `timeout`, `unknown-namespace`,
`unknown-var`, `pull-too-deep`, `invalid-expression`) keeps its type, also
when SCI wraps it (128). A pull over the node budget is `result-too-large`, and
a request at the worker cap is `busy`. Neither is `evaluation-error`.

The Tool phase wires the bounds of 107, 108, 109, 112 and 119 (129): `pull`
takes the node budget and the deadline check, the renderer takes the deadline
check, the refresh plans its reads with `plan-reads` from the total-byte cap
and checks its deadline before each file, and `run-with-timeout` takes the stop
grace and the worker cap. All values come from the effective bounds.

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
  sequence, the size ceiling. Fix round 1 adds: malformed special forms,
  metadata that holds code, the allowlist of def shapes (each denied shape,
  the attr-map in every position, a macro defined in one sub-form of a `do`
  and called in the next), user macros (each marker shape that SCI honors,
  the computed marker, a macro defined and called in one request); nesting
  over the depth cap, a `StackOverflowError` in each pre-worker step, and a
  `Throwable` from `execute` in `invoke!`; a timed-out worker that really ends
  (CPU and threads checked afterwards); nested explicit pull joins; `def`
  bookkeeping on throw, timeout and concurrency; rendering of an endless
  sequence of each kind; a refresh over its time bound and a file set over the
  total-byte cap; a refusal with several problems; a pull over a cycle; a
  `.cljc` var with two platforms.
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
