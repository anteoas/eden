(ns eden.mcp.handlers.content
  "Content management handlers for MCP"
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [eden.mcp.api :as api]
            [eden.mcp.simulator :as simulator]
            [eden.loader :as loader]))

(defn list-content
  "List content files with metadata"
  [config params]
  (try
    (let [site-edn (:site-edn config)
          site-file (io/file site-edn)]
      (if (.exists site-file)
        (let [site-root (.getParentFile site-file)
              api-config (assoc config :site-root site-root)
              content (api/list-content api-config params)]
          {:content content})
        ;; Return empty content list if site file doesn't exist
        {:content []}))
    (catch Exception e
      {:error (.getMessage e)})))

(defn read-content
  "Read a content file and return its raw content"
  [config {:keys [path]}]
  (try
    (let [site-root (-> (:site-edn config) io/file (.getParentFile))
          api-config (assoc config :site-root site-root)
          content (api/read-content api-config {:path path})]
      (if content
        {:content [{:type "text" :text content}]}
        {:error (str "File not found: " path)}))
    (catch Exception e
      {:error (.getMessage e)})))

(defn write-content
  "Create or update a content file - simulates first, then writes"
  [config {:keys [path frontmatter content]}]
  (try
    ;; Format the content for simulation
    (let [formatted-content (if (str/ends-with? path ".md")
                              (loader/format-frontmatter frontmatter content)
                              (pr-str (merge frontmatter {:content content})))

          ;; First simulate the change to validate it
          sim-result (simulator/simulate-content-change
                      {:site-edn (:site-edn config)
                       :path path
                       :content formatted-content})]

      (if (:success? sim-result)
        ;; Simulation succeeded, write the file
        (let [site-root (-> (:site-edn config) io/file (.getParentFile))
              api-config (assoc config :site-root site-root)]
          (api/write-content api-config {:path path
                                         :frontmatter frontmatter
                                         :content content})
          ;; Trigger rebuild
          (api/build-site config {:clean false})
          {:content [{:type "text"
                      :text (str "✅ Successfully wrote content to: " path "\n"
                                 "Build completed.\n"
                                 (when (seq (:warnings sim-result))
                                   (str "⚠️ Warnings:\n"
                                        (str/join "\n" (map #(str "  - " %) (:warnings sim-result)))))
                                 "\n\nHTML Preview (first 500 chars):\n"
                                 (when-let [html (:html sim-result)]
                                   (subs html 0 (min 500 (count html)))))}]})

        ;; Simulation failed, return error without writing
        {:content [{:type "text"
                    :text (str "❌ Validation failed for: " path "\n"
                               "Error: " (:error sim-result) "\n"
                               (when (:exception sim-result)
                                 (str "Details: " (ex-message (:exception sim-result)) "\n"))
                               "\nThe file was NOT written to disk.\n"
                               "Please fix the errors and try again.")}]}))

    (catch Exception e
      {:error (.getMessage e)})))

(defn delete-content
  "Delete a content file - triggers rebuild"
  [config {:keys [path confirm]}]
  (if-not confirm
    {:error "Deletion not confirmed"}
    (try
      (let [site-root (-> (:site-edn config) io/file (.getParentFile))
            api-config (assoc config :site-root site-root)
            delete-result (api/delete-content api-config {:path path})]
        (if delete-result
          (do
            ;; Trigger rebuild
            (api/build-site config {:clean false})
            {:content [{:type "text"
                        :text (str "Successfully deleted: " path "\n"
                                   "Build completed.")}]})
          {:error (str "File not found: " path)}))
      (catch Exception e
        {:error (.getMessage e)}))))
