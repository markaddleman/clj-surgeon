---
parent: code-index-design
prefix: MCP-OP-QUERY
status: draft (index/slice-a, fix round 1b)
---

# Code Index (`query_clojure`) Specifications

IDs are stable and must not be reused if a requirement is deleted.

## Tool contract

- [ ] **MCP-OP-QUERY-001**: The `:full` tool profile shall publish a tool named `query_clojure` with outcome classes `#{:read-success :typed-refusal}` and annotations that mark it read-only, idempotent and not open world.
- [ ] **MCP-OP-QUERY-002**: The `query_clojure` input schema shall require the string field `expression`, shall accept the optional string field `workspace_root`, and shall refuse any other field with a typed refusal before it resolves the workspace or reads a file.
- [ ] **MCP-OP-QUERY-003**: When a request succeeds, clj-surgeon shall return `ok=true`, `operation="query-clojure"`, `value` (text), `value_chars` equal to the length of `value`, `index`, `read_complete`, `session`, `workspace_root` and `elapsed_ms`.
- [ ] **MCP-OP-QUERY-004**: When a request is refused, clj-surgeon shall return `ok=false`, `operation="query-clojure"`, `problems` (see MCP-OP-QUERY-091), an `error_type` from the refusal table of the design, an `error` message, and exactly one of `next_call` or `remedy`, where `error_type`, `error` and the continuation are those of the first problem.
  Changed in fix round 1: a refusal reports every problem of the request (decision on C25, issue #3).
- [ ] **MCP-OP-QUERY-005**: clj-surgeon shall never return a `next_call` whose arguments equal the arguments of the call that produced it, neither at the top level of a refusal nor in any of its problems.
  Changed in fix round 1: the rule also holds for each problem of the list (decision on C25, issue #3).
- [ ] **MCP-OP-QUERY-006**: clj-surgeon shall publish every success and every refusal of `query_clojure` through `mcp-operation/invoke!` with one summary rendering, so that `elapsed_ms` is the only field that the shared finalizer adds.
- [ ] **MCP-OP-QUERY-007**: clj-surgeon shall not create, change or delete any file in the workspace when it handles a `query_clojure` request.
- [ ] **MCP-OP-QUERY-008**: When `workspace_root` is absent, clj-surgeon shall use the server project root; when it is present, clj-surgeon shall resolve it with the shared workspace router and shall refuse a value that is not an existing canonical absolute directory with `error_type=invalid-workspace-root` and a `remedy`.

## Discovery

- [ ] **MCP-OP-QUERY-009**: clj-surgeon shall take as the source files of a workspace the `.clj`, `.cljs` and `.cljc` files under the `:paths` of each `deps.edn` and `bb.edn`, the `:source-paths` of each `project.clj` (default `src`), and the `test` and `tests` directories next to each build file.
- [ ] **MCP-OP-QUERY-010**: clj-surgeon shall read build files with `clojure.edn` only, and shall never evaluate their content.
- [ ] **MCP-OP-QUERY-011**: If a source path, after realpath, lies outside the workspace root, then clj-surgeon shall drop it and shall not read any file under it.
- [ ] **MCP-OP-QUERY-012**: If a symbolic link inside a source path resolves outside the workspace root, then clj-surgeon shall not follow it.
- [ ] **MCP-OP-QUERY-013**: If discovery reaches the source-file cap, then clj-surgeon shall stop the walk, shall set `read_complete=false`, and shall record the cap in `skipped`.
- [ ] **MCP-OP-QUERY-014**: If a source file is larger than the file-byte cap, then clj-surgeon shall not read it, shall list it in `skipped` with its size and the reason `too-large`, and shall set `read_complete=false`.

## Ingest of one file

- [ ] **MCP-OP-QUERY-015**: When clj-surgeon ingests a file, it shall stat the file, read its bytes, and stat it again, and when the two stats differ in mtime or size it shall read again, at most three times in all.
- [ ] **MCP-OP-QUERY-016**: If the stats of a file still differ after the third read, then clj-surgeon shall not ingest it, shall list it in `skipped` with the reason `unstable`, and shall set `read_complete=false`.
- [ ] **MCP-OP-QUERY-017**: When clj-surgeon ingests a file, it shall record the mtime, the size and the SHA-256 of the bytes that it analyzed.
- [ ] **MCP-OP-QUERY-018**: clj-surgeon shall analyze source with clj-kondo in the server process, with analysis of arglists and keywords on, analysis of locals off, every linter off, and clj-kondo hooks off.
- [ ] **MCP-OP-QUERY-019**: Where the workspace has a `.clj-kondo/config.edn`, clj-surgeon shall apply its `:lint-as` entries to the analysis and shall ignore its `:hooks` entries.
- [ ] **MCP-OP-QUERY-020**: When clj-surgeon ingests a file, it shall make one var record for each top-level var, and for each platform of it (see MCP-OP-QUERY-098 and 099), that the file defines in its namespace, with `:qname`, `:ns`, `:name`, `:platform`, `:file`, `:start`, `:end`, `:kind`, `:private?`, `:arglists`, `:doc`, `:intent`, `:source`, `:calls`, `:fx`, `:uses-kw`, `:reads-kw` and `:throws?`.
  Changed in fix round 1: the record has a platform, and a `.cljc` var can have one record for each platform (decision on C1, issue #3).
- [ ] **MCP-OP-QUERY-021**: clj-surgeon shall set `:source` of a var record to the exact text of the file from the first line to the last line of the top-level form.
- [ ] **MCP-OP-QUERY-022**: clj-surgeon shall set `:calls` of a var record to the qualified names of the vars that the form uses, with each var of a `clojure.*` namespace left out.
- [ ] **MCP-OP-QUERY-023**: clj-surgeon shall set `:throws?` of a var record to true exactly when the text of the form holds `(throw` or `(ex-info` as a call head.
- [ ] **MCP-OP-QUERY-024**: clj-surgeon shall set `:intent` of a var record to the text of the docstring up to and including the first `.`, `!` or `?` that a space or the end follows, with runs of whitespace joined to one space, cut to at most 200 characters, and shall leave `:intent` absent when the var has no docstring.

## Update of the index

- [ ] **MCP-OP-QUERY-025**: clj-surgeon shall hold the index of a workspace as one immutable map in one atom, and shall change the atom only with the complete next map.
- [ ] **MCP-OP-QUERY-026**: When an ingest step changes, adds or removes files, clj-surgeon shall replace the entries and var records of each changed file, add those of each new file, and remove those of each removed file, in one step from the previous map.
- [ ] **MCP-OP-QUERY-027**: When the index changes, clj-surgeon shall rebuild the callers index, the keyword-users index and the uses-by-target index from the var records of the new map.
- [ ] **MCP-OP-QUERY-028**: clj-surgeon shall produce the same index map for the same set of file contents, whatever the order in which it ingests the files.
- [ ] **MCP-OP-QUERY-029**: While one ingest of a workspace runs, clj-surgeon shall not start a second ingest of the same workspace.

## Freshness

- [ ] **MCP-OP-QUERY-030**: When a `query_clojure` request arrives, clj-surgeon shall run discovery and the stale check of the workspace before it evaluates the expression.
- [ ] **MCP-OP-QUERY-031**: When the stale check finds a file whose mtime or size differs from its last ingest, a new source file, or a removed source file, clj-surgeon shall update the index for those files before it evaluates the expression, and shall list the re-ingested files under `:reingested` in the metadata of the result (MCP-OP-QUERY-135).
- [ ] **MCP-OP-QUERY-032**: clj-surgeon shall evaluate the expression of a request against one index map, the one that the atom held after the stale check of that request.
- [ ] **MCP-OP-QUERY-033**: clj-surgeon shall list under `:files` in the metadata of the result each source file of which a bound function returned data during the request, with the SHA-256 recorded at its ingest (MCP-OP-QUERY-135).
- [ ] **MCP-OP-QUERY-034**: When the first request for a workspace arrives, clj-surgeon shall build the index of all its source files with a bounded worker pool, and shall report the build time in `index.built_ms`.
- [ ] **MCP-OP-QUERY-035**: If the cold build of a workspace fails, then clj-surgeon shall refuse the request with `error_type=index-unavailable` and a `remedy` that names the cause.

## Functions

- [ ] **MCP-OP-QUERY-036**: The evaluation context shall bind in `user` the functions `search`, `namespaces`, `skeleton`, `form`, `refs`, `pull` and `help` (changed by issue #27: `search` added), and no other function that reads or acts on the host.
- [ ] **MCP-OP-QUERY-037**: Each bound function shall return only maps, vectors, strings, numbers, booleans, keywords and nil, and shall never return a host object, the index map, or a reference into it.
- [ ] **MCP-OP-QUERY-038**: `(namespaces)` shall return a vector with one map `{:kind :ns :ns :file :vars :doc}` for each namespace of the index, sorted by name, where `:vars` is the number of its vars.
- [ ] **MCP-OP-QUERY-039**: `(skeleton ns)` shall accept `ns` as a symbol or a string, and shall return a vector of var maps for the vars of `ns` in order of their start line, with metadata `{:kind :skeleton :ns :doc}`. A var that has two versions (see MCP-OP-QUERY-099) shall give two var maps, the `:clj` version first.
  Changed in fix round 1: a var with a version for each platform gives one map for each version (decision on C1, issue #3).
- [ ] **MCP-OP-QUERY-040**: A var map shall hold `:kind :var`, `:qname`, `:ns`, `:name`, `:platform`, `:file`, `:start`, `:end`, `:arglists`, `:private?`, `:calls`, `:fx` and `:throws?`, and `:intent` when the var has one.
  Changed in fix round 1: the var map holds the platform (decision on C1, issue #3).
- [ ] **MCP-OP-QUERY-041**: When clj-surgeon ingests a var, it shall set `:fx` to the sorted, distinct names out of the fixed side-effect list of the design that name a var that the form uses, in any namespace and `clojure.*` included, because most names of that list are `clojure.core` functions that `:calls` leaves out.
- [ ] **MCP-OP-QUERY-042**: `(form sym)` and `(form [sym …])` shall accept qualified symbols or strings, and shall return a vector with one map `{:kind :form :qname :platform :file :start :end :source}` for each version of each var, in the order given, with the `:clj` version of a var before its `:cljs` version.
  Changed in fix round 1: a var with a version for each platform gives one map for each version, and each map has its platform (decision on C1, issue #3).
- [ ] **MCP-OP-QUERY-043**: `(refs sym)` shall return a vector with one map `{:kind :use :file :line :ns :from :use-kind}` for each use of `sym` in the index, sorted by file and line, where `:use-kind` is one of `call`, `alias-call`, `value` and `refer`.
- [ ] **MCP-OP-QUERY-044**: When `(refs sym {:include-tests false})` is called, clj-surgeon shall leave out each use in a file under a `test` or `tests` directory of its project.
- [ ] **MCP-OP-QUERY-045**: clj-surgeon shall not count as a use of a var a symbol that occurs only in a string, a comment, or a quoted form.
- [ ] **MCP-OP-QUERY-046**: `(help)` shall return a string that names each bound function with its arguments and one example, and `(help fn-name)` shall return the part for that function.

## `pull`

- [ ] **MCP-OP-QUERY-047**: `(pull pattern eid)` shall accept as `eid` exactly `[:var/qname s]`, `[:ns/name s]` and `[:kw/name s]`, and shall refuse any other form of `eid` with `error_type=invalid-expression`.
- [ ] **MCP-OP-QUERY-048**: `pull` shall return, for each attribute of the pattern that the entity has, its value, and shall leave out each attribute that the entity does not have, without a refusal.
- [ ] **MCP-OP-QUERY-049**: `pull` shall follow the joins `:var/calls`, `:var/_calls`, `:var/uses-kw`, `:var/_uses-kw`, `:var/reads-kw`, `:var/in-ns`, `:ns/_in-ns` and `:var/_in-ns` with the sub-pattern of the join.
- [ ] **MCP-OP-QUERY-050**: When a join holds a number `n` in place of a sub-pattern, `pull` shall apply the parent pattern recursively to depth `n`. The depth of that join, for the bound of MCP-OP-QUERY-051, is `n`.
  Changed in fix round 1: the number counts toward the one depth bound of MCP-OP-QUERY-111 (issue #8).
- [ ] **MCP-OP-QUERY-051**: If the depth of a pull, as MCP-OP-QUERY-111 defines it, is greater than 3, or a join holds `...`, then clj-surgeon shall refuse the request with `error_type=pull-too-deep` and a `next_call` whose pattern has depth 1.
  Changed in fix round 1: the bound covers explicit nested joins as well as numeric depths (issue #8).
- [ ] **MCP-OP-QUERY-052**: When a pull, through any join (explicit or numeric), reaches a var that is already on the path from the root, `pull` shall return that var as its id and the cycle marker of MCP-OP-QUERY-096, and shall not descend further. A var is on the path when its qualified name and its platform match.
  Changed in fix round 1: the cut applies to every join, not only to numeric depths (issue #8), and the cut is visible in the result (decision on C17, issue #3).
- [ ] **MCP-OP-QUERY-053**: When a join of `pull` reaches a called var that has no var record in the index, `pull` shall return it as `{:var/qname q :var/external? true}`.

## Unknown names

- [ ] **MCP-OP-QUERY-054**: If `skeleton` or `pull` names a namespace that is not in the index, then clj-surgeon shall refuse the request with `error_type=unknown-namespace`, and with a `next_call` to `skeleton` of the nearest indexed namespace names when there is at least one, else a `remedy`.
- [ ] **MCP-OP-QUERY-055**: If `form`, `refs` or `pull` names a var that is not in the index, then clj-surgeon shall refuse the request with `error_type=unknown-var`, and with a `next_call` to `skeleton` of its namespace when that namespace is in the index, else a `remedy`.

## Rendering

- [ ] **MCP-OP-QUERY-056**: clj-surgeon shall render only the final value of the expression into `value`.
- [ ] **MCP-OP-QUERY-057**: When the final value is a string, clj-surgeon shall publish it unchanged.
- [ ] **MCP-OP-QUERY-058**: When the final value is a var map, or a vector of var maps, clj-surgeon shall render one var line for each, and when the vector has skeleton metadata it shall start with the namespace line `ns <name> — <first sentence of the ns doc>`.
- [ ] **MCP-OP-QUERY-059**: clj-surgeon shall render a var line as `λ <name>(<arglists joined by " | ">)` followed, each only when present and in this order, by ` | platform <p>`, ` | calls <c1>,<c2>,…`, ` | fx <f1>,…`, ` | throws`, ` | private` and ` — <intent>`. The platform clause is present only when the file of the var is a `.cljc` file and the platform of the var is `clj` or `cljs`.
  Changed in fix round 1: a var of a `.cljc` file that has a version for each platform shows which version a line is (decision on C1, issue #3).
- [ ] **MCP-OP-QUERY-060**: In a var line, clj-surgeon shall write each called var of the same namespace as its bare name, and each called var of another namespace as the last segment of that namespace, `/`, and its name, and shall sort the written names in lexicographic order.
- [ ] **MCP-OP-QUERY-061**: When the final value is a form map, or a vector of form maps, clj-surgeon shall render for each a header line `;; <qname>  <file>:<start>-<end>` followed by its `:source` unchanged. When the platform clause rule of MCP-OP-QUERY-059 applies to the form, the header line shall end with `  [<platform>]`.
  Changed in fix round 1: the header names the platform of a version from a `.cljc` file (decision on C1, issue #3).
- [ ] **MCP-OP-QUERY-062**: When the final value is a vector of use maps, clj-surgeon shall render one line `<file>:<line> <ns>/<from> <use-kind>` for each, followed by one total line.
- [ ] **MCP-OP-QUERY-063**: When the final value is a namespace map, or a vector of namespace maps, clj-surgeon shall render one line `ns <name> (<vars> vars) — <first sentence of the doc>` for each.
- [ ] **MCP-OP-QUERY-064**: When the final value is a pull result, clj-surgeon shall render one line for each entity with its id first and its scalar attributes in the fixed order of the design, a join that holds only ids as one line `<label>: <id>, …`, a join that holds more as lines indented under its parent, and `:var/source` as a code block. A pull result of a var with two versions shall render as one entity for each version, and the entity line shall show the platform as MCP-OP-QUERY-059 says. A cycle entry shall render as MCP-OP-QUERY-097 says.
  Changed in fix round 1: the rendering shows the platform and the cycle marker (decisions on C1 and C17, issue #3).
- [ ] **MCP-OP-QUERY-065**: When the final value is any other value, clj-surgeon shall render it with `pr-str`.
- [ ] **MCP-OP-QUERY-066**: clj-surgeon shall produce byte-identical `value` text for the same expression against the same index map and the same session state.

## Sessions

- [ ] **MCP-OP-QUERY-067**: clj-surgeon shall keep one evaluation context for each pair of MCP session id and canonical workspace root, and shall give a request with no session id a fresh context that it uses once.
- [ ] **MCP-OP-QUERY-068**: When an expression `def`s a name, clj-surgeon shall keep that var in the context of the session, so that a later request of the same session and workspace can use it.
- [ ] **MCP-OP-QUERY-069**: clj-surgeon shall not make a var that one session `def`s visible to any other session.
- [ ] **MCP-OP-QUERY-070**: clj-surgeon shall keep at most 32 session contexts, shall drop the least recently used one first, and shall drop a context that has been idle for 2 hours.
- [ ] **MCP-OP-QUERY-071**: When a request arrives for a session whose context clj-surgeon dropped, clj-surgeon shall evaluate it in a fresh context and shall return `session.reset=true`.
- [ ] **MCP-OP-QUERY-072**: clj-surgeon shall return in `session.defs` the names that the context of the session holds after the request.
- [ ] **MCP-OP-QUERY-073**: If the `def`s of a request would make the context hold more than 64 names, then clj-surgeon shall refuse the request with `error_type=session-full` and a `remedy` before it evaluates the expression, and shall leave the context as it was. The names that the context holds are the names that it really holds, not the names that earlier requests recorded.
  Changed in fix round 1: the limit is checked before evaluation, and it counts the names in the context (issue #9).
- [ ] **MCP-OP-QUERY-074**: When the final value of an expression is a var that it `def`d, clj-surgeon shall render the line `#'user/<name>` followed by one line that gives the kind and the count of the value.

## Evaluation fence

- [ ] **MCP-OP-QUERY-075**: clj-surgeon shall read `expression` with the SCI reader, without reader eval, and shall refuse with `error_type=invalid-expression` an expression that holds no form, more than one form, or a form that the reader cannot read.
- [ ] **MCP-OP-QUERY-076**: Before evaluation, clj-surgeon shall admit each symbol of the form only when it is a local, a bound function, a symbol of the pure core list, a name that the session has `def`d, or a macro of the expansion list, and shall report each other symbol as a problem with `error_type=denied-symbol`, a message that names the symbol, and a `remedy` that names the bound functions (see MCP-OP-QUERY-093). Admission shall treat as code every part of the form that evaluation can evaluate, metadata included (see MCP-OP-QUERY-103).
  Changed in fix round 1: admission reports every denied symbol, and it walks metadata (issues #6 and C25 decision).
- [ ] **MCP-OP-QUERY-077**: clj-surgeon shall refuse with `error_type=denied-symbol` each of interop forms (`.method`, `Class/member`, `new`), `require`, `in-ns`, `ns`, `eval`, `load-string`, `load-file`, `slurp`, `spit`, `future`, `agent`, `atom`, `alter-var-root`, `set!` and each dynamic var.
- [ ] **MCP-OP-QUERY-078**: clj-surgeon shall evaluate the expression without evaluating any part of it before admission.
- [ ] **MCP-OP-QUERY-079**: clj-surgeon shall initialize each context with no host classes and no host namespaces beyond the bound functions.
- [ ] **MCP-OP-QUERY-080**: When an expression `def`s a symbol that is qualified, or that names a bound function or a symbol of the pure core list, clj-surgeon shall refuse it with `error_type=denied-symbol` and leave the context as it was.
- [ ] **MCP-OP-QUERY-081**: The bound functions shall take no file path from the expression, and shall read only the index map.
- [ ] **MCP-OP-QUERY-082**: If the evaluation or the rendering of a request runs longer than the time bound, then clj-surgeon shall stop the evaluation as MCP-OP-QUERY-105 requires, and shall refuse the request with `error_type=timeout` and a `remedy`.
  Changed in fix round 1: an interrupt flag is not enough, the evaluation must end (issue #7).
- [ ] **MCP-OP-QUERY-083**: If the evaluation of an expression throws, then clj-surgeon shall refuse the request with `error_type=evaluation-error` and the message of the exception, cut at the caller-text ceiling.
  Changed in fix round 1b: "throws" means any `Throwable`, `StackOverflowError` and other `Error`s included (issue #19). An exception that a bound function throws with a typed `error_type` keeps that type (see MCP-OP-QUERY-128).
  Changed in fix round 1c: a `StackOverflowError` is the exception to this rule. It gives `expression-too-deep` (MCP-OP-QUERY-125).

## Bounds

- [ ] **MCP-OP-QUERY-084**: If `expression` is longer than the expression bound, then clj-surgeon shall refuse the request with `error_type=expression-too-large` and a `remedy`, before it reads the expression.
- [ ] **MCP-OP-QUERY-085**: clj-surgeon shall render the final value into a writer that stops at the result bound, so that it never realizes a lazy value past the bound, whatever the kind of the value (see MCP-OP-QUERY-118).
  Changed in fix round 1: the rule holds for var, form, use, namespace, pull and skeleton values, not only for `pr-str` values (issue #10).
- [ ] **MCP-OP-QUERY-086**: If the rendering of a value that is not a skeleton exceeds the result bound, then clj-surgeon shall refuse the request with `error_type=result-too-large`, and with a `next_call` that narrows the request when one exists, else a `remedy`.
- [ ] **MCP-OP-QUERY-087**: If the rendering of a skeleton exceeds the transport ceiling, then clj-surgeon shall refuse the request with `error_type=result-too-large` and a `remedy`.
- [ ] **MCP-OP-QUERY-088**: clj-surgeon shall take every bound of the design from one configuration map, with the defaults of the design.

## Performance

- [ ] **MCP-OP-QUERY-089**: On a warm index of a workspace of about 700 namespaces, `skeleton`, `form` and `refs` requests shall complete within 100 ms, and a typical `pull` request within 500 ms, including the stale check.
- [ ] **MCP-OP-QUERY-090**: The cold build of a workspace of about 700 namespaces shall complete within 30 s, with the server heap under 1 GB.

## Fix round 1

IDs 091 and up come from fix round 1. Each cites its source: an issue (#6 to #11) or a decision of the owner on issue #3 (C1, C17, C25).

### Refusal with every problem (C25)

- [ ] **MCP-OP-QUERY-091**: When a refusal has one or more problems, clj-surgeon shall return them in `problems`, a non-empty list in which each item holds `error_type`, `error` and exactly one of `next_call` or `remedy`. A refusal with one problem shall have a list of one item.
- [ ] **MCP-OP-QUERY-092**: Before it evaluates the expression, clj-surgeon shall run these checks in this order and shall not stop at the first problem: each field of the request, the length of the expression, the workspace root, the reading of the expression, admission, and the check of the `def` names. A check shall run unless its input is missing. The reading needs an expression that is a string and within the length bound. Admission and the `def` check need a form that the reader could read. A problem with the workspace root shall not stop the checks of the expression.
  Changed in fix round 1b: the check of the nesting depth (MCP-OP-QUERY-124) runs after the length check and before the reading, and the reading needs an expression within the depth bound. A `Throwable` in one check becomes a problem of that check (MCP-OP-QUERY-125), and the checks that need its output do not run (issue #19).
- [ ] **MCP-OP-QUERY-093**: Admission shall report each distinct denied symbol as its own problem, in the order of its first occurrence in the form.
- [ ] **MCP-OP-QUERY-094**: The check of the `def` names shall report each refused name, and `session-full` when the limit applies, each as its own problem.
- [ ] **MCP-OP-QUERY-095**: A problem that arises during the refresh, the evaluation or the rendering (`unknown-namespace`, `unknown-var`, `pull-too-deep`, `timeout`, `evaluation-error`, `result-too-large`, `index-unavailable`, `busy`) shall end the request, and `problems` shall hold that problem alone.

### Cycles in a pull (C17)

- [ ] **MCP-OP-QUERY-096**: When `pull` cuts a join because it reaches a var that is on the path from the root, `pull` shall return that var as a map that holds its id and the key `:pull/cycle-to`, whose value is the qualified name of the var on the path that the map returns to.
- [ ] **MCP-OP-QUERY-097**: When clj-surgeon renders a pull result, it shall render a cycle map as a line `<qname> cycle: returns to <qname> (closed by <parent qname>)`, where the closing var is the var whose join holds the cycle map, and shall never render it as a plain id.

### Platform of a var (C1)

- [ ] **MCP-OP-QUERY-098**: clj-surgeon shall set `:platform` of a var record to `:clj` for a var of a `.clj` file, to `:cljs` for a var of a `.cljs` file, and for a var of a `.cljc` file to `:clj`, `:cljs` or `:cljc` as MCP-OP-QUERY-099 says.
- [ ] **MCP-OP-QUERY-099**: When a `.cljc` file defines a var, clj-surgeon shall make one record for each platform on which the analysis of the var differs (`:arglists`, `:calls`, `:fx`, `:uses-kw`, `:reads-kw`, `:throws?`, `:kind`, `:private?`, `:doc`, or the form), with `:platform` `:clj` or `:cljs`. When the analysis is the same on both platforms, clj-surgeon shall make one record with `:platform` `:cljc`. For a var that a reader conditional defines, `:start`, `:end` and `:source` shall be those of the defining form of that platform.
- [ ] **MCP-OP-QUERY-100**: `skeleton`, `form` and `pull` shall return each version of a var with its platform. A join of `pull` that leaves a version shall reach the versions of the target that apply to the platform of the source: the target version of the same platform, or the `:cljc` version, and every version of the target when the source is `:cljc`.
- [ ] **MCP-OP-QUERY-101**: A namespace shall live in one file. If two files declare the same namespace, then clj-surgeon shall index the file with the smaller path, shall not index the other file, shall list it in `skipped` with the reason `duplicate-namespace`, and shall set `read_complete=false`. Inside one file, the first definition of a var and platform shall win.

### Admission (issue #6)

- [ ] **MCP-OP-QUERY-102**: If admission meets a special form that it cannot walk completely, then clj-surgeon shall refuse the request. Such forms include `let*` or `loop*` with a binding form that is not a vector of even length, and an `fn` arity that is not a list that starts with a vector. The refusal shall have `error_type=invalid-expression` and a `remedy` that names the form. Admission shall never skip a part of a form because it is malformed.
- [ ] **MCP-OP-QUERY-103**: Admission shall walk the reader metadata of every symbol and every collection of the form as code, and shall report each denied symbol in it.
- [ ] **MCP-OP-QUERY-104**: clj-surgeon shall refuse with `error_type=denied-symbol`, naming the marker, each `def` or `defn` whose name carries `:macro` or `:sci/macro` metadata in a shape that admission can read without evaluation (`^:macro`, `^:sci/macro`, `^{:macro true}`, `^{:sci/macro true}`, and the attr-map of `defn`). This is the early check. MCP-OP-QUERY-122 is the authoritative check.
  Changed in fix round 1c: superseded by the allowlist of MCP-OP-QUERY-130 and 131. The blocklist of shapes in this text no longer decides what admission accepts. The refusal that names the marker stays for a readable marker. MCP-OP-QUERY-121 to 123 stay as defense in depth (issue #24).
  Changed in fix round 1b: the early check covers `:sci/macro` and the `defn` attr-map, and it is no longer the only check (issue #18).

### Time bound (issue #7)

- [ ] **MCP-OP-QUERY-105**: When the time bound passes, the evaluation shall end, the worker shall stop using CPU and memory within the stop grace (default 1 s), and the request shall not change the state of any session. A `def` that a late worker would complete shall have no effect.
- [ ] **MCP-OP-QUERY-106**: clj-surgeon shall give each evaluation a deadline. The SCI context shall check the deadline during evaluation through its interrupt hook (`:interrupt-fn`), and shall bind the interruptible `clojure.core` of SCI (`sci.interrupt/clojure-core`) for the lazy and looping functions of the pure core list, so that `(loop [] (recur))` and `(last (range))` end at the deadline.
- [ ] **MCP-OP-QUERY-107**: Each bound function and the renderer shall check the deadline and the interrupt flag of their thread in every loop whose length depends on the data, so that they end at the deadline.
- [ ] **MCP-OP-QUERY-108**: clj-surgeon shall bound the refresh of the index (discovery, the stale check, the reads, the analysis and the build-file reads) with a refresh time bound (default 60 s) that it checks before each file. If the refresh passes the bound, then clj-surgeon shall add the files that it finished to the index in one step, shall refuse the request with `error_type=timeout` and a `remedy` that tells the agent to call again, and shall not leave a worker running.
- [ ] **MCP-OP-QUERY-109**: If the number of workers that did not end after a timeout reaches the worker cap (default 2), then clj-surgeon shall refuse each new request that needs an evaluation with `error_type=busy` and a `remedy`, until a worker ends.
- [ ] **MCP-OP-QUERY-110**: clj-surgeon shall compute the nearest names of MCP-OP-QUERY-054 from at most the first 64 characters of the name, and shall check the deadline while it does so.

### Pull bounds (issue #8)

- [ ] **MCP-OP-QUERY-111**: The depth of a pull shall be the greatest number of joins on any path from the root of the pattern, where a numeric join `n` counts as `n` joins and an explicit join counts as 1. clj-surgeon shall compute the depth before `pull` visits the index.
- [ ] **MCP-OP-QUERY-112**: If a pull would return more than the pull node budget (default 2,000 entities, cycle maps included), then clj-surgeon shall stop the pull as soon as it reaches the budget and shall refuse the request with `error_type=result-too-large` and a `remedy`.

### Sessions (issue #9)

- [ ] **MCP-OP-QUERY-113**: If a request throws, runs out of time, or is refused after its evaluation started, then clj-surgeon shall leave the context, and the names that it holds, as they were before the request.
- [ ] **MCP-OP-QUERY-114**: clj-surgeon shall not let a worker that outlived its request change the context, the names or the values of any session.
- [ ] **MCP-OP-QUERY-115**: clj-surgeon shall run the requests of one session and one workspace root one after another.
- [ ] **MCP-OP-QUERY-116**: clj-surgeon shall apply the limit of MCP-OP-QUERY-073 only when the request adds a name that the context does not hold. A request that adds no name shall never be refused with `session-full`.
- [ ] **MCP-OP-QUERY-117**: clj-surgeon shall bound the data that a context holds. If a `def` would make the values of the context hold more than the item bound (default 100,000 items, counted through nested collections, with a lazy sequence realized only up to the bound), then clj-surgeon shall refuse the request with `error_type=session-full` and a `remedy`, and shall leave the context as it was.
  Changed in fix round 1c: the item bound is a session bound that reaches the sessions object from the configuration (MCP-OP-QUERY-134, issue #26).

### Rendering bound (issue #10)

- [ ] **MCP-OP-QUERY-118**: For every kind of value, clj-surgeon shall realize at most `limit + 1` elements of a sequence to render it, where `limit` is the result bound that applies, and shall decide the kind of a sequence from the elements that it realized.

### Ingest bounds (issue #11)

- [ ] **MCP-OP-QUERY-119**: clj-surgeon shall stop to read files when the bytes read in one refresh reach the total-byte cap (default 128 MiB). It shall list each file that it did not read in `skipped` with the reason `total-bytes`, in order of path, and shall set `read_complete=false`.
- [ ] **MCP-OP-QUERY-120**: If a build file or the `.clj-kondo/config.edn` file is larger than the config-byte cap (default 1 MiB), then clj-surgeon shall not read it, shall treat it as unreadable (a build file then adds only its `test` and `tests` directories, and the config adds no `:lint-as` entry), shall list it in `skipped` with the reason `too-large`, and shall set `read_complete=false`.

## Fix round 1b

IDs 121 and up come from fix round 1b. Each cites its source: issue #18 or issue #19.

### No session macro (issue #18)

- [ ] **MCP-OP-QUERY-121**: A var shall count as a macro when SCI 0.15.58 treats it as one. This holds when any of these is true, with any truthy value: the metadata of the var has `:macro` (`sci.lang.Var/isMacro`, `sci/lang.cljc`); the metadata of the root value of the var has `:sci/macro` (`sci.lang.Var/isMacro`); the metadata of the value at the head of a call has `:sci/macro` or `:macro` (`sci.impl.utils/macro?`, used by `sci.impl.analyzer` when it expands a call). The metadata of a `def` name is evaluated, so a computed value counts (`^{:sci/macro (= 1 1)} m`). The value of a macro marker shall not decide the matter other than by its truthiness.
- [ ] **MCP-OP-QUERY-122**: After the evaluation of a request, and before it accepts the result, clj-surgeon shall ask the SCI context whether a var that the request defined or changed is a macro as MCP-OP-QUERY-121 says. It shall do so whether the evaluation returned or threw. If any such var is a macro, then clj-surgeon shall restore the context as MCP-OP-QUERY-113 requires, and shall refuse the request with `error_type=denied-symbol`, a message that names the var and the marker (`:macro` or `:sci/macro`), and a `remedy`. This refusal has priority over `evaluation-error` and over the value of the request. The check shall cover each shape that SCI honors: a literal marker, an attr-map, a computed marker, and a marker on the metadata of the root value (`(def m (with-meta (fn [&form &env] ...) {:sci/macro true}))`, `vary-meta`, `alter-meta!`).
- [ ] **MCP-OP-QUERY-123**: clj-surgeon shall initialize each SCI context with the `:deny` option set to the symbols that MCP-OP-QUERY-077 lists, so that no expansion of a macro that a request defines and calls in the same request can resolve a denied symbol. This is a second barrier. It does not replace MCP-OP-QUERY-122.

### Failure before the worker (issue #19)

- [ ] **MCP-OP-QUERY-124**: clj-surgeon shall refuse an expression whose nesting depth is greater than the depth bound (default 256, in `default-bounds` as `:max-expression-depth`), with `error_type=expression-too-deep` and a `remedy` that gives the bound and the depth found. It shall measure the depth with one iterative scan of the expression text, with no recursion and no reader call. The scan shall count one level for each open `(`, `[`, `{`, `#(`, `#{` and for each prefix of a form (`'`, `` ` ``, `~`, `~@`, `@`, `^`, `#'`, `#_`, `#?`, `#?@`), and shall not count what is inside a string, a comment or a character literal. The scan shall run after the length check of MCP-OP-QUERY-084 and before the reading of MCP-OP-QUERY-075.
- [ ] **MCP-OP-QUERY-125**: Each step that runs before the bounded worker (the reading, admission, the check of the `def` names, and any other pre-evaluation step) shall turn any `Throwable`, `StackOverflowError` included, into a typed refusal. A `StackOverflowError` shall give `error_type=expression-too-deep` and a `remedy` that gives the depth bound and tells the agent to flatten the expression (see MCP-OP-QUERY-124). Any other `Throwable` shall give `error_type=internal-error` (see MCP-OP-QUERY-126). A `StackOverflowError` in the evaluation shall give `expression-too-deep` in the same way, not `evaluation-error`. A `Throwable` shall never leave `execute-request!`.
  Changed in fix round 1c: a `StackOverflowError` gives `error_type=expression-too-deep`, with a `remedy` that gives the bound and says to flatten the expression, the same as the depth cap of MCP-OP-QUERY-124. It no longer gives `invalid-expression` with `reason=too-deep`. This holds for a `StackOverflowError` in any step, also in the evaluation (owner decision 2026-10-18, issue #3).
- [ ] **MCP-OP-QUERY-126**: The refusal `internal-error` shall mean that clj-surgeon failed in a step that is not the fault of the expression. It shall hold an `error` with the class of the `Throwable` and its message cut at the caller-text ceiling, and a `remedy` that tells the agent to send the request again, and to report the failure when it comes again. It shall not hold a stack trace.
- [ ] **MCP-OP-QUERY-127**: `mcp-operation/invoke!` shall never let a `Throwable` escape the tool handler. If `execute`, `summarize` or the callback throws, then `invoke!` shall return the typed refusal `internal-error` of MCP-OP-QUERY-126 (or `expression-too-deep` for a `StackOverflowError`) with `elapsed_ms`, as an outcome of the class `:typed-refusal`.
  Changed in fix round 1c: a `StackOverflowError` gives `expression-too-deep` as MCP-OP-QUERY-125 says, in every case (owner decision 2026-10-18, issue #3).

### Typed refusals from the bounded work (wiring of 107, 108, 109, 112, 119)

- [ ] **MCP-OP-QUERY-128**: If a bound function, the renderer or the worker pool throws a typed refusal (`result-too-large`, `busy`, `timeout`, `unknown-namespace`, `unknown-var`, `pull-too-deep`, `invalid-expression`), then clj-surgeon shall return that `error_type`, also when SCI wraps the exception in another one. It shall never turn such a refusal into `evaluation-error`. A pull over the node budget of MCP-OP-QUERY-112 shall give `result-too-large` with a `remedy`. A request that meets the worker cap of MCP-OP-QUERY-109 shall give `busy`.
- [ ] **MCP-OP-QUERY-129**: The tool phase shall wire the bounds of MCP-OP-QUERY-107, 108, 109, 112 and 119 as follows. The bound `pull` shall take the pull node budget and the deadline check. The renderer shall take the deadline check. The refresh shall plan its reads with `plan-reads` from the total-byte cap before it reads, and shall check the refresh deadline before each file. `run-with-timeout` shall take the stop grace and the worker cap. Each value shall come from the effective bounds of MCP-OP-QUERY-088.

## Fix round 1c

IDs 130 and up come from fix round 1c. Each cites its source: issue #24, issue #25, issue #26 or the owner decision of 2026-10-18 on issue #3.

### Allowlist of def shapes (issue #24)

- [ ] **MCP-OP-QUERY-130**: Admission shall accept a `def` or `defn` form, at any depth of the expression, only in one of these shapes, and shall refuse every other shape. The shapes are `(def name)`, `(def name value)`, `(def name "doc" value)`, `(defn name "doc"? [params] body...)` and `(defn name "doc"? ([params] body...)+)`. A `defn` shall have no attr-map, in any position. `name` shall be a plain symbol, or a symbol with the metadata that MCP-OP-QUERY-131 allows. The `value`, the params and the body shall follow the other rules of admission. A form that is not in a shape shall be refused with `error_type=invalid-expression`, `reason=def-shape`, a message that names the form, and a `remedy` that gives the shapes that are allowed.
  The forms `defn-`, `defmacro`, `definline`, `defmulti`, `defmethod`, `defonce`, `deftype`, `defrecord`, `defprotocol`, `intern` and `alter-meta!` are not in the allowlist. MCP-OP-QUERY-077 does not name any of them, so the allowlist is what denies them. clj-surgeon shall refuse each of them with `error_type=denied-symbol`, a message that names the symbol, and a `remedy` that names the allowed `def` shapes. The refusals that MCP-OP-QUERY-077 lists stay in force.
- [ ] **MCP-OP-QUERY-131**: Metadata on the name of a `def` or `defn`, written as reader metadata (`^`) or as a `with-meta` form on the name, shall be refused unless every key is `:doc` or `:private` and every value is a literal. The value of `:doc` shall be a string. The value of `:private` shall be a boolean. A key that is not in this list (`:macro`, `:sci/macro`, `:tag`, `:arglists` and any computed key included), a value that is not a literal, or a map that is not a literal shall give the refusal `invalid-expression` with `reason=def-shape`, a message that names the key, and a `remedy` that gives the two allowed keys.
  `:macro` and `:sci/macro` shall keep the refusal `denied-symbol` of MCP-OP-QUERY-104 when the shape is readable, so that the message still names the marker.

### Top-level `do` in sub-forms (issue #24)

- [ ] **MCP-OP-QUERY-132**: clj-surgeon shall split a top-level `do`, and each `do` that is a direct sub-form of a top-level `do`, into its sub-forms, and shall evaluate the sub-forms one at a time in the session, in order. This is the same as a top-level `do` in Clojure, where each sub-form is evaluated before the next one is expanded. After each sub-form, and before the next one, clj-surgeon shall run the check of MCP-OP-QUERY-122 on each var that the request has defined or changed so far. If a check finds a macro, or if a sub-form throws or is refused, then clj-surgeon shall restore the context as MCP-OP-QUERY-113 requires, shall not evaluate the sub-forms that follow, and shall refuse the request as MCP-OP-QUERY-122 requires. The value of the request is the value of the last sub-form. Admission (MCP-OP-QUERY-076 to 078 and MCP-OP-QUERY-130) shall run on the whole form before the first sub-form is evaluated.

### Join `:var/_reads-kw` (issue #25)

- [ ] **MCP-OP-QUERY-133**: `pull` shall follow the join `:var/_reads-kw`, in the same way as MCP-OP-QUERY-049 for the other joins. From a keyword, the join shall return the versions of the vars that read the keyword (the vars whose `:reads-kw` holds its name), sorted by qualified name and then by platform, as MCP-OP-QUERY-100 requires for the platform. A pull through this join shall never throw. No earlier spec states this join.

### Session bounds reach the sessions object (issue #26)

- [ ] **MCP-OP-QUERY-134**: Every session bound of the effective bounds of MCP-OP-QUERY-088 (`:max-contexts`, `:idle-ms`, `:max-defs`, `:max-def-items`, and each other bound that the sessions object reads) shall reach the sessions object that the tool uses, and shall have the effect that MCP-OP-QUERY-073 and MCP-OP-QUERY-117 describe. A bound that the configuration sets shall replace the default. Only a bound that the configuration does not set shall take the default.

### The reply carries no file receipts (decision C24, business-case step 0)

Changed in business-case step 0: MCP-OP-QUERY-003, 031, 033 and the summary of MCP-OP-QUERY-006 (API).

- [ ] **MCP-OP-QUERY-135**: The success result of `query_clojure` shall not hold the keys `files` or `reingested`. clj-surgeon shall record them as Clojure metadata on the result map (`:files` as in MCP-OP-QUERY-033, `:reingested` as in MCP-OP-QUERY-031), so that tests and receipts can read them and the MCP reply does not carry them. The one-line summary shall be `<value>`, a newline, then `;; <value_chars> chars · <elapsed>`. Reason: decision C24 on issue #3. On a snapshot of this repository, the `files` list was 52,180 of the 52,454 characters of one whole-workspace reply.

### Search from words to vars (issue #27, decision 2026-10-19)

Reason: in the business-case step 1, agents began each task with a text search for words from the issue, and every other bound function needs a name (https://github.com/markaddleman/clj-surgeon/issues/3#issuecomment-6258641541). In the offline replay, BM25 put a gold var first in 5 of 6 agent searches, against 2 of 6 for grep's order (https://github.com/markaddleman/clj-surgeon/issues/3#issuecomment-6264154611).

- [ ] **MCP-OP-QUERY-136**: The evaluation context shall bind `search` with two arities: `(search text)` and `(search text opts)`. `text` shall be a string that is not blank and has at most `:max-expression-chars` characters. `opts` may hold `:limit` (an integer from 1 to 50, default 10) and `:include-tests` (a boolean, default false). Any other input shall throw `{:error-type :invalid-expression}`, as the other bound functions do for a bad argument, with a remedy that gives a correct call.
- [ ] **MCP-OP-QUERY-137**: `search` shall rank the var records of the index by BM25 over three fields: the qualified name (weight 2), the docstring (weight 1) and the source (weight 1). One analysis shall apply to the fields and to `text`: split on whitespace and on the characters `()[]{}"'`,;#^@~:/\`, then split words on case changes, letter-digit changes, hyphens, underscores and dots, keep each original token as well, and lowercase. There shall be no stemming and no stop words. The query shall be the distinct terms of `text`, each a SHOULD clause, at most the first 340. Equal scores shall be ordered by qualified name.
- [ ] **MCP-OP-QUERY-138**: `search` shall return a vector of var maps in rank order, at most `:limit` long, in the shape of the `skeleton` var maps (MCP-OP-QUERY-040), except that `:name` holds the qualified name, so that each rendered line names its namespace. The vector shall carry the metadata `{:kind :search :query text}`, and it shall render as one var line for each var (MCP-OP-QUERY-059). A var with two platform versions shall count once, as its first version. When no var matches, the result shall be an empty vector.
- [ ] **MCP-OP-QUERY-139**: Unless `:include-tests` is true, `search` shall leave out vars whose file is a test file (a file under a `test` or `tests` directory of its project, as in MCP-OP-QUERY-044). The files of the returned vars shall count as touched files (MCP-OP-QUERY-033).
- [ ] **MCP-OP-QUERY-140**: The search index shall be built in memory from the var records of the current index map only, never from the file system, on the first `search` after a refresh that changed the index. clj-surgeon shall keep the search indexes of at most 4 index maps and reuse the one built from the identical index map.
- [ ] **MCP-OP-QUERY-141**: When a warm refresh finds no file to ingest, no file to remove and the same discovery skips as the index holds, clj-surgeon shall keep the identical index map and shall not rebuild it. Reason: the search index (MCP-OP-QUERY-140) is reused for the identical index map, and a rebuild costs about 2 s on a repository of 1,332 files.
