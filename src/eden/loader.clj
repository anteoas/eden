(ns eden.loader
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [markdown.core :as md]
            [sci.core :as sci]
            [eden.config :as config])
  (:import [java.io File]))

(defn load-template
  "Load a single template file. Returns template data or function."
  [template-file]
  (let [path (.getPath template-file)]
    (cond
      (str/ends-with? path ".edn")
      (edn/read-string (slurp template-file))

      (str/ends-with? path ".clj")
      (let [code (slurp template-file)]
        (sci/eval-string code {:namespaces {'clojure.string {:as 'str}}}))

      :else nil)))

(defn load-templates
  "Load all templates from a directory"
  [templates-dir]
  (when (.exists templates-dir)
    (into {}
          (map (fn [file]
                 (let [name (-> (.getName file)
                                (str/replace #"\.(edn|clj)$" "")
                                keyword)]
                   [name (load-template file)]))
               (filter #(re-matches #".*\.(edn|clj)$" (.getName %))
                       (file-seq templates-dir))))))

(defn parse-markdown
  "Parse markdown file with metadata. Supports two formats:
   1. EDN format: {:key value ...} followed by ---
   2. Legacy format: key: value lines at start
   Returns map with metadata and :markdown/content."
  [content]
  (let [trimmed (str/trim content)
        lines (str/split-lines content)]
    (cond
      ;; Empty or whitespace only
      (str/blank? trimmed)
      {:markdown/content ""}

      ;; EDN format - starts with { or [ or (
      (or (str/starts-with? trimmed "{")
          (str/starts-with? trimmed "[")
          (str/starts-with? trimmed "("))
      (if-let [divider-idx (str/index-of content "---")]
        (let [metadata-str (subs content 0 divider-idx)
              ;; Skip the --- and any following newline
              content-start (+ divider-idx 3)
              content-start (if (and (< content-start (count content))
                                     (= \newline (nth content content-start)))
                              (inc content-start)
                              content-start)
              content-str (if (< content-start (count content))
                            (subs content content-start)
                            "")]
          (try
            (let [metadata (edn/read-string metadata-str)]
              (if (map? metadata)
                (assoc metadata :markdown/content content-str)
                ;; Not a map - treat entire content as markdown
                {:markdown/content content
                 :eden/parse-warning (str "EDN frontmatter must be a map, got: " (type metadata))}))
            (catch Exception e
              ;; Parse error - treat entire content as markdown with warning
              {:markdown/content content
               :eden/parse-warning (str "Invalid EDN frontmatter: " (.getMessage e))})))
        ;; No divider but starts with EDN-like syntax - treat as content
        {:markdown/content content})

      ;; Legacy format - key: value lines  
      (and (seq lines) (re-matches #"^[^:]+:.*" (first lines)))
      (let [metadata-lines (take-while #(re-matches #"^[^:]+:.*" %) lines)
            content-start (+ (count metadata-lines)
                             (if (and (< (count metadata-lines) (count lines))
                                      (str/blank? (nth lines (count metadata-lines))))
                               1 0))
            content-lines (drop content-start lines)

            metadata (reduce (fn [m line]
                               (let [[_ k v] (re-matches #"^([^:]+):\s*(.*)$" line)]
                                 (if k
                                   (assoc m (keyword (str/trim k)) (str/trim v))
                                   m)))
                             {}
                             metadata-lines)]
        (assoc metadata :markdown/content (str/join "\n" content-lines)))

      ;; No metadata - just content
      :else
      {:markdown/content content})))

(defn format-frontmatter
  "Format EDN frontmatter and content back into markdown format."
  [frontmatter content]
  (str (pr-str frontmatter)
       "\n---\n"
       content))

(defn- load-content-file
  "Load a single content file and convert markdown to HTML."
  [file]
  (let [path (str file)]
    (cond
      (str/ends-with? path ".edn")
      (edn/read-string (slurp (str file)))

      (str/ends-with? path ".md")
      (let [parsed (parse-markdown (slurp (str file)))]
        ;; Convert markdown to HTML immediately
        (if (:markdown/content parsed)
          (-> parsed
              (assoc :content/html (md/md-to-html-string (:markdown/content parsed)))
              (dissoc :markdown/content)) ; Remove raw markdown to save memory
          parsed))

      :else nil)))

(defn- path-to-content-key
  "Convert a file path to a content keyword.
   Examples:
     'landing.md' -> :landing
     'products/logistics.md' -> :products.logistics
     'news/2024-04-15-partnerskap.md' -> :news.2024-04-15-partnerskap"
  [path]
  (when (and path (not (str/blank? path)))
    (let [;; Remove file extension
          without-ext (str/replace path #"\.[^.]+$" "")
          ;; Replace / with .
          with-dots (str/replace without-ext "/" ".")]
      (when (not (str/blank? with-dots))
        (keyword with-dots)))))

(defn- load-all-content-files
  "Recursively load all content files from the content directory.
   Returns a map organized by language and content-key:
   {:no {:landing {...} :products.logistics {...}}
    :en {:landing {...} :about {...}}}
   Each content item includes :content-key for self-reference."
  [root-path]
  (let [content-dir (io/file root-path "content")]
    (when (.exists content-dir)
      (reduce
       (fn [acc lang-dir]
         (if (and (File/.isDirectory lang-dir)
                  (not (str/starts-with? (File/.getName lang-dir) ".")))
           (let [lang-code (keyword (File/.getName lang-dir))
                 ;; Walk the language directory recursively
                 content-files (->> (file-seq lang-dir)
                                    (filter #(File/.isFile %))
                                    (filter #(let [name (File/.getName %)]
                                               (or (str/ends-with? name ".edn")
                                                   (str/ends-with? name ".md"))))
                                    ;; Skip strings files
                                    (remove #(str/starts-with? (File/.getName %) "strings.")))]
             ;; Load each content file
             (assoc acc lang-code
                    (reduce (fn [lang-acc file]
                              (let [;; Get path relative to language dir
                                    relative-path (str (fs/relativize lang-dir file))
                                    content-key (path-to-content-key relative-path)
                                    content (load-content-file file)]
                                (if (and content-key content)
                                  ;; Add :content-key to the content itself
                                  (assoc lang-acc content-key
                                         (assoc content :content-key content-key))
                                  lang-acc)))
                            {}
                            content-files)))
           acc))
       {}
       (.listFiles content-dir)))))

(defn translation-file-path
  "Get the expected path for a language's translation file"
  [root-path lang-code]
  (str (io/file root-path "content" (str "strings." (name lang-code) ".edn"))))

(defn load-translation-strings
  "Load translation strings for a language"
  [root-path lang-code]
  (let [file (io/file (translation-file-path root-path lang-code))]
    (when (.exists file)
      (edn/read-string (slurp file)))))

(defn load-site-data
  "Load site configuration and templates"
  [site-edn-path output-dir]
  (let [site-edn-file (io/file site-edn-path)
        _ (when-not (.exists site-edn-file)
            (throw (ex-info "Site EDN file not found" {:path site-edn-path})))
        site-config (edn/read-string (slurp site-edn-file))
        ;; All paths are resolved relative to site.edn location
        ;; If site.edn has no parent (in current dir), use current dir
        parent-file (or (.getParentFile site-edn-file) (io/file "."))
        site-root (str (fs/absolutize parent-file))
        output-path (str (fs/absolutize (io/file site-root (or output-dir "dist"))))
        ;; Update config with absolute paths
        config (assoc site-config
                      :root-path site-root
                      :output-path output-path)
        ;; Parse strategies
        url-strategy (or (:url-strategy config) :flat)
        url->filepath (config/parse-url-strategy url-strategy)
        page-url-strategy (or (:page-url-strategy config) :default)
        page->url (config/parse-page-url-strategy page-url-strategy)
        ;; Build constants computed once for entire build
        build-constants {:eden/current-year (str (.getYear (java.time.LocalDate/now)))}]
    {:config config
     :default-lang (config/find-default-language config)
     :templates (load-templates (io/file site-root "templates"))
     :content (load-all-content-files site-root)
     :build-constants build-constants
     :url->filepath url->filepath
     :page->url page->url}))
