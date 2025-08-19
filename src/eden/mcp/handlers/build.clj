(ns eden.mcp.handlers.build
  "Build operations for MCP"
  (:require [eden.mcp.api :as api]))

(defn build-site
  "Trigger a site build"
  [config {:keys [clean]}]
  (try
    (api/build-site config {:clean clean :mode :prod})

    {:content [{:type "text"
                :text "Build completed successfully."}]}

    (catch Exception e
      {:error (.getMessage e)})))

(defn rebuild-site
  "Rebuild the site (used internally after content changes)"
  [config]
  (try
    (api/build-site config {:clean false :mode :prod})
    {:success true}
    (catch Exception e
      {:success false :error (.getMessage e)})))

(defn get-build-status
  "Get current build status and warnings"
  [config _]
  (try
    (let [status (api/get-build-status config)]
      {:content [{:type "text"
                  :text (str "Build status: " (:status status))}]})
    (catch Exception e
      {:error (.getMessage e)})))
