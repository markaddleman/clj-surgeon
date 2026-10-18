# Code Index API Contract

Status: draft contract for slice A of issue #3. Parallel test writers and the
implementer share this file. It refines
[code-index-design.md](code-index-design.md) (the LLD) and serves the specs in
[code-index-specs.md](code-index-specs.md) (MCP-OP-QUERY-001 through 090).

Where the LLD or the specs leave a detail open, this file chooses one and marks
it **Decided in the API contract**. A reviewer can search for that phrase.

## Conventions

- All namespaces are flat, in `src/clj_surgeon/`: `code_index_render.clj`,
  `code_index_ingest.clj`, `code_index.clj`, `code_index_discovery.clj`,
  `code_index_pull.clj`, `code_index_functions.clj`, `code_index_fence.clj`,
  `code_index_session.clj`, `mcp_query_clojure.clj`.
- Dependency order (no cycles): render, ingest, index, discovery, pull,
  functions (reads the index shape only), fence, session (uses fence), mcp-query-clojure (uses all).
- **Errors.** A function that refuses throws `(ex-info msg {:error-type <kw> ...})`.
  The `:error-type` keyword is the kebab-case `error_type` value of the specs
  (`:unknown-var` becomes `"unknown-var"` in the tool output). Extra ex-data keys
  are listed per function.
- **Qnames** are strings `"ns/name"`. Namespace names, var names and keyword
  names in the index are strings. Functions accept symbols or strings where the
  LLD says so, and return strings.
- **Paths** are strings, relative to the workspace root, with `/` separators.
- **Keyword values in the tool output** are strings (`:call` becomes `"call"`).
  Inside the index and inside function results they stay keywords.
- **Source positions** `:start`, `:end`, `:line` are 1-based. `:end` is inclusive.
- The shared literal fixtures are in
  `test/clj_surgeon/code_index_fixtures.clj` (namespace
  `clj-surgeon.code-index-fixtures`: `a-b-file`, `c-d-e-file`, `a-b-test-file`,
  `all-files`). They have exactly the shape that `code-index-ingest/file-result`
  returns. Their `:sha256` strings are 64-character fakes, not hashes of any text.

## Data shapes

### Var record (inside a file result and in `:vars` of the index)

```clojure
{:qname "a.b/f" :ns "a.b" :name "f"
 :file "src/a/b.clj" :start 4 :end 8
 :kind "clojure.core/defn"          ; defining macro; "clojure.core/defn-" for private defn
 :private? false
 :arglists ["[x]" "[x y]"]           ; strings; [] for a var that is not a function
 :doc "Does f. Then more."           ; key ABSENT when no docstring
 :intent "Does f."                   ; key ABSENT when no docstring (MCP-OP-QUERY-024)
 :source "<exact lines start..end joined by \n, no trailing newline>"
 :calls ["a.b/g" "c.d.e/h"]          ; sorted, distinct, clojure.* and cljs.core left out
 :fx ["swap!"]                       ; sorted, distinct, names only
 :uses-kw ["scope"]                  ; sorted, distinct keyword names
 :reads-kw []                        ; sorted, distinct destructured names
 :throws? false}
```

**Decided in the API contract.**
- `:source` is the whole lines `:start` through `:end` of the file, joined by
  `"\n"`, with no trailing newline.
- `:calls` leaves out a namespace named `clojure` or starting with `clojure.`, and
  `cljs.core`. A call to the var itself is kept (the specs say "the vars that the
  form uses").
- `:uses-kw` holds keyword literals of the form body, as `"scope"` for `:scope` and
  `"x/k"` for `:x/k`. `:reads-kw` holds the names that destructuring reads
  (`{:keys [db]}` gives `"db"`). A destructured name is in `:reads-kw` only.
- When two files define the same qname (for example the two branches of a `.cljc`
  file, or two files with one namespace), the var of the file with the smaller
  path wins in the index. Inside one file result, the first definition wins.
- A file without an `ns` form has `:ns "user"`. Only the first `ns` form's vars are
  recorded.

### Use

```clojure
{:file "src/a/b.clj" :line 7 :from "a.b/f" :target "a.b/g" :kind :call}
```

`:kind` is one of `:call :alias-call :value :refer`. `:from` is the qname of the
enclosing top-level var, or `nil` for a use outside any var (for example a `:refer`
in the `ns` form).

**Decided in the API contract.** A file result's `:uses` hold only uses whose
`:target` is not in a `clojure.*` namespace or `cljs.core`. The `:fx` facet is
computed from all uses, `clojure.*` included, before that filter.

### File result (output of `code-index-ingest/file-result`)

```clojure
{:path "src/a/b.clj" :mtime 1792000000000 :size 211 :sha256 "<64 hex>"
 :ns "a.b" :ns-doc "The a.b namespace."   ; :ns-doc key ABSENT when no docstring
 :vars [<var record> ...]                  ; source order
 :uses [<use> ...]}                        ; sorted by [:line :from :target :kind]
```

### Index map (output of `code-index/apply-ingest`, held in an atom)

```clojure
{:files {"src/a/b.clj" {:mtime 1792000000000 :size 211 :sha256 "…"
                        :ns "a.b" :vars ["a.b/f" "a.b/g"]   ; qnames, source order
                        :uses [<use> ...]}}
 :namespaces {"a.b" {:name "a.b" :file "src/a/b.clj" :doc "…"}}  ; :doc ABSENT if none
 :vars {"a.b/f" <var record>}
 :callers {"a.b/g" #{"a.b/f"}}                  ; callee qname -> set of caller qnames
 :kw-users {"scope" #{"a.b/f" "a.b/g"}}         ; kw name -> set of qnames
 :uses-by-target {"a.b/g" [<use> ...]}          ; sorted by [:file :line :from :kind-name]
 :skipped [{:file "src/big.clj" :reason :too-large :size 3000000}]
 :read-complete? true                           ; (empty? :skipped)
 :built-ms 812}
```

`:callers` keys include callees that have no var record (external vars such as
`"z.external/q"`). `:namespaces` has one entry for each file result `:ns`. If two
files claim one namespace, the smaller path wins, as for vars.

### Skipped entry

`{:file <path> :reason <kw> :size <n>}` with `:reason` in `:too-large`, `:unstable`,
`:unreadable`. The cap entry has no `:file`: `{:reason :file-cap :cap 10000}`.

## Bounds map (MCP-OP-QUERY-088)

`code-index/default-bounds`:

```clojure
{:max-files 10000                 ; source files discovered
 :max-file-bytes 2097152          ; 2 MiB
 :max-expression-chars 4000
 :eval-timeout-ms 10000
 :max-result-chars 30000          ; not a skeleton
 :max-skeleton-chars 200000       ; skeleton, and the transport ceiling
 :max-pull-depth 3
 :max-contexts 32
 :idle-ms 7200000                 ; 2 hours
 :max-defs 64
 :max-error-chars 1000            ; caller-text ceiling for evaluation-error text
 :pool-size 4}                    ; cold-build worker pool
```

**Decided in the API contract.** `:max-error-chars` and `:pool-size` are not in the
LLD Bounds table. They take the values above. A caller passes a partial map. The
effective bounds are `(merge default-bounds partial)`.

---

## Namespace `clj-surgeon.code-index-render`

Pure. Serves MCP-OP-QUERY-024, 056-066, 074, 085.

### `(first-sentence s) => string | nil`

Serves 024. `nil`, a non-string or a blank string gives `nil`. Otherwise: join runs
of whitespace to one space, trim, then take the text up to and including the first
`.`, `!` or `?` that a space or the end of the text follows, then cut to at most 200
characters. With no such terminator, the whole normalized text (cut to 200).

- `(first-sentence "Does f. Then more.")` => `"Does f."`
- `(first-sentence "A\n  multi   line doc? Yes.")` => `"A multi line doc?"`
- `(first-sentence "v1.2 is fine")` => `"v1.2 is fine"` (the `.` has no space after)
- `(first-sentence nil)` => `nil`

**Decided in the API contract.** The terminator is kept (spec 024: "up to and
including"). The LLD text ("up to the first") loses to the spec. A cut at 200 adds
no ellipsis.

### `(var-line var-map) => string`

Serves 059, 060. Input: a var map (the skeleton shape, or a var record). Keys used:
`:name :ns :arglists :calls :fx :throws? :private? :intent`.

Format, in order, each part only when present: 

```
λ <name>(<arglists joined by " | ">)
 | calls <c1>,<c2>,…
 | fx <f1>,<f2>,…
 | throws
 | private
 — <intent>
```

The parts join with no extra separator (the separators above start with a space).
`—` is U+2014 with one space on each side. The `λ` is U+03BB.

Called names (spec 060): a qname in the same namespace as `:ns` is written as the bare
name. A qname of another namespace is written as the last dot-segment of its
namespace, `/`, and its name. The written names are distinct and sorted
lexicographically (`compare` on strings).

Examples, using the fixture var `a.b/f`:

- `λ f([x] | [x y]) | calls e/h,g | fx swap! — Does f.`
- `λ g([x y]) | calls external/q | throws | private`
- `λ h([x]) | calls b/f` (`c.d.e/h` calls `a.b/f`, a var of another namespace)

`:fx`, `:calls` or `:arglists` absent or empty: the clause is left out. For the
arglists, empty gives `()`: `λ conf()`. `:throws?` true gives ` | throws`.
`:private?` true gives ` | private`. `:intent` blank or absent gives no ` — ` clause.

**Decided in the API contract.** An empty `:arglists` renders `λ name()`.

### `(ns-line ns-info) => string`

Serves 058. `ns-info` is a map with `:ns` (name) and optionally `:doc`, which is the
skeleton metadata shape. `"ns a.b — The a.b namespace."`. With no doc, `"ns a.b"`.
The doc is passed through `first-sentence`.

### `(namespace-line ns-map) => string`

Serves 063. Input `{:kind :ns :ns :file :vars :doc}`. `"ns a.b (2 vars) — The a.b namespace."`
With no doc: `"ns c.d.e (1 vars)"`. The word is always `vars`.

**Decided in the API contract.** The word is `vars` for a count of 1 as well (the
spec writes `(<vars> vars)`).

### `(form-block form-map) => string`

Serves 061. `";; a.b/f  src/a/b.clj:4-8\n<source>"` (two spaces after the qname; no
trailing newline; source unchanged).

### `(use-line use-map) => string`

Serves 062. Input `{:kind :use :file :line :ns :from :use-kind}`.
`"src/a/b.clj:7 a.b/f call"`: `<file>:<line> <ns>/<from> <name of use-kind>`. When
`:from` is nil: `"src/a/b.clj:2 a.b refer"` (no slash).

### `(render value) => string`

Serves 056-065, 066. The text of the final value, with no limit. Dispatch, in order:

1. A string: unchanged.
2. A map with `:kind :var`: `(var-line m)`.
3. A map with `:kind :form`, `:kind :use`, `:kind :ns`: their lines.
4. A map with metadata `{:kind :pull}`: the pull rendering (below).
5. A non-empty sequential (vector, list or lazy seq) whose elements are all maps of
   one of the kinds `:var`, `:form`, `:use`, `:ns`, or all pull results: the lines of
   its elements joined with `"\n"`, with these additions.
   - A vector with metadata `{:kind :skeleton}` starts with `(ns-line (meta v))`.
   - A vector of `:use` maps ends with the line `total <n>`.
   - A vector of `:form` maps joins its blocks with a blank line (`"\n\n"`).
6. Anything else, including an empty vector: `(pr-str value)`.

**Decided in the API contract.**
- The renderer dispatches on the `:kind` of the first element and requires all
  elements to match. A mixed vector falls to `pr-str`.
- `(filter ...)` on a skeleton loses the metadata, so it renders var lines only.
  This is the intended behavior.
- An empty skeleton (a namespace with no vars) is an empty vector with metadata. It
  renders as the `ns` line only: `"ns a.b — The a.b namespace."`. This is the one
  empty vector that does not render as `[]`.
- The total line is `total <n>` (n = number of uses).

#### Pull rendering

A pull result is a map with metadata `{:kind :pull}` (set by `code-index-pull/pull`).
Nested entity maps in joins have no metadata. Lines indent by two spaces for each
nesting level.

Entity line (level 0 has no indent):

- A var entity (has `:var/qname`):
  `<qname>` then, each only when present and in this order, a space and:
  `external` (when `:var/external?` is true), the value of `:var/kind`, `private`
  (when `:var/private?` is true), `<start>-<end>` (when `:var/start` and `:var/end`
  are present), the arglists joined by `" | "`, then ` — <intent>` from
  `:var/intent`, else ` — <doc>` from `:var/doc` (the whole docstring with
  whitespace joined to one space).
  Example: `a.b/g clojure.core/defn- private 10-13 [x y]`.
  Example with intent: `a.b/f clojure.core/defn 4-8 [x] | [x y] — Does f.`
- A namespace entity (has `:ns/name`): `<name>` then ` — <first sentence of :ns/doc>`
  when `:ns/doc` is present.
- A keyword entity (has `:kw/name`): `<name>`.
- The attributes `:var/name :var/ns :var/file :var/throws? :var/fx :ns/file` are not
  rendered.

After the entity line: if `:var/source` is present, a fenced block indented by two
spaces more than the entity line: a line `` ```clojure ``, the source lines, a line
` ``` ` (all with that indent).

Then the joins, in this fixed order and with these labels:
`:var/calls` "calls", `:var/_calls` "called by", `:var/uses-kw` "uses",
`:var/_uses-kw` "used by", `:var/reads-kw` "reads", `:var/_reads-kw` "read by",
`:var/in-ns` "in", `:ns/_in-ns` and `:var/_in-ns` "vars". A join whose elements each
hold only an id key (`:var/qname`, `:ns/name` or `:kw/name`) and nothing else renders
as one line `<indent+2><label>: <id>, <id>`. A join with any richer element renders
as `<indent+2><label>:` and then each element as an entity at indent+4. An empty
join gives no line.

**Decided in the API contract.** All of the above is the exact pull format. The LLD
leaves the entity line layout open. `:var/_reads-kw` is rendered but the specs do not
list it as a join; it is accepted by `pull` too (see `code-index-pull`).

### `(render-def name value) => string`

Serves 074. `"#'user/<name>\n<shape>"`. The shape:

- vector, map, set, list: `"<kind> of <n>"`, for example `"vector of 3"`, `"map of 2"`,
  `"set of 1"`, `"list of 4"`.
- a lazy seq or other sequence: `"seq of <n>"`, where n counts at most 10,001 items. At
  more than 10,000 items, `"seq of 10000+"`.
- a string: `"string of <n>"` (characters).
- a number, boolean, keyword, symbol: the kind word alone (`"number"`, `"boolean"`,
  `"keyword"`, `"symbol"`). `nil`: `"nil"`.
- anything else: `"value"`.

**Decided in the API contract.** The shape strings above.

### `(render-bounded value limit) => {:text string :over-limit? boolean :chars long}`

Serves 085, 086, 087. Renders like `render`, into a writer that stops at `limit`
characters. `:text` is the first `limit` characters when the rendering is longer, else
the whole rendering. `:chars` is `(count :text)`. `:over-limit?` is true exactly when
the rendering is longer than `limit`. The renderer never realizes a lazy value past
the limit: `(render-bounded (range) 100)` ends with `:over-limit? true`. It does not
throw. `limit` is a non-negative integer.

Test seam for 066: `(= (render v) (render v))` for equal inputs.

---

## Namespace `clj-surgeon.code-index-ingest`

Serves 015-024 (the pure parts), 018, 019, 041. Does file work only through its
arguments. `clj-kondo` runs in process.

### `fx-names`

A set of strings: `#{"swap!" "reset!" "spit" "transact" "transact!" "sh" "shell" "process"
"delete-file" "send" "put!" ">!" "future" "println"}`.

### `(kondo-config lint-as) => map`

Serves 018, 019. `lint-as` is a map of symbol to symbol (may be nil or empty). Returns
the clj-kondo `:config` map for one analysis:

```clojure
{:analysis {:arglists true :keywords true :locals false}
 :linters  {<every clj-kondo linter key> {:level :off} ...}   ; non-empty
 :lint-as  <lint-as, or {} when nil>
 :output   {:canonical-paths false}}
```

No `:hooks` key, or `:hooks {}`. Test seams: `(get-in cfg [:analysis :locals])` is false,
`(every? #(= :off (:level %)) (vals (:linters cfg)))` is true and the map is not empty,
`(empty? (:hooks cfg))` is true, `(:lint-as cfg)` equals the input.

**Decided in the API contract.** Only the keys above are fixed. The implementer picks
the full list of linter keys (every key of clj-kondo's default config `:linters`).
The implementer must also stop clj-kondo from loading `.clj-kondo/config.edn` by itself
(for example `:config-dir` set to a directory that does not exist, or an equivalent),
so that no workspace `:hooks` entry runs. The only workspace config that applies is
the `:lint-as` that the caller passes in.

### `(throws? source-text) => boolean`

Serves 023. True exactly when the text holds a list whose head is the unqualified
symbol `throw` or `ex-info`. String literals and `;` comments do not count.

- `(throws? "(defn g [] (throw (ex-info \"x\" {})))")` => true
- `(throws? "(defn g [] (ex-info \"x\" {}))")` => true
- `(throws? "(defn g [] (throw-it 1))")` => false
- `(throws? "(defn g [] \"(throw\")")` => false
- `(throws? "(defn g [] ; (throw x)\n 1)")` => false
- `(throws? "(defn g [] (clojure.core/throw x))")` => false

**Decided in the API contract.** Qualified heads do not count. The symbol must end at
whitespace, `)`, `]`, `}` or the end of the text.

### `(file-result file-input) => file result`

Serves 017-024, 041. `file-input`:

```clojure
{:path "src/a/b.clj"        ; string, required
 :text "(ns a.b) ..."        ; string, required
 :mtime 1792000000000       ; long, required (copied to the result)
 :size 211                  ; long, required (copied)
 :sha256 "…"                ; string, required (copied)
 :lint-as {}}               ; map symbol->symbol, optional
```

Returns a file result (see "Data shapes"). `:path`, `:mtime`, `:size`, `:sha256` are
copied unchanged. It runs `clj-kondo.core/run!` with `:lint ["-"]` on `:text`
(`with-in-str`), `:filename` `:path`, and `(kondo-config lint-as)`. It does not touch
the file system. A text with a syntax error gives a partial or empty result, not an
exception.

Throws:
- `{:error-type :index-unavailable :path p :cause "<message>"}` when clj-kondo itself
  throws. The cold-build refusal uses this type (035).
- `{:error-type :invalid-file-input :path p}` when a required key is missing or has the
  wrong type. (**Decided in the API contract.** This type is internal. It never reaches
  a tool output: `mcp-query-clojure` treats it as `index-unavailable`.)

---

## Namespace `clj-surgeon.code-index`

Pure, except the lock helper. Serves 025-029.

### `default-bounds`

See "Bounds map".

### `empty-index`

The value (a def, not a function):

```clojure
{:files {} :namespaces {} :vars {} :callers {} :kw-users {} :uses-by-target {}
 :skipped [] :read-complete? true :built-ms 0}
```

### `(apply-ingest index ingest) => index`

Serves 025-028. Pure. `ingest`:

```clojure
{:upsert  [<file result> ...]     ; optional, default []
 :remove  [<path> ...]            ; optional, default []
 :skipped [<skipped entry> ...]   ; optional; when the key is present it REPLACES :skipped
 :built-ms 812}                   ; optional; when present it replaces :built-ms
```

Steps, in one pure computation: remove each path of `:remove` (its file entry, its var
records and its namespace entry), then for each file result of `:upsert` remove what
that path had before and add its entry, namespace and vars. Then rebuild `:callers`,
`:kw-users` and `:uses-by-target` from all var records and file uses. `:read-complete?`
is `(empty? (:skipped result))`. A path in `:remove` that the index does not hold is
ignored. A path in both `:remove` and `:upsert` ends up present (upsert wins).

Properties tests rely on:
- Order independence (028): for any permutation of `:upsert`,
  `(= (apply-ingest i {:upsert p1}) (apply-ingest i {:upsert p2}))`. This holds because
  the maps and sets are unordered, the `:uses-by-target` vectors are sorted, and a
  qname defined in two files goes to the smaller path.
- `(apply-ingest index {})` equals `index`, except that `:read-complete?` is recomputed.
- Incremental equals batch: `(apply-ingest (apply-ingest e {:upsert [a]}) {:upsert [b]})`
  equals `(apply-ingest e {:upsert [b a]})` for files with distinct paths.
- The input `index` is not changed (immutable maps).

Sorting of `:uses-by-target` vectors: by `[:file :line :from (name :kind)]`, with a nil
`:from` first.

### `(stale-plan index discovered) => {:ingest [path ...] :remove [path ...]}`

Serves 030, 031. `discovered` is the `:files` of a discovery result: a vector (or seq) of
maps with at least `:path :mtime :size`. Returns `:ingest`, the sorted paths that are
new, or whose `:mtime` or `:size` differs from the index file entry. Returns `:remove`,
the sorted paths that the index holds and `discovered` does not. A file with the same
mtime and size is in neither list. A path in the index `:skipped` list but now present
and normal is in `:ingest` (it is new to `:files`).

### Lock helper

#### `(new-entry) => entry`

A workspace entry: `{:index (atom empty-index) :lock <java.util.concurrent.locks.ReentrantLock> :cold? (atom true)}`.
A map with exactly these three keys.

#### `(with-ingest-lock entry f) => (f)`

Serves 029. Acquires the entry lock, calls the zero-argument `f`, releases the lock in a
`finally`, and returns the value of `f`. A second thread that calls `with-ingest-lock`
on the same entry blocks until the first call returns. A different entry does not block.
Reads of `@(:index entry)` never take the lock.

**Decided in the API contract.** The lock is reentrant; a nested call from the same
thread does not deadlock. No other function in this namespace takes the lock. The caller
commits with `(reset! (:index entry) next-index)` inside `f`.

---

## Namespace `clj-surgeon.code-index-discovery`

Serves 009-014, 015, 016, 017, 019. This namespace reads the file system (the fast lane
uses temp dirs under `java.io.tmpdir`).

### `(discover root opts) => discovery`

`root`: absolute canonical directory string. `opts`: the bounds map (partial is fine; it
uses `:max-files` and `:max-file-bytes`, defaults from `code-index/default-bounds`).

```clojure
{:files [{:path "src/a/b.clj" :abs "/…/src/a/b.clj" :mtime 1792000000000 :size 211} ...]
 :skipped [<skipped entry> ...]
 :read-complete? true
 :lint-as {a.macro/defthing clojure.core/def}}    ; symbol->symbol
```

`:files` is sorted by `:path` and holds normal files only (a too-large file is in
`:skipped`). `:mtime` is `lastModifiedTime` in milliseconds. `:lint-as` comes from
`(read-lint-as root)`.

Rules:
- Build files (009, 010). The root, and each directory below the root at depth at most 3
  that holds a `deps.edn`, `bb.edn` or `project.clj`, skipping directories whose name
  starts with `.` and the names `node_modules` and `target`. `deps.edn` and `bb.edn` are
  read with `clojure.edn/read-string` (never evaluated). Source paths are `:paths`, and
  `["src"]` for `deps.edn` with no `:paths`. `project.clj` is read with
  `clojure.edn/read-string` as its first form, a `(defproject name version & kvs)` list; the source
  paths are the `:source-paths` value, and `["src"]` by default. A build file that
  `clojure.edn` cannot read contributes only its `test` and `tests` directories.
  Next to each build file, the directories `test` and `tests` are source paths when
  they exist.
- Source files (009): all `.clj`, `.cljs` and `.cljc` files under the source paths.
- Confinement (011, 012). A source path whose realpath lies outside `root` is dropped. A
  directory or file symbolic link inside a source path whose realpath is outside `root`
  is not followed and not read. Links that stay inside `root` are followed once.
- Cap (013). The walk counts every source file found (too-large files included). When
  the count reaches `:max-files`, the walk stops, and `:skipped` gets the entry
  `{:reason :file-cap :cap <max-files>}`. `:read-complete?` is false.
- Size (014). A file with a size over `:max-file-bytes` is not in `:files` and not read.
  `:skipped` gets `{:file p :reason :too-large :size n}`. `:read-complete?` is false.
- A path that two source paths both reach appears once.
- `:read-complete?` is `(empty? :skipped)`.

Throws: `{:error-type :invalid-workspace-root :root root}` when `root` is not an
existing directory. (The router refuses first, so this is a defect guard.)

**Decided in the API contract.** The depth limit of 3 for build files, the skip list of
directory names, and `["src"]` as the default of `deps.edn` are not in the specs.

### `(read-lint-as root) => map`

Serves 019. Reads `<root>/.clj-kondo/config.edn` with `clojure.edn/read-string` and
returns its `:lint-as` map. Everything else, including `:hooks`, is dropped. Missing
file, unreadable file or non-map `:lint-as` gives `{}`. The file is never evaluated.

### `(sha256 x) => string`

Serves 017. `x`: a byte array or a string (UTF-8 bytes). Returns 64 lowercase hex
characters. `(sha256 "")` is
`"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"`.

### `(read-stable path opts) => result`

Serves 015, 016, 017. `opts`:

```clojure
{:stat-fn (fn [path] {:mtime long :size long})   ; default: reads the file attributes
 :read-fn (fn [path] byte-array)                 ; default: Files/readAllBytes
 :max-attempts 3}                                ; default 3
```

Algorithm: up to `:max-attempts` rounds. In a round, call `stat-fn` (s1), then `read-fn`,
then `stat-fn` (s2). When `s1` and `s2` are equal in `:mtime` and `:size`, return the
success. Otherwise start another round.

Success: `{:ok true :bytes <byte-array> :mtime (:mtime s2) :size (:size s2) :sha256 (sha256 bytes) :attempts n}`.
Failure after the last round: `{:ok false :reason :unstable :attempts 3}`. A throw of
`stat-fn` or `read-fn` (for example `IOException`) gives
`{:ok false :reason :unreadable :message "<ex message>"}` at once, with no further round.
`read-stable` does not throw. Test seams: a counting `stat-fn` shows 2 calls per round,
so 6 calls and 3 `read-fn` calls for a file that never settles.

---

## Namespace `clj-surgeon.code-index-pull`

Pure. Serves 047-053.

### `(pull index pattern eid) => map`

`index`: an index map. `pattern`: a vector of attribute keywords and join maps. `eid`: a
vector `[:var/qname "a.b/f"]`, `[:ns/name "a.b"]` or `[:kw/name "scope"]`; the second
element is a string (a symbol is also accepted and converted to its string).

Returns a map with metadata `{:kind :pull}`. Keys are the attributes of the pattern that
the entity has, as a plain map (no ordering promise; the renderer fixes the order).
Attributes with no value (the entity does not have it, or `nil`) are left out. `false`
is a value and stays.

**Entity attributes.**
- Var: `:var/qname :var/name :var/ns :var/file :var/start :var/end :var/kind
  :var/private? :var/arglists :var/doc :var/intent :var/source :var/throws? :var/fx
  :var/external?`. `:var/external?` is `true` for an external var (a qname in `:callers`
  or `:uses-by-target` with no var record) and absent otherwise. An external var has
  `:var/qname`, `:var/name`, `:var/ns` and `:var/external? true` only.
- Namespace: `:ns/name :ns/doc :ns/file`.
- Keyword: `:kw/name`.

An attribute that belongs to another entity type is left out (no refusal).

**Joins** (a map `{join sub-pattern}` in the pattern). The value is always a vector.
- `:var/calls` (from a var): the callee entities, sorted by qname. A callee with no var
  record is returned as `{:var/qname q :var/external? true}` (053), with the sub-pattern
  applied to its attributes (so a sub-pattern of `[:var/name]` gives `{:var/name "q"}`;
  **Decided in the API contract.** the id key and `:var/external? true` are always added
  for an external callee, whatever the sub-pattern says, so that it stays recognizable).
- `:var/_calls` (from a var): the callers, sorted by qname. An external var has callers
  too.
- `:var/uses-kw`, `:var/reads-kw` (from a var): keyword entities sorted by name.
- `:var/_uses-kw`, `:var/_reads-kw` (from a keyword): the vars, sorted by qname.
- `:var/in-ns` (from a var): a vector of one namespace entity. (**Decided in the API
  contract.** A join is always a vector, also for one namespace.)
- `:ns/_in-ns` and `:var/_in-ns` (from a namespace): its vars, in source order.
- A join that does not apply to the entity type is left out.

**Recursion** (050, 051, 052). A join may hold a positive integer `n` in place of the
sub-pattern: `[:var/qname {:var/_calls 2}]`. The parent pattern (the whole vector that
holds the join) applies to the joined entities, and the join in them recurses with
`n-1`. At `n` = 0 the recursive join is left out of the entity. The number `n` counts
hops. Rules:
- `n` greater than `max-pull-depth` (3), or the symbol `...`: throws `pull-too-deep`.
- `n` less than 1, a non-integer, or a join map with more than one key: throws
  `invalid-expression`.
- Cycle (052): during a recursive join, a joined entity that is already on the path from
  the root (the root counts) is returned as its id map only (`{:var/qname q}`), with no
  further descent. This rule applies to recursive joins only.

**Decided in the API contract.** `...` can only reach `pull` inside a quoted pattern,
for example `(pull '[{:var/calls ...}] [:var/qname "a.b/f"])`, because the fence denies
an unquoted `...` as an unknown symbol first. Quoted forms pass admission.

**Wildcard.** `'*` and any attribute that is not a keyword or a join map throw
`invalid-expression` (**Decided in the API contract**; the specs do not define a wildcard).

Throws (ex-data keys beyond `:error-type`):
- `:invalid-expression` (047): `eid` is not exactly a 2-element vector with one of the
  three id keys and a string or symbol value; or the pattern is not a vector; or the
  pattern holds an invalid element. `{:reason <kw>}` with `:eid` or `:pattern`.
- `:unknown-namespace` (054): `[:ns/name s]` names a namespace that is not in
  `:namespaces`. `{:name "x.y" :nearest ["a.b" ...]}`. `:nearest` is at most 5 indexed
  namespace names, ordered by Levenshtein distance to the name, then by name. It is `[]`
  when the index holds no namespace.
- `:unknown-var` (055): `[:var/qname s]` is not in `:vars` and not an external qname.
  `{:qname "a.b/zzz" :ns "a.b" :ns-known? true}`.
- `:pull-too-deep` (051): `{:depth n-or-"..." :pattern <the pattern argument> :eid eid}`.
- A keyword entity `[:kw/name s]` that no var uses is not an error: it returns an entity
  with only what the pattern asks (`:kw/name s`), and its reverse joins are `[]` (left out).

Example on the fixtures (`all-files` applied to `empty-index`):

```clojure
(pull idx [:var/qname :var/private? {:var/calls [:var/qname]}] [:var/qname "a.b/g"])
;; => {:var/qname "a.b/g" :var/private? true
;;     :var/calls [{:var/qname "z.external/q" :var/external? true}]}
(pull idx [:var/qname {:var/calls 3}] [:var/qname "a.b/f"])
;; a.b/f -> c.d.e/h -> a.b/f: the second a.b/f is {:var/qname "a.b/f"} (on the path)
```

---

## Namespace `clj-surgeon.code-index-functions`

Pure over an index. Serves 036-046, 054, 055, 081.

### `(bound-symbols) => ['namespaces 'skeleton 'form 'refs 'pull 'help]`

A vector of exactly these six symbols, in this order (036).

### Plain functions (the first argument is the index map)

All return plain data (037): maps, vectors, strings, numbers, booleans, keywords, nil.
Every map is a fresh map (not a reference into the index). None takes a path.

- `(namespaces index) => [{:kind :ns :ns "a.b" :file "src/a/b.clj" :vars 2 :doc "…"} ...]`
  Sorted by `:ns`. `:vars` is a count. `:doc` is the full docstring, absent when none
  (038).
- `(skeleton index ns) => vector` of var maps. `ns` is a symbol or string. Order: by
  `:start`. Metadata `{:kind :skeleton :ns "a.b" :doc "The a.b namespace."}` (`:doc` absent
  when none). A var map has exactly the keys `:kind :qname :ns :name :file :start :end
  :arglists :private? :calls :fx :throws?`, plus `:intent` when the var has one (039, 040).
  `:kind` is `:var`. Throws `:unknown-namespace` (054).
  Example, `(skeleton idx 'a.b)` first element:
  `{:kind :var :qname "a.b/f" :ns "a.b" :name "f" :file "src/a/b.clj" :start 4 :end 8
    :arglists ["[x]" "[x y]"] :private? false :calls ["a.b/g" "c.d.e/h"] :fx ["swap!"]
    :throws? false :intent "Does f."}`.
- `(form index sym-or-syms) => [{:kind :form :qname :file :start :end :source} ...]`
  A symbol, a string, or a vector of them. Qualified. One map for each, in the order
  given (042). Throws `:unknown-var` (055) for a qname with no var record (an external
  var is also unknown here). An unqualified symbol, or an element that is not a symbol or
  string, throws `:invalid-expression`.
- `(refs index sym) => vector`, `(refs index sym opts) => vector`
  Use maps `{:kind :use :file :line :ns :from :use-kind}` for every use of `sym` in
  `:uses-by-target` (043). `:ns` is the namespace of the enclosing var. `:from` is the bare
  name of the enclosing var, or `nil`. `:use-kind` is a keyword. Sorted by
  `[:file :line :from (name :use-kind)]`. `opts`: `{:include-tests true|false}`, default
  `true`. With `false`, each use whose `:file` has a directory segment `test` or `tests`
  is left out (044). Unknown keys of `opts` are ignored; a non-map `opts` throws
  `:invalid-expression`. `sym` known when it has a var record or is a key of
  `:uses-by-target` or `:callers` (so an external var works, **Decided in the API
  contract**). A symbol that occurs only in a string, comment or quoted form is not in
  the index (045), so `refs` of it throws `:unknown-var` unless it is also a real var.
  Throws `:unknown-var`.
- `(pull index pattern eid)`: `code-index-pull/pull`.
- `(help) => string`, `(help fn-name) => string` (046). `fn-name` is a symbol or string.
  `(help)` is the blocks of the six functions in `bound-symbols` order, joined with
  `"\n\n"`. A block is:

  ```
  (skeleton ns)
    The vars of a namespace, one var map each, in source order.
    Example: (skeleton 'a.b)
  ```

  Line 1 is `(<name> <args>)`; the last line starts with `  Example: `. `(help 'form)` is
  the block of `form` only. An unknown name gives the string
  `"no such function: <name>. Functions: namespaces, skeleton, form, refs, pull, help"`
  and does not throw (**Decided in the API contract**). `help` takes no index.

### `(bindings index touched) => {symbol fn}`

The SCI bindings for `user`. `touched` is an atom holding a set of path strings. The map
has the six symbols of `bound-symbols`; each value is a function of the expression's
arguments only (no index argument), closed over `index`. Each call adds to `touched` the
`:file` of every map it returns, in this way (033):

- `namespaces`, `skeleton`, `form`, `refs`: the `:file` of each returned map.
- `pull`: the `:file` of every var whose `:var/qname` appears anywhere in the result, and
  of every namespace whose `:ns/name` appears, looked up in `index`.
- `help`: none.

Test seam: `(let [t (atom #{})] ((get (bindings idx t) 'skeleton) 'a.b) @t)` equals
`#{"src/a/b.clj"}`.

---

## Namespace `clj-surgeon.code-index-fence`

Pure. Serves 075-080, 078, 081.

### `(read-expression text) => form`

Serves 075. Reads exactly one form with the SCI reader (`sci.core/parse-string`-style, no
reader eval, no `#=`). Returns the form as data. Never evaluates anything.

Throws `{:error-type :invalid-expression :reason r :message m}`:
- `:reason :empty`: no form (empty text, whitespace, only comments).
- `:reason :multiple`: more than one form (`"(+ 1 2) (+ 3 4)"`).
- `:reason :unreadable`: the reader fails (`"(+ 1"`, `"#=(+ 1 2)"`, `"#<x>"`). `:message` is
  the reader message.
`text` that is not a string throws `:invalid-expression` with `:reason :empty`.

### `denied-symbols`

A set of symbols that admission always refuses: `#{require in-ns ns eval load-string
load-file slurp spit future agent atom alter-var-root set! new}`. Plus these classes that
admission refuses by shape (a test iterates both):

- interop: a symbol that starts with `.` (`.length`), a qualified symbol whose namespace is
  not `user` and not a bound alias (`System/exit`, `Math/abs`), and `new`;
- a dynamic var: any symbol of the form `*name*` (`*out*`, `*ns*`, `*1` is not a dynamic var
  but is not on the allowlist either, so it is denied as unknown).

### `(admit form defs) => nil | denial`

Serves 076, 077, 078, 080, 081. `form`: a form from `read-expression`. `defs`: a set of
symbols that the session has `def`d. Walks the form without evaluating any part of it
(078). Returns `nil` when every symbol is admitted, else the first denial:

```clojure
{:error-type :denied-symbol
 :symbol "slurp"                       ; string
 :bound ["namespaces" "skeleton" "form" "refs" "pull" "help"]}
```

A symbol is admitted when it is: a local (bound by `fn`, `let`, `loop`, `for`, `defn`
arguments and so on), one of `code-index-functions/bound-symbols`, a symbol of the pure
core list (the `pure-core-symbols` of `clj-surgeon.edit-dsl` plus `def`, `defn`, `str`, `keys`,
`vals`, `sort-by`, `frequencies`, `group-by`, `name`, `namespace`, `count`, the macros of
the expansion list, and `user/<bound>`), a symbol in `defs`, or inside a `quote` form
(a quoted form is data and is not walked). Everything else is denied: `slurp`, `.getName`,
`System/exit`, `new`, `Class/member`, `(require ...)`, `in-ns`, `ns`, `eval`, `load-string`,
`load-file`, `spit`, `future`, `agent`, `atom`, `alter-var-root`, `set!`, `*out*`, any
unknown symbol (`foo`), and symbols in a `def` value or inside nested `fn` bodies.

**Decided in the API contract.**
- `admit` returns the denial as data. It does not throw. The caller turns it into a
  refusal.
- The allowlist is the one that the design names. The implementer may extend
  `edit-dsl`'s lists or copy them; `edit-dsl/sandbox-admission` itself is private and
  stays unchanged in slice A.
- Quoted data is not walked, so `(count '[slurp])` is admitted. `...` is therefore
  reachable only inside a quoted pull pattern.
- The first denial in a left-to-right, depth-first walk is the one reported.

### `(def-names form) => [symbol ...]`

The simple or qualified symbol names of every `def` and `defn` form in `form` (nested
ones included), in walk order, not walking quoted data. `(def-names '(do (def a 1) (defn b [] 2)))`
=> `[a b]`. The forms `defn-`, `defmacro`, `defonce`, `declare`, `defmulti` are not in the
allowlist, so `admit` already denies them.

### `(check-def form defs max-defs) => nil | refusal-data`

Serves 073, 080. `defs`: the set of symbols already `def`d in the session. Examines the
`def-names` of `form`. Returns `nil` when all is well, else the first problem:

- A qualified name (`(def a/b 1)`), a name that is a bound function (`(def skeleton 1)`),
  or a name of the pure core list (`(def map 1)`):
  `{:error-type :denied-symbol :symbol "a/b" :bound [...]}` (080).
- A def that makes `(count (into defs names))` exceed `max-defs`:
  `{:error-type :session-full :defs <current count> :limit max-defs}` (073).
  Re-defining a name that `defs` already holds does not add to the count.

---

## Namespace `clj-surgeon.code-index-session`

Serves 067-074, 079, 082. Holds SCI contexts. Pure of the file system.

### `(sessions config) => sessions`

`config`: `{:max-contexts 32 :idle-ms 7200000 :max-defs 64 :clock (fn [] millis)}`. All
optional (defaults from `default-bounds`; clock defaults to `System/currentTimeMillis`).
Returns a sessions object (a map holding atoms). The test injects a clock:

```clojure
(let [t (atom 0)
      s (sessions {:max-contexts 2 :idle-ms 1000 :clock #(deref t)})] ...)
```

### `(checkout! sessions session-id workspace-root) => {:handle h :reset? bool}`

Serves 067, 070, 071. Finds or creates the context for the key `[session-id workspace-root]`.

- `session-id` nil: returns a fresh context that is not stored; `:reset?` false (067).
- First, drop every stored context whose last use is `idle-ms` or more before `(clock)`
  (idle is `>=`). Dropped keys are remembered.
- Known key: return its handle, set its last use to `(clock)`, move it to most recently
  used, `:reset?` false.
- New key: create a context. If the stored count is now above `max-contexts`, drop the
  least recently used one. If the key is in the remembered dropped keys, `:reset?` is true
  and the key leaves that memory; else false.
- `workspace-root` is part of the key: the same session id with another root is a
  different context (067).

**Decided in the API contract.** The dropped-key memory holds at most `32 * max-contexts`
keys, oldest first out. A session that is evicted and also forgotten gets `:reset? false`.

The handle `h` is an opaque map with at least `:session-id`, `:workspace-root`; it holds a
context and the set of `def`d names. Do not read other keys.

### `(eval-in-session sessions handle form bindings) => result`

Serves 068-069, 072-074, 078-080. `form`: one form from `read-expression`. `bindings`: the
map from `code-index-functions/bindings`. It runs, in this order:

1. `(code-index-fence/admit form defs)`: a denial throws.
2. `(code-index-fence/check-def form defs max-defs)`: a problem throws. A throw leaves the
   context as it was (080, 073).
3. Evaluation in the SCI context of the handle (`sci/init` with `:classes {}` and the
   `user` namespace holding only `bindings`; no host classes and no other namespace, 079).
   A `def` writes into the handle's context only (069).

Returns:

```clojure
{:value <the value; for a (def x v) as the final form, the value v>
 :def-name "x"             ; string when the final form of the expression is (def x …) or (defn x …), else nil
 :defs ["x" "y"]}          ; sorted names the context holds after this call
```

Throws:
- `{:error-type :denied-symbol :symbol s :bound [...]}`
- `{:error-type :session-full :defs n :limit max-defs}`
- `{:error-type :evaluation-error :message "<message>"}` for any other throwable of
  evaluation. The message is the original, uncut. If the throwable (or a cause in its
  `ex-cause` chain) is an ex-info from a bound function with `:error-type` in
  `#{:unknown-namespace :unknown-var :pull-too-deep :invalid-expression}`, that ex-info is
  re-thrown as it is, with its ex-data (SCI wraps host exceptions; the implementer unwraps
  them).

A value that a session `def`d is available in a later `eval-in-session` of the same handle
(068), is absent from another handle (069), and is gone after the context is dropped (071).

**Decided in the API contract.** `bindings` is passed on every call, because the functions
close over the index of that request. The implementer installs them into the long-lived
context for the call (for example with `sci/merge-opts`). A function value held by a
`def` from an earlier call reads the index of the call where it was bound, which is
allowed (the LLD: "a value held from an earlier call can be older than the index").
Only data that a `def` holds is supported; the tests do not `def` a function of the
bound set.

### `(run-with-timeout ms f) => (f)`

Serves 082. Runs the zero-argument `f` on a worker thread (a daemon thread) and waits `ms`
milliseconds. Returns the value of `f`. If `f` throws, `run-with-timeout` throws the same
throwable. At the limit it interrupts the worker and throws
`{:error-type :timeout :limit-ms ms}`, even if the worker does not stop.

### Inspection helpers

- `(context-count sessions) => long`: the number of stored contexts.
- `(session-keys sessions) => [[session-id root] ...]`: stored keys, sorted by `pr-str`.
- `(handle-defs handle) => [string ...]`: sorted names the handle's context holds.

---

## Namespace `clj-surgeon.mcp-query-clojure`

Serves 001-008, 030-035, 056, 066, 067, 071, 072, 075, 084-088.

### Tool map

```clojure
query-clojure-tool
;; => {:id :query-clojure
;;     :name "query_clojure"
;;     :description <string>
;;     :schema query-clojure-schema
;;     :output-schema query-clojure-output-schema
;;     :annotations {:title "Query Clojure" :read-only true :destructive false
;;                   :idempotent true :open-world false :return-direct false}
;;     :outcome-classes #{:read-success :typed-refusal}
;;     :structured? true
;;     :tool-fn #'handle-query-clojure}
```

`query-clojure-schema`:

```clojure
{:type "object" :additionalProperties false
 :properties {"expression" {:type "string"} "workspace_root" {:type "string"}}
 :required ["expression"]}
```

**Decided in the API contract.** `:outcome-classes` is a key of the tool map (001), because
`relation-census-tool` has no such key and the tool registry for the `:full` profile must
carry it. The implementer finds where `relation-census-tool` is registered and adds
`query-clojure-tool` to the `:full` profile only. If the registry already derives outcome
classes elsewhere, the implementer keeps the key in this map as well, so a test can read it.

### Request config (the `config` argument)

A map. Keys the code reads:

| Key | Meaning |
|---|---|
| `:project-root` | Default workspace root (as for the other tools). |
| `:workspace-router` | Optional. As for `relation_census`. Built from `config` when absent. |
| `:code-index/bounds` | Optional partial bounds map, merged over `default-bounds` (088). |
| `:code-index/registry` | Optional atom holding `{root entry}`. Default: one global atom. A test passes a fresh `(atom {})`. |
| `:code-index/sessions` | Optional sessions object. Default: one global object. A test passes `(sessions {...})` with a fake clock. |

### `(execute-request! config params) => result`, `(execute-request! config params opts) => result`

`params`: the tool arguments as a map with string or keyword keys (it is normalized through a
JSON round trip, as for `relation_census`). `opts`:

```clojure
{:session-id "s1"          ; the MCP session id, or nil/absent: a fresh context used once (067)
 :ingest-fn  (fn [file-input] file-result)   ; default code-index-ingest/file-result
 :discover-fn (fn [root bounds] discovery)    ; default code-index-discovery/discover
 :stat-fn  (fn [path] {:mtime :size})         ; passed to read-stable
 :read-fn  (fn [path] bytes)                  ; passed to read-stable
 :now-ms   (fn [] millis)}                    ; default System/currentTimeMillis; used for built_ms
```

All keys are optional. Test seams:

- A **failing ingest**: `:ingest-fn (fn [_] (throw (ex-info "boom" {})))` gives the refusal
  `index-unavailable` with a `remedy` that contains `"boom"`.
- A **clock**: `:now-ms` gives `built_ms` as the difference of two calls, so
  `(let [t (atom [0 812])] ...)` can fix `index.built_ms` at 812. The session clock is
  separate: the `sessions` object has its own `:clock`.
- **Freshness** without a disk: `:discover-fn` returns a `discovery` map and `:read-fn` /
  `:stat-fn` give bytes. A second request with a changed `:mtime` in the discovery shows the
  path in `reingested`.
- **Session id**: `:session-id` in `opts` only. `execute-request!` never reads it from `params`
  (the schema has no such field, and an extra field is refused by 002).

`execute-request!` returns the result map (no `elapsed_ms`; `mcp-operation/invoke!` adds it, 006).

**Order of work** (decided below): shape check, expression length, workspace routing,
`read-expression`, `admit`, session checkout and `check-def`, index refresh, evaluation and
rendering under the time limit, result build.

**Decided in the API contract.** The order lets fence refusals (`invalid-expression`,
`denied-symbol`) cost no disk read. Spec 030 still holds: the stale check runs before
evaluation. The shape check and the length check come before workspace routing, so a refusal
for them has no `workspace_root` key.

### Success result (003, 033, 072)

```clojure
{:ok true
 :operation "query-clojure"
 :value "<text>"
 :value_chars 123                       ; (count value)
 :session {:defs ["s"] :reset false}    ; names sorted; reset is a boolean, always present
 :files [{:path "src/a/b.clj" :sha256 "…"} ...]   ; the touched files, sorted by path
 :index {:files 3 :vars 4 :skipped 0 :built_ms 812}
 :reingested ["src/a/b.clj"]            ; sorted paths ingested by the stale check
 :read_complete true
 :skipped []                            ; the index :skipped entries as maps with string values
 :workspace_root "/abs/root"}
```

**Decided in the API contract.**
- `:files` holds only files of which a bound function returned data (033); the sha256 is the
  one recorded at ingest (from the index).
- On the cold build, `:reingested` is `[]` (the build is reported by `index.built_ms`). After
  that, a new, changed or removed file appears in `:reingested`; a removed path is listed too.
- `:skipped` is always present at the top level; each entry has string `reason`, and `file` and
  `size` when known. `index.skipped` is the count.
- `value` for a final value that is a `(def x …)`: `(render-def "x" v)`.
- All keyword values are strings in the output (`"call"`, `"too-large"`).

### Refusal result (004, 005)

```clojure
{:ok false
 :operation "query-clojure"
 :error_type "unknown-var"
 :error "<message>"
 :next_call {:tool "query_clojure" :expression "(skeleton 'a.b)" :workspace_root "/abs/root"}
 ;; or :remedy "<text>"  -- exactly one of the two keys
 :workspace_root "/abs/root"}           ; present once the root is routed
```

A refusal map has no key other than: `ok operation error_type error next_call|remedy
workspace_root`. (`elapsed_ms` is added later by the finalizer.)

`next_call` is a flat map, as for `relation_census`: `:tool "query_clojure"`, `:expression`,
and `:workspace_root` only when the request had one (it carries the request value verbatim).
Rule (005): before a refusal is returned, if `(dissoc next_call :tool)` equals the request
params (after normalization), the `next_call` is dropped and a `remedy` is given instead.

**Refusal table** (error_type, trigger, continuation):

| error_type | Trigger | Continuation | Specs |
|---|---|---|---|
| `invalid-request` | Params not a map, `expression` absent or not a string, `workspace_root` present and not a string, or any other field | `remedy` | 002 |
| `invalid-workspace-root` | Router refusal | `remedy` | 008 |
| `invalid-expression` | `read-expression` throws; `pull` eid or pattern invalid | `remedy` with the reader message | 047, 075 |
| `expression-too-large` | `(count expression)` over `:max-expression-chars` | `remedy` | 084 |
| `denied-symbol` | `admit` or `check-def` denial | `remedy` that names the symbol and the six bound functions | 076, 077, 080 |
| `unknown-namespace` | thrown by `skeleton` or `pull` | `next_call` `(skeleton 'NEAREST)` with the first of `:nearest`; `remedy` when `:nearest` is empty | 054 |
| `unknown-var` | thrown by `form`, `refs`, `pull` | `next_call` `(skeleton 'NS)` when `:ns-known?`, else `remedy` | 055 |
| `pull-too-deep` | depth over 3 or `...` | `next_call` `(pull '<pattern with every depth and ... replaced by 1> <eid>)` printed with `pr-str` | 051 |
| `session-full` | `check-def` | `remedy` | 073 |
| `timeout` | `run-with-timeout` | `remedy` | 082 |
| `evaluation-error` | other throwable; `:error` is the message cut to `:max-error-chars` | `remedy` | 083 |
| `result-too-large` | render over the bound | see below | 085-087 |
| `index-unavailable` | cold build or re-ingest throws | `remedy` that names the cause | 035 |
| `server-not-initialized` | `handle-query-clojure` before `init!` | `remedy` | none (guard) |

**Decided in the API contract.**
- Spec 002 does not name the error_type for an unknown field. It is `invalid-request`.
  `expression` absent or not a string is also `invalid-request`.
- The nearest-namespace `next_call` uses the single nearest name. The full `:nearest` list
  (up to 5 names) goes in the `error` text.
- `result-too-large`. The limit is `:max-skeleton-chars` when the final value is a skeleton
  (a vector with metadata `{:kind :skeleton}`), else `:max-result-chars`. A skeleton over the
  limit: `remedy` (087). Another value over the limit: a `next_call` when one exists (086):
  (a) the expression is `(form ...)` and the value has two or more form maps: `(skeleton 'NS)`
  for the namespace of the first form map; (b) the expression is `(refs 'X)` or
  `(refs 'X opts)` with `:include-tests` not false: `(refs 'X {:include-tests false})`.
  Otherwise `remedy`. The 005 rule applies.
- `timeout` bounds evaluation and rendering together (082): both run inside one
  `run-with-timeout`.
- A refusal never changes the index, except that files ingested before the refusal stay in
  the index (the index is only ever replaced with a complete next map, 025).

### Behavior of the stale check (030, 031, 034, 035)

`(refresh-index! entry root bounds opts) => {:index map :reingested [path ...]}` is public.
It runs `discover-fn`, `stale-plan`, then for each path to ingest `code-index-discovery/read-stable`
and `ingest-fn`, then one `apply-ingest` and one `reset!`, all inside `with-ingest-lock`. Files
that `read-stable` reports as `:unstable` or `:unreadable` go to `:skipped`, not to `:upsert`. The
`:skipped` of the ingest is the discovery skipped entries plus those. With an empty index it uses
a pool of `:pool-size` workers and sets `:built-ms`. A throw of `ingest-fn` aborts before
the `reset!` (the index is as before) and the request refuses with `index-unavailable`.

### `(handle-query-clojure exchange params callback)`

The MCP callback handler (a Var). It calls `mcp-operation/invoke!` with `:execute` (the
`execute-request!` call with `{:session-id (session-id-of exchange)}`), `:summarize` `summary`,
and `:callback`. With no live config (`init!` not called) it returns the
`server-not-initialized` refusal.

#### `(session-id-of exchange) => string | nil`

**Decided in the API contract.** If `exchange` is a map, `(:session-id exchange)`. Otherwise
the implementer reads the MCP session id from the server exchange object (for the transport
in use) and returns nil when there is none. A test passes `{:session-id "s1"}`.

### `(init! config)`

Sets the live config atom, as `relation-census/init!` does (nil disarms it).

### `(summary result) => string`

The one summary rendering (006).

- Success: the `value` text, a newline, then
  `;; <value_chars> chars · <n> file(s) · <reingested count> re-ingested · <mcp-operation/format-elapsed-ms elapsed_ms>`.
- Refusal: `query_clojure refused · <error_type> · <elapsed>\n<error>\nnothing was written`.

---

## Spec coverage map

| Specs | Where |
|---|---|
| 001, 002, 003, 004, 005, 006, 008 | `mcp-query-clojure` |
| 007 | `mcp-query-clojure` (no write; tests check a temp workspace is unchanged) |
| 009-014 | `code-index-discovery/discover`, `read-lint-as` |
| 015, 016, 017 | `code-index-discovery/read-stable`, `sha256` |
| 018, 019 | `code-index-ingest/kondo-config`, `code-index-discovery/read-lint-as` |
| 020-024, 041 | `code-index-ingest/file-result`, `throws?`, `fx-names`; `code-index-render/first-sentence` |
| 025-028 | `code-index/apply-ingest`, `empty-index` |
| 029 | `code-index/with-ingest-lock` |
| 030-035 | `mcp-query-clojure/refresh-index!`, `execute-request!`, `code-index/stale-plan` |
| 036-046 | `code-index-functions` |
| 047-053 | `code-index-pull/pull` |
| 054, 055 | `code-index-functions`, `code-index-pull`, refusals in `mcp-query-clojure` |
| 056-066, 074 | `code-index-render` |
| 067-073 | `code-index-session` |
| 075-083 | `code-index-fence`, `code-index-session`, `mcp-query-clojure` |
| 084-088 | `mcp-query-clojure`, `code-index-render/render-bounded`, `code-index/default-bounds` |
| 089, 090 | Battery lane only (`{:lane :battery}`); no function of its own |
