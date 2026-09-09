;; test/cut_test.cljs — build the command and compare it with the system
;; cut, byte for byte, on stdout, stderr AND exit status.
;;
;; Two behaviours carry most of the weight here and neither is an edge case:
;;
;;   a line with NO delimiter is printed WHOLE, not as an empty field
;;   the default delimiter is a TAB, not a space
;;
;; Both measured against /usr/bin/cut rather than recalled.

(ns cut-test
  (:require [clojure.string :as str] ["fs" :as fs] ["path" :as path] ["os" :as os]))

(def cp (js/require "node:child_process"))

(defn- run [cmd args opts]
  (let [r (.spawnSync cp cmd (clj->js args)
                      (clj->js (merge {:encoding "buffer"} opts)))]
    {:status (.-status r) :out (.-stdout r) :err (.-stderr r)}))

(defn- refuse [message]
  (println (pr-str {:ok false :phase :setup :message message}))
  (.exit js/process 2))

(def amu-home
  (or (.-AMU_HOME js/process.env)
      (let [guess (.resolve path (.cwd js/process) ".." ".." "kotoba-lang" "amu")]
        (when (.existsSync fs (.join path guess "bin" "amu")) guess))))

(def system-cut "/usr/bin/cut")

;; A directory of fixtures, and the cases over them. Each case is an argv,
;; and each is here because it separates a right implementation from a wrong
;; one that passes the others:
;;
;;   one file           -- the basic contract
;;   two files          -- concatenated in ORDER, with nothing added between
;;   the same file twice-- an operand is not deduplicated
;;   an EMPTY file      -- reads as the empty string, which must not end the
;;                         loop the way "past the last operand" does
;;   empty then content -- the same trap from the other side
;;   no trailing newline-- cat adds nothing of its own
;;   binary-ish bytes   -- high bytes survive the round trip
;;   no operands        -- POSIX reads stdin; there is no stdin capability,
;;                         so this asserts what it ACTUALLY does (nothing),
;;                         not what POSIX says
(def fixtures
  {"colon"  "a:b:c\nd:e:f\n"
   "tabs"   "one\ttwo\tthree\n"
   ;; No delimiter at all: cut prints the line WHOLE.
   "nosep"  "nofield\n"
   "empty"  ""
   ;; Two fields, so -f9 asks for one that is not there.
   "short"  "a:b\n"
   ;; A leading and a trailing delimiter: the empty field on each side is a
   ;; field.
   "edges"  ":lead\ntrail:\n"
   "utf8"   "\u65e5:\u672c:\u8a9e\n"
   ;; Five fields, so ranges have room on both sides.
   "wide"   "a:b:c:d:e\n"})

(def cases
  [["-d:" "-f1" "colon"] ["-d:" "-f2" "colon"] ["-d:" "-f3" "colon"]
   ;; Past the last field. This is the case that separates "the remainder is
   ;; the last field" from "the line ran out": before it was fixed, -f4 over
   ;; a three-field line answered `c` while -f1, -f2 and -f3 all agreed.
   ["-d:" "-f4" "colon"] ["-d:" "-f9" "short"]
   ["-d:" "-f2" "nosep"] ["-d:" "-f2" "empty"] ["-d:" "-f2" "short"]
   ["-d:" "-f1" "edges"] ["-d:" "-f2" "edges"] ["-d:" "-f2" "utf8"]
   ;; No -d: the delimiter is a TAB. `colon` has none, so the whole line.
   ["-f1" "tabs"] ["-f2" "tabs"] ["-f2" "colon"]
   ["-d:" "-f2" "missing"]
   ;; --- field LISTS and RANGES ---------------------------------------
   ;; The output is ascending and de-duplicated regardless of how the list
   ;; is written, so `3,1` and `1,1` are the two cases that separate "test
   ;; each field in order" from "walk the list as given".
   ["-d:" "-f1,3" "wide"] ["-d:" "-f3,1" "wide"] ["-d:" "-f1,1" "wide"]
   ["-d:" "-f2-4" "wide"] ["-d:" "-f2-" "wide"] ["-d:" "-f-3" "wide"]
   ;; Every field, and a range that starts past the end.
   ["-d:" "-f1-" "wide"] ["-d:" "-f9-" "wide"]
   ;; A field past the end inside a list is SKIPPED, not an error, and does
   ;; not produce a stray delimiter.
   ["-d:" "-f2,9" "wide"] ["-d:" "-f4,5" "short"]
   ;; A DESCENDING range selects nothing -- and still prints a line.
   ["-d:" "-f3-1" "wide"]
   ;; A list over a line with no delimiter still prints the whole line.
   ["-d:" "-f1,3" "nosep"]
   ;; A list over the empty and the multi-byte fixtures.
   ["-d:" "-f1,3" "utf8"] ["-d:" "-f1,3" "empty"]
   ;; Malformed lists: two different diagnostics and exit 1, compared as
   ;; bytes like every other case. `0` and the empty list are "may not
   ;; include zero"; a non-number and a second dash are "illegal list
   ;; value". Before this the command had ONE message, spelled `[-cf]`
   ;; where cut spells it `[-bcf]`, and no case exercised it at all.
   ["-d:" "-f0" "wide"] ["-d:" "-fx" "wide"] ["-d:" "-f1-x" "wide"]
   ["-d:" "-f1-2-3" "wide"] ["-d:" "-f1," "wide"] ["-d:" "-f,1" "wide"]
   ["-d:" "-f-" "wide"]])

(when-not amu-home (refuse "set AMU_HOME to an amu checkout"))
(let [amu (.join path amu-home "bin" "amu")
      packager (.join path amu-home "scripts" "package-command.cljs")]
  (when-not (.existsSync fs amu) (refuse (str "no amu at " amu)))
  (when-not (.existsSync fs packager) (refuse (str "no packager at " packager)))
  (when-not (.existsSync fs system-cut) (refuse (str "no " system-cut " to compare against")))
  (let [tmp (.mkdtempSync fs (.join path (.tmpdir os) "org-ieee-wc-"))
        src (.resolve path (.cwd js/process) "cut" "core.kotoba")
        policy (.join path tmp "policy.edn")
        kexe (.join path tmp "cut.kexe")
        blob (.join path tmp "cut.bin")
        exe (.join path tmp "cut")
        exe-big (.join path tmp "cut-big")]
    (.writeFileSync fs policy "{:allow #{[:cap/call 35] [:cap/call 37] [:cap/call 38] [:cap/call 39]}}" "utf8")
    ;; The fixtures live in the tree the binary is packaged for. The native
    ;; loader refuses a relative request outright, so operands are absolute.
    (let [data (.join path tmp "data")]
      (.mkdirSync fs data)
      (doseq [[name content] fixtures]
        (.writeFileSync fs (.join path data name)
                        content
                        "utf8")))
    (let [c (run "node" [amu "compile" src "--target" "aarch64-macos" "--jvm-free"
                         "--policy" policy "--output" kexe] {})]
      (when (not= 0 (:status c))
        (refuse (str "compile failed: " (str (:err c)) (str (:out c))))))
    (let [e (run "node" [amu "extract-native" kexe "--symbol" "main" "--output" blob] {})
          _ (when (not= 0 (:status e)) (refuse (str "extract failed: " (str (:err e)))))
          report (str (:out e))
          offset (second (re-find #":offset (\d+)" report))]
      (when-not offset (refuse (str "no :offset in the extract report: " report)))
      ;; TWO binaries from the same code: one with the loader's default
      ;; string-arena budget and one with a raised budget. The pair is what
      ;; makes the ceiling below a measurement instead of a claim -- a single
      ;; binary could only show that some size works and some does not, not
      ;; that the bound is the arena and that it moves.
      ;; Fuel and arena are constants of the binary, so they are packaged
      ;; here rather than supplied at run time. Counting words walks one code
      ;; point at a time, so the guest recursion is as long as the file and
      ;; the default 512 fuel counts almost nothing.
      (doseq [[out extra] [[exe ["--fuel" "50000000" "--pairs" "200000" "--string-pool" "8000000"]]]]
        (let [p (run "nbb" (into [packager "--code" blob "--offset" offset "--isa" "aarch64"
                                  "--allow" "35,37,38,39"
                                  "--fs-scope" (.realpathSync fs (.join path tmp "data"))
                                  "--output" out]
                                 extra) {})]
          (when (not= 0 (:status p)) (refuse (str "package failed: " (str (:err p))))))))
    ;; Now the only thing that matters: run it.
    (let [results
          (for [names cases]
            (let [argv (mapv #(if (str/starts-with? % "-")
                                %
                                (.join path (.realpathSync fs (.join path tmp "data")) %))
                             names)
                  k (run exe argv {})
                  s (run system-cut argv {})
                  ;; stderr is compared too, now that there is a capability
                  ;; that can write it. Without this the `-n 0` case would
                  ;; pass on an empty stdout and the right exit status while
                  ;; saying nothing -- which is what it did before wire 39.
                  same? (and (= (.toString (:out k) "base64") (.toString (:out s) "base64"))
                             (= (.toString (:err k) "base64") (.toString (:err s) "base64"))
                             (= (:status k) (:status s)))]
              {:argv names :ok same? :kotoba (.toString (:out k) "utf8")
               :kotoba-err (.toString (:err k) "utf8")
               :system (.toString (:out s) "utf8")
               :exit [(:status k) (:status s)]}))
          bad (remove :ok results)]
      (doseq [r results]
        (println (str (if (:ok r) "  ok   " "  FAIL ")
                      (pr-str (:argv r))
                      " -> " (pr-str (:kotoba r))
                      (when-not (:ok r) (str " but " system-cut " says " (pr-str (:system r))
                                             " exits " (pr-str (:exit r))))))) 
      (println (pr-str {:ok (empty? bad) :cases (count results) :failed (count bad)}))
      (.exit js/process (if (seq bad) 1 0)))))