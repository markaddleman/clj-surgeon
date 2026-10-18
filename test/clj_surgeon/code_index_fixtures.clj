(ns clj-surgeon.code-index-fixtures
  "Literal file results for the code index tests.

   Each value has the exact shape that `clj-surgeon.code-index-ingest/file-result`
   returns (see docs/intent/code-index/code-index-api.md). They are written by
   hand, not produced by clj-kondo. The `:sha256` strings are fakes.

   The fixture graph:
     a.b/f   calls a.b/g and c.d.e/h, effect swap!, keyword scope
     a.b/g   private, throws, calls z.external/q (no file defines it)
     c.d.e/h calls a.b/f (a call cycle with a.b/f -> c.d.e/h)
     a.b-test/t calls a.b/f twice: one alias-call and one value use")

(def a-b-file
  {:path "src/a/b.clj"
   :mtime 1792000000000
   :size 233
   :sha256 "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
   :ns "a.b"
   :ns-doc "The a.b namespace."
   :vars
   [{:qname "a.b/f"
     :ns "a.b"
     :name "f"
     :file "src/a/b.clj"
     :start 4
     :end 7
     :kind "clojure.core/defn"
     :private? false
     :arglists ["[x]" "[x y]"]
     :doc "Does f. Then more."
     :intent "Does f."
     :source (str "(defn f\n"
                  "  \"Does f. Then more.\"\n"
                  "  ([x] (g x 1))\n"
                  "  ([x y] (swap! state conj (g x y)) (c.d.e/h x) {:scope y}))")
     :calls ["a.b/g" "c.d.e/h"]
     :fx ["swap!"]
     :uses-kw ["scope"]
     :reads-kw []
     :throws? false}
    {:qname "a.b/g"
     :ns "a.b"
     :name "g"
     :file "src/a/b.clj"
     :start 9
     :end 12
     :kind "clojure.core/defn-"
     :private? true
     :arglists ["[x y]"]
     :source (str "(defn- g\n"
                  "  [x y]\n"
                  "  (when (nil? x) (throw (ex-info \"bad\" {:scope y :x/k 1})))\n"
                  "  (z.external/q x))")
     :calls ["z.external/q"]
     :fx []
     :uses-kw ["scope" "x/k"]
     :reads-kw []
     :throws? true}]
   :uses
   [{:file "src/a/b.clj" :line 6 :from "a.b/f" :target "a.b/g" :kind :call}
    {:file "src/a/b.clj" :line 7 :from "a.b/f" :target "a.b/g" :kind :call}
    {:file "src/a/b.clj" :line 7 :from "a.b/f" :target "c.d.e/h" :kind :call}
    {:file "src/a/b.clj" :line 12 :from "a.b/g" :target "z.external/q" :kind :call}]})

(def c-d-e-file
  {:path "src/c/d/e.clj"
   :mtime 1792000001000
   :size 40
   :sha256 "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
   :ns "c.d.e"
   :vars
   [{:qname "c.d.e/h"
     :ns "c.d.e"
     :name "h"
     :file "src/c/d/e.clj"
     :start 3
     :end 3
     :kind "clojure.core/defn"
     :private? false
     :arglists ["[x]"]
     :source "(defn h [x] (a.b/f x))"
     :calls ["a.b/f"]
     :fx []
     :uses-kw []
     :reads-kw []
     :throws? false}]
   :uses
   [{:file "src/c/d/e.clj" :line 3 :from "c.d.e/h" :target "a.b/f" :kind :call}]})

(def a-b-test-file
  {:path "test/a/b_test.clj"
   :mtime 1792000002000
   :size 90
   :sha256 "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
   :ns "a.b-test"
   :vars
   [{:qname "a.b-test/t"
     :ns "a.b-test"
     :name "t"
     :file "test/a/b_test.clj"
     :start 4
     :end 4
     :kind "clojure.core/defn"
     :private? false
     :arglists ["[]"]
     :source "(defn t [] (b/f 1) (map b/f [1 2]))"
     :calls ["a.b/f"]
     :fx []
     :uses-kw []
     :reads-kw []
     :throws? false}]
   :uses
   [{:file "test/a/b_test.clj" :line 4 :from "a.b-test/t" :target "a.b/f" :kind :alias-call}
    {:file "test/a/b_test.clj" :line 4 :from "a.b-test/t" :target "a.b/f" :kind :value}]})

(def all-files
  [a-b-file c-d-e-file a-b-test-file])
