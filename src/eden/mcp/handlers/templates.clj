(ns eden.mcp.handlers.templates
  "Template operations for MCP"
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.edn :as edn]
            [eden.mcp.api :as api]))

(defn list-templates
  "List available templates with their requirements"
  [config _]
  (try
    (let [site-root (-> (:site-edn config) io/file (.getParentFile))
          api-config (assoc config :site-root site-root)
          templates (api/list-templates api-config)
          formatted (if (empty? templates)
                      "No templates found."
                      (str "Available Templates (" (count templates) "):\n\n"
                           (str/join "\n\n"
                                     (map (fn [t]
                                            (str "• " (:name t) "\n"
                                                 "  Path: " (:path t)))
                                          templates))))]
      {:content [{:type "text" :text formatted}]})
    (catch Exception e
      {:error (.getMessage e)})))

(defn preview-template
  "Preview a template with sample data"
  [config {:keys [template data]}]
  (try
    ;; Parse EDN data if it's a string
    (let [parsed-data (if (string? data)
                        (try
                          (edn/read-string data)
                          (catch Exception e
                            (throw (ex-info "Invalid EDN in data"
                                            {:data data
                                             :error (.getMessage e)}))))
                        data)

          site-root (-> (:site-edn config) io/file (.getParentFile))
          api-config (assoc config :site-root site-root)
          result (api/preview-template api-config {:template-name template
                                                   :data (merge {:lang :no
                                                                 :strings {}
                                                                 :pages {}
                                                                 :eden/current-year (str (.getYear (java.time.LocalDate/now)))}
                                                                parsed-data)})]
      (if (:html result)
        {:content [{:type "text" :text (:html result)}]}
        {:error (str "Template not found: " template)}))
    (catch Exception e
      {:error (.getMessage e)})))
