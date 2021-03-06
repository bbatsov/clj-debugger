(ns debugger.repl
  (:require [clojure.pprint :refer [pprint]]
            [clj-time.core :as t]
            [clojure.string :as s]
            [debugger.config :refer :all]
            [debugger.formatter :refer [non-std-trace-element?]]
            [debugger.commands :refer [help-message
                                       print-full-source
                                       print-short-source
                                       print-trace]]))

(defn prompt-fn [fn-symbol break-line signal-val]
  (if-not @signal-val
    (printf "%s:%s=> " fn-symbol break-line)))

(defn eval-fn [break-ns break-line fn-symbol trace signal-val return-val cached-cont-val locals-fn cont-fn form]
  (do
    (condp re-find (clojure.string/trim (str form))
      #"\(h\)|\(help\)" (do
                          (help-message)
                          (println))

      #"\(w\)" (do
                 (print-short-source break-line fn-symbol))

      #"\(whereami\)" (do
                        (print-full-source break-line fn-symbol))

      #"\(l\)|\(locals\)" (do
                            (binding [*print-length* *locals-print-length*]
                              (pprint (locals-fn))))

      #"\(c\)|\(continue\)" (do
                              ;; break one more time
                              (reset! signal-val :stream-end)
                              (reset! cached-cont-val (cont-fn)))

      #"\(wtf\)" (do (print-trace (fn [[_ s]] (non-std-trace-element? s)) trace)
                   (println))

      #"\(wtf\?+\)" (do
                      (print-trace trace)
                      (println))

      #"\(q\)|\(quit\)" (do
                          (reset! signal-val :stream-end)
                          (reset! *last-quit-at* (t/now))
                          (println "Quitting debugger..."))

      ;; TODO: require & use from ns + in *ns*
      (do
        ;; break one more time
        (println)
        (reset!
          return-val
          (let [orig-ns (ns-name *ns*)]
            (try
              (in-ns break-ns)
              (binding [*locals* (locals-fn)]
                (eval
                  `(let ~(vec (mapcat #(list % `(*locals* '~%)) (keys *locals*)))
                     ~form)))
              (finally (in-ns orig-ns)))))))))


;; TODO use readline/jline
(defn read-fn [signal-val request-prompt request-exit]
  ;; (println "> Read-fn with" (pr-str request-prompt) "and" (pr-str request-exit))
  (or ({:line-start request-prompt :stream-end request-exit}
       (or (deref signal-val)
           (clojure.main/skip-whitespace *in*)))
      (let [input (read)]
        ;; (println "> Read-fn got:" (seq (.getBytes "asd")))
        (clojure.main/skip-if-eol *in*)
        input)))


(defn caught-fn [e]
  (println "> Start caught-fn")
  (let [ex (clojure.main/repl-exception e)
        tr (.getStackTrace ex)]
    (binding [*out* *err*]
      (println (str (-> ex class .getSimpleName)
                    " " (.getMessage ex) " "
                    (when-not (instance? clojure.lang.Compiler$CompilerException ex)
                      (str " " (if (not-empty tr)
                                 (print-trace tr)
                                 "[trace missing]"))))))))


