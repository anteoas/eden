(ns user
  (:require [eden.core]
            [clojure.datafy]))

(defmacro capture-env
  "Capture local bindings.

  Example:

  (defn adder [x y]
    (user/capture-env)
    (+ x y))

  expands to:

  (defn adder [x y]
    (def x x)
    (def y y)
    (+ x y))

  you can also specify which symbols to capture

  (defn adder [x y]
    (user/capture-env y)
    (+ x y))

  expands to:

  (defn adder [x y]
    (def y y)
    (+ x y))


  Useful for debugging function bodies in the repl."
  ([]
   `(capture-env ~@(keys &env)))
  ([& symbols]
   (cons 'do
         (map (fn [local]
                `(def ~local ~local))
              symbols))))

(when-let [open (requiring-resolve 'portal.api/open)]
  (let [selected (requiring-resolve 'portal.api/selected)
        submit   (comp (requiring-resolve 'portal.api/submit) clojure.datafy/datafy)]

    (defonce p (open {:theme :portal.colors/gruvbox :name "website"}))
    (defonce _add-tap-only-once (add-tap submit))
    (defn selected [] (selected p))
    (defn popen [] (open p))))
