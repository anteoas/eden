(ns eden.mcp.handlers.templates
  "Template operations for MCP"
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [eden.mcp.api :as api]))

(defn list-templates
  "List available templates with their requirements"
  [config _]
  (try
    (let [site-root (-> (:site-edn config) io/file (.getParentFile))
          api-config (assoc config :site-root site-root)
          templates (api/list-templates api-config)]
      {:content [{:type "text"
                  :text (str/join "\n\n"
                                  (map (fn [t]
                                         (str "Template: " (:name t) "\n"
                                              "Path: " (:path t)))
                                       templates))}]})
    (catch Exception e
      {:error (.getMessage e)})))

(defn preview-template
  "Preview a template with sample data"
  [config {:keys [template data]}]
  (try
    (let [site-root (-> (:site-edn config) io/file (.getParentFile))
          api-config (assoc config :site-root site-root)
          result (api/preview-template api-config {:template-name template
                                                   :data (merge {:lang :no
                                                                 :strings {}
                                                                 :pages {}
                                                                 :eden/current-year (str (.getYear (java.time.LocalDate/now)))}
                                                                data)})]
      (if (:html result)
        {:content [{:type "text" :text (:html result)}]}
        {:error (str "Template not found: " template)}))
    (catch Exception e
      {:error (.getMessage e)})))
