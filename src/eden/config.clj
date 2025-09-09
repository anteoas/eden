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
          (or lang-prefix "/")
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
          (if lang-prefix
            (str lang-prefix "/index.html")
            "/index.html")
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
  (let [lang-config (:lang config)]
    (or (some (fn [[code cfg]] (when (:default cfg) code)) lang-config)
        (first (keys lang-config)))))
