(ns eden.init
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]))

(defn- copy-template-files
  "Copy template files from resources to target directory"
  [target-dir]
  (let [template-resource "init-site"
        resource-url (io/resource template-resource)]
    (if-not resource-url
      (throw (ex-info "Eden template not found in resources" {:resource template-resource}))
      (let [resource-path (if (= "jar" (.getProtocol resource-url))
                            ;; Handle JAR resources - TODO: implement for distribution
                            (throw (ex-info "JAR resource copying not yet implemented" {}))
                            ;; Handle file resources (development)
                            (fs/path (.getPath resource-url)))]
        ;; Copy all files from template to target
        (fs/copy-tree resource-path target-dir {:replace-existing true})

        ;; Rename gitignore.template to .gitignore
        (fs/move (fs/path target-dir "gitignore.template")
                 (fs/path target-dir ".gitignore")
                 {:replace-existing true})))))

(defn- update-package-json
  "Update package.json with the actual site name"
  [target-dir site-name]
  (let [package-json-path (str (fs/path target-dir "package.json"))
        package-json (slurp package-json-path)
        updated-json (str/replace package-json "\"eden-site\"" (str "\"" site-name "\""))]
    (spit package-json-path updated-json)))

(defn- init-git-repo
  "Initialize a git repository in the target directory"
  [target-dir]
  (let [git-result (shell/sh "git" "init" :dir (str target-dir))]
    (if (zero? (:exit git-result))
      (println "✓ Initialized git repository")
      (println "⚠ Could not initialize git repository:" (:err git-result)))))

(defn- install-npm-deps
  "Install npm dependencies"
  [target-dir]
  (println "Installing npm dependencies...")
  (let [npm-result (shell/sh "npm" "install" :dir (str target-dir))]
    (if (zero? (:exit npm-result))
      (println "✓ Installed npm dependencies")
      (do
        (println "⚠ Could not install npm dependencies:" (:err npm-result))
        (println "  Run 'npm install' manually in the project directory")))))

(defn create-site
  "Initialize a new Eden site in the current directory"
  []
  (let [target-dir (fs/absolutize ".")
        ;; Normalize to remove trailing dots and get clean directory name
        normalized-dir (fs/normalize target-dir)
        site-name (str (fs/file-name normalized-dir))]
    (println (str "Creating Eden site in " target-dir "..."))

    ;; Check if current directory is not empty
    (when (seq (fs/list-dir target-dir))
      (println "Warning: Current directory is not empty. Files may be overwritten."))

    ;; Create the site
    (copy-template-files target-dir)
    (update-package-json target-dir site-name)
    (init-git-repo target-dir)
    (install-npm-deps target-dir)

    ;; Success message
    (println (str "\n✓ Created Eden site in " target-dir))
    (println "\nTo get started:")
    (println "  npm run dev")
    (println "\nAvailable commands:")
    (println "  npm run dev    - Start development server")
    (println "  npm run build  - Build static site")
    (println "  npm run clean  - Clean output files")))
