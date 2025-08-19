(ns eden.config
  (:require [sci.core :as sci]))

(defn parse-url-strategy
  "Parse URL strategy from config into a function"
  [strategy]
  (cond
    (= strategy :flat)
    (fn [{:keys [path]}]
      (str (subs path 1) ".html")) ; /about → about.html

    (= strategy :nested)
    (fn [{:keys [path]}]
      (if (= path "/")
        "index.html"
        (str (subs path 1) "/index.html"))) ; /about → about/index.html

    (keyword? strategy)
    (throw (ex-info "Unknown url-strategy" {:strategy strategy}))

    (symbol? strategy)
    (requiring-resolve strategy)

    (list? strategy)
    (sci/eval-string (pr-str strategy))

    :else
    (throw (ex-info "Invalid url-strategy. Must be :flat, :nested, a symbol, or a function."
                    {:strategy strategy}))))

(defn parse-page-url-strategy
  "Parse page URL strategy from config into a function that generates URLs for pages"
  [strategy]
  (cond
    (or (= strategy :default) (nil? strategy))
    (fn [{:keys [slug lang site-config]}]
      (let [;; Find the configured default language if lang is nil
            default-lang (or (some (fn [[code cfg]]
                                     (when (:default cfg) code))
                                   (:lang site-config))
                             (first (keys (:lang site-config))))
            lang (or lang default-lang)
            default-lang-config (get-in site-config [:lang default-lang])
            is-default-lang (and (= lang default-lang)
                                 (:default default-lang-config))
            lang-prefix (when-not is-default-lang
                          (str "/" (name lang)))]
        (if (empty? slug)
          (str lang-prefix "/")
          (str lang-prefix "/" slug))))

    (= strategy :with-extension)
    (fn [{:keys [slug lang site-config]}]
      (let [;; Find the configured default language if lang is nil
            default-lang (or (some (fn [[code cfg]]
                                     (when (:default cfg) code))
                                   (:lang site-config))
                             (first (keys (:lang site-config))))
            lang (or lang default-lang)
            default-lang-config (get-in site-config [:lang default-lang])
            is-default-lang (and (= lang default-lang)
                                 (:default default-lang-config))
            lang-prefix (when-not is-default-lang
                          (str "/" (name lang)))]
        (if (empty? slug)
          (str lang-prefix "/index.html")
          (str lang-prefix "/" slug ".html"))))

    (keyword? strategy)
    (throw (ex-info "Unknown page-url-strategy" {:strategy strategy}))

    (symbol? strategy)
    (requiring-resolve strategy)

    (list? strategy)
    (sci/eval-string (pr-str strategy))

    :else
    (throw (ex-info "Invalid page-url-strategy. Must be :default, a symbol, or a function."
                    {:strategy strategy}))))

(defn find-default-language
  "Find the default language and assoc it to context"
  [config]
  (let [lang-config (:lang config)
        default-lang (or (some (fn [[code cfg]] (when (:default cfg) code)) lang-config)
                         (first (keys lang-config)))]
    default-lang))

(defn parse-args
  "Parse command line arguments"
  [args]
  (loop [args args
         result {}]
    (if (empty? args)
      ;; If serve is set but mode isn't, default to dev
      (if (and (:serve result) (not (:mode result)))
        (assoc result :mode :dev)
        result)
      (let [arg (first args)]
        (cond
          (= arg "--output-dir")
          (recur (drop 2 args) (assoc result :output-dir (second args)))

          (= arg "--mode")
          (recur (drop 2 args) (assoc result :mode (keyword (second args))))

          (= arg "--serve")
          (recur (rest args) (assoc result :serve true))

          (not (.startsWith arg "--"))
          (recur (rest args) (assoc result :site-edn arg))

          :else
          (do
            (println "Unknown option:" arg)
            (recur (rest args) result)))))))