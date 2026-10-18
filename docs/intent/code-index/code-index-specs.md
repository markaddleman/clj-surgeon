---
parent: code-index-design
prefix: MCP-OP-QUERY
status: draft (index/slice-a)
---

# Code Index (`query_clojure`) Specifications

IDs are stable and must not be reused if a requirement is deleted.

## Tool contract

- [ ] **MCP-OP-QUERY-001**: The `:full` tool profile shall publish a tool named `query_clojure` with outcome classes `#{:read-success :typed-refusal}` and annotations that mark it read-only, idempotent and not open world.
- [ ] **MCP-OP-QUERY-002**: The `query_clojure` input schema shall require the string field `expression`, shall accept the optional string field `workspace_root`, and shall refuse any other field with a typed refusal before it resolves the workspace or reads a file.
- [ ] **MCP-OP-QUERY-003**: When a request succeeds, clj-surgeon shall return `ok=true`, `operation="query-clojure"`, `value` (text), `value_chars` equal to the length of `value`, `files`, `index`, `reingested`, `read_complete`, `session`, `workspace_root` and `elapsed_ms`.
- [ ] **MCP-OP-QUERY-004**: When a request is refused, clj-surgeon shall return `ok=false`, `operation="query-clojure"`, an `error_type` from the refusal table of the design, an `error` message, and exactly one of `next_call` or `remedy`.
- [ ] **MCP-OP-QUERY-005**: clj-surgeon shall never return a `next_call` whose arguments equal the arguments of the call that produced it.
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
- [ ] **MCP-OP-QUERY-020**: When clj-surgeon ingests a file, it shall make one var record for each top-level var that the file defines in its namespace, with `:qname`, `:ns`, `:name`, `:file`, `:start`, `:end`, `:kind`, `:private?`, `:arglists`, `:doc`, `:intent`, `:source`, `:calls`, `:fx`, `:uses-kw`, `:reads-kw` and `:throws?`.
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
- [ ] **MCP-OP-QUERY-031**: When the stale check finds a file whose mtime or size differs from its last ingest, a new source file, or a removed source file, clj-surgeon shall update the index for those files before it evaluates the expression, and shall list the re-ingested files in `reingested`.
- [ ] **MCP-OP-QUERY-032**: clj-surgeon shall evaluate the expression of a request against one index map, the one that the atom held after the stale check of that request.
- [ ] **MCP-OP-QUERY-033**: clj-surgeon shall list in `files` each source file of which a bound function returned data during the request, with the SHA-256 recorded at its ingest.
- [ ] **MCP-OP-QUERY-034**: When the first request for a workspace arrives, clj-surgeon shall build the index of all its source files with a bounded worker pool, and shall report the build time in `index.built_ms`.
- [ ] **MCP-OP-QUERY-035**: If the cold build of a workspace fails, then clj-surgeon shall refuse the request with `error_type=index-unavailable` and a `remedy` that names the cause.

## Functions

- [ ] **MCP-OP-QUERY-036**: The evaluation context shall bind in `user` the functions `namespaces`, `skeleton`, `form`, `refs`, `pull` and `help`, and no other function that reads or acts on the host.
- [ ] **MCP-OP-QUERY-037**: Each bound function shall return only maps, vectors, strings, numbers, booleans, keywords and nil, and shall never return a host object, the index map, or a reference into it.
- [ ] **MCP-OP-QUERY-038**: `(namespaces)` shall return a vector with one map `{:kind :ns :ns :file :vars :doc}` for each namespace of the index, sorted by name, where `:vars` is the number of its vars.
- [ ] **MCP-OP-QUERY-039**: `(skeleton ns)` shall accept `ns` as a symbol or a string, and shall return a vector of var maps for the vars of `ns` in order of their start line, with metadata `{:kind :skeleton :ns :doc}`.
- [ ] **MCP-OP-QUERY-040**: A var map shall hold `:kind :var`, `:qname`, `:ns`, `:name`, `:file`, `:start`, `:end`, `:arglists`, `:private?`, `:calls`, `:fx` and `:throws?`, and `:intent` when the var has one.
- [ ] **MCP-OP-QUERY-041**: When clj-surgeon ingests a var, it shall set `:fx` to the sorted, distinct names out of the fixed side-effect list of the design that name a var that the form uses, in any namespace and `clojure.*` included, because most names of that list are `clojure.core` functions that `:calls` leaves out.
- [ ] **MCP-OP-QUERY-042**: `(form sym)` and `(form [sym …])` shall accept qualified symbols or strings, and shall return a vector with one map `{:kind :form :qname :file :start :end :source}` for each var, in the order given.
- [ ] **MCP-OP-QUERY-043**: `(refs sym)` shall return a vector with one map `{:kind :use :file :line :ns :from :use-kind}` for each use of `sym` in the index, sorted by file and line, where `:use-kind` is one of `call`, `alias-call`, `value` and `refer`.
- [ ] **MCP-OP-QUERY-044**: When `(refs sym {:include-tests false})` is called, clj-surgeon shall leave out each use in a file under a `test` or `tests` directory of its project.
- [ ] **MCP-OP-QUERY-045**: clj-surgeon shall not count as a use of a var a symbol that occurs only in a string, a comment, or a quoted form.
- [ ] **MCP-OP-QUERY-046**: `(help)` shall return a string that names each bound function with its arguments and one example, and `(help fn-name)` shall return the part for that function.

## `pull`

- [ ] **MCP-OP-QUERY-047**: `(pull pattern eid)` shall accept as `eid` exactly `[:var/qname s]`, `[:ns/name s]` and `[:kw/name s]`, and shall refuse any other form of `eid` with `error_type=invalid-expression`.
- [ ] **MCP-OP-QUERY-048**: `pull` shall return, for each attribute of the pattern that the entity has, its value, and shall leave out each attribute that the entity does not have, without a refusal.
- [ ] **MCP-OP-QUERY-049**: `pull` shall follow the joins `:var/calls`, `:var/_calls`, `:var/uses-kw`, `:var/_uses-kw`, `:var/reads-kw`, `:var/in-ns`, `:ns/_in-ns` and `:var/_in-ns` with the sub-pattern of the join.
- [ ] **MCP-OP-QUERY-050**: When a join holds a number `n` in place of a sub-pattern, `pull` shall apply the parent pattern recursively to depth `n`.
- [ ] **MCP-OP-QUERY-051**: If a recursion depth is greater than 3, or a join holds `...`, then clj-surgeon shall refuse the request with `error_type=pull-too-deep` and a `next_call` that uses depth 1.
- [ ] **MCP-OP-QUERY-052**: When a recursive pull reaches a var that is already on the path from the root, `pull` shall return that var as its id only and shall not descend further.
- [ ] **MCP-OP-QUERY-053**: When a join of `pull` reaches a called var that has no var record in the index, `pull` shall return it as `{:var/qname q :var/external? true}`.

## Unknown names

- [ ] **MCP-OP-QUERY-054**: If `skeleton` or `pull` names a namespace that is not in the index, then clj-surgeon shall refuse the request with `error_type=unknown-namespace`, and with a `next_call` to `skeleton` of the nearest indexed namespace names when there is at least one, else a `remedy`.
- [ ] **MCP-OP-QUERY-055**: If `form`, `refs` or `pull` names a var that is not in the index, then clj-surgeon shall refuse the request with `error_type=unknown-var`, and with a `next_call` to `skeleton` of its namespace when that namespace is in the index, else a `remedy`.

## Rendering

- [ ] **MCP-OP-QUERY-056**: clj-surgeon shall render only the final value of the expression into `value`.
- [ ] **MCP-OP-QUERY-057**: When the final value is a string, clj-surgeon shall publish it unchanged.
- [ ] **MCP-OP-QUERY-058**: When the final value is a var map, or a vector of var maps, clj-surgeon shall render one var line for each, and when the vector has skeleton metadata it shall start with the namespace line `ns <name> — <first sentence of the ns doc>`.
- [ ] **MCP-OP-QUERY-059**: clj-surgeon shall render a var line as `λ <name>(<arglists joined by " | ">)` followed, each only when present and in this order, by ` | calls <c1>,<c2>,…`, ` | fx <f1>,…`, ` | throws`, ` | private` and ` — <intent>`.
- [ ] **MCP-OP-QUERY-060**: In a var line, clj-surgeon shall write each called var of the same namespace as its bare name, and each called var of another namespace as the last segment of that namespace, `/`, and its name, and shall sort the written names in lexicographic order.
- [ ] **MCP-OP-QUERY-061**: When the final value is a form map, or a vector of form maps, clj-surgeon shall render for each a header line `;; <qname>  <file>:<start>-<end>` followed by its `:source` unchanged.
- [ ] **MCP-OP-QUERY-062**: When the final value is a vector of use maps, clj-surgeon shall render one line `<file>:<line> <ns>/<from> <use-kind>` for each, followed by one total line.
- [ ] **MCP-OP-QUERY-063**: When the final value is a namespace map, or a vector of namespace maps, clj-surgeon shall render one line `ns <name> (<vars> vars) — <first sentence of the doc>` for each.
- [ ] **MCP-OP-QUERY-064**: When the final value is a pull result, clj-surgeon shall render one line for each entity with its id first and its scalar attributes in the fixed order of the design, a join that holds only ids as one line `<label>: <id>, …`, a join that holds more as lines indented under its parent, and `:var/source` as a code block.
- [ ] **MCP-OP-QUERY-065**: When the final value is any other value, clj-surgeon shall render it with `pr-str`.
- [ ] **MCP-OP-QUERY-066**: clj-surgeon shall produce byte-identical `value` text for the same expression against the same index map and the same session state.

## Sessions

- [ ] **MCP-OP-QUERY-067**: clj-surgeon shall keep one evaluation context for each pair of MCP session id and canonical workspace root, and shall give a request with no session id a fresh context that it uses once.
- [ ] **MCP-OP-QUERY-068**: When an expression `def`s a name, clj-surgeon shall keep that var in the context of the session, so that a later request of the same session and workspace can use it.
- [ ] **MCP-OP-QUERY-069**: clj-surgeon shall not make a var that one session `def`s visible to any other session.
- [ ] **MCP-OP-QUERY-070**: clj-surgeon shall keep at most 32 session contexts, shall drop the least recently used one first, and shall drop a context that has been idle for 2 hours.
- [ ] **MCP-OP-QUERY-071**: When a request arrives for a session whose context clj-surgeon dropped, clj-surgeon shall evaluate it in a fresh context and shall return `session.reset=true`.
- [ ] **MCP-OP-QUERY-072**: clj-surgeon shall return in `session.defs` the names that the context of the session holds after the request.
- [ ] **MCP-OP-QUERY-073**: If a `def` would make the context hold more than 64 names, then clj-surgeon shall refuse the request with `error_type=session-full` and a `remedy`, and shall leave the context as it was.
- [ ] **MCP-OP-QUERY-074**: When the final value of an expression is a var that it `def`d, clj-surgeon shall render the line `#'user/<name>` followed by one line that gives the kind and the count of the value.

## Evaluation fence

- [ ] **MCP-OP-QUERY-075**: clj-surgeon shall read `expression` with the SCI reader, without reader eval, and shall refuse with `error_type=invalid-expression` an expression that holds no form, more than one form, or a form that the reader cannot read.
- [ ] **MCP-OP-QUERY-076**: Before evaluation, clj-surgeon shall admit each symbol of the form only when it is a local, a bound function, a symbol of the pure core list, a name that the session has `def`d, or a macro of the expansion list, and shall refuse any other symbol with `error_type=denied-symbol`, a message that names the symbol, and a `remedy` that names the bound functions.
- [ ] **MCP-OP-QUERY-077**: clj-surgeon shall refuse with `error_type=denied-symbol` each of interop forms (`.method`, `Class/member`, `new`), `require`, `in-ns`, `ns`, `eval`, `load-string`, `load-file`, `slurp`, `spit`, `future`, `agent`, `atom`, `alter-var-root`, `set!` and each dynamic var.
- [ ] **MCP-OP-QUERY-078**: clj-surgeon shall evaluate the expression without evaluating any part of it before admission.
- [ ] **MCP-OP-QUERY-079**: clj-surgeon shall initialize each context with no host classes and no host namespaces beyond the bound functions.
- [ ] **MCP-OP-QUERY-080**: When an expression `def`s a symbol that is qualified, or that names a bound function or a symbol of the pure core list, clj-surgeon shall refuse it with `error_type=denied-symbol` and leave the context as it was.
- [ ] **MCP-OP-QUERY-081**: The bound functions shall take no file path from the expression, and shall read only the index map.
- [ ] **MCP-OP-QUERY-082**: If the evaluation or the rendering of a request runs longer than the time bound, then clj-surgeon shall interrupt the worker and shall refuse the request with `error_type=timeout` and a `remedy`.
- [ ] **MCP-OP-QUERY-083**: If the evaluation of an expression throws, then clj-surgeon shall refuse the request with `error_type=evaluation-error` and the message of the exception, cut at the caller-text ceiling.

## Bounds

- [ ] **MCP-OP-QUERY-084**: If `expression` is longer than the expression bound, then clj-surgeon shall refuse the request with `error_type=expression-too-large` and a `remedy`, before it reads the expression.
- [ ] **MCP-OP-QUERY-085**: clj-surgeon shall render the final value into a writer that stops at the result bound, so that it never realizes a lazy value past the bound.
- [ ] **MCP-OP-QUERY-086**: If the rendering of a value that is not a skeleton exceeds the result bound, then clj-surgeon shall refuse the request with `error_type=result-too-large`, and with a `next_call` that narrows the request when one exists, else a `remedy`.
- [ ] **MCP-OP-QUERY-087**: If the rendering of a skeleton exceeds the transport ceiling, then clj-surgeon shall refuse the request with `error_type=result-too-large` and a `remedy`.
- [ ] **MCP-OP-QUERY-088**: clj-surgeon shall take every bound of the design from one configuration map, with the defaults of the design.

## Performance

- [ ] **MCP-OP-QUERY-089**: On a warm index of a workspace of about 700 namespaces, `skeleton`, `form` and `refs` requests shall complete within 100 ms, and a typical `pull` request within 500 ms, including the stale check.
- [ ] **MCP-OP-QUERY-090**: The cold build of a workspace of about 700 namespaces shall complete within 30 s, with the server heap under 1 GB.
