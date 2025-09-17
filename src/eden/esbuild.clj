(ns eden.esbuild
  (:require [clojure.string :as str]))

(defn args
  "Generate esbuild CLI arguments. 
   Options map can include:
   - :entry-points - vector of entry point files
   - :outfile - output file path
   - :outdir - output directory path
   - :bundle - boolean, whether to bundle
   - :minify - boolean, whether to minify
   - :log-level - string, Disable logging ('verbose', 'debug', 'info', 'warning', 'error', 'silent'), default 'info'
   - :sourcemap - boolean or string ('inline', 'external', 'both')
   - :target - string or vector of targets (e.g. 'es2020' or ['chrome58', 'firefox57'])
   - :platform - string ('browser', 'node', 'neutral')
   - :format - string ('iife', 'cjs', 'esm')
   - :loader - map of file extensions to loader types
   - :external - vector of modules/patterns to mark as external
   - :define - map of global identifiers to replace
   - :pure - vector of function names to mark as pure
   - :watch - boolean, whether to watch for changes
   - :serve - map with :port and optionally :host
   - :metafile - boolean, whether to generate metafile
   - :splitting - boolean, whether to enable code splitting
   - :jsx - string ('transform', 'preserve', 'automatic')
   - :jsx-factory - string, JSX factory function
   - :jsx-fragment - string, JSX fragment function"
  [& {:as options}]
  (let [add-flag (fn [args flag value]
                   (cond
                     (true? value) (conj args (str "--" (name flag)))
                     (false? value) args
                     (string? value) (conj args (str "--" (name flag) "=" value))
                     (keyword? value) (conj args (str "--" (name flag) "=" (name value)))
                     :else args))
        
        add-multi-flag (fn [args flag values]
                         (reduce (fn [acc v]
                                   (conj acc (str "--" (name flag) ":" v)))
                                 args
                                 values))
        
        add-loader (fn [args loaders]
                     (reduce (fn [acc [ext loader]]
                               (conj acc (str "--loader:" (name ext) "=" (name loader))))
                             args
                             loaders))
        
        add-define (fn [args defines]
                     (reduce (fn [acc [k v]]
                               (conj acc (str "--define:" (name k) "=" v)))
                             args
                             defines))
        
        entry-points (vec (:entry-points options []))]
    
    (-> entry-points
        ;; Output options
        (add-flag :outfile (:outfile options))
        (add-flag :outdir (:outdir options))
        
        ;; Basic options
        (add-flag :bundle (:bundle options))
        (add-flag :minify (:minify options))
        (add-flag :sourcemap (:sourcemap options))
        (add-flag :splitting (:splitting options))
        (add-flag :watch (:watch options))
        (add-flag :metafile (:metafile options))
        (add-flag :log-level (:log-level options))
        
        ;; Target and platform
        (add-flag :platform (:platform options))
        (add-flag :format (:format options))
        (cond->
          (string? (:target options)) (add-flag :target (:target options))
          (sequential? (:target options)) (add-flag :target (str/join "," (:target options))))
        
        ;; JSX options
        (add-flag :jsx (:jsx options))
        (add-flag :jsx-factory (:jsx-factory options))
        (add-flag :jsx-fragment (:jsx-fragment options))
        
        ;; Loaders
        (cond->
          (:loader options) (add-loader (:loader options)))
        
        ;; External modules
        (cond->
          (:external options) (add-multi-flag :external (:external options)))
        
        ;; Define replacements
        (cond->
          (:define options) (add-define (:define options)))
        
        ;; Pure functions
        (cond->
          (:pure options) (add-multi-flag :pure (:pure options)))
        
        ;; Serve options
        (cond->
          (:serve options)
          (add-flag :serve (if (map? (:serve options))
                             (str (get-in options [:serve :port] 8000))
                             "8000"))))))


(comment
  (args
   {:entry-points ["src/app.js"]
    :bundle true
    :minify true
    :sourcemap :external
    :outfile "dist/app.js"
    :target ["chrome58" "firefox57"]
    :loader {:.png :file
             :.jpg :file
             :.svg :dataurl}
    :external ["react" "react-dom"]
    :define {:DEBUG "false"
             :API_URL "\"https://api.example.com\""}})

  (args :metafile "/dev/*")
)
